package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.EditorPreviewSession;
import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.PreviewModelFrames;
import com.naocraftlab.skins.client.PreviewRenderer;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public final class CpmBakedDisplayProbe {
    private static Consumer<Object> enqueue;
    private static Runnable draw = () -> {};
    private static Runnable pop = () -> {};
    static Runnable flushed = () -> {};
    private static Runnable lighting = () -> {};
    private static int draws;
    private static int poses;

    public static void main(String[] args) throws Exception {
        bodyProducer(new PreviewRenderer.PreviewAppearance(
                new com.naocraftlab.skins.client.TextureRegistry.TextureHandle("fixture:baked-display",64,64),
                com.naocraftlab.skins.client.SkinModel.CLASSIC, java.util.Optional.empty(),
                PreviewRenderer.CapeMode.OFF, com.naocraftlab.skins.client.OuterLayerVisibility.allVisible()));
    }

    static void bodyProducer(PreviewRenderer.PreviewAppearance appearance) throws Exception {
        bodyProducer(appearance, null);
    }

    static void bodyProducer(PreviewRenderer.PreviewAppearance appearance, com.naocraftlab.skins.runtime.GalleryNativePassFixture gallery) throws Exception {
        String extraction = "com.naocraftlab.skins.compat.client.identifier.extraction.";
        String submission = "com.naocraftlab.skins.compat.client.identifier.submission.";
        boolean extracting = CpmBakedDisplayProbe.class.getClassLoader().getResource(extraction.replace('.', '/') + "AvatarPipPreviewRenderer.class") != null;
        String prefix = extracting ? extraction : submission;
        String bakedName = prefix + (extracting ? "AvatarPipPreviewRenderer" : "SimplePreviewRenderer");
        if (CpmBakedDisplayProbe.class.getClassLoader().getResource(bakedName.replace('.', '/') + ".class") == null) return;
        String mainName = prefix + (extracting ? "AvatarRenderStatePreviewRenderer" : "SubmissionPreviewRenderer");
        String dispatcherName = prefix + (extracting ? "NclBakedPlayerRenderer" : "BakedPreviewRenderer");
        String targetName = prefix + (extracting ? "NclBakedPlayerTarget" : "BakedPreviewTarget");
        String stateName = prefix + (extracting ? "NclBakedPlayerRenderState" : "BakedPreviewRenderState");
        String transportName = prefix + (extracting ? "NclBakedPlayerSubmission" : "BakedPreviewSubmission");
        if (!extracting) {
            var admissionLoader = new Loader(prefix, mainName, bakedName, dispatcherName, false);
            admissionLoader.live = true;
            var selected = (PreviewRenderer<?>) submissionOwner(admissionLoader,admissionLoader.loadClass(mainName));
            Client.current = new Client();
            check(selected.prepareGalleryBody("fixture:submission-gallery-A", false, () -> true),
                    "actual selected Submission owner must opt in to source-scoped Gallery, not default false");
            closeTarget(selected);
            Object terminalSession = field(selected,"session");
            selected.prepareBody("fixture:ordinary-after-terminal-close",false);
            check(field(selected,"session") == terminalSession, "N3 closed Submission owner cannot recreate ordinary session");
        }
        galleryExit(prefix, mainName, bakedName, dispatcherName, transportName, appearance, gallery);
        physicalLifetime(prefix, mainName, bakedName, dispatcherName, targetName, transportName, extracting, appearance);
        consumerCleanup(prefix, mainName, bakedName, dispatcherName, extracting);
        var loader = new Loader(prefix, mainName, bakedName, dispatcherName, extracting);
        Client.current = new Client();
        var client = Client.current;
        Class<?> mainType = loader.loadClass(mainName);
        Class<?> bakedType = loader.loadClass(bakedName);
        Class<?> stateType = loader.loadClass(stateName);
        Class<?> targetType = loader.loadClass(targetName);
        Class<?> dispatcherType = loader.loadClass(dispatcherName);
        Class<?> transport = loader.loadClass(transportName);
        var replace = transport.getMethod("replace", Object.class, PipState.class);
        var render = mainType.getMethod("render", Graphics.class, PreviewRenderer.PreviewRequest.class);
        var nativePrepare = java.util.Arrays.stream(dispatcherType.getDeclaredMethods())
                .filter(m -> m.getName().equals("prepare") && !m.isBridge()).findFirst().orElseThrow();
        boolean late = nativePrepare.getParameterCount() == 4;
        var request = new PreviewRenderer.PreviewRequest(appearance, 0, 0, 160, 180, 0, 0, 1, PreviewRenderer.PreviewIntent.EDITOR_DRAFT);
        for (String scenario : java.util.List.of("worldless", "failed", "blocked", "timeout", "circuit", "cached-first", "draw-failure", "pose-failure", "late-failure", "pass-failure", "frame-failure", "retire", "closed", "no-texture", "target-closed")) {
            if (!late && java.util.Set.of("late-failure", "pass-failure", "frame-failure").contains(scenario)) continue;
            if (!loader.staticFlush && java.util.Set.of("pass-failure", "frame-failure").contains(scenario)) continue;
            long[] now = {0};
            var session = new EditorPreviewSession(() -> now[0]);
            boolean terminal = java.util.Set.of("failed", "blocked", "timeout", "circuit").contains(scenario);
            client.level = terminal ? new CpmPreviewScopeProbe.Level() : null;
            client.player = terminal ? new CpmPreviewScopeProbe.Avatar(client.level) : null;
            session.beginBody("fixture:baked", terminal, terminal);
            switch (scenario) {
                case "failed" -> session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.FAILED, () -> true));
                case "blocked" -> session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.BLOCKED, () -> true));
                case "timeout" -> { now[0] = java.time.Duration.ofSeconds(60).toNanos(); session.bodyStatus(); }
                case "circuit" -> session.disableLive(new IllegalStateException("synthetic circuit"));
                default -> {}
            }
            Object target = create(targetType);
            Object baked = create(bakedType);
            set(baked, "target", target);
            if (extracting) set(baked, "models", new Models());
            else { set(baked, "wide", new Model()); set(baked, "slim", new Model()); }
            Object main = create(mainType);
            set(main, "session", session);
            set(main, extracting ? "bakedRenderer" : "baked", baked);
            set(main, "diagnostics", com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
            set(main, "accessoryObserver", (Consumer<com.naocraftlab.skins.client.PreviewAccessories>) ignored -> {});
            if (scenario.equals("closed")) set(main, "closed", true);
            Object dispatcher = dispatcherType.getConstructors()[0].getParameterCount() == 0
                    ? create(dispatcherType) : dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
            Object[] queued = {null};
            var graphics = new Graphics();
            enqueue = owner -> queued[0] = call(replace, null, owner, new VanillaState());
            var observed = new java.util.ArrayList<PreviewRenderer.DisplayKind>();
            PreviewRenderer<?> query = (PreviewRenderer<?>) main;
            session.observeBody(ignored -> {
                var kind = query.displayKind();
                if (kind == PreviewRenderer.DisplayKind.ORDINARY) check(poses == 0, "ordinary notification follows pose cleanup");
                observed.add(kind);
            });
            NativePlayerSkinLifecycle.Registration pending = scenario.equals("no-texture") ? NativePlayerSkinLifecycle.pending(appearance.skin().location()) : null;
            draws = 0; poses = 0;
            draw = () -> {
                check(query.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "primary draw alone is not completed display");
                if (scenario.equals("draw-failure")) throw new IllegalStateException("synthetic primary failure");
                if (scenario.equals("retire")) session.beginBody("fixture:replacement", false, false);
            };
            pop = () -> { if (scenario.equals("pose-failure")) throw new IllegalStateException("synthetic pose cleanup failure"); };
            CpmExtractionBodyProbe.bakedBoundaries(
                    () -> { check(query.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "late native flush precedes display commit"); if (scenario.equals("late-failure")) throw new IllegalStateException("synthetic late failure"); },
                    () -> { if (scenario.equals("pass-failure")) throw new IllegalStateException("synthetic pass cleanup failure"); },
                    () -> { if (scenario.equals("frame-failure")) throw new IllegalStateException("synthetic frame cleanup failure"); });
            CpmExtractionBodyProbe.cached = scenario.equals("cached-first");
            Throwable failure = null;
            try {
                call(render, main, graphics, request);
                check(query.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "actual main renderer enqueue is not display");
                if (queued[0] != null) {
                    var completion = stateType.getMethod("completion").invoke(queued[0]);
                    check(completion != null, "actual main fallback transports its scoped completion through withScissor");
                    if (scenario.equals("target-closed")) ((AutoCloseable) target).close();
                    prepare(nativePrepare, dispatcher, queued[0], late);
                }
            } catch (RuntimeException | Error thrown) { failure = thrown; }
            boolean positive = java.util.Set.of("worldless", "failed", "blocked", "timeout", "circuit").contains(scenario);
            check(!positive || failure == null, scenario + " must complete: " + failure);
            check(query.displayKind() == (positive ? PreviewRenderer.DisplayKind.ORDINARY : PreviewRenderer.DisplayKind.UNCONFIRMED), scenario + " actual baked display truth");
            if (positive) {
                check(draws == 1 && observed.stream().filter(k -> k == PreviewRenderer.DisplayKind.ORDINARY).count() == 1, "one primary draw and ordinary transition");
                int notifications = observed.size();
                Object previous = queued[0];
                CpmExtractionBodyProbe.cached = true;
                call(render, main, graphics, request);
                check(previous.equals(queued[0]), "unchanged authority preserves baked state/cache identity");
                prepare(nativePrepare, dispatcher, queued[0], late);
                check(draws == 1 && observed.size() == notifications, "cached unchanged pass does not draw or publish again");
                targetType.getMethod("release", Object.class).invoke(target, dispatcher);
                check(query.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "released target revokes ordinary proof");
                call(render, main, graphics, request);
                prepare(nativePrepare, dispatcher, queued[0], late);
                check(query.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "cached/no-primary return cannot authorize replacement");
            } else if (java.util.Set.of("closed", "no-texture", "cached-first", "target-closed").contains(scenario)) {
                check(draws == 0, scenario + " must not execute a primary draw");
            }
            if (pending != null) pending.retire();
            if (scenario.equals("worldless") && gallery != null) {
                var controller = (com.naocraftlab.skins.client.OuterLayerVisibilityController) loader.loadClass(
                        "com.naocraftlab.skins.compat.client.identifier.IdentifierOuterLayerVisibilityController").getConstructor().newInstance();
                gallery.layerController = controller;
                var providers = gallery.runtime.snapshot().providers();
                var noEffects = gallery.noEffectsCheckpoint();
                check(client.level == null && client.player == null && client.getConnection() == null, "joined native world is absent, not only disconnected");
                check(gallery.runtime.snapshot().selectedPresetId().isEmpty(), "joined no-selected fallback");
                for (String group : new String[]{"head","body","legs"}) for (boolean reverse : new boolean[]{false,true}) {
                    var external = reverse ? com.naocraftlab.skins.client.OuterLayerVisibility.noneVisible() : com.naocraftlab.skins.client.OuterLayerVisibility.allVisible();
                    controller.applyDurable(external);
                    var preview = gallery.runtime.galleryPreviewCandidate().orElseThrow();
                    var callback = gallery.runtime.observePreviewBody(preview, query::displayKind);
                    session.observeBody(callback);
                    mainType.getMethod("prepareBody", String.class, boolean.class).invoke(main, gallery.runtime.previewBodySource(preview), false);
                    CpmExtractionBodyProbe.cached = false;
                    call(render, main, graphics, gallery.request());
                    check(gallery.displayed() == PreviewRenderer.DisplayKind.UNCONFIRMED, "enqueue cannot grant desired layer authority");
                    prepare(nativePrepare, dispatcher, queued[0], late);
                    gallery.layersVisible(true);
                    int saves = client.options.saves, broadcasts = client.options.broadcasts;
                    boolean cape = client.options.isModelPartEnabled(CpmPreviewScopeProbe.Part.CAPE); String hand = client.options.mainHand;
                    gallery.runtime.dispatchWidget("gallery.outer_layer."+group, reverse, com.naocraftlab.skins.runtime.InteractionOrigin.KEYBOARD);
                    gallery.verifyCycle(group, external, reverse);
                    check(client.options.saves == saves+1 && client.options.broadcasts == broadcasts, "worldless one Options save and no broadcast");
                    check(cape == client.options.isModelPartEnabled(CpmPreviewScopeProbe.Part.CAPE) && hand.equals(client.options.mainHand), "worldless cape/main hand preserved");
                    check(providers.equals(gallery.runtime.snapshot().providers()), "worldless providers preserved");
                    check(gallery.runtime.view(854,480,0,0).widget("gallery.done").isPresent(), "worldless clean footer");
                    callback.accept(PreviewRenderer.BodyStatus.ORDINARY);
                    gallery.layersVisible(true);
                    check(!gallery.runtime.view(854,480,0,0).widget("gallery.outer_layer.head").orElseThrow().activationEnabled(),
                            "retained worldless mask has no successor action authority");
                    noEffects.run();
                }
                System.out.println("PASS joined actual baked-worldless pass -> Gallery observer/view/action -> compiled native controller; six fresh forward/reverse cycles; no world/player/connection; synthetic GPU/Options terminals");
            }
            ((AutoCloseable) target).close();
            ((AutoCloseable) dispatcher).close();
            System.out.println("PASS actual baked " + scenario + " late=" + late + " static=" + loader.staticFlush + " draws=" + draws);
        }
        enqueue = null; draw = () -> {}; pop = () -> {};
        CpmExtractionBodyProbe.bakedBoundaries(() -> {}, () -> {}, () -> {});
        CpmExtractionBodyProbe.cached = false;
        Client.current = null;
    }

    private static void galleryExit(String prefix, String main, String baked, String dispatcherName,
            String transport, PreviewRenderer.PreviewAppearance appearance,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture gallery) throws Exception {
        boolean extracting = prefix.endsWith("extraction.");
        String profileField = extracting ? "previewProfile" : "profile";
        String bakedField = extracting ? "bakedRenderer" : "baked";
        var loader = new Loader(prefix, main, baked, dispatcherName, extracting);
        loader.live = !extracting;
        loader.physical = true;
        loader.types.put("com/mojang/blaze3d/textures/GpuTexture", CpmExtractionBodyProbe.LegacyTexture.class);
        Client.current = new Client();
        var type = loader.loadClass(main);
        @SuppressWarnings("unchecked") var renderer = (PreviewRenderer<Graphics>) submissionOwner(loader, type);
        var dispatcherType = loader.loadClass(dispatcherName);
        var nativePrepare = java.util.Arrays.stream(dispatcherType.getDeclaredMethods())
                .filter(m -> m.getName().equals("prepare") && !m.isBridge()).findFirst().orElseThrow();
        boolean late = nativePrepare.getParameterCount() == 4;
        Object dispatcher = late ? create(dispatcherType) : dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
        var replace = loader.loadClass(transport).getMethod("replace", Object.class, PipState.class);
        Object[] queued = {null};
        enqueue = owner -> queued[0] = call(replace, null, owner, new VanillaState());
        var request = new PreviewRenderer.PreviewRequest(appearance,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.EDITOR_DRAFT);
        var materials = CpmExtractionBodyProbe.queuedMaterials(); materials.clear();
        CpmExtractionBodyProbe.cached = false; draw = () -> {}; pop = () -> {};
        CpmExtractionBodyProbe.consumerControl("normal");
        try (var subject = gallery == null ? null : gallery.fresh()) {
            for (String destination : java.util.List.of("providers", "providers-repeat", "editor", "editor-repeat")) {
                CpmExtractionBodyProbe.consumerControl("normal");
                String source = "fixture:exit-gallery-" + destination;
                check(renderer.prepareGalleryBody(source,false, () -> true), "Gallery producer admitted after return: " + destination);
                renderer.renderGallery(new Graphics(), source, request);
                Object galleryState = queued[0];
                Object gallerySession = field(renderer,"session"), galleryProfile = field(renderer,profileField);
                Object galleryClock = field(renderer,"previewClock"), galleryTick = field(renderer,"previewTickGate");
                Object retiredTarget = stateField(galleryState,"target");
                var retiredCompletion = (PreviewModelFrames.Completion) stateField(galleryState,"completion");
                var observed = new java.util.ArrayList<PreviewRenderer.DisplayKind>();
                renderer.observeBody(status -> observed.add(renderer.displayKind()));
                Object[] freshTarget = {null};
                String[] expectedSkin = {appearance.skin().location()};
                CpmExtractionBodyProbe.consumerFrame(loader, () -> {
                    prepareRaw(nativePrepare,dispatcher,galleryState,late);
                    check(materials.size() == 1 && materials.get(0).texture.data != null, "Gallery actual native material before exit");
                    observed.clear();
                    var nonGalleryRequest = request;
                    String nonGallerySource = "fixture:" + destination + "-after-gallery";
                    if (subject != null && destination.equals("providers")) {
                        subject.runtime.dispatchWidget("gallery.providers");
                        var view = subject.runtime.view(854,480,0,0);
                        check(view.screenId().equals("providers") && subject.runtime.galleryPreviewCandidate().isEmpty(), "actual admitted Gallery Providers action leaves Gallery native branch");
                        var preview = view.previews().stream().filter(p -> com.naocraftlab.skins.runtime.ViewHostPolicy.screenOwnedBody(view.screenId(),p)).findFirst().orElseThrow();
                        var player = subject.runtime.previewPlayerAppearance(preview).orElseThrow();
                        var projected = new PreviewRenderer.PreviewAppearance(player.skin(),player.model(),player.cape(),preview.capeMode(),preview.outerLayerVisibility(),preview.capeHasElytra(),false,preview.nativeAccessories());
                        nonGalleryRequest = com.naocraftlab.skins.runtime.ViewHostPolicy.previewRequest(preview,java.util.Optional.empty(),java.util.Optional.of(projected)).orElseThrow();
                        nonGallerySource = subject.runtime.previewBodySource(preview);
                        var callback = subject.runtime.observePreviewBody(preview,renderer::displayKind);
                        renderer.observeBody(status -> { observed.add(renderer.displayKind()); callback.accept(status); });
                    }
                    expectedSkin[0] = nonGalleryRequest.appearance().skin().location();
                    renderer.prepareBody(nonGallerySource,false);
                    renderer.render(new Graphics(),nonGalleryRequest);
                    check(queued[0] != galleryState, "Providers producer enqueues a new state, not stale Gallery queue");
                    freshTarget[0] = stateField(queued[0],"target");
                    try { prepareRaw(nativePrepare,dispatcher,queued[0],late); }
                    catch (RuntimeException | Error failure) { CpmExtractionBodyProbe.resetConsumerControl(); throw failure; }
                    check(materials.size() == 2 && materials.get(1).texture.data.equals(new Identifier(expectedSkin[0])), "Providers actual body material after Gallery exit");
                    check(materials.get(0).texture != materials.get(1).texture && freshTarget[0] != retiredTarget, "fresh non-Gallery physical target, not reopened Gallery owner");
                }, () -> {
                    CpmExtractionBodyProbe.consumerSubmitted();
                    check(!materials.get(0).texture.closed && !materials.get(1).texture.closed, "prepared Gallery and Providers survive consumer draw");
                });
                check(renderer.displayKind() == PreviewRenderer.DisplayKind.ORDINARY && renderer.body().current().getAsBoolean(), "Providers has current ordinary completion authority");
                check(observed.stream().filter(kind -> kind == PreviewRenderer.DisplayKind.ORDINARY).count() == 1, "fresh session publishes through current observer exactly once");
                check(field(renderer,"session") != gallerySession && field(renderer,profileField) != galleryProfile
                        && field(renderer,"previewClock") != galleryClock && field(renderer,"previewTickGate") != galleryTick,
                        "fresh ordinary context after source retirement");
                check(field(renderer,"previewPlayer") == null && field(renderer,"previewLevel") == null, "worldless context carries no retired proxy/world");
                check(!retiredCompletion.current(), "retired Gallery completion loses authority immediately");
                int notifications = observed.size();
                retiredCompletion.accept(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.FAILED, () -> true));
                ((EditorPreviewSession)gallerySession).invalidateDisplayedBody();
                check(renderer.displayKind() == PreviewRenderer.DisplayKind.ORDINARY && observed.size() == notifications, "old completion cannot invalidate or publish into fresh session");
                int[] factories = {0};
                var acquire = retiredTarget.getClass().getMethod("acquire",Object.class,java.util.function.Supplier.class,Class.class);
                try {
                    call(acquire,retiredTarget,new Object(),(java.util.function.Supplier<AutoCloseable>)() -> { factories[0]++; return () -> {}; },AutoCloseable.class);
                    throw new AssertionError("retired target must reject acquisition");
                } catch (IllegalStateException expected) { check(expected.getMessage().equals("Render target is closed") && expected.getSuppressed().length == 0, "strict retired target rejection"); }
                check(factories[0] == 0, "retired owner cannot invoke resource factory");
                check(!materials.get(0).texture.closed, "fence enqueue is not a signal for prepared retired Gallery material");
                CpmExtractionBodyProbe.signalConsumers();
                check(materials.get(0).texture.closes == 1 && !materials.get(1).texture.closed, "separate signal closes retired Gallery exactly once, preserving fresh material");
                var freshMaterial = materials.get(1);
                materials.clear();
                if (destination.equals("editor-repeat")) {
                    boolean[] reentered = {false};
                    renderer.observeBody(status -> { if (!reentered[0]) { reentered[0] = true; closeTarget(renderer); } });
                    check(!renderer.prepareGalleryBody("fixture:close-during-return",false, () -> true) && reentered[0], "reentrant close during ordinary invalidation rejects Gallery recreation");
                    closeTarget(renderer);
                    Object session = field(renderer,"session"), bakedOwner = field(renderer,bakedField);
                    renderer.prepareBody("fixture:forbidden-after-close",false);
                    check(!renderer.prepareGalleryBody("fixture:forbidden-gallery-after-close",false, () -> true), "closed renderer rejects Gallery admission");
                    check(field(renderer,"session") == session && field(renderer,bakedField) == bakedOwner, "closed renderer creates no ordinary context");
                    check(freshMaterial.texture.closes == 1, "closed final non-Gallery material retires exactly once");
                } else {
                    check(renderer.prepareGalleryBody(source + "-return",false, () -> true), "return to Gallery admitted on same live owner");
                    check(freshMaterial.texture.closes == 1, "return to Gallery retires the previous ordinary physical owner");
                    renderer.prepareBody("fixture:return-" + destination,false);
                    check(renderer.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "return requires its own ordinary material completion");
                }
                System.out.println("PASS actual Gallery producer -> prepared native material -> retire -> fresh Providers producer/state -> native prepare/material/ordinary display; return=" + destination + "; old completion, observer, strict factory0, distinct target, consumer/fence signal");
            }
        } finally {
            closeTarget(renderer); ((AutoCloseable)dispatcher).close(); CpmExtractionBodyProbe.signalConsumers();
            materials.clear(); CpmExtractionBodyProbe.resetConsumerControl(); Client.current = null;
            enqueue = null; draw = () -> {}; pop = () -> {};
        }
    }

    private static Object field(Object owner, String name) {
        try { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static Object stateField(Object state, String name) {
        try { var method = state.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(state); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void prepare(java.lang.reflect.Method method, Object owner, Object state, boolean late) {
        try {
            CpmExtractionBodyProbe.consumerFrame(owner.getClass().getClassLoader(), () -> prepareRaw(method, owner, state, late), () -> {});
            CpmExtractionBodyProbe.signalConsumers();
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static void prepareRaw(java.lang.reflect.Method method, Object owner, Object state, boolean late) {
        call(method, owner, late ? new Object[]{state, new CpmExtractionBodyProbe.GuiRenderState(), new CpmExtractionBodyProbe.Features(), 1}
                : new Object[]{state, new CpmExtractionBodyProbe.GuiRenderState(), 1});
    }
    private static void physicalLifetime(String prefix, String main, String bakedName, String dispatcherName,
            String targetName, String transportName, boolean extracting, PreviewRenderer.PreviewAppearance appearance) throws Exception {
        for (String scenario : java.util.List.of("normal", "draw-failure", "reentrant-close", "nested-frame", "fence-failure", "shared-backend")) {
        var loader = new Loader(prefix, main, bakedName, dispatcherName, extracting);
        loader.physical = true;
        loader.types.put("com/mojang/blaze3d/textures/GpuTexture", CpmExtractionBodyProbe.LegacyTexture.class);
        Client.current = new Client();
        Class<?> bakedType = loader.loadClass(bakedName), targetType = loader.loadClass(targetName);
        Class<?> dispatcherType = loader.loadClass(dispatcherName);
        var replace = loader.loadClass(transportName).getMethod("replace", Object.class, PipState.class);
        var render = bakedType.getMethod("render", Graphics.class, PreviewRenderer.PreviewRequest.class);
        var nativePrepare = java.util.Arrays.stream(dispatcherType.getDeclaredMethods()).filter(m -> m.getName().equals("prepare") && !m.isBridge()).findFirst().orElseThrow();
        boolean late = nativePrepare.getParameterCount() == 4;
        Object dispatcher = late ? create(dispatcherType) : dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
        Object[] targets = {create(targetType), create(targetType)}, baked = {create(bakedType), create(bakedType)}, states = new Object[2];
        for (int i = 0; i < 2; i++) {
            set(baked[i], "target", scenario.equals("shared-backend") ? targets[0] : targets[i]);
            if (extracting) set(baked[i], "models", new Models()); else { set(baked[i], "wide", new Model()); set(baked[i], "slim", new Model()); }
            final int index = i;
            enqueue = owner -> states[index] = call(replace, null, owner, new VanillaState());
            var selected = new PreviewRenderer.PreviewAppearance(new com.naocraftlab.skins.client.TextureRegistry.TextureHandle("fixture:baked-" + i,64,64),
                    appearance.model(), java.util.Optional.empty(), PreviewRenderer.CapeMode.OFF, appearance.outerLayerVisibility());
            call(render, baked[i], new Graphics(), new PreviewRenderer.PreviewRequest(selected,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.EDITOR_DRAFT));
        }
        var materials = CpmExtractionBodyProbe.queuedMaterials(); materials.clear();
        CpmExtractionBodyProbe.cached = false; draw = () -> {}; pop = () -> {};
        CpmExtractionBodyProbe.consumerControl(scenario);
        Throwable failure = null;
        try {
            try { CpmExtractionBodyProbe.consumerFrame(loader, () -> {
                prepareRaw(nativePrepare, dispatcher, states[0], late);
                check(materials.size() == 1 && materials.get(0).texture.data != null, "actual baked A material admission");
                if (!scenario.equals("shared-backend")) closeTarget(baked[0]);
                if (scenario.equals("nested-frame")) {
                    try { CpmExtractionBodyProbe.consumerFrame(loader, () -> {}, () -> {}); }
                    catch (Exception error) { throw new AssertionError(error); }
                }
                prepareRaw(nativePrepare, dispatcher, states[1], late);
            }, () -> {
                check(materials.size() == 2, "actual baked A/B blits");
                CpmExtractionBodyProbe.consumerSubmitted();
                check(materials.get(0).texture.data.equals(new Identifier("fixture:baked-0")) && materials.get(1).texture.data.equals(new Identifier("fixture:baked-1")), "baked A/B actual distinct producer material");
                check(!materials.get(0).texture.closed, "queued baked A survives retirement before actual GUI consumer");
                if (scenario.equals("reentrant-close")) { closeTarget(baked[0]); closeTarget(baked[1]); }
                check(!materials.get(1).texture.closed, "baked B reentrant retirement waits for consumer signal");
                if (scenario.equals("draw-failure")) throw new IllegalStateException("synthetic baked consumer failure");
            }); } catch (RuntimeException | Error thrown) { failure = thrown; }
            if (materials.size() != 2) throw new AssertionError("baked failure has admitted both actual native materials", failure);
            if (scenario.equals("shared-backend")) closeTarget(baked[0]);
            closeTarget(baked[1]);
            check(!materials.get(0).texture.closed && !materials.get(1).texture.closed, "baked fence enqueue is not GPU signal");
            CpmExtractionBodyProbe.signalConsumers();
            check(materials.get(0).texture.closes == 1 && materials.get(1).texture.closes == 1, "baked retired A/B close exactly once after signal");
            if (scenario.equals("shared-backend")) check(failure instanceof AssertionError && failure.getMessage().equals("baked A/B actual distinct producer material") && failure.getSuppressed().length == 0, "baked shared-backend exact material sensitivity");
            else if (scenario.equals("draw-failure")) check(failure instanceof IllegalStateException && failure.getMessage().equals("synthetic baked consumer failure") && failure.getSuppressed().length == 0, "baked consumer failure preserved");
            else if (scenario.equals("fence-failure")) check(failure instanceof IllegalStateException && failure.getMessage().equals("synthetic fence enqueue failure") && failure.getSuppressed().length == 0, "baked fence retry preserves original failure");
            else check(failure == null, "baked actual native positive " + failure);
            System.out.println("PASS actual baked A/B queued material -> native GUI consumer -> fence signal -> exact-once retirement " + scenario);
        } finally {
            for (Object target : targets) ((AutoCloseable)target).close();
            ((AutoCloseable)dispatcher).close(); CpmExtractionBodyProbe.signalConsumers(); materials.clear(); CpmExtractionBodyProbe.resetConsumerControl();
        }
        }
    }

    private static void consumerCleanup(String prefix, String main, String bakedName, String dispatcherName, boolean extracting) throws Exception {
        var loader = new Loader(prefix, main, bakedName, dispatcherName, extracting);
        var frame = loader.loadClass("com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame");
        var retain = frame.getMethod("retain", com.naocraftlab.skins.client.ScreenOwnedRenderTarget.class);
        int[] closed = new int[2];
        var targets = new com.naocraftlab.skins.client.ScreenOwnedRenderTarget[]{new com.naocraftlab.skins.client.ScreenOwnedRenderTarget(), new com.naocraftlab.skins.client.ScreenOwnedRenderTarget()};
        for (int i = 0; i < 2; i++) {
            int index = i;
            targets[i].acquire(new Object(), () -> (AutoCloseable) () -> { closed[index]++; throw new IllegalStateException("synthetic retained cleanup " + index); }, AutoCloseable.class);
        }
        CpmExtractionBodyProbe.consumerControl("cleanup");
        try {
            CpmExtractionBodyProbe.consumerFrame(loader, () -> {
                for (var target : targets) { call(retain, null, target); target.close(); }
            }, CpmExtractionBodyProbe::consumerSubmitted);
            check(closed[0] == 0 && closed[1] == 0, "retired cleanup waits for native consumer signal");
            Throwable failure = null;
            try { CpmExtractionBodyProbe.signalConsumers(); } catch (RuntimeException | Error thrown) { failure = thrown; }
            check(closed[0] == 1 && closed[1] == 1 && failure instanceof IllegalStateException && failure.getSuppressed().length == 1,
                    "all consumer cleanup executes exactly once with suppressed secondary failure");
            check(failure.getCause().getMessage().equals("synthetic retained cleanup 0")
                    && failure.getSuppressed()[0].getCause().getMessage().equals("synthetic retained cleanup 1"), "cleanup preserves exact failure order");
            var outside = new com.naocraftlab.skins.client.ScreenOwnedRenderTarget();
            try { call(retain, null, outside); throw new AssertionError("unscoped PIP target retention must reject"); }
            catch (IllegalStateException expected) { check(expected.getMessage().equals("Preview target has no GUI consumer frame"), "consumer frame restores after cleanup"); }
            outside.close();
            System.out.println("PASS native consumer shared cleanup: exact-once all resources, suppressed failure order, no-frame fail-closed");
        } finally { CpmExtractionBodyProbe.resetConsumerControl(); }
    }

    private static void closeTarget(Object target) {
        try { ((AutoCloseable)target).close(); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static Object create(Class<?> type) throws Exception { var c = type.getDeclaredConstructor(); c.setAccessible(true); return c.newInstance(); }
    private static void set(Object owner, String name, Object value) throws Exception { var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value); }
    private static Object call(java.lang.reflect.Method method, Object owner, Object... args) {
        try { method.setAccessible(true); return method.invoke(owner, args); }
        catch (InvocationTargetException e) { if (e.getCause() instanceof RuntimeException r) throw r; if (e.getCause() instanceof Error r) throw r; throw new AssertionError(e.getCause()); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    static void primary() { draws++; draw.run(); }
    static void flush() { flushed.run(); }
    static void push() { poses++; }
    static void pop() { poses--; pop.run(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    static com.naocraftlab.skins.client.VanillaPlayerModelTransform.Operations<CpmPreviewScopeProbe.Pose> immediateOperations() {
        return new com.naocraftlab.skins.client.VanillaPlayerModelTransform.Operations<>() {
            public void scale(CpmPreviewScopeProbe.Pose pose,float x,float y,float z){pose.scale(x,y,z);}
            public void rotateZThenX(CpmPreviewScopeProbe.Pose pose,float z,float x){pose.mulPose(new org.joml.Quaternionf().rotateZ(z).rotateX(x));}
            public void rotateY(CpmPreviewScopeProbe.Pose pose,float y){pose.mulPose(new org.joml.Quaternionf().rotateY(y));}
            public void translate(CpmPreviewScopeProbe.Pose pose,float x,float y,float z){pose.translate(x,y,z);}
        };
    }

    static void immediateFallback(Class<?> type,Object renderer,CpmPreviewScopeProbe.Client client,
            PreviewRenderer.PreviewAppearance appearance) throws Exception {
        var render=type.getMethod("render",CpmPreviewScopeProbe.Graphics.class,PreviewRenderer.PreviewRequest.class);
        var request=new PreviewRenderer.PreviewRequest(appearance,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.EDITOR_DRAFT);
        PreviewRenderer<?> query=(PreviewRenderer<?>) renderer;
        for(String scenario:java.util.List.of("worldless","failed","blocked","timeout","circuit","draw-failure","flush-failure","pose-failure","lighting-failure","retire","closed","no-texture")) {
            long[] now={0};var session=new EditorPreviewSession(()->now[0]);
            boolean terminal=java.util.Set.of("failed","blocked","timeout","circuit").contains(scenario);
            client.level=terminal?new CpmPreviewScopeProbe.Level():null;
            client.player=terminal?new CpmPreviewScopeProbe.Avatar(client.level):null;
            session.beginBody("fixture:immediate-baked",terminal,terminal);
            switch(scenario) {
                case "failed" -> session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.FAILED,()->true));
                case "blocked" -> session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.BLOCKED,()->true));
                case "timeout" -> {now[0]=java.time.Duration.ofSeconds(60).toNanos();session.bodyStatus();}
                case "circuit" -> session.disableLive(new IllegalStateException("synthetic circuit"));
                default -> {}
            }
            set(renderer,"session",session);set(renderer,"closed",scenario.equals("closed"));
            int[] notifications={0};session.observeBody(ignored->{if(query.displayKind()==PreviewRenderer.DisplayKind.ORDINARY){check(poses==0,"ordinary follows pose cleanup");notifications[0]++;}});
            var pending=scenario.equals("no-texture")?NativePlayerSkinLifecycle.pending(appearance.skin().location()):null;
            draws=0;poses=0;
            draw=()->{check(query.displayKind()==PreviewRenderer.DisplayKind.UNCONFIRMED,"immediate primary is not completed display");if(scenario.equals("draw-failure"))throw new IllegalStateException("synthetic draw failure");if(scenario.equals("retire"))session.beginBody("fixture:replacement",false,false);};
            flushed=()->{check(query.displayKind()==PreviewRenderer.DisplayKind.UNCONFIRMED,"flush precedes display");if(scenario.equals("flush-failure"))throw new IllegalStateException("synthetic flush failure");};
            pop=()->{if(scenario.equals("pose-failure"))throw new IllegalStateException("synthetic pose cleanup");};
            lighting=()->{check(query.displayKind()==PreviewRenderer.DisplayKind.UNCONFIRMED,"lighting cleanup precedes completion");if(scenario.equals("lighting-failure"))throw new IllegalStateException("synthetic lighting cleanup");};
            Throwable failure=null;
            try {call(render,renderer,new CpmPreviewScopeProbe.Graphics(),request);}catch(RuntimeException|Error thrown){failure=thrown;}
            boolean positive=java.util.Set.of("worldless","failed","blocked","timeout","circuit").contains(scenario);
            check(!positive||failure==null,scenario+" immediate fallback completes: "+failure);
            check(query.displayKind()==(positive?PreviewRenderer.DisplayKind.ORDINARY:PreviewRenderer.DisplayKind.UNCONFIRMED),scenario+" immediate fallback display truth");
            if(positive) {
                check(draws==1&&notifications[0]==1,"one immediate primary and ordinary transition");
                draw=()->{};flushed=()->{};lighting=()->{};
                session.beginBody("fixture:immediate-baked",terminal,terminal);
                call(render,renderer,new CpmPreviewScopeProbe.Graphics(),request);
                check(notifications[0]==1,"unchanged immediate fallback does not publish again");
                var resource=NativePlayerSkinLifecycle.pending(appearance.skin().location());resource.ready();
                check(query.displayKind()==PreviewRenderer.DisplayKind.UNCONFIRMED,"new resource revokes immediate fallback");resource.retire();
            }
            if(pending!=null)pending.retire();
            System.out.println("PASS actual immediate baked "+scenario+" draws="+draws);
        }
        draw=()->{};pop=()->{};flushed=()->{};lighting=()->{};
    }

    static void submissionBodyProducer(PreviewRenderer.PreviewAppearance appearance, Runnable binding, Runnable cleanupFailure,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture gallery) throws Exception {
        String prefix = "com.naocraftlab.skins.compat.client.identifier.submission.";
        for (String scenario : java.util.List.of("ordinary","cpm","layer-failure","feature-failure","cleanup-failure","blit-failure","normal","shared-backend","owned-close","late-A-error","native-reentrant-close","consumer-failure","fence-failure","cold-owned")) {
            var loader = new Loader(prefix,prefix+"SubmissionPreviewRenderer",prefix+"SimplePreviewRenderer",prefix+"BakedPreviewRenderer",false);
            loader.live = true;loader.physical = true;
            loader.types.remove("com/naocraftlab/skins/runtime/CapeProjection");
            loader.types.put("com/mojang/blaze3d/textures/GpuTexture",CpmExtractionBodyProbe.LegacyTexture.class);
            Client.current = new Client();Client.current.level = new CpmPreviewScopeProbe.Level();Client.current.player = new CpmPreviewScopeProbe.Avatar(Client.current.level);
            Class<?> ownerType = loader.loadClass(prefix+"SubmissionPreviewRenderer"), liveType = loader.loadClass(prefix+"LivePreviewRenderer");
            Object owner = submissionOwner(loader,ownerType);
            Object[] reopenedOwner={null};
            Object dispatcher = liveType.getConstructor(Buffers.class).newInstance(new Buffers());
            var prepare = java.util.Arrays.stream(liveType.getMethods()).filter(m -> m.getName().equals("prepare") && !m.isBridge()).findFirst().orElseThrow();
            var replace = loader.loadClass(prefix+"LivePreviewSubmission").getMethod("replace",Object.class,PipState.class);
            var queued = new java.util.ArrayList<Object>();
            enqueue = graphics -> queued.add(call(replace,null,graphics,new VanillaState()));
            @SuppressWarnings("unchecked") var renderer = (PreviewRenderer<Graphics>)owner;
            CpmExtractionBodyProbe.cached = false;
            CpmExtractionBodyProbe.queuedMaterials().clear();
            CpmExtractionBodyProbe.submissionBoundaries(() -> {},() -> {});
            boolean joined = java.util.Set.of("normal","shared-backend","owned-close","late-A-error","native-reentrant-close","consumer-failure","fence-failure","cold-owned").contains(scenario);
            try (var subject = joined ? scenario.equals("cold-owned") ? gallery.freshOwned() : gallery.fresh() : null) {
                if (subject != null) {
                    subject.enableCpmCape();
                    var environment = com.naocraftlab.skins.runtime.ClientRuntime.class.getDeclaredField("skinExtensionEnvironment"); environment.setAccessible(true);
                    environment.set(subject.runtime, new com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment(1, Map.of(), true));
                }
                if (!joined) {
                    renderer.prepareBody("fixture:submission:"+scenario,false);
                    var request = new PreviewRenderer.PreviewRequest(appearance,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.EDITOR_DRAFT);
                    renderer.render(new Graphics(),request);
                    check(queued.size()==1 && renderer.displayKind()==PreviewRenderer.DisplayKind.UNCONFIRMED,"actual submission enqueue is not completed pass");
                    CpmExtractionBodyProbe.submissionBoundaries(scenario.equals("ordinary") ? () -> {} : binding,
                            scenario.equals("layer-failure") ? () -> check(com.naocraftlab.skins.client.EditorPreviewLayerGuard.handle(new IllegalStateException("synthetic feature layer failure")),"native layer scope")
                            : scenario.equals("feature-failure") ? () -> { throw new IllegalStateException("synthetic feature failure"); }
                            : scenario.equals("cleanup-failure") ? cleanupFailure : () -> {});
                    Throwable failed = null;
                    CpmExtractionBodyProbe.blitBoundary = scenario.equals("blit-failure") ? () -> { throw new IllegalStateException("synthetic material selection failure"); } : () -> {};
                    try { CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),() -> {}); }
                    catch (RuntimeException | Error failure) { failed=failure; }
                    boolean positive = scenario.equals("ordinary") || scenario.equals("cpm");
                    check(renderer.displayKind()==(positive ? scenario.equals("cpm") ? PreviewRenderer.DisplayKind.CPM : PreviewRenderer.DisplayKind.ORDINARY : PreviewRenderer.DisplayKind.UNCONFIRMED),"full prepare/material/blit completion truth "+scenario+" kind="+renderer.displayKind()+" failure="+failed+" materials="+CpmExtractionBodyProbe.queuedMaterials().size());
                    check(!scenario.equals("blit-failure") || failed instanceof IllegalStateException,"material failure propagated");
                } else {
                    Class<?> hostType = scenario.equals("cold-owned") ? CpmPreviewScreenSlice.loadNativeLifecycle(prefix+"NclSkinsScreen",ownerType)
                            : CpmPreviewScreenSlice.loadNative(prefix+"NclSkinsScreen",ownerType);
                    Object host = hostType.getConstructor().newInstance();
                    var registry = new CpmOwnedPreviewDispatchProbe.Registry();
                    try (var skins = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,com.naocraftlab.skins.client.TextureRegistry.TextureKind.PLAYER_SKIN,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
                         var capes = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,com.naocraftlab.skins.client.TextureRegistry.TextureKind.IMAGE,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
                        for (var field : hostType.getDeclaredFields()) {
                            Object value = switch(field.getName()) {
                                case "runtime" -> subject.runtime;case "editorRenderer" -> owner;case "skinTextures" -> skins;case "capeTextures" -> capes;case "textureRegistry" -> registry;
                                case "activeScreen" -> true;case "focusRequests" -> new com.naocraftlab.skins.runtime.FocusRequestLedger();
                                case "nativeWidgetSignatures","nativeTabSignatures" -> java.util.List.of();case "orderedRenderables" -> new java.util.ArrayList<>();
                                case "scrollController" -> new CpmOwnedPreviewDispatchProbe.ScrollTerminal();
                                default -> field.getType()==Map.class ? new HashMap<>() : null;
                            };
                            if(value!=null)field.set(host,value);
                        }
                        if(scenario.equals("cold-owned")) {
                            var terminal=(CpmOwnedPreviewDispatchProbe.ScreenTerminal)host;terminal.width=854;terminal.height=480;
                            terminal.font=new CpmOwnedPreviewDispatchProbe.FontTerminal();terminal.minecraft=new CpmOwnedPreviewDispatchProbe.ClientTerminal();terminal.minecraft.screen=terminal;
                        }
                        var drawHost = scenario.equals("cold-owned") ? hostType.getMethod("render",Graphics.class,int.class,int.class,float.class)
                                : hostType.getDeclaredMethod("renderPreviews",Graphics.class,com.naocraftlab.skins.runtime.ViewSpec.class);
                        var sync = hostType.getDeclaredMethod("synchronizePreviewAssets",com.naocraftlab.skins.runtime.ViewSpec.class);
                        var assetKey = hostType.getDeclaredMethod("skinKey",com.naocraftlab.skins.runtime.ViewSpec.Preview.class);assetKey.setAccessible(true);
                        Object keyA=assetKey.invoke(null,subject.preview());
                        if(scenario.equals("cold-owned")) {
                            call(sync,host,submissionMainView(subject.runtime.view(854,480,0,0)));
                            while(skins.handle(keyA).isEmpty() && subject.runNextWorker())call(sync,host,submissionMainView(subject.runtime.view(854,480,0,0)));
                            var handle=skins.handle(assetKey.invoke(null,subject.preview())).orElseThrow();
                            check(java.util.Arrays.equals(subject.ownedBytes(subject.preview()),registry.registered.get(handle)),"actual imported A producer -> load/cache/relookup bytes");
                        }
                        var changed = scenario.equals("cold-owned") ? hostType.getDeclaredMethod("snapshotChanged",com.naocraftlab.skins.runtime.ClientSnapshot.class) : null;
                        var subscription = changed==null ? null : subject.runtime.subscribe(snapshot -> call(changed,host,snapshot));
                        var ownedA=subject.preview();
                        var inputA=subject.runtime.galleryAccessoryAdmission(ownedA);
                        String sourceA = subject.runtime.previewBodySource(ownedA);
                        drawSubmissionHost(drawHost,host,subject);
                        check(queued.size()==1,"actual frozen host enqueues A");
                        CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),() -> {});
                        check(subject.displayed()==PreviewRenderer.DisplayKind.ORDINARY,"actual caller -> runtime -> selected Submission -> native PIP pass -> Gallery display");
                        var requestA = stateRequest(queued.get(0));
                        com.naocraftlab.skins.client.PreviewAccessories accessoryA;
                        try(var decoded=CpmBehaviorProbe.nativeAccessoryObservation(submissionSource(owner,sourceA))) {
                            accessoryA=renderer.galleryAccessories(sourceA,requestA,inputA);
                            check(accessoryA.canInspect() && accessoryA.present(),"A native matching decoded profile accessories positive");
                        }
                        CpmExtractionBodyProbe.signalConsumers();CpmExtractionBodyProbe.queuedMaterials().clear();queued.clear();
                        drawSubmissionHost(drawHost,host,subject);
                        Object oldA=queued.get(0);queued.clear();
                        var paintedA=rootLeft();
                        subject.selectC();
                        var candidate=subject.runtime.galleryPreviewCandidate().orElseThrow();
                        String sourceB=subject.runtime.previewBodySource(candidate);
                        if(scenario.equals("cold-owned")) {
                            check(!subject.runtime.view(854,480,0,0).previews().contains(candidate),"owned desired B is hidden while A remains displayed");
                            Object keyB=assetKey.invoke(null,candidate);
                            check(!keyA.equals(keyB) && skins.handle(keyB).isEmpty(),"actual hidden B is cold and has distinct source key");
                            call(sync,host,submissionMainView(subject.runtime.view(854,480,0,0)));
                            subject.drainWorkers();call(sync,host,submissionMainView(subject.runtime.view(854,480,0,0)));
                            var handleB=skins.handle(keyB).orElseThrow();var handleA=skins.handle(keyA).orElseThrow();
                            check(!handleA.equals(handleB) && candidate.variant()==com.naocraftlab.skins.core.model.SkinVariant.SLIM
                                    && java.util.Arrays.equals(subject.ownedBytes(candidate),registry.registered.get(handleB)),"actual hidden B imported bytes/model/handle reached native cache relookup");
                            check(subject.preview().bodySource().equals(ownedA.bodySource()),"loaded hidden B cannot authorize display before actual pass");
                        }
                        drawSubmissionHost(drawHost,host,subject);
                        check(queued.size()==2,"actual host draws retained A and hidden desired B");
                        if(scenario.equals("cold-owned"))check(!paintedA.isEmpty() && rootLeft().equals(paintedA),"actual root retains A model/left controls for one queued frame revision");
                        Object stateA=queued.get(0), stateB=queued.get(1);
                        check(stateRequest(stateA).appearance().skin().equals(requestA.appearance().skin())
                                && stateRequest(stateA).appearance().model()==requestA.appearance().model()
                                && stateRequest(stateA).appearance().outerLayerVisibility().equals(requestA.appearance().outerLayerVisibility())
                                && !stateRequest(stateB).appearance().equals(stateRequest(stateA).appearance()),"actual host owns distinct A/B source/model/mask");
                        check(contextCurrent(stateA) && contextCurrent(oldA),"queued A remains current after B selection/preparation");
                        var targetField=stateA.getClass().getDeclaredField("target");targetField.setAccessible(true);
                        check(targetField.get(stateA)!=targetField.get(stateB),"same-size live sources own separate physical target carriers");
                        if(scenario.equals("shared-backend"))targetField.set(stateB,targetField.get(stateA));
                        Runnable lateAFailure = () -> {
                          if(scenario.equals("late-A-error")) try {
                            var sink=oldA.getClass().getDeclaredMethod("failureSink");sink.setAccessible(true);Object callback=sink.invoke(oldA);
                            var fail=callback.getClass().getInterfaces()[0].getMethod("onFailure",RuntimeException.class);fail.setAccessible(true);fail.invoke(callback,new IllegalStateException("synthetic late A failure"));
                            var slots=ownerType.getDeclaredField("gallerySlots");slots.setAccessible(true);Object slot=((Map<?,?>)slots.get(owner)).get(sourceB);
                            var session=slot.getClass().getDeclaredMethod("session");session.setAccessible(true);
                            check(((EditorPreviewSession)session.invoke(slot)).path(PreviewRenderer.PreviewIntent.EDITOR_DRAFT,true)==EditorPreviewSession.Path.LIVE,"late A error cannot disable selected B session");
                          } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                        };
                        var materials=CpmExtractionBodyProbe.queuedMaterials();materials.clear();
                        Throwable failure=null;
                        CpmExtractionBodyProbe.consumerControl(scenario.equals("fence-failure") ? "fence-failure" : "normal");
                        try { CpmExtractionBodyProbe.consumerFrame(loader,() -> {
                            prepareRaw(prepare,dispatcher,stateA,false);
                            lateAFailure.run();
                            if(scenario.equals("native-reentrant-close"))CpmExtractionBodyProbe.submissionBoundaries(() -> {},() -> closeTarget(owner));
                            prepareRaw(prepare,dispatcher,stateB,false);
                            if(scenario.equals("owned-close")) {
                                boolean[] attempted={false};
                                renderer.observeBody(status -> {
                                    if(!attempted[0]) { attempted[0]=true;check(!renderer.prepareGalleryBody("fixture:forbidden-close-reentry",false, () -> true),"logical owner close rejects reentrant Gallery admission before publication"); }
                                });
                                closeTarget(owner);
                                check(attempted[0],"actual close exercises synchronous body observer reentry");
                            }
                        },() -> {
                            CpmExtractionBodyProbe.consumerSubmitted();
                            check(materials.size()==2,"actual live A/B native blits");
                            check(materials.get(0).texture.data.equals(stateRequest(stateA).appearance()) && materials.get(1).texture.data.equals(stateRequest(stateB).appearance()),"live A/B actual distinct producer material");
                            check(!materials.get(0).texture.closed && !materials.get(1).texture.closed,"retired live source material survives actual GuiRenderer.draw");
                            if(scenario.equals("consumer-failure"))throw new IllegalStateException("synthetic live consumer failure");
                        }); } catch(RuntimeException|Error thrown){failure=thrown;}
                        if(scenario.equals("shared-backend"))check(failure instanceof AssertionError && failure.getMessage().equals("live A/B actual distinct producer material") && failure.getSuppressed().length==0,"shared live target exact material negative");
                        else if(scenario.equals("consumer-failure"))check(failure instanceof IllegalStateException && failure.getMessage().equals("synthetic live consumer failure") && failure.getSuppressed().length==0,"live consumer failure preserved");
                        else if(scenario.equals("fence-failure"))check(failure instanceof IllegalStateException && failure.getMessage().equals("synthetic fence enqueue failure") && failure.getSuppressed().length==0,"live fence enqueue retry preserves failure");
                        else check(failure==null,"actual submission joined positive "+scenario+": "+failure);
                        if(!java.util.Set.of("owned-close","native-reentrant-close","shared-backend").contains(scenario)) {
                            check(subject.displayed()==PreviewRenderer.DisplayKind.ORDINARY && subject.preview().bodySource().equals(candidate.bodySource()),"only completed B pass commits its own left projection");
                            try(var decoded=CpmBehaviorProbe.nativeAccessoryObservation(submissionSource(owner,sourceB))) {
                                var accessoryB=renderer.galleryAccessories(sourceB,stateRequest(stateB),subject.runtime.galleryAccessoryAdmission(candidate));
                                check(accessoryB.canInspect() && accessoryB.present() && !accessoryB.scope().equals(accessoryA.scope()),"B matching distinct decoded profile accessories positive");
                            }
                            check(!renderer.galleryAccessories(sourceA,requestA,inputA).canInspect() && !contextCurrent(oldA),"retired A loses callbacks/accessory authority immediately");
                        }
                        if(scenario.equals("cold-owned")) {
                            check(rootLeft().equals(paintedA),"deferred native completion cannot retroactively repaint root A controls");
                            queued.clear();drawSubmissionHost(drawHost,host,subject);
                            check(!rootLeft().equals(paintedA),"next actual root paints completed B controls");
                            var previousB=materials.get(1);
                            CpmExtractionBodyProbe.signalConsumers();
                            check(materials.get(0).texture.closes==1 && !previousB.texture.closed,"predecessor fence release does not close current B");
                            materials.clear();queued.clear();
                            subject.selectB();
                            var returned=subject.runtime.galleryPreviewCandidate().orElseThrow();
                            var returnedInput=subject.runtime.galleryAccessoryAdmission(returned);
                            String returnedSource=subject.runtime.previewBodySource(returned);
                            check(!returnedSource.equals(sourceA) && !returnedSource.equals(sourceB),"B-C-B return has new source authority, not resurrected A");
                            call(sync,host,submissionMainView(subject.runtime.view(854,480,0,0)));subject.drainWorkers();
                            call(sync,host,submissionMainView(subject.runtime.view(854,480,0,0)));
                            drawSubmissionHost(drawHost,host,subject);
                            check(queued.size()==2,"actual returned B-C-B root enqueues predecessor and new candidate");
                            Object returnedState=queued.get(1);
                            check(stateRequest(returnedState).appearance().skin().equals(requestA.appearance().skin())
                                    && stateRequest(returnedState).appearance().model()==requestA.appearance().model(),"returned producer has original owned bytes/model handle");
                            CpmExtractionBodyProbe.consumerControl("normal");
                            CpmExtractionBodyProbe.consumerFrame(loader,() -> {
                                for(var state:queued)prepareRaw(prepare,dispatcher,state,false);
                            },() -> { CpmExtractionBodyProbe.consumerSubmitted();check(materials.size()==2 && materials.get(1).texture.data.equals(stateRequest(returnedState).appearance()),"returned B-C-B actual own material consumer"); });
                            check(subject.preview().presetId().equals(ownedA.presetId()) && !contextCurrent(oldA),"B-C-B actual completed source cannot restore old queued authority");
                            try(var decoded=CpmBehaviorProbe.nativeAccessoryObservation(submissionSource(owner,returnedSource))) {
                                var positive=renderer.galleryAccessories(returnedSource,stateRequest(returnedState),returnedInput);
                                check(positive.canInspect() && positive.cape() && positive.elytra(),"returned current source has real decoded accessories");
                                int[] reads={0,0};
                                var stale=subject.runtime.observePreviewAccessories(subject.preview(),() -> { reads[0]++;return renderer.galleryAccessories(returnedSource,stateRequest(returnedState),returnedInput); });
                                var current=subject.runtime.observePreviewAccessories(subject.preview(),() -> { reads[1]++;return renderer.galleryAccessories(returnedSource,stateRequest(returnedState),returnedInput); });
                                reads[0]=0;reads[1]=0;stale.accept(positive);current.accept(positive);
                                check(reads[0]==0 && reads[1]>0,"replaced native accessory supplier stays unread; current supplier positive");
                                int before=reads[1];subject.sessionAccount=new java.util.UUID(0,899);current.accept(positive);
                                check(reads[1]==before,"account replacement rejects old accessory supplier before read");
                                subject.sessionAccount=new java.util.UUID(0,801);subject.runtime.closeScreen();current.accept(positive);
                                check(reads[1]==before,"closed screen rejects source accessory supplier before read");
                                subject.runtime.reopen();subject.drainWorkers();current.accept(positive);
                                check(reads[1]==before,"reopen cannot restore retired accessory callback");
                            }
                            CpmExtractionBodyProbe.signalConsumers();
                            check(previousB.texture.closes==1,"B-C-B previous B closes exactly once after its own consumer signal");
                            materials.clear();queued.clear();
                            closeTarget(owner);
                            reopenedOwner[0]=submissionOwner(loader,ownerType);
                            Object nextHost=hostType.getConstructor().newInstance();
                            for(var field:hostType.getDeclaredFields()) {
                                Object value=switch(field.getName()) {
                                    case "editorRenderer" -> reopenedOwner[0];case "nativeWidgetSignatures","nativeTabSignatures" -> java.util.List.of();
                                    case "orderedRenderables" -> new java.util.ArrayList<>();case "view","bodyObserver","accessoryObserver","bodyCapability" -> null;
                                    case "focusRequests" -> new com.naocraftlab.skins.runtime.FocusRequestLedger();
                                    case "scrollController" -> new CpmOwnedPreviewDispatchProbe.ScrollTerminal();
                                    default -> field.getType()==Map.class ? new HashMap<>() : field.get(host);
                                };
                                if(value!=null)field.set(nextHost,value);
                            }
                            var nextTerminal=(CpmOwnedPreviewDispatchProbe.ScreenTerminal)nextHost;nextTerminal.width=854;nextTerminal.height=480;
                            nextTerminal.font=new CpmOwnedPreviewDispatchProbe.FontTerminal();nextTerminal.minecraft=new CpmOwnedPreviewDispatchProbe.ClientTerminal();nextTerminal.minecraft.screen=nextTerminal;
                            call(sync,nextHost,subject.runtime.view(854,480,0,0));subject.drainWorkers();call(sync,nextHost,subject.runtime.view(854,480,0,0));
                            drawSubmissionHost(drawHost,nextHost,subject);
                            check(queued.size()==1,"distinct reopened host/renderer prepares fresh current source");
                            CpmExtractionBodyProbe.consumerControl("normal");
                            CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),CpmExtractionBodyProbe::consumerSubmitted);
                            check(subject.displayed()==PreviewRenderer.DisplayKind.ORDINARY,"reopened native pass positively publishes new source");
                            call(hostType.getDeclaredMethod("releasePreviewAccessories"),nextHost);call(hostType.getDeclaredMethod("releasePreviewBody"),nextHost);
                            closeTarget(reopenedOwner[0]);
                        }
                        closeTarget(owner);
                        check(materials.stream().noneMatch(m -> m.texture.closed),"owner close before fence signal retains actual live materials");
                        CpmExtractionBodyProbe.signalConsumers();
                        check(materials.stream().allMatch(m -> m.texture.closes==1),"live A/B exact-once physical close after signal");
                        call(hostType.getDeclaredMethod("releasePreviewAccessories"),host);
                        call(hostType.getDeclaredMethod("releasePreviewBody"),host);
                        if(subscription!=null)subscription.close();
                    }
                }
                System.out.println("PASS actual submission native prepare/blit/consumer "+scenario);
            } finally {
                CpmExtractionBodyProbe.blitBoundary=() -> {};closeTarget(owner);if(reopenedOwner[0]!=null)closeTarget(reopenedOwner[0]);closeTarget(dispatcher);CpmExtractionBodyProbe.signalConsumers();
                CpmExtractionBodyProbe.queuedMaterials().clear();CpmExtractionBodyProbe.submissionBoundaries(() -> {},() -> {});CpmExtractionBodyProbe.resetConsumerControl();enqueue=null;
            }
        }
        submissionObserverRebind(gallery);
        submissionBakedSources(gallery);
        gallery.fallback();
    }

    private static void submissionObserverRebind(com.naocraftlab.skins.runtime.GalleryNativePassFixture root) throws Exception {
        String prefix = "com.naocraftlab.skins.compat.client.identifier.submission.";
        var loader = new Loader(prefix,prefix+"SubmissionPreviewRenderer",prefix+"SimplePreviewRenderer",prefix+"BakedPreviewRenderer",false);
        loader.live = true; loader.physical = true;
        loader.types.put("com/mojang/blaze3d/textures/GpuTexture",CpmExtractionBodyProbe.LegacyTexture.class);
        Client.current = new Client(); Client.current.level = new CpmPreviewScopeProbe.Level();
        Client.current.player = new CpmPreviewScopeProbe.Avatar(Client.current.level);
        var type = loader.loadClass(prefix+"SubmissionPreviewRenderer");
        Object owner = submissionOwner(loader,type);
        @SuppressWarnings("unchecked") var target = (PreviewRenderer<Graphics>) owner;
        var dispatcherType = loader.loadClass(prefix+"LivePreviewRenderer");
        Object dispatcher = dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
        var prepare = java.util.Arrays.stream(dispatcherType.getDeclaredMethods()).filter(m -> m.getName().equals("prepare") && !m.isBridge()).findFirst().orElseThrow();
        var replace = loader.loadClass(prefix+"LivePreviewSubmission").getMethod("replace",Object.class,PipState.class);
        var queued = new java.util.ArrayList<Object>();
        enqueue = graphics -> queued.add(call(replace,null,graphics,new VanillaState()));
        draw = () -> {}; pop = () -> {}; CpmExtractionBodyProbe.consumerControl("normal");
        try (var subject = root.fresh()) {
            var a = subject.runtime.galleryPreviewCandidate().orElseThrow();
            String sourceA = subject.runtime.previewBodySource(a);
            var requestA = subject.request();
            target.observeBody(subject.observeBody(a,target));
            check(target.prepareGalleryBody(sourceA,false,subject.runtime.galleryPreviewAdmission(a)),"N2 submission A admitted");
            target.renderGallery(new Graphics(),sourceA,requestA);
            CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),CpmExtractionBodyProbe::consumerSubmitted);
            check(target.body().current().getAsBoolean(),"N2 submission actual A native material");
            queued.clear(); subject.selectC();
            var b = subject.runtime.galleryPreviewCandidate().orElseThrow();
            String sourceB = subject.runtime.previewBodySource(b);
            var requestB = subject.request();
            target.observeBody(subject.observeBody(b,target));
            check(target.prepareGalleryBody(sourceB,false,subject.runtime.galleryPreviewAdmission(b)),"N2 submission B admitted");
            int[] publications = {0};
            var inputB = subject.runtime.galleryAccessoryAdmission(b);
            var common = subject.runtime.observePreviewAccessories(b,() -> target.galleryAccessories(sourceB,requestB,inputB));
            target.observeAccessories(value -> { publications[0]++; common.accept(value); });
            target.renderGallery(new Graphics(),sourceA,requestA);
            target.renderGallery(new Graphics(),sourceB,requestB);
            check(queued.size()==2 && contextCurrent(queued.get(0)),"N2 submission host order retains legitimate A before hidden B");
            var failureSink = queued.get(0).getClass().getDeclaredMethod("failureSink"); failureSink.setAccessible(true);
            Object sink = failureSink.invoke(queued.get(0));
            var fail = sink.getClass().getInterfaces()[0].getMethod("onFailure",RuntimeException.class); fail.setAccessible(true);
            fail.invoke(sink,new IllegalStateException("synthetic retained submission A failure"));
            check(publications[0]==0,"N2 submission retained A cannot notify registered successor B");
            CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(1),false),CpmExtractionBodyProbe::consumerSubmitted);
            check(target.body().current().getAsBoolean() && subject.displayed()==PreviewRenderer.DisplayKind.ORDINARY,"N2 submission own B actual native material after A failure");
            Object own = failureSink.invoke(queued.get(1)); fail.invoke(own,new IllegalStateException("synthetic own submission B failure"));
            check(publications[0]>0,"N2 submission own B notifies registered B observer");
            System.out.println("PASS N2 submission prepare/register B -> retained A deferred failure -> zero B publications -> own B native material and observer positive");
        } finally { closeTarget(owner); closeTarget(dispatcher); CpmExtractionBodyProbe.signalConsumers(); CpmExtractionBodyProbe.resetConsumerControl(); enqueue=null; }
    }

    private static void submissionBakedSources(com.naocraftlab.skins.runtime.GalleryNativePassFixture gallery) throws Exception {
        String prefix="com.naocraftlab.skins.compat.client.identifier.submission.";
        var loader=new Loader(prefix,prefix+"SubmissionPreviewRenderer",prefix+"SimplePreviewRenderer",prefix+"BakedPreviewRenderer",false);
        loader.live=true;loader.physical=true;loader.types.put("com/mojang/blaze3d/textures/GpuTexture",CpmExtractionBodyProbe.LegacyTexture.class);
        Client.current=new Client();
        var type=loader.loadClass(prefix+"SubmissionPreviewRenderer");Object owner=submissionOwner(loader,type);
        var dispatcherType=loader.loadClass(prefix+"BakedPreviewRenderer");Object dispatcher=dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
        var prepare=java.util.Arrays.stream(dispatcherType.getDeclaredMethods()).filter(m -> m.getName().equals("prepare") && !m.isBridge()).findFirst().orElseThrow();
        var replace=loader.loadClass(prefix+"BakedPreviewSubmission").getMethod("replace",Object.class,PipState.class);
        var queued=new java.util.ArrayList<Object>();enqueue=graphics -> queued.add(call(replace,null,graphics,new VanillaState()));
        CpmExtractionBodyProbe.cached=false;draw=() -> {};pop=() -> {};CpmExtractionBodyProbe.bakedBoundaries(() -> {},() -> {},() -> {});
        var materials=CpmExtractionBodyProbe.queuedMaterials();materials.clear();
        try(var subject=gallery.freshOwned()) {
            var hostType=CpmPreviewScreenSlice.loadNative(prefix+"NclSkinsScreen",type);Object host=hostType.getConstructor().newInstance();
            var registry=new CpmOwnedPreviewDispatchProbe.Registry();
            try(var skins=new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,com.naocraftlab.skins.client.TextureRegistry.TextureKind.PLAYER_SKIN,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
                var capes=new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,com.naocraftlab.skins.client.TextureRegistry.TextureKind.IMAGE,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
                for(var field:hostType.getDeclaredFields()) {
                    Object value=switch(field.getName()) {
                        case "runtime" -> subject.runtime;case "editorRenderer" -> owner;case "skinTextures" -> skins;case "capeTextures" -> capes;case "textureRegistry" -> registry;
                        default -> field.getType()==Map.class ? new HashMap<>() : null;
                    };
                    if(value!=null)field.set(host,value);
                }
                var sync=hostType.getDeclaredMethod("synchronizePreviewAssets",com.naocraftlab.skins.runtime.ViewSpec.class);
                var key=hostType.getDeclaredMethod("skinKey",com.naocraftlab.skins.runtime.ViewSpec.Preview.class);key.setAccessible(true);
                var drawHost=hostType.getDeclaredMethod("renderPreviews",Graphics.class,com.naocraftlab.skins.runtime.ViewSpec.class);
                Object keyA=key.invoke(null,subject.preview());
                call(sync,host,submissionMainView(subject.runtime.view(854,480,0,0)));
                while(skins.handle(keyA).isEmpty() && subject.runNextWorker())call(sync,host,submissionMainView(subject.runtime.view(854,480,0,0)));
                var shownA=subject.preview();String sourceA=subject.runtime.previewBodySource(shownA);var handleA=skins.handle(keyA).orElseThrow();
                check(java.util.Arrays.equals(subject.ownedBytes(shownA),registry.registered.get(handleA)),"worldless selected A imported source bytes");
                drawSubmissionHost(drawHost,host,subject);
                check(queued.size()==1 && subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"worldless own fallback enqueue is not actual display");
                CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),() -> {});
                check(subject.displayed()==PreviewRenderer.DisplayKind.ORDINARY,"worldless source A obtains actual selected baked pass");
                CpmExtractionBodyProbe.signalConsumers();materials.clear();queued.clear();
                call(hostType.getDeclaredMethod("releasePreviewBody"),host);
                subject.offlineCapeCapability(true);
                call(sync,host,submissionMainView(subject.runtime.view(854,480,0,0)));
                subject.drainWorkers(); call(sync,host,submissionMainView(subject.runtime.view(854,480,0,0))); subject.drainWorkers();
                drawSubmissionHost(drawHost,host,subject);
                CpmExtractionBodyProbe.consumerFrame(loader,() -> { for(var state:queued)prepareRaw(prepare,dispatcher,state,false); },() -> {});
                var capabilityA = subject.preview();
                check(capabilityA.capeHasElytra(),"COMMON-02 submission real account predecessor capability");
                CpmExtractionBodyProbe.signalConsumers();materials.clear();queued.clear();
                subject.offlineCapeCapability(false);
                var capabilityB = subject.runtime.galleryPreviewCandidate().orElseThrow();
                check(capabilityA.bodySource().equals(capabilityB.bodySource())
                        && !subject.runtime.previewBodySource(capabilityA).equals(subject.runtime.previewBodySource(capabilityB)),
                        "COMMON-02 submission equal BodySource distinct exact identity");
                call(sync,host,submissionMainView(subject.runtime.view(854,480,0,0)));
                drawSubmissionHost(drawHost,host,subject);
                check(queued.size()==2,"COMMON-02 submission enqueues actual retained and same-ID successor");
                CpmExtractionBodyProbe.consumerFrame(loader,() -> { for(var state:queued)prepareRaw(prepare,dispatcher,state,false); },() -> {});
                check(!subject.preview().capeHasElytra(),"COMMON-02 submission actual same-ID successor pass");
                CpmExtractionBodyProbe.signalConsumers();materials.clear();queued.clear();
                System.out.println("PASS COMMON-02 submission actual host same-ID real account capability replacement");
                subject.selectC();var hidden=subject.runtime.galleryPreviewCandidate().orElseThrow();Object keyB=key.invoke(null,hidden);
                check(skins.handle(keyB).isEmpty() && !subject.runtime.view(854,480,0,0).previews().contains(hidden),"worldless selected hidden B is cold, not borrowed A");
                call(sync,host,submissionMainView(subject.runtime.view(854,480,0,0)));subject.drainWorkers();call(sync,host,submissionMainView(subject.runtime.view(854,480,0,0)));
                var handleB=skins.handle(keyB).orElseThrow();
                check(!handleA.equals(handleB) && hidden.variant()==com.naocraftlab.skins.core.model.SkinVariant.SLIM
                        && java.util.Arrays.equals(subject.ownedBytes(hidden),registry.registered.get(handleB)),"worldless B own imported bytes/model/handle through cache relookup");
                drawSubmissionHost(drawHost,host,subject);check(queued.size()==2,"worldless actual host enqueues retained A and hidden B");
                var target=queued.get(0).getClass().getMethod("target");check(target.invoke(queued.get(0))!=target.invoke(queued.get(1)),"selected worldless slots own separate baked targets");
                CpmExtractionBodyProbe.consumerControl("normal");
                CpmExtractionBodyProbe.consumerFrame(loader,() -> { for(var state:queued)prepareRaw(prepare,dispatcher,state,false); },() -> {
                    CpmExtractionBodyProbe.consumerSubmitted();check(materials.size()==2,"selected worldless A/B actual native blits");
                    check(materials.get(0).texture.data.equals(new Identifier(handleA.location())) && materials.get(1).texture.data.equals(new Identifier(handleB.location())),"selected worldless A/B actual source materials, not prepared DTO");
                    closeTarget(owner);check(materials.stream().noneMatch(m -> m.texture.closed),"selected worldless owned close waits for actual consumer signal");
                });
                check(!sourceA.equals(subject.runtime.previewBodySource(hidden)),"selected worldless sources remain distinct");
                check(materials.stream().noneMatch(m -> m.texture.closed),"selected baked fence enqueue is not GPU signal");
                CpmExtractionBodyProbe.signalConsumers();check(materials.stream().allMatch(m -> m.texture.closes==1),"selected baked resources close exactly once after own signal");
                call(hostType.getDeclaredMethod("releasePreviewAccessories"),host);call(hostType.getDeclaredMethod("releasePreviewBody"),host);
            }
        } finally { closeTarget(owner);closeTarget(dispatcher);CpmExtractionBodyProbe.signalConsumers();CpmExtractionBodyProbe.resetConsumerControl();materials.clear();enqueue=null; }
        System.out.println("PASS selected Submission worldless Gallery A/B own imported source/model/cache/native material/consumer/fence/exact-once close");
    }

    private static java.util.List<String> rootLeft() { return rootPainted.stream().filter(v -> v.startsWith("gallery.outer_layer.") || v.startsWith("gallery.inspect.")).toList(); }
    private static void drawSubmissionHost(java.lang.reflect.Method method,Object host,com.naocraftlab.skins.runtime.GalleryNativePassFixture subject) {
        rootPainted.clear();
        if(method.getParameterCount()==4)call(method,host,new Graphics(),0,0,0f);
        else call(method,host,new Graphics(),subject.runtime.view(854,480,0,0));
    }

    private static com.naocraftlab.skins.runtime.ViewSpec submissionMainView(com.naocraftlab.skins.runtime.ViewSpec view) {
        try {
            var components=view.getClass().getRecordComponents();Object[] values=new Object[components.length];Class<?>[] types=new Class<?>[components.length];
            for(int i=0;i<components.length;i++) {
                types[i]=components[i].getType();values[i]=components[i].getName().equals("previews")
                        ? view.previews().stream().filter(p -> p.id().equals("gallery.preview")).toList() : components[i].getAccessor().invoke(view);
            }
            return view.getClass().getConstructor(types).newInstance(values);
        } catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }

    private static com.naocraftlab.skins.runtime.EffectiveSkinSource submissionSource(Object owner,String key) throws Exception {
        var slots=owner.getClass().getDeclaredField("gallerySlots");slots.setAccessible(true);
        Object slot=((Map<?,?>)slots.get(owner)).get(key);
        var profile=slot.getClass().getDeclaredMethod("profile");profile.setAccessible(true);Object value=profile.invoke(slot);
        var id=(java.util.UUID)value.getClass().getMethod("id").invoke(value);
        return com.naocraftlab.skins.runtime.CapeProjection.skinSource(id);
    }

    private static PreviewRenderer.PreviewRequest stateRequest(Object state) {
        try { var method=state.getClass().getDeclaredMethod("request");method.setAccessible(true);return (PreviewRenderer.PreviewRequest)method.invoke(state); }
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static boolean contextCurrent(Object state) {
        try { var method=state.getClass().getDeclaredMethod("previewContext");method.setAccessible(true);Object context=method.invoke(state);return (boolean)context.getClass().getMethod("current").invoke(context); }
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static Object submissionOwner(Loader loader,Class<?> type) throws Exception {
        Object owner=create(type);
        for(var field:type.getDeclaredFields()) {
            if(java.lang.reflect.Modifier.isStatic(field.getModifiers()))continue;
            Object value=switch(field.getName()) {
                case "session" -> new EditorPreviewSession();case "gallerySlots" -> new java.util.LinkedHashMap<>();
                case "profile", "previewProfile" -> new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"NCLFixture");
                case "previewClock" -> new com.naocraftlab.skins.client.EditorPreviewClock();case "previewTickGate" -> new com.naocraftlab.skins.client.EditorPreviewTickGate();
                case "liveTarget" -> new com.naocraftlab.skins.client.ScreenOwnedRenderTarget();
                case "baked", "bakedRenderer" -> {
                    var constructor = loader.loadClass(loader.baked).getDeclaredConstructor();
                    constructor.setAccessible(true); yield constructor.newInstance();
                }
                case "diagnostics" -> com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding();
                case "accessoryObserver" -> (Consumer<com.naocraftlab.skins.client.PreviewAccessories>)ignored -> {};
                case "bodyObserver" -> (Consumer<PreviewRenderer.BodyStatus>)ignored -> {};default -> null;
            };
            if(value!=null){field.setAccessible(true);field.set(owner,value);}
        }
        return owner;
    }

    private static void submissionTerminals(ClassNode node) {
        var helpers = node.methods.stream().filter(m -> java.util.Set.of("playerSkin","previewPlayer","applySettling","configure").contains(m.name)).toList();
        CpmExtractionBodyProbe.init(node);
        node.methods.removeIf(m -> java.util.Set.of("texture","modelParts").contains(m.name));
        node.methods.removeAll(helpers);
        for (var old : helpers) {
            var helper = CpmExtractionBodyProbe.method(node,old.name,old.desc); helper.access = old.access;
            switch (old.name) {
                case "playerSkin" -> {
                    helper.visitTypeInsn(Opcodes.NEW,Type.getInternalName(CpmExtractionBodyProbe.Skin.class)); helper.visitInsn(Opcodes.DUP);
                    helper.visitMethodInsn(Opcodes.INVOKESPECIAL,Type.getInternalName(CpmExtractionBodyProbe.Skin.class),"<init>","()V",false);helper.visitInsn(Opcodes.ARETURN);
                }
                case "previewPlayer" -> {
                    helper.visitVarInsn(Opcodes.ALOAD,0);helper.visitVarInsn(Opcodes.ALOAD,1);helper.visitVarInsn(Opcodes.ALOAD,2);
                    helper.visitMethodInsn(Opcodes.INVOKESTATIC,Type.getInternalName(CpmBakedDisplayProbe.class),"submissionPlayer",
                            "(Ljava/lang/Object;"+Type.getDescriptor(Client.class)+Type.getDescriptor(PreviewRenderer.PreviewAppearance.class)+")"+Type.getDescriptor(CpmPreviewScopeProbe.Avatar.class),false);
                    helper.visitInsn(Opcodes.ARETURN);
                }
                case "configure" -> {
                    helper.visitVarInsn(Opcodes.ALOAD,0);helper.visitVarInsn(Opcodes.ALOAD,1);
                    helper.visitMethodInsn(Opcodes.INVOKESTATIC,Type.getInternalName(CpmBakedDisplayProbe.class),"submissionAppearance",
                            "("+Type.getDescriptor(CpmExtractionBodyProbe.Avatar.class)+Type.getDescriptor(PreviewRenderer.PreviewRequest.class)+")V",false);
                    helper.visitInsn(Opcodes.RETURN);
                }
                default -> helper.visitInsn(Opcodes.RETURN);
            }
            helper.visitMaxs(3,6);
        }
    }

    public static CpmPreviewScopeProbe.Avatar submissionPlayer(Object owner, Client client, PreviewRenderer.PreviewAppearance appearance) {
        try {
            var profile = owner.getClass().getDeclaredField("profile");profile.setAccessible(true);
            Object value=profile.get(owner);
            var id=(java.util.UUID)value.getClass().getMethod("id").invoke(value);
            com.naocraftlab.skins.runtime.CapeProjection.effectiveSkin(id,new java.util.UUID(0,1),appearance.skin().location(),appearance.model(),true);
            var field = owner.getClass().getDeclaredField("previewPlayer");field.setAccessible(true);
            var level = owner.getClass().getDeclaredField("previewLevel");level.setAccessible(true);
            if (field.get(owner) == null || level.get(owner) != client.level) {
                field.set(owner,new CpmPreviewScopeProbe.Avatar(client.level));level.set(owner,client.level);
            }
            return (CpmPreviewScopeProbe.Avatar) field.get(owner);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    public static void submissionAppearance(CpmExtractionBodyProbe.Avatar state, PreviewRenderer.PreviewRequest request) {
        state.appearance = request.appearance();
    }

    private static final class Loader extends ClassLoader {
        private final String prefix, main, baked, dispatcher;
        private final Map<String, Class<?>> types = new HashMap<>();
        private boolean staticFlush;
        private boolean physical;
        private boolean live;
        private boolean interfaceDevice;
        Loader(String prefix, String main, String baked, String dispatcher, boolean extracting) {
            super(CpmBakedDisplayProbe.class.getClassLoader()); this.prefix=prefix; this.main=main; this.baked=baked; this.dispatcher=dispatcher;
            types.putAll(CpmExtractionBodyProbe.nativeTypes());
            types.remove((prefix+"AvatarPipPreviewRenderer").replace('.', '/'));
            types.put("net/minecraft/client/Minecraft", Client.class);
            types.put("net/minecraft/world/entity/Entity", CpmPreviewScopeProbe.Entity.class);
            types.put("net/minecraft/client/entity/ClientAvatarState", CpmPreviewScopeProbe.Physics.class);
            types.put("net/minecraft/world/phys/Vec3", CpmPreviewScopeProbe.Vector.class);
            types.put("net/minecraft/client/DeltaTracker", CpmPreviewScopeProbe.Delta.class);
            types.put("net/minecraft/client/Options", CpmExtractionBodyProbe.Options.class);
            types.put("net/minecraft/client/multiplayer/ClientPacketListener", CpmExtractionBodyProbe.Connection.class);
            types.put("net/minecraft/world/entity/player/PlayerModelPart", CpmPreviewScopeProbe.Part.class);
            types.put("net/minecraft/client/multiplayer/ClientLevel", CpmPreviewScopeProbe.Level.class);
            types.put("net/minecraft/client/player/LocalPlayer", CpmPreviewScopeProbe.Avatar.class);
            types.put("net/minecraft/client/player/AbstractClientPlayer", CpmPreviewScopeProbe.Avatar.class);
            types.put((main+"$PreviewPlayer").replace('.', '/'), CpmPreviewScopeProbe.Avatar.class);
            types.put("net/minecraft/world/entity/player/PlayerSkin", CpmExtractionBodyProbe.Skin.class);
            types.put("net/minecraft/world/item/ItemStack", CpmPreviewScopeProbe.Stack.class);
            types.put("net/minecraft/world/entity/Avatar", CpmPreviewScopeProbe.Player.class);
            types.put("net/minecraft/world/entity/LivingEntity", CpmPreviewScopeProbe.Entity.class);
            types.put("net/minecraft/client/gui/GuiGraphics", Graphics.class);
            types.put("net/minecraft/client/gui/GuiGraphicsExtractor", Graphics.class);
            for (String name : java.util.List.of("Model", "Model$Simple", "player/PlayerModel", "player/PlayerCapeModel", "object/equipment/ElytraModel")) types.put("net/minecraft/client/model/"+name, Model.class);
            types.put("net/minecraft/client/model/geom/ModelPart", Part.class);
            types.put("net/minecraft/resources/Identifier", Identifier.class);
            types.put("net/minecraft/client/gui/navigation/ScreenRectangle", Rectangle.class);
            types.put("net/minecraft/client/gui/render/state/pip/PictureInPictureRenderState", PipState.class);
            types.put("net/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState", PipState.class);
            types.put("net/minecraft/client/gui/render/state/GuiRenderState", CpmExtractionBodyProbe.GuiRenderState.class);
            types.put("net/minecraft/client/gui/render/state/BlitRenderState", CpmExtractionBodyProbe.Blit.class);
            types.put("net/minecraft/client/gui/render/state/PanoramaRenderState", CpmExtractionBodyProbe.Panorama.class);
            types.put("net/minecraft/client/renderer/state/gui/GuiRenderState", CpmExtractionBodyProbe.GuiRenderState.class);
            types.put("net/minecraft/client/renderer/MultiBufferSource$BufferSource", Buffers.class);
            types.put("net/minecraft/client/renderer/MultiBufferSource", BufferAccess.class);
            types.put("net/minecraft/client/renderer/RenderType", RenderType.class);
            types.put("net/minecraft/client/renderer/rendertype/RenderType", RenderType.class);
            types.put("net/minecraft/client/renderer/rendertype/RenderTypes", RenderType.class);
            types.put("com/mojang/blaze3d/vertex/VertexConsumer", Vertex.class);
            types.put("com/mojang/blaze3d/vertex/PoseStack", CpmExtractionBodyProbe.Pose.class);
            types.put("com/mojang/blaze3d/platform/Lighting", CpmPreviewScopeProbe.Light.class);
            types.put("com/mojang/blaze3d/platform/Lighting$Entry", CpmPreviewScopeProbe.Light.Entry.class);
            types.put("net/minecraft/client/renderer/GameRenderer", GameRenderer.class);
            types.put("net/minecraft/client/renderer/SubmitNodeCollector", CpmExtractionBodyProbe.Collector.class);
            types.put("net/minecraft/client/renderer/OrderedSubmitNodeCollector", CpmExtractionBodyProbe.Collector.class);
            types.put("net/minecraft/client/renderer/SubmitNodeStorage", CpmExtractionBodyProbe.Storage.class);
            types.put("net/minecraft/client/renderer/feature/FeatureRenderDispatcher", CpmExtractionBodyProbe.Features.class);
            types.put("net/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame", CpmExtractionBodyProbe.PreparedFrame.class);
            types.put("net/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay", Overlay.class);
            types.put("net/minecraft/client/renderer/texture/OverlayTexture", Overlay.class);
            types.put("net/minecraft/util/Unit", Unit.class);
            types.put("com/mojang/blaze3d/systems/RenderSystem", CpmExtractionBodyProbe.RenderSystem.class);
            types.put("com/mojang/blaze3d/textures/GpuTexture", BakedTexture.class);
            types.put("com/mojang/blaze3d/textures/GpuTextureView", CpmExtractionBodyProbe.TextureView.class);
            types.put("com/mojang/renderpearl/api/textures/GpuTexture", CpmExtractionBodyProbe.GpuTexture.class);
            types.put("com/mojang/renderpearl/api/textures/GpuTextureView", CpmExtractionBodyProbe.TextureView.class);
            types.put("com/mojang/renderpearl/api/device/GpuDevice", CpmExtractionBodyProbe.Device.class);
            types.put("com/mojang/renderpearl/api/commands/CommandEncoder", CpmExtractionBodyProbe.Encoder.class);
            types.put("com/mojang/renderpearl/api/commands/RenderPass", CpmExtractionBodyProbe.Pass.class);
            types.put((prefix+"VanillaPreviewModels").replace('.', '/'), Models.class);
            types.put((prefix+"VanillaPreviewModels$AttachmentModels").replace('.', '/'), ExtractionAttachments.class);
            types.put((prefix+"SimplePreviewRenderer$AttachmentModels").replace('.', '/'), SubmissionAttachments.class);
            types.put("net/minecraft/client/renderer/entity/state/HumanoidRenderState", Humanoid.class);
            types.put("com/mojang/blaze3d/textures/TextureFormat", CpmExtractionBodyProbe.Format.class);
            types.put("net/minecraft/client/renderer/CachedOrthoProjectionMatrixBuffer", CpmExtractionBodyProbe.ProjectionBuffer.class);
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            boolean pip = name.equals("net.minecraft.client.gui.render.pip.PictureInPictureRenderer");
            boolean frame = name.equals("net.minecraft.client.gui.render.GuiRenderer");
            if (!pip && !frame && !name.startsWith(prefix) && !name.startsWith("com.naocraftlab.skins.compat.client.identifier.preview.")
                    && !name.equals("com.naocraftlab.skins.compat.client.identifier.foil.CapeFoilBuffer")
                    && !name.equals("com.naocraftlab.skins.compat.client.identifier.IdentifierOuterLayerVisibilityController")) return super.loadClass(name,resolve);
            Class<?> found=findLoadedClass(name);
            if (found==null) try (var input=getParent().getResourceAsStream(name.replace('.','/')+".class")) {
                if(input==null) throw new ClassNotFoundException(name);
                byte[] original=input.readAllBytes(); var node=new ClassNode(); new ClassReader(original).accept(node,0);
                if (pip && physical) for (var method : node.methods) for (var instruction : method.instructions) {
                    if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.owner.equals("com/mojang/blaze3d/systems/GpuDevice") && call.itf) interfaceDevice = true;
                }
                if (interfaceDevice) {
                    types.put("com/mojang/blaze3d/systems/GpuDevice", CpmExtractionBodyProbe.LegacyDeviceApi.class);
                    types.put("com/mojang/blaze3d/systems/CommandEncoder", CpmExtractionBodyProbe.LegacyEncoderApi.class);
                    for (var method : node.methods) for (var instruction : method.instructions) {
                        if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.owner.equals("com/mojang/blaze3d/systems/RenderSystem") && call.name.equals("getDevice")) call.name = "getLegacyDevice";
                    }
                }
                if (frame) CpmExtractionBodyProbe.frameTerminals(node);
                if (name.contains(".mixin.")) { node.access &= ~Opcodes.ACC_ABSTRACT; CpmExtractionBodyProbe.init(node); }
                if(pip && !physical) {
                    for(var m:node.methods) for(var ins:m.instructions) if(ins instanceof org.objectweb.asm.tree.MethodInsnNode c && c.name.equals("renderAllFeatures") && c.getOpcode()==Opcodes.INVOKESTATIC) staticFlush=true;
                    boolean deferred=node.methods.stream().anyMatch(m->m.name.equals("prepare") && m.desc.contains("FeatureRenderDispatcher"));
                    if(deferred) CpmExtractionBodyProbe.nativeTerminals(node,true);
                    else nativePlayerTerminals(node);
                    node.interfaces.add("java/lang/AutoCloseable");
                    var close=CpmExtractionBodyProbe.method(node,"close","()V");close.visitInsn(Opcodes.RETURN);close.visitMaxs(0,1);
                    for (var ctor : node.methods) if (ctor.name.equals("<init>") && ctor.desc.equals("()V")) {
                        var body = new org.objectweb.asm.tree.InsnList();
                        String texture = staticFlush ? "com/mojang/renderpearl/api/textures/GpuTexture" : "com/mojang/blaze3d/textures/GpuTexture";
                        String value = Type.getInternalName(staticFlush ? Gpu.class : BakedTexture.class);
                        body.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD,0));body.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW,value));body.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));body.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESPECIAL,value,"<init>","()V",false));body.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTFIELD,node.name,"texture","L"+texture+";"));
                        if (deferred) {
                            String storage=Type.getInternalName(CpmExtractionBodyProbe.Storage.class);
                            body.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD,0));body.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW,storage));body.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));body.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESPECIAL,storage,"<init>","()V",false));body.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTFIELD,node.name,"submitNodeStorage","Lnet/minecraft/client/renderer/SubmitNodeStorage;"));
                        }
                        ctor.instructions.insertBefore(ctor.instructions.getLast(),body);ctor.maxStack=3;
                    }
                    node.fields.add(new org.objectweb.asm.tree.FieldNode(Opcodes.ACC_PROTECTED,"bufferSource","Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;",null,null));
                    var ctor=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)V",null,null);
                    ctor.visitVarInsn(Opcodes.ALOAD,0); ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,node.name,"<init>","()V",false);
                    ctor.visitVarInsn(Opcodes.ALOAD,0); ctor.visitVarInsn(Opcodes.ALOAD,1); ctor.visitFieldInsn(Opcodes.PUTFIELD,node.name,"bufferSource","Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"); ctor.visitInsn(Opcodes.RETURN);ctor.visitMaxs(2,2);node.methods.add(ctor);
                } else if(name.equals(main) && live) {
                    submissionTerminals(node);
                } else if(name.equals(main)) {
                    node.methods.removeIf(m -> !java.util.Set.of("render","body","prepareBody","observeBody","renderFallback","onLiveRenderFailure","clearAccessoryInspection","accessoryObserverFor","observeAccessories",
                            "saveGallerySlot","retireGallerySlots","selectGallerySlot","prepareGalleryBody","confirmGalleryBody","renderGallery","galleryAccessories","close").contains(m.name) && !m.name.startsWith("lambda$"));
                    CpmExtractionBodyProbe.init(node);
                    var method=CpmExtractionBodyProbe.method(node,"accessories","(Lcom/naocraftlab/skins/client/PreviewRenderer$PreviewRequest;)Lcom/naocraftlab/skins/client/PreviewAccessories;");
                    method.visitFieldInsn(Opcodes.GETSTATIC,"com/naocraftlab/skins/client/PreviewRenderer$LiveReason","LIVE_UNAVAILABLE","Lcom/naocraftlab/skins/client/PreviewRenderer$LiveReason;");
                    method.visitMethodInsn(Opcodes.INVOKESTATIC,"com/naocraftlab/skins/client/PreviewAccessories","unavailable","(Lcom/naocraftlab/skins/client/PreviewRenderer$LiveReason;)Lcom/naocraftlab/skins/client/PreviewAccessories;",false);method.visitInsn(Opcodes.ARETURN);method.visitMaxs(1,2);
                    node.innerClasses.clear();
                } else if(name.equals(baked)) {
                    CpmExtractionBodyProbe.init(node);
                    {
                        var ctor = node.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
                        var code = new org.objectweb.asm.tree.InsnList();
                        for (String field : baked.endsWith("AvatarPipPreviewRenderer") ? java.util.List.of("models","target") : java.util.List.of("wide","slim","cape","elytra","target")) {
                            var f = node.fields.stream().filter(v -> v.name.equals(field)).findFirst().orElseThrow();
                            boolean attachment = field.equals("cape") || field.equals("elytra");
                            String type = field.equals("target") ? prefix.replace('.','/') + (baked.endsWith("AvatarPipPreviewRenderer") ? "NclBakedPlayerTarget" : "BakedPreviewTarget") : Type.getInternalName(attachment ? SubmissionAttachments.class : field.equals("models") ? Models.class : Model.class);
                            code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD,0));
                            code.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW,type)); code.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
                            if (attachment) code.add(new org.objectweb.asm.tree.InsnNode(field.equals("elytra") ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
                            code.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESPECIAL,type,"<init>",attachment ? "(Z)V" : "()V",false));
                            code.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTFIELD,node.name,field,f.desc));
                        }
                        ctor.instructions.insertBefore(ctor.instructions.getLast(),code);ctor.maxStack=4;
                    }
                }
                CpmExtractionBodyProbe.bindRenderPearl(node);
                if (physical) for (var method : node.methods) for (var instruction : method.instructions) {
                    if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) call.desc = call.desc.replace("Lorg/joml/Matrix3x2f;", "Lorg/joml/Matrix3x2fc;");
                }
                node.innerClasses.clear();
                var writer=new ClassWriter(0);node.accept(new ClassRemapper(writer,new Remapper(Opcodes.ASM9){ @Override public String map(String internal){var mapped=types.get(internal);return mapped==null?internal:Type.getInternalName(mapped);}}));
                byte[] bytes=writer.toByteArray();System.out.println("Baked class "+name+" original="+sha(original)+" headless="+sha(bytes));found=defineClass(name,bytes,0,bytes.length);
            } catch(java.io.IOException failure){throw new ClassNotFoundException(name,failure);}
            if(resolve)resolveClass(found);return found;
        }
        private void nativePlayerTerminals(ClassNode node) {
            node.access &= ~Opcodes.ACC_ABSTRACT; node.superName="java/lang/Object";node.signature=null;node.interfaces.clear();node.innerClasses.clear();node.nestMembers=null;
            node.methods.removeIf(m -> !m.name.equals("prepare"));node.fields.removeIf(f -> !java.util.Set.of("texture","textureView","depthTextureView").contains(f.name));CpmExtractionBodyProbe.init(node);
            String state=node.methods.stream().filter(m->m.name.equals("prepare")).findFirst().orElseThrow().desc.split(";")[0].substring(1)+";";
            var ready=CpmExtractionBodyProbe.method(node,"textureIsReadyToBlit","("+state+")Z");ready.visitFieldInsn(Opcodes.GETSTATIC,Type.getInternalName(CpmExtractionBodyProbe.class),"cached","Z");ready.visitInsn(Opcodes.IRETURN);ready.visitMaxs(1,2);
            var texture=CpmExtractionBodyProbe.method(node,"prepareTexturesAndProjection","(ZII)V");texture.visitInsn(Opcodes.RETURN);texture.visitMaxs(0,4);
            var blit=CpmExtractionBodyProbe.method(node,"blitTexture","("+state+"L"+(prefix.contains("submission")?"net/minecraft/client/gui/render/state/GuiRenderState":"net/minecraft/client/renderer/state/gui/GuiRenderState")+";)V");blit.visitInsn(Opcodes.RETURN);blit.visitMaxs(0,3);
            var y=CpmExtractionBodyProbe.method(node,"getTranslateY","(II)F");y.visitInsn(Opcodes.FCONST_0);y.visitInsn(Opcodes.FRETURN);y.visitMaxs(1,3);
            var render=CpmExtractionBodyProbe.method(node,"renderToTexture","("+state+"Lcom/mojang/blaze3d/vertex/PoseStack;)V");render.visitInsn(Opcodes.RETURN);render.visitMaxs(0,3);
        }
    }
    private static String sha(byte[] bytes) { try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));} catch(Exception e){throw new AssertionError(e);} }
    public record Identifier(String value) { public static Identifier parse(String value){return new Identifier(value);} }
    public record Rectangle(int left,int top,int width,int height) {}
    public interface PipState {
        default org.joml.Matrix3x2fc pose() { return new org.joml.Matrix3x2f(); }
        int x0();int x1();int y0();int y1();float scale();Rectangle scissorArea();
        static Rectangle getBounds(int x0,int y0,int x1,int y1,Rectangle scissor){return new Rectangle(x0,y0,x1-x0,y1-y0);}
    }
    public record VanillaState() implements PipState {public int x0(){return 0;}public int x1(){return 160;}public int y0(){return 0;}public int y1(){return 180;}public float scale(){return 1;}public Rectangle scissorArea(){return new Rectangle(0,0,160,180);}}
    private static final java.util.List<String> rootPainted = new java.util.ArrayList<>();
    public static void rootPaint(CpmOwnedPreviewDispatchProbe.WidgetTerminal widget) {
        var value=(CpmPreviewScopeProbe.FrameWidget)widget;
        rootPainted.add(value.id+":"+value.x+":"+value.y+":"+value.width+":"+value.message.getString());
    }
    public static final class FrameSurface {
        public static void renderFramePanels(Graphics graphics,com.naocraftlab.skins.runtime.ViewSpec view) {}
    }
    public static final class Graphics {
        public void nextStratum() {}
        public void enableScissor(int x,int y,int right,int bottom) {}
        public void disableScissor() {}
        public void submitEntityRenderState(CpmExtractionBodyProbe.State state, float scale, org.joml.Vector3f translation,
                org.joml.Quaternionf rotation, org.joml.Quaternionf camera, int x0, int y0, int x1, int y1) { enqueue.accept(this); }
        public void submitSkinRenderState(Model model,Identifier texture,float scale,float pitch,float yaw,float pivot,int x0,int y0,int x1,int y1){enqueue.accept(this);}
        public void skin(Model model,Identifier texture,float scale,float pitch,float yaw,float pivot,int x0,int y0,int x1,int y1){enqueue.accept(this);}
    }
    public static final class Client {
        static Client current; public static Client getInstance(){return current;}
        public CpmPreviewScopeProbe.Level level;public CpmPreviewScopeProbe.Avatar player;
        public final CpmExtractionBodyProbe.Options options = new CpmExtractionBodyProbe.Options();
        public CpmExtractionBodyProbe.Connection getConnection() { return null; }
        public CpmExtractionBodyProbe.MainRenderTarget getMainRenderTarget() { return new CpmExtractionBodyProbe.MainRenderTarget(); }
        public final GameRenderer gameRenderer=new GameRenderer();
        public boolean isSameThread() { return true; }
        public CpmPreviewScopeProbe.Delta getDeltaTracker() { return ignored -> 0; }
        public CpmExtractionBodyProbe.Dispatcher getEntityRenderDispatcher() { return new CpmExtractionBodyProbe.Dispatcher(); }
    }
    public static final class GameRenderer {
        public CpmExtractionBodyProbe.MainRenderTarget mainRenderTarget() { return Client.current.getMainRenderTarget(); }
        public CpmExtractionBodyProbe.Features getFeatureRenderDispatcher() { return new CpmExtractionBodyProbe.Features(); }
        public CpmPreviewScopeProbe.Light getLighting(){return new CpmPreviewScopeProbe.Light();}public CpmPreviewScopeProbe.Light lighting(){return getLighting();}}
    public static class Part {public boolean visible;public float xRot,yRot,zRot,x,y,z;public void resetPose(){}public Part getChild(String name){return new Part();} }
    public static class Model {
        public float attackTime;public boolean crouching,riding,young;
        public final Part head=new Part(),body=new Part(),rightArm=new Part(),leftArm=new Part(),rightLeg=new Part(),leftLeg=new Part();
        public final Part hat=new Part(),jacket=new Part(),leftSleeve=new Part(),rightSleeve=new Part(),leftPants=new Part(),rightPants=new Part();
        public void resetPose(){}public void setAllVisible(boolean visible){}
        public void setupAnim(CpmExtractionBodyProbe.Avatar state){}
        public void setupAnim(Humanoid state){}
        public Part root(){return new Part();}
        public RenderType renderType(Identifier texture){return RenderType.entityTranslucent(texture);}
        public RenderType renderType(CpmPreviewScopeProbe.Location texture){return new RenderType();}
        public void renderToBuffer(CpmExtractionBodyProbe.Pose pose,Vertex consumer,int light,int overlay){primary();}
        public void renderToBuffer(CpmPreviewScopeProbe.Pose pose,Vertex consumer,int light,int overlay,int color){primary();}
        public void renderToBuffer(CpmPreviewScopeProbe.Pose pose,Vertex consumer,int light,int overlay,float r,float g,float b,float a){primary();}
    }
    public static final class Humanoid { public float elytraRotX,elytraRotY,elytraRotZ; }
    public record SubmissionAttachments(Model renderModel, Model capePoseModel, Model elytraPoseModel) {
        public SubmissionAttachments(boolean elytra) { this(new Model(), elytra); }
        private SubmissionAttachments(Model model, boolean elytra) { this(model, elytra ? null : model, elytra ? model : null); }
    }
    public record ExtractionAttachments(Model simple, Model player, Model simpleCapePose, Model playerCapePose,
            Model simpleElytraPose, Model playerElytraPose) {
        public ExtractionAttachments(boolean elytra) { this(new Model(), new Model(), elytra ? null : new Model(),
                elytra ? null : new Model(), elytra ? new Model() : null, elytra ? new Model() : null); }
    }
    public static final class Models {
        public final Model wideModel=new Model(),slimModel=new Model(),widePlayerModel=new Model(),slimPlayerModel=new Model();
        public final ExtractionAttachments cape=new ExtractionAttachments(false),elytra=new ExtractionAttachments(true);
    }
    public interface Vertex {}
    public interface BufferAccess {Vertex getBuffer(RenderType type);}
    public static final class Buffers implements BufferAccess {public Vertex getBuffer(RenderType type){ if(CpmExtractionBodyProbe.RenderSystem.outputColorTextureOverride != null) CpmExtractionBodyProbe.RenderSystem.outputColorTextureOverride.texture.data = type.texture; return new Vertex(){};}public void endBatch(){flush();} }
    public static final class RenderType {Identifier texture;public static RenderType entityTranslucent(Identifier id){var type=new RenderType();type.texture=id;return type;}public static RenderType entityTranslucent(CpmPreviewScopeProbe.Location id){return new RenderType();} }
    public static final class ImmediateLighting {
        public static void setupForEntityInInventory(){}
        public static void setupFor3DItems(){lighting.run();}
        public static void setShaderLights(org.joml.Vector3f first,org.joml.Vector3f second){}
    }
    public static final class Overlay { public static final int NO_OVERLAY=0; }
    public static final class BakedTexture { public int getWidth(int mip){return 160;}public int getHeight(int mip){return 180;} }
    public static final class Gpu implements CpmExtractionBodyProbe.GpuTexture { public CpmExtractionBodyProbe.Format getFormat(){return CpmExtractionBodyProbe.Format.RGBA8_UNORM;}public int getWidth(int mip){return 160;}public int getHeight(int mip){return 180;}public void close(){} }
    public enum Unit {INSTANCE}
}
