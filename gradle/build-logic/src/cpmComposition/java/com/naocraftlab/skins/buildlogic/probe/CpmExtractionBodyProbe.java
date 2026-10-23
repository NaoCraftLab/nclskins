package com.naocraftlab.skins.buildlogic.probe;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.naocraftlab.skins.client.EditorPreviewLayerGuard;
import com.naocraftlab.skins.client.EditorPreviewSession;
import com.naocraftlab.skins.client.ExactLocalPlayerScope;
import com.naocraftlab.skins.client.PreviewModelFrames;
import com.naocraftlab.skins.client.PreviewRenderer;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public final class CpmExtractionBodyProbe {
    private static final String PREFIX = "com.naocraftlab.skins.compat.client.identifier.extraction.";
    private static final String PIP = "net.minecraft.client.gui.render.pip.PictureInPictureRenderer";
    private static final String GUI = "net.minecraft.client.gui.render.pip.GuiEntityRenderer";
    private static Runnable submit = () -> {};
    private static Runnable late = () -> {};
    private static Runnable passClose = () -> {};
    private static Runnable frameClose = () -> {};
    private static Runnable afterInner = () -> {};
    private static Consumer<GuiState> inner = ignored -> {};
    private static java.util.function.Function<Object, Avatar> stateFactory;
    private static int submits;
    private static int flushes;
    private static int frameOpens;
    private static int frameCloses;
    public static boolean cached;

    static void bodyProducer(PreviewRenderer.PreviewAppearance appearance, Runnable binding, Runnable cleanupFailure) throws Exception {
        if (CpmExtractionBodyProbe.class.getClassLoader().getResource(PREFIX.replace('.', '/') + "AvatarPreviewContext.class") == null) return;
        PreviewModelFrames.install(value -> {
            var frame = com.naocraftlab.skins.compat.cpm.CpmFrame.open(value);
            frameOpens++;
            return new PreviewModelFrames.Lease() {
                @Override public PreviewModelFrames.Body body() { return frame.body(); }
                @Override public void close() { try { frame.close(); } finally { frameCloses++; } }
            };
        });
        var types = new HashMap<String, Class<?>>();
        types.put("net/minecraft/client/Minecraft", Client.class);
        types.put("net/minecraft/client/player/AbstractClientPlayer", CpmPreviewScopeProbe.Avatar.class);
        types.put("net/minecraft/client/player/LocalPlayer", CpmPreviewScopeProbe.Avatar.class);
        types.put("net/minecraft/world/entity/Avatar", CpmPreviewScopeProbe.Player.class);
        types.put("net/minecraft/world/entity/LivingEntity", CpmPreviewScopeProbe.Entity.class);
        types.put("net/minecraft/world/entity/EquipmentSlot", CpmPreviewScopeProbe.Slot.class);
        types.put("net/minecraft/world/entity/player/PlayerModelPart", CpmPreviewScopeProbe.Part.class);
        types.put("net/minecraft/world/entity/player/PlayerSkin", Skin.class);
        types.put("net/minecraft/core/ClientAsset$Texture", Texture.class);
        types.put("net/minecraft/world/item/ItemStack", CpmPreviewScopeProbe.Stack.class);
        types.put("net/minecraft/client/renderer/entity/state/EntityRenderState", State.class);
        types.put("net/minecraft/client/renderer/entity/state/AvatarRenderState", Avatar.class);
        types.put("net/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState", PipState.class);
        types.put("net/minecraft/client/renderer/state/gui/pip/GuiEntityRenderState", GuiState.class);
        types.put("net/minecraft/client/renderer/state/gui/GuiRenderState", GuiRenderState.class);
        types.put("net/minecraft/client/renderer/SubmitNodeCollector", Collector.class);
        types.put("net/minecraft/client/renderer/SubmitNodeStorage", Storage.class);
        types.put("net/minecraft/client/renderer/feature/FeatureRenderDispatcher", Features.class);
        types.put("net/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame", PreparedFrame.class);
        types.put("net/minecraft/client/renderer/entity/EntityRenderDispatcher", Dispatcher.class);
        types.put("net/minecraft/client/renderer/state/level/CameraRenderState", CpmPreviewScopeProbe.Camera.class);
        types.put("net/minecraft/client/renderer/state/CameraRenderState", CpmPreviewScopeProbe.Camera.class);
        types.put("net/minecraft/client/renderer/GameRenderer", GameRenderer.class);
        types.put("net/minecraft/client/multiplayer/ClientLevel", CpmPreviewScopeProbe.Level.class);
        types.put("net/minecraft/client/gui/GuiGraphicsExtractor", Graphics.class);
        types.put((PREFIX + "AvatarRenderStatePreviewRenderer$PreviewPlayer").replace('.', '/'), CpmPreviewScopeProbe.Avatar.class);
        types.put((PREFIX + "AvatarPipPreviewRenderer").replace('.', '/'), Baked.class);
        types.put("com/naocraftlab/skins/compat/client/identifier/CapeMaterialSnapshot", Material.class);
        types.put("com/naocraftlab/skins/compat/client/identifier/CapeMaterialSnapshot$Carrier", MaterialCarrier.class);
        types.put("com/mojang/blaze3d/platform/Lighting", CpmPreviewScopeProbe.Light.class);
        types.put("com/mojang/blaze3d/platform/Lighting$Entry", CpmPreviewScopeProbe.Light.Entry.class);
        types.put("com/mojang/blaze3d/vertex/PoseStack", Pose.class);
        types.put("com/mojang/blaze3d/systems/RenderSystem", RenderSystem.class);
        types.put("com/mojang/blaze3d/textures/GpuTexture", LegacyTexture.class);
        types.put("com/mojang/blaze3d/textures/GpuTextureView", TextureView.class);
        types.put("com/mojang/renderpearl/api/textures/GpuTexture", GpuTexture.class);
        types.put("com/mojang/renderpearl/api/textures/GpuTextureView", TextureView.class);
        types.put("com/mojang/renderpearl/api/device/GpuDevice", Device.class);
        types.put("com/mojang/renderpearl/api/commands/CommandEncoder", Encoder.class);
        types.put("com/mojang/renderpearl/api/commands/RenderPass", Pass.class);
        var loader = new Loader(types);
        Class<?> contextType = loader.loadClass(PREFIX + "AvatarPreviewContext");
        Class<?> scope = loader.loadClass(PREFIX + "AvatarPreviewScope");
        Class<?> marker = loader.loadClass(PREFIX + "NclSkinsWideDepthState");
        Class<?> stateType = loader.state(marker);
        Class<?> wrapperType = loader.loadClass(PREFIX + "mixin.GuiEntityRendererMixin");
        Method wrapper = java.util.Arrays.stream(wrapperType.getDeclaredMethods())
                .filter(method -> method.getName().equals("nclskins$guardEditorPreview")).findFirst().orElseThrow();
        wrapper.setAccessible(true);
        Object wrapperOwner = create(wrapperType);
        Class<?> nativeGuiType = loader.loadClass(GUI);
        Method nativeGui = java.util.Arrays.stream(nativeGuiType.getDeclaredMethods())
                .filter(method -> method.getName().equals("renderToTexture")).findFirst().orElseThrow();
        nativeGui.setAccessible(true);
        Object nativeGuiOwner = create(nativeGuiType);
        set(nativeGuiOwner, "entityRenderDispatcher", new Dispatcher());
        boolean deferred = wrapper.getParameterCount() == 4;
        Object outerOwner = deferred ? create(loader.loadClass(PREFIX + "mixin.PictureInPictureRendererMixin")) : null;
        Method outer = deferred ? java.util.Arrays.stream(outerOwner.getClass().getDeclaredMethods())
                .filter(method -> method.getName().equals("nclskins$withDepthScope")).findFirst().orElseThrow() : null;
        if (outer != null) outer.setAccessible(true);
        Object nativePipOwner = deferred ? create(loader.loadClass(PIP)) : null;
        if (nativePipOwner != null) {
            set(nativePipOwner, "submitNodeStorage", new Storage());
            set(nativePipOwner, "texture", loader.staticFlush ? new GpuTexture() {
                public int getWidth(int mip) { return 16; }
                public int getHeight(int mip) { return 16; }
            } : new LegacyTexture());
        }
        Method nativePrepare = deferred ? nativePipOwner.getClass().getDeclaredMethod("prepare", PipState.class, GuiRenderState.class, Features.class, int.class) : null;
        var client = new Client();
        client.player = new CpmPreviewScopeProbe.Avatar(client.level);
        Client.current = client;
        var constructor = contextType.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        int[] notifications = {0};
        try {
            for (String scenario : new String[]{"success", "late-binding", "inner-handled", "layer-handled", "late-failure", "cleanup-failure", "retire", "pass-close", "frame-close", "combined-failure", "ordinary", "source-mismatch", "model-mismatch", "cached", "recursive", "different-context", "replacement"}) {
                if (!deferred && java.util.Set.of("pass-close", "frame-close", "cached", "recursive", "different-context", "replacement").contains(scenario)) continue;
                if (!loader.staticFlush && java.util.Set.of("pass-close", "frame-close").contains(scenario)) continue;
                var session = new EditorPreviewSession();
                session.beginBody("fixture:extraction", false, true);
                session.observeBody(ignored -> {
                    notifications[0]++;
                    if (session.body().status() == PreviewRenderer.BodyStatus.READY) {
                        check(!EditorPreviewLayerGuard.isActive(), "READY notification follows owned layer cleanup");
                        check(frameOpens == frameCloses, "READY notification follows genuine CPM frame cleanup");
                        if (deferred) {
                            try {
                                var depth = outerOwner.getClass().getDeclaredField("NCLSKINS_DEPTH_EXTENT"); depth.setAccessible(true);
                                check(((ThreadLocal<?>) depth.get(null)).get().equals(0.0F), "READY notification follows depth restoration");
                            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                        }
                    }
                });
                boolean[] current = {true};
                BooleanSupplier currentScope = () -> current[0];
                var selected = scenario.equals("source-mismatch") || scenario.equals("model-mismatch")
                        ? new PreviewRenderer.PreviewAppearance(scenario.equals("source-mismatch")
                                ? new com.naocraftlab.skins.client.TextureRegistry.TextureHandle("fixture:other-body", 64, 64) : appearance.skin(),
                                scenario.equals("model-mismatch") ? com.naocraftlab.skins.client.SkinModel.SLIM : appearance.model(),
                                appearance.cape(), appearance.capeMode(), appearance.outerLayerVisibility(), appearance.capeHasElytra(), appearance.capeGlint(), appearance.nativeAccessories())
                        : appearance;
                Object context = constructor.getParameterCount() == 6
                        ? constructor.newInstance(client.player, selected, new Skin(), CpmPreviewScopeProbe.Stack.EMPTY,
                                currentScope, session.displayBody(currentScope))
                        : constructor.newInstance(client.player, selected, new Skin(), CpmPreviewScopeProbe.Stack.EMPTY, currentScope);
                Object avatar = stateType.getConstructor().newInstance();
                marker.getMethod("nclskins$setPreviewContext", contextType).invoke(avatar, context);
                Class<?> failureSinkType = loader.loadClass(PREFIX + "PreviewRenderFailureSink");
                Object failureSink = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{failureSinkType}, (proxy, method, args) -> null);
                marker.getMethod("nclskins$setFailureSink", failureSinkType).invoke(avatar, failureSink);
                marker.getMethod("nclskins$setLayerFailureSink", failureSinkType).invoke(avatar, failureSink);
                marker.getMethod("nclskins$setDepthExtent", float.class).invoke(avatar, 300.0F);
                GuiState gui = new GuiState((State) avatar);
                submits = 0; flushes = 0; frameOpens = 0; frameCloses = 0; cached = scenario.equals("cached");
                submit = () -> {
                    if (!scenario.equals("late-binding") && !scenario.equals("ordinary")) binding.run();
                    if (scenario.equals("inner-handled")) throw new IllegalStateException("synthetic inner failure");
                    if (scenario.equals("combined-failure")) cleanupFailure.run();
                };
                late = () -> {
                    if (scenario.equals("late-binding")) binding.run();
                    if (scenario.equals("cleanup-failure")) cleanupFailure.run();
                    if (scenario.equals("layer-handled")) check(EditorPreviewLayerGuard.handle(new IllegalStateException("synthetic handled layer")), "late layer guard spans feature execution");
                    if (scenario.equals("late-failure") || scenario.equals("combined-failure")) throw new IllegalStateException("synthetic late failure");
                    if (scenario.equals("retire")) current[0] = false;
                };
                passClose = () -> { if (scenario.equals("pass-close") || scenario.equals("combined-failure")) throw new IllegalStateException("synthetic pass close failure"); };
                frameClose = () -> { if (scenario.equals("frame-close") || scenario.equals("combined-failure")) throw new IllegalStateException("synthetic frame close failure"); };
                inner = state -> {
                    Pose pose = new Pose();
                    Storage storage = new Storage();
                    Operation<Void> original = args -> {
                        check(args[0] == state && args[1] == pose && (!deferred || args[2] == storage), "inner wrapper preserves continuation arguments");
                        call(nativeGui, nativeGuiOwner, deferred ? new Object[]{args[0], args[1], args[2]} : new Object[]{args[0], args[1]});
                        return null;
                    };
                    call(wrapper, wrapperOwner, deferred ? new Object[]{state, pose, storage, original} : new Object[]{state, pose, original});
                };
                afterInner = () -> {
                    check(frameOpens - frameCloses == 1 && EditorPreviewLayerGuard.isActive(), "one matching native frame and layer guard span inner return through late execution");
                    if (flushes == 0) check(session.body().status() == PreviewRenderer.BodyStatus.ORDINARY, "inner submission return before actual native late flush is not display");
                    if (scenario.equals("retire")) current[0] = false;
                    if (scenario.equals("recursive")) inner.accept(gui);
                    if (scenario.equals("different-context")) {
                        try {
                            Object another = constructor.getParameterCount() == 6
                                    ? constructor.newInstance(client.player, appearance, new Skin(), CpmPreviewScopeProbe.Stack.EMPTY, currentScope, session.displayBody(currentScope))
                                    : constructor.newInstance(client.player, appearance, new Skin(), CpmPreviewScopeProbe.Stack.EMPTY, currentScope);
                            Object different = stateType.getConstructor().newInstance();
                            marker.getMethod("nclskins$setPreviewContext", contextType).invoke(different, another);
                            inner.accept(new GuiState((State) different));
                        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                    }
                    if (scenario.equals("replacement")) session.beginBody("fixture:new-extraction", false, true);
                };
                Throwable failure = null;
                if (deferred && scenario.equals("success")) {
                    inner.accept(gui);
                    check(session.body().status() == PreviewRenderer.BodyStatus.ORDINARY,
                            "submission-only invocation has no display authority without matching outer completion");
                    check(frameOpens == 1 && frameCloses == 1, "standalone submission preserves existing own-scope cleanup");
                    frameOpens = 0; frameCloses = 0; submits = 0;
                }
                try {
                    if (deferred) {
                        Operation<Void> continuation = args -> {
                            check(args[0] == gui && ((Integer) args[3]) == 1, "outer wrapper preserves state and gui scale");
                            call(nativePrepare, nativePipOwner, args);
                            return null;
                        };
                        call(outer, outerOwner, gui, new GuiRenderState(), new Features(), 1, continuation);
                    } else inner.accept(gui);
                } catch (RuntimeException | Error thrown) { failure = thrown; }
                boolean positive = scenario.equals("success") || scenario.equals("late-binding");
                if (deferred && java.util.Set.of("late-failure", "pass-close", "frame-close", "combined-failure").contains(scenario)) {
                    check(failure instanceof IllegalStateException, "late native failure propagation is preserved");
                    if (scenario.equals("combined-failure")) check(failure.getMessage().equals("synthetic late failure")
                                    && failure.getSuppressed().length == (loader.staticFlush ? 3 : 1),
                            "original late exception and native/owned cleanup suppression are preserved");
                }
                check(!positive || failure == null, scenario + " actual producer must complete: " + failure);
                check(session.body().status() == (positive ? PreviewRenderer.BodyStatus.READY : PreviewRenderer.BodyStatus.ORDINARY),
                        scenario + ": actual " + (deferred ? "outer prepare + late features" : "player-model full pass") + " + own cleanup must report " + (positive ? "READY" : "ORDINARY"));
                boolean ordinaryDisplay = java.util.Set.of("ordinary", "source-mismatch", "model-mismatch").contains(scenario);
                check(session.body().current().getAsBoolean() == (positive || ordinaryDisplay),
                        scenario + ": only successful current primary/late execution and cleanup is display evidence");
                check(!EditorPreviewLayerGuard.isActive(), scenario + ": layer guard closes");
                check(frameOpens == 1 && frameCloses == 1, scenario + ": exactly one owned native frame opens and closes");
                if (deferred) {
                    var depthField = outerOwner.getClass().getDeclaredField("NCLSKINS_DEPTH_EXTENT"); depthField.setAccessible(true);
                    check(((ThreadLocal<?>) depthField.get(null)).get().equals(0.0F), "outer depth is restored before displayed completion");
                }
                check(scope.getMethod("modelPart", CpmPreviewScopeProbe.Player.class, CpmPreviewScopeProbe.Part.class)
                        .invoke(null, client.player, CpmPreviewScopeProbe.Part.HAT) == null, scenario + ": native scope closes");
                try (var ignored = new ExactLocalPlayerScope(client.player, appearance)) {}
                if (positive || ordinaryDisplay) {
                    int before = notifications[0];
                    if (deferred) call(outer, outerOwner, gui, new GuiRenderState(), new Features(), 1, (Operation<Void>) args -> { call(nativePrepare, nativePipOwner, args); return null; });
                    else inner.accept(gui);
                    check(notifications[0] == before, "equivalent frames do not create body publication churn");
                    if (deferred) {
                        cached = true;
                        int priorSubmits = submits;
                        call(outer, outerOwner, gui, new GuiRenderState(), new Features(), 1, (Operation<Void>) args -> { call(nativePrepare, nativePipOwner, args); return null; });
                        check(submits == priorSubmits && session.body().current().getAsBoolean(),
                                "cached pass may retain only the already-confirmed same-context current display");
                        cached = false;
                    }
                    try (var occupied = new ExactLocalPlayerScope(client.player, appearance)) {
                        try {
                            if (deferred) call(outer, outerOwner, gui, new GuiRenderState(), new Features(), 1, (Operation<Void>) args -> { call(nativePrepare, nativePipOwner, args); return null; });
                            else inner.accept(gui);
                        } catch (IllegalStateException expected) {}
                        check(session.body().status() == PreviewRenderer.BodyStatus.ORDINARY,
                                "failed attempt admission clears a previously displayed witness without borrowing the active scope");
                    }
                    current[0] = false;
                    check(session.body().status() == PreviewRenderer.BodyStatus.ORDINARY, "retired context revokes displayed body without a callback");
                }
                System.out.println("PASS extraction " + scenario + " deferred=" + deferred + " submits=" + submits + " flushes=" + flushes);
            }
            int[] calls = {0};
            GuiState ordinary = new GuiState(new Avatar());
            if (deferred) call(outer, outerOwner, ordinary, new GuiRenderState(), new Features(), 1, (Operation<Void>) args -> { calls[0]++; return null; });
            else call(wrapper, wrapperOwner, ordinary, new Pose(), (Operation<Void>) args -> { calls[0]++; return null; });
            check(calls[0] == 1, "non-owned continuation executes exactly once");
            submit = () -> {}; late = () -> {}; passClose = () -> {}; frameClose = () -> {}; afterInner = () -> {}; cached = false;
            frameOpens = 0; frameCloses = 0;
            if (deferred) call(outer, outerOwner, ordinary, new GuiRenderState(), new Features(), 1, (Operation<Void>) args -> { call(nativePrepare, nativePipOwner, args); return null; });
            else inner.accept(ordinary);
            check(frameOpens == 0 && frameCloses == 0 && !EditorPreviewLayerGuard.isActive(), "actual non-owned native path does not open preview frame or layer scope");
            Class<?> rendererType = loader.loadClass(PREFIX + "AvatarRenderStatePreviewRenderer");
            Object renderer = create(rendererType);
            var session = new EditorPreviewSession();
            for (var field : rendererType.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = switch (field.getName()) {
                    case "session" -> session;
                    case "previewPlayer" -> client.player;
                    case "previewLevel" -> client.level;
                    case "previewProfile" -> new com.mojang.authlib.GameProfile(new java.util.UUID(0, 999), "NCLFixture");
                    case "previewClock" -> new com.naocraftlab.skins.client.EditorPreviewClock();
                    case "bakedRenderer" -> new Baked();
                    case "diagnostics" -> com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding();
                    case "accessoryObserver" -> (Consumer<com.naocraftlab.skins.client.PreviewAccessories>) ignored -> {};
                    default -> null;
                };
                if (value != null) field.set(renderer, value);
            }
            stateFactory = context -> {
                try {
                    Object state = stateType.getConstructor().newInstance();
                    marker.getMethod("nclskins$setPreviewContext", contextType).invoke(state, context);
                    return (Avatar) state;
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            };
            var render = rendererType.getMethod("render", Graphics.class, PreviewRenderer.PreviewRequest.class);
            var body = rendererType.getMethod("body");
            var prepareBody = rendererType.getMethod("prepareBody", String.class, boolean.class);
            var request = new PreviewRenderer.PreviewRequest(appearance, 0, 0, 160, 180, 0, 0, 1, PreviewRenderer.PreviewIntent.EDITOR_DRAFT);
            submit = binding; late = () -> {}; passClose = () -> {}; frameClose = () -> {}; afterInner = () -> {}; cached = false;
            prepareBody.invoke(renderer, "fixture:producer-render", false);
            render.invoke(renderer, new Graphics(), request);
            check(session.body().status() == PreviewRenderer.BodyStatus.ORDINARY, "actual extraction renderer enqueue does not report displayed READY");
            Consumer<GuiState> execute = state -> {
                if (deferred) call(outer, outerOwner, state, new GuiRenderState(), new Features(), 1, (Operation<Void>) args -> { call(nativePrepare, nativePipOwner, args); return null; });
                else inner.accept(state);
            };
            execute.accept(Graphics.queued);
            check(((PreviewModelFrames.Body) body.invoke(renderer)).status() == PreviewRenderer.BodyStatus.READY,
                    "actual extraction render -> original context completion -> full native pass -> renderer body query");
            Object oldState = Graphics.queued;
            prepareBody.invoke(renderer, "fixture:producer-replacement", false);
            execute.accept((GuiState) oldState);
            check(session.body().status() == PreviewRenderer.BodyStatus.ORDINARY, "old queued context cannot borrow replacement source");
            render.invoke(renderer, new Graphics(), request); execute.accept(Graphics.queued);
            check(session.body().status() == PreviewRenderer.BodyStatus.READY, "replacement source obtains its own actual display");
            var texture = com.naocraftlab.skins.client.NativePlayerSkinLifecycle.pending(appearance.skin().location()); texture.ready();
            render.invoke(renderer, new Graphics(), request); execute.accept(Graphics.queued); texture.retire();
            check(((PreviewModelFrames.Body) body.invoke(renderer)).status() == PreviewRenderer.BodyStatus.ORDINARY, "extraction resource retirement revokes actual witness");
            render.invoke(renderer, new Graphics(), request); execute.accept(Graphics.queued);
            var level = client.level; client.level = null;
            check(session.body().status() == PreviewRenderer.BodyStatus.ORDINARY, "extraction world loss revokes actual witness");
            client.level = level;
            render.invoke(renderer, new Graphics(), request); execute.accept(Graphics.queued);
            set(renderer, "closed", true);
            check(session.body().status() == PreviewRenderer.BodyStatus.ORDINARY, "closed extraction renderer revokes witness");
            System.out.println("PASS actual extraction renderer enqueue/context/current/session/body query, source replacement/resource/world/close");
        } finally {
            submit = () -> {}; late = () -> {}; passClose = () -> {}; frameClose = () -> {}; afterInner = () -> {}; inner = ignored -> {};
            Client.current = null;
            stateFactory = null;
            Graphics.queued = null;
            PreviewModelFrames.install(com.naocraftlab.skins.compat.cpm.CpmFrame::open);
        }
        System.out.println("PASS actual extraction producer: genuine target native method bytecode + production wrappers/context/scope + genuine CPM primary binding; synthetic host/GPU terminals, not game acceptance");
    }

    private static Object create(Class<?> type) throws Exception {
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true); return constructor.newInstance();
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private static Object call(Method method, Object target, Object... args) {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Loader extends ClassLoader {
        private final Map<String, Class<?>> types;
        private boolean staticFlush;
        Loader(Map<String, Class<?>> types) { super(CpmExtractionBodyProbe.class.getClassLoader()); this.types = types; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(PREFIX) && !name.equals(PIP) && !name.equals(GUI)) return super.loadClass(name, resolve);
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (input == null) throw new ClassNotFoundException(name);
                    byte[] original = input.readAllBytes();
                    var node = new ClassNode(); new ClassReader(original).accept(node, 0);
                    if (name.equals(PREFIX + "AvatarRenderStatePreviewRenderer")) rendererTerminals(node);
                    if (name.equals(PIP) || name.equals(GUI)) {
                        if (name.equals(PIP)) for (var method : node.methods) for (var instruction : method.instructions) {
                            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                                    && call.name.equals("renderAllFeatures") && call.getOpcode() == Opcodes.INVOKESTATIC) staticFlush = true;
                        }
                        nativeTerminals(node, name.equals(PIP));
                        for (var method : node.methods) for (var instruction : method.instructions) {
                            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                                    && call.owner.equals("net/minecraft/client/renderer/state/gui/pip/GuiEntityRenderState")) {
                                call.desc = call.desc.replace("Lorg/joml/Vector3fc;", "Lorg/joml/Vector3f;")
                                        .replace("Lorg/joml/Quaternionfc;", "Lorg/joml/Quaternionf;");
                            }
                        }
                    }
                    if (name.contains(".mixin.")) {
                        node.access &= ~Opcodes.ACC_ABSTRACT;
                        init(node);
                    }
                    var writer = new ClassWriter(0);
                    node.accept(new ClassRemapper(writer, new Remapper(Opcodes.ASM9) {
                        @Override public String map(String internalName) {
                            Class<?> mapped = types.get(internalName);
                            return mapped == null ? internalName : Type.getInternalName(mapped);
                        }
                    }));
                    byte[] bytes = writer.toByteArray();
                    String export = System.getProperty("nclskins.cpm.extractionExport");
                    if (export != null) {
                        var path = java.nio.file.Path.of(export).resolve(name.replace('.', '/') + ".class");
                        java.nio.file.Files.createDirectories(path.getParent()); java.nio.file.Files.write(path, bytes);
                    }
                    System.out.println("Extraction class " + name + " original=" + digest(original) + " headless=" + digest(bytes));
                    type = defineClass(name, bytes, 0, bytes.length);
                } catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
            }
            if (resolve) resolveClass(type);
            return type;
        }
        Class<?> state(Class<?> marker) {
            String name = PREFIX + "ProbeState";
            var writer = new ClassWriter(0);
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, Type.getInternalName(Avatar.class), new String[]{Type.getInternalName(marker)});
            var init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0); init.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(Avatar.class), "<init>", "()V", false);
            init.visitInsn(Opcodes.RETURN); init.visitMaxs(1, 1); init.visitEnd();
            for (String field : new String[]{"DepthExtent", "FailureSink", "LayerFailureSink", "PreviewContext"}) {
                Method getter = java.util.Arrays.stream(marker.getMethods()).filter(method -> method.getName().equals("nclskins$" + Character.toLowerCase(field.charAt(0)) + field.substring(1))).findFirst().orElseThrow();
                String desc = Type.getDescriptor(getter.getReturnType());
                writer.visitField(Opcodes.ACC_PUBLIC, field, desc, null, null).visitEnd();
                var get = writer.visitMethod(Opcodes.ACC_PUBLIC, getter.getName(), "()" + desc, null, null);
                get.visitVarInsn(Opcodes.ALOAD, 0); get.visitFieldInsn(Opcodes.GETFIELD, name.replace('.', '/'), field, desc);
                get.visitInsn(desc.equals("F") ? Opcodes.FRETURN : Opcodes.ARETURN); get.visitMaxs(1, 1); get.visitEnd();
                var set = writer.visitMethod(Opcodes.ACC_PUBLIC, "nclskins$set" + field, "(" + desc + ")V", null, null);
                set.visitVarInsn(Opcodes.ALOAD, 0); set.visitVarInsn(desc.equals("F") ? Opcodes.FLOAD : Opcodes.ALOAD, 1);
                set.visitFieldInsn(Opcodes.PUTFIELD, name.replace('.', '/'), field, desc); set.visitInsn(Opcodes.RETURN); set.visitMaxs(2, 2); set.visitEnd();
            }
            writer.visitEnd(); byte[] bytes = writer.toByteArray(); return defineClass(name, bytes, 0, bytes.length);
        }
    }

    static void nativeTerminals(ClassNode node, boolean pip) {
        node.access &= ~Opcodes.ACC_ABSTRACT; node.superName = "java/lang/Object";
        node.signature = null; node.interfaces.clear(); node.innerClasses.clear(); node.nestMembers = null;
        node.methods.removeIf(method -> !(pip ? method.name.equals("prepare") : method.name.equals("renderToTexture") && (method.access & Opcodes.ACC_BRIDGE) == 0));
        init(node);
        if (!pip) return;
        node.fields.removeIf(field -> !java.util.Set.of("texture", "textureView", "depthTextureView", "submitNodeStorage").contains(field.name));
        String state = "Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;";
        MethodNode ready = method(node, "textureIsReadyToBlit", "(" + state + ")Z");
        ready.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(CpmExtractionBodyProbe.class), "cached", "Z"); ready.visitInsn(Opcodes.IRETURN); ready.visitMaxs(1, 2);
        MethodNode textures = method(node, "prepareTexturesAndProjection", "(ZII)V"); textures.visitInsn(Opcodes.RETURN); textures.visitMaxs(0, 4);
        MethodNode blit = method(node, "blitTexture", "(" + state + "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;)V"); blit.visitInsn(Opcodes.RETURN); blit.visitMaxs(0, 3);
        MethodNode y = method(node, "getTranslateY", "(II)F"); y.visitInsn(Opcodes.FCONST_0); y.visitInsn(Opcodes.FRETURN); y.visitMaxs(1, 3);
        MethodNode submit = method(node, "renderToTexture", "(" + state + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V");
        submit.visitVarInsn(Opcodes.ALOAD, 1); submit.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(GuiState.class));
        submit.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(CpmExtractionBodyProbe.class), "runInner", "(" + Type.getDescriptor(GuiState.class) + ")V", false);
        submit.visitInsn(Opcodes.RETURN); submit.visitMaxs(1, 4);
        MethodNode label = method(node, "lambda$prepare$0", "()Ljava/lang/String;"); label.visitLdcInsn("synthetic preview pass"); label.visitInsn(Opcodes.ARETURN); label.visitMaxs(1, 0);
        label.access |= Opcodes.ACC_STATIC;
    }

    private static void rendererTerminals(ClassNode node) {
        var helpers = node.methods.stream().filter(method -> java.util.Set.of("playerSkin", "previewPlayer", "applySettling", "createRenderState", "configurePreviewState", "previewElytraStack", "accessories").contains(method.name)).toList();
        node.methods.removeIf(method -> !java.util.Set.of("render", "body", "prepareBody", "observeBody", "onLiveRenderFailure", "onLiveLayerFailure", "clearAccessoryInspection").contains(method.name)
                && !method.name.startsWith("lambda$render$"));
        node.methods.removeIf(method -> (method.access & Opcodes.ACC_BRIDGE) != 0);
        init(node); node.innerClasses.clear(); node.nestMembers = null;
        node.fields.removeIf(field -> !java.util.Set.of("session", "closed", "previewPlayer", "previewLevel", "previewProfile", "previewClock", "bakedRenderer", "diagnostics", "accessoryObserver", "layerFailureLogged").contains(field.name));
        for (var old : helpers) {
            var helper = method(node, old.name, old.desc); helper.access = old.access;
            switch (old.name) {
                case "playerSkin" -> {
                    helper.visitTypeInsn(Opcodes.NEW, Type.getInternalName(Skin.class)); helper.visitInsn(Opcodes.DUP);
                    helper.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(Skin.class), "<init>", "()V", false); helper.visitInsn(Opcodes.ARETURN);
                }
                case "previewPlayer" -> { helper.visitVarInsn(Opcodes.ALOAD, 0); helper.visitFieldInsn(Opcodes.GETFIELD, node.name, "previewPlayer", "L" + node.name + "$PreviewPlayer;"); helper.visitInsn(Opcodes.ARETURN); }
                case "createRenderState" -> {
                    helper.visitVarInsn(Opcodes.ALOAD, 3); helper.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(CpmExtractionBodyProbe.class), "makeState", "(Ljava/lang/Object;)" + Type.getDescriptor(Avatar.class), false); helper.visitInsn(Opcodes.ARETURN);
                }
                case "previewElytraStack" -> { helper.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(CpmPreviewScopeProbe.Stack.class), "EMPTY", Type.getDescriptor(CpmPreviewScopeProbe.Stack.class)); helper.visitInsn(Opcodes.ARETURN); }
                case "accessories" -> { helper.visitFieldInsn(Opcodes.GETSTATIC, "com/naocraftlab/skins/client/PreviewRenderer$LiveReason", "LIVE_UNAVAILABLE", "Lcom/naocraftlab/skins/client/PreviewRenderer$LiveReason;"); helper.visitMethodInsn(Opcodes.INVOKESTATIC, "com/naocraftlab/skins/client/PreviewAccessories", "unavailable", "(Lcom/naocraftlab/skins/client/PreviewRenderer$LiveReason;)Lcom/naocraftlab/skins/client/PreviewAccessories;", false); helper.visitInsn(Opcodes.ARETURN); }
                default -> helper.visitInsn(Opcodes.RETURN);
            }
            helper.visitMaxs(2, 6);
        }
    }

    static MethodNode method(ClassNode node, String name, String desc) {
        var method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null); node.methods.add(method); return method;
    }
    static void init(ClassNode node) {
        node.methods.removeIf(method -> method.name.equals("<init>"));
        var init = method(node, "<init>", "()V"); init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); init.visitInsn(Opcodes.RETURN); init.visitMaxs(1, 1);
    }

    public static void runInner(GuiState state) { inner.accept(state); afterInner.run(); }
    public static Avatar makeState(Object context) { return stateFactory.apply(context); }
    public static class State {}
    public static class Avatar extends State implements MaterialCarrier {
        public float ageInTicks;
        public Skin skin = new Skin();
        public void nclskins$setCapeMaterial(Material material) {}
    }
    public interface MaterialCarrier { void nclskins$setCapeMaterial(Material material); }
    public static final class Material {
        public static Material preview(String texture, String selected, boolean cape, boolean glint,
                com.naocraftlab.skins.client.TextureUseValidity validity) { return new Material(); }
    }
    public static final class Graphics {
        static GuiState queued;
        public void entity(State state, float scale, org.joml.Vector3fc offset, org.joml.Quaternionfc rotation, org.joml.Quaternionfc camera, int x0, int y0, int x1, int y1) { queued = new GuiState(state); }
        public void entity(State state, float scale, org.joml.Vector3f offset, org.joml.Quaternionf rotation, org.joml.Quaternionf camera, int x0, int y0, int x1, int y1) { queued = new GuiState(state); }
    }
    public static final class Baked { public void render(Graphics graphics, PreviewRenderer.PreviewRequest request) {} }
    public interface PipState { int x0(); int x1(); int y0(); int y1(); float scale(); }
    public record GuiState(State renderState) implements PipState {
        public int x0() { return 0; } public int x1() { return 16; } public int y0() { return 0; } public int y1() { return 16; } public float scale() { return 1; }
        public org.joml.Vector3f translation() { return new org.joml.Vector3f(); }
        public org.joml.Quaternionf rotation() { return new org.joml.Quaternionf(); }
        public org.joml.Quaternionf overrideCameraAngle() { return null; }
    }
    public static final class GuiRenderState {}
    static void bakedBoundaries(Runnable lateBoundary, Runnable passBoundary, Runnable frameBoundary) {
        late = lateBoundary; passClose = passBoundary; frameClose = frameBoundary;
    }
    public interface Collector {
        default Collector order(int order) { return this; }
        default void submitModel(CpmBakedDisplayProbe.Model model, Object state, Pose pose,
                CpmBakedDisplayProbe.Identifier texture, int light, int overlay, int color) { CpmBakedDisplayProbe.primary(); }
        default void submitModel(CpmBakedDisplayProbe.Model model, Object state, Pose pose,
                CpmBakedDisplayProbe.Identifier texture, int light, int overlay, int color, CpmBakedDisplayProbe.Overlay crumbling) { CpmBakedDisplayProbe.primary(); }
        default void submitModel(CpmBakedDisplayProbe.Model model, Object state, Pose pose,
                CpmBakedDisplayProbe.RenderType type, int light, int overlay, int color) { CpmBakedDisplayProbe.primary(); }
        default void submitModel(CpmBakedDisplayProbe.Model model, Object state, Pose pose,
                CpmBakedDisplayProbe.RenderType type, int light, int overlay, int color, CpmBakedDisplayProbe.Overlay crumbling) { CpmBakedDisplayProbe.primary(); }
    }
    public static final class Storage implements Collector {}
    public static final class Pose {
        public void pushPose() { CpmBakedDisplayProbe.push(); }
        public void popPose() { CpmBakedDisplayProbe.pop(); }
        public void translate(float x, float y, float z) {} public void scale(float x, float y, float z) {} public void mulPose(org.joml.Quaternionfc value) {} public void rotate(org.joml.Quaternionfc value) {}
    }
    public static final class Skin { public Texture cape() { return null; } }
    public static final class Texture {}
    public static final class Dispatcher {
        public void submit(State state, CpmPreviewScopeProbe.Camera camera, double x, double y, double z, Pose pose, Collector collector) { submits++; submit.run(); }
    }
    public static final class Features {
        public Storage getSubmitNodeStorage() { return new Storage(); }
        public void renderAllFeatures() { flushes++; late.run(); }
        public void renderAllFeatures(Storage storage) { renderAllFeatures(); }
        public PreparedFrame prepareFrame(Storage storage) { return new PreparedFrame(); }
        public static void renderAllFeatures(Pass pass, PreparedFrame frame) { flushes++; late.run(); }
    }
    public static final class PreparedFrame implements AutoCloseable { public void close() { frameClose.run(); } }
    public static final class GameRenderer {
        public CpmPreviewScopeProbe.Light getLighting() { return new CpmPreviewScopeProbe.Light(); }
        public CpmPreviewScopeProbe.Light lighting() { return getLighting(); }
        public Features getFeatureRenderDispatcher() { return new Features(); }
    }
    public static final class Client {
        static Client current;
        public static Client getInstance() { return current; }
        public final GameRenderer gameRenderer = new GameRenderer();
        public CpmPreviewScopeProbe.Level level = new CpmPreviewScopeProbe.Level();
        public CpmPreviewScopeProbe.Avatar player;
        public boolean isSameThread() { return true; }
    }
    public static final class LegacyTexture { public int getWidth(int mip) { return 16; } public int getHeight(int mip) { return 16; } }
    public interface GpuTexture { int getWidth(int mip); int getHeight(int mip); }
    public static final class TextureView {}
    public interface Device { Encoder createCommandEncoder(); }
    public interface Encoder { Pass createRenderPass(java.util.function.Supplier<String> name, TextureView color, java.util.Optional<?> clear, TextureView depth, java.util.OptionalDouble clearDepth); }
    public interface Pass extends AutoCloseable { void close(); }
    public static final class RenderSystem {
        public static TextureView outputColorTextureOverride;
        public static TextureView outputDepthTextureOverride;
        public static org.joml.Matrix4fStack getModelViewStack() { return new org.joml.Matrix4fStack(16); }
        public static Device getDevice() { return () -> (name, color, clear, depth, clearDepth) -> () -> passClose.run(); }
        public static void bindDefaultUniforms(Pass pass) {}
    }
}
