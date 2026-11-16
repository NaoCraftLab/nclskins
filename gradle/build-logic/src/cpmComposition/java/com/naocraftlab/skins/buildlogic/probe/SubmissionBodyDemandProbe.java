package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.*;
import java.util.concurrent.atomic.AtomicLong;

public final class SubmissionBodyDemandProbe {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void field(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    public static void main(String[] args) throws Exception {
        String prefix = "com.naocraftlab.skins.compat.client.identifier.submission.";
        Class<?> loaderType = Class.forName(CpmBakedDisplayProbe.class.getName() + "$Loader");
        var constructor = loaderType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var loader = (ClassLoader) constructor.newInstance(prefix, prefix + "SubmissionPreviewRenderer",
                prefix + "SimplePreviewRenderer", prefix + "BakedPreviewRenderer", false);
        var client = new CpmBakedDisplayProbe.Client();
        client.level = new CpmPreviewScopeProbe.Level();
        client.player = new CpmPreviewScopeProbe.Avatar(client.level);
        var previous = CpmBakedDisplayProbe.Client.current;
        CpmBakedDisplayProbe.Client.current = client;
        Class<?> type = loader.loadClass(prefix + "SubmissionPreviewRenderer");
        var create = CpmBakedDisplayProbe.class.getDeclaredMethod("submissionOwner", loaderType, Class.class);
        create.setAccessible(true);
        Object owner = create.invoke(null, loader, type);
        var renderer = (PreviewRenderer<?>) owner;
        var clock = new AtomicLong(10);
        try {
            var session = new EditorPreviewSession(clock::get);
            field(owner, "session", session);
            renderer.prepareBody("source-before-texture", "same-open", true);
            check(field(session, "bodyDemand").equals("same-open"), "selected submission owner must forward semantic body demand instead of inherited source-only default");
            clock.addAndGet(59_000_000_000L);
            renderer.prepareBody("source-after-texture", "same-open", true);
            clock.addAndGet(2_000_000_000L);
            check(session.bodyStatus() == PreviewRenderer.BodyStatus.TIMED_OUT, "readiness-derived source refinement must preserve original 60-second deadline");
            renderer.retireBody();
            check(field(owner, "session") != session, "selected submission retireBody must revoke and replace native body owner");
            var gallery = new EditorPreviewSession.Gallery(clock::get);
            field(owner, "gallery", gallery);
            check(renderer.prepareGalleryBody("gallery-before-texture", "gallery-open", true, () -> true), "native Gallery admission");
            var before = (EditorPreviewSession) field(owner, "session");
            check(field(before, "bodyDemand").equals("gallery-open"), "selected submission Gallery must forward semantic demand");
            clock.addAndGet(59_000_000_000L);
            check(renderer.prepareGalleryBody("gallery-after-texture", "gallery-open", true, () -> true), "native Gallery readiness successor admission");
            var after = (EditorPreviewSession) field(owner, "session");
            clock.addAndGet(2_000_000_000L);
            check(after != before && after.bodyStatus() == PreviewRenderer.BodyStatus.TIMED_OUT, "Gallery native successor preserves same-demand deadline without retaining predecessor authority");
            renderer.retireBody();
            check(field(owner, "gallery") == null, "native retirement clears Gallery owner");
            renderer.prepareBody("new-source", "new-open", true);
            check(((EditorPreviewSession) field(owner, "session")).bodyStatus() == PreviewRenderer.BodyStatus.PENDING, "legitimate new demand starts fresh interval");
            System.out.println("PASS_SUBMISSION_NATIVE_DEMAND editorRefinement=true galleryRefinement=true deadline60s=true retireOwner=true newDemand=true");
            joined(loader, type, create, args[0]);
        } finally {
            try {
                try {
                    var output = java.nio.file.Path.of(args[0]);
                    java.nio.file.Files.createDirectories(output);
                    java.nio.file.Files.writeString(output.resolve("native-definitions.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(field(loader, "definitions")));
                } finally { ((AutoCloseable) owner).close(); }
            }
            finally { CpmBakedDisplayProbe.Client.current = previous; }
        }
        check(CpmBakedDisplayProbe.Client.current == previous, "exact previous raw client restored");
    }

    private static void joined(ClassLoader loader, Class<?> type, java.lang.reflect.Method create, String output) throws Exception {
        var image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) image.setRGB(x, y, 0xffaaccbb);
        var bytes = new java.io.ByteArrayOutputStream();
        check(javax.imageio.ImageIO.write(image, "PNG", bytes), "synthetic connected source");
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:joined-demand", 64, 64),
                SkinModel.CLASSIC, java.util.Optional.empty(), PreviewRenderer.CapeMode.OFF, OuterLayerVisibility.allVisible(), false, false);
        try {
            for (String surface : java.util.List.of("gallery", "editor", "providers")) {
                try (var fixture = com.naocraftlab.skins.runtime.GalleryNativePassFixture.numeric(appearance, bytes.toByteArray(),
                        java.nio.file.Path.of(output, surface), true)) {
                    if (surface.equals("editor")) {
                        var selected = fixture.runtime.snapshot().selectedPresetId().orElseThrow();
                        fixture.runtime.dispatchWidget("gallery.preset." + selected + ".more");
                        fixture.runtime.dispatchWidget("gallery.preset." + selected + ".edit");
                    } else if (surface.equals("providers")) fixture.runtime.dispatchWidget("gallery.providers");
                    Object nativeOwner = create.invoke(null, loader, type);
                    try {
                        Class<?> host = CpmPreviewScreenSlice.loadNative("com.naocraftlab.skins.compat.client.identifier.submission.NclSkinsScreen", type);
                        Object screen = host.getConstructor().newInstance();
                        var registry = new CpmOwnedPreviewDispatchProbe.Registry();
                        try (var skins = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry, TextureRegistry.TextureKind.PLAYER_SKIN, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
                             var capes = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry, TextureRegistry.TextureKind.IMAGE, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
                            for (var field : host.getDeclaredFields()) {
                                Object value = switch (field.getName()) {
                                    case "runtime" -> fixture.runtime;
                                    case "editorRenderer" -> nativeOwner;
                                    case "skinTextures" -> skins;
                                    case "capeTextures" -> capes;
                                    case "textureRegistry" -> registry;
                                    default -> field.getType() == java.util.Map.class ? new java.util.HashMap<>() : null;
                                };
                                if (value != null) { field.setAccessible(true); field.set(screen, value); }
                            }
                            var view = fixture.runtime.view(854, 480, 0, 0);
                            var synchronize = host.getDeclaredMethod("synchronizePreviewAssets", com.naocraftlab.skins.runtime.ViewSpec.class);
                            var render = host.getDeclaredMethod("renderPreviews", CpmBakedDisplayProbe.Graphics.class, com.naocraftlab.skins.runtime.ViewSpec.class);
                            synchronize.setAccessible(true); render.setAccessible(true);
                            synchronize.invoke(screen, view);
                            render.invoke(screen, new CpmBakedDisplayProbe.Graphics(), view);
                            Object operation = field(fixture.runtime, "numericFitOperation");
                            check(operation != null, "compiled host creates actual common foreground operation " + surface);
                            var preview = surface.equals("gallery") ? fixture.runtime.galleryPreviewCandidate().orElseThrow() : view.previews().get(0);
                            var session = (EditorPreviewSession) field(nativeOwner, "session");
                            check(field(session, "bodyDemand").equals(fixture.runtime.previewBodyDemand(preview)), "compiled host forwards actual runtime demand to selected native owner " + surface);
                            Object observer = field(screen, "bodyObserver");
                            for (int i = 0; i < 100; i++) render.invoke(screen, new CpmBakedDisplayProbe.Graphics(), view);
                            check(field(fixture.runtime, "numericFitOperation") == operation && field(screen, "bodyObserver") == observer,
                                    "same native/source pending opening retains operation and observer " + surface);
                            check(fixture.numericLoads == 0 && fixture.numericCreates == 0, "joined pending source has no numeric disk work " + surface);
                            fixture.worldChanged();
                            check(field(nativeOwner, "session") != session && field(nativeOwner, "gallery") == null,
                                    "actual common retirement invokes actual selected native retirement " + surface);
                            System.out.println("PASS_SUBMISSION_JOINED_DEMAND " + surface + " frames=101 sameOperation=true exactDemand=true retainedObserver=true actualNativeRetirement=true disk=0");
                        }
                    } finally { ((AutoCloseable) nativeOwner).close(); }
                }
            }
        } finally { com.naocraftlab.skins.runtime.GalleryNativePassFixture.closeNumericCoordinator(); }
    }
}
