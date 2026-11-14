package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.provider.ProviderSkin;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import com.naocraftlab.skins.runtime.*;
import java.util.*;
import java.util.function.*;

final class CpmSourceReplacementProbe {
    record Material(String location, SkinModel model) {}

    static void run(Object target, Object graphics, GalleryNativePassFixture root,
            Runnable reset, Runnable consume, BooleanSupplier hidden, Supplier<Material> material,
            Runnable replaceWorld, Runnable replacePlayer) throws Exception {
        @SuppressWarnings("unchecked") var renderer = (PreviewRenderer<Object>) target;
        var type = target.getClass();
        var originalSession = (EditorPreviewSession) field(target, "session");
        var registrations = new ArrayList<NativePlayerSkinLifecycle.Registration>();
        try (var subject = root.freshOwned()) {
            var storageField = GalleryNativePassFixture.class.getDeclaredField("storage");
            storageField.setAccessible(true);
            var storage = (NclSkinsStorage) storageField.get(subject);
            var account = subject.runtime.snapshot().account().orElseThrow();
            var second = account.skinAssets().get(1);
            boolean providers = Boolean.getBoolean("nclskins.sourceReplacement.providers");
            storage.updateAppearance(account.accountId(), old -> {
                var cape = old.providers().cape();
                if (providers) for (var provider : cape.order())
                    if (provider.canWrite(AppearanceProviders.Component.CAPE)) cape = cape.disable(provider);
                return old.withProviders(new AppearanceProviders(old.providers().skin()
                        .observeMinecraft(new ProviderSkin(second.sha256(), second.variant())), cape));
            });
            var runtime = subject.runtime;
            runtime.dispatchWidget("gallery.providers");
            subject.drainWorkers();
            if (!providers) {
                runtime.dispatchWidget("providers.back");
                runtime.dispatchWidget("gallery.preset." + runtime.snapshot().selectedPresetId().orElseThrow() + ".edit");
                subject.drainWorkers();
            }
            var hostType = CpmPreviewScreenSlice.loadNative(CpmPreviewScreenSlice.availableOwner(), type);
            Object host = hostType.getConstructor().newInstance();
            var registry = new CpmOwnedPreviewDispatchProbe.Registry();
            try (var skins = new PreviewAssetCache<Object>(registry, TextureRegistry.TextureKind.PLAYER_SKIN, DiagnosticSinks.discarding());
                 var capes = new PreviewAssetCache<Object>(registry, TextureRegistry.TextureKind.IMAGE, DiagnosticSinks.discarding())) {
                for (var f : hostType.getDeclaredFields()) {
                    f.setAccessible(true);
                    Object value = switch (f.getName()) {
                        case "runtime" -> runtime;
                        case "skinTextures" -> skins;
                        case "capeTextures" -> capes;
                        case "textureRegistry" -> registry;
                        case "editorRenderer" -> target;
                        case "width" -> 854;
                        case "height" -> 480;
                        case "capabilities" -> new CpmOwnedPreviewDispatchProbe.Capabilities() {
                            @SuppressWarnings({"unchecked", "rawtypes"}) public PreviewRenderer<CpmOwnedPreviewDispatchProbe.Graphics> createEditorPreviewRenderer() { return (PreviewRenderer) target; }
                            public PreviewRenderer<CpmOwnedPreviewDispatchProbe.Graphics> createSimplePreviewRenderer() { return new CpmOwnedPreviewDispatchProbe.RenderTerminal(); }
                        };
                        default -> f.getType() == Map.class ? new HashMap<>() : null;
                    };
                    if (value != null) f.set(host, value);
                }
                boolean immediate = hostType.getName().endsWith("NclSkinsImmediateScreen");
                boolean extraction = hostType.getName().contains("extraction");
                var sync = hostType.getDeclaredMethod(immediate ? "synchronizePreviews" : extraction ? "syncPreviewAssets" : "synchronizePreviewAssets", ViewSpec.class);
                sync.setAccessible(true);
                var draw = immediate ? hostType.getDeclaredMethod("renderPreview", graphics.getClass(), ViewSpec.Preview.class)
                        : hostType.getDeclaredMethod(extraction ? "drawPreviews" : "renderPreviews", graphics.getClass(), ViewSpec.class);
                draw.setAccessible(true);
                var key = hostType.getDeclaredMethod(immediate ? "previewAssetKey" : "skinKey", ViewSpec.Preview.class);
                key.setAccessible(true);
                Material predecessor = null;
                var phases = Boolean.getBoolean("nclskins.sourceReplacement.sameSourceOnly")
                        ? List.of("initial", "registration", "world", "player")
                        : List.of("initial", providers ? "source" : "model", "registration", "world", "player");
                for (String phase : phases) {
                    if (phase.equals("source")) runtime.dispatchWidget("providers.row.MINECRAFT");
                    if (phase.equals("model")) runtime.dispatchWidget("editor.model_choice.slim");
                    subject.drainWorkers();
                    var view = runtime.view(854, 480, 0, 0);
                    sync.invoke(host, view); subject.drainWorkers();
                    view = runtime.view(854, 480, 0, 0);
                    sync.invoke(host, view); subject.drainWorkers();
                    var preview = main(runtime);
                    var handle = skins.handle(key.invoke(null, preview)).orElseThrow();
                    check(registry.registered.containsKey(handle), "SOURCE1 own producer bytes reach native registered cache");
                    var expected = new Material(handle.location(), preview.variant() == com.naocraftlab.skins.core.model.SkinVariant.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC);
                    if (phase.equals("source")) check(predecessor != null && !expected.location().equals(predecessor.location()), "SOURCE1 actual same-Providers-session source replacement");
                    if (phase.equals("model")) check(predecessor != null && expected.model() != predecessor.model(), "SOURCE1 actual same-Editor-session model replacement");
                    var before = renderer.body();
                    boolean nativeReplacement = List.of("registration", "world", "player").contains(phase);
                    PreviewRenderer.PreviewRequest direct = null;
                    if (nativeReplacement) {
                        var project = hostType.getDeclaredMethod("previewAppearance", ViewSpec.Preview.class,
                                TextureRegistry.TextureHandle.class, SkinModel.class, Optional.class);
                        project.setAccessible(true);
                        var own = (PreviewRenderer.PreviewAppearance) project.invoke(host, preview, handle, expected.model(), Optional.empty());
                        direct = ViewHostPolicy.previewRequest(preview, Optional.of(own), Optional.empty()).orElseThrow();
                    }
                    if (phase.equals("registration")) {
                        var registration = NativePlayerSkinLifecycle.pending(handle.location());
                        registration.ready(); registrations.add(registration);
                    }
                    if (phase.equals("world")) replaceWorld.run();
                    if (phase.equals("player")) replacePlayer.run();
                    reset.run();
                    if (direct == null) invokeDraw(draw, host, graphics, immediate, runtime);
                    else renderer.render(graphics, direct);
                    check(field(target, "session") == originalSession && field(target, "gallery") == null,
                            "SOURCE1 actual host must reuse one live Editor/Providers session");
                    check(hidden.getAsBoolean(), "SOURCE1 " + phase + " own first native pass must be hidden before consumer");
                    consume.run();
                    check(expected.equals(material.get()), "SOURCE1 " + phase + " actual consumer owns selected skin/model material");
                    check(renderer.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED,
                            "SOURCE1 " + phase + " hidden replacement consumer cannot grant visible-body authority");
                    check(displayed(runtime) == PreviewRenderer.DisplayKind.UNCONFIRMED, "SOURCE1 hidden replacement cannot grant common controls");
                    controls(runtime, false);
                    check(originalSession.imageVisible(), "SOURCE1 own hidden consumption releases only image preparation");
                    check(!before.current().getAsBoolean(), "SOURCE1 predecessor authority stays retired");
                    reset.run();
                    if (direct == null) invokeDraw(draw, host, graphics, immediate, runtime);
                    else renderer.render(graphics, direct);
                    check(!hidden.getAsBoolean(), "SOURCE1 next separate native pass is visible");
                    consume.run();
                    check(expected.equals(material.get()), "SOURCE1 visible consumer owns same exact selected material");
                    check(renderer.displayKind() == PreviewRenderer.DisplayKind.ORDINARY && displayed(runtime) == PreviewRenderer.DisplayKind.ORDINARY,
                            "SOURCE1 separate visible own consumer grants ordinary/common authority");
                    controls(runtime, true);
                    predecessor = expected;
                    System.out.println("PASS SOURCE1 " + (nativeReplacement ? "compiled projection/direct wrapper lifecycle sibling" : "compiled host/cache replacement")
                            + " -> same live session -> hidden own consumer -> UNCONFIRMED -> separate visible own consumer/common controls: " + phase);
                }
            }
        } finally {
            renderer.observeBody(ignored -> {});
            registrations.forEach(NativePlayerSkinLifecycle.Registration::retire);
        }
    }

    private static ViewSpec.Preview main(ClientRuntime runtime) {
        return runtime.view(854, 480, 0, 0).previews().stream().filter(p -> p.id().equals("providers.preview") || p.id().equals("editor.preview")).findFirst().orElseThrow();
    }

    private static void controls(ClientRuntime runtime, boolean visible) {
        var view = runtime.view(854, 480, 0, 0);
        boolean actual = view.widgets().stream().anyMatch(w -> w.id().startsWith("providers.outer_layer.") || w.id().startsWith("editor.outer_layer."));
        check(actual == visible, "SOURCE1 common outer-layer controls visible=" + visible + " actual=" + actual);
    }

    private static PreviewRenderer.DisplayKind displayed(ClientRuntime runtime) {
        return (PreviewRenderer.DisplayKind) field(runtime, "displayedBodyKind");
    }

    private static void invokeDraw(java.lang.reflect.Method draw, Object host, Object graphics, boolean immediate, ClientRuntime runtime) throws Exception {
        try { draw.invoke(host, graphics, immediate ? main(runtime) : runtime.view(854, 480, 0, 0)); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof RuntimeException error) throw error;
            throw failure;
        }
    }

    private static Object field(Object target, String name) {
        try { var f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
