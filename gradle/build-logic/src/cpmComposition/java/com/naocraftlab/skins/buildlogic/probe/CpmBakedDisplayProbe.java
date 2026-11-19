package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.EditorPreviewSession;
import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.PreviewModelFrames;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.PreviewRenderer.PreviewAppearance;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import java.util.List;
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
    private static boolean menuPanelJoin;
    static boolean loadingCanonical;
    private static Consumer<Object> enqueue;
    private static Runnable draw = () -> {};
    private static Runnable pop = () -> {};
    static Runnable flushed = () -> {};
    private static Runnable lighting = () -> {};
    private static int draws;
    private static int poses;

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("submission-pool")) {
            hiddenImage("submission-pool");
            return;
        }
        if (args.length == 1 && args[0].equals("frame-cards")) {
            System.setProperty("nclskins.frameCards","true");
            hiddenImage("frame-cards");
            return;
        }
        if (args.length == 1 && args[0].equals("context-callbacks")) {
            hiddenImage("context-callbacks");
            return;
        }
        if (args.length == 1 && args[0].equals("refresh-host")) {
            System.setProperty("nclskins.refreshOnly","true");
            retentionHost();
            return;
        }
        if (args.length == 1 && args[0].equals("retention-host")) {
            retentionHost();
            return;
        }
        if (args.length == 1 && args[0].equals("retention-image")) {
            retentionImage();
            return;
        }
        if (args.length == 1 && args[0].equals("submission-image")) {
            submissionImageConsumer();
            return;
        }
        if (args.length == 1 && args[0].equals("equipment-image")) {
            hiddenImage("equipment");
            return;
        }
        if (args.length == 1 && args[0].equals("gallery-mode")) {
            hiddenImage("gallery-mode");
            return;
        }
        if (args.length == 1 && args[0].equals("card-image")) {
            hiddenImage("card");
            return;
        }
        if (args.length == 1 && args[0].equals("menu-panel")) {
            featureMaterialControls();
            bufferMaterialControls();
            menuPanelJoin = true;
            for (boolean pause : new boolean[]{false,true}) {
                System.setProperty("nclskins.menuPause",Boolean.toString(pause));
                for (String scenario : java.util.List.of("menu", "menu-texture", "menu-texture-failed", "menu-consumer-failure", "menu-closed", "menu-replaced", "menu-animation", "menu-cape-retired")) hiddenImage(scenario);
            }
            return;
        }
        if(args.length==2 && args[0].equals("immediate-static")) { immediateStaticJoin=true; immediateMenu(args[1]); return; }
        if (args.length == 2 && args[0].equals("immediate-panel")) {
            menuPanelJoin = true;
            immediateMenu(args[1]);
            return;
        }
        if (args.length == 2 && args[0].equals("immediate-menu")) {
            immediateMenu(args[1]);
            return;
        }
        if (args.length >= 1 && args[0].equals("hidden-image")) {
            hiddenImage(args.length > 1 ? args[1] : "normal");
            return;
        }
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
        for (String scenario : java.util.List.of("normal", "consumer-failure", "prepare-abort", "menu", "menu-consumer-failure", "menu-texture", "menu-texture-failed", "menu-closed", "menu-replaced", "fence-cleanup-failure")) hiddenImage(scenario);
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
                Object targetOwner = extracting ? field(field(dispatcher, "context"), "owner")
                        : preparedSubmissionOwner(targetType, dispatcher);
                check(targetOwner != null && targetOwner != dispatcher, "prepare binds a GUI context owner, not the dispatcher");
                int notifications = observed.size();
                Object previous = queued[0];
                CpmExtractionBodyProbe.cached = true;
                call(render, main, graphics, request);
                check(previous.equals(queued[0]), "unchanged authority preserves baked state/cache identity");
                prepare(nativePrepare, dispatcher, queued[0], late);
                check(draws == 1 && observed.size() == notifications, "cached unchanged pass does not draw or publish again");
                check(query.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED,
                        "cached no-primary pass invalidates the previous ordinary proof before resubmission");
                CpmExtractionBodyProbe.cached = false;
                call(render, main, graphics, request);
                prepare(nativePrepare, dispatcher, queued[0], late);
                check(draws == 2 && query.displayKind() == PreviewRenderer.DisplayKind.ORDINARY,
                        "fresh primary draw restores ordinary proof before release");
                var release = targetType.getMethod("release", Object.class);
                release.invoke(target, dispatcher);
                check(query.displayKind() == PreviewRenderer.DisplayKind.ORDINARY, "foreign release must not revoke ordinary proof");
                release.invoke(target, targetOwner);
                check(query.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "released live target revokes ordinary proof");
                CpmExtractionBodyProbe.cached = true;
                call(render, main, graphics, request);
                prepare(nativePrepare, dispatcher, queued[0], late);
                check(query.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "cached/no-primary return cannot authorize replacement");
                System.out.println("PASS baked " + scenario + " context release: foreign dispatcher no-op, exact owner revokes ordinary proof");
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

    static final java.util.List<CpmPreviewScopeProbe.Location> immediateMaterials = new java.util.ArrayList<>();
    static final java.util.List<Float> immediateTranslations = new java.util.ArrayList<>();
    private static final List<CpmPreviewScopeProbe.Location> immediateDrawnMaterials = new java.util.ArrayList<>();
    static boolean observeImmediateMenu;
    private static boolean immediateStaticJoin;
    public static void translated(float x) { if (observeImmediateMenu) immediateTranslations.add(x); }

    static void immediateMenu(String family) throws Exception {
        String prefix = "com.naocraftlab.skins.compat.client.resourcelocation." + family + ".";
        String owner = prefix + "SimplePreviewRenderer";
        var types = new HashMap<String, Class<?>>();
        types.put("net/minecraft/client/Minecraft", CpmPreviewScopeProbe.Client.class);
        types.put("net/minecraft/client/multiplayer/ClientLevel", CpmPreviewScopeProbe.Level.class);
        types.put("net/minecraft/client/player/LocalPlayer", CpmPreviewScopeProbe.Avatar.class);
        types.put("net/minecraft/client/gui/GuiGraphics", CpmPreviewScopeProbe.Graphics.class);
        types.put("com/mojang/blaze3d/vertex/PoseStack", CpmPreviewScopeProbe.Pose.class);
        types.put("com/mojang/blaze3d/vertex/PoseStack$Pose", CpmPreviewScopeProbe.Pose.Entry.class);
        types.put("net/minecraft/resources/ResourceLocation", CpmPreviewScopeProbe.Location.class);
        types.put("net/minecraft/client/model/PlayerModel", Model.class);
        types.put("net/minecraft/client/model/EntityModel", Model.class);
        types.put("net/minecraft/client/model/ElytraModel", Model.class);
        types.put("net/minecraft/client/model/geom/ModelPart", Part.class);
        types.put("net/minecraft/client/renderer/RenderType", RenderType.class);
        types.put("net/minecraft/client/renderer/MultiBufferSource$BufferSource", Buffers.class);
        types.put("net/minecraft/client/renderer/MultiBufferSource", BufferAccess.class);
        types.put("com/naocraftlab/skins/compat/client/cape/CapeTranslucentFoilMaterial", ImmediateCapeMaterial.class);
        types.put("net/minecraft/client/renderer/texture/OverlayTexture", Overlay.class);
        types.put("com/mojang/blaze3d/vertex/VertexConsumer", Vertex.class);
        types.put("com/mojang/blaze3d/platform/Lighting", ImmediateLighting.class);
        types.put("com/mojang/blaze3d/systems/RenderSystem", ImmediateLighting.class);
        var loader = new ClassLoader(CpmBakedDisplayProbe.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!(name.startsWith(owner) || name.startsWith(prefix + "BakedPlayerPose") || name.startsWith(prefix + "BakedPreviewLighting")
                        || name.equals(prefix + "PreviewPoseScale")))
                    return super.loadClass(name, resolve);
                Class<?> found = findLoadedClass(name);
                if (found == null) try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (input == null) throw new ClassNotFoundException(name);
                    byte[] original = input.readAllBytes();
                    var node = new ClassNode(); new ClassReader(original).accept(node, 0);
                    if (name.equals(owner)) {
                        CpmExtractionBodyProbe.init(node);
                        for (var method : node.methods) for (var instruction : method.instructions) {
                            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                                    && call.owner.equals("net/minecraft/resources/ResourceLocation")
                                    && call.name.equals("withDefaultNamespace"))
                                call.owner = Type.getInternalName(ImmediateCapeMaterial.class);
                        }
                        if (menuPanelJoin) {
                            var ctor = CpmExtractionBodyProbe.method(node,"<init>","(Lnet/minecraft/client/Minecraft;)V");
                            ctor.visitVarInsn(Opcodes.ALOAD,0);
                            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,node.name,"<init>","()V",false);
                            ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(1,2);
                        }
                    }
                    var writer = new ClassWriter(0);
                    node.accept(new ClassRemapper(writer, new Remapper(Opcodes.ASM9) {
                        @Override public String map(String name) { var type = types.get(name); return type == null ? name : Type.getInternalName(type); }
                    }));
                    byte[] bytes = writer.toByteArray();
                    System.out.println("Immediate menu class " + name + " original=" + sha(original) + " headless=" + sha(bytes));
                    found = defineClass(name, bytes, 0, bytes.length);
                } catch (java.io.IOException failure) { throw new ClassNotFoundException(name, failure); }
                if (resolve) resolveClass(found);
                return found;
            }
        };
        Class<?> type = loader.loadClass(owner);
        var appearance = new PreviewRenderer.PreviewAppearance(
                new com.naocraftlab.skins.client.TextureRegistry.TextureHandle("fixture:immediate-own-body",64,64),
                com.naocraftlab.skins.client.SkinModel.SLIM, java.util.Optional.empty(), PreviewRenderer.CapeMode.OFF,
                com.naocraftlab.skins.client.OuterLayerVisibility.allVisible());
        CpmPreviewScopeProbe.Client.current = new CpmPreviewScopeProbe.Client();
        var panel = menuPanelJoin ? new CpmMenuPanelSlice(loader,prefix,appearance,CpmPreviewScopeProbe.Graphics.class,CpmPreviewScopeProbe.Client.class) : null;
        if (panel != null) {
            panel.install(com.naocraftlab.skins.core.config.MenuPreviewPlacement.OFF);
            panel.draw(new CpmPreviewScopeProbe.Graphics());
            check(!panel.actionVisible(), "JOIN immediate actual menu OFF/layout admission");
            panel.install(com.naocraftlab.skins.core.config.MenuPreviewPlacement.RIGHT);
            panel.anchor(false); panel.draw(new CpmPreviewScopeProbe.Graphics());
            check(!panel.actionVisible(), "JOIN immediate actual missing anchor");
            panel.anchor(true); panel.dimensions(300,240); panel.draw(new CpmPreviewScopeProbe.Graphics());
            check(!panel.actionVisible(), "JOIN immediate actual minimum bounds");
            panel.dimensions(854,480); panel.appearance(null); panel.draw(new CpmPreviewScopeProbe.Graphics());
            check(panel.actionVisible(), "JOIN immediate absent image retains permitted navigation");
            panel.appearance(appearance);
        }
        if(immediateStaticJoin) { immediateStaticHost(type,appearance); return; }
        Object renderer = panel == null ? type.getConstructor().newInstance() : panel.renderer();
        for (var field : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            Object value = field.getType() == Model.class ? new Model() : field.getType() == Part.class ? new Part()
                    : field.getName().equals("imageSession") ? new EditorPreviewSession() : null;
            if (value != null) { field.setAccessible(true); field.set(renderer, value); }
        }
        if (Boolean.getBoolean("nclskins.explicitImageControl")) {
            var selected = (PreviewRenderer<CpmPreviewScopeProbe.Graphics>)renderer;
            var first = new PreviewRenderer.PreviewRequest(appearance,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.ASSET_THUMBNAIL);
            observeImmediateMenu = true;
            try {
                selected.prepareImage("fixture:explicit-A",System.nanoTime());
                selected.render(new CpmPreviewScopeProbe.Graphics(),first);
                selected.render(new CpmPreviewScopeProbe.Graphics(),first);
                var nextSkin = new TextureRegistry.TextureHandle("fixture:explicit-B",64,64);
                var nextAppearance = new PreviewRenderer.PreviewAppearance(nextSkin,SkinModel.CLASSIC,java.util.Optional.empty(),PreviewRenderer.CapeMode.OFF,OuterLayerVisibility.allVisible());
                var next = new PreviewRenderer.PreviewRequest(nextAppearance,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.ASSET_THUMBNAIL);
                var pending = NativePlayerSkinLifecycle.pending(nextSkin.location());
                try {
                    selected.prepareImage("fixture:explicit-B",System.nanoTime());
                    immediateMaterials.clear(); immediateTranslations.clear();
                    selected.render(new CpmPreviewScopeProbe.Graphics(),next);
                    check(immediateMaterials.equals(java.util.List.of(new CpmPreviewScopeProbe.Location(appearance.skin().location())))
                            && immediateTranslations.get(0)>0,"IMMEDIATE explicit B pending preserves actual visible own A");
                    pending.ready();
                    selected.render(new CpmPreviewScopeProbe.Graphics(),next);
                    immediateMaterials.clear(); immediateTranslations.clear();
                    selected.render(new CpmPreviewScopeProbe.Graphics(),next);
                    check(immediateMaterials.equals(java.util.List.of(new CpmPreviewScopeProbe.Location(nextSkin.location())))
                            && immediateTranslations.get(0)>0,"IMMEDIATE explicit own B replaces A after own native flush");
                } finally { pending.retire(); }
                for (String equipment : java.util.List.of("cape", "custom-elytra", "off", "default-elytra")) {
                    var valid = new java.util.concurrent.atomic.AtomicBoolean(true);
                    var cape = new TextureRegistry.TextureHandle("fixture:retained-" + equipment,64,32);
                    var mode = equipment.equals("off") ? PreviewRenderer.CapeMode.OFF
                            : equipment.equals("cape") ? PreviewRenderer.CapeMode.CAPE : PreviewRenderer.CapeMode.ELYTRA;
                    var withCape = new PreviewRenderer.PreviewAppearance(appearance.skin(),SkinModel.SLIM,
                            java.util.Optional.of(cape),mode,OuterLayerVisibility.allVisible(),
                            !equipment.equals("default-elytra"),false,false,valid::get);
                    var capeRequest = new PreviewRenderer.PreviewRequest(withCape,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.ASSET_THUMBNAIL);
                    String source = "fixture:retained-A-" + equipment;
                    selected.prepareImage(source,System.nanoTime());
                    selected.render(new CpmPreviewScopeProbe.Graphics(),capeRequest);
                    selected.render(new CpmPreviewScopeProbe.Graphics(),capeRequest);
                    var sameSkin = new PreviewRenderer.PreviewAppearance(appearance.skin(),SkinModel.CLASSIC,
                            java.util.Optional.empty(),PreviewRenderer.CapeMode.OFF,OuterLayerVisibility.allVisible());
                    var replacement = new PreviewRenderer.PreviewRequest(sameSkin,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.ASSET_THUMBNAIL);
                    String pendingSource = "fixture:retained-B-" + equipment;
                    selected.prepareImage(pendingSource,System.nanoTime(),false);
                    immediateMaterials.clear(); immediateTranslations.clear();
                    selected.render(new CpmPreviewScopeProbe.Graphics(),replacement);
                    boolean consumesCape = equipment.equals("cape") || equipment.equals("custom-elytra");
                    check(immediateTranslations.get(0)>0 && immediateMaterials.contains(new CpmPreviewScopeProbe.Location(appearance.skin().location()))
                            && (!consumesCape || immediateMaterials.contains(new CpmPreviewScopeProbe.Location(cape.location()))),
                            "IMMEDIATE valid A retained with same-skin B pending " + equipment);
                    valid.set(false);
                    immediateMaterials.clear(); immediateTranslations.clear();
                    selected.render(new CpmPreviewScopeProbe.Graphics(),replacement);
                    check(consumesCape ? !immediateMaterials.contains(new CpmPreviewScopeProbe.Location(cape.location()))
                                    && immediateTranslations.stream().noneMatch(x -> x > 0)
                                    : immediateMaterials.contains(new CpmPreviewScopeProbe.Location(appearance.skin().location())),
                            "IMMEDIATE retired cape must not be consumed; unused custom input must not block A " + equipment);
                    selected.prepareImage(pendingSource,System.nanoTime(),true);
                    selected.render(new CpmPreviewScopeProbe.Graphics(),replacement);
                    immediateMaterials.clear(); immediateTranslations.clear();
                    selected.render(new CpmPreviewScopeProbe.Graphics(),replacement);
                    check(immediateTranslations.get(0)>0 && immediateMaterials.equals(java.util.List.of(new CpmPreviewScopeProbe.Location(appearance.skin().location()))),
                            "IMMEDIATE own same-skin B restores visible material after retirement " + equipment);
                }
                System.out.println("PASS IMMEDIATE explicit prepared source A -> pending B retains actual material -> own B flush -> visible B; cape retirement and unused-input controls family="+family);
            } finally { observeImmediateMenu=false; }
            return;
        }
        var request = new PreviewRenderer.PreviewRequest(appearance,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.CURRENT_APPEARANCE);
        var render = type.getMethod("render", CpmPreviewScopeProbe.Graphics.class, PreviewRenderer.PreviewRequest.class);
        Runnable publicDraw = () -> {
            if (panel == null) call(render,renderer,new CpmPreviewScopeProbe.Graphics(),request);
            else try { panel.draw(new CpmPreviewScopeProbe.Graphics()); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Exception failure) { throw new AssertionError(failure); }
        };
        observeImmediateMenu = true;
        immediateMaterials.clear(); immediateTranslations.clear(); draws = 0; poses = 0;
        try {
            publicDraw.run();
            check(draws == 1 && poses == 0 && immediateMaterials.equals(java.util.List.of(new CpmPreviewScopeProbe.Location(appearance.skin().location()))),
                    "immediate hidden producer completes own material and cleanup");
            check(!immediateTranslations.isEmpty() && immediateTranslations.get(0) < 0,
                    "public immediate menu renderer must hide its first preparation image without stopping producer");
            var image = (EditorPreviewSession) field(renderer, "imageSession");
            check(image.imageVisible() && !image.body().current().getAsBoolean(), "immediate preparation is not visible body control authority");
            immediateTranslations.clear(); immediateMaterials.clear();
            publicDraw.run();
            check(immediateTranslations.get(0) > 0 && draws == 2 && immediateMaterials.size() == 1,
                    "next immediate menu frame shows its own completed image");
            if (panel != null) {
                var next = new PreviewRenderer.PreviewAppearance(
                        new com.naocraftlab.skins.client.TextureRegistry.TextureHandle("fixture:immediate-own-B",64,64),
                        com.naocraftlab.skins.client.SkinModel.SLIM,java.util.Optional.empty(),PreviewRenderer.CapeMode.OFF,
                        com.naocraftlab.skins.client.OuterLayerVisibility.allVisible());
                long[] menuNow = {0};
                set(image,"nanoTime",(java.util.function.LongSupplier)() -> menuNow[0]);
                var pendingNext = NativePlayerSkinLifecycle.pending(next.skin().location());
                try {
                    panel.appearance(next);
                    for (long tick : new long[]{0,299_000_000L}) {
                        menuNow[0]=tick; immediateTranslations.clear(); immediateMaterials.clear();
                        publicDraw.run();
                        check(!immediateTranslations.isEmpty() && immediateTranslations.get(0)>0
                                        && immediateMaterials.equals(java.util.List.of(new CpmPreviewScopeProbe.Location(appearance.skin().location()))),
                                "MENU immediate transition keeps whole confirmed A before threshold tick="+tick+" translations="+immediateTranslations+" materials="+immediateMaterials);
                    }
                    menuNow[0]=300_000_000L; immediateTranslations.clear(); immediateMaterials.clear();
                    CpmPreviewScopeProbe.Graphics.loadingFrames.clear(); publicDraw.run();
                    check(immediateMaterials.isEmpty() && CpmPreviewScopeProbe.Graphics.loadingFrames.size()==1,
                            "MENU immediate transition replaces A with spinner at 300ms");
                } finally { pendingNext.retire(); }
                panel.appearance(next); immediateTranslations.clear(); immediateMaterials.clear();
                publicDraw.run();
                image = (EditorPreviewSession)field(renderer,"imageSession");
                check(immediateTranslations.get(0) > 0 && immediateTranslations.stream().anyMatch(x -> x < 0) && image.imageVisible()
                                && immediateMaterials.equals(java.util.List.of(new CpmPreviewScopeProbe.Location(appearance.skin().location()),
                                        new CpmPreviewScopeProbe.Location(next.skin().location()))),
                        "JOIN immediate actual menu keeps whole A while preparing only own B material hidden");
                immediateTranslations.clear(); publicDraw.run();
                check(immediateTranslations.get(0) > 0, "JOIN immediate replacement becomes actually visible");
                var pending = NativePlayerSkinLifecycle.pending(next.skin().location());
                try {
                    int before = draws;
                    publicDraw.run();
                    image = (EditorPreviewSession)field(renderer,"imageSession");
                    check(draws == before && image.imagePreparing(), "JOIN immediate actual pending native texture does not draw fallback");
                    long[] now = {0};
                    image = new EditorPreviewSession(() -> now[0]);
                    set(renderer, "imageSession", image);
                    CpmPreviewScopeProbe.Graphics.loadingFrames.clear();
                    publicDraw.run();
                    now[0] = 300_000_000L;
                    publicDraw.run();
                    check(CpmPreviewScopeProbe.Graphics.loadingFrames.size() == 1, "LOADING immediate menu native crop is painted after delay");
                    var frame = CpmPreviewScopeProbe.Graphics.loadingFrames.get(0);
                    check(frame.resource().equals("nclskins:textures/gui/icons/status/preview_loading.png")
                            && frame.v() == 96 && frame.width() == 32 && frame.textureHeight() == 256, "LOADING immediate native crop dimensions");
                    pending.failed(); publicDraw.run();
                    check(draws == before && !image.imagePreparing() && !image.imageSpinner(), "JOIN immediate actual terminal texture is not infinite spinner");
                } finally { pending.retire(); }
                var failing = new PreviewRenderer.PreviewAppearance(
                        new com.naocraftlab.skins.client.TextureRegistry.TextureHandle("fixture:immediate-failure-C",64,64),
                        com.naocraftlab.skins.client.SkinModel.SLIM,java.util.Optional.empty(),PreviewRenderer.CapeMode.OFF,
                        com.naocraftlab.skins.client.OuterLayerVisibility.allVisible());
                panel.appearance(failing);
                flushed = () -> { throw new IllegalStateException("synthetic joined immediate flush failure"); };
                Throwable failure = null;
                try { publicDraw.run(); } catch (RuntimeException | Error thrown) { failure=thrown; }
                flushed = () -> {};
                image = (EditorPreviewSession)field(renderer,"imageSession");
                check(failure instanceof IllegalStateException && failure.getMessage().equals("synthetic joined immediate flush failure")
                                && failure.getSuppressed().length == 0 && poses == 0 && !image.imageVisible() && !image.imagePreparing(),
                        "JOIN immediate actual menu renderer failure is terminal after native cleanup");
                int settledDraws = draws; publicDraw.run();
                check(draws == settledDraws && !image.imageSpinner(), "JOIN immediate terminal native render cannot ambient retry");
                panel.close();
                int before = draws; publicDraw.run();
                check(draws == before, "JOIN immediate real menu closed facade rejects further frames");
            }
            System.out.println("PASS immediate menu hidden producer -> own material/flush/cleanup -> visible image; " + family);
        } finally {
            if (panel != null) panel.close();
            observeImmediateMenu = false; immediateMaterials.clear(); immediateTranslations.clear();
            draw = () -> {}; pop = () -> {}; flushed = () -> {}; lighting = () -> {};
        }
        if (menuPanelJoin) {
            String previousPause = System.getProperty("nclskins.menuPause");
            try {
                for (boolean pause : new boolean[]{false,true}) {
                    System.setProperty("nclskins.menuPause",Boolean.toString(pause));
                    for (boolean glint : new boolean[]{false,true})
                        immediateMenuAnimation(loader,prefix,appearance,family,pause,glint);
                }
            } finally {
                if (previousPause == null) System.clearProperty("nclskins.menuPause");
                else System.setProperty("nclskins.menuPause",previousPause);
            }
        }
    }

    private static void immediateMenuAnimation(ClassLoader loader, String prefix, PreviewAppearance appearance,
            String family, boolean pause, boolean glint) throws Exception {
        String role = " family=" + family + " pause=" + pause + " glint=" + glint;
        var frames = List.of(new TextureRegistry.TextureHandle("fixture:menu-frame-A",64,32),
                new TextureRegistry.TextureHandle("fixture:menu-frame-B",64,32));
        var timeline = com.naocraftlab.skins.core.png.CapeTimeline.pngStrip(2);
        var previousClient = CpmPreviewScopeProbe.Client.current;
        CpmPreviewScopeProbe.Client.current = new CpmPreviewScopeProbe.Client();
        try (var panel = new CpmMenuPanelSlice(loader,prefix,appearance,CpmPreviewScopeProbe.Graphics.class,CpmPreviewScopeProbe.Client.class)) {
            panel.install(com.naocraftlab.skins.core.config.MenuPreviewPlacement.RIGHT);
            var renderer = initializeImmediateRenderer(panel.renderer());
            panel.animate(timeline,frames,glint);
            var owner = panel.capeOwner();
            observeImmediateMenu = true;
            immediateMenuFrame(panel,appearance,frames.get(0),false,role);
            immediateMenuFrame(panel,appearance,frames.get(0),true,role);
            Object session = field(renderer,"imageSession");
            for (long elapsed : new long[]{100,100,200,300,700}) {
                panel.animationTime(elapsed);
                var frame = frames.get(timeline.frameAt(elapsed));
                check(panel.capeOwner() == owner && owner.ownsFrame(frame.location()), "MENU animation owner and frame" + role);
                immediateMenuFrame(panel,appearance,frame,true,role + " elapsed=" + elapsed);
                check(field(renderer,"imageSession") == session, "MENU animation preserves prepared image session" + role);
            }

            var frame = frames.get(timeline.frameAt(700));
            owner.installFrame("fixture:unpublished-menu-frame");
            immediateMenuFrame(panel,appearance,null,false,role);
            owner.installFrame(frame.location());
            immediateMenuFrame(panel,appearance,frame,true,role);
            check(field(renderer,"imageSession") == session, "MENU unowned input cannot replace visible image session" + role);

            panel.replaceCapeAsset(frame);
            var replacement = panel.capeOwner();
            check(replacement != owner && !owner.isActive(), "MENU replacement changes owner even with same texture" + role);
            flushed = () -> replacement.installFrame("fixture:superseded-during-menu-flush");
            try {
                immediateMenuFrame(panel,appearance,frame,false,role);
                var preparing = (EditorPreviewSession) field(renderer,"imageSession");
                check(preparing.imagePreparing() && !preparing.imageVisible(),
                        "MENU a frame replaced during hidden draw cannot confirm readiness" + role);
            } finally { flushed = () -> {}; }
            replacement.installFrame(frame.location());
            immediateMenuFrame(panel,appearance,frame,false,role);
            check(field(renderer,"imageSession") != session, "MENU replacement needs its own image session" + role);
            immediateMenuFrame(panel,appearance,frame,true,role);
            replacement.retire();
            immediateMenuFrame(panel,appearance,null,false,role);
            System.out.println("PASS immediate menu animated cape continuity/currentness" + role);
        } finally {
            observeImmediateMenu = false;
            immediateMaterials.clear(); immediateTranslations.clear(); immediateDrawnMaterials.clear();
            CpmPreviewScopeProbe.Client.current = previousClient;
        }
    }

    private static void immediateMenuFrame(CpmMenuPanelSlice panel, PreviewAppearance appearance,
            TextureRegistry.TextureHandle cape, boolean visible, String role) throws Exception {
        immediateTranslations.clear(); immediateMaterials.clear(); immediateDrawnMaterials.clear();
        CpmPreviewScopeProbe.Graphics.loadingFrames.clear();
        panel.draw(new CpmPreviewScopeProbe.Graphics());
        check(CpmPreviewScopeProbe.Graphics.loadingFrames.isEmpty(), "MENU animation must not restart loading" + role);
        if (cape == null) {
            check(immediateTranslations.isEmpty() && immediateDrawnMaterials.isEmpty(), "MENU unowned cape must not draw" + role);
            return;
        }
        check(immediateDrawnMaterials.equals(List.of(new CpmPreviewScopeProbe.Location(appearance.skin().location()),
                new CpmPreviewScopeProbe.Location(cape.location()))), "MENU animation consumes exact current skin and cape" + role);
        check(!immediateTranslations.isEmpty() && (visible ? immediateTranslations.get(0) > 0 : immediateTranslations.get(0) < 0),
                "MENU animation expected " + (visible ? "visible" : "hidden") + " whole player" + role);
    }

    private static Object newImmediateRenderer(Class<?> rendererType) {
        try {
            return initializeImmediateRenderer(rendererType.getConstructor().newInstance());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static Object initializeImmediateRenderer(Object renderer) throws ReflectiveOperationException {
        for (var field : renderer.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            Object value = field.getType() == Model.class ? new Model() : field.getType() == Part.class ? new Part()
                    : field.getName().equals("imageSession") ? new EditorPreviewSession() : null;
            if (value != null) { field.setAccessible(true); field.set(renderer, value); }
        }
        return renderer;
    }

    private static void immediateStaticHost(Class<?> rendererType,PreviewRenderer.PreviewAppearance appearance) throws Exception {
        var image=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"PNG",bytes);
        try(var base=new com.naocraftlab.skins.runtime.GalleryNativePassFixture(appearance,bytes.toByteArray());var subject=base.freshOwned()) {
            var hostType=CpmPreviewScreenSlice.loadNative(CpmPreviewScreenSlice.availableOwner(),rendererType);
            var allocatedRenderers = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object,Boolean>());
            for(var surface:subject.staticSurfaces()) {
                Object host=hostType.getConstructor().newInstance();var registry=new CpmOwnedPreviewDispatchProbe.Registry();
                try(var skins=new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.PLAYER_SKIN,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
                    var capes=new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.IMAGE,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
                    for(var field:hostType.getDeclaredFields()) {
                        Object value=switch(field.getName()) {
                            case "runtime" -> subject.runtime;case "skinTextures" -> skins;case "capeTextures" -> capes;
                            case "capabilities" -> new CpmOwnedPreviewDispatchProbe.Capabilities() {
                                @SuppressWarnings({"unchecked","rawtypes"}) public PreviewRenderer<CpmOwnedPreviewDispatchProbe.Graphics> createSimplePreviewRenderer(){
                                    Object renderer = newImmediateRenderer(rendererType);
                                    check(allocatedRenderers.add(renderer),"IMMEDIATE distinct host slot owns a fresh simple renderer");
                                    return (PreviewRenderer)renderer;
                                }
                                @SuppressWarnings({"unchecked","rawtypes"}) public PreviewRenderer<CpmOwnedPreviewDispatchProbe.Graphics> createEditorPreviewRenderer(){
                                    Object renderer = newImmediateRenderer(rendererType);
                                    check(allocatedRenderers.add(renderer),"IMMEDIATE distinct host slot owns a fresh editor renderer");
                                    return (PreviewRenderer)renderer;
                                }
                            };
                            default -> field.getType()==Map.class?new HashMap<>():null;
                        };if(value!=null)field.set(host,value);
                    }
                    var key=hostType.getDeclaredMethod("previewAssetKey",com.naocraftlab.skins.runtime.ViewSpec.Preview.class);key.setAccessible(true);
                    skins.request(key.invoke(host,surface.preview()),()->java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.of(surface.png())),()->{});
                    var copy=CpmOwnedPreviewDispatchProbe.class.getDeclaredMethod("copyView",com.naocraftlab.skins.runtime.ViewSpec.class,com.naocraftlab.skins.runtime.ViewSpec.Preview.class);copy.setAccessible(true);
                    var view=(com.naocraftlab.skins.runtime.ViewSpec)copy.invoke(null,surface.view(),surface.preview());
                    var sync=hostType.getDeclaredMethod("synchronizePreviews",com.naocraftlab.skins.runtime.ViewSpec.class);sync.setAccessible(true);sync.invoke(host,view);
                    var paint=hostType.getDeclaredMethod("renderStaticPreviews",CpmPreviewScopeProbe.Graphics.class,com.naocraftlab.skins.runtime.ViewSpec.class);paint.setAccessible(true);
                    var handle=skins.handle(key.invoke(host,surface.preview())).orElseThrow();
                    observeImmediateMenu=true;immediateMaterials.clear();immediateTranslations.clear();draws=0;
                    paint.invoke(host,new CpmPreviewScopeProbe.Graphics(),view);
                    check(draws==1 && immediateTranslations.get(0)<0 && immediateMaterials.equals(java.util.List.of(new CpmPreviewScopeProbe.Location(handle.location()))),"IMMEDIATE CARD own hidden native material/flush "+surface.role());
                    immediateTranslations.clear();immediateMaterials.clear();paint.invoke(host,new CpmPreviewScopeProbe.Graphics(),view);
                    check(draws==2 && immediateTranslations.get(0)>0 && immediateMaterials.equals(java.util.List.of(new CpmPreviewScopeProbe.Location(handle.location()))),"IMMEDIATE CARD separate visible own material "+surface.role());
                    System.out.println("PASS IMMEDIATE CARD actual presenter="+surface.role()+" -> compiled clipped static host -> own hidden native flush -> separate visible own material; synthetic GPU terminal");
                }
            }
        } finally {observeImmediateMenu=false;immediateMaterials.clear();immediateTranslations.clear();}
    }

    private static void submissionPoolCards(Loader loader, Class<?> bakedType, java.lang.reflect.Method prepare, Class<?> dispatcherType) throws Exception {
        var pixels = new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var png = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(pixels,"PNG",png);
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:pool-host",64,64), SkinModel.CLASSIC, java.util.Optional.empty(), PreviewRenderer.CapeMode.OFF, OuterLayerVisibility.allVisible());
        try (var base = new com.naocraftlab.skins.runtime.GalleryNativePassFixture(appearance,png.toByteArray()); var subject = base.freshOwned()) {
            var hostType = CpmPreviewScreenSlice.loadNative(CpmPreviewScreenSlice.availableOwner(),bakedType);
            for (String role : java.util.List.of("gallery", "skin-catalog")) {
                Object host = hostType.getConstructor().newInstance(); var registry = new CpmOwnedPreviewDispatchProbe.Registry();
                try (var skins = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.PLAYER_SKIN,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
                     var capes = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.IMAGE,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
                    for (var f : hostType.getDeclaredFields()) {
                        Object value = switch(f.getName()) {
                            case "runtime" -> subject.runtime; case "editorRenderer" -> submissionOwner(loader,loader.loadClass(bakedType.getPackageName()+".SubmissionPreviewRenderer"));
                            case "skinTextures" -> skins; case "capeTextures" -> capes; case "textureRegistry" -> registry;
                            case "width" -> 854; case "height" -> 480; default -> f.getType()==Map.class ? new HashMap<>() : null;
                        }; if (value != null) f.set(host,value);
                    }
                    var view = role.equals("gallery") ? subject.staticSurfaces().get(0).view() : subject.catalogFrame();
                    var key = hostType.getDeclaredMethod("skinKey",com.naocraftlab.skins.runtime.ViewSpec.Preview.class);key.setAccessible(true);
                    for (var preview : view.previews()) {
                        if (role.equals("gallery") && !preview.id().startsWith("gallery.preset.")) continue;
                        Object k = key.invoke(null,preview); byte[] bytes = role.equals("gallery") ? subject.ownedBytes(preview) : png.toByteArray();
                        skins.request(k,()->java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.of(bytes)),()->{});
                        @SuppressWarnings("unchecked") var keys = (Map<String,Object>)field(host,"previewKeys"); keys.put(preview.id(),k);
                    }
                    var paint = hostType.getDeclaredMethod("renderStaticPreviews",Graphics.class,com.naocraftlab.skins.runtime.ViewSpec.class);paint.setAccessible(true);
                    var replace = loader.loadClass(bakedType.getPackageName()+".BakedPreviewSubmission").getMethod("replace",Object.class,PipState.class);
                    int[] parentClip = {0};
                    var states = new java.util.ArrayList<Object>(); enqueue = graphics -> states.add(call(replace,null,graphics,
                            new MenuState(new Rectangle(0,0,1000 + parentClip[0],1000))));
                    Class<?> registration = loader.loadClass("net.neoforged.neoforge.client.gui.PictureInPictureRendererRegistration");
                    Class<?> poolType = loader.loadClass("net.neoforged.neoforge.client.gui.PictureInPictureRendererPool");
                    Object registered = registration.getConstructor(Class.class,java.util.function.Function.class).newInstance(prepare.getParameterTypes()[0],(java.util.function.Function<Object,Object>)buffers -> {
                        try { return dispatcherType.getConstructor(Buffers.class).newInstance(buffers); } catch (Exception failure) { throw new AssertionError(failure); }
                    });
                    Object pool = poolType.getConstructor(registration,Buffers.class).newInstance(registered,new Buffers());
                    var get = poolType.getMethod("get",PipState.class,int.class,boolean.class);var prune = poolType.getMethod("clearUnusedRenderers");
                    var gui = new CpmExtractionBodyProbe.GuiRenderState(); var graphics = new Graphics();
                    var stable = new java.util.IdentityHashMap<Object,PreviewModelFrames.Body>(); var generations = new java.util.IdentityHashMap<Object,Object>();
                    var assignments = new java.util.IdentityHashMap<Object,Object>(); var textures = new java.util.IdentityHashMap<Object,CpmExtractionBodyProbe.LegacyTexture>(); int[] swaps = {0};
                    try {
                        for (int frame=0;frame<4;frame++) {
                            parentClip[0]=frame;states.clear();paint.invoke(host,graphics,view);check(states.size()>=2,"POOL both actual "+role+" card sources emitted");
                            var ordered = new java.util.ArrayList<>(states);if ((frame&1)!=0) java.util.Collections.reverse(ordered);
                            if(frame==0)check(states.stream().allMatch(s->((PipState)s).x1()<0),"POOL genuine own hidden image producers");
                            else check(states.stream().allMatch(s->((PipState)s).x0()>=0),"POOL stable "+role+" images do not rehide or restart");
                            for (Object state : ordered) scheduledBaked(state,false);
                            var materials = CpmExtractionBodyProbe.queuedMaterials();materials.clear(); final int currentFrame=frame;
                            CpmExtractionBodyProbe.consumerFrame(loader,gui,()->{
                                for (Object state : ordered) {
                                    Object dispatcher = call(get,pool,state,1,true);if(dispatcher==null)dispatcher=call(get,pool,state,1,false);
                                    Object target;try{target=state.getClass().getMethod("target").invoke(state);}catch(Exception failure){throw new AssertionError(failure);}
                                    Object previous=assignments.put(target,dispatcher);if(previous!=null&&previous!=dispatcher)swaps[0]++;
                                    call(prepare,dispatcher,state,gui,1);
                                    for (var body : stable.values()) check(body.current().getAsBoolean(),"POOL real reassignment preserves established own "+role+" material currentness");
                                }
                            },()->{
                                check(materials.size()==states.size(),"POOL every own physical material reaches native GUI consumer");
                                for(int i=0;i<ordered.size();i++)try{
                                    Object state=ordered.get(i);Object target=state.getClass().getMethod("target").invoke(state);var texture=materials.get(i).texture;
                                    check(!texture.closed && texture.data.equals(field(state,"skinTexture")),"POOL consumed exact own source material");
                                    if(currentFrame==0)textures.put(target,texture);else check(textures.get(target)==texture,"POOL dispatcher reassignment keeps exact image physical target");
                                }catch(Exception failure){throw new AssertionError(failure);}
                                CpmExtractionBodyProbe.consumerSubmitted();
                            });
                            CpmExtractionBodyProbe.signalConsumers();call(prune,pool);
                            @SuppressWarnings("unchecked") var renderers=(Map<String,Object>)field(host,"bakedRenderers");
                            for(Object renderer:renderers.values()) {var image=(EditorPreviewSession)field(renderer,"imageSession");check(image.imageVisible(),"POOL own hidden/visible fence keeps image ready");
                                if(frame==0){stable.put(image,(PreviewModelFrames.Body)field(image,"imageMaterial"));generations.put(image,field(image,"imageGeneration"));}
                                else check(generations.get(image).equals(field(image,"imageGeneration")),"POOL image attempt continuity across native reassignment");}
                        }
                        check(swaps[0]>0,"POOL actual Neo native reuse must reassign at least one real snapshot");
                        call(prune,pool);for(var body:stable.values())check(body.current().getAsBoolean(),"POOL empty-frame prune does not retire GUI material");
                        CpmExtractionBodyProbe.consumerFrame(loader,gui,()->{for(Object state:states){Object dispatcher=call(get,pool,state,1,false);call(prepare,dispatcher,state,gui,1);}},CpmExtractionBodyProbe::consumerSubmitted);
                        closeTarget(pool);CpmExtractionBodyProbe.closeConsumerOwner(loader,gui);
                        check(textures.values().stream().noneMatch(t->t.closed),"POOL native parent retirement waits for queued consumer fence");
                        for(var body:stable.values())check(!body.current().getAsBoolean(),"POOL true parent close rejects old material currentness");
                        CpmExtractionBodyProbe.signalConsumers();check(textures.values().stream().allMatch(t->t.closes==1),"POOL parent-close resources retire exactly once after fence");
                        System.out.println("PASS SUBMISSION_POOL "+role+" actual snapshots/Neo pool/native reuse/prepare; hidden and separate visible consumer/fence; stable physical material/currentness/image attempt; empty prune; true parent close; swaps="+swaps[0]);
                    } finally {closeTarget(pool);CpmExtractionBodyProbe.signalConsumers();@SuppressWarnings("unchecked") var renderers=(Map<String,Object>)field(host,"bakedRenderers");for(var renderer:renderers.values())closeTarget(renderer);}
                }
            }
        }
    }

    private static boolean getNeoPoolResource() {
        return CpmBakedDisplayProbe.class.getClassLoader().getResource("net/neoforged/neoforge/client/gui/PictureInPictureRendererPool.class") != null;
    }

    private static void staticCardHost(Loader loader, Class<?> bakedType, java.lang.reflect.Method prepare,
            Object dispatcher, boolean late, boolean extraction) throws Exception {
        var pixels = new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var bytes = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(pixels,"PNG",bytes);
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:host",64,64),
                SkinModel.CLASSIC, java.util.Optional.empty(), PreviewRenderer.CapeMode.OFF, OuterLayerVisibility.allVisible());
        try (var base = new com.naocraftlab.skins.runtime.GalleryNativePassFixture(appearance,bytes.toByteArray());
             var subject = base.freshOwned()) {
            var runtime = subject.runtime;
            var hostType = CpmPreviewScreenSlice.loadNative(CpmPreviewScreenSlice.availableOwner(),bakedType);
            Object host = hostType.getConstructor().newInstance();
            var registry = new CpmOwnedPreviewDispatchProbe.Registry();
            try (var skins = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.PLAYER_SKIN,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
                 var capes = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.IMAGE,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
                for (var f : hostType.getDeclaredFields()) {
                    f.setAccessible(true);
                    Object value = switch(f.getName()) {
                        case "runtime" -> runtime;
                        case "editorRenderer" -> submissionOwner(loader,loader.loadClass(bakedType.getPackageName()+"."+(extraction?"AvatarRenderStatePreviewRenderer":"SubmissionPreviewRenderer")));
                        case "skinTextures" -> skins;
                        case "capeTextures" -> capes;
                        case "textureRegistry" -> registry;
                        case "width" -> 854;
                        case "height" -> 480;
                        default -> f.getType() == Map.class ? new HashMap<>() : null;
                    };
                    if (value != null) f.set(host,value);
                }
                for(var surface:subject.staticSurfaces()) {
                var view = surface.view();
                var sync = hostType.getDeclaredMethod(extraction ? "syncPreviewAssets" : "synchronizePreviewAssets",com.naocraftlab.skins.runtime.ViewSpec.class);
                sync.setAccessible(true);
                if(surface.role().equals("gallery")) {sync.invoke(host,view);subject.drainWorkers();}
                var preview = surface.preview();
                var copy = CpmOwnedPreviewDispatchProbe.class.getDeclaredMethod("copyView",com.naocraftlab.skins.runtime.ViewSpec.class,com.naocraftlab.skins.runtime.ViewSpec.Preview.class);
                copy.setAccessible(true); view = (com.naocraftlab.skins.runtime.ViewSpec) copy.invoke(null,view,preview);
                var key = hostType.getDeclaredMethod("skinKey",com.naocraftlab.skins.runtime.ViewSpec.Preview.class); key.setAccessible(true);
                skins.request(key.invoke(null,preview),()->java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.of(surface.png())),()->{});
                var handle = skins.handle(key.invoke(null,preview)).orElseThrow();
                @SuppressWarnings("unchecked") var keys=(Map<String,Object>)field(host,extraction?"previewSkinKeys":"previewKeys");keys.put(preview.id(),key.invoke(null,preview));
                var paint = hostType.getDeclaredMethod("renderStaticPreviews",Graphics.class,com.naocraftlab.skins.runtime.ViewSpec.class); paint.setAccessible(true);
                var replace = loader.loadClass(bakedType.getPackageName()+"."+(extraction?"NclBakedPlayerSubmission":"BakedPreviewSubmission")).getMethod("replace",Object.class,PipState.class);
                Object[] queued = {null};
                enqueue = graphics -> queued[0] = call(replace,null,graphics,new MenuState(((Graphics)graphics).scissor));
                var graphics = new Graphics();
                paint.invoke(host,graphics,view);
                check(queued[0] != null,"CARD actual compiled static host must enqueue own preparation");
                scheduledBaked(queued[0],extraction);
                var materials = CpmExtractionBodyProbe.queuedMaterials(); materials.clear();
                CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued[0],late),() -> {
                    check(materials.size()==1 && materials.get(0).texture.data.equals(new Identifier(handle.location())),"CARD own host/cache material reaches actual native consumer");
                    CpmExtractionBodyProbe.consumerSubmitted();
                });
                CpmExtractionBodyProbe.signalConsumers();
                var renderers = (Map<?,?>)field(host,extraction ? "galleryRenderers" : "bakedRenderers");
                var image = (EditorPreviewSession)field(renderers.get((extraction ? "" : "preview:")+preview.id()),"imageSession");
                check(image.imageVisible(),"CARD own signalled consumer completes actual host image");
                paint.invoke(host,graphics,view);
                check(((PipState)queued[0]).x0()>=0,"CARD completed static image becomes visible through the same host on next frame");
                scheduledBaked(queued[0],extraction);
                int previous = materials.size();
                CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued[0],late),() -> {
                    check(materials.size()==previous+1 && materials.get(previous).texture.data.equals(new Identifier(handle.location()))
                            && !materials.get(previous).texture.closed,"CARD visible actual consumer receives the own completed material");
                    CpmExtractionBodyProbe.consumerSubmitted();
                });
                CpmExtractionBodyProbe.signalConsumers();
                for (var value : renderers.values()) ((AutoCloseable)value).close();
                renderers.clear();
                System.out.println("PASS CARD actual presenter="+surface.role()+" preview="+preview.id()+" -> compiled static host/cache/clip/SDK collector -> own hidden and visible native consumers/fences; synthetic GPU terminal");
                }
                var capeView=subject.capeSurface();
                var sync=hostType.getDeclaredMethod(extraction?"syncPreviewAssets":"synchronizePreviewAssets",com.naocraftlab.skins.runtime.ViewSpec.class);sync.setAccessible(true);
                sync.invoke(host,capeView);subject.drainWorkers();sync.invoke(host,capeView);subject.drainWorkers();
                var equipment=capeView.backEquipmentPreviews().stream().filter(p -> p.projection()==com.naocraftlab.skins.runtime.ViewSpec.BackEquipmentPreview.Projection.EQUIPMENT).findFirst().orElseThrow();
                var paint=hostType.getDeclaredMethod(extraction?"drawBackEquipmentPreviews":"renderBackEquipment",Graphics.class,com.naocraftlab.skins.runtime.ViewSpec.class);paint.setAccessible(true);
                var replace=loader.loadClass(bakedType.getPackageName()+"."+(extraction?"NclBakedPlayerSubmission":"BakedPreviewSubmission")).getMethod("replace",Object.class,PipState.class);
                for(var mode:com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Mode.values()) {
                    var selected=new com.naocraftlab.skins.runtime.ViewSpec.BackEquipmentPreview(equipment.id(),equipment.bounds(),equipment.capeId(),mode,true);
                    var selectedView=new com.naocraftlab.skins.runtime.ViewSpec("editor",capeView.title(),854,480,java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.Optional.empty(),java.util.List.of(),java.util.Optional.empty(),capeView.clipRegions(),java.util.List.of(selected));
                    Object[] queued={null};enqueue=graphics -> queued[0]=call(replace,null,graphics,new MenuState(((Graphics)graphics).scissor));
                    var handle=capes.handle(com.naocraftlab.skins.runtime.ViewHostPolicy.cardCapeKey(selected)).orElseThrow();
                    var materials=CpmExtractionBodyProbe.queuedMaterials();materials.clear();
                    paint.invoke(host,new Graphics(),selectedView);
                    check(queued[0]!=null && ((PipState)queued[0]).x1()<0,"EQUIPMENT actual clipped cape catalog hidden own payload "+mode);
                    scheduledBaked(queued[0],extraction);
                    CpmExtractionBodyProbe.consumerFrame(loader,()->prepareRaw(prepare,dispatcher,queued[0],late),()->{check(materials.size()==1 && materials.get(0).texture.data.equals(new Identifier(handle.location())),"EQUIPMENT own cape material "+mode);CpmExtractionBodyProbe.consumerSubmitted();});
                    CpmExtractionBodyProbe.signalConsumers();materials.clear();
                    paint.invoke(host,new Graphics(),selectedView);check(((PipState)queued[0]).x0()>=0,"EQUIPMENT own prepared cape catalog becomes visible "+mode);scheduledBaked(queued[0],extraction);
                    CpmExtractionBodyProbe.consumerFrame(loader,()->prepareRaw(prepare,dispatcher,queued[0],late),()->{check(materials.size()==1 && materials.get(0).texture.data.equals(new Identifier(handle.location())),"EQUIPMENT visible own material "+mode);CpmExtractionBodyProbe.consumerSubmitted();});
                    CpmExtractionBodyProbe.signalConsumers();
                    @SuppressWarnings("unchecked") var renderers=(Map<String,Object>)field(host,extraction?"galleryRenderers":"bakedRenderers");for(var value:renderers.values())((AutoCloseable)value).close();renderers.clear();
                    System.out.println("PASS EQUIPMENT actual CapeCatalog presenter -> clipped host -> SDK collector -> own hidden/visible material/fences mode="+mode);
                }
            }
        }
    }

    private static void galleryModeHost(Loader loader, Class<?> bakedType, java.lang.reflect.Method prepare,
            Object dispatcher, boolean late, boolean extraction) throws Exception {
        var pixels = new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var bytes = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(pixels,"PNG",bytes);
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:gallery-mode",64,64),
                SkinModel.CLASSIC, java.util.Optional.empty(), PreviewRenderer.CapeMode.OFF, OuterLayerVisibility.allVisible());
        try (var base = new com.naocraftlab.skins.runtime.GalleryNativePassFixture(appearance,bytes.toByteArray());
             var subject = base.freshOwned()) {
            subject.localCapeWinner(); subject.selectB(); subject.drainWorkers();
            var hostType = CpmPreviewScreenSlice.loadNative(CpmPreviewScreenSlice.availableOwner(),bakedType);
            Object host = hostType.getConstructor().newInstance();
            var registry = new CpmOwnedPreviewDispatchProbe.Registry();
            try (var skins = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.PLAYER_SKIN,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
                 var capes = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.IMAGE,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
                for (var f : hostType.getDeclaredFields()) {
                    f.setAccessible(true);
                    Object value = switch(f.getName()) {
                        case "runtime" -> subject.runtime;
                        case "editorRenderer" -> submissionOwner(loader,loader.loadClass(bakedType.getPackageName()+"."+(extraction?"AvatarRenderStatePreviewRenderer":"SubmissionPreviewRenderer")));
                        case "skinTextures" -> skins; case "capeTextures" -> capes; case "textureRegistry" -> registry;
                        case "width" -> 854; case "height" -> 480;
                        default -> f.getType() == Map.class ? new HashMap<>() : null;
                    };
                    if (value != null) f.set(host,value);
                }
                var sync = hostType.getDeclaredMethod(extraction ? "syncPreviewAssets" : "synchronizePreviewAssets",com.naocraftlab.skins.runtime.ViewSpec.class); sync.setAccessible(true);
                var paint = hostType.getDeclaredMethod("renderStaticPreviews",Graphics.class,com.naocraftlab.skins.runtime.ViewSpec.class); paint.setAccessible(true);
                var replace = loader.loadClass(bakedType.getPackageName()+"."+(extraction?"NclBakedPlayerSubmission":"BakedPreviewSubmission")).getMethod("replace",Object.class,PipState.class);
                var states = new java.util.ArrayList<Object>();
                enqueue = graphics -> states.add(call(replace,null,graphics,new MenuState(((Graphics)graphics).scissor)));
                var graphics = new Graphics();
                var materials = CpmExtractionBodyProbe.queuedMaterials();
                var view = subject.runtime.view(854,480,0,0);
                for (int warm=0;warm<3;warm++) { sync.invoke(host,view); subject.drainWorkers(); view=subject.runtime.view(854,480,0,0); }
                var cards = view.previews().stream().filter(p -> p.id().startsWith("gallery.preset.")).toList();
                check(cards.size()==2 && cards.stream().filter(p -> p.capeId().isPresent()).count()==1,
                        "GALLERY complete actual view has cape and no-cape cards");
                for(int frame=0;frame<2;frame++) {
                    sync.invoke(host,view); states.clear(); paint.invoke(host,graphics,view);
                    check(states.size()==2,"GALLERY both actual cards enqueue their own image");
                    for(Object state:java.util.List.copyOf(states)) {
                        scheduledBaked(state,extraction);
                        CpmExtractionBodyProbe.consumerFrame(loader,()->prepareRaw(prepare,dispatcher,state,late),CpmExtractionBodyProbe::consumerSubmitted);
                        CpmExtractionBodyProbe.signalConsumers();
                    }
                    if(frame==1)check(states.stream().allMatch(s -> ((PipState)s).x0()>=0),"GALLERY A established by separate actual visible consumers");
                }
                @SuppressWarnings("unchecked") var renderers=(Map<String,Object>)field(host,extraction?"galleryRenderers":"bakedRenderers");
                try {
                    for(int transition=0;transition<2;transition++) {
                        var before=view;
                        var capeCard=cards.stream().filter(p -> p.capeId().isPresent()).findFirst().orElseThrow();
                        var bareCard=cards.stream().filter(p -> p.capeId().isEmpty()).findFirst().orElseThrow();
                        var oldMode=capeCard.capeMode();
                        Object bareTarget=field(renderers.get((extraction?"":"preview:")+bareCard.id()),"target");
                        var control=before.widget("gallery.preview_mode").orElseThrow();
                        check(control.visible() && control.enabled() && control.activationEnabled(),"GALLERY actual mode control admitted");
                        subject.runtime.dispatchWidget(control.id(),false,com.naocraftlab.skins.runtime.InteractionOrigin.POINTER);
                        view=subject.runtime.view(854,480,0,0); sync.invoke(host,view); subject.drainWorkers();
                        cards=view.previews().stream().filter(p -> p.id().startsWith("gallery.preset.")).toList();
                        var nextCape=cards.stream().filter(p -> p.id().equals(capeCard.id())).findFirst().orElseThrow();
                        var nextBare=cards.stream().filter(p -> p.id().equals(bareCard.id())).findFirst().orElseThrow();
                        check(nextCape.capeMode()!=oldMode && nextBare.capeMode()==PreviewRenderer.CapeMode.OFF,"GALLERY admitted toggle changes cape card only");
                        states.clear(); paint.invoke(host,graphics,view);
                        var visible=states.stream().filter(s -> ((PipState)s).x0()>=0).toList();
                        var hidden=states.stream().filter(s -> ((PipState)s).x1()<0).toList();
                        System.out.println("GALLERY transition="+transition+" mode="+oldMode+"->"+nextCape.capeMode()+" completePreviews="+view.previews().size()+" visible="+visible.size()+" hidden="+hidden.size());
                        check(visible.size()==2 && hidden.size()==1,"GALLERY pending B retains own visible A and stable no-cape card; visible="+visible.size()+" required=2");
                        Object retained=visible.stream().filter(s -> { try{return field(s,"capeMode")==oldMode;}catch(Exception e){throw new AssertionError(e);} }).findFirst().orElseThrow();
                        Object pending=hidden.get(0);
                        Object bare=visible.stream().filter(s -> fieldUnchecked(s,"capeMode")==PreviewRenderer.CapeMode.OFF).findFirst().orElseThrow();
                        check(field(bare,"target")==bareTarget,"GALLERY no-cape control retains the same physical target");
                        check(field(pending,"capeMode")==nextCape.capeMode() && field(retained,"target")!=field(pending,"target"),"GALLERY A/B mode and physical targets independent");
                        materials.clear();
                        CpmExtractionBodyProbe.consumerFrame(loader,()->prepareRaw(prepare,dispatcher,pending,late),CpmExtractionBodyProbe::consumerSubmitted);
                        var successorFences=takeConsumerFences();
                        var successor=materials.get(0).texture;
                        int priorDraws=draws;
                        CpmExtractionBodyProbe.consumerFrame(loader,()->prepareRaw(prepare,dispatcher,retained,late),()->{
                            check(materials.size()==2 && materials.get(1).texture!=successor && !materials.get(1).texture.closed,
                                    "GALLERY queued A survives B hidden preparation without physical alias");
                            check(draws==priorDraws && materials.get(1).texture.materialInputs.contains(fieldUnchecked(retained,"skinTexture"))
                                    && materials.get(1).texture.materialInputs.contains(fieldUnchecked(retained,"attachmentTexture")),
                                    "GALLERY retained whole A consumes own prior cape/skin output without old input submission");
                            CpmExtractionBodyProbe.consumerSubmitted();
                        });
                        CpmExtractionBodyProbe.signalConsumers();
                        CpmExtractionBodyProbe.consumerFrame(loader,()->prepareRaw(prepare,dispatcher,bare,late),()->{
                            var material=materials.get(materials.size()-1).texture;
                            check(!material.closed && material.data.equals(fieldUnchecked(bare,"skinTexture"))
                                    && material.materialInputs.equals(java.util.List.of(fieldUnchecked(bare,"skinTexture"))),
                                    "GALLERY no-cape control consumes stable own skin-only material while B fence is held");
                            CpmExtractionBodyProbe.consumerSubmitted();
                        });
                        CpmExtractionBodyProbe.signalConsumers();
                        states.clear();sync.invoke(host,view);paint.invoke(host,graphics,view);
                        check(states.stream().filter(s -> ((PipState)s).x0()>=0).count()==2 && states.stream().anyMatch(s -> ((PipState)s).x1()<0),"GALLERY B preparation alone cannot replace A before own fence");
                        successorFences.forEach(Runnable::run);
                        states.clear();sync.invoke(host,view);paint.invoke(host,graphics,view);
                        check(states.size()==2 && states.stream().allMatch(s -> ((PipState)s).x0()>=0),"GALLERY own B fence permits separate visible B");
                        for(Object state:java.util.List.copyOf(states)) {
                            CpmExtractionBodyProbe.consumerFrame(loader,()->prepareRaw(prepare,dispatcher,state,late),CpmExtractionBodyProbe::consumerSubmitted);
                            CpmExtractionBodyProbe.signalConsumers();
                        }
                        states.clear();sync.invoke(host,view);paint.invoke(host,graphics,view);
                        check(states.size()==2 && states.stream().noneMatch(s -> ((PipState)s).x1()<0),"GALLERY visible B ACK stops retaining A without restarting same-source attempt");
                        System.out.println("PASS GALLERY complete native cape/no-cape mode continuity transition="+transition+" own A -> hidden B -> own fence -> separate visible B; synthetic GPU terminal");
                    }
                } finally { for(Object renderer:renderers.values())((AutoCloseable)renderer).close(); renderers.clear(); }
            }
        }
    }

    private static Object fieldUnchecked(Object owner, String name) {
        try { return field(owner,name); } catch(Exception failure) { throw new AssertionError(failure); }
    }

    private static java.util.List<Runnable> takeConsumerFences() throws Exception {
        var field = CpmExtractionBodyProbe.class.getDeclaredField("fences"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var fences = (java.util.List<Runnable>)field.get(null);
        var captured = java.util.List.copyOf(fences); fences.clear();
        check(!captured.isEmpty(), "POOLED actual native consumer enqueued its fenced completion");
        return captured;
    }

    private static void lateContextCompletion(Loader loader, Class<?> bakedType, java.lang.reflect.Method prepare,
            boolean late, PreviewRenderer.PreviewRequest request) throws Exception {
        var priorEnqueue = enqueue;
        var dispatcherType = loader.loadClass(loader.dispatcher);
        var replace = loader.loadClass(loader.prefix + "NclBakedPlayerSubmission").getMethod("replace", Object.class, PipState.class);
        var states = new java.util.ArrayList<Object>();
        enqueue = graphics -> states.add(call(replace, null, graphics, new MenuState()));
        try {
            for (boolean obsoleteLast : new boolean[]{false, true}) {
                var ctor = bakedType.getDeclaredConstructor(); ctor.setAccessible(true);
                Object producer = ctor.newInstance();
                Object a = late ? create(dispatcherType) : dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
                Object b = late ? create(dispatcherType) : dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
                var contextA = new CpmExtractionBodyProbe.GuiRenderState();
                var contextB = new CpmExtractionBodyProbe.GuiRenderState();
                @SuppressWarnings("unchecked") var renderer = (PreviewRenderer<Graphics>)producer;
                var image = (EditorPreviewSession)field(producer, "imageSession");
                var materials = CpmExtractionBodyProbe.queuedMaterials(); materials.clear(); states.clear();
                try {
                    renderer.render(new Graphics(), request);
                    check(states.size() == 1 && ((PipState)states.get(0)).x1() < 0, "POOLED A starts with genuine own hidden producer");
                    Object imageIdentity = field(image, "imageIdentity"); Object generation = field(image, "imageGeneration");
                    Object stateA = states.get(0);
                    CpmExtractionBodyProbe.consumerFrame(loader, contextA, () -> call(prepare, a,
                            late ? new Object[]{stateA, contextA, new CpmExtractionBodyProbe.Features(), 1} : new Object[]{stateA, contextA, 1}), CpmExtractionBodyProbe::consumerSubmitted);
                    var fenceA = takeConsumerFences();
                    check(materials.size() == 1 && field(image, "imageMaterial") == null && image.imagePreparing(),
                            "POOLED material A is prepared but its real fence has not published image readiness");
                    var physicalA = materials.get(0).texture;
                    closeTarget(a);
                    CpmExtractionBodyProbe.closeConsumerOwner(loader, contextA);
                    states.clear(); renderer.render(new Graphics(), request);
                    check(states.size() == 1 && ((PipState)states.get(0)).x1() < 0, "POOLED same-source B remains own hidden pending");
                    Object stateB = states.get(0);
                    CpmExtractionBodyProbe.consumerFrame(loader, contextB, () -> call(prepare, b,
                            late ? new Object[]{stateB, contextB, new CpmExtractionBodyProbe.Features(), 1} : new Object[]{stateB, contextB, 1}), CpmExtractionBodyProbe::consumerSubmitted);
                    var fenceB = takeConsumerFences();
                    check(materials.size() == 2 && materials.get(1).texture != physicalA, "POOLED B prepares its own resource after actual context A close");
                    if (obsoleteLast) {
                        fenceB.forEach(Runnable::run);
                        check(image.imageVisible(), "POOLED own B fence positively publishes current image");
                        fenceA.forEach(Runnable::run);
                        check(image.imageVisible(), "POOLED obsolete A fence after B fence cannot erase B");
                    } else {
                        fenceA.forEach(Runnable::run);
                        check(image.imagePreparing() && !image.imageVisible(), "POOLED obsolete A fence cannot terminalize still-pending same-image B");
                        fenceB.forEach(Runnable::run);
                        check(image.imageVisible(), "POOLED own B fence recovers after obsolete A");
                    }
                    check(imageIdentity == field(image, "imageIdentity") && generation.equals(field(image, "imageGeneration")),
                            "POOLED late-context rejection preserves exact image identity and generation");
                    check(physicalA.closes == 1 && !materials.get(1).texture.closed, "POOLED old physical fence releases only A exactly once");
                    states.clear(); renderer.render(new Graphics(), request);
                    check(states.size() == 1 && ((PipState)states.get(0)).x0() >= 0, "POOLED B follows its own fence with separately visible native state");
                    Object visible = states.get(0);
                    CpmExtractionBodyProbe.consumerFrame(loader, contextB, () -> call(prepare, b,
                            late ? new Object[]{visible, contextB, new CpmExtractionBodyProbe.Features(), 1} : new Object[]{visible, contextB, 1}), CpmExtractionBodyProbe::consumerSubmitted);
                    CpmExtractionBodyProbe.signalConsumers();
                    System.out.println("PASS POOLED actual same-image context A retirement -> own B prepare -> real fence order " + (obsoleteLast ? "B,A" : "A,B") + " -> preserved current own B -> separate visible consumer");
                } finally { closeTarget(producer); closeTarget(a); closeTarget(b); CpmExtractionBodyProbe.signalConsumers(); }
            }
        } finally { enqueue = priorEnqueue; }
    }

    private static void frameCards(Loader loader,Class<?> bakedType,java.lang.reflect.Method prepare,Object dispatcher,boolean late) throws Exception {
        System.setProperty("nclskins.frameCards", "true");
        var pixels=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(pixels,"PNG",bytes);
        var appearance=new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:frames",64,64),SkinModel.CLASSIC,java.util.Optional.empty(),PreviewRenderer.CapeMode.OFF,OuterLayerVisibility.allVisible());
        try(var base=new com.naocraftlab.skins.runtime.GalleryNativePassFixture(appearance,bytes.toByteArray());var subject=base.freshOwned();var sdk=new CpmExtractionBodyProbe.SdkFrames()) {
            boolean root=Boolean.getBoolean("nclskins.rootBoundary");
            Class<?> hostType=root ? CpmPreviewScreenSlice.loadNativeLifecycle(CpmPreviewScreenSlice.availableOwner(),bakedType) : CpmPreviewScreenSlice.loadNative(CpmPreviewScreenSlice.availableOwner(),bakedType);
            Object host=hostType.getConstructor().newInstance();var registry=new CpmOwnedPreviewDispatchProbe.Registry();
            try(var skins=new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.PLAYER_SKIN,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
                var capes=new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.IMAGE,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
                for(var f:hostType.getDeclaredFields()) {
                    Object value=switch(f.getName()) {
                        case "runtime" -> subject.runtime;case "skinTextures" -> skins;case "capeTextures" -> capes;case "textureRegistry" -> registry;
                        case "scrollController" -> new CpmOwnedPreviewDispatchProbe.ScrollTerminal();
                        case "activeScreen" -> true;
                        case "widgetSignature", "tabGroupSignature" -> java.util.List.of();
                        case "focusRequests" -> new com.naocraftlab.skins.runtime.FocusRequestLedger();
                        case "orderedRenderables" -> new java.util.ArrayList<CpmOwnedPreviewDispatchProbe.RenderFrame>();
                        case "editorRenderer" -> submissionOwner(loader,loader.loadClass(bakedType.getPackageName()+".AvatarRenderStatePreviewRenderer"));
                        case "width" -> 854;case "height" -> 480;default -> f.getType()==Map.class?new HashMap<>():null;
                    };if(value!=null)f.set(host,value);
                }
                var backgrounds=hostType.getDeclaredMethod("drawCardBackgrounds",Graphics.class,com.naocraftlab.skins.runtime.ViewSpec.class,int.class,int.class);backgrounds.setAccessible(true);
                var paint=hostType.getDeclaredMethod("drawPreviews",Graphics.class,com.naocraftlab.skins.runtime.ViewSpec.class);paint.setAccessible(true);
                var key=hostType.getDeclaredMethod("skinKey",com.naocraftlab.skins.runtime.ViewSpec.Preview.class);key.setAccessible(true);
                var replace=loader.loadClass(bakedType.getPackageName()+".NclBakedPlayerSubmission").getMethod("replace",Object.class,PipState.class);
                var view=subject.catalogFrame();check(view.previews().size()==3,"three genuine catalog presenter cards");
                if(root) {
                    FrameSurface.actual=CpmPreviewScreenSlice.loadSurface(CpmPreviewScreenSlice.availableOwner());
                    var terminal=(CpmOwnedPreviewDispatchProbe.ScreenTerminal)host;
                    terminal.width=854;terminal.height=480;terminal.font=new CpmOwnedPreviewDispatchProbe.FontTerminal();
                    terminal.minecraft=new CpmOwnedPreviewDispatchProbe.ClientTerminal();terminal.minecraft.screen=terminal;
                    set(host,"currentView",view);
                    var signatures=hostType.getDeclaredMethod("signatures",com.naocraftlab.skins.runtime.ViewSpec.class);signatures.setAccessible(true);set(host,"widgetSignature",signatures.invoke(null,view));
                    @SuppressWarnings("unchecked") var widgets=(Map<String,CpmOwnedPreviewDispatchProbe.WidgetTerminal>)field(host,"nativeWidgets");
                    @SuppressWarnings("unchecked") var renderables=(java.util.List<CpmOwnedPreviewDispatchProbe.RenderFrame>)field(host,"orderedRenderables");
                    for(var spec:view.widgets()) if(spec.visible()) {var widget=CpmPreviewScopeProbe.frameWidget(spec);widgets.put(spec.id(),widget);renderables.add(widget);}
                    var tabs=hostType.getDeclaredMethod("tabSignatures",com.naocraftlab.skins.runtime.ViewSpec.class);tabs.setAccessible(true);set(host,"tabGroupSignature",tabs.invoke(null,view));
                    var changed=hostType.getDeclaredMethod("snapshotChanged",com.naocraftlab.skins.runtime.ClientSnapshot.class);changed.setAccessible(true);
                    set(host,"subscription",subject.runtime.subscribe(snapshot -> {try{changed.invoke(host,snapshot);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}}));
                }
                for(var preview:view.previews()) {
                    Object k=key.invoke(null,preview);skins.request(k,()->java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.of(bytes.toByteArray())),()->{});
                    @SuppressWarnings("unchecked") var keys=(Map<String,Object>)field(host,"previewSkinKeys");keys.put(preview.id(),k);
                }
                Graphics.frames=sdk;var graphics=new Graphics();
                enqueue=g -> {
                    Object payload=call(replace,null,g,new MenuState(((Graphics)g).scissor));Graphics.framePayloads.add(payload);
                    try {var b=(Rectangle)payload.getClass().getMethod("bounds").invoke(payload);sdk.submit(payload,b==null?null:new int[]{b.left(),b.top(),b.left()+b.width(),b.top()+b.height()});}
                    catch(Exception failure){throw new AssertionError(failure);}
                };
                int[][] pointers={{0,0},{0,0},{0,0},{0,0},{0,0},{0,0},{0,0},{0,0}};
                var first=view.previews().get(0).bounds();var second=view.previews().get(1).bounds();
                pointers[3]=new int[]{first.x()+first.width()/2,first.y()+first.height()/2};pointers[4]=pointers[3];
                pointers[5]=new int[]{second.x()+second.width()/2,second.y()+second.height()/2};pointers[6]=pointers[5];
                var guiContext = new CpmExtractionBodyProbe.GuiRenderState();
                var dispatcherType = dispatcher.getClass();
                Object[] dispatchers = {dispatcher,
                        late ? create(dispatcherType) : dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers()),
                        late ? create(dispatcherType) : dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers())};
                var stableMaterials = new java.util.IdentityHashMap<Object, PreviewModelFrames.Body>();
                var stableGenerations = new java.util.IdentityHashMap<Object, Object>();
                Object[] staleFirst = {null};
                for(int frame=0;frame<pointers.length;frame++) {
                    final int currentFrame = frame;
                    Graphics.framePayloads.clear();var materials=CpmExtractionBodyProbe.queuedMaterials();materials.clear();
                    int[] pointer=pointers[frame];boolean background=false;
                    if(root) {
                        graphics.pointerX = pointer[0];
                        graphics.pointerY = pointer[1];
                        hostType.getMethod("extractFrame",Graphics.class,int.class,int.class,float.class).invoke(host,graphics,pointer[0],pointer[1],0F);
                        var publish=subject.runtime.getClass().getDeclaredMethod("publish");publish.setAccessible(true);publish.invoke(subject.runtime);
                        System.out.println("ROOT returned + actual runtime publication/subscriber; rebuilds="+((CpmOwnedPreviewDispatchProbe.ScreenTerminal)host).rebuilds+" foreground="+rootPainted.size()+" blits="+graphics.blits.size());
                    }
                    else {background=(boolean)backgrounds.invoke(host,graphics,view,pointer[0],pointer[1]);if(background)graphics.nextStratum();paint.invoke(host,graphics,view);}
                    var emitted=java.util.List.copyOf(Graphics.framePayloads);var ordered=sdk.prepareOrder();
                    if (frame == 0) staleFirst[0] = emitted.get(0);
                    check(emitted.size()==3 && ordered.size()==3,"all three host emissions survive real SDK collector");
                    var consumed=new java.util.IdentityHashMap<Object,CpmExtractionBodyProbe.TextureView>();
                    CpmExtractionBodyProbe.consumerFrame(loader, guiContext,()->{
                        for(Object payload:ordered) {
                            int before=materials.size();
                            Object pooled = dispatchers[(ordered.indexOf(payload) + currentFrame) % dispatchers.length];
                            call(prepare, pooled, late ? new Object[]{payload, guiContext, new CpmExtractionBodyProbe.Features(), 1}
                                    : new Object[]{payload, guiContext, 1});
                            for (var established : stableMaterials.values()) check(established.current().getAsBoolean(),
                                    "POOLED same GUI context preserves old own material across dispatcher permutation");
                            check(materials.size()==before+1,"each collected card produces own material");
                            consumed.put(payload,materials.get(before));
                            try {var p=(PipState)payload;sdk.blit(payload,new int[]{p.x0(),p.y0(),p.x1(),p.y1()},"blit:"+emitted.indexOf(payload));}
                            catch(Exception failure){throw new AssertionError(failure);}
                        }
                    },()->{
                        for(Object payload:emitted) {
                            var material=consumed.get(payload);check(material!=null && !material.texture.closed,"card material survives sibling preparations");
                            check(material.texture.data.equals(field(payload,"skinTexture")),"draw consumes exact card material, not last sibling");
                        }
                        CpmExtractionBodyProbe.consumerSubmitted();
                    });CpmExtractionBodyProbe.signalConsumers();
                    var elements=sdk.elements();
                    System.out.println("FRAME "+frame+" pointer="+java.util.Arrays.toString(pointer)+" backgrounds="+background+" hidden="+emitted.stream().map(p->((PipState)p).x1()<0).toList()+" prepareOrder="+ordered.stream().map(emitted::indexOf).toList()+" GUI="+elements);
                    @SuppressWarnings("unchecked") var renderers=(Map<String,Object>)field(host,"galleryRenderers");
                    for(var entry:renderers.entrySet()) {
                        var image=(EditorPreviewSession)field(entry.getValue(),"imageSession");
                        check(image.imageVisible(),"own consumed card remains ready after entire SDK frame");
                        if (frame == 0) {
                            stableMaterials.put(image, (PreviewModelFrames.Body)field(image, "imageMaterial"));
                            stableGenerations.put(image, field(image, "imageGeneration"));
                        } else check(stableGenerations.get(image).equals(field(image, "imageGeneration")),
                                "POOLED traversal does not restart image generation");
                    }
                    if(frame>0)check(emitted.stream().noneMatch(p->((PipState)p).x1()<0),"stable pointer/UI-only frames must not rehide ready cards");
                    sdk.reset();
                }
                var lastStates = java.util.List.copyOf(Graphics.framePayloads);
                var oldTextures = CpmExtractionBodyProbe.queuedMaterials().stream().map(value -> value.texture).toList();
                closeTarget(dispatchers[2]);
                for (var material : stableMaterials.values()) check(material.current().getAsBoolean(),
                        "POOLED pruning unused tail preserves active and unqueued retained A");
                check(oldTextures.stream().noneMatch(value -> value.closed), "POOLED tail close does not retire context resources");
                Object reusedSlot = late ? create(dispatcherType) : dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
                CpmExtractionBodyProbe.consumerFrame(loader, guiContext, () -> {
                    Object state = lastStates.get(0);
                    call(prepare, reusedSlot, late ? new Object[]{state, guiContext, new CpmExtractionBodyProbe.Features(), 1} : new Object[]{state, guiContext, 1});
                }, CpmExtractionBodyProbe::consumerSubmitted);
                CpmExtractionBodyProbe.signalConsumers();
                closeTarget(dispatchers[0]); closeTarget(dispatchers[1]); closeTarget(reusedSlot);
                for (var material : stableMaterials.values()) check(material.current().getAsBoolean(),
                        "POOLED empty frame with zero participants preserves all live GUI context targets");
                Object afterEmpty = late ? create(dispatcherType) : dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
                CpmExtractionBodyProbe.consumerFrame(loader, guiContext, () -> {
                    Object state = lastStates.get(0);
                    call(prepare, afterEmpty, late ? new Object[]{state, guiContext, new CpmExtractionBodyProbe.Features(), 1} : new Object[]{state, guiContext, 1});
                }, CpmExtractionBodyProbe::consumerSubmitted);
                CpmExtractionBodyProbe.signalConsumers();
                closeTarget(afterEmpty);
                for (var material : stableMaterials.values()) check(material.current().getAsBoolean(),
                        "POOLED same GUI admits new dispatcher after empty frame without reviving or replacing material");
                var newContext = new CpmExtractionBodyProbe.GuiRenderState();
                Object replacement = late ? create(dispatcherType) : dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
                CpmExtractionBodyProbe.queuedMaterials().clear();
                CpmExtractionBodyProbe.consumerFrame(loader, newContext, () -> {
                    for (Object state : lastStates) call(prepare, replacement, late ? new Object[]{state, newContext, new CpmExtractionBodyProbe.Features(), 1} : new Object[]{state, newContext, 1});
                }, CpmExtractionBodyProbe::consumerSubmitted);
                CpmExtractionBodyProbe.signalConsumers();
                for (var material : stableMaterials.values()) check(!material.current().getAsBoolean(),
                        "POOLED genuine GUI context replacement retires old logical material");
                check(oldTextures.stream().allMatch(value -> value.closes == 1), "POOLED old physical resources retire exactly once");
                Graphics.framePayloads.clear();
                paint.invoke(host, graphics, view);
                var hiddenReplacement = java.util.List.copyOf(Graphics.framePayloads);
                check(hiddenReplacement.size() == 3 && hiddenReplacement.stream().allMatch(value -> ((PipState)value).x1() < 0),
                        "POOLED new context requires each own hidden image producer, not forced readiness");
                CpmExtractionBodyProbe.queuedMaterials().clear();
                CpmExtractionBodyProbe.consumerFrame(loader, newContext, () -> {
                    for (Object state : hiddenReplacement) call(prepare, replacement, late ? new Object[]{state, newContext, new CpmExtractionBodyProbe.Features(), 1} : new Object[]{state, newContext, 1});
                }, CpmExtractionBodyProbe::consumerSubmitted);
                CpmExtractionBodyProbe.signalConsumers();
                closeTarget(reusedSlot);
                @SuppressWarnings("unchecked") var liveRenderers = (Map<String,Object>)field(host, "galleryRenderers");
                for (Object renderer : liveRenderers.values()) check(((EditorPreviewSession)field(renderer, "imageSession")).imageVisible(),
                        "POOLED old context final close cannot retire new context own material");
                var newTextures = CpmExtractionBodyProbe.queuedMaterials().stream().map(value -> value.texture).toList();
                CpmExtractionBodyProbe.consumerFrame(loader, newContext, () -> {
                    for (Object state : hiddenReplacement) call(prepare, replacement, late ? new Object[]{state, newContext, new CpmExtractionBodyProbe.Features(), 1} : new Object[]{state, newContext, 1});
                    closeTarget(replacement);
                    try { CpmExtractionBodyProbe.closeConsumerOwner(loader, newContext); } catch (Exception failure) { throw new AssertionError(failure); }
                    closeTarget(replacement);
                }, () -> {
                    check(newTextures.stream().noneMatch(value -> value.closed), "POOLED last context close preserves acquired native consumer");
                    CpmExtractionBodyProbe.consumerSubmitted();
                });
                check(newTextures.stream().noneMatch(value -> value.closed), "POOLED enqueued fence is not signal");
                CpmExtractionBodyProbe.signalConsumers();
                check(newTextures.stream().allMatch(value -> value.closes == 1), "POOLED true GUI parent close retires every resource exactly once after fence");
                Object forbidden = late ? create(dispatcherType) : dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
                try {
                    Object state = hiddenReplacement.get(0);
                    call(prepare, forbidden, late ? new Object[]{state, newContext, new CpmExtractionBodyProbe.Features(), 1} : new Object[]{state, newContext, 1});
                    throw new AssertionError("POOLED closed context must not revive");
                } catch (IllegalStateException expected) {
                    check(expected.getMessage().equals("GUI context is closed"), "POOLED strict closed context rejection");
                } finally { closeTarget(forbidden); }
                Object contextOwner = field(field(dispatchers[0], "context"), "owner");
                check(contextOwner == null && field(field(replacement, "context"), "owner") == null,
                        "POOLED closed dispatcher leases detach context owners");
                var anchorContext = new CpmExtractionBodyProbe.GuiRenderState();
                Object anchor = late ? create(dispatcherType) : dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
                Object tail = late ? create(dispatcherType) : dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
                var staleCompletion = (PreviewModelFrames.Completion)field(staleFirst[0], "completion");
                check(!staleCompletion.current(), "POOLED first-slot state really is stale before anchor admission");
                CpmExtractionBodyProbe.queuedMaterials().clear();
                call(prepare, anchor, late ? new Object[]{staleFirst[0], anchorContext, new CpmExtractionBodyProbe.Features(), 1} : new Object[]{staleFirst[0], anchorContext, 1});
                Object anchorOwner = field(field(anchor, "context"), "owner");
                check(anchorOwner != null && (int)field(anchorOwner, "participants") == 1
                                && ((java.util.Set<?>)field(anchorOwner, "targets")).isEmpty()
                                && CpmExtractionBodyProbe.queuedMaterials().isEmpty(),
                        "POOLED stale first slot binds metadata-only anchor without acquiring target/material");
                Graphics.framePayloads.clear(); paint.invoke(host, graphics, view);
                var validLater = java.util.List.copyOf(Graphics.framePayloads);
                check(validLater.size() == 3 && validLater.stream().allMatch(state -> ((PipState)state).x1() < 0),
                        "POOLED valid later slots perform their own hidden recovery");
                CpmExtractionBodyProbe.consumerFrame(loader, anchorContext, () -> {
                    for (Object state : validLater) call(prepare, tail, late ? new Object[]{state, anchorContext, new CpmExtractionBodyProbe.Features(), 1} : new Object[]{state, anchorContext, 1});
                }, CpmExtractionBodyProbe::consumerSubmitted);
                CpmExtractionBodyProbe.signalConsumers();
                check((int)field(anchorOwner, "participants") == 2, "POOLED real valid later slot joins stale anchor context");
                closeTarget(tail);
                for (Object renderer : liveRenderers.values()) check(((EditorPreviewSession)field(renderer, "imageSession")).imageVisible(),
                        "POOLED stale original anchor preserves ready unqueued material after valid tail pruning");
                var anchoredTextures = CpmExtractionBodyProbe.queuedMaterials().stream().map(value -> value.texture).toList();
                check(anchoredTextures.size() == 3 && anchoredTextures.stream().noneMatch(value -> value.closed),
                        "POOLED pruning participant lease does not invalidate scope/resource witness");
                closeTarget(anchor);
                CpmExtractionBodyProbe.closeConsumerOwner(loader, anchorContext);
                check(anchoredTextures.stream().allMatch(value -> value.closes == 1)
                                && (int)field(anchorOwner, "participants") == 0,
                        "POOLED true parent close retires entire context exactly once");
                contextOwnerCleanup(loader);
                System.out.println("PASS POOLED actual multi-dispatcher same-context permutation/currentness/generation; prune retained A; cache-hit participant; context replacement; old-close guard; own hidden recovery; acquired consumer/fence; final exact-once close; no closed-context revival");
                if(root) ((AutoCloseable)field(host,"subscription")).close();
                @SuppressWarnings("unchecked") var renderers=(Map<String,Object>)field(host,"galleryRenderers");for(Object renderer:renderers.values())((AutoCloseable)renderer).close();
            } finally {Graphics.frames=null;Graphics.framePayloads.clear();}
        }
    }

    private static void contextOwnerCleanup(Loader loader) throws Exception {
        var targetType = loader.loadClass(loader.prefix + "NclBakedPlayerTarget");
        var leaseType = loader.loadClass(targetType.getName() + "$ContextLease");
        var ownerType = loader.loadClass(targetType.getName() + "$ContextOwner");
        var join = leaseType.getMethod("join", CpmExtractionBodyProbe.GuiRenderState.class);
        var acquire = ownerType.getMethod("acquire", targetType, java.util.function.Supplier.class, Class.class);
        Object lease = create(leaseType);
        Object owner = call(join, lease, new CpmExtractionBodyProbe.GuiRenderState());
        int[] closes = {0, 0};
        var targets = new java.util.ArrayList<Object>();
        for (int i = 0; i < 2; i++) {
            int index = i;
            Object target = create(targetType); targets.add(target);
            call(acquire, owner, target, (java.util.function.Supplier<AutoCloseable>)() -> () -> {
                closes[index]++;
                closeTarget(lease);
                check((boolean)field(owner, "closed") && ((java.util.Set<?>)field(owner, "targets")).isEmpty(),
                        "POOLED context detaches registry and marks closed before callback");
                throw new IllegalStateException("fixture context cleanup " + index);
            }, AutoCloseable.class);
        }
        try { closeTarget(lease); closeTarget(owner); throw new AssertionError("POOLED cleanup fault must propagate"); }
        catch (IllegalStateException expected) { check(expected.getSuppressed().length == 1, "POOLED aggregate cleanup preserves both failures"); }
        closeTarget(lease);
        check(closes[0] == 1 && closes[1] == 1 && (int)field(owner, "participants") == 0,
                "POOLED reentrant aggregate cleanup is exact-once and no participant leak");
        Object nextLease = create(leaseType);
        Object nextOwner = call(join, nextLease, new CpmExtractionBodyProbe.GuiRenderState());
        Object reentrantLease = create(leaseType);
        Object reentrantOwner = call(join, reentrantLease, new CpmExtractionBodyProbe.GuiRenderState());
        Object target = create(targetType);
        int[] replacementCloses = {0};
        AutoCloseable replacement = () -> replacementCloses[0]++;
        call(acquire, reentrantOwner, target, (java.util.function.Supplier<AutoCloseable>)() -> () -> {
            closeTarget(reentrantLease);
            call(acquire, nextOwner, target, (java.util.function.Supplier<AutoCloseable>)() -> replacement, AutoCloseable.class);
        }, AutoCloseable.class);
        closeTarget(reentrantLease);
        closeTarget(reentrantOwner);
        check((boolean)call(targetType.getMethod("owns", Object.class, AutoCloseable.class), target, nextOwner, replacement)
                        && replacementCloses[0] == 0, "POOLED reentrant next context replacement survives old cleanup");
        closeTarget(target);
        check(replacementCloses[0] == 1, "POOLED source target close is stronger than live context");
        closeTarget(nextLease);
        check(replacementCloses[0] == 1, "POOLED context after source close cannot double-release");
        Object duringCreate = create(leaseType);
        Object duringOwner = call(join, duringCreate, new CpmExtractionBodyProbe.GuiRenderState());
        int[] rejectedCloses = {0};
        try {
            call(acquire, duringOwner, create(targetType), (java.util.function.Supplier<AutoCloseable>)() -> {
                closeTarget(duringCreate);
                closeTarget(duringOwner);
                return () -> rejectedCloses[0]++;
            }, AutoCloseable.class);
            throw new AssertionError("POOLED context closed in factory must reject late creation");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().equals("GUI context closed during creation") && rejectedCloses[0] == 1,
                    "POOLED rejected late resource is closed exactly once");
        }
        Object[] weak = weakContextInventory(loader, targetType, leaseType, join, acquire);
        var contextReference = (java.lang.ref.WeakReference<?>)weak[2];
        var targetReference = (java.lang.ref.WeakReference<?>)weak[3];
        for (int i = 0; i < 12 && (contextReference.get() != null || targetReference.get() != null); i++) {
            System.gc();
            Thread.sleep(10);
        }
        check(contextReference.get() == null && targetReference.get() == null,
                "POOLED context registry and target inventory do not strongly retain departed identities");
        check(((java.util.Set<?>)field(weak[1], "targets")).isEmpty(), "POOLED weak target inventory expunges departed target");
        closeTarget(weak[0]);
        check((int)field(weak[1], "participants") == 0, "POOLED weak inventory lease settles without participant leak");
        System.out.println("PASS POOLED context wrapper cleanup: detach-before-reentry, aggregate faults, exact-once, new-owner preservation, stronger source close, factory-time retirement, weak registry/inventory");
    }

    private static Object[] weakContextInventory(Loader loader, Class<?> targetType, Class<?> leaseType,
            java.lang.reflect.Method join, java.lang.reflect.Method acquire) throws Exception {
        var state = new CpmExtractionBodyProbe.GuiRenderState();
        Object target = create(targetType);
        Object lease = create(leaseType);
        Object owner = call(join, lease, state);
        call(acquire, owner, target, (java.util.function.Supplier<AutoCloseable>)() -> () -> {}, AutoCloseable.class);
        closeTarget(target);
        return new Object[]{lease, owner, new java.lang.ref.WeakReference<>(state), new java.lang.ref.WeakReference<>(target)};
    }

    private static void scheduledBaked(Object state, boolean extraction) throws Exception {
        var payload = (PipState) state;
        Rectangle actual = (Rectangle) state.getClass().getMethod("bounds").invoke(state);
        var traversal = CpmExtractionBodyProbe.sdkTraversal(java.util.List.of(payload), false, false,
                extraction ? "net.minecraft.client.renderer.state.gui." : "net.minecraft.client.gui.render.state.",
                value -> actual == null ? null : new int[]{actual.left(), actual.top(), actual.left()+actual.width(), actual.top()+actual.height()}, value -> 0, value -> true, false);
        check(actual != null && traversal.size() == 1 && traversal.get(0) == state,
                "CARD actual SDK bounds/collector must schedule the exact hidden preparation under the actual host clip");
    }

    private static void featureMaterialControls() {
        var cape = new Identifier("fixture:feature-cape-C");
        var skin = new Identifier("fixture:feature-skin");
        var nextCape = new Identifier("fixture:feature-cape-D");
        var storage = new CpmExtractionBodyProbe.Storage();
        storage.submitModel(null, null, new CpmExtractionBodyProbe.Pose(), cape, 0, 0, -1);
        storage.submitModel(null, null, new CpmExtractionBodyProbe.Pose(), skin, 0, 0, -1);
        var features = new CpmExtractionBodyProbe.Features();
        var frame = features.prepareFrame(storage);
        storage.submitModel(null, null, new CpmExtractionBodyProbe.Pose(), nextCape, 0, 0, -1);
        var output = new CpmExtractionBodyProbe.LegacyTexture();
        var view = new CpmExtractionBodyProbe.TextureView(output);
        check(output.materialInputs.isEmpty(), "FEATURE no material consumption before the actual feature terminal");
        CpmExtractionBodyProbe.Features.renderAllFeatures(new CpmExtractionBodyProbe.Pass() {
            public CpmExtractionBodyProbe.TextureView target() { return view; }
            public void close() {}
        }, frame);
        check(output.data.equals(skin), "FEATURE preserves the existing final skin material oracle");
        check(output.materialInputs.equals(java.util.List.of(cape, skin)),
                "FEATURE actual material terminal preserves cape and skin from the immutable prepared frame");
        var nextOutput = new CpmExtractionBodyProbe.LegacyTexture();
        var nextView = new CpmExtractionBodyProbe.TextureView(nextOutput);
        CpmExtractionBodyProbe.Features.renderAllFeatures(new CpmExtractionBodyProbe.Pass() {
            public CpmExtractionBodyProbe.TextureView target() { return nextView; }
            public void close() {}
        }, features.prepareFrame(storage));
        check(nextOutput.materialInputs.equals(java.util.List.of(nextCape))
                        && output.materialInputs.equals(java.util.List.of(cape, skin)),
                "FEATURE next prepared frame drains previous inputs without changing the prior material");
        System.out.println("PASS FEATURE immutable cape/skin material inputs consumed only in actual feature terminal");
    }

    private static void bufferMaterialControls() {
        var previous = CpmExtractionBodyProbe.RenderSystem.outputColorTextureOverride;
        var output = new CpmExtractionBodyProbe.LegacyTexture();
        var buffers = new Buffers();
        var cape = new Identifier("fixture:buffer-cape");
        var skin = new Identifier("fixture:buffer-skin");
        try {
            CpmExtractionBodyProbe.RenderSystem.outputColorTextureOverride = new CpmExtractionBodyProbe.TextureView(output);
            var capeConsumer = buffers.getBuffer(RenderType.entityTranslucent(cape));
            check(output.materialInputs.isEmpty(), "BUFFER lookup alone does not consume material");
            new Model().renderToBuffer(new CpmExtractionBodyProbe.Pose(),capeConsumer,0,0);
            new Model().renderToBuffer(new CpmExtractionBodyProbe.Pose(),buffers.getBuffer(RenderType.entityTranslucent(skin)),0,0);
            buffers.endBatch();
            check(output.materialInputs.equals(java.util.List.of(cape,skin)),
                    "BUFFER actual model consumption and native batch preserve cape and skin");
            buffers.endBatch();
            check(output.materialInputs.equals(java.util.List.of(cape,skin)), "BUFFER empty native batch preserves the consumed output");
            new Model().renderToBuffer(new CpmExtractionBodyProbe.Pose(),buffers.getBuffer(RenderType.entityTranslucent(cape)),0,0);
            buffers.endBatch();
            check(output.materialInputs.equals(java.util.List.of(cape)), "BUFFER next batch drains previous material inputs");
        } finally { CpmExtractionBodyProbe.RenderSystem.outputColorTextureOverride = previous; }
        System.out.println("PASS BUFFER model-consumed cape/skin batch material inputs and draining");
    }

    private static void consumeVisibleBaked(ClassLoader loader, java.lang.reflect.Method prepare, Object dispatcher,
            Object state, boolean late, boolean extraction, String skin) throws Exception {
        consumeVisibleBaked(loader, prepare, dispatcher, state, late, extraction, skin, null);
    }

    private static void consumeVisibleBaked(ClassLoader loader, java.lang.reflect.Method prepare, Object dispatcher,
            Object state, boolean late, boolean extraction, String skin, String cape) throws Exception {
        check(((PipState) state).x0() == (menuPanelJoin ? 535 : 0), "actual visible consumer receives the current on-screen image payload");
        scheduledBaked(state, extraction);
        var materials = CpmExtractionBodyProbe.queuedMaterials();
        int previous = materials.size();
        CpmExtractionBodyProbe.consumerFrame(loader, () -> prepareRaw(prepare, dispatcher, state, late), () -> {
            check(materials.size() == previous + 1 && materials.get(previous).texture.data.equals(new Identifier(skin))
                            && !materials.get(previous).texture.closed,
                    "actual on-screen GUI consumer receives the same source's own prepared material");
            if (cape != null) check(materials.get(previous).texture.materialInputs.contains(new Identifier(cape)),
                    "MENU animation actual visible native material consumes the current cape frame, not retained predecessor");
            CpmExtractionBodyProbe.consumerSubmitted();
        });
        CpmExtractionBodyProbe.signalConsumers();
    }

    private static void consumeRetainedBaked(ClassLoader loader, java.lang.reflect.Method prepare, Object dispatcher,
            Object state, boolean late, boolean extraction, CpmExtractionBodyProbe.LegacyTexture retained,
            int materialCount, String skin, String cape) throws Exception {
        check(((PipState) state).x0() == (menuPanelJoin ? 535 : 0), "retained output uses the current on-screen payload");
        scheduledBaked(state, extraction);
        var materials = CpmExtractionBodyProbe.queuedMaterials();
        int previousSubmissions = draws;
        CpmExtractionBodyProbe.consumerFrame(loader, () -> prepareRaw(prepare, dispatcher, state, late), () -> {
            check(materials.size() == materialCount + 1 && materials.get(materialCount).texture == retained
                            && draws == previousSubmissions
                            && !retained.closed && retained.data.equals(new Identifier(skin))
                            && retained.materialInputs.contains(new Identifier(cape)),
                    "retained A blits its previously rasterized output without another native input/material pass count="
                            + materials.size() + "/" + materialCount + " same="
                            + (materials.size() > materialCount && materials.get(materialCount).texture == retained)
                            + " submissions=" + draws + "/" + previousSubmissions
                            + " closed=" + retained.closed + " data=" + retained.data + " capeInputs=" + retained.materialInputs
                            + " added=" + (materials.size() > materialCount
                                    ? materials.subList(materialCount, materials.size()).stream().map(value -> value.texture.data + ":" + value.texture.materialInputs).toList()
                                    : java.util.List.of()));
            CpmExtractionBodyProbe.consumerSubmitted();
        });
        CpmExtractionBodyProbe.signalConsumers();
    }

    private static void hiddenImage(String scenario) throws Exception {
        boolean extraction = CpmBakedDisplayProbe.class.getClassLoader().getResource(
                "com/naocraftlab/skins/compat/client/identifier/extraction/AvatarPipPreviewRenderer.class") != null;
        String prefix = "com.naocraftlab.skins.compat.client.identifier." + (extraction ? "extraction." : "submission.");
        String baked = prefix + (extraction ? "AvatarPipPreviewRenderer" : "SimplePreviewRenderer");
        String dispatcherName = prefix + (extraction ? "NclBakedPlayerRenderer" : "BakedPreviewRenderer");
        var loader = new Loader(prefix, prefix + (extraction ? "AvatarRenderStatePreviewRenderer" : "SubmissionPreviewRenderer"), baked, dispatcherName, extraction);
        loader.physical = true;
        loader.types.put("com/mojang/blaze3d/textures/GpuTexture", CpmExtractionBodyProbe.LegacyTexture.class);
        Client.current = new Client();
        var bakedType = loader.loadClass(baked);
        var constructor = bakedType.getDeclaredConstructor(); constructor.setAccessible(true);
        var dispatcherType = loader.loadClass(dispatcherName);
        var prepare = java.util.Arrays.stream(dispatcherType.getDeclaredMethods())
                .filter(m -> m.getName().equals("prepare") && !m.isBridge()).findFirst().orElseThrow();
        boolean late = prepare.getParameterCount() == 4;
        Object dispatcher = late ? create(dispatcherType) : dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
        var transport = loader.loadClass(prefix + (extraction ? "NclBakedPlayerSubmission" : "BakedPreviewSubmission"));
        var replace = transport.getMethod("replace", Object.class, PipState.class);
        var render = bakedType.getDeclaredMethod("render", Graphics.class, PreviewRenderer.PreviewRequest.class, PreviewModelFrames.Completion.class);
        render.setAccessible(true);
        Object[] queued = {null};
        enqueue = graphics -> queued[0] = call(replace, null, graphics, new MenuState());
        var skin = new com.naocraftlab.skins.client.TextureRegistry.TextureHandle("fixture:hidden-own-body",64,64);
        var appearance = new PreviewRenderer.PreviewAppearance(skin, com.naocraftlab.skins.client.SkinModel.SLIM,
                java.util.Optional.empty(), PreviewRenderer.CapeMode.OFF, com.naocraftlab.skins.client.OuterLayerVisibility.allVisible());
        var panel = menuPanelJoin ? new CpmMenuPanelSlice(loader,prefix,appearance) : null;
        var animationFrames = java.util.List.of(
                new TextureRegistry.TextureHandle("fixture:menu-frame-0",64,32),
                new TextureRegistry.TextureHandle("fixture:menu-frame-1",64,32),
                new TextureRegistry.TextureHandle("fixture:menu-frame-2",64,32),
                new TextureRegistry.TextureHandle("fixture:menu-frame-3",64,32));
        var animationTimeline = com.naocraftlab.skins.core.png.CapeTimeline.pngStrip(animationFrames.size());
        if (panel != null && (scenario.equals("menu-animation") || scenario.equals("menu-cape-retired"))) panel.animate(animationTimeline, animationFrames);
        if (panel != null && scenario.equals("menu")) {
            panel.install(com.naocraftlab.skins.core.config.MenuPreviewPlacement.OFF);
            panel.draw(new Graphics());
            check(queued[0] == null && !panel.actionVisible(), "JOIN real menu OFF emits no image/action");
            panel.install(com.naocraftlab.skins.core.config.MenuPreviewPlacement.RIGHT);
            panel.anchor(false); panel.draw(new Graphics());
            check(queued[0] == null && !panel.actionVisible(), "JOIN real menu missing native anchor emits no image/action");
            panel.anchor(true); panel.dimensions(300,240); panel.draw(new Graphics());
            check(queued[0] == null && !panel.actionVisible(), "JOIN real menu minimum bounds reject overlap");
            panel.dimensions(854,480); panel.appearance(null); panel.draw(new Graphics());
            check(queued[0] == null && panel.actionVisible(), "JOIN real menu absent appearance has no phantom preparation, navigation retained");
            panel.appearance(appearance);
        }
        Object producer = panel == null ? constructor.newInstance() : panel.renderer();
        boolean equipment = scenario.equals("equipment");
        boolean menu = scenario.startsWith("menu") || scenario.equals("card") || equipment;
        var request = new PreviewRenderer.PreviewRequest(appearance,menu ? 0 : 855,0,160,180,0,0,1,
                scenario.equals("card") ? PreviewRenderer.PreviewIntent.ASSET_THUMBNAIL : PreviewRenderer.PreviewIntent.CURRENT_APPEARANCE);
        boolean[] ready = {false}, early = {false}, terminal = {false};
        var completion = new PreviewModelFrames.Completion() {
            public boolean current() { return true; }
            public boolean requiresConsumption() { return true; }
            public void accept(PreviewModelFrames.Body body) {
                ready[0] = body.status() == PreviewRenderer.BodyStatus.ORDINARY && body.current().getAsBoolean();
                terminal[0] = body.status() == PreviewRenderer.BodyStatus.FAILED;
            }
        };
        var materials = CpmExtractionBodyProbe.queuedMaterials(); materials.clear();
        CpmExtractionBodyProbe.cached = false; draw = () -> {}; pop = () -> {};
        CpmExtractionBodyProbe.consumerControl("normal");
        var pendingTexture = scenario.startsWith("menu-texture") ? NativePlayerSkinLifecycle.pending(skin.location()) : null;
        try {
            var publicRender = bakedType.getMethod("render", Graphics.class, PreviewRenderer.PreviewRequest.class);
            java.util.function.Consumer<PreviewRenderer.PreviewRequest> publicDraw = value -> {
                if (equipment) {
                    @SuppressWarnings("unchecked") var capeRenderer = (com.naocraftlab.skins.client.BackEquipmentPreviewRenderer<Graphics>) producer;
                    capeRenderer.render(new Graphics(), new com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Request(
                            value.appearance().skin(), com.naocraftlab.skins.client.BackEquipmentPreviewRenderer.Mode.CAPE,
                            value.left(), value.top(), value.width(), value.height()));
                } else if (panel == null) call(publicRender,producer,new Graphics(),value);
                else {
                    panel.appearance(value.appearance());
                    try { panel.draw(new Graphics()); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }
            };
            if (scenario.equals("submission-pool")) {
                loader.rawSdk = true;
                submissionPoolCards(loader,bakedType,prepare,dispatcherType);
                return;
            }
            if (scenario.equals("context-callbacks")) {
                lateContextCompletion(loader, bakedType, prepare, late, request);
                return;
            }
            if (scenario.equals("gallery-mode")) {
                galleryModeHost(loader, bakedType, prepare, dispatcher, late, extraction);
                return;
            }
            if (scenario.equals("card")) {
                staticCardHost(loader, bakedType, prepare, dispatcher, late, extraction);
                if (!extraction && getNeoPoolResource()) submissionPoolCards(loader,bakedType,prepare,dispatcherType);
                if (extraction) {
                    lateContextCompletion(loader, bakedType, prepare, late, request);
                    frameCards(loader, bakedType, prepare, dispatcher, late);
                }
                return;
            }
            if (scenario.equals("frame-cards")) {
                frameCards(loader,bakedType,prepare,dispatcher,late);
                return;
            }
            if (scenario.equals("menu-texture-failed")) {
                long[] now = {0};
                var image = new EditorPreviewSession(() -> now[0]);
                set(producer, "imageSession", image);
                pendingTexture.failed();
                publicDraw.accept(request);
                now[0] = 300_000_000L;
                check(queued[0] == null && !image.imageSpinner(), "failed native texture must terminate image preparation, not wait forever");
                System.out.println("PASS terminal native texture is not menu image pending; " + prefix);
                return;
            }
            if (pendingTexture != null) {
                publicDraw.accept(request);
                check(queued[0] == null, "pending native texture cannot submit a default material as prepared menu image");
                pendingTexture.ready();
            }
            if (scenario.equals("menu")) {
                long[] now = {0};
                set(producer, "imageSession", new EditorPreviewSession(() -> now[0]));
                Graphics.loadingFrames.clear();
                publicDraw.accept(request);
                check(Graphics.loadingFrames.isEmpty(), "LOADING no spinner before delay");
                now[0] = 300_000_000L;
                publicDraw.accept(request);
                check(Graphics.loadingFrames.size() == 1, "LOADING actual native menu paints delayed frame");
                var frame = Graphics.loadingFrames.get(0);
                check(frame.resource.equals("nclskins:textures/gui/icons/status/preview_loading.png")
                                && frame.u == 0 && frame.v == 96 && frame.width == 32 && frame.height == 32
                                && frame.sourceWidth == 32 && frame.sourceHeight == 32
                                && frame.textureWidth == 32 && frame.textureHeight == 256,
                        "LOADING actual native menu crops one phase without scaling entire strip");
            }
            if (menu) publicDraw.accept(request);
            else call(render, producer, new Graphics(), request, completion);
            check(queued[0] != null && !ready[0], "hidden menu producer enqueues without readiness");
            scheduledBaked(queued[0], extraction);
            if (menu) check((int) stateField(queued[0], "x1") < 0,
                    "public menu renderer must hide its first preparation image without stopping native submission");
            var image = menu ? (EditorPreviewSession) field(producer, "imageSession") : null;
            var cleanupTarget = new com.naocraftlab.skins.client.ScreenOwnedRenderTarget();
            var retainImageTarget = loader.loadClass("com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame")
                    .getMethod("retain", com.naocraftlab.skins.client.ScreenOwnedRenderTarget.class);
            if (scenario.equals("fence-cleanup-failure")) cleanupTarget.acquire(new Object(),
                    () -> (AutoCloseable) () -> { throw new IllegalStateException("synthetic image fence cleanup failure"); }, AutoCloseable.class);
            Throwable failure = null;
            if (scenario.equals("prepare-abort")) CpmExtractionBodyProbe.resetConsumerControl();
            try { CpmExtractionBodyProbe.consumerFrame(loader, () -> {
                if (scenario.equals("fence-cleanup-failure")) {
                    call(retainImageTarget, null, cleanupTarget);
                    cleanupTarget.close();
                }
                if (menu) {
                    var inputCheck = (com.naocraftlab.skins.client.PreviewModelFrames.Completion) stateField(queued[0], "completion");
                    check(inputCheck.current(), "queued current menu frame is still admitted before its native prepare");
                    check(inputCheck.currentInput(String.valueOf(stateField(queued[0], "attachmentTexture"))),
                            "queued actual native input matches current cape frame; actual="
                                    + stateField(queued[0], "attachmentTexture") + ", request="
                                    + request.appearance().cape().map(com.naocraftlab.skins.client.TextureRegistry.TextureHandle::location).orElse("absent"));
                }
                prepareRaw(prepare, dispatcher, queued[0], late);
                check(materials.size() == 1 && materials.get(0).texture.data.equals(new Identifier(skin.location())),
                        "hidden producer prepares its own actual material; count=" + materials.size()
                                + (materials.isEmpty() ? "" : ", input=" + materials.get(0).texture.data));
                early[0] |= menu ? image.imageVisible() : ready[0];
                if (scenario.equals("prepare-abort")) throw new IllegalStateException("synthetic hidden preparation abort");
            }, () -> {
                early[0] |= menu ? image.imageVisible() : ready[0];
                check(!materials.get(0).texture.closed, "own material remains leased during actual GUI consumption");
                CpmExtractionBodyProbe.consumerSubmitted();
                if (scenario.endsWith("consumer-failure")) throw new IllegalStateException("synthetic hidden consumer failure");
            }); } catch (RuntimeException | Error thrown) { failure = thrown; }
            check(!early[0], "hidden image preparation must await its actual GUI consumer, not native prepare completion");
            check(!(menu ? image.imageVisible() : ready[0]), "consumer submission is not a signalled fence");
            if (scenario.equals("menu-closed")) {
                check(failure == null, "closed-menu preceding consumer must succeed: " + failure);
                if (panel != null) panel.close(); else closeTarget(producer);
                check(!materials.get(0).texture.closed, "menu owner retirement retains its material until consumer fence");
                CpmExtractionBodyProbe.signalConsumers();
                check(!image.imageVisible() && !image.imageSpinner() && materials.get(0).texture.closes == 1,
                        "retired menu completion cannot revive image or spinner; physical material closes once");
                System.out.println("PASS hidden menu source/consumer case=" + scenario + " family=" + prefix);
                return;
            }
            if (scenario.equals("menu-replaced")) {
                check(failure == null, "replaced-menu preceding consumer must succeed: " + failure);
                var nextSkin = new com.naocraftlab.skins.client.TextureRegistry.TextureHandle("fixture:hidden-own-B",64,64);
                var nextAppearance = new PreviewRenderer.PreviewAppearance(nextSkin, com.naocraftlab.skins.client.SkinModel.CLASSIC,
                        java.util.Optional.empty(), PreviewRenderer.CapeMode.OFF, com.naocraftlab.skins.client.OuterLayerVisibility.noneVisible());
                var next = new PreviewRenderer.PreviewRequest(nextAppearance,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.CURRENT_APPEARANCE);
                publicDraw.accept(next);
                scheduledBaked(queued[0], extraction);
                CpmExtractionBodyProbe.signalConsumers();
                check(!image.imageVisible() && (int) stateField(queued[0], "x1") < 0,
                        "A fence cannot release B image gate or publish an old visible frame");
                CpmExtractionBodyProbe.consumerFrame(loader, () -> prepareRaw(prepare, dispatcher, queued[0], late), () -> {
                    check(materials.size() == 2 && materials.get(1).texture.data.equals(new Identifier(nextSkin.location()))
                            && !materials.get(1).texture.closed, "replacement B reaches its own current material consumer");
                    CpmExtractionBodyProbe.consumerSubmitted();
                });
                CpmExtractionBodyProbe.signalConsumers();
                check(image.imageVisible() && !image.body().current().getAsBoolean(), "own B fence releases image, never visible controls");
                publicDraw.accept(next);
                check((int) stateField(queued[0], "x0") == (panel == null ? 0 : 535), "only completed own B becomes visible");
                consumeVisibleBaked(loader, prepare, dispatcher, queued[0], late, extraction, nextSkin.location());
                System.out.println("PASS hidden menu source/consumer case=" + scenario + " family=" + prefix);
                return;
            }
            Throwable signalFailure = null;
            try { CpmExtractionBodyProbe.signalConsumers(); }
            catch (RuntimeException | Error thrown) { signalFailure = thrown; }
            if (scenario.equals("fence-cleanup-failure")) {
                check(failure == null && signalFailure instanceof IllegalStateException
                                && signalFailure.getCause() != null && signalFailure.getCause().getMessage().equals("synthetic image fence cleanup failure")
                                && signalFailure.getSuppressed().length == 0, "exact image fence cleanup failure is preserved");
                check(!ready[0] && terminal[0], "failed consumer fence cleanup must terminate, not strand, the prepared image attempt");
                System.out.println("PASS hidden menu source/consumer case=" + scenario + " family=" + prefix);
                return;
            }
            check(signalFailure == null, "successful image fence: " + signalFailure);
            if (scenario.equals("prepare-abort")) {
                check(failure instanceof IllegalStateException && failure.getMessage().equals("synthetic hidden preparation abort")
                        && failure.getSuppressed().length == 0, "exact preparation abort is preserved");
                check(!ready[0] && terminal[0], "aborted native preparation must terminate its image attempt without consumer readiness");
            } else if (scenario.endsWith("consumer-failure")) {
                check(failure instanceof IllegalStateException && failure.getMessage().equals("synthetic hidden consumer failure")
                        && failure.getSuppressed().length == 0, "exact hidden consumer failure is preserved");
                check(!(menu ? image.imageVisible() : ready[0]), "failed actual GUI consumption cannot release hidden image gate");
                if (menu) {
                    check(!image.imageSpinner(), "terminal GUI failure stops the image attempt");
                    queued[0] = null;
                    publicDraw.accept(request);
                    check(queued[0] == null, "terminal menu image attempt cannot retry on ambient frames");
                }
            } else {
                check(failure == null, "hidden image consumer positive: " + failure);
                check(menu ? image.imageVisible() : ready[0], "successful actual consumer releases hidden image gate");
                if (menu) {
                    check(!image.body().current().getAsBoolean(), "real menu preparation session cannot grant visible body controls");
                    var preparedTarget = stateField(queued[0], "target");
                    publicDraw.accept(request);
                    check((int) stateField(queued[0], "x0") == (panel == null ? 0 : 535) && stateField(queued[0], "target") == preparedTarget,
                            "next public menu frame displays its own prepared target without another owner");
                    consumeVisibleBaked(loader, prepare, dispatcher, queued[0], late, extraction, skin.location());
                    if (panel != null && scenario.equals("menu-animation")) {
                        check(materials.get(materials.size()-1).texture.materialInputs.contains(new Identifier(animationFrames.get(0).location())),
                                "MENU animation warmed image contains its own first cape frame");
                        var emitted = new java.util.ArrayList<Object>();
                        enqueue = graphics -> emitted.add(call(replace, null, graphics, new MenuState()));
                        for (long elapsed : new long[]{100,200,300,700}) {
                            panel.animationTime(elapsed);
                            emitted.clear();
                            publicDraw.accept(request);
                            String expected = animationFrames.get(animationTimeline.frameAt(elapsed)).location();
                            boolean visible = false;
                            for (Object state : emitted) {
                                if (((PipState)state).x0() >= 0) {
                                    consumeVisibleBaked(loader,prepare,dispatcher,state,late,extraction,skin.location(),expected);
                                    visible = true;
                                } else {
                                    scheduledBaked(state,extraction);
                                    CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,state,late),CpmExtractionBodyProbe::consumerSubmitted);
                                    CpmExtractionBodyProbe.signalConsumers();
                                }
                            }
                            check(visible, "MENU animation current timeline frame remains visibly progressing at low cadence");
                        }
                    }
                    if (panel != null && scenario.equals("menu")) {
                        var resource = materials.get(materials.size()-1).texture;
                        for (int frameIndex=0; frameIndex<8; frameIndex++) {
                            panel.pointer(frameIndex%2==0 ? 560 : 740,frameIndex%2==0 ? 200 : 320);
                            publicDraw.accept(request);
                            check(image.imageVisible() && !image.imageSpinner()
                                            && (int)stateField(queued[0],"x0")==535
                                            && stateField(queued[0],"target")==preparedTarget,
                                    "MENU steady completed frame remains visible/current frame="+frameIndex);
                            consumeVisibleBaked(loader,prepare,dispatcher,queued[0],late,extraction,skin.location());
                            check(materials.get(materials.size()-1).texture==resource && !materials.get(materials.size()-1).texture.closed,
                                    "MENU steady frame retains exact current physical material frame="+frameIndex);
                        }
                        System.out.println("PASS MENU steady underlay/collector/native/consumer/fence frames=8 role="
                                +(Boolean.getBoolean("nclskins.menuPause") ? "pause" : "title")+" NONREPRO_CONTROL synthetic GPU terminal");
                        var nextSkin = new TextureRegistry.TextureHandle("fixture:menu-transition-B",64,64);
                        var nextAppearance = new PreviewRenderer.PreviewAppearance(nextSkin,SkinModel.CLASSIC,
                                java.util.Optional.empty(),PreviewRenderer.CapeMode.OFF,OuterLayerVisibility.noneVisible());
                        var nextRequest = new PreviewRenderer.PreviewRequest(nextAppearance,0,0,160,180,0,0,1,
                                PreviewRenderer.PreviewIntent.CURRENT_APPEARANCE);
                        var pendingB = NativePlayerSkinLifecycle.pending(nextSkin.location());
                        var transitionStates = new java.util.ArrayList<Object>();
                        enqueue = graphics -> transitionStates.add(call(replace,null,graphics,new MenuState()));
                        long[] now = {400_000_000L};
                        set(image,"nanoTime",(java.util.function.LongSupplier)() -> now[0]);
                        Object retainedQueuedA = null;
                        try {
                            for (long elapsed : new long[]{0,299_000_000L}) {
                                now[0] = 400_000_000L+elapsed;
                                transitionStates.clear(); Graphics.loadingFrames.clear();
                                publicDraw.accept(nextRequest);
                                check(transitionStates.size()==1 && ((PipState)transitionStates.get(0)).x0()==535
                                                && field(transitionStates.get(0),"skinTexture").equals(new Identifier(skin.location()))
                                                && stateField(transitionStates.get(0),"target")==preparedTarget
                                                && Graphics.loadingFrames.isEmpty(),
                                        "MENU transition keeps whole own confirmed A before threshold elapsed="+elapsed);
                                consumeVisibleBaked(loader,prepare,dispatcher,transitionStates.get(0),late,extraction,skin.location());
                                retainedQueuedA = transitionStates.get(0);
                            }
                            now[0] = 700_000_000L; transitionStates.clear(); Graphics.loadingFrames.clear();
                            publicDraw.accept(nextRequest);
                            check(transitionStates.isEmpty() && Graphics.loadingFrames.size()==1,
                                    "MENU transition spinner replaces A exactly at 300ms");
                            pendingB.ready(); transitionStates.clear(); publicDraw.accept(nextRequest);
                            check(transitionStates.size()==1 && ((PipState)transitionStates.get(0)).x1()<0,
                                    "MENU own ready texture still requires hidden own native B");
                            Object hiddenB = transitionStates.get(0);
                            check(stateField(hiddenB,"target")!=preparedTarget, "MENU A and B have independent physical targets");
                            scheduledBaked(hiddenB,extraction);
                            CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,hiddenB,late),CpmExtractionBodyProbe::consumerSubmitted);
                            var bResource = materials.get(materials.size()-1).texture;
                            check(bResource!=resource && bResource.data.equals(new Identifier(nextSkin.location())),
                                    "MENU B native material is independent and own, not A");
                            CpmExtractionBodyProbe.signalConsumers();
                            var nextImage = (EditorPreviewSession)field(producer,"imageSession");
                            check(nextImage.imageVisible() && !nextImage.body().current().getAsBoolean(),
                                    "MENU hidden B fence permits first visible B, never old A authority");
                            consumeVisibleBaked(loader,prepare,dispatcher,retainedQueuedA,late,extraction,skin.location());
                            check(!resource.closed && !bResource.closed, "MENU queued A consumed after prepared B retains separate materials");
                            transitionStates.clear(); publicDraw.accept(nextRequest);
                            check(transitionStates.size()==1 && ((PipState)transitionStates.get(0)).x0()==535,
                                    "MENU own B is immediately visible after hidden fence");
                            consumeVisibleBaked(loader,prepare,dispatcher,transitionStates.get(0),late,extraction,nextSkin.location());
                            check(resource.closes==1 && !bResource.closed, "MENU visible B consumer retires A exactly once");
                        } finally { pendingB.retire(); }
                    }
                }
            }
            if (panel != null && scenario.equals("menu-cape-retired")) {
                var previousOwner = panel.capeOwner();
                check(previousOwner != null && previousOwner.isActive(), "MENU A has a live projected cape input owner");
                var capeB = new TextureRegistry.TextureHandle("fixture:menu-retired-input-B",64,32);
                panel.replaceCapeAsset(capeB);
                var ownerB = panel.capeOwner();
                check(!previousOwner.isActive() && ownerB != null && ownerB != previousOwner
                                && ownerB.isActive() && ownerB.ownsFrame(capeB.location()),
                        "MENU real cape asset retirement publishes distinct current B through CapeProjection");
                var pendingBStates = new java.util.ArrayList<Object>();
                var pendingImage = new EditorPreviewSession[1];
                var preparedTarget = stateField(queued[0],"target");
                var materialA = materials.get(materials.size()-1).texture;
                var retainedResource = field(field(preparedTarget,"target"),"resource");
                check(retainedResource != null, "MENU A target retains its own prepared output resource");
                var nativeTarget = field(retainedResource,"value");
                var nativeTexture = nativeTarget.getClass().getSuperclass().getDeclaredField("texture");
                nativeTexture.setAccessible(true);
                check(nativeTexture.get(nativeTarget) != null, "MENU A target physically owns a native render output");
                enqueue = graphics -> pendingBStates.add(call(replace,null,graphics,new MenuState()));
                long[] now = {0};
                set(image,"nanoTime",(java.util.function.LongSupplier)() -> now[0]);
                for (long elapsed : new long[]{0,299_000_000L}) {
                    now[0]=elapsed; pendingBStates.clear(); Graphics.loadingFrames.clear();
                    publicDraw.accept(request);
                    pendingImage[0]=(EditorPreviewSession)field(producer,"imageSession");
                    var visibleA=pendingBStates.stream().filter(state -> ((PipState)state).x0()>=0).findFirst().orElse(null);
                    check(visibleA!=null && field(visibleA,"skinTexture").equals(new Identifier(skin.location()))
                                    && stateField(visibleA,"target")==preparedTarget && Graphics.loadingFrames.isEmpty(),
                            "MENU retired cape input owner keeps whole confirmed A visible before 300ms elapsed="+elapsed);
                    var hiddenB=pendingBStates.stream().filter(state -> ((PipState)state).x1()<0).findFirst().orElse(null);
                    check(hiddenB!=null && stateField(hiddenB,"target")!=preparedTarget
                                    && stateField(hiddenB,"attachmentTexture").equals(new Identifier(capeB.location()))
                                    && ((PreviewModelFrames.Completion)stateField(hiddenB,"completion")).current()
                                    && !pendingImage[0].imageVisible(),
                            "MENU B is independently admitted but hidden and not acknowledged before its own prepare/fence states="
                                    + pendingBStates.stream().map(state -> ((PipState)state).x0()+".."+((PipState)state).x1()+":"
                                            + stateField(state,"attachmentTexture")+":"
                                            + (stateField(state,"completion")==null?"none":((PreviewModelFrames.Completion)stateField(state,"completion")).current())).toList());
                    int materialCount = materials.size();
                    consumeRetainedBaked(loader,prepare,dispatcher,visibleA,late,extraction,materialA,
                            materialCount,skin.location(),animationFrames.get(0).location());
                }
                now[0]=300_000_000L; pendingBStates.clear(); Graphics.loadingFrames.clear();
                publicDraw.accept(request);
                check(pendingBStates.stream().noneMatch(state -> ((PipState)state).x0()>=0)
                                && Graphics.loadingFrames.size()==1,
                        "MENU retired cape source uses the existing 300ms loading threshold");
                    pendingBStates.clear(); publicDraw.accept(request);
                    pendingImage[0]=(EditorPreviewSession)field(producer,"imageSession");
                    var hiddenB=pendingBStates.stream().filter(state -> ((PipState)state).x1()<0).findFirst().orElseThrow();
                    check(((PreviewModelFrames.Completion)stateField(hiddenB,"completion")).currentInput(capeB.location()),
                            "MENU B exact current cape input is admitted for its own native prepare");
                    scheduledBaked(hiddenB,extraction);
                    CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,hiddenB,late),CpmExtractionBodyProbe::consumerSubmitted);
                    var materialB=materials.get(materials.size()-1).texture;
                    check(materialB!=materialA && materialB.materialInputs.contains(new Identifier(capeB.location())),
                            "MENU own B preparation reaches the actual native cape material consumer");
                    CpmExtractionBodyProbe.signalConsumers();
                    check(pendingImage[0].imageVisible(), "MENU B becomes ready only after its own consumer fence");
                    pendingBStates.clear(); publicDraw.accept(request);
                    var visibleB=pendingBStates.stream().filter(state -> ((PipState)state).x0()>=0).findFirst().orElseThrow();
                    consumeVisibleBaked(loader,prepare,dispatcher,visibleB,late,extraction,skin.location(),capeB.location());
                    check(materialA.closes==1 && !materialB.closed,
                            "MENU visible B replaces and releases A only after B's own material and fence");
            }
            if (panel == null || !scenario.equals("menu") && !scenario.equals("menu-cape-retired"))
                check(!materials.get(0).texture.closed, "current material survives preparation fence");
            System.out.println("PASS hidden menu source/consumer case=" + scenario + " family=" + prefix);
        } finally {
            if (pendingTexture != null) pendingTexture.retire();
            if (panel != null) panel.close();
            closeTarget(producer); closeTarget(dispatcher); CpmExtractionBodyProbe.signalConsumers();
            materials.clear(); enqueue = null; Client.current = null;
            CpmExtractionBodyProbe.resetConsumerControl();
        }
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

    private static final Map<Object, CpmExtractionBodyProbe.GuiRenderState> prepareContexts = new java.util.WeakHashMap<>();

    private static Object preparedSubmissionOwner(Class<?> targetType, Object dispatcher) throws ReflectiveOperationException {
        var contexts = targetType.getDeclaredField("CONTEXTS");
        contexts.setAccessible(true);
        return ((Map<?, ?>) contexts.get(null)).get(prepareContexts.get(dispatcher));
    }

    private static void prepareRaw(java.lang.reflect.Method method, Object owner, Object state, boolean late) {
        var context = prepareContexts.computeIfAbsent(owner, ignored -> new CpmExtractionBodyProbe.GuiRenderState());
        call(method, owner, late ? new Object[]{state, context, new CpmExtractionBodyProbe.Features(), 1}
                : new Object[]{state, context, 1});
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

    private static void submissionReplacement(com.naocraftlab.skins.runtime.GalleryNativePassFixture gallery) throws Exception {
        String prefix = "com.naocraftlab.skins.compat.client.identifier.submission.";
        var loader = new Loader(prefix, prefix + "SubmissionPreviewRenderer", prefix + "SimplePreviewRenderer", prefix + "BakedPreviewRenderer", false);
        loader.live = true; loader.physical = true;
        loader.types.remove("com/naocraftlab/skins/runtime/CapeProjection");
        loader.types.put("com/mojang/blaze3d/textures/GpuTexture", CpmExtractionBodyProbe.LegacyTexture.class);
        Client.current = new Client(); Client.current.level = new CpmPreviewScopeProbe.Level();
        Client.current.player = new CpmPreviewScopeProbe.Avatar(Client.current.level);
        var owner = submissionOwner(loader, loader.loadClass(prefix + "SubmissionPreviewRenderer"));
        var liveType = loader.loadClass(prefix + "LivePreviewRenderer");
        var dispatcher = liveType.getConstructor(Buffers.class).newInstance(new Buffers());
        var prepare = java.util.Arrays.stream(liveType.getMethods()).filter(m -> m.getName().equals("prepare") && !m.isBridge()).findFirst().orElseThrow();
        var replace = loader.loadClass(prefix + "LivePreviewSubmission").getMethod("replace", Object.class, PipState.class);
        var queued = new java.util.ArrayList<Object>();
        enqueue = graphics -> queued.add(call(replace, null, graphics, new VanillaState()));
        CpmExtractionBodyProbe.cached = false;
        CpmExtractionBodyProbe.submissionBoundaries(() -> {}, () -> {});
        CpmExtractionBodyProbe.consumerControl("normal");
        CpmSourceReplacementProbe.Material[] consumed = {null};
        try {
            CpmSourceReplacementProbe.run(owner, new Graphics(), gallery,
                    () -> { queued.clear(); CpmExtractionBodyProbe.queuedMaterials().clear(); consumed[0] = null; },
                    () -> {
                        check(queued.size() == 1, "SOURCE1 exactly one selected native submission");
                        try {
                            CpmExtractionBodyProbe.consumerFrame(loader, () -> prepareRaw(prepare, dispatcher, queued.get(0), false), () -> {
                                var materials = CpmExtractionBodyProbe.queuedMaterials();
                                check(materials.size() == 1 && !materials.get(0).texture.closed, "SOURCE1 own live material survives GUI consumption");
                                var own = (PreviewRenderer.PreviewAppearance) materials.get(0).texture.data;
                                consumed[0] = new CpmSourceReplacementProbe.Material(own.skin().location(), own.model());
                                CpmExtractionBodyProbe.consumerSubmitted();
                            });
                            CpmExtractionBodyProbe.signalConsumers();
                        } catch (Exception failure) { throw new AssertionError(failure); }
                    }, () -> queued.size() == 1 && ((PipState) queued.get(0)).x1() < 0, () -> consumed[0],
                    () -> { Client.current.level = new CpmPreviewScopeProbe.Level(); Client.current.player = new CpmPreviewScopeProbe.Avatar(Client.current.level); },
                    () -> Client.current.player = new CpmPreviewScopeProbe.Avatar(Client.current.level));
        } finally {
            closeTarget(owner); closeTarget(dispatcher); CpmExtractionBodyProbe.signalConsumers();
            CpmExtractionBodyProbe.resetConsumerControl(); enqueue = null; Client.current = null;
        }
    }

    private static void retentionHost() throws Exception {
        var pixels = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var bytes = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(pixels, "PNG", bytes);
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:retention-host", 64, 64), SkinModel.CLASSIC,
                java.util.Optional.empty(), PreviewRenderer.CapeMode.OFF, OuterLayerVisibility.allVisible());
        try (var base = new com.naocraftlab.skins.runtime.GalleryNativePassFixture(appearance, bytes.toByteArray())) {
            for (String route : Boolean.getBoolean("nclskins.refreshOnly") ? java.util.List.of("providers:unrelated", "providers:refresh") : java.util.List.of("gallery:source", "gallery:layer", "gallery:mode", "editor:model", "editor:layer", "editor:mode", "editor:source", "providers:inspection", "providers:early", "providers:mode", "providers:layer", "providers:cape", "providers:unrelated", "providers:refresh")) {
                String surface = route.substring(0, route.indexOf(':'));
                String change = route.substring(route.indexOf(':') + 1);
                boolean extraction = CpmBakedDisplayProbe.class.getClassLoader().getResource("com/naocraftlab/skins/compat/client/identifier/extraction/AvatarPipPreviewRenderer.class") != null;
                String prefix = "com.naocraftlab.skins.compat.client.identifier." + (extraction ? "extraction." : "submission.");
                var loader = new Loader(prefix, prefix + (extraction ? "AvatarRenderStatePreviewRenderer" : "SubmissionPreviewRenderer"), prefix + (extraction ? "AvatarPipPreviewRenderer" : "SimplePreviewRenderer"), prefix + (extraction ? "NclBakedPlayerRenderer" : "BakedPreviewRenderer"), extraction);
                loader.live = !extraction; loader.physical = true;
                loader.types.put("com/mojang/blaze3d/textures/GpuTexture", CpmExtractionBodyProbe.LegacyTexture.class);
                Client.current = new Client();
                Object owner = submissionOwner(loader, loader.loadClass(prefix + (extraction ? "AvatarRenderStatePreviewRenderer" : "SubmissionPreviewRenderer")));
                @SuppressWarnings("unchecked") var renderer = (PreviewRenderer<Graphics>) owner;
                var dispatcherType = loader.loadClass(prefix + (extraction ? "NclBakedPlayerRenderer" : "BakedPreviewRenderer"));
                Object dispatcher = dispatcherType.getConstructors()[0].getParameterCount() == 0
                        ? create(dispatcherType) : dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
                var prepare = java.util.Arrays.stream(dispatcherType.getDeclaredMethods()).filter(m -> m.getName().equals("prepare") && !m.isBridge()).findFirst().orElseThrow();
                boolean late = prepare.getParameterCount() == 4;
                var replace = loader.loadClass(prefix + (extraction ? "NclBakedPlayerSubmission" : "BakedPreviewSubmission")).getMethod("replace", Object.class, PipState.class);
                var queued = new java.util.ArrayList<Object>(); enqueue = graphics -> {
                    var clip = ((Graphics) graphics).currentScissor();
                    queued.add(call(replace, null, graphics, new MenuState(clip)));
                };
                var materials = CpmExtractionBodyProbe.queuedMaterials(); materials.clear();
                CpmExtractionBodyProbe.cached = false; CpmExtractionBodyProbe.bakedBoundaries(() -> {}, () -> {}, () -> {});
                CpmExtractionBodyProbe.consumerControl("normal");
                try (var subject = base.freshOwned()) {
                    subject.localCapes();
                    if (surface.equals("providers") && (change.equals("mode") || change.equals("cape"))) subject.selectProviderCape();
                    if (route.equals("editor:source")) subject.distinctPersonalCapes();
                    subject.layerController = new com.naocraftlab.skins.client.OuterLayerVisibilityController() {
                        private OuterLayerVisibility value = OuterLayerVisibility.allVisible();
                        public OuterLayerVisibility current() { return value; }
                        public void applyDurable(OuterLayerVisibility next) { value = next; }
                    };
                    var runtime = subject.runtime;
                    if (surface.equals("editor")) {
                        var more = runtime.view(854, 480, 0, 0).widgets().stream().filter(w -> w.id().startsWith("gallery.preset.") && w.id().endsWith(".more") && w.visible() && w.enabled()).findFirst().orElseThrow();
                        runtime.dispatchWidget(more.id());
                        var edit = runtime.view(854, 480, 0, 0).widgets().stream().filter(w -> w.id().startsWith("gallery.preset.") && w.id().endsWith(".edit") && w.visible() && w.enabled()).findFirst().orElseThrow();
                        runtime.dispatchWidget(edit.id());
                        if (change.equals("source")) runtime.dispatchWidget("editor.tab.cape");
                    } else if (surface.equals("providers")) {
                        runtime.dispatchWidget("gallery.providers");
                        if (change.equals("cape") || change.equals("layer")) runtime.dispatchWidget("providers.tab.CAPE");
                        if (change.equals("layer")) {
                            subject.drainWorkers();
                            for (String provider : java.util.List.of("OFFLINE", "MINECRAFT")) {
                                String remove = "providers.remove." + provider;
                                check(runtime.view(854,480,0,0).widget(remove).filter(widget -> widget.visible() && widget.enabled()).isPresent(),
                                        "PROVIDERS actual no-Gallery setup removal admitted " + remove);
                                runtime.dispatchWidget(remove); subject.drainWorkers();
                            }
                            check(!runtime.snapshot().providers().galleryAvailable(), "PROVIDERS layer route really has no Gallery");
                        }
                    }
                    subject.drainWorkers();
                    if (change.equals("refresh")) { runtime.dispatchWidget("providers.row.MINECRAFT"); subject.drainWorkers(); }
                    var hostType = CpmPreviewScreenSlice.loadNative(prefix + "NclSkinsScreen", owner.getClass());
                    Object host = hostType.getConstructor().newInstance();
                    var registry = new CpmOwnedPreviewDispatchProbe.Registry();
                    try (var skins = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry, TextureRegistry.TextureKind.PLAYER_SKIN, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding(), renderer::retainedTextures);
                         var capes = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry, TextureRegistry.TextureKind.IMAGE, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding(), renderer::retainedTextures)) {
                        for (var f : hostType.getDeclaredFields()) {
                            Object value = switch (f.getName()) {
                                case "runtime" -> runtime; case "editorRenderer" -> owner; case "skinTextures" -> skins; case "capeTextures" -> capes; case "textureRegistry" -> registry;
                                default -> f.getType() == Map.class ? new HashMap<>() : null;
                            };
                            if (value != null) f.set(host, value);
                        }
                        var sync = hostType.getDeclaredMethod(extraction ? "syncPreviewAssets" : "synchronizePreviewAssets", com.naocraftlab.skins.runtime.ViewSpec.class);
                        var paint = hostType.getDeclaredMethod(extraction ? "drawPreviews" : "renderPreviews", Graphics.class, com.naocraftlab.skins.runtime.ViewSpec.class);
                        var graphics = new Graphics();
                        for (int pass = 0; pass < 8 && renderer.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED; pass++) {
                            call(sync, host, runtime.view(854, 480, 0, 0)); subject.drainWorkers();
                            call(sync, host, runtime.view(854, 480, 0, 0)); queued.clear(); materials.clear();
                            call(paint, host, graphics, runtime.view(854, 480, 0, 0));
                            if (pass == 0 && surface.equals("providers")) check(!queued.isEmpty()
                                    && queued.stream().allMatch(state -> ((PipState)state).x1() < 0), "PROVIDERS first open has no invented A");
                            CpmExtractionBodyProbe.consumerFrame(loader, () -> { for (var state : queued) prepareRaw(prepare, dispatcher, state, late); }, CpmExtractionBodyProbe::consumerSubmitted);
                            CpmExtractionBodyProbe.signalConsumers();
                        }
                        check(renderer.displayKind() == PreviewRenderer.DisplayKind.ORDINARY && !materials.isEmpty(), "RETENTION_HOST established actual complete A " + surface);
                        var materialA = materials.get(materials.size() - 1).texture.data;
                        var physicalA = materials.get(materials.size() - 1).texture;
                        var stateA = queued.get(queued.size() - 1);
                        var slotA = field(owner, "session");
                        var before = surface.equals("gallery") ? runtime.galleryPreviewCandidate().orElseThrow() : runtime.view(854,480,0,0).previews().get(0);
                        String action;
                        if (change.equals("refresh") || change.equals("unrelated")) action = "providers.refresh";
                        else if (change.equals("mode")) action = surface + ".preview_mode";
                        else if (change.equals("layer")) action = surface + ".outer_layer.head";
                        else if (surface.equals("gallery")) {
                            var widget = runtime.view(854,480,0,0).widgets().stream().filter(w -> w.id().startsWith("gallery.card.") && w.visible() && w.enabled() && !w.id().equals("gallery.card." + subject.preview().presetId().orElseThrow())).findFirst().orElseThrow();
                            action = widget.id();
                        } else if (surface.equals("editor") && change.equals("source")) {
                            var editorView = runtime.view(854,480,0,0);
                            var catalog = runtime.snapshot().editor().orElseThrow().capeCatalog();
                            var alternate = catalog.cards().stream()
                                    .filter(card -> !card.service() && card.texture().isPresent()
                                            && !card.texture().equals(before.capeId()))
                                    .filter(card -> editorView.navigationNode(card.widgetId())
                                            .filter(node -> node.enabled()).isPresent())
                                    .findFirst().orElseThrow();
                            action = alternate.widgetId();
                        } else action = surface.equals("editor") ? "editor.model_choice.slim" : "providers.row.MINECRAFT";
                        var capeA = change.equals("source") && surface.equals("editor") || change.equals("cape") && surface.equals("providers")
                                ? before.capeId().flatMap(id -> capes.handle(runtime.previewCapeSurfaceKey(before))).orElseThrow()
                                : null;
                        var capeAEntryValidity = capeA == null ? null
                                : capes.capeState(runtime.previewCapeSurfaceKey(before), before.capeId().orElseThrow()).validity();
                        if (capeA != null) {
                            check(registry.capture(capeA).isValid(), "RETENTION_HOST A owns its distinct cape before source transition");
                            check(java.util.Objects.equals(field(stateA, "attachmentTexture"), new Identifier(capeA.location())),
                                    "RETENTION_HOST displayed A request carries its own cape material");
                        }
                        var admitted = runtime.view(854,480,0,0).widget(action).orElse(null);
                        if (admitted != null) check(admitted.visible() && admitted.enabled(), "RETENTION_HOST real action admitted " + action);
                        else check(runtime.view(854,480,0,0).navigationNode(action).filter(node -> node.enabled()).isPresent(),
                                "RETENTION_HOST actual admitted cape navigation action " + action);
                        runtime.dispatchWidget(action);
                        if (!change.equals("refresh") && !change.equals("unrelated")) subject.drainWorkers();
                        var after = surface.equals("gallery") ? runtime.galleryPreviewCandidate().orElseThrow() : runtime.view(854,480,0,0).previews().get(0);
                        if (change.equals("refresh") || change.equals("unrelated")) {
                            var anchor = after.anchorBounds();
                            double x = anchor.x() + anchor.width() / 2.0;
                            double y = anchor.y() + anchor.height() / 2.0;
                            runtime.pointerPressed(x, y, 0);
                            runtime.pointerDragged(x + 24, y + 12, 0, 24, 12);
                            runtime.pointerReleased(x + 24, y + 12, 0);
                            runtime.pointerScrolled(x, y, 0, 1);
                            var gesture = runtime.view(854,480,0,0).previews().get(0);
                            check(gesture.yawDegrees() != after.yawDegrees() && gesture.pitchDegrees() != after.pitchDegrees()
                                    && gesture.scale() != after.scale(), "REFRESH admitted pointer drag/wheel changes actual ViewSpec pose");
                            check(runtime.view(854,480,0,0).widget(action).filter(w -> !w.enabled()).isPresent(),
                                    "REFRESH pending keeps duplicate action disabled after gesture");
                            runtime.dispatchWidget(action);
                            System.out.println("PASS REFRESH actual input admission " + change);
                        }
                        if (change.equals("unrelated")) {
                            check(runtime.providerImagePreparation(after).isEmpty(), "REFRESH unrelated worker does not own main preparation");
                            call(sync,host,runtime.view(854,480,0,0)); queued.clear(); materials.clear();
                            call(paint,host,graphics,runtime.view(854,480,0,0));
                            check(queued.size()==1 && ((PipState)queued.get(0)).x0()>=0 && field(owner,"session")==slotA,
                                    "REFRESH unrelated pending worker preserves own visible main and session");
                            CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),late),CpmExtractionBodyProbe::consumerSubmitted);
                            CpmExtractionBodyProbe.signalConsumers();
                            check(materials.get(0).texture==physicalA && !physicalA.closed && renderer.displayKind()==PreviewRenderer.DisplayKind.ORDINARY,
                                    "REFRESH unrelated retains exact material and own authority");
                            subject.drainWorkers();
                            System.out.println("PASS REFRESH unrelated real worker preserves exact visible main material/authority");
                            continue;
                        }
                        if (change.equals("refresh")) {
                            check(runtime.providerImagePreparation(after).isPresent(), "REFRESH real admitted own worker remains pending");
                            check(runtime.previewBodySource(before).equals(runtime.previewBodySource(after)) && before.imageRevision().equals(after.imageRevision()),
                                    "REFRESH actual producer preserves same body source and image identity");
                        } else if (change.equals("source") && surface.equals("editor")) {
                            check(!before.capeId().equals(after.capeId()), "RETENTION_HOST admitted Editor cape card changes the actual cape source");
                        } else {
                            check(!runtime.previewBodySource(before).equals(runtime.previewBodySource(after)), "RETENTION_HOST admitted action really changes candidate " + surface);
                        }
                        if (change.equals("mode")) check(before.capeMode() != after.capeMode(), "RETENTION_HOST actual cape/elytra mode changed");
                        if (change.equals("model")) check(before.variant() != after.variant(), "RETENTION_HOST actual body model changed");
                        if (change.equals("layer")) check(!before.outerLayerVisibility().equals(after.outerLayerVisibility()), "RETENTION_HOST actual mask changed");
                        if (change.equals("cape")) check(before.capeId().isPresent() && !before.capeId().equals(after.capeId()),
                                "RETENTION_HOST Providers cape-tab inspection changes its actual cape source");
                        if (change.equals("source")) {
                            check(!before.capeId().equals(after.capeId()), "RETENTION_HOST selected source really changes its own cape as well as body");
                            if (surface.equals("providers"))
                                check(before.capeId().isPresent() && after.capeId().isPresent(), "RETENTION_HOST Providers A and B both have distinct cape material");
                        }
                        call(sync, host, runtime.view(854,480,0,0)); if (!change.equals("refresh")) subject.drainWorkers(); call(sync, host, runtime.view(854,480,0,0));
                        if (capeA != null) {
                            check(!capeAEntryValidity.isValid(), "RETENTION_HOST A candidate CapeState validity is revoked after B replacement");
                            check(registry.capture(capeA).isValid(), "RETENTION_HOST displayed A retains its own material reference until ACK");
                        }
                        queued.clear(); materials.clear(); call(paint, host, graphics, runtime.view(854,480,0,0));
                        var slotB = (EditorPreviewSession) field(owner, "session");
                        if (!change.equals("refresh")) check(slotB != slotA, "RETENTION_HOST desired B has independent native owner " + surface);
                        long[] now = {0}; set(slotB, "nanoTime", (java.util.function.LongSupplier) () -> now[0]); set(slotB, "imageStarted", 0L);
                        Object[] retainedFrameA = {null};
                        for (long tick : change.equals("early") ? new long[]{0} : surface.equals("providers") ? new long[]{0, 299_000_000L, 300_000_000L, 5_000_000_000L} : new long[]{0, 299_000_000L}) {
                            now[0] = tick; queued.clear(); materials.clear(); Graphics.loadingFrames.clear(); call(paint, host, graphics, runtime.view(854,480,0,0));
                            if (surface.equals("providers")) check(!slotB.imageSpinner() && Graphics.loadingFrames.isEmpty(), "PROVIDERS main never paints spinner at " + tick);
                            check(queued.size() == 2 && ((PipState)queued.get(0)).x0() >= 0 && ((PipState)queued.get(1)).x1() < 0, "RETENTION_HOST whole A and hidden B at " + tick + " " + surface + " queued=" + queued.size());
                            retainedFrameA[0] = queued.get(0);
                            if (change.equals("refresh")) {
                                var gesture = runtime.view(854,480,0,0).previews().get(0);
                                check(field(queued.get(0), "yawDegrees").equals(gesture.yawDegrees())
                                        && field(queued.get(0), "pitchDegrees").equals(gesture.pitchDegrees())
                                        && !field(queued.get(0), "scale").equals(field(stateA, "scale")),
                                        "REFRESH displayed A uses latest admitted rotation and zoom, not frozen pose: actual="
                                                + field(queued.get(0), "yawDegrees") + "/" + field(queued.get(0), "pitchDegrees") + "/" + field(queued.get(0), "scale")
                                                + " expected=" + gesture.yawDegrees() + "/" + gesture.pitchDegrees() + "/" + gesture.scale()
                                                + " oldScale=" + field(stateA,"scale"));
                                double expectedScale = ((Number) field(stateA, "scale")).doubleValue()
                                        * gesture.renderedScale() / before.renderedScale();
                                double actualScale = ((Number) field(queued.get(0), "scale")).doubleValue();
                                check(Math.abs(actualScale - expectedScale) < Math.max(0.0001, Math.abs(expectedScale) * 0.00001),
                                        "REFRESH retained A uses rendered layout times logical zoom, without replacing A composition");
                            }
                            for (String component : extraction
                                    ? java.util.List.of("playerModel", "simplePlayerModel", "attachmentPlayerModel", "simpleAttachmentModel", "capeMode", "outerLayerVisibility", "skinTexture", "attachmentTexture")
                                    : java.util.List.of("playerModel", "attachmentModel", "capeMode", "outerLayerVisibility", "skinTexture", "attachmentTexture"))
                                check(java.util.Objects.equals(field(stateA, component), field(queued.get(0), component)), "RETENTION_HOST exact old recipe component " + component + " " + surface);
                            int modelDrawsBefore = draws;
                            CpmExtractionBodyProbe.consumerFrame(loader, () -> prepareRaw(prepare, dispatcher, queued.get(0), late), CpmExtractionBodyProbe::consumerSubmitted);
                            CpmExtractionBodyProbe.signalConsumers();
                            if (change.equals("refresh")) check(draws > modelDrawsBefore,
                                    "REFRESH retained A consumes real native model inputs, not only cached raster blit");
                            check(materials.size() == 1 && materials.get(0).texture.data.equals(materialA), "RETENTION_HOST actual A body material unchanged " + surface);
                            if (capeA != null) {
                                check(registry.capture(capeA).isValid(), "RETENTION_HOST displayed A cape reference remains alive at " + tick);
                                check(java.util.Objects.equals(field(queued.get(0), "attachmentTexture"), new Identifier(capeA.location())),
                                        "RETENTION_HOST actual A draw retains its own cape material at " + tick);
                            }
                            check(renderer.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "RETENTION_HOST old image never grants successor authority " + surface);
                        }
                        if (change.equals("refresh")) {

                            @SuppressWarnings("unchecked") var keys = (Map<String,Object>) field(host,extraction ? "previewSkinKeys" : "previewKeys");
                            var savedKeys = new HashMap<>(keys);
                            keys.clear();
                            try {
                                var gesture = runtime.view(854,480,0,0).previews().get(0);
                                var anchor = gesture.anchorBounds();
                                double x = anchor.x() + anchor.width() / 2.0, y = anchor.y() + anchor.height() / 2.0;
                                runtime.pointerPressed(x,y,0);
                                runtime.pointerDragged(x + 10,y + 5,0,10,5);
                                runtime.pointerReleased(0);
                                runtime.pointerScrolled(x,y,0,1);
                                var latest = runtime.view(854,480,0,0).previews().get(0);
                                var liveView = runtime.view(854,480,0,0);
                                var components = liveView.getClass().getRecordComponents();
                                var types = new Class<?>[components.length];
                                var values = new Object[components.length];
                                for (int i = 0; i < components.length; i++) {
                                    types[i] = components[i].getType();
                                    values[i] = components[i].getAccessor().invoke(liveView);
                                    if (components[i].getName().equals("previews")) values[i] = liveView.previews().stream()
                                            .map(p -> p.id().equals(latest.id()) ? p.withOwnedPng(true) : p).toList();
                                }
                                var absentView = (com.naocraftlab.skins.runtime.ViewSpec) liveView.getClass().getDeclaredConstructor(types).newInstance(values);
                                check(runtime.previewBodySource(absentView.previews().get(0)).equals(runtime.previewBodySource(latest)),
                                        "REFRESH projected-empty host component preserves actual semantic source");
                                queued.clear(); materials.clear(); call(paint,host,graphics,absentView);
                                check(queued.size() == 1 && ((PipState)queued.get(0)).x0() >= 0,
                                        "REFRESH absent projected B emits only retained visible A");
                                check(field(queued.get(0),"yawDegrees").equals(latest.yawDegrees())
                                        && field(queued.get(0),"pitchDegrees").equals(latest.pitchDegrees())
                                        && !field(queued.get(0),"scale").equals(field(retainedFrameA[0],"scale")),
                                        "REFRESH absent projected B still forwards latest A gesture pose");
                                int beforeDraw = draws;
                                CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),late),CpmExtractionBodyProbe::consumerSubmitted);
                                CpmExtractionBodyProbe.signalConsumers();
                                check(draws > beforeDraw && materials.size() == 1 && materials.get(0).texture.data.equals(materialA),
                                        "REFRESH absent B still consumes live A inputs with exact old material");
                                retainedFrameA[0] = queued.get(0);
                            } finally { keys.putAll(savedKeys); }
                            System.out.println("PASS REFRESH projected-empty B real gesture/model/material");
                        }
                        now[0] = change.equals("early") ? 25_000_000L : surface.equals("providers") ? 5_000_000_000L : 300_000_000L;
                        queued.clear(); materials.clear(); call(paint, host, graphics, runtime.view(854,480,0,0));
                        check(queued.size() == (surface.equals("providers") ? 2 : 1)
                                && ((PipState)queued.get(queued.size() - 1)).x1() < 0
                                && slotB.imageSpinner() != surface.equals("providers"), "RETENTION_HOST surface-specific pending policy " + surface);
                        if (change.equals("refresh")) {
                            check(runtime.providerImagePreparation(after).isPresent(), "REFRESH own worker still held through long pending");
                            subject.drainWorkers();
                            var settled = runtime.view(854,480,0,0).previews().get(0);
                            check(runtime.previewBodySource(settled).equals(runtime.previewBodySource(after)), "REFRESH unchanged completion preserves semantic source");
                            check(runtime.providerImagePreparation(settled).isEmpty(), "REFRESH real completion settles its own attempt");
                            call(sync,host,runtime.view(854,480,0,0));
                            queued.clear(); materials.clear(); call(paint,host,graphics,runtime.view(854,480,0,0));
                            check(queued.size()==2 && ((PipState)queued.get(0)).x0()>=0 && ((PipState)queued.get(1)).x1()<0,
                                    "REFRESH completion still needs own hidden B, retaining A");
                        }
                        var stateB = queued.get(queued.size() - 1);
                        if (change.equals("refresh")) {
                            var gesture = runtime.view(854,480,0,0).previews().get(0);
                            check(field(stateB,"yawDegrees").equals(gesture.yawDegrees())
                                    && field(stateB,"pitchDegrees").equals(gesture.pitchDegrees())
                                    && field(stateB,"scale").equals(field(retainedFrameA[0],"scale")),
                                    "REFRESH own hidden B inherits latest admitted gesture pose");
                        }
                        if (change.equals("refresh")) check(slotA != slotB && stateField(stateB,"target") != stateField(stateA,"target"),
                                "REFRESH same semantic source owns separate session and native target");
                        var skinKey = hostType.getDeclaredMethod("skinKey", com.naocraftlab.skins.runtime.ViewSpec.Preview.class); skinKey.setAccessible(true);
                        var expectedSkin = after.requiresLoadedSkin() ? skins.handle(skinKey.invoke(null, after)).orElseThrow()
                                : runtime.previewPlayerAppearance(after).orElseThrow().skin();
                        check(field(stateB, "skinTexture").equals(new Identifier(expectedSkin.location())), "RETENTION_HOST B uses its canonical own source, never borrowed A");
                        check(field(stateB, "outerLayerVisibility").equals(after.outerLayerVisibility()) && field(stateB, "capeMode") == after.capeMode(), "RETENTION_HOST own B mask/mode");
                        var bakedB = field(owner, extraction ? "bakedRenderer" : "baked");
                        String modelPrefix = after.variant() == com.naocraftlab.skins.core.model.SkinVariant.SLIM ? "slim" : "wide";
                        String modelComponent = extraction && field(stateB, "playerModel") == null ? "simplePlayerModel" : "playerModel";
                        var expectedModel = extraction ? field(field(bakedB, "models"), modelPrefix + (modelComponent.equals("playerModel") ? "PlayerModel" : "Model")) : field(bakedB, modelPrefix);
                        check(field(stateB, modelComponent) == expectedModel, "RETENTION_HOST own B selected body model");
                        var ownCape = after.capeId().flatMap(id -> capes.handle(runtime.previewCapeSurfaceKey(after)));
                        Object expectedAttachment = ownCape.filter(ignored -> after.capeMode() != PreviewRenderer.CapeMode.OFF)
                                .map(handle -> new Identifier(after.capeMode() == PreviewRenderer.CapeMode.ELYTRA && !capes.capeHasElytra(runtime.previewCapeSurfaceKey(after))
                                        ? "minecraft:textures/entity/equipment/wings/elytra.png" : handle.location())).orElse(null);
                        check(java.util.Objects.equals(field(stateB, "attachmentTexture"), expectedAttachment), "RETENTION_HOST own B cape/elytra material, never A accessory");
                        Object admittedB = stateB;
                        boolean consumedByCollector = false;
                        if (surface.equals("providers") && extraction) {
                            var pipB = (PipState) stateB;
                            var clip = pipB.scissorArea();
                            var actualBounds = (Rectangle) stateB.getClass().getMethod("bounds").invoke(stateB);
                            check(field(stateB, "completion") != null && pipB.x1() < 0,
                                    "PROVIDERS host B is the real hidden completion payload");
                            check(java.util.Objects.equals(actualBounds, PipState.getBounds(pipB.x0(), pipB.y0(), pipB.x1(), pipB.y1(), null)),
                                    "PROVIDERS hidden B SDK bounds intentionally omit host scissor");
                            System.out.println("TRACE PROVIDERS hidden B hostScissor=" + clip + " SDKBounds=" + actualBounds);
                            String sdkNamespace = "net.minecraft.client.renderer.state.gui.";
                            var components = stateB.getClass().getRecordComponents();
                            var types = new Class<?>[components.length];
                            var values = new Object[components.length];
                            for (int i = 0; i < components.length; i++) {
                                types[i] = components[i].getType();
                                values[i] = components[i].getAccessor().invoke(stateB);
                                if (components[i].getName().equals("x0") || components[i].getName().equals("x1"))
                                    values[i] = ((Integer) values[i]) + 1000;
                            }
                            var canonical = stateB.getClass().getDeclaredConstructor(types);
                            canonical.setAccessible(true);
                            Object movedB = canonical.newInstance(values);
                            var disjoint = new Rectangle(pipB.x1() + 1100, pipB.y1() + 100, 10, 10);
                            var withScissor = stateB.getClass().getDeclaredMethod("withScissor", Rectangle.class);
                            withScissor.setAccessible(true);
                            var rejectedB = (PipState) withScissor.invoke(movedB, disjoint);
                            check(rejectedB.x1() >= 0 && rejectedB.scissorArea().equals(disjoint)
                                            && rejectedB.getClass().getMethod("bounds").invoke(rejectedB) == null,
                                    "PROVIDERS legal moved B with disjoint scissor has null SDK bounds");
                            var rejected = CpmExtractionBodyProbe.sdkTraversal(java.util.List.of(rejectedB), false, false,
                                    sdkNamespace, value -> {
                                        try {
                                            var b = (Rectangle) value.getClass().getMethod("bounds").invoke(value);
                                            return b == null ? null : new int[]{b.left(), b.top(), b.left() + b.width(), b.top() + b.height()};
                                        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                                    }, value -> 0, value -> true, false);
                            check(rejected.isEmpty(), "PROVIDERS SDK rejects empty bounds");
                            java.util.function.Consumer<java.util.List<PipState>> consumeCollected = payloads -> {
                                try {
                                    CpmExtractionBodyProbe.consumerFrame(loader, () -> {
                                        for (var payload : payloads) prepareRaw(prepare, dispatcher, payload, late);
                                    }, CpmExtractionBodyProbe::consumerSubmitted);
                                    CpmExtractionBodyProbe.signalConsumers();
                                } catch (Exception failure) { throw new AssertionError(failure); }
                            };
                            consumeCollected.accept(rejected);
                            check(materials.isEmpty() && renderer.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED,
                                    "PROVIDERS collector rejection cannot produce material or ACK");
                            var accepted = CpmExtractionBodyProbe.sdkTraversal(java.util.List.of(pipB), false, false,
                                    sdkNamespace, value -> actualBounds == null ? null : new int[]{actualBounds.left(), actualBounds.top(),
                                            actualBounds.left() + actualBounds.width(), actualBounds.top() + actualBounds.height()},
                                    value -> 0, value -> true, false);
                            check(actualBounds != null && accepted.size() == 1 && accepted.get(0) == stateB,
                                    "PROVIDERS SDK admits exactly the host-produced hidden B");
                            consumeCollected.accept(accepted);
                            consumedByCollector = true;
                            System.out.println("PASS PROVIDERS first SDK collector exact host B; disjoint scissor rejected without material or ACK");
                        }
                        if (!consumedByCollector) {
                            Object collectedB = admittedB;
                            CpmExtractionBodyProbe.consumerFrame(loader, () -> prepareRaw(prepare, dispatcher, collectedB, late), CpmExtractionBodyProbe::consumerSubmitted); CpmExtractionBodyProbe.signalConsumers();
                        }
                        var materialB = materials.get(materials.size() - 1).texture.data;
                        if (change.equals("refresh")) {
                            var physicalB = materials.get(materials.size()-1).texture;
                            check(physicalB!=physicalA && !physicalA.closed && !physicalB.closed, "REFRESH same source owns independent physical materials");
                            CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,retainedFrameA[0],late),CpmExtractionBodyProbe::consumerSubmitted);
                            CpmExtractionBodyProbe.signalConsumers();
                            check(materials.get(materials.size()-1).texture==physicalA && physicalA.data.equals(materialA)
                                            && !physicalB.closed, "REFRESH queued A consumed after prepared B is not overwritten or released");
                        }
                        check(renderer.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "RETENTION_HOST hidden B has no ACK " + surface);
                        queued.clear(); materials.clear(); call(paint, host, graphics, runtime.view(854,480,0,0));
                        check(queued.size() == 1 && ((PipState)queued.get(0)).x0() >= 0, "RETENTION_HOST immediate own B reveal " + surface);
                        CpmExtractionBodyProbe.consumerFrame(loader, () -> prepareRaw(prepare, dispatcher, queued.get(0), late), CpmExtractionBodyProbe::consumerSubmitted); CpmExtractionBodyProbe.signalConsumers();
                        check(materials.get(materials.size() - 1).texture.data.equals(materialB), "RETENTION_HOST own visible B consumes its own prepared material");
                        check(renderer.displayKind() == PreviewRenderer.DisplayKind.ORDINARY && ((Map<?,?>)field(owner,"gallerySlots")).size() == 1, "RETENTION_HOST own visible B ACK retires A " + surface);
                        call(sync, host, runtime.view(854,480,0,0)); subject.drainWorkers();
                        if (capeA != null) check(!registry.capture(capeA).isValid(), "RETENTION_HOST own B ACK and ensuing live retain sweep release the A cape reference exactly once");
                        System.out.println("PASS RETENTION_HOST compiled " + surface + " admitted " + action + " -> independent desired B -> exact A material -> "
                                + (surface.equals("providers") ? change.equals("early") ? "25ms own B, no spinner" : "0/299/300/5000ms A, no spinner" : "300ms spinner")
                                + " -> own B consumer/visible ACK" + (capeA == null ? "" : "; old own cape A material held until ACK and released on live sweep"));
                        var oldB = queued.get(0);
                        if (surface.equals("gallery")) runtime.resourcesReloaded();
                        else if (surface.equals("editor")) call(com.naocraftlab.skins.runtime.ClientRuntime.class.getDeclaredMethod("importWorldChanged"), runtime);
                        else runtime.closeScreen();
                        check(renderer.retainedTextures().isEmpty(), "RETENTION_HOST lifecycle immediately revokes predecessor material ownership " + surface);
                        materials.clear();
                        CpmExtractionBodyProbe.consumerFrame(loader, () -> prepareRaw(prepare, dispatcher, oldB, late), CpmExtractionBodyProbe::consumerSubmitted);
                        CpmExtractionBodyProbe.signalConsumers();
                        check(materials.isEmpty(), "RETENTION_HOST retired queued frame cannot draw after lifecycle invalidation " + surface);
                    }
                } finally { closeTarget(owner); closeTarget(dispatcher); CpmExtractionBodyProbe.signalConsumers(); CpmExtractionBodyProbe.resetConsumerControl(); materials.clear(); enqueue = null; Client.current = null; }
            }
        }
    }

    private static void retentionImage() throws Exception {
        retentionImage(false);
        retentionImage(true);
    }

    private static void retentionImage(boolean early) throws Exception {
        boolean extraction = CpmBakedDisplayProbe.class.getClassLoader().getResource("com/naocraftlab/skins/compat/client/identifier/extraction/AvatarPipPreviewRenderer.class") != null;
                String prefix = "com.naocraftlab.skins.compat.client.identifier." + (extraction ? "extraction." : "submission.");
        var loader = new Loader(prefix, prefix + (extraction ? "AvatarRenderStatePreviewRenderer" : "SubmissionPreviewRenderer"), prefix + (extraction ? "AvatarPipPreviewRenderer" : "SimplePreviewRenderer"), prefix + (extraction ? "NclBakedPlayerRenderer" : "BakedPreviewRenderer"), extraction);
        loader.live = !extraction;
        loader.physical = true;
        loader.types.put("com/mojang/blaze3d/textures/GpuTexture", CpmExtractionBodyProbe.LegacyTexture.class);
        Client.current = new Client();
        var type = loader.loadClass(prefix + (extraction ? "AvatarRenderStatePreviewRenderer" : "SubmissionPreviewRenderer"));
        Object owner = submissionOwner(loader, type);
        @SuppressWarnings("unchecked") var renderer = (PreviewRenderer<Graphics>) owner;
        var dispatcherType = loader.loadClass(prefix + (extraction ? "NclBakedPlayerRenderer" : "BakedPreviewRenderer"));
        Object dispatcher = dispatcherType.getConstructors()[0].getParameterCount() == 0
                ? create(dispatcherType) : dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
        var prepare = java.util.Arrays.stream(dispatcherType.getDeclaredMethods()).filter(m -> m.getName().equals("prepare") && !m.isBridge()).findFirst().orElseThrow();
        boolean late = prepare.getParameterCount() == 4;
        var replace = loader.loadClass(prefix + (extraction ? "NclBakedPlayerSubmission" : "BakedPreviewSubmission")).getMethod("replace", Object.class, PipState.class);
        var queued = new java.util.ArrayList<Object>();
        enqueue = graphics -> queued.add(call(replace, null, graphics, new VanillaState()));
        var materials = CpmExtractionBodyProbe.queuedMaterials();
        materials.clear();
        CpmExtractionBodyProbe.cached = false;
        CpmExtractionBodyProbe.bakedBoundaries(() -> {}, () -> {}, () -> {});
        CpmExtractionBodyProbe.consumerControl("normal");
        long[] now = {0};
        try {
            var a = new PreviewRenderer.PreviewRequest(new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:retention-A", 64, 64),
                    SkinModel.CLASSIC, java.util.Optional.empty(), PreviewRenderer.CapeMode.OFF, OuterLayerVisibility.allVisible()),
                    0, 0, 160, 180, 0, 0, 1, PreviewRenderer.PreviewIntent.EDITOR_DRAFT);
            var b = new PreviewRenderer.PreviewRequest(new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:retention-B", 64, 64),
                    SkinModel.SLIM, java.util.Optional.empty(), PreviewRenderer.CapeMode.OFF, OuterLayerVisibility.noneVisible()),
                    0, 0, 160, 180, 0, 0, 1, PreviewRenderer.PreviewIntent.EDITOR_DRAFT);
            renderer.prepareGalleryImage("A", false, () -> true);
            set(field(owner, "session"), "nanoTime", (java.util.function.LongSupplier) () -> now[0]);
            renderer.prepareImage("A", 0);
            renderer.renderGallery(new Graphics(), "A", a);
            check(queued.size() == 1 && ((PipState) queued.get(0)).x1() < 0, "RETENTION first open has no invented predecessor");
            CpmExtractionBodyProbe.consumerFrame(loader, () -> prepareRaw(prepare, dispatcher, queued.get(0), late), CpmExtractionBodyProbe::consumerSubmitted);
            CpmExtractionBodyProbe.signalConsumers(); queued.clear(); materials.clear();
            renderer.renderGallery(new Graphics(), "A", a);
            check(queued.size() == 1 && ((PipState) queued.get(0)).x0() >= 0, "RETENTION A own complete visible request");
            CpmExtractionBodyProbe.consumerFrame(loader, () -> prepareRaw(prepare, dispatcher, queued.get(0), late), CpmExtractionBodyProbe::consumerSubmitted);
            CpmExtractionBodyProbe.signalConsumers();
            check(renderer.displayKind() == PreviewRenderer.DisplayKind.ORDINARY && materials.get(0).texture.data.equals(new Identifier("fixture:retention-A")), "RETENTION established actual own A material and display authority");
            renderer.confirmGalleryBody("A");
            queued.clear(); materials.clear();
            renderer.prepareGalleryImage("B", false, () -> true);
            set(field(owner, "session"), "nanoTime", (java.util.function.LongSupplier) () -> now[0]);
            renderer.prepareImage("image:B", 0);
            for (long tick : early ? new long[]{0} : new long[]{0, 299_000_000L}) {
                now[0] = tick; queued.clear(); materials.clear();
                renderer.renderGallery(new Graphics(), "B", b);
                check(queued.size() == 2 && ((PipState) queued.get(0)).x0() >= 0 && ((PipState) queued.get(1)).x1() < 0,
                        "RETENTION at " + tick + " must draw whole retained A plus only hidden own B, actual queued=" + queued.size());
                CpmExtractionBodyProbe.consumerFrame(loader, () -> prepareRaw(prepare, dispatcher, queued.get(0), late), CpmExtractionBodyProbe::consumerSubmitted);
                CpmExtractionBodyProbe.signalConsumers();
                check(materials.size() == 1 && materials.get(0).texture.data.equals(new Identifier("fixture:retention-A")), "RETENTION actual visible material remains exact A");
                check(renderer.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "RETENTION old A never grants B controls");
            }
            now[0] = early ? 25_000_000L : 300_000_000L; queued.clear(); materials.clear();
            renderer.renderGallery(new Graphics(), "B", b);
            check(queued.size() == (early ? 2 : 1) && ((PipState) queued.get(queued.size() - 1)).x1() < 0
                    && ((EditorPreviewSession) field(owner, "session")).imageSpinner() == !early, "RETENTION threshold spinner or early own preparation, B remains hidden");
            var hiddenB = queued.get(queued.size() - 1);
            CpmExtractionBodyProbe.consumerFrame(loader, () -> prepareRaw(prepare, dispatcher, hiddenB, late), CpmExtractionBodyProbe::consumerSubmitted);
            CpmExtractionBodyProbe.signalConsumers();
            check(materials.get(0).texture.data.equals(new Identifier("fixture:retention-B")) && renderer.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "RETENTION own B preparation material is not ACK");
            queued.clear(); materials.clear(); renderer.renderGallery(new Graphics(), "B", b);
            check(queued.size() == 1 && ((PipState) queued.get(0)).x0() >= 0, "RETENTION ready B immediately visible without minimum spinner");
            CpmExtractionBodyProbe.consumerFrame(loader, () -> prepareRaw(prepare, dispatcher, queued.get(0), late), CpmExtractionBodyProbe::consumerSubmitted);
            CpmExtractionBodyProbe.signalConsumers();
            check(materials.get(0).texture.data.equals(new Identifier("fixture:retention-B")) && renderer.displayKind() == PreviewRenderer.DisplayKind.ORDINARY, "RETENTION only visible own B consumer grants B controls");
            renderer.confirmGalleryBody("B");
            check(((Map<?, ?>) field(owner, "gallerySlots")).size() == 1, "RETENTION bounded displayed plus latest desired, A retired at B ACK");
            renderer.prepareGalleryImage("C", false, () -> true);
            set(field(owner, "session"), "nanoTime", (java.util.function.LongSupplier) () -> now[0]);
            renderer.prepareImage("image:C", now[0]);
            queued.clear();
            enqueue = graphics -> { queued.add(call(replace, null, graphics, new VanillaState())); closeTarget(owner); };
            renderer.renderGallery(new Graphics(), "C", a);
            check(queued.size() == 1 && renderer.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED,
                    "RETENTION reentrant close during old-image enqueue cannot resume successor or revive authority");
            System.out.println("PASS RETENTION actual source-scoped native A/material/ACK -> prethreshold A and hidden B -> own B/material/visible ACK; early=" + early + "; synthetic GPU terminals");
        } finally {
            closeTarget(owner); closeTarget(dispatcher); CpmExtractionBodyProbe.signalConsumers();
            CpmExtractionBodyProbe.resetConsumerControl(); materials.clear(); enqueue = null; Client.current = null;
        }
    }

    private static void submissionImageConsumer() throws Exception {
        String prefix = "com.naocraftlab.skins.compat.client.identifier.submission.";
        for (boolean rejected : new boolean[]{false, true}) {
            var loader = new Loader(prefix, prefix + "SubmissionPreviewRenderer", prefix + "SimplePreviewRenderer", prefix + "BakedPreviewRenderer", false);
            loader.live = true;
            loader.physical = true;
            loader.types.put("com/mojang/blaze3d/textures/GpuTexture", CpmExtractionBodyProbe.LegacyTexture.class);
            Client.current = new Client();
            Client.current.level = new CpmPreviewScopeProbe.Level();
            Client.current.player = new CpmPreviewScopeProbe.Avatar(Client.current.level);
            var ownerType = loader.loadClass(prefix + "SubmissionPreviewRenderer");
            var owner = submissionOwner(loader, ownerType);
            @SuppressWarnings("unchecked") var renderer = (PreviewRenderer<Graphics>) owner;
            var liveType = loader.loadClass(prefix + "LivePreviewRenderer");
            var dispatcher = liveType.getConstructor(Buffers.class).newInstance(new Buffers());
            var prepare = java.util.Arrays.stream(liveType.getMethods()).filter(method -> method.getName().equals("prepare") && !method.isBridge()).findFirst().orElseThrow();
            var replace = loader.loadClass(prefix + "LivePreviewSubmission").getMethod("replace", Object.class, PipState.class);
            var queued = new java.util.ArrayList<Object>();
            enqueue = graphics -> queued.add(call(replace, null, graphics, new VanillaState()));
            CpmExtractionBodyProbe.cached = false;
            CpmExtractionBodyProbe.queuedMaterials().clear();
            CpmExtractionBodyProbe.submissionBoundaries(() -> {}, () -> {});
            CpmExtractionBodyProbe.consumerControl("normal");
            var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("fixture:submission-image-own", 64, 64),
                    SkinModel.CLASSIC, java.util.Optional.empty(), PreviewRenderer.CapeMode.OFF, OuterLayerVisibility.allVisible());
            var request = new PreviewRenderer.PreviewRequest(appearance, 0, 0, 160, 180, 0, 0, 1, PreviewRenderer.PreviewIntent.EDITOR_DRAFT);
            renderer.prepareBody("fixture:submission-image", false);
            renderer.prepareImage("fixture:submission-image", System.nanoTime());
            var session = (EditorPreviewSession) field(owner, "session");
            Throwable failure = null;
            boolean[] early = {false};
            try {
                renderer.render(new Graphics(), request);
                check(queued.size() == 1 && ((PipState) queued.get(0)).x1() < 0, "SUBMISSION_IMAGE actual ordinary producer stays hidden");
                try {
                    CpmExtractionBodyProbe.consumerFrame(loader, () -> {
                        prepareRaw(prepare, dispatcher, queued.get(0), false);
                        early[0] = session.imageVisible();
                    }, () -> {
                        check(!CpmExtractionBodyProbe.queuedMaterials().isEmpty(), "SUBMISSION_IMAGE actual own native material reaches consumer");
                        CpmExtractionBodyProbe.consumerSubmitted();
                        if (rejected) throw new IllegalStateException("synthetic submission image consumer failure");
                    });
                } catch (RuntimeException | Error thrown) { failure = thrown; }
                CpmExtractionBodyProbe.signalConsumers();
                check(!early[0], "SUBMISSION_IMAGE_PREPARATION_IS_NOT_CONSUMPTION");
                check(session.imageVisible() == !rejected && renderer.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED,
                        "SUBMISSION_IMAGE own consumed preparation is separate from visible controls");
                check(!rejected || failure instanceof IllegalStateException && failure.getSuppressed().length == 0,
                        "SUBMISSION_IMAGE consumer failure settles without teardown contamination");
                if (!rejected) {
                    queued.clear();
                    renderer.render(new Graphics(), request);
                    check(((PipState) queued.get(0)).x0() >= 0, "SUBMISSION_IMAGE own complete image now visible");
                    CpmExtractionBodyProbe.consumerFrame(loader, () -> prepareRaw(prepare, dispatcher, queued.get(0), false), CpmExtractionBodyProbe::consumerSubmitted);
                    check(renderer.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "SUBMISSION_IMAGE queued visible pass is not ACK");
                    CpmExtractionBodyProbe.signalConsumers();
                    check(renderer.displayKind() == PreviewRenderer.DisplayKind.ORDINARY, "SUBMISSION_IMAGE visible own consumer admits controls");
                }
                System.out.println("PASS SUBMISSION_IMAGE hidden source -> own consumer/fence -> visible own consumer/control rejected=" + rejected);
            } finally {
                closeTarget(owner);
                ((AutoCloseable) dispatcher).close();
                CpmExtractionBodyProbe.resetConsumerControl();
                CpmExtractionBodyProbe.queuedMaterials().clear();
                Client.current = null;
            }
        }
    }

    static void submissionBodyProducer(PreviewRenderer.PreviewAppearance appearance, Runnable binding, Runnable cleanupFailure,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture gallery) throws Exception {
        String prefix = "com.naocraftlab.skins.compat.client.identifier.submission.";
        if (Boolean.getBoolean("nclskins.sourceReplacement")) { submissionReplacement(gallery); return; }
        for (String scenario : loadingCanonical ? java.util.List.of("normal","shared-backend","owned-close","late-A-error","native-reentrant-close","consumer-failure","fence-failure","cold-owned") : java.util.List.of("ordinary","cpm","layer-failure","feature-failure","cleanup-failure","blit-failure","normal","shared-backend","owned-close","late-A-error","native-reentrant-close","consumer-failure","fence-failure","cold-owned")) {
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
                        var initialA=subject.preview();
                        var initialInput=subject.runtime.galleryAccessoryAdmission(initialA);
                        String initialSource=subject.runtime.previewBodySource(initialA);
                        drawSubmissionHost(drawHost,host,subject);
                        check(queued.size()==1,"actual frozen host enqueues A");
                        CpmExtractionBodyProbe.consumerControl("normal");
                        CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),CpmExtractionBodyProbe::consumerSubmitted);
                        check(subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"hidden A preparation cannot grant common display");
                        CpmExtractionBodyProbe.signalConsumers();
                        check(((PipState)queued.get(0)).x1()<0,"actual initial A producer stays hidden");
                        queued.clear();CpmExtractionBodyProbe.queuedMaterials().clear();
                        drawSubmissionHost(drawHost,host,subject);
                        check(queued.size()==1 && ((PipState)queued.get(0)).x0()>=0,"A own consumed preparation permits visible successor pass");
                        CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),CpmExtractionBodyProbe::consumerSubmitted);
                        check(subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"A visible preparation is not consumer completion");
                        CpmExtractionBodyProbe.signalConsumers();
                        check(subject.displayed()==PreviewRenderer.DisplayKind.ORDINARY,"actual caller -> runtime -> selected Submission -> native PIP pass -> Gallery display");
                        var initialRequest=stateRequest(queued.get(0));
                        com.naocraftlab.skins.client.PreviewAccessories accessoryA;
                        try(var decoded=CpmBehaviorProbe.nativeAccessoryObservation(submissionSource(owner,initialSource))) {
                            accessoryA=renderer.galleryAccessories(initialSource,initialRequest,initialInput);
                            check(accessoryA.canInspect() && accessoryA.present(),"A native matching decoded profile accessories positive");
                        }
                        CpmExtractionBodyProbe.signalConsumers();CpmExtractionBodyProbe.queuedMaterials().clear();queued.clear();
                        drawSubmissionHost(drawHost,host,subject);
                        var observedA=subject.runtime.galleryPreviewCandidate().orElseThrow();
                        if(!subject.runtime.previewBodySource(observedA).equals(initialSource)) {
                            check(queued.size()==2 && ((PipState)queued.get(0)).x0()>=0 && ((PipState)queued.get(1)).x1()<0,"decoded accessory capability retains A with separate exact-source hidden successor");
                            CpmExtractionBodyProbe.consumerFrame(loader,() -> {for(var state:queued)prepareRaw(prepare,dispatcher,state,false);},CpmExtractionBodyProbe::consumerSubmitted);
                            CpmExtractionBodyProbe.signalConsumers();queued.clear();CpmExtractionBodyProbe.queuedMaterials().clear();
                            drawSubmissionHost(drawHost,host,subject);
                            CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),CpmExtractionBodyProbe::consumerSubmitted);
                            CpmExtractionBodyProbe.signalConsumers();queued.clear();CpmExtractionBodyProbe.queuedMaterials().clear();
                            drawSubmissionHost(drawHost,host,subject);
                        }
                        var ownedA=subject.preview();
                        var inputA=subject.runtime.galleryAccessoryAdmission(ownedA);
                        String sourceA=subject.runtime.previewBodySource(ownedA);
                        Object oldA=queued.get(0);queued.clear();
                        var requestA=stateRequest(oldA);
                        check(contextCurrent(oldA),"queued A is current before B selection");
                        var beforeSession=(EditorPreviewSession)field(owner,"session");
                        var paintedA=rootLeft();
                        subject.selectC();
                        check(field(((Map<?,?>)field(owner,"gallerySlots")).get(beforeSession),"session")==beforeSession,"selection alone preserves native A slot session identity");
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
                        check(queued.size()==2 && ((PipState)queued.get(0)).x0()>=0 && ((PipState)queued.get(1)).x1()<0,"actual successor root retains visible A and prepares separate own hidden B");
                        if(scenario.equals("cold-owned"))check(!paintedA.isEmpty() && rootLeft().equals(paintedA),"actual root preserves predecessor controls while candidate image is hidden");
                        Object stateA=queued.get(0), stateB=queued.get(1);
                        check(stateRequest(stateA).appearance().skin().equals(requestA.appearance().skin())
                                && stateRequest(stateA).appearance().model()==requestA.appearance().model()
                                && stateRequest(stateA).appearance().outerLayerVisibility().equals(requestA.appearance().outerLayerVisibility())
                                && !stateRequest(stateB).appearance().equals(stateRequest(stateA).appearance()),"actual host owns distinct A/B source/model/mask");
                        var retainedSession=(EditorPreviewSession)field(((Map<?,?>)field(owner,"gallerySlots")).get(beforeSession),"session");
                        check(retainedSession==beforeSession && retainedSession.body().current().getAsBoolean()
                                && ((EditorPreviewSession.Gallery)field(owner,"gallery")).retains(retainedSession),"own completed A session remains internally retained");
                        check(contextCurrent(stateA) && contextCurrent(oldA),"queued A remains current after B selection/preparation");
                        var targetField=stateA.getClass().getDeclaredField("target");targetField.setAccessible(true);
                        check(targetField.get(stateA)!=targetField.get(stateB),"same-size live sources own separate physical target carriers");
                        if(scenario.equals("shared-backend"))targetField.set(stateB,targetField.get(stateA));
                        Runnable lateAFailure = () -> {
                          if(scenario.equals("late-A-error")) try {
                            var sink=oldA.getClass().getDeclaredMethod("failureSink");sink.setAccessible(true);Object callback=sink.invoke(oldA);
                            var fail=callback.getClass().getInterfaces()[0].getMethod("onFailure",RuntimeException.class);fail.setAccessible(true);fail.invoke(callback,new IllegalStateException("synthetic late A failure"));
                            var slots=ownerType.getDeclaredField("gallerySlots");slots.setAccessible(true);Object slot=((Map<?,?>)slots.get(owner)).get(((EditorPreviewSession.Gallery)field(owner,"gallery")).session(sourceB));
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
                        if(failure!=null && !scenario.equals("shared-backend")) {
                            check(subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED && subject.preview().bodySource().equals(ownedA.bodySource()),"failed hidden B consumer preserves predecessor and cannot ACK");
                        }
                        if(!java.util.Set.of("native-reentrant-close","shared-backend","consumer-failure","fence-failure").contains(scenario)) {
                            check(subject.preview().bodySource().equals(ownedA.bodySource()) && subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,
                                    "hidden B consumer cannot commit left projection or visible display");
                            var retainedMaterial=materials.get(0);var candidateMaterial=materials.get(1);
                            CpmExtractionBodyProbe.signalConsumers();
                            CpmExtractionBodyProbe.consumerControl("normal");
                            materials.clear();queued.clear();drawSubmissionHost(drawHost,host,subject);
                            check(queued.size()==1 && ((PipState)queued.get(0)).x0()>=0,"own completed hidden B permits visible own pass");
                            CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),CpmExtractionBodyProbe::consumerSubmitted);
                            check(subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"visible B prepare cannot ACK before own consumer fence");
                            CpmExtractionBodyProbe.signalConsumers();
                            check(retainedMaterial.texture.closes==1 && !candidateMaterial.texture.closed,"visible B commits and retires retained A only after actual consumer");
                            materials.clear();materials.add(retainedMaterial);materials.add(candidateMaterial);
                            check(subject.displayed()==PreviewRenderer.DisplayKind.ORDINARY && subject.preview().bodySource().equals(candidate.bodySource()),"only completed visible B pass commits its own left projection");
                            if(scenario.equals("owned-close")) {
                                materials.clear();queued.clear();drawSubmissionHost(drawHost,host,subject);
                                boolean[] attempted={false};
                                CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),() -> {
                                    CpmExtractionBodyProbe.consumerSubmitted();
                                    renderer.observeBody(status -> {
                                        if(!attempted[0]) { attempted[0]=true;check(!renderer.prepareGalleryBody("fixture:forbidden-close-reentry",false,() -> true),"logical owner close rejects reentrant Gallery admission before publication"); }
                                    });
                                    closeTarget(owner);
                                    check(attempted[0],"actual close exercises synchronous body observer reentry after visible B completion");
                                    check(materials.size()==1 && !materials.get(0).texture.closed,"reentrant close preserves actual in-flight own B consumer material");
                                });
                            } else try(var decoded=CpmBehaviorProbe.nativeAccessoryObservation(submissionSource(owner,sourceB))) {
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
                            check(queued.size()==2 && ((PipState)queued.get(0)).x0()>=0 && ((PipState)queued.get(1)).x1()<0,"actual returned B-C-B root retains predecessor plus separate hidden candidate");
                            Object returnedState=queued.get(1);
                            check(stateRequest(returnedState).appearance().skin().equals(requestA.appearance().skin())
                                    && stateRequest(returnedState).appearance().model()==requestA.appearance().model(),"returned producer has original owned bytes/model handle");
                            CpmExtractionBodyProbe.consumerControl("normal");
                            CpmExtractionBodyProbe.consumerFrame(loader,() -> {
                                for(var state:queued)prepareRaw(prepare,dispatcher,state,false);
                            },() -> { CpmExtractionBodyProbe.consumerSubmitted();check(materials.size()==2 && materials.get(1).texture.data.equals(stateRequest(returnedState).appearance()) && materials.get(0).texture.data.equals(stateRequest(queued.get(0)).appearance()),"returned B-C-B separate retained and own hidden material consumers"); });
                            check(subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"returned hidden own material is not common ACK");
                            CpmExtractionBodyProbe.signalConsumers();materials.clear();queued.clear();
                            drawSubmissionHost(drawHost,host,subject);
                            check(queued.size()==1 && ((PipState)queued.get(0)).x0()>=0,"returned own completed preparation reveals its actual native pass");
                            CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),CpmExtractionBodyProbe::consumerSubmitted);
                            check(subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"returned visible prepare remains provisional before own fence");
                            CpmExtractionBodyProbe.signalConsumers();
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
                            check(subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"reopened hidden native pass cannot publish display");
                            CpmExtractionBodyProbe.signalConsumers();materials.clear();queued.clear();
                            drawSubmissionHost(drawHost,nextHost,subject);
                            check(queued.size()==1 && ((PipState)queued.get(0)).x0()>=0,"reopened own consumed preparation reveals native pass");
                            CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),CpmExtractionBodyProbe::consumerSubmitted);
                            CpmExtractionBodyProbe.signalConsumers();
                            check(subject.displayed()==PreviewRenderer.DisplayKind.ORDINARY,"reopened native pass positively publishes new source");
                            call(hostType.getDeclaredMethod("releasePreviewAccessories"),nextHost);call(hostType.getDeclaredMethod("releasePreviewBody"),nextHost);
                            closeTarget(reopenedOwner[0]);
                        }
                        int pendingStart=materials.size();
                        boolean finalConsumer=!java.util.Set.of("owned-close","native-reentrant-close","shared-backend","cold-owned","consumer-failure","fence-failure").contains(scenario);
                        if(finalConsumer) {
                            queued.clear();drawSubmissionHost(drawHost,host,subject);
                            CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),CpmExtractionBodyProbe::consumerSubmitted);
                        }
                        var pendingMaterials=(finalConsumer ? materials.subList(pendingStart,materials.size()) : materials).stream().filter(m -> !m.texture.closed).toList();
                        check(scenario.equals("cold-owned") || !pendingMaterials.isEmpty(),"lifetime close oracle has actual unretired consumer material");
                        closeTarget(owner);
                        check(pendingMaterials.stream().noneMatch(m -> m.texture.closed),"owner close before fence signal retains actual live materials");
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
            check(target.prepareGalleryImage(sourceA,false,subject.runtime.galleryPreviewAdmission(a)),"N2 submission A admitted");
            target.renderGallery(new Graphics(),sourceA,requestA);
            CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),CpmExtractionBodyProbe::consumerSubmitted);
            check(!((EditorPreviewSession)field(owner,"session")).imageVisible() && subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"N2 submission hidden A waits for its actual consumer fence");
            CpmExtractionBodyProbe.signalConsumers();
            check(((EditorPreviewSession)field(owner,"session")).imageVisible() && subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"N2 submission hidden A image is ready but not displayed");
            queued.clear();target.renderGallery(new Graphics(),sourceA,requestA);
            CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),CpmExtractionBodyProbe::consumerSubmitted);
            CpmExtractionBodyProbe.signalConsumers();
            check(target.body().current().getAsBoolean(),"N2 submission actual A native material");
            check(subject.displayed()==PreviewRenderer.DisplayKind.ORDINARY,"N2 submission A visible native consumer is acknowledged before replacement");
            queued.clear(); subject.selectC();
            var b = subject.runtime.galleryPreviewCandidate().orElseThrow();
            String sourceB = subject.runtime.previewBodySource(b);
            var requestB = subject.request();
            target.observeBody(subject.observeBody(b,target));
            check(target.prepareGalleryImage(sourceB,false,subject.runtime.galleryPreviewAdmission(b)),"N2 submission B admitted");
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
            check(!((EditorPreviewSession)field(owner,"session")).imageVisible() && subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"N2 submission hidden B waits for its own consumer fence");
            CpmExtractionBodyProbe.signalConsumers();
            check(((EditorPreviewSession)field(owner,"session")).imageVisible() && subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"N2 submission own hidden B readiness does not acknowledge display");
            queued.clear();target.renderGallery(new Graphics(),sourceB,requestB);
            CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),CpmExtractionBodyProbe::consumerSubmitted);
            CpmExtractionBodyProbe.signalConsumers();
            check(target.body().current().getAsBoolean() && subject.displayed()==PreviewRenderer.DisplayKind.ORDINARY,"N2 submission own B actual native material after A failure");
            Object own = failureSink.invoke(queued.get(0)); fail.invoke(own,new IllegalStateException("synthetic own submission B failure"));
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
                completeSubmissionImage(loader,prepare,dispatcher,drawHost,host,subject,queued);
                check(subject.displayed()==PreviewRenderer.DisplayKind.ORDINARY,"worldless source A obtains actual selected baked pass");
                CpmExtractionBodyProbe.signalConsumers();materials.clear();queued.clear();
                call(hostType.getDeclaredMethod("releasePreviewBody"),host);
                subject.offlineCapeCapability(true);
                call(sync,host,submissionMainView(subject.runtime.view(854,480,0,0)));
                subject.drainWorkers(); call(sync,host,submissionMainView(subject.runtime.view(854,480,0,0))); subject.drainWorkers();
                drawSubmissionHost(drawHost,host,subject);
                completeSubmissionImage(loader,prepare,dispatcher,drawHost,host,subject,queued);
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
                check(queued.size()==2 && ((PipState)queued.get(0)).x0()>=0 && ((PipState)queued.get(1)).x1()<0,"COMMON-02 submission retains A plus separate hidden same-ID successor");
                completeSubmissionImage(loader,prepare,dispatcher,drawHost,host,subject,queued);
                check(!subject.preview().capeHasElytra(),"COMMON-02 submission actual same-ID successor pass");
                CpmExtractionBodyProbe.signalConsumers();materials.clear();queued.clear();
                System.out.println("PASS COMMON-02 submission actual host same-ID real account capability replacement");
                drawSubmissionHost(drawHost,host,subject);Object retainedState=queued.get(0);queued.clear();
                subject.selectC();var hidden=subject.runtime.galleryPreviewCandidate().orElseThrow();Object keyB=key.invoke(null,hidden);
                check(skins.handle(keyB).isEmpty() && !subject.runtime.view(854,480,0,0).previews().contains(hidden),"worldless selected hidden B is cold, not borrowed A");
                call(sync,host,submissionMainView(subject.runtime.view(854,480,0,0)));subject.drainWorkers();call(sync,host,submissionMainView(subject.runtime.view(854,480,0,0)));
                var handleB=skins.handle(keyB).orElseThrow();
                check(!handleA.equals(handleB) && hidden.variant()==com.naocraftlab.skins.core.model.SkinVariant.SLIM
                        && java.util.Arrays.equals(subject.ownedBytes(hidden),registry.registered.get(handleB)),"worldless B own imported bytes/model/handle through cache relookup");
                drawSubmissionHost(drawHost,host,subject);check(queued.size()==2 && ((PipState)queued.get(0)).x0()>=0 && ((PipState)queued.get(1)).x1()<0,"worldless actual host retains visible A plus own hidden B");
                var target=queued.get(0).getClass().getMethod("target");check(target.invoke(retainedState)!=target.invoke(queued.get(1)),"selected worldless slots own separate baked targets");
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

    static void submissionCpmImage(com.naocraftlab.skins.runtime.GalleryNativePassFixture original) throws Exception {
        if (Boolean.getBoolean("nclskins.cpm.submissionCapture")) {
            try {
                Class.forName("com.naocraftlab.skins.buildlogic.probe.SubmissionCaptureBackend")
                        .getMethod("run", com.naocraftlab.skins.runtime.GalleryNativePassFixture.class).invoke(null, original);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception exception) throw exception;
                if (failure.getCause() instanceof Error error) throw error;
                throw failure;
            }
            return;
        }
        if (Boolean.getBoolean("nclskins.cpm.submissionCaptureDeferred")) return;
        String prefix="com.naocraftlab.skins.compat.client.identifier.submission.";
        if(CpmBakedDisplayProbe.class.getClassLoader().getResource(prefix.replace('.','/')+"SubmissionPreviewRenderer.class")==null)return;
        var loader=new Loader(prefix,prefix+"SubmissionPreviewRenderer",prefix+"SimplePreviewRenderer",prefix+"BakedPreviewRenderer",false);
        loader.live=true;loader.physical=true;loader.types.remove("com/naocraftlab/skins/runtime/CapeProjection");
        loader.types.put("com/mojang/blaze3d/textures/GpuTexture",CpmExtractionBodyProbe.LegacyTexture.class);
        Client.current=new Client();Client.current.level=new CpmPreviewScopeProbe.Level();Client.current.player=new CpmPreviewScopeProbe.Avatar(Client.current.level);
        var type=loader.loadClass(prefix+"SubmissionPreviewRenderer");Object owner=submissionOwner(loader,type);
        var diagnostics=type.getDeclaredField("diagnostics");diagnostics.setAccessible(true);
        diagnostics.set(owner,(com.naocraftlab.skins.diagnostics.DiagnosticSink)(event,details) -> details.get().failure().ifPresent(failure -> failure.asThrowable().printStackTrace(System.out)));
        var dispatcherType=loader.loadClass(prefix+"LivePreviewRenderer");Object dispatcher=dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
        var prepare=java.util.Arrays.stream(dispatcherType.getMethods()).filter(m -> m.getName().equals("prepare")&&!m.isBridge()).findFirst().orElseThrow();
        var replace=loader.loadClass(prefix+"LivePreviewSubmission").getMethod("replace",Object.class,PipState.class);
        var queued=new java.util.ArrayList<Object>();enqueue=graphics -> queued.add(call(replace,null,graphics,new VanillaState()));
        var definitions=new java.util.ArrayList<AutoCloseable>();
        try(var subject=original.freshOwnedCpm();var profiles=CpmBehaviorProbe.nativeProfileScope()) {
            var hostType=CpmPreviewScreenSlice.loadNative(prefix+"NclSkinsScreen",type);Object host=hostType.getConstructor().newInstance();
            var registry=new CpmOwnedPreviewDispatchProbe.Registry();
            try(var skins=new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.PLAYER_SKIN,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
                var capes=new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.IMAGE,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
                for(var field:hostType.getDeclaredFields()) {
                    Object value=switch(field.getName()) {
                        case "runtime" -> subject.runtime;case "editorRenderer" -> owner;case "skinTextures" -> skins;case "capeTextures" -> capes;case "textureRegistry" -> registry;
                        default -> field.getType()==Map.class?new HashMap<>():null;
                    };if(value!=null)field.set(host,value);
                }
                var sync=hostType.getDeclaredMethod("synchronizePreviewAssets",com.naocraftlab.skins.runtime.ViewSpec.class);
                var drawHost=hostType.getDeclaredMethod("renderPreviews",Graphics.class,com.naocraftlab.skins.runtime.ViewSpec.class);
                var requestMethod=hostType.getDeclaredMethod("galleryRequest",com.naocraftlab.skins.runtime.ViewSpec.Preview.class);requestMethod.setAccessible(true);
                call(sync,host,subject.runtime.view(854,480,0,0));subject.drainWorkers();call(sync,host,subject.runtime.view(854,480,0,0));subject.drainWorkers();
                var desired=subject.runtime.galleryPreviewCandidate().orElseThrow();String source=subject.runtime.previewBodySource(desired);
                check(desired.bodySource().orElseThrow().cpmExpected(),"SUBMISSION_CPM actual owned carrier requires CPM body");
                @SuppressWarnings("unchecked") var request=(java.util.Optional<PreviewRenderer.PreviewRequest>)requestMethod.invoke(host,desired);
                var own=request.orElseThrow();
                @SuppressWarnings("unchecked") var production=(PreviewRenderer<Graphics>)owner;
                var registration=subject.runtime.observePreviewBody(desired,production::displayKind,() -> production.confirmGalleryBody(source));
                production.observeBody(registration);
                check(production.prepareGalleryBody(source,true,subject.runtime.galleryPreviewAdmission(desired)),"SUBMISSION_CPM real common registration prepares own exact native slot");
                var session=(EditorPreviewSession)field(owner,"session");
                check(!session.imageVisible() && subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"SUBMISSION_CPM own preparation is not visible display");
                check(java.util.Arrays.equals(registry.registered.get(own.appearance().skin()),subject.ownedBytes(desired)),"SUBMISSION_CPM actual cache/relookup own imported pixels");
                Object slot=((Map<?,?>)field(owner,"gallerySlots")).get(((EditorPreviewSession.Gallery)field(owner,"gallery")).session(source));Object profile=field(slot,"profile");
                definitions.add(CpmBehaviorProbe.nativeOwnedBody(subject.ownedBytes(desired),CpmBehaviorProbe.nativeProfileId(profile),own.appearance()));
                CpmExtractionBodyProbe.cached=false;CpmExtractionBodyProbe.queuedMaterials().clear();
                drawSubmissionHost(drawHost,host,subject);
                check(queued.size()==1 && ((PipState)queued.get(0)).x1()<0,"SUBMISSION_CPM own resolved producer remains hidden before material consumption");
                CpmExtractionBodyProbe.consumerControl("normal");
                CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),() -> {
                    CpmExtractionBodyProbe.consumerSubmitted();
                    check(CpmExtractionBodyProbe.queuedMaterials().size()==1 && CpmExtractionBodyProbe.queuedMaterials().get(0).texture.data.equals(own.appearance()),"SUBMISSION_CPM own primary binding reaches native material consumer");
                });
                check(!session.imageVisible() && subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"SUBMISSION_CPM preparation is neither visible nor common ACK");
                CpmExtractionBodyProbe.signalConsumers();
                check(session.imageVisible() && subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"SUBMISSION_CPM hidden own consumer fence permits image but not controls");
                queued.clear();CpmExtractionBodyProbe.queuedMaterials().clear();drawSubmissionHost(drawHost,host,subject);
                check(queued.size()==1 && ((PipState)queued.get(0)).x0()>=0,"SUBMISSION_CPM own hidden material progresses to visible native pass");
                CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),CpmExtractionBodyProbe::consumerSubmitted);
                check(subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"SUBMISSION_CPM visible submission is not ACK");
                CpmExtractionBodyProbe.signalConsumers();
                check(subject.displayed()==PreviewRenderer.DisplayKind.CPM && subject.preview().bodySource().equals(desired.bodySource()),"SUBMISSION_CPM actual visible own consumer grants common CPM ACK");
                subject.layersVisible(false);
                call(hostType.getDeclaredMethod("releasePreviewAccessories"),host);call(hostType.getDeclaredMethod("releasePreviewBody"),host);
                System.out.println("PASS SUBMISSION_CPM actual owned carrier/cache/pinned decode -> hidden own primary binding/material/consumer/fence -> visible own consumer -> common CPM ACK");
            }
        } finally {
            closeTarget(owner);closeTarget(dispatcher);CpmExtractionBodyProbe.signalConsumers();CpmExtractionBodyProbe.resetConsumerControl();
            for(int i=definitions.size()-1;i>=0;i--)definitions.get(i).close();
            CpmExtractionBodyProbe.submissionBoundaries(() -> {},() -> {});CpmExtractionBodyProbe.queuedMaterials().clear();enqueue=null;Client.current=null;
        }
    }

    private static void completeSubmissionImage(ClassLoader loader, java.lang.reflect.Method prepare, Object dispatcher,
            java.lang.reflect.Method drawHost, Object host, com.naocraftlab.skins.runtime.GalleryNativePassFixture subject,
            java.util.List<Object> queued) throws Exception {
        check(!queued.isEmpty() && ((PipState)queued.get(queued.size()-1)).x1()<0,"compiled submission host begins own exact-source hidden image after optional retained A");
        CpmExtractionBodyProbe.consumerControl("normal");
        CpmExtractionBodyProbe.consumerFrame(loader,() -> { for(var state:queued)prepareRaw(prepare,dispatcher,state,false); },CpmExtractionBodyProbe::consumerSubmitted);
        check(subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"hidden own submission material cannot common ACK");
        CpmExtractionBodyProbe.signalConsumers();
        queued.clear();CpmExtractionBodyProbe.queuedMaterials().clear();drawSubmissionHost(drawHost,host,subject);
        check(queued.size()==1 && ((PipState)queued.get(0)).x0()>=0,"own consumed submission preparation reveals own visible pass");
        CpmExtractionBodyProbe.consumerFrame(loader,() -> prepareRaw(prepare,dispatcher,queued.get(0),false),CpmExtractionBodyProbe::consumerSubmitted);
        check(subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"visible submission prepare is not common ACK before consumer fence");
        CpmExtractionBodyProbe.signalConsumers();
        check(subject.displayed()==PreviewRenderer.DisplayKind.ORDINARY,"own visible submission consumer grants common ACK");
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
        Object slot=((Map<?,?>)slots.get(owner)).get(((EditorPreviewSession.Gallery)field(owner,"gallery")).session(key));
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

    static final class SelectedFallback implements AutoCloseable {
        private final Loader loader;
        private final boolean extracting;
        private final Object baked, dispatcher;
        private final java.lang.reflect.Method render, replace, prepare;
        private final Client previousClient = Client.current;
        private final Client client = new Client();
        private final java.util.List<Object> queued = new java.util.ArrayList<>();
        int invocations, completions, primaryDraws, materialPasses;
        float scale;
        String source;
        boolean failDraw;

        SelectedFallback() throws Exception {
            this(true);
        }

        SelectedFallback(boolean extracting) throws Exception {
            this.extracting=extracting;
            String prefix = "com.naocraftlab.skins.compat.client.identifier."+(extracting?"extraction.":"submission.");
            loader = new Loader(prefix, prefix + (extracting?"AvatarRenderStatePreviewRenderer":"SubmissionPreviewRenderer"), prefix + (extracting?"AvatarPipPreviewRenderer":"SimplePreviewRenderer"),
                    prefix + (extracting?"NclBakedPlayerRenderer":"BakedPreviewRenderer"), extracting);
            loader.physical = true;
            loader.rawSdk = true;
            loader.types.put("com/mojang/blaze3d/textures/GpuTexture", CpmExtractionBodyProbe.LegacyTexture.class);
            Client.current = client;
            baked = create(loader.loadClass(loader.baked));
            dispatcher = extracting?create(loader.loadClass(loader.dispatcher)):loader.loadClass(loader.dispatcher).getConstructor(Buffers.class).newInstance(new Buffers());
            render = baked.getClass().getDeclaredMethod("render", Graphics.class, PreviewRenderer.PreviewRequest.class, PreviewModelFrames.Completion.class);
            render.setAccessible(true);
            replace = loader.loadClass(prefix + (extracting?"NclBakedPlayerSubmission":"BakedPreviewSubmission")).getMethod("replace", Object.class, PipState.class);
            prepare = java.util.Arrays.stream(dispatcher.getClass().getDeclaredMethods())
                    .filter(method -> method.getName().equals("prepare") && !method.isBridge()).findFirst().orElseThrow();
        }

        void render(PreviewRenderer.PreviewRequest request, PreviewModelFrames.Completion completion) {
            invocations++;
            enqueue = owner -> queued.add(call(replace, null, owner, new VanillaState()));
            call(render, baked, new Graphics(), request, new PreviewModelFrames.Completion() {
                public boolean current() { return completion.current(); }
                public boolean currentInput(String location) { return completion.currentInput(location); }
                public boolean needsOwnInputPreparation() { return completion.needsOwnInputPreparation(); }
                public boolean reusesConfirmedOutput() { return completion.reusesConfirmedOutput(); }
                public boolean requiresConsumption() { return completion.requiresConsumption(); }
                public void accept(PreviewModelFrames.Body body) {
                    if (current() && body.status() == PreviewRenderer.BodyStatus.ORDINARY && body.current().getAsBoolean()) completions++;
                    completion.accept(body);
                }
            });
        }

        int queued() { return queued.size(); }

        void menu(com.naocraftlab.skins.runtime.ClientRuntime runtime, boolean pause) throws Exception {
            menu(runtime, pause, true, true);
        }

        void menu(com.naocraftlab.skins.runtime.ClientRuntime runtime, boolean pause,
                boolean anchor, boolean expectedQueue) throws Exception {
            MenuRuntime.current = runtime;
            String prefix = loader.prefix;
            loader.types.put("net/minecraft/client/gui/screens/Screen", MenuScreen.class);
            loader.types.put("net/minecraft/client/gui/screens/TitleScreen", TitleMenu.class);
            loader.types.put("net/minecraft/client/gui/screens/PauseScreen", PauseMenu.class);
            loader.types.put("net/minecraft/client/gui/components/AbstractWidget", MenuWidget.class);
            loader.types.put("net/minecraft/client/gui/components/events/GuiEventListener", MenuWidget.class);
            if (extracting) loader.types.put("net/minecraft/client/gui/components/Renderable", CpmMenuPanelSlice.Renderable.class);
            loader.types.put((prefix+(extracting?"NclSkinsMenuPanel$NoChromeAction":"NclSkinsMenuPanel$State$Action")).replace('.','/'), MenuAction.class);
            loader.types.put((prefix+(extracting?"NclSkinsExtractionClientRuntime":"SubmissionClientRuntime")).replace('.','/'), MenuRuntime.class);
            Class<?> panel = loader.loadClass(prefix + "NclSkinsMenuPanel");
            MenuScreen screen = pause ? new PauseMenu() : new TitleMenu();
            screen.anchor = anchor;
            if(extracting)check((boolean)panel.getMethod("supports", MenuScreen.class).invoke(null,screen), "actual native title/pause admission");
            Class<?> type = loader.loadClass(prefix + (extracting?"NclSkinsMenuPanel$PlayerPreviewUnderlay":"NclSkinsMenuPanel$State"));
            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
            Object underlay = ((sun.misc.Unsafe)unsafeField.get(null)).allocateInstance(type);
            var action = new MenuAction();
            set(underlay,"screen",screen);set(underlay,"action",action);set(underlay,"renderer",baked);
            set(underlay,"placement",com.naocraftlab.skins.core.config.MenuPreviewPlacement.RIGHT);
            enqueue = owner -> queued.add(call(replace,null,owner,new VanillaState()));
            var extract = extracting ? CpmMenuPanelSlice.extractionCallback(type, Graphics.class)
                    : type.getDeclaredMethod("render", Graphics.class, int.class, int.class, float.class);
            extract.setAccessible(true);
            var graphics = new Graphics();
            if (extract.getParameterCount() == 1) call(extract, underlay, graphics);
            else call(extract, underlay, graphics, 0, 0, 0F);
            if (anchor) check(action.bounds.equals(java.util.Optional.of(
                    new com.naocraftlab.skins.runtime.Bounds(531,142,104,116))), "actual native menu layout and action bounds");
            check(queued.size() == (expectedQueue ? 1 : 0), "actual native menu selected baked queue admission");
            MenuRuntime.current = null;
        }

        void assertMenuAppearance(com.naocraftlab.skins.client.TextureRegistry.TextureHandle skin,
                com.naocraftlab.skins.client.SkinModel model, com.naocraftlab.skins.client.OuterLayerVisibility mask) {
            Object state = queued.get(0);
            check(stateField(state, "skinTexture").equals(new Identifier(skin.location())), "own queued menu source");
            check(stateField(state, "playerModel") == field(baked,
                    model == com.naocraftlab.skins.client.SkinModel.SLIM ? "slim" : "wide"), "own queued menu model");
            check(stateField(state, "outerLayerVisibility").equals(mask), "own queued menu mask");
        }

        java.util.List<Map<String, Object>> definitions() { return java.util.List.copyOf(loader.definitions); }

        void freezeDefinitions(java.nio.file.Path output) throws java.io.IOException {
            for (var entry : loader.boundDefinitions.entrySet()) {
                var file = output.resolve(entry.getKey().replace('.', '/') + ".class");
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.write(file, entry.getValue());
            }
        }

        void retireMenuTarget() {closeTarget(baked);}

        void consume() throws Exception {
            Client savedClient=Client.current;
            Client.current=client;
            var materials = CpmExtractionBodyProbe.queuedMaterials();
            materials.clear();
            CpmExtractionBodyProbe.cached = false;
            if (failDraw) CpmExtractionBodyProbe.resetConsumerControl();
            else CpmExtractionBodyProbe.consumerControl("normal");
            int before = draws;
            draw = () -> { if (failDraw) throw new IllegalStateException("synthetic selected fallback draw failure"); };
            pop = () -> {};
            CpmExtractionBodyProbe.bakedBoundaries(() -> {}, () -> {}, () -> {});
            try {
                CpmExtractionBodyProbe.consumerFrame(loader, () -> {
                    for (var state : queued) {
                        scale = ((PipState) state).scale();
                        prepareRaw(prepare, dispatcher, state, prepare.getParameterCount() == 4);
                        source = ((Identifier)stateField(state,"skinTexture")).value();
                    }
                }, () -> {
                    CpmExtractionBodyProbe.consumerSubmitted();
                    for (var material : materials) {
                        check(material.texture.data.equals(new Identifier(source)), "selected fallback actual own material");
                        materialPasses++;
                    }
                });
            } finally {
                primaryDraws += draws - before;
                queued.clear();
                CpmExtractionBodyProbe.signalConsumers();
                materials.clear();
                CpmExtractionBodyProbe.resetConsumerControl();
                draw = () -> {};
                enqueue = null;
                Client.current=savedClient;
            }
        }

        @Override public void close() {
            try { closeTarget(baked); closeTarget(dispatcher); CpmExtractionBodyProbe.signalConsumers(); }
            finally { MenuRuntime.current = null; if(Client.current==client)Client.current = previousClient; }
        }
    }

    private static final class Loader extends ClassLoader {
        private final String prefix, main, baked, dispatcher;
        private final Map<String, Class<?>> types = new HashMap<>();
        private boolean staticFlush;
        private boolean physical;
        private boolean live;
        private boolean interfaceDevice;
        private boolean rawSdk;
        private final java.util.List<Map<String, Object>> definitions = new java.util.ArrayList<>();
        private final Map<String, byte[]> boundDefinitions = new HashMap<>();
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
            types.put("net/minecraft/client/renderer/RenderPipelines", CpmExtractionBodyProbe.Pipelines.class);
            types.put("com/mojang/blaze3d/pipeline/RenderPipeline", CpmExtractionBodyProbe.Pipeline.class);
            types.put("com/mojang/renderpearl/api/pipeline/RenderPipeline", CpmExtractionBodyProbe.Pipeline.class);
            types.put("net/minecraft/client/gui/GuiPipeline", CpmExtractionBodyProbe.Pipeline.class);
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
            CpmExtractionBodyProbe.bindRenderPassLayout(types);
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
            if (!pip && !frame && !name.startsWith("net.neoforged.neoforge.client.gui.PictureInPictureRenderer") && !name.startsWith(prefix) && !name.startsWith("com.naocraftlab.skins.compat.client.identifier.preview.")
                    && !name.equals("com.naocraftlab.skins.compat.client.identifier.foil.CapeFoilBuffer")
                    && !name.equals("com.naocraftlab.skins.compat.client.identifier.IdentifierOuterLayerVisibilityController")) return super.loadClass(name,resolve);
            Class<?> found=findLoadedClass(name);
            if (found==null) try (var input=getParent().getResourceAsStream(name.replace('.','/')+".class")) {
                if(input==null) throw new ClassNotFoundException(name);
                byte[] original=input.readAllBytes();
                var origin = getParent().getResource(name.replace('.', '/') + ".class");
                if ((rawSdk || Boolean.getBoolean("nclskins.cpm.nativeMeasurement")) && (pip || frame)) {
                    var resources=getParent().getResources(name.replace('.','/')+".class");
                    boolean foundRaw=false;
                    while(resources.hasMoreElements()) {
                        var resource=resources.nextElement();
                        if(!resource.getProtocol().equals("jar")) continue;
                        try(var raw=resource.openStream()){original=raw.readAllBytes();}
                        origin = resource;
                        foundRaw=true;break;
                    }
                    if(!foundRaw) throw new AssertionError("Pinned raw baked caller missing: "+name);
                }
                var node=new ClassNode(); new ClassReader(original).accept(node,0);
                if(name.equals(prefix+"NclSkinsMenuPanel")) {
                    node.methods.removeIf(method -> !method.name.equals("supports") && !method.name.equals("topMainAction")
                            && !method.name.equals("anchor") && !method.name.startsWith("lambda$anchor$")
                            && !method.name.startsWith("lambda$topMainAction$") && !method.name.equals("<clinit>"));
                } else if(name.equals(prefix+"NclSkinsMenuPanel$PlayerPreviewUnderlay") || name.equals(prefix+"NclSkinsMenuPanel$State")) {
                    node.interfaces.clear();node.interfaces.add("java/lang/AutoCloseable");
                    node.methods.removeIf(method -> !java.util.Set.of("drawUnderlay","render","updateLayout","close").contains(method.name)
                            && !method.name.startsWith("lambda$updateLayout$") && !method.name.startsWith("lambda$render$"));
                } else if (name.equals(prefix + "GuiExtraction")) {
                    CpmPreviewScreenSlice.sliceGuiAdapter(node, java.util.Set.of("isVisible", "setVisible", "blit"));
                }
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
                    node.methods.removeIf(m -> !java.util.Set.of("render","body","prepareBody", "retireBody", "modelFitApplicable", "prepareModelFit", "suppressModelFit", "renderPrepared", "prepareImage", "prepareGalleryImage","observeBody","renderFallback","onLiveRenderFailure","clearAccessoryInspection","accessoryObserverFor","observeAccessories",
                            "saveGallerySlot","retireGallerySlots","selectGallerySlot", "selectGallerySession","prepareGalleryBody","confirmGalleryBody","renderGallery", "transformRetained", "renderRetained", "retainedTextures","galleryAccessories","close").contains(m.name) && !m.name.startsWith("lambda$"));
                    CpmExtractionBodyProbe.init(node);
                    var method=CpmExtractionBodyProbe.method(node,"accessories","(Lcom/naocraftlab/skins/client/PreviewRenderer$PreviewRequest;)Lcom/naocraftlab/skins/client/PreviewAccessories;");
                    method.visitFieldInsn(Opcodes.GETSTATIC,"com/naocraftlab/skins/client/PreviewRenderer$LiveReason","LIVE_UNAVAILABLE","Lcom/naocraftlab/skins/client/PreviewRenderer$LiveReason;");
                    method.visitMethodInsn(Opcodes.INVOKESTATIC,"com/naocraftlab/skins/client/PreviewAccessories","unavailable","(Lcom/naocraftlab/skins/client/PreviewRenderer$LiveReason;)Lcom/naocraftlab/skins/client/PreviewAccessories;",false);method.visitInsn(Opcodes.ARETURN);method.visitMaxs(1,2);
                    node.innerClasses.clear();
                } else if(name.equals(baked)) {
                    node.access |= Opcodes.ACC_PUBLIC;
                    node.methods.stream().filter(m -> m.name.equals("<init>")).forEach(m -> m.access = (m.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC);
                    CpmExtractionBodyProbe.init(node);
                    {
                        var ctor = node.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
                        var code = new org.objectweb.asm.tree.InsnList();
                        for (String field : baked.endsWith("AvatarPipPreviewRenderer") ? java.util.List.of("models","target","imageSession") : java.util.List.of("wide","slim","cape","elytra","target","imageSession")) {
                            var f = node.fields.stream().filter(v -> v.name.equals(field)).findFirst().orElseThrow();
                            boolean attachment = field.equals("cape") || field.equals("elytra");
                            String type = field.equals("imageSession") ? Type.getInternalName(EditorPreviewSession.class)
                                    : field.equals("target") ? prefix.replace('.','/') + (baked.endsWith("AvatarPipPreviewRenderer") ? "NclBakedPlayerTarget" : "BakedPreviewTarget") : Type.getInternalName(attachment ? SubmissionAttachments.class : field.equals("models") ? Models.class : Model.class);
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
                byte[] bytes=writer.toByteArray();
                var boundNode = new ClassNode(); new ClassReader(bytes).accept(boundNode, 0);
                definitions.add(Map.of("class", name, "origin", origin.toString(), "original", sha(original),
                        "bound", sha(bytes), "members", boundNode.methods.stream().map(m -> m.name + m.desc).toList()));
                boundDefinitions.put(name, bytes);
                System.out.println("Baked class "+name+" original="+sha(original)+" headless="+sha(bytes));found=defineClass(name,bytes,0,bytes.length);
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
    public record Identifier(String value) {
        public static Identifier parse(String value){return new Identifier(value);}
        @Override public String toString(){return value;}
    }
    public record Rectangle(int left,int top,int width,int height) {}
    public interface PipState {
        default org.joml.Matrix3x2fc pose() { return new org.joml.Matrix3x2f(); }
        int x0();int x1();int y0();int y1();float scale();Rectangle scissorArea();
        static Rectangle getBounds(int x0,int y0,int x1,int y1,Rectangle scissor) {
            try {
                String namespace = CpmBakedDisplayProbe.class.getClassLoader().getResource("net/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState.class") != null
                        ? "net.minecraft.client.renderer.state.gui.pip." : "net.minecraft.client.gui.render.state.pip.";
                Class<?> rectangle = Class.forName("net.minecraft.client.gui.navigation.ScreenRectangle");
                Object clip = scissor == null ? null : rectangle.getConstructor(int.class,int.class,int.class,int.class)
                        .newInstance(scissor.left(),scissor.top(),scissor.width(),scissor.height());
                Object bounds = Class.forName(namespace+"PictureInPictureRenderState").getMethod("getBounds",int.class,int.class,int.class,int.class,rectangle)
                        .invoke(null,x0,y0,x1,y1,clip);
                if (bounds == null) return null;
                return new Rectangle((int)rectangle.getMethod("left").invoke(bounds),(int)rectangle.getMethod("top").invoke(bounds),
                        (int)rectangle.getMethod("width").invoke(bounds),(int)rectangle.getMethod("height").invoke(bounds));
            } catch (ReflectiveOperationException failure) { throw new AssertionError("SDK bounds binding",failure); }
        }
    }
    public record MenuState(Rectangle scissorArea) implements PipState {public MenuState(){this(null);}public int x0(){return 0;}public int x1(){return 160;}public int y0(){return 0;}public int y1(){return 180;}public float scale(){return 1;}}
    public record VanillaState() implements PipState {public int x0(){return 0;}public int x1(){return 160;}public int y0(){return 0;}public int y1(){return 180;}public float scale(){return 1;}public Rectangle scissorArea(){return new Rectangle(0,0,160,180);}}
    private static final java.util.List<String> rootPainted = new java.util.ArrayList<>();
    public static void rootPaint(CpmOwnedPreviewDispatchProbe.WidgetTerminal widget) {
        var value=(CpmPreviewScopeProbe.FrameWidget)widget;
        rootPainted.add(value.id+":"+value.x+":"+value.y+":"+value.width+":"+value.message.getString());
    }
    public static final class FrameSurface {
        static Class<?> actual;
        private static void forward(String name,Class<?>[] types,Object... args) {try{actual.getMethod(name,types).invoke(null,args);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}}
        public static void drawVanillaListPanel(Graphics graphics,com.naocraftlab.skins.runtime.ViewSpec view,com.naocraftlab.skins.runtime.ViewSpec.Panel panel) {forward("drawVanillaListPanel",new Class<?>[]{Graphics.class,com.naocraftlab.skins.runtime.ViewSpec.class,com.naocraftlab.skins.runtime.ViewSpec.Panel.class},graphics,view,panel);}
        public static void drawVanillaTabContentPanel(Graphics graphics,com.naocraftlab.skins.runtime.ViewSpec view,com.naocraftlab.skins.runtime.ViewSpec.Panel panel) {forward("drawVanillaTabContentPanel",new Class<?>[]{Graphics.class,com.naocraftlab.skins.runtime.ViewSpec.class,com.naocraftlab.skins.runtime.ViewSpec.Panel.class},graphics,view,panel);}
        public static void drawFrameBackgrounds(Graphics graphics,com.naocraftlab.skins.runtime.ViewSpec view) {forward("drawFrameBackgrounds",new Class<?>[]{Graphics.class,com.naocraftlab.skins.runtime.ViewSpec.class},graphics,view);}
        public static void drawFrameSeparators(Graphics graphics,com.naocraftlab.skins.runtime.ViewSpec view,int width,int height) {forward("drawFrameSeparators",new Class<?>[]{Graphics.class,com.naocraftlab.skins.runtime.ViewSpec.class,int.class,int.class},graphics,view,width,height);}
        public static void renderFramePanels(Graphics graphics,com.naocraftlab.skins.runtime.ViewSpec view) {}
    }
    public record LoadingFrame(String resource, int x, int y, float u, float v, int width, int height,
            int sourceWidth, int sourceHeight, int textureWidth, int textureHeight) {}
    public static final class Graphics extends CpmOwnedPreviewDispatchProbe.Graphics {
        Rectangle currentScissor() { return scissor; }
        private final org.joml.Matrix3x2fStack matrix=new org.joml.Matrix3x2fStack(16);
        public org.joml.Matrix3x2fStack pose() {return matrix;}
        @Override public void blit(Object pipeline,CpmOwnedPreviewDispatchProbe.Text texture,int x,int y,float u,float v,int width,int height,int tw,int th) {super.blit(pipeline,texture,x,y,u,v,width,height,tw,th);fill(x,y,x+width,y+height,-1);}
        static CpmExtractionBodyProbe.SdkFrames frames;
        static final java.util.List<Object> framePayloads = new java.util.ArrayList<>();
        public void fill(int x,int y,int right,int bottom,int color) {
            if (frames == null) return;
            try {frames.fill(new int[]{x,y,right,bottom},"fill:"+x+":"+y+":"+Integer.toHexString(color));}
            catch (Exception failure) {throw new AssertionError(failure);}
        }
        static final java.util.List<LoadingFrame> loadingFrames = new java.util.ArrayList<>();
        public void blit(CpmExtractionBodyProbe.Pipeline pipeline, Identifier texture, int x, int y, float u, float v,
                int width, int height, int sourceWidth, int sourceHeight, int textureWidth, int textureHeight, int color) {
            loadingFrames.add(new LoadingFrame(texture.value(), x, y, u, v, width, height, sourceWidth, sourceHeight, textureWidth, textureHeight));
        }
        public void blit(Object pipeline, CpmOwnedPreviewDispatchProbe.Text texture, int x, int y, float u, float v,
                int width, int height, int sourceWidth, int sourceHeight, int textureWidth, int textureHeight, int color) {
            loadingFrames.add(new LoadingFrame(texture.toString(), x, y, u, v, width, height, sourceWidth, sourceHeight, textureWidth, textureHeight));
        }
        public void nextStratum() {if(frames!=null)try{frames.next();}catch(Exception failure){throw new AssertionError(failure);}}
        Rectangle scissor;
        public void enableScissor(int x,int y,int right,int bottom) { scissor = new Rectangle(x,y,right-x,bottom-y); }
        public void disableScissor() { scissor = null; }
        public void submitEntityRenderState(CpmExtractionBodyProbe.State state, float scale, org.joml.Vector3f translation,
                org.joml.Quaternionf rotation, org.joml.Quaternionf camera, int x0, int y0, int x1, int y1) { enqueue.accept(this); }
        public void submitSkinRenderState(Model model,Identifier texture,float scale,float pitch,float yaw,float pivot,int x0,int y0,int x1,int y1){enqueue.accept(this);}
        public void skin(Model model,Identifier texture,float scale,float pitch,float yaw,float pivot,int x0,int y0,int x1,int y1){enqueue.accept(this);}
    }
    public static class MenuWidget {
        public boolean visible=true;
        public boolean isVisible() { return visible; }
        public void setVisible(boolean value) { visible = value; }
        public int getX(){return 327;}public int getY(){return 142;}public int getWidth(){return 200;}public int getHeight(){return 20;}
        public int getRight(){return getX()+getWidth();}public int getBottom(){return getY()+getHeight();}
    }
    public static final class MenuAction extends MenuWidget {
        java.util.Optional<com.naocraftlab.skins.runtime.Bounds> bounds=java.util.Optional.empty();
        public boolean isFocused(){return false;}
        public void applyBounds(java.util.Optional<com.naocraftlab.skins.runtime.Bounds> value){bounds=value;}
        public void apply(java.util.Optional<com.naocraftlab.skins.runtime.Bounds> value){bounds=value;}
    }
    public static class MenuScreen {
        public int width=854,height=480;
        boolean anchor = true;
        public java.util.List<MenuWidget> children(){return anchor ? java.util.List.of(new MenuWidget()) : java.util.List.of();}
    }
    public static final class TitleMenu extends MenuScreen {}
    public static final class PauseMenu extends MenuScreen {}
    public static final class MenuRuntime {
        static com.naocraftlab.skins.runtime.ClientRuntime current;
        public static boolean closed(){return false;}
        public static com.naocraftlab.skins.runtime.ClientRuntime runtime(){return java.util.Objects.requireNonNull(current);}
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
    public static class Part {public boolean visible;public float xRot,yRot,zRot,x,y,z;public void resetPose(){}public Part getChild(String name){return new Part();}
        public java.util.stream.Stream<Part> getAllParts(){return java.util.stream.Stream.of(this);}
        public void render(CpmPreviewScopeProbe.Pose pose,Vertex consumer,int light,int overlay){consumer.consumeMaterial();}
        public void render(CpmPreviewScopeProbe.Pose pose,Vertex consumer,int light,int overlay,float r,float g,float b,float a){consumer.consumeMaterial();}
    }
    public static class Model {
        public float attackTime;public boolean crouching,riding,young;
        public final Part head=new Part(),body=new Part(),rightArm=new Part(),leftArm=new Part(),rightLeg=new Part(),leftLeg=new Part();
        public final Part hat=new Part(),jacket=new Part(),leftSleeve=new Part(),rightSleeve=new Part(),leftPants=new Part(),rightPants=new Part();
        public void resetPose(){}public void setAllVisible(boolean visible){}
        public void setupAnim(CpmExtractionBodyProbe.Avatar state){}
        public void setupAnim(Humanoid state){}
        public void copyPropertiesTo(Model other){}
        public Part root(){return new Part();}
        public RenderType renderType(Identifier texture){return RenderType.entityTranslucent(texture);}
        public RenderType renderType(CpmPreviewScopeProbe.Location texture){return RenderType.entityTranslucent(texture);}
        public void renderToBuffer(CpmExtractionBodyProbe.Pose pose,Vertex consumer,int light,int overlay){consumer.consumeMaterial();primary();}
        public void renderToBuffer(CpmPreviewScopeProbe.Pose pose,Vertex consumer,int light,int overlay,int color){consumer.consumeMaterial();primary();}
        public void renderToBuffer(CpmPreviewScopeProbe.Pose pose,Vertex consumer,int light,int overlay,float r,float g,float b,float a){consumer.consumeMaterial();primary();}
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
    public interface Vertex { default void consumeMaterial() {} }
    public interface BufferAccess {Vertex getBuffer(RenderType type);}
    public static final class ImmediateCapeMaterial {
        public static Vertex buffer(BufferAccess buffers,CpmPreviewScopeProbe.Location texture,boolean glint,RenderType type){return buffers.getBuffer(type);}
        public static CpmPreviewScopeProbe.Location withDefaultNamespace(String path){return new CpmPreviewScopeProbe.Location("minecraft",path);}
    }
    public static final class Buffers implements BufferAccess {
        private final java.util.List<Object> consumedMaterials = new java.util.ArrayList<>();
        public Vertex getBuffer(RenderType type) {
            if(observeImmediateMenu && type.immediateTexture != null) immediateMaterials.add(type.immediateTexture);
            if(CpmExtractionBodyProbe.RenderSystem.outputColorTextureOverride != null)
                CpmExtractionBodyProbe.RenderSystem.outputColorTextureOverride.texture.data = type.texture;
            return new Vertex() {
                public void consumeMaterial() {
                    if (type.texture != null) consumedMaterials.add(type.texture);
                    if (observeImmediateMenu && type.immediateTexture != null) immediateDrawnMaterials.add(type.immediateTexture);
                }
            };
        }
        public void endBatch() {
            if(!consumedMaterials.isEmpty() && CpmExtractionBodyProbe.RenderSystem.outputColorTextureOverride != null)
                CpmExtractionBodyProbe.RenderSystem.outputColorTextureOverride.texture.materialInputs = java.util.List.copyOf(consumedMaterials);
            consumedMaterials.clear();
            flush();
        }
    }
    public static final class RenderType {Identifier texture;CpmPreviewScopeProbe.Location immediateTexture;public static RenderType entityTranslucent(Identifier id){var type=new RenderType();type.texture=id;return type;}public static RenderType entityTranslucent(CpmPreviewScopeProbe.Location id){var type=new RenderType();type.immediateTexture=id;return type;}
        public static RenderType armorCutoutNoCull(CpmPreviewScopeProbe.Location id){return entityTranslucent(id);}
    }
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
