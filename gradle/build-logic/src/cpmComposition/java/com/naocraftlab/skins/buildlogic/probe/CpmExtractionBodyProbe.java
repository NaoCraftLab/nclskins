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
import java.util.Optional;
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
    private static boolean galleryImageJoin;
    private static boolean cpmImageJoin;

    static void cpmImage(PreviewRenderer.PreviewAppearance appearance, com.naocraftlab.skins.runtime.GalleryNativePassFixture gallery) throws Exception {
        galleryImageJoin=true;cpmImageJoin=true;
        try { bodyProducer(appearance,() -> {},() -> {},gallery); }
        finally { galleryImageJoin=false;cpmImageJoin=false; }
    }

    public static void main(String[] args) throws Exception {
        if (!java.util.Arrays.equals(args, new String[]{"gallery-image"})) throw new IllegalArgumentException("gallery-image");
        galleryImageJoin = true;
        var image = new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image,"PNG",bytes);
        var appearance = new PreviewRenderer.PreviewAppearance(
                new com.naocraftlab.skins.client.TextureRegistry.TextureHandle("fixture:join-default",64,64),
                com.naocraftlab.skins.client.SkinModel.CLASSIC, Optional.empty(), PreviewRenderer.CapeMode.OFF,
                com.naocraftlab.skins.client.OuterLayerVisibility.allVisible());
        try (var gallery = new com.naocraftlab.skins.runtime.GalleryNativePassFixture(appearance,bytes.toByteArray())) {
            bodyProducer(appearance, () -> {}, () -> {}, gallery);
        }
    }
    private static final String PREFIX = "com.naocraftlab.skins.compat.client.identifier.extraction.";
    private static final String PIP = "net.minecraft.client.gui.render.pip.PictureInPictureRenderer";
    private static final String GUI = "net.minecraft.client.gui.render.pip.GuiEntityRenderer";
    private static final String FRAME = "net.minecraft.client.gui.render.GuiRenderer";
    private static Consumer<PreviewRenderer.PreviewAppearance> submit = ignored -> {};
    private static Runnable late = () -> {};
    private static Runnable passClose = () -> {};
    private static Runnable frameClose = () -> {};
    private static Runnable afterInner = () -> {};
    private static Consumer<GuiState> inner = ignored -> {};
    private static Pose innerPose;
    private static Storage innerStorage;
    private static java.util.function.Function<Object, Avatar> stateFactory;
    private static int submits;
    private static int flushes;
    private static int frameOpens;
    private static int frameCloses;
    public static boolean cached;
    private static Consumer<GuiState> leasedPrepare;
    private static final java.util.List<Runnable> fences = new java.util.ArrayList<>();
    private static final java.util.List<TextureView> materials = new java.util.ArrayList<>();
    private static TextureView lastMaterial;
    static Runnable blitBoundary = () -> {};
    public static java.util.function.Predicate<Avatar> measurement = ignored -> false;
    public static java.util.function.LongSupplier galleryClock = System::nanoTime;
    static final Map<java.util.UUID, Object> measurementPlayers = new HashMap<>();
    static AutoCloseable measurementScope() throws Exception {
        if (!Boolean.getBoolean("nclskins.cpm.nativeMeasurement")) return () -> {};
        return (AutoCloseable)Class.forName("com.naocraftlab.skins.buildlogic.probe.CpmNativeMeasurementBackend").getMethod("scope").invoke(null);
    }
    private static Consumer<Object[]> retainedPrepare;
    private static Consumer<Object[]> retainedInner;
    private static Consumer<Object[]> retainedFrame;
    private static Consumer<Object> retainedDraw;
    private static Runnable framePreparation = () -> {};
    private static Runnable consumerDraw = () -> {};
    private static Object sharedBackend;
    private static boolean shareBackend;
    private static boolean consumerFinished;
    static Consumer<ClassNode> rendererOperands = ignored -> {};
    private static boolean requireConsumerFence;
    private static int fenceFailures;


    static void bodyProducer(PreviewRenderer.PreviewAppearance appearance, Runnable binding, Runnable cleanupFailure, com.naocraftlab.skins.runtime.GalleryNativePassFixture gallery) throws Exception {
        if (Boolean.getBoolean("nclskins.cpm.nativeMeasurement"))
            Class.forName("com.naocraftlab.skins.buildlogic.probe.CpmNativeMeasurementBackend").getMethod("install").invoke(null);
        if (CpmExtractionBodyProbe.class.getClassLoader().getResource(PREFIX.replace('.', '/') + "AvatarPreviewContext.class") == null) return;
        PreviewModelFrames.install(value -> {
            var frame = com.naocraftlab.skins.compat.cpm.CpmFrame.open(value);
            frameOpens++;

            return new PreviewModelFrames.Lease() {
                @Override public PreviewModelFrames.Body body() { return frame.body(); }
                @Override public void close() { try { frame.close(); } finally { frameCloses++; } }
            };
        });
        var types = nativeTypes();
        var loader = new Loader(types);
        runBodyProducer(appearance, binding, cleanupFailure, gallery, loader);
    }

    static Map<String, Class<?>> nativeTypes() {
        var types = new HashMap<String, Class<?>>();
        types.put("net/minecraft/client/Minecraft", Client.class);
        types.put("net/minecraft/client/Options", Options.class);
        types.put("net/minecraft/client/multiplayer/ClientPacketListener", Connection.class);
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
        types.put("com/mojang/blaze3d/pipeline/RenderTarget", MainRenderTarget.class);
        types.put("com/mojang/blaze3d/textures/GpuTextureView", TextureView.class);
        types.put("com/mojang/renderpearl/api/textures/GpuTexture", GpuTexture.class);
        types.put("com/mojang/renderpearl/api/textures/GpuTextureView", TextureView.class);
        types.put("com/mojang/renderpearl/api/device/GpuDevice", Device.class);
        types.put("com/mojang/renderpearl/api/commands/CommandEncoder", Encoder.class);
        bindRenderPassLayout(types);
        types.put("com/mojang/renderpearl/api/commands/RenderPass", Pass.class);
        types.put("com/mojang/blaze3d/systems/GpuDevice", LegacyDevice.class);
        types.put("com/mojang/blaze3d/systems/CommandEncoder", LegacyEncoder.class);
        types.put("com/mojang/blaze3d/GpuFormat", Format.class);
        types.put("com/mojang/blaze3d/textures/TextureFormat", Format.class);
        types.put("net/minecraft/client/renderer/MultiBufferSource$BufferSource", CpmBakedDisplayProbe.Buffers.class);
        types.put("net/minecraft/client/renderer/RenderBuffers", RenderBuffers.class);
        types.put("com/mojang/renderpearl/api/GpuFormat", Format.class);
        types.put("com/mojang/renderpearl/api/textures/FilterMode", Filter.class);
        types.put("com/mojang/renderpearl/api/textures/GpuSampler", Sampler.class);
        types.put("com/mojang/renderpearl/api/buffers/GpuBufferSlice", BufferSlice.class);
        types.put("com/mojang/blaze3d/textures/FilterMode", Filter.class);
        types.put("com/mojang/blaze3d/textures/GpuSampler", Sampler.class);
        types.put("com/mojang/blaze3d/systems/SamplerCache", SamplerCache.class);
        types.put("com/mojang/blaze3d/pipeline/RenderPipeline", Pipeline.class);
        types.put("com/mojang/renderpearl/api/pipeline/RenderPipeline", Pipeline.class);
        types.put("net/minecraft/client/renderer/RenderPipelines", Pipelines.class);
        types.put("net/minecraft/client/gui/GuiPipeline", Pipeline.class);
        types.put("net/minecraft/client/gui/render/TextureSetup", TextureSetup.class);
        types.put("net/minecraft/client/renderer/state/gui/BlitRenderState", Blit.class);
        types.put("net/minecraft/client/gui/navigation/ScreenRectangle", Rectangle.class);
        types.put("net/minecraft/client/renderer/Projection", Projection.class);
        types.put("net/minecraft/client/renderer/ProjectionMatrixBuffer", ProjectionBuffer.class);
        types.put("com/mojang/blaze3d/buffers/GpuBufferSlice", BufferSlice.class);
        types.put("com/mojang/blaze3d/ProjectionType", ProjectionType.class);
        types.put("net/minecraft/util/profiling/Profiler", Profiler.class);
        types.put("net/minecraft/util/profiling/ProfilerFiller", ProfilerFiller.class);
        types.put("net/minecraft/client/renderer/StagedVertexBuffer", VertexBuffer.class);
        types.put("net/minecraft/SharedConstants", Constants.class);
        types.put("net/minecraft/client/renderer/state/gui/PanoramaRenderState", Panorama.class);
        types.put("net/minecraft/client/renderer/CubeMap", CubeMap.class);
        types.put("com/naocraftlab/skins/runtime/CapeProjection", AccessoryProjection.class);
        return types;
    }

    private static void runBodyProducer(PreviewRenderer.PreviewAppearance appearance, Runnable binding,
            Runnable cleanupFailure, com.naocraftlab.skins.runtime.GalleryNativePassFixture gallery, Loader loader) throws Exception {
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
                .filter(method -> method.getName().equals("nclskins$nativeRenderToTexture")).findFirst().orElseThrow();
        nativeGui.setAccessible(true);
        Object nativeGuiOwner = nativeGuiType.getConstructors()[0].getParameterCount() == 1
                ? nativeGuiType.getConstructor(Dispatcher.class).newInstance(new Dispatcher())
                : nativeGuiType.getConstructor(CpmBakedDisplayProbe.Buffers.class, Dispatcher.class).newInstance(new CpmBakedDisplayProbe.Buffers(), new Dispatcher());
        boolean deferred = wrapper.getParameterCount() == 4;
        Object outerOwner = create(loader.loadClass(PREFIX + "mixin.PictureInPictureRendererMixin"));
        Method outer = java.util.Arrays.stream(outerOwner.getClass().getDeclaredMethods())
                .filter(method -> method.getName().equals("nclskins$withDepthScope")).findFirst().orElseThrow();
        if (outer != null) outer.setAccessible(true);
        Object nativePipOwner = deferred ? create(loader.loadClass(PIP)) : loader.loadClass(PIP).getConstructor(CpmBakedDisplayProbe.Buffers.class).newInstance(new CpmBakedDisplayProbe.Buffers());
        if (deferred) set(nativePipOwner, "submitNodeStorage", new Storage());
        Method nativePrepare = java.util.Arrays.stream(nativePipOwner.getClass().getDeclaredMethods()).filter(m -> m.getName().equals("nclskins$nativePrepare")).findFirst().orElseThrow();
        var previousClient = Client.current;
        var client = new Client();
        client.player = new CpmPreviewScopeProbe.Avatar(client.level);
        Client.current = client;
        retainedPrepare = args -> {
            Operation<Void> operation = original -> {
                Object owner = args[0];
                if (shareBackend) { if (sharedBackend == null) sharedBackend = owner; owner = sharedBackend; }
                callPrepare(nativePrepare, owner, original); return null;
            };
            callOuter(outer, outerOwner, args[1], args[2], deferred ? args[3] : new Features(), args[args.length - 1], operation);
        };
        retainedInner = args -> {
            Operation<Void> operation = original -> { call(nativeGui, args[0], original); return null; };
            call(wrapper, wrapperOwner, deferred ? new Object[]{args[1],args[2],args[3],operation} : new Object[]{args[1],args[2],operation});
        };
        var constructor = contextType.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        int[] notifications = {0};
        try {
            for (String scenario : galleryImageJoin ? new String[0] : new String[]{"success", "late-binding", "inner-handled", "layer-handled", "late-failure", "cleanup-failure", "retire", "pass-close", "frame-close", "combined-failure", "ordinary", "source-mismatch", "model-mismatch", "cached", "recursive", "different-context", "replacement"}) {
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
                submit = ignored -> {
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
                    Pose pose = innerPose == null ? new Pose() : innerPose;
                    Storage storage = innerStorage == null ? new Storage() : innerStorage;
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
                    Operation<Void> continuation = args -> {
                        check(args[0] == gui && ((Integer) args[args.length - 1]) == 1, "outer wrapper preserves state and gui scale");
                        callPrepare(nativePrepare, nativePipOwner, args);
                        return null;
                    };
                    callOuter(outer, outerOwner, gui, new GuiRenderState(), new Features(), 1, continuation);
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
                    callOuter(outer, outerOwner, gui, new GuiRenderState(), new Features(), 1, (Operation<Void>) args -> { callPrepare(nativePrepare, nativePipOwner, args); return null; });
                    check(notifications[0] == before, "equivalent frames do not create body publication churn");
                    if (deferred) {
                        cached = true;
                        int priorSubmits = submits;
                        callOuter(outer, outerOwner, gui, new GuiRenderState(), new Features(), 1, (Operation<Void>) args -> { callPrepare(nativePrepare, nativePipOwner, args); return null; });
                        check(submits == priorSubmits && session.body().current().getAsBoolean(),
                                "cached pass may retain only the already-confirmed same-context current display");
                        cached = false;
                    }
                    try (var occupied = new ExactLocalPlayerScope(client.player, appearance)) {
                        try {
                            callOuter(outer, outerOwner, gui, new GuiRenderState(), new Features(), 1, (Operation<Void>) args -> { callPrepare(nativePrepare, nativePipOwner, args); return null; });
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
            callOuter(outer, outerOwner, ordinary, new GuiRenderState(), new Features(), 1, (Operation<Void>) args -> { calls[0]++; return null; });
            check(calls[0] == 1, "non-owned continuation executes exactly once");
            submit = ignored -> {}; late = () -> {}; passClose = () -> {}; frameClose = () -> {}; afterInner = () -> {}; cached = false;
            frameOpens = 0; frameCloses = 0;
            callOuter(outer, outerOwner, ordinary, new GuiRenderState(), new Features(), 1, (Operation<Void>) args -> { callPrepare(nativePrepare, nativePipOwner, args); return null; });
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
                    var appearanceField = contextType.getDeclaredField("appearance"); appearanceField.setAccessible(true);
                    ((Avatar) state).appearance = (PreviewRenderer.PreviewAppearance) appearanceField.get(context);
                    return (Avatar) state;
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            };
            if (Boolean.getBoolean("nclskins.sourceReplacement")) {
                submit = ignored -> {}; cached = false;
                CpmSourceReplacementProbe.Material[] consumed = {null};
                consumerControl("normal");
                try {
                    CpmSourceReplacementProbe.run(renderer, new Graphics(), gallery,
                            () -> { Graphics.states.clear(); materials.clear(); consumed[0] = null; },
                            () -> {
                                check(Graphics.states.size() == 1, "SOURCE1 exactly one selected native extraction");
                                try {
                                    consumerFrame(loader, () -> callOuter(outer, outerOwner, Graphics.states.get(0), new GuiRenderState(), new Features(), 1,
                                            (Operation<Void>) args -> { callPrepare(nativePrepare, nativePipOwner, args); return null; }), () -> {
                                        check(materials.size() == 1 && !materials.get(0).texture.closed, "SOURCE1 own extracted material survives GUI consumption");
                                        var own = (PreviewRenderer.PreviewAppearance) materials.get(0).texture.data;
                                        consumed[0] = new CpmSourceReplacementProbe.Material(own.skin().location(), own.model());
                                        consumerSubmitted();
                                    });
                                    signalConsumers();
                                } catch (Exception failure) { throw new AssertionError(failure); }
                            }, () -> Graphics.states.size() == 1 && Graphics.states.get(0).x1() < 0, () -> consumed[0],
                            () -> { client.level = new CpmPreviewScopeProbe.Level(); client.player = new CpmPreviewScopeProbe.Avatar(client.level); },
                            () -> client.player = new CpmPreviewScopeProbe.Avatar(client.level));
                } finally { rendererType.getMethod("close").invoke(renderer); signalConsumers(); resetConsumerControl(); }
                return;
            }
            if (galleryImageJoin) {
                submit = ignored -> {};
                nativeFlickerMaterials(rendererType, renderer, gallery, loader, state -> callOuter(outer, outerOwner, state,
                        new GuiRenderState(), new Features(), 1, (Operation<Void>) args -> { callPrepare(nativePrepare, nativePipOwner, args); return null; }), true);
                return;
            }
            var render = rendererType.getMethod("render", Graphics.class, PreviewRenderer.PreviewRequest.class);
            var body = rendererType.getMethod("body");
            var prepareBody = rendererType.getMethod("prepareBody", String.class, boolean.class);
            var request = new PreviewRenderer.PreviewRequest(appearance, 0, 0, 160, 180, 0, 0, 1, PreviewRenderer.PreviewIntent.EDITOR_DRAFT);
            materials.clear();
            Class<?> frameType = loader.loadClass(deferred ? PREFIX + "mixin.GuiRendererMixin" : "com.naocraftlab.skins.compat.client.identifier.preview.mixin.GuiRendererMixin");
            Runnable renderFrame = () -> {
                try { consumerFrame(loader, framePreparation, consumerDraw); }
                catch (RuntimeException | Error failure) { throw failure; }
                catch (Exception failure) { throw new AssertionError(failure); }
            };
            submit = ignored -> binding.run(); late = () -> {}; passClose = () -> {}; frameClose = () -> {}; afterInner = () -> {}; cached = false;
            prepareBody.invoke(renderer, "fixture:producer-render", false);
            if (Boolean.getBoolean("nclskins.cpm.fitReference") || CpmExtractionBodyProbe.class.getClassLoader().getResource("nclskins.preview-fit.mixins.json") != null) {
                Object fitOwner=create(rendererType);
                for(var field:rendererType.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object value=switch(field.getName()) {
                        case "session" -> new com.naocraftlab.skins.client.EditorPreviewSession();
                        case "gallerySlots" -> new java.util.LinkedHashMap<>();
                        case "previewProfile" -> new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"NCLFitControl");
                        case "bakedRenderer" -> new Baked();
                        default -> field.get(renderer);
                    };
                    field.set(fitOwner,value);
                }
                @SuppressWarnings("unchecked") var fittingRenderer = (PreviewRenderer<Graphics>) fitOwner;
                var preparation = fittingRenderer.prepareModelFit("fixture:producer-render", appearance);
                check(preparation.isPresent(), "reference actual native renderer admits ordinary model-fit preparation");
                var fit = preparation.orElseThrow();
                check(fit.current() && !fit.measure().isDone(), "ordinary fit waits for the actual deferred native geometry pass");
                fit.close();
                check(!fit.current(), "native fit retirement revokes admission before native work");
            }
            render.invoke(renderer, new Graphics(), request);
            check(session.body().status() == PreviewRenderer.BodyStatus.ORDINARY, "actual extraction renderer enqueue does not report displayed READY");
            Consumer<GuiState> execute = state -> {

                Consumer<GuiState> pass = queued -> callOuter(outer, outerOwner, queued, new GuiRenderState(), new Features(), 1,
                        (Operation<Void>) args -> { callPrepare(nativePrepare, nativePipOwner, args); return null; });
                leasedPrepare = pass;
                try {
                    framePreparation = () -> pass.accept(state);
                    consumerDraw = () -> {
                        for (var material : materials) check(!material.texture.closed && material.texture.data != null, "queued native material survives logical retirement until GUI draw");
                    };
                    renderFrame.run();
                    var submitted = new java.util.ArrayList<>(fences); fences.clear();
                    submitted.forEach(Runnable::run);
                    materials.clear();
                } catch (Exception failure) { throw new AssertionError(failure); }
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
            {
                Object galleryRenderer = create(rendererType);
                var gallerySession = new EditorPreviewSession();
                for (var field : rendererType.getDeclaredFields()) {
                    field.setAccessible(true);
                    if (field.getName().equals("closed")) continue;
                    field.set(galleryRenderer, field.getName().equals("session") ? gallerySession
                            : field.getName().equals("gallerySlots") ? new java.util.LinkedHashMap<>() : field.get(renderer));
                }
                @SuppressWarnings("unchecked") var production = (PreviewRenderer<Graphics>) galleryRenderer;
                var selected = gallery.preview();
                check(selected.bodySource().orElseThrow().role() == com.naocraftlab.skins.runtime.ViewSpec.BodyRole.GALLERY_MAIN,
                        "real Gallery producer owns main body source");
                var callback = gallery.observeBody(selected, production);
                production.observeBody(callback);
                check(production.prepareGalleryBody(gallery.runtime.previewBodySource(selected), false, gallery.runtime.galleryPreviewAdmission(selected)), "representative Gallery opt-in");
                gallerySession.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
                check(gallery.displayed() == PreviewRenderer.DisplayKind.UNCONFIRMED, "Gallery prepared READY is not display");
                gallery.layersVisible(false);
                submit = ignored -> binding.run();
                check(gallery.request().appearance().skin().equals(appearance.skin()) && gallery.request().appearance().model() == appearance.model(), "Gallery request keeps native binding skin/model");
                var galleryPrevious = Graphics.queued;
                render.invoke(galleryRenderer, new Graphics(), gallery.request());
                check(Graphics.queued != galleryPrevious, "Gallery renderer must enqueue its own new state");
                check(gallery.displayed() == PreviewRenderer.DisplayKind.UNCONFIRMED, "Gallery enqueue is not completed pass");
                gallery.layersVisible(false);
                execute.accept(Graphics.queued);
                check(gallery.displayed() == PreviewRenderer.DisplayKind.CPM, "Gallery projection -> compiled renderer -> native pass -> session callback -> GalleryFlow CPM: renderer=" + production.displayKind() + ", session=" + gallerySession.body().status() + ", gallery=" + gallery.displayed());
                submit = ignored -> {};
                render.invoke(galleryRenderer, new Graphics(), gallery.request()); execute.accept(Graphics.queued);
                check(gallery.displayed() == PreviewRenderer.DisplayKind.ORDINARY, "Gallery ordinary native pass differs from CPM");
                gallery.layersVisible(true);
                var metadataSource = gallery.runtime.previewBodySource(gallery.preview());
                nativeMetadataRebuild(rendererType, galleryRenderer, gallery, execute);
                check(metadataSource.equals(gallery.runtime.previewBodySource(gallery.preview())), "favorite source continuity");
                render.invoke(galleryRenderer, new Graphics(), gallery.request()); execute.accept(Graphics.queued);
                check(gallery.displayed() == PreviewRenderer.DisplayKind.ORDINARY,
                        "same admitted favorite -> durable readback -> source -> actual native pass -> callback");
                System.out.println("PASS admitted favorite durable readback joins same native source/body/callback without busy");
                var coherentPredecessor = production.body();
                check(coherentPredecessor.current().getAsBoolean(), "coherent tracer starts after actual ordinary native pass");
                render.invoke(galleryRenderer, new Graphics(), gallery.request());
                var oldB = Graphics.queued;
                gallery.selectC();
                var successor = gallery.runtime.galleryPreviewCandidate().orElseThrow();
                check(!successor.bodySource().equals(selected.bodySource()), "real selection creates C source");
                var next = gallery.observeBody(successor, production);
                production.observeBody(next);
                check(production.prepareGalleryBody(gallery.runtime.previewBodySource(successor), false, gallery.runtime.galleryPreviewAdmission(successor)), "representative successor opt-in");
                check(coherentPredecessor.current().getAsBoolean(),
                        "coherent tracer: prepareBody successor must retain predecessor drawing lease until actual successor publication");
                execute.accept(oldB);
                check(gallery.displayed() == PreviewRenderer.DisplayKind.UNCONFIRMED, "queued old B cannot publish into C");
                gallery.layersVisible(true);
                submit = ignored -> binding.run();
                render.invoke(galleryRenderer, new Graphics(), gallery.request()); execute.accept(Graphics.queued);
                check(gallery.displayed() == PreviewRenderer.DisplayKind.CPM, "C obtains its own native display");
                gallery.layersVisible(false);
                callback.accept(PreviewRenderer.BodyStatus.ORDINARY);
                gallery.runtime.invalidatePreviewBody(callback);
                check(gallery.displayed() == PreviewRenderer.DisplayKind.CPM, "old callback/release cannot retire successor");
                check(gallery.runtime.snapshot().editor().isEmpty(), "Gallery callback never targets Editor");
                var controller = (com.naocraftlab.skins.client.OuterLayerVisibilityController) loader.loadClass(
                        "com.naocraftlab.skins.compat.client.identifier.IdentifierOuterLayerVisibilityController").getConstructor().newInstance();
                gallery.layerController = controller;
                submit = ignored -> {};
                render.invoke(galleryRenderer, new Graphics(), gallery.request()); execute.accept(Graphics.queued);
                gallery.layersVisible(true);
                int selectedWrites = Client.current.options.saves;
                for (String action : new String[]{"gallery.outer_layer.head","gallery.inspect.none"}) {
                    var retainedViews = gallery.coherentAction(action);
                    var desired = gallery.runtime.galleryPreviewCandidate().orElseThrow();
                    var ownCallback = gallery.observeBody(desired, production);
                    production.observeBody(ownCallback);
                    check(production.prepareGalleryBody(gallery.runtime.previewBodySource(desired),false, gallery.runtime.galleryPreviewAdmission(desired)), "mask/inspect native preparation");
                    render.invoke(galleryRenderer,new Graphics(),gallery.request());
                    retainedViews.run();
                    execute.accept(Graphics.queued);
                    check(gallery.preview().bodySource().equals(desired.bodySource()), "mask/inspect commits only own completed native pass");
                    check(gallery.displayed() == PreviewRenderer.DisplayKind.ORDINARY, "mask/inspect actual ordinary callback");
                }
                check(gallery.preview().outerLayerVisibility().visible(com.naocraftlab.skins.client.OuterLayerPart.HEAD), "selected C changes its own none-visible draft");
                System.out.println("PASS same-ID mask/inspection -> retained subscribers -> own compiled native pass -> callback commit");
                check(Client.current.options.saves == selectedWrites, "selected C does not write Options");
                gallery.fallback();
                gallery.layersVisible(true);
                check(!gallery.runtime.view(854,480,0,0).widget("gallery.outer_layer.head").orElseThrow().activationEnabled(),
                        "fallback successor retains composition without old layer authority");
                var preservedProviders = gallery.runtime.snapshot().providers();
                for (boolean connected : new boolean[]{false,true}) {
                    Client.current.connection = connected ? new Connection() : null;
                    for (String group : new String[]{"head","body","legs"}) for (boolean reverse : new boolean[]{false,true}) {
                        var external = reverse ? com.naocraftlab.skins.client.OuterLayerVisibility.noneVisible()
                                : com.naocraftlab.skins.client.OuterLayerVisibility.allVisible();
                        controller.applyDurable(external);
                        var currentPreview = gallery.runtime.galleryPreviewCandidate().orElseThrow();
                        var layerCallback = gallery.observeBody(currentPreview, production);
                        production.observeBody(layerCallback);
                        check(production.prepareGalleryBody(gallery.runtime.previewBodySource(currentPreview), false, gallery.runtime.galleryPreviewAdmission(currentPreview)),
                                "fresh fallback Options projection uses its own Gallery candidate session");
                        render.invoke(galleryRenderer, new Graphics(), gallery.request()); execute.accept(Graphics.queued);
                        gallery.layersVisible(true);
                        int saves = Client.current.options.saves, broadcasts = Client.current.options.broadcasts;
                        boolean cape = Client.current.options.isModelPartEnabled(CpmPreviewScopeProbe.Part.CAPE);
                        String hand = Client.current.options.mainHand;
                        gallery.runtime.dispatchWidget("gallery.outer_layer." + group, reverse,
                                com.naocraftlab.skins.runtime.InteractionOrigin.KEYBOARD);
                        gallery.verifyCycle(group,external,reverse);
                        check(Client.current.options.saves == saves + 1, "one native controller Options save");
                        check(Client.current.options.broadcasts == broadcasts + (connected ? 1 : 0), "ordinary connected broadcast only");
                        check(cape == Client.current.options.isModelPartEnabled(CpmPreviewScopeProbe.Part.CAPE)
                                && hand.equals(Client.current.options.mainHand), "cape and main hand untouched");
                        check(gallery.runtime.snapshot().providers().equals(preservedProviders), "layer action preserves provider observations/delivery/revisions");
                        check(gallery.runtime.view(854,480,0,0).widget("gallery.done").isPresent(), "direct Options stays clean");
                    }
                }
                System.out.println("PASS joined actual ordinary pass -> Gallery visible action -> compiled native controller: selected draft; connected/worldless fresh forward/reverse six Options flags, cape/main hand preserved; synthetic Options/network terminals");
                gallery.verifyTeardown = () -> {
                    gallery.runtime.closeScreen();
                    next.accept(PreviewRenderer.BodyStatus.READY);
                    try { check(gallery.displayed() == PreviewRenderer.DisplayKind.UNCONFIRMED, "closed Gallery rejects callback"); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                    production.observeBody(ignored -> {});
                    System.out.println("PASS joined Gallery screen close rejects late callback after shared-source controls finish");
                };
                System.out.println("PASS joined Gallery projection/native pass/session callback/GalleryFlow: prepared-only, enqueue-only, CPM, ordinary, queued B after C, replacement/old retirement");
            }
            Method closeRenderer = rendererType.getMethod("close");
            for (String scenario : new String[]{"normal", "superseded-before-completion", "draw-failure", "reentrant-close", "shared-backend", "fence-failure", "owned-close"}) {
                try (var subject = gallery.fresh()) {
                    Object nativeRenderer = create(rendererType);
                    for (var field : rendererType.getDeclaredFields()) {
                        field.setAccessible(true);
                        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                        if (field.getName().equals("closed")) continue;
                        field.set(nativeRenderer, switch (field.getName()) {
                            case "session" -> new EditorPreviewSession();
                            case "gallerySlots" -> new java.util.LinkedHashMap<>();
                            case "gallery", "activeGallerySource" -> null;
                            default -> field.get(renderer);
                        });
                    }
                    @SuppressWarnings("unchecked") var target = (PreviewRenderer<Graphics>) nativeRenderer;
                    var a = subject.preview();
                    String sourceA = subject.runtime.previewBodySource(a);
                    target.observeBody(subject.observeBody(a, target));
                    check(target.prepareGalleryBody(sourceA, false, subject.runtime.galleryPreviewAdmission(a)), "actual A source preparation");
                    var requestA = subject.request();
                    TextureView[] queued = new TextureView[2];
                    Object[] expected = new Object[2];
                    BooleanSupplier[] valid = new BooleanSupplier[1];
                    submit = ignored -> binding.run(); materials.clear(); fences.clear(); consumerFinished = false;
                    shareBackend = scenario.equals("shared-backend"); sharedBackend = null; requireConsumerFence = true;
                    fenceFailures = scenario.equals("fence-failure") ? 1 : 0;
                    framePreparation = () -> {
                        target.renderGallery(new Graphics(), sourceA, requestA);
                        GuiState stateA = Graphics.queued;
                        callOuter(outer, outerOwner, stateA, new GuiRenderState(), new Features(), 1,
                                (Operation<Void>) args -> { callPrepare(nativePrepare, nativePipOwner, args); return null; });
                        valid[0] = target.body().current();
                        check(valid[0].getAsBoolean(), "A source is genuinely displayed before replacement");
                        queued[0] = materials.get(materials.size() - 1); expected[0] = ((Avatar) stateA.renderState()).appearance;
                        subject.selectC();
                        var b = subject.runtime.galleryPreviewCandidate().orElseThrow();
                        target.observeBody(subject.observeBody(b, target));
                        String sourceB = subject.runtime.previewBodySource(b);
                        check(!sourceA.equals(sourceB) && target.prepareGalleryBody(sourceB, false, subject.runtime.galleryPreviewAdmission(b)), "real selection owns distinct B source");
                        var requestB = subject.request();
                        check(!requestA.appearance().outerLayerVisibility().equals(requestB.appearance().outerLayerVisibility()), "actual A/B producer materials are distinguishable");
                        target.renderGallery(new Graphics(), sourceB, requestB);
                        GuiState stateB = Graphics.queued;
                        callOuter(outer, outerOwner, stateB, new GuiRenderState(), new Features(), 1,
                                (Operation<Void>) args -> { callPrepare(nativePrepare, nativePipOwner, args); return null; });
                        queued[1] = materials.get(materials.size() - 1); expected[1] = ((Avatar) stateB.renderState()).appearance;
                        check(!valid[0].getAsBoolean(), "logical A source invalidates immediately after B completion");
                        check(fences.isEmpty() && !queued[0].texture.closed, "no fence or physical close before actual consumer");
                    };
                    consumerDraw = () -> {
                        check(queued[0].texture.data.equals(expected[0]), "queued A material survives actual B prepare");
                        check(queued[1].texture.data.equals(expected[1]), "queued B material is its actual producer payload");
                        check(queued[0] != queued[1] && queued[0].texture != queued[1].texture, "native selected material storage is isolated");
                        if (scenario.equals("reentrant-close")) call(closeRenderer, nativeRenderer);
                        check(!queued[0].texture.closed && !queued[1].texture.closed, "reentrant retirement cannot close pending physical reads");
                        consumerFinished = true;
                        if (scenario.equals("draw-failure")) throw new IllegalStateException("synthetic consumer failure");
                    };
                    Throwable failure = null;
                    try { renderFrame.run(); } catch (RuntimeException | Error thrown) { failure = thrown; }
                    if (queued[0] == null || queued[1] == null) throw new AssertionError("native material prerequisite failed", failure);
                    if (scenario.equals("owned-close")) call(closeRenderer, nativeRenderer);
                    else target.prepareBody("fixture:retire-after-frame", false);
                    check(!queued[0].texture.closed && !queued[1].texture.closed && !fences.isEmpty(), "fence creation is not signal or early close");
                    var completions = new java.util.ArrayList<>(fences); fences.clear(); completions.forEach(Runnable::run);
                    check(queued[0].texture.closes == 1, "signal releases retired A exactly once");
                    if (!shareBackend) check(queued[1].texture.closes == 1, "signal releases B exactly once");
                    if (shareBackend) check(failure instanceof AssertionError && failure.getMessage().equals("queued A material survives actual B prepare") && failure.getSuppressed().length == 0,
                            "same-backend sensitivity must fail the exact A material oracle: " + failure);
                    else if (scenario.equals("draw-failure")) check(failure instanceof IllegalStateException && failure.getMessage().equals("synthetic consumer failure"), "consumer exception preserved");
                    else if (scenario.equals("fence-failure")) check(failure instanceof IllegalStateException && failure.getMessage().equals("synthetic fence enqueue failure"), "fence exception preserved and close retries owned consumers");
                    else check(failure == null, "native material/lifetime positive: " + failure);
                    if (scenario.equals("normal")) {
                        nativeRetainedFailure(rendererType, nativeRenderer, subject, execute, false);
                        nativeAccessories(rendererType, nativeRenderer, subject);
                        try (var rebound = subject.fresh()) {
                            nativeRetainedFailure(rendererType, nativeRenderer, rebound, execute, true);
                        }
                    }
                    if (scenario.equals("superseded-before-completion")) nativeSupersededCandidate(rendererType, nativeRenderer, subject, execute);
                    System.out.println("PASS retained native material/consumer " + scenario + (shareBackend ? " EXPECTED_ASSERTION_RED" : ""));
                } finally { shareBackend = false; sharedBackend = null; requireConsumerFence = false; materials.clear(); }
            }
            nativeFlickerMaterials(rendererType, renderer, gallery, loader, state -> callOuter(outer, outerOwner, state,
                    new GuiRenderState(), new Features(), 1, (Operation<Void>) args -> { callPrepare(nativePrepare, nativePipOwner, args); return null; }));
            nativeAccessoryFeedback(rendererType, renderer, gallery, execute, false);
            nativeAccessoryFeedback(rendererType, renderer, gallery, execute, true);
            nativeAccessoryFeedback(rendererType, renderer, gallery, execute, true, true);
        } finally {
            submit = ignored -> {}; late = () -> {}; passClose = () -> {}; frameClose = () -> {}; afterInner = () -> {}; inner = ignored -> {};
            Client.current = previousClient;
            stateFactory = null;
            Graphics.queued = null;
            PreviewModelFrames.install(com.naocraftlab.skins.compat.cpm.CpmFrame::open);
        }
        System.out.println("PASS actual extraction producer: genuine target native method bytecode + production wrappers/context/scope + genuine CPM primary binding; synthetic host/GPU terminals, not game acceptance");
    }

    private static void nativeRetainedFailure(Class<?> type, Object owner,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture subject, Consumer<GuiState> execute, boolean lateCapture) throws Exception {
        @SuppressWarnings("unchecked") var target = (PreviewRenderer<Graphics>) owner;
        var imageSession = type.getDeclaredField("session"); imageSession.setAccessible(true);
        materials.clear();
        submit = ignored -> {};
        var a = subject.runtime.galleryPreviewCandidate().orElseThrow();
        String sourceA = subject.runtime.previewBodySource(a);
        target.observeBody(subject.observeBody(a, target));
        var requestA = subject.request();
        check(target.prepareGalleryImage(sourceA,false, subject.runtime.galleryPreviewAdmission(a)), "N2 actual A admission");
        target.renderGallery(new Graphics(),sourceA,subject.request()); execute.accept(Graphics.queued);
        check(((EditorPreviewSession) imageSession.get(owner)).imageVisible() && subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,
                "N2 extraction hidden A readiness precedes visible acknowledgement");
        target.renderGallery(new Graphics(),sourceA,requestA); execute.accept(Graphics.queued);
        check(target.body().current().getAsBoolean(), "N2 own successful A native material prerequisite");
        check(subject.displayed()==PreviewRenderer.DisplayKind.ORDINARY,"N2 extraction A is acknowledged before replacement");
        target.renderGallery(new Graphics(),sourceA,subject.request());
        GuiState oldA = Graphics.queued;
        if (a.presetId().equals(subject.runtime.snapshot().activePresetId())) subject.selectC();
        else subject.selectB();
        var b = subject.runtime.galleryPreviewCandidate().orElseThrow();
        String sourceB = subject.runtime.previewBodySource(b);
        check(!sourceA.equals(sourceB), "N2 real selection creates pending successor");
        target.observeBody(subject.observeBody(b, target));
        check(target.prepareGalleryImage(sourceB,false, subject.runtime.galleryPreviewAdmission(b)), "N2 B native admission without completion");
        var field = type.getDeclaredField("session"); field.setAccessible(true);
        var successor = (EditorPreviewSession)field.get(owner);
        int[] accessoryPublications = {0};
        var requestB = subject.request();
        var inputB = subject.runtime.galleryAccessoryAdmission(b);
        var commonObserver = subject.runtime.observePreviewAccessories(b, () -> target.galleryAccessories(sourceB, requestB, inputB));
        target.observeAccessories(value -> { accessoryPublications[0]++; commonObserver.accept(value); });
        if (lateCapture) {
            int queuedStart = Graphics.states.size();
            target.renderGallery(new Graphics(), sourceB, requestB);
            var hostStates = Graphics.states.subList(queuedStart, Graphics.states.size());
            check(hostStates.size() == 2 && hostStates.get(0).x0() >= 0 && hostStates.get(1).x1() < 0,
                    "N2 host order emits legitimate retained A and hidden B after B observer registration");
            check(hostStates.get(0) != oldA, "N2 retained A is freshly captured after B observer registration");
            oldA = hostStates.get(0);
            check(oldA != Graphics.queued, "N2 host order captures separate retained A and hidden B states after B observer registration");
        }
        submit = ignored -> { throw new IllegalStateException("synthetic retained A failure"); };
        execute.accept(oldA);
        check(successor.path(PreviewRenderer.PreviewIntent.EDITOR_DRAFT,true) == EditorPreviewSession.Path.LIVE,
                "N2 retained A guarded failure must not disable successor B");
        check(accessoryPublications[0] == 0, "N2 retained A cannot publish into successor accessory observer lateCapture=" + lateCapture);
        submit = ignored -> {};
        target.renderGallery(new Graphics(),sourceB,subject.request()); execute.accept(Graphics.queued);
        check(((EditorPreviewSession) imageSession.get(owner)).imageVisible() && subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,
                "N2 extraction own hidden B readiness is not visible acknowledgement");
        target.renderGallery(new Graphics(),sourceB,subject.request()); execute.accept(Graphics.queued);
        check(target.body().current().getAsBoolean() && subject.displayed() == PreviewRenderer.DisplayKind.ORDINARY,
                "N2 successor obtains own successful native material after predecessor failure");
        int afterSuccess = accessoryPublications[0];
        submit = ignored -> { throw new IllegalStateException("synthetic retired A failure"); };
        execute.accept(oldA);
        check(successor.path(PreviewRenderer.PreviewIntent.EDITOR_DRAFT,true) == EditorPreviewSession.Path.LIVE
                && accessoryPublications[0] == afterSuccess, "N2 retired A failure is a source-local no-op");
        submit = ignored -> {};
        if (lateCapture) {
            target.renderGallery(new Graphics(), sourceB, requestB);
            submit = ignored -> { throw new IllegalStateException("synthetic own B failure"); };
            execute.accept(Graphics.queued);
            check(accessoryPublications[0] > afterSuccess, "N2 own B failure must notify its registered B observer");
            submit = ignored -> {};
        }
        System.out.println("PASS N2 retained A guarded native failure -> source-local owner -> unchanged B LIVE/accessories -> own B actual material lateCapture=" + lateCapture);
    }

    private static Object create(Class<?> type) throws Exception {
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true); return constructor.newInstance();
    }

    private static void nativeFlickerMaterials(Class<?> rendererType, Object template,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture original, ClassLoader loader,
            Consumer<GuiState> prepare) throws Exception {
        boolean imagePolicy = java.util.Arrays.stream(rendererType.getDeclaredMethods()).anyMatch(method -> method.getName().equals("prepareGalleryImage"));
        if (imagePolicy) {
            boolean previous = galleryImageJoin;
            galleryImageJoin = true;
            try { nativeFlickerMaterials(rendererType, template, original, loader, prepare, true); }
            finally { galleryImageJoin = previous; }
        } else {
            nativeFlickerMaterials(rendererType, template, original, loader, prepare, false);
            nativeFlickerMaterials(rendererType, template, original, loader, prepare, true);
        }
    }

    private static void nativeFlickerMaterials(Class<?> rendererType, Object template,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture original, ClassLoader loader,
            Consumer<GuiState> prepare, boolean atomicCape) throws Exception {
        var previousClock=galleryClock;
        long[] imageTime={System.nanoTime()};
        galleryClock=()->imageTime[0];
        try (var subject = cpmImageJoin ? original.freshOwnedCpm() : original.freshOwned();
             var profiles = cpmImageJoin ? CpmBehaviorProbe.nativeProfileScope() : (AutoCloseable)() -> {}) {
            if (atomicCape) subject.localCapes();
            var definitions=new java.util.ArrayList<AutoCloseable>();
            Object renderer = create(rendererType);
            for (var field : rendererType.getDeclaredFields()) {
                field.setAccessible(true);
                if (field.getName().equals("closed")) continue;
                field.set(renderer, field.getName().equals("session") ? new EditorPreviewSession(galleryClock)
                        : field.getName().equals("gallerySlots") ? new java.util.LinkedHashMap<>() : field.get(template));
            }
            Class<?> hostType = CpmPreviewScreenSlice.loadNativeLifecycle(PREFIX + "NclSkinsScreen", rendererType);
            var registry = new CpmOwnedPreviewDispatchProbe.Registry();
            try (var skins = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry, com.naocraftlab.skins.client.TextureRegistry.TextureKind.PLAYER_SKIN, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
                 var capes = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry, com.naocraftlab.skins.client.TextureRegistry.TextureKind.IMAGE, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
                Object host = hostType.getConstructor().newInstance();
                for (var field : hostType.getDeclaredFields()) {
                    Object value = switch (field.getName()) {
                        case "runtime" -> subject.runtime;
                        case "editorRenderer" -> renderer;
                        case "skinTextures" -> skins;
                        case "capeTextures" -> capes;
                        case "textureRegistry" -> registry;
                        case "scrollController" -> new CpmOwnedPreviewDispatchProbe.ScrollTerminal();
                        case "focusRequests" -> new com.naocraftlab.skins.runtime.FocusRequestLedger();
                        case "destination" -> com.naocraftlab.skins.client.ScreenDestination.GALLERY;
                        case "nativeDispatchDepth" -> 1;
                        default -> Map.class.isAssignableFrom(field.getType()) ? new HashMap<>()
                                : java.util.List.class.isAssignableFrom(field.getType()) ? new java.util.ArrayList<>() : null;
                    };
                    if (value != null) field.set(host, value);
                }
                var nativeHost = (CpmOwnedPreviewDispatchProbe.ScreenTerminal) host;
                nativeHost.width = 854; nativeHost.height = 480;
                nativeHost.font = new CpmOwnedPreviewDispatchProbe.FontTerminal();
                nativeHost.minecraft = new CpmOwnedPreviewDispatchProbe.ClientTerminal();
                nativeHost.minecraft.screen = nativeHost;
                Method init = hostType.getDeclaredMethod("init"); init.setAccessible(true);
                Method draw = hostType.getDeclaredMethod("drawPreviews", Graphics.class, com.naocraftlab.skins.runtime.ViewSpec.class); draw.setAccessible(true);
                try {
                    submit = ignored -> {};
                    AccessoryProjection.cape = false; AccessoryProjection.elytra = false;
                    call(init, host); subject.drainWorkers();
                    Method request = hostType.getDeclaredMethod("galleryRequest", com.naocraftlab.skins.runtime.ViewSpec.Preview.class);
                    request.setAccessible(true);
                    Method sync = hostType.getDeclaredMethod("syncPreviewAssets", com.naocraftlab.skins.runtime.ViewSpec.class); sync.setAccessible(true);
                    Method skinKey = hostType.getDeclaredMethod("skinKey", com.naocraftlab.skins.runtime.ViewSpec.Preview.class); skinKey.setAccessible(true);
                    if (atomicCape) {
                        call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                        call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                    }
                    var ownCapeHandles = new java.util.HashSet<com.naocraftlab.skins.client.TextureRegistry.TextureHandle>();
                    var joinedSources = new java.util.HashSet<String>();
                    PreviewRenderer.PreviewAppearance lastShownImage = null;
                    for (String action : cpmImageJoin ? new String[]{"initial", "gallery.card.00000000-0000-0000-0000-000000000323"}
                            : galleryImageJoin ? new String[]{"initial", "gallery.card.00000000-0000-0000-0000-000000000323", "gallery.outer_layer.head"}
                            : atomicCape ? new String[]{"initial", "gallery.card.00000000-0000-0000-0000-000000000323"}
                            : new String[]{"initial", "gallery.card.00000000-0000-0000-0000-000000000323", "gallery.outer_layer.head", "gallery.inspect.none", "repeat", "clean-repeat"}) {
                        var before = subject.preview();
                        String beforeSource = subject.runtime.previewBodySource(before);
                        if (!action.equals("initial")) {
                            if (action.equals("repeat") || action.equals("clean-repeat")) subject.selectC();
                            else subject.runtime.dispatchWidget(action);
                        }
                        if (atomicCape && !galleryImageJoin && !action.equals("initial")) {
                            var pending = subject.runtime.galleryPreviewCandidate().orElseThrow();
                            Object ownSkinKey = call(skinKey,host,pending);
                            call(sync,host,subject.runtime.view(854,480,0,0));
                            while (skins.handle(ownSkinKey).isEmpty() && subject.runNextWorker()) {}
                            check(skins.handle(ownSkinKey).isPresent(), "ATOMIC_CAPE own B player ready prerequisite");
                            @SuppressWarnings("unchecked") var unresolved = (java.util.Optional<PreviewRenderer.PreviewRequest>) call(request,host,pending);
                            check(unresolved.isEmpty(), "ATOMIC_CAPE_PENDING_B_MUST_NOT_BORROW_A: own B player ready, own B cape unresolved");
                            Graphics.states.clear(); materials.clear();
                            call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                            var retainedStates = new java.util.ArrayList<>(Graphics.states);
                            check(retainedStates.size() == 1, "ATOMIC_CAPE only retained A submits while B cape pending");
                            consumerFrame(loader, () -> retainedStates.forEach(prepare), () ->
                                    check(materials.size() == 1 && !materials.get(0).texture.closed
                                                    && materials.get(0).texture.data.equals(((Avatar)retainedStates.get(0).renderState()).appearance)
                                                    && ((Avatar)retainedStates.get(0).renderState()).appearance.cape().filter(ownCapeHandles::contains).isPresent(),
                                            "ATOMIC_CAPE retained A own material remains live"));
                            signalConsumers(); Graphics.states.clear(); materials.clear();
                            check(subject.preview().equals(before), "ATOMIC_CAPE pending B cannot common ACK");
                            subject.drainWorkers(); call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                        }
                        if (galleryImageJoin && !action.equals("initial")) {
                            call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                            call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                        }
                        var desired = subject.runtime.galleryPreviewCandidate().orElseThrow();
                        if (!action.equals("initial")) check(subject.preview().equals(before), "FLICKER retained common projection before own candidate pass");
                        @SuppressWarnings("unchecked") var projected = (java.util.Optional<PreviewRenderer.PreviewRequest>) call(request, host, desired);
                        var own = projected.orElseThrow();
                        if (atomicCape) {
                            var cape = own.appearance().cape().orElseThrow();
                            if (!galleryImageJoin || !action.equals("gallery.outer_layer.head"))
                                check(ownCapeHandles.add(cape), "ATOMIC_CAPE A and B carry distinct own cape handles");
                            check(own.appearance().capeValidity().isValid(), "ATOMIC_CAPE own captured cape validity");
                            check(registry.registered.containsKey(cape), "ATOMIC_CAPE native request uses actual registered cape");
                        }
                        check(java.util.Arrays.equals(registry.registered.get(own.appearance().skin()), subject.ownedBytes(desired)), "FLICKER own loaded bitmap before native pass");
                        boolean expectedCpm=cpmImageJoin && action.equals("initial");
                        check(desired.bodySource().orElseThrow().cpmExpected()==expectedCpm,"JOIN actual imported source determines CPM/ordinary expectation");
                        if(expectedCpm) {
                            definitions.add(prepareOwnedFeedback(subject,host,rendererType,renderer,own.appearance().skin()));
                            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
                            boolean prepared=false;
                            while(System.nanoTime()<deadline) {
                                CpmBehaviorProbe.drain();subject.drainWorkers();
                                Graphics.states.clear();Graphics.strata.clear();materials.clear();
                                call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                                Object fitting=ownedField(renderer,"nativeFit");
                                if(fitting instanceof PreviewRenderer.ModelFitPreparation current && current.current()
                                        && subject.runtime.previewBodySource(desired).equals(ownedField(fitting,"source"))
                                        && ownedField(fitting,"fit")!=null) {prepared=true;break;}
                                var numericStates=new java.util.ArrayList<>(Graphics.states);
                                if(!numericStates.isEmpty()) {
                                    consumerControl("normal");
                                    consumerFrame(loader,()->numericStates.forEach(prepare),CpmExtractionBodyProbe::consumerSubmitted);
                                    signalConsumers();
                                }
                                Object operation=ownedField(subject.runtime,"numericFitOperation");
                                Object settled=operation==null?null:((java.util.concurrent.CompletableFuture<?>)ownedField(operation,"result")).getNow(null);
                                check(settled==null || ((com.naocraftlab.skins.runtime.PreviewAssets.NumericFitResult)settled).value()!=null,
                                        "JOIN own numeric preparation requires genuine captured geometry before image material");
                                Thread.sleep(1);
                            }
                            check(prepared && subject.numericCreates==1 && subject.durableNumericFit().status()
                                            ==com.naocraftlab.skins.runtime.PreviewModelFitStore.Status.PRESENT,
                                    "JOIN actual numeric preparation persists its own native measurement before hidden image");
                        } else {
                            submit=ignored -> {};
                            Graphics.states.clear();Graphics.strata.clear();materials.clear();
                            call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                        }
                        var states = new java.util.ArrayList<>(Graphics.states);
                        check(!states.isEmpty(), "FLICKER actual compiled host emits material input");
                        System.out.println("TRACE FLICKER action=" + action + " queued=" + states.size()
                                + " sourceChanged=" + !beforeSource.equals(subject.runtime.previewBodySource(desired))
                                + " imageRevisionChanged=" + !before.imageRevision().equals(desired.imageRevision())
                                + " bitmapChanged=" + !before.skin().equals(desired.skin()));
                        if (galleryImageJoin) {
                            check(joinedSources.add(subject.runtime.previewBodySource(desired)), "JOIN A/B/C are distinct actual semantic source demands");
                            var sessionField = rendererType.getDeclaredField("session"); sessionField.setAccessible(true);
                            var imageSession = (EditorPreviewSession)sessionField.get(renderer);
                            check(!imageSession.imageVisible(), "JOIN actual native source begins hidden");
                            if (action.equals("initial")) {
                                check(!imageSession.imageSpinner(), "JOIN actual host/native source has no immediate spinner");
                                imageTime[0]+=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(299);
                                check(!imageSession.imageSpinner(), "JOIN actual pending source stays spinner-free before 300ms");
                                imageTime[0]+=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1);
                                check(imageSession.imageSpinner(), "JOIN actual pending own source delays spinner until 300ms");
                                call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                                check(imageSession.imageSpinner(), "JOIN host frame/stage does not reset 300ms");
                            }
                            var consumed = new java.util.IdentityHashMap<GuiState, TextureView>();
                            consumerControl("normal");
                            var joinOrder = sdkTraversal(states,true,false);
                            consumerFrame(loader, () -> joinOrder.forEach(state -> {
                                int count = materials.size(); prepare.accept(state);
                                if (materials.size() > count) consumed.put(state,materials.get(materials.size()-1));
                            }), () -> {
                                for (var state : states) {
                                    var material = consumed.get(state);
                                    check(material != null && !material.texture.closed
                                                    && ((Avatar)state.renderState()).appearance.equals(material.texture.data),
                                            "JOIN hidden producer reaches its own actual body/cape material consumer");
                                }
                                consumerSubmitted();
                            });
                            signalConsumers();
                            var retainedStates=states.stream().filter(state -> state.x0()<854 && state.x1()>0).toList();
                            check(retainedStates.size()==(action.equals("initial")?0:1),
                                    "JOIN_GALLERY_PENDING_IMAGE_RETAINED: first open has no predecessor; successor retains exactly one coherent A");
                            if(!retainedStates.isEmpty()) {
                                var retained=((Avatar)retainedStates.get(0).renderState()).appearance;
                                check(retained.skin().equals(lastShownImage.skin()) && retained.model()==lastShownImage.model()
                                                && retained.cape().equals(lastShownImage.cape()) && retained.capeMode()==lastShownImage.capeMode()
                                                && retained.outerLayerVisibility().equals(lastShownImage.outerLayerVisibility())
                                                && retained.nativeAccessories()==lastShownImage.nativeAccessories() && retained.capeHasElytra()==lastShownImage.capeHasElytra(),
                                        "JOIN retained visible predecessor consumes its exact last completed whole recipe, never pending B");
                            }
                            check(states.stream().filter(state -> state.x1()<0).count()==1,"JOIN own pending B remains a separate hidden preparation");
                            check(subject.displayed() == PreviewRenderer.DisplayKind.UNCONFIRMED,
                                    "JOIN hidden material/fence must not grant common ordinary controls");
                            Graphics.states.clear(); materials.clear();
                            call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                            var visible = new java.util.ArrayList<>(Graphics.states);
                            check(visible.stream().filter(state -> state.x0() < 854 && state.x1() > 0).count() == 1,
                                    "JOIN own hidden material must progress to an actually visible native consumer");
                            consumerControl("normal");
                            var visibleOrder = sdkTraversal(visible,true,false);
                            boolean[] prematureAck = {false};
                            consumerFrame(loader, () -> visibleOrder.forEach(prepare), () -> {
                                check(materials.size() == visible.size(), "JOIN visible own native material");
                                try { prematureAck[0] = subject.displayed() != PreviewRenderer.DisplayKind.UNCONFIRMED; }
                                catch (Exception failure) { throw new AssertionError(failure); }
                                consumerSubmitted();
                            });
                            signalConsumers();
                            check(!prematureAck[0], "JOIN_VISIBLE_ACK_AFTER_CONSUMER: visible prepare must remain provisional before GUI consumption/fence");
                            check(subject.preview().bodySource().equals(desired.bodySource())
                                            && subject.displayed() == (expectedCpm ? PreviewRenderer.DisplayKind.CPM : PreviewRenderer.DisplayKind.ORDINARY),
                                    "JOIN visible own native consumer admits correct common CPM/ordinary ACK");
                            check(imageSession.imageVisible() && !imageSession.imageSpinner(), "JOIN own fence ends pending spinner immediately");
                            lastShownImage=((Avatar)visible.stream().filter(state->state.x0()<854 && state.x1()>0).findFirst().orElseThrow().renderState()).appearance;
                            subject.layersVisible(!expectedCpm);
                            if (action.equals("initial") && !cpmImageJoin) {
                                String stableSource = subject.runtime.previewBodySource(desired);
                                var stableBody = ((PreviewRenderer<?>)renderer).body();
                                subject.favoriteDisplayed();
                                check(imageSession.imageVisible() && !imageSession.imageSpinner()
                                                && stableSource.equals(subject.runtime.previewBodySource(subject.runtime.galleryPreviewCandidate().orElseThrow())),
                                        "JOIN admitted metadata writer/durable regroup does not reset actual image attempt");
                                check(stableBody.current().getAsBoolean(), "JOIN admitted metadata write retains current body authority before next frame");
                                Graphics.states.clear(); materials.clear();
                                call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                                var metadataStates = new java.util.ArrayList<>(Graphics.states);
                                check(metadataStates.stream().filter(state -> state.x0() < 854 && state.x1() > 0).count() == 1,
                                        "JOIN metadata-only frame remains renderable");
                                consumerControl("normal");
                                consumerFrame(loader, () -> metadataStates.forEach(prepare), CpmExtractionBodyProbe::consumerSubmitted);
                                signalConsumers();
                                check(((PreviewRenderer<?>)renderer).displayKind() == PreviewRenderer.DisplayKind.ORDINARY
                                                && subject.displayed() == PreviewRenderer.DisplayKind.ORDINARY,
                                        "JOIN metadata-only own material/control authority retained");
                            }
                            System.out.println("PASS JOIN actual Gallery owned source/body/cape -> hidden consumer/fence -> visible consumer -> common ACK/controls action=" + action);
                            continue;
                        }
                        var ordered = sdkTraversal(states, true, false);
                        check(sdkTraversal(states, false, false).equals(states), "SDK no-prior submission order control");
                        check(sdkTraversal(states, true, true).equals(states), "SDK nonoverlap-prior submission order control");
                        var consumed = new java.util.IdentityHashMap<GuiState, TextureView>();
                        consumerFrame(loader, () -> ordered.forEach(state -> {
                            int count = materials.size();
                            System.out.println("TRACE SDK prepare submissionIndex=" + states.indexOf(state)
                                    + " current=" + capturedCurrent(state) + " commonSourceIsDesired="
                                    + subject.runtime.previewBodySource(subject.preview()).equals(subject.runtime.previewBodySource(desired)));
                            prepare.accept(state);
                            if (materials.size() > count) consumed.put(state, materials.get(materials.size()-1));
                        }), () -> {
                            int visible = 0;
                            for (var state : states) {
                                var material = consumed.get(state);
                                var expected = ((Avatar)state.renderState()).appearance;
                                boolean onScreen = state.x0() < 854 && state.x1() > 0;
                                boolean ownMaterial = material != null && !material.texture.closed && expected.equals(material.texture.data);
                                System.out.println("TRACE SDK GUI submissionIndex=" + states.indexOf(state) + " onScreen=" + onScreen
                                        + " ownMaterial=" + ownMaterial + " x0=" + state.x0() + " x1=" + state.x1() + " scissor=" + state.scissorArea());
                                if (onScreen && ownMaterial) visible++;
                                if (material != null) check(ownMaterial, "SDK consumer must retain each emitted own material through successor ACK");
                            }
                            check(visible == 1, "SDK_CAUSAL_VISIBLE_MATERIAL: expected exactly one own on-screen body, actual=" + visible);
                            check(consumed.size() == states.size(), "SDK every actual captured body reaches its own material consumer");
                        });
                        signalConsumers(); materials.clear();
                        check(subject.preview().bodySource().equals(desired.bodySource()) && subject.displayed() == PreviewRenderer.DisplayKind.ORDINARY,
                                "FLICKER own ordinary candidate pass/common ACK");
                        System.out.println("PASS FLICKER actual loaded ordinary material action=" + action);
                        if (action.equals("clean-repeat")) check(beforeSource.equals(subject.runtime.previewBodySource(desired)),
                                "CLEAN_REPEAT_IDENTITY: own unchanged displayed material must not create another BODY source");
                    }
                    if(cpmImageJoin) return;
                    if (galleryImageJoin) {
                        call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                        call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                        int uploads = registry.registered.size();
                        var stablePreview = subject.runtime.galleryPreviewCandidate().orElseThrow();
                        var stableCape = capes.capeState(subject.runtime.previewCapeSurfaceKey(stablePreview),stablePreview.capeId().orElseThrow()).handle();
                        for (int mode = 0; mode < 8; mode++) {
                            var previousMode = subject.runtime.galleryPreviewCandidate().orElseThrow().capeMode();
                            subject.runtime.dispatchWidget("gallery.preview_mode");
                            var changed = subject.runtime.galleryPreviewCandidate().orElseThrow();
                            check(changed.capeMode() != previousMode, "JOIN retained mode control really changes request");
                            call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                            @SuppressWarnings("unchecked") var modeRequest = (Optional<PreviewRenderer.PreviewRequest>)call(request,host,changed);
                            check(modeRequest.orElseThrow().appearance().cape().equals(stableCape), "JOIN mode preserves its own prepared cape handle");
                            for (int phase = 0; phase < 2; phase++) {
                                Graphics.states.clear(); materials.clear();
                                call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                                var modeStates = new java.util.ArrayList<>(Graphics.states);
                                final boolean hidden = phase == 0;
                                check(modeStates.size()==(hidden?2:1)
                                                && modeStates.stream().filter(state->state.x1()<0).count()==(hidden?1:0),
                                        "JOIN mode retained predecessor plus separate hidden successor, then only visible own successor");
                                if(hidden)check(((Avatar)modeStates.get(0).renderState()).appearance.capeMode()==previousMode
                                                && ((Avatar)modeStates.get(1).renderState()).appearance.capeMode()==changed.capeMode(),
                                        "JOIN mode retained and hidden consumers keep independent exact cape recipes");
                                consumerControl("normal");
                                consumerFrame(loader, () -> modeStates.forEach(prepare), () -> {
                                    check(materials.size() == modeStates.size() && materials.stream().allMatch(material -> !material.texture.closed),
                                            "JOIN each mode phase consumes own leased material");
                                    consumerSubmitted();
                                });
                                signalConsumers();
                            }
                            check(subject.preview().bodySource().equals(changed.bodySource()) && subject.displayed() == PreviewRenderer.DisplayKind.ORDINARY,
                                    "JOIN each mode successor receives own visible common ACK");
                            check(registry.registered.size() == uploads, "JOIN mode retains actual prepared working set without uploads");
                            System.out.println("PASS JOIN Gallery mode-cache retained own cape/material hidden/visible/common ACK mode=" + mode);
                        }
                        subject.selectB(); call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                        call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                        var held = subject.runtime.galleryPreviewCandidate().orElseThrow();
                        String heldSource = subject.runtime.previewBodySource(held);
                        Graphics.states.clear(); materials.clear();
                        call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                        var heldStates = new java.util.ArrayList<>(Graphics.states);
                        consumerControl("normal");
                        consumerFrame(loader, () -> heldStates.forEach(prepare), CpmExtractionBodyProbe::consumerSubmitted);
                        var heldMaterial = materials.get(materials.size()-1);
                        check(!heldMaterial.texture.closed, "JOIN held B material is leased before its fence");
                        subject.selectC(); call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                        call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                        var terminal = subject.runtime.galleryPreviewCandidate().orElseThrow();
                        check(!heldSource.equals(subject.runtime.previewBodySource(terminal)), "JOIN actual B to C replacement has distinct source");
                        Graphics.states.clear();
                        call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                        var terminalStates = new java.util.ArrayList<>(Graphics.states);
                        var sessionField = rendererType.getDeclaredField("session"); sessionField.setAccessible(true);
                        var terminalSession = (EditorPreviewSession)sessionField.get(renderer);
                        check(!heldMaterial.texture.closed, "JOIN logical B retirement cannot close its physical material before fence");
                        signalConsumers();
                        check(heldMaterial.texture.closes == 1 && !terminalSession.imageVisible()
                                        && subject.displayed() == PreviewRenderer.DisplayKind.UNCONFIRMED,
                                "JOIN stale B fence releases exactly once without C image/controls ACK");
                        materials.clear();
                        consumerControl("normal");
                        check(heldStates.stream().filter(state->state.x1()<0).count()==1,"JOIN captured own hidden B is separate from retained visible A");
                        consumerFrame(loader, () -> heldStates.stream().filter(state->state.x1()<0).forEach(prepare), CpmExtractionBodyProbe::consumerSubmitted);
                        signalConsumers();
                        check(materials.isEmpty() && !terminalSession.imageVisible(), "JOIN retired hidden B native work cannot borrow C producer or material");
                        consumerControl("normal");
                        Throwable terminalFailure = null;
                        try { consumerFrame(loader, () -> terminalStates.forEach(prepare), () -> {
                            consumerSubmitted();
                            throw new IllegalStateException("synthetic joined Gallery consumer failure");
                        }); } catch (RuntimeException | Error failure) { terminalFailure = failure; }
                        signalConsumers();
                        check(terminalFailure instanceof IllegalStateException
                                        && terminalFailure.getMessage().equals("synthetic joined Gallery consumer failure")
                                        && terminalFailure.getSuppressed().length == 0,
                                "JOIN exact terminal consumer failure is preserved with clean teardown");
                        check(!terminalSession.imageVisible() && !terminalSession.imagePreparing() && !terminalSession.imageSpinner()
                                        && subject.displayed() == PreviewRenderer.DisplayKind.UNCONFIRMED,
                                "JOIN terminal C cannot display, spin forever or grant ordinary controls");
                        Graphics.states.clear();
                        call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                        check(Graphics.states.isEmpty(), "JOIN terminal source has no ambient retry");
                        subject.selectB(); call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                        call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                        Graphics.states.clear(); materials.clear();
                        call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                        var closingStates = new java.util.ArrayList<>(Graphics.states);
                        check(!closingStates.isEmpty(), "JOIN new semantic source after terminal gets its own producer");
                        consumerControl("normal");
                        consumerFrame(loader, () -> closingStates.forEach(prepare), CpmExtractionBodyProbe::consumerSubmitted);
                        var closingMaterial = materials.get(materials.size()-1);
                        var closingSession = (EditorPreviewSession)sessionField.get(renderer);
                        subject.runtime.closeScreen(); rendererType.getMethod("close").invoke(renderer);
                        check(!closingMaterial.texture.closed, "JOIN close retains own queued physical consumer until fence");
                        signalConsumers();
                        check(closingMaterial.texture.closes == 1 && !closingSession.imageVisible() && !closingSession.imageSpinner(),
                                "JOIN close rejects late material/ACK and releases exact once");
                        System.out.println("PASS JOIN Gallery B->C stale fence/work, terminal consumer/no-retry, legitimate replacement and close/fenced exact-once retirement");
                        return;
                    }
                    if (atomicCape) {
                        var coldThumbnailStates = new java.util.ArrayList<String>();
                        for (var thumbnail : subject.runtime.view(854,480,0,0).backEquipmentPreviews()) {
                            coldThumbnailStates.add(thumbnail.id() + "=" + capes.capeState(
                                    com.naocraftlab.skins.runtime.ViewHostPolicy.cardCapeKey(thumbnail), thumbnail.capeId()).status());
                        }
                        call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                        call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                        var preparedThumbnails = new java.util.HashMap<String,com.naocraftlab.skins.client.TextureRegistry.TextureHandle>();
                        for (var thumbnail : subject.runtime.view(854,480,0,0).backEquipmentPreviews()) {
                            var key = com.naocraftlab.skins.runtime.ViewHostPolicy.cardCapeKey(thumbnail);
                            preparedThumbnails.put(key,capes.capeState(key,thumbnail.capeId()).handle().orElseThrow());
                        }
                        check(!preparedThumbnails.isEmpty(), "MODE_CACHE real cape-only set is prepared before reuse assertions");
                        System.out.println("TRACE MODE_CACHE post-B first-load prerequisites=" + coldThumbnailStates);
                        var stable = subject.runtime.galleryPreviewCandidate().orElseThrow();
                        var stableKey = subject.runtime.previewCapeSurfaceKey(stable);
                        var stableCape = capes.capeState(stableKey,stable.capeId().orElseThrow()).handle();
                        int registrations = registry.registered.size();
                        var baselineRegistrations = new java.util.HashSet<>(registry.registered.keySet());
                        for (int i = 0; i < 8; i++) {
                            subject.runtime.dispatchWidget("gallery.preview_mode");
                            var changed = subject.runtime.galleryPreviewCandidate().orElseThrow();
                            check(changed.capeMode() != stable.capeMode(), "MODE_CACHE actual enabled mode changes request");
                            call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                            @SuppressWarnings("unchecked") var changedRequest = (java.util.Optional<PreviewRenderer.PreviewRequest>)call(request,host,changed);
                            check(changedRequest.orElseThrow().appearance().cape().equals(stableCape), "MODE_CACHE compiled retain/request preserves own cape handle");
                            Graphics.states.clear(); materials.clear();
                            call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                            var states = new java.util.ArrayList<>(Graphics.states);
                            var ordered = sdkTraversal(states,true,false);
                            consumerFrame(loader, () -> ordered.forEach(prepare), () ->
                                    check(materials.size() == states.size(), "MODE_CACHE every own mode pass reaches material"));
                            signalConsumers(); Graphics.states.clear(); materials.clear();
                            check(subject.preview().bodySource().equals(changed.bodySource()), "MODE_CACHE actual mode pass/common ACK");
                            var addedRegistrations = new java.util.HashSet<>(registry.registered.keySet());
                            addedRegistrations.removeAll(baselineRegistrations);
                            var addedCapeRoles = new java.util.ArrayList<String>();
                            for (var thumbnail : subject.runtime.view(854,480,0,0).backEquipmentPreviews()) {
                                var thumbnailKey = com.naocraftlab.skins.runtime.ViewHostPolicy.cardCapeKey(thumbnail);
                                var thumbnailState = capes.capeState(thumbnailKey, thumbnail.capeId());
                                check(thumbnailState.handle().equals(java.util.Optional.ofNullable(preparedThumbnails.get(thumbnailKey))),
                                        "MODE_CACHE actual prepared cape-only entry retains its original handle");
                                if (thumbnailState.handle().filter(addedRegistrations::contains).isPresent()) addedCapeRoles.add(thumbnail.id());
                            }
                            check(registry.registered.size() == registrations,
                                    "MODE_CACHE no texture reload across actual syncAssets; added=" + addedRegistrations
                                            + "; addedCapeThumbnailRoles=" + addedCapeRoles
                                            + "; mainCapeRetained=" + changedRequest.orElseThrow().appearance().cape().equals(stableCape));
                            stable = changed;
                        }
                        System.out.println("PASS ATOMIC_CAPE own imported A/B cape -> actual compiled sync/request -> retained pending A -> own B native material/GUI/common ACK; eight mode sync/request/material passes without upload");
                        var invalidate = capes.getClass().getDeclaredMethod("invalidatePrepared",Object.class); invalidate.setAccessible(true);
                        invalidate.invoke(capes,stable.capeId().orElseThrow());
                        check(capes.capeState(stableKey,stable.capeId().orElseThrow()).status()
                                        == com.naocraftlab.skins.runtime.PreviewAssetCache.CapeStatus.FAILED,
                                "ATOMIC_CAPE integrity invalidation reaches terminal FAILED, not absence");
                        @SuppressWarnings("unchecked") var failedRequest = (java.util.Optional<PreviewRenderer.PreviewRequest>)call(request,host,stable);
                        var failed = failedRequest.orElseThrow();
                        check(failed.appearance().cape().isEmpty() && !failed.appearance().nativeAccessories()
                                        && stable.capeId().isPresent(),
                                "ATOMIC_CAPE FAILED own B request has no borrowed cape and does not fabricate semantic absence");
                        Graphics.states.clear(); materials.clear();
                        call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                        var failedStates = new java.util.ArrayList<>(Graphics.states);
                        check(!failedStates.isEmpty(), "ATOMIC_CAPE FAILED still submits own B body");
                        consumerFrame(loader, () -> failedStates.forEach(prepare), () -> {
                            check(materials.size() == failedStates.size(), "ATOMIC_CAPE FAILED own B reaches native render-end material");
                            for (var state : failedStates) check(((Avatar)state.renderState()).appearance.cape().isEmpty(),
                                    "ATOMIC_CAPE FAILED captured native request remains cape-free");
                        });
                        signalConsumers(); Graphics.states.clear(); materials.clear();
                        check(subject.preview().bodySource().equals(stable.bodySource()) && subject.displayed() == PreviewRenderer.DisplayKind.ORDINARY,
                                "ATOMIC_CAPE FAILED own B completes without A or a synthetic none decision");
                        System.out.println("PASS ATOMIC_CAPE exact integrity FAILED -> own B cape-free immutable request -> native render-end/material/common ACK; semantic cape identity retained");
                    }
                } finally {
                    if (hostType.getField("subscription").get(host) instanceof com.naocraftlab.skins.runtime.ClientRuntime.Subscription owned) owned.close();
                    nativeHost.minecraft.screen = null;
                    signalConsumers();
                    rendererType.getMethod("close").invoke(renderer);
                    AccessoryProjection.cape = true; AccessoryProjection.elytra = true;
                    Graphics.states.clear(); materials.clear(); submit = ignored -> {};
                    for(int i=definitions.size()-1;i>=0;i--) definitions.get(i).close();
                }
            }
        } finally {
            galleryClock=previousClock;
        }
    }

    private static boolean capturedCurrent(GuiState state) {
        try {
            Object context = state.renderState().getClass().getMethod("nclskins$previewContext").invoke(state.renderState());
            return (boolean)context.getClass().getMethod("current").invoke(context);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static java.util.List<GuiState> sdkTraversal(java.util.List<GuiState> states, boolean prior, boolean outside) throws Exception {
        return sdkTraversal(states, prior, outside, "net.minecraft.client.renderer.state.gui.",
                state -> new int[]{state.x0(), state.y0(), state.x1(), state.y1()},
                state -> Graphics.strata.getOrDefault(state, 0), state -> state.scissorArea() == null);
    }

    static <T> java.util.List<T> sdkTraversal(java.util.List<T> states, boolean prior, boolean outside,
            String namespace, java.util.function.Function<T, int[]> bounds,
            java.util.function.ToIntFunction<T> stratumOf, java.util.function.Predicate<T> unclipped) throws Exception {
        return sdkTraversal(states, prior, outside, namespace, bounds, stratumOf, unclipped, true);
    }

    static <T> java.util.List<T> sdkTraversal(java.util.List<T> states, boolean prior, boolean outside,
            String namespace, java.util.function.Function<T, int[]> bounds,
            java.util.function.ToIntFunction<T> stratumOf, java.util.function.Predicate<T> unclipped, boolean requireAll) throws Exception {
        boolean added = namespace.equals("net.minecraft.client.renderer.state.gui.");
        check(added || namespace.equals("net.minecraft.client.gui.render.state."), "exact admitted SDK collector namespace");
        String name = namespace + "GuiRenderState";
        var resources = CpmExtractionBodyProbe.class.getClassLoader().getResources(name.replace('.', '/') + ".class");
        java.net.URL resource = null;
        while (resources.hasMoreElements()) {
            var next = resources.nextElement();
            if (next.getProtocol().equals("jar")) { resource = next; break; }
        }
        check(resource != null && resource.getProtocol().equals("jar"), "genuine SDK collector must come from its dependency JAR");
        var connection = (java.net.JarURLConnection)resource.openConnection();
        var jar = connection.getJarFileURL();
        byte[] debugFlags = sdkDebugFlags(jar, namespace);
        try (var sdk = new java.net.URLClassLoader(new java.net.URL[]{jar}, CpmExtractionBodyProbe.class.getClassLoader()) {
            @Override public java.net.URL getResource(String name) {
                return name.startsWith("net/minecraft/") ? findResource(name) : super.getResource(name);
            }
            @Override protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                if (!requested.startsWith("net.minecraft.")) return super.loadClass(requested, resolve);
                if (!(requested.startsWith(namespace) || requested.startsWith("net.minecraft.client.gui.navigation.")
                        || requested.equals("net.minecraft.SharedConstants"))) {
                    if (requested.equals("net.minecraft.client.Minecraft") || requested.equals("net.minecraft.server.Bootstrap"))
                        throw new ClassNotFoundException("SDK data-only boundary: " + requested);
                    return super.loadClass(requested, resolve);
                }
                Class<?> type = findLoadedClass(requested);
                if (type == null) type = requested.equals("net.minecraft.SharedConstants")
                        ? defineClass(requested, debugFlags, 0, debugFlags.length) : findClass(requested);
                if (resolve) resolveClass(type);
                return type;
            }
        }) {
            Class<?> collector = sdk.loadClass(name);
            check(collector.getClassLoader() == sdk && collector.getProtectionDomain().getCodeSource().getLocation().equals(jar), "SDK defining loader and CodeSource identity");
            byte[] bytes = connection.getInputStream().readAllBytes();
            check(java.util.Arrays.equals(bytes, sdk.getResourceAsStream(name.replace('.', '/') + ".class").readAllBytes()), "SDK unmodified defining class bytes");
            Class<?> constants = sdk.loadClass("net.minecraft.SharedConstants");
            check(constants.getClassLoader() == sdk && (constants.getProtectionDomain().getCodeSource() == null
                    || constants.getProtectionDomain().getCodeSource().getLocation() == null),
                    "SDK debug scalars must use only the admitted own fixture definition");
            check(!constants.getField("DEBUG_RENDER_UI_LAYERING_RECTANGLES").getBoolean(null)
                    && !constants.getField("DEBUG_SHUFFLE_UI_RENDERING_ORDER").getBoolean(null), "SDK non-debug scalar profile");
            Class<?> rectangle = sdk.loadClass("net.minecraft.client.gui.navigation.ScreenRectangle");
            var rect = rectangle.getConstructor(int.class,int.class,int.class,int.class);
            Class<?> element = sdk.loadClass(namespace + "GuiElementRenderState");
            Class<?> pip = sdk.loadClass(namespace + "pip.PictureInPictureRenderState");
            Object value = collector.getConstructor().newInstance();
            if (prior) collector.getMethod(added ? "addGuiElement" : "submitGuiElement",element).invoke(value, sdkArea(sdk,element,
                    outside ? rect.newInstance(-20,-20,10,10) : rect.newInstance(bounds.apply(states.get(0))[0]+100,bounds.apply(states.get(0))[1]+100,20,20)));
            var payloads = new java.util.IdentityHashMap<Object,T>();
            int stratum = stratumOf.applyAsInt(states.get(0));
            for (var state : states) {
                check(unclipped.test(state), "captured native PIP has no active scissor");
                int next = stratumOf.applyAsInt(state);
                while (stratum < next) { collector.getMethod("nextStratum").invoke(value); stratum++; }
                int[] area = bounds.apply(state);
                Object proxy = sdkArea(sdk,pip,area == null ? null : rect.newInstance(area[0],area[1],area[2]-area[0],area[3]-area[1]));
                check(!payloads.containsValue(state), "one exact native payload per SDK submission"); payloads.put(proxy,state);
                collector.getMethod(added ? "addPicturesInPictureState" : "submitPicturesInPictureState",pip).invoke(value,proxy);
            }
            var result = new java.util.ArrayList<T>();
            collector.getMethod("forEachPictureInPicture",Consumer.class).invoke(value,(Consumer<Object>) proxy -> {
                T payload = payloads.get(proxy); check(payload != null, "SDK traversal preserves actual captured payload reference"); result.add(payload);
            });
            check(!requireAll || result.size() == states.size(), "SDK traversal cannot lose a payload");
            System.out.println("TRACE SDK collector=" + jar + " classSHA=" + digest(bytes) + " prior=" + prior + " outside=" + outside
                    + " order=" + result.stream().map(states::indexOf).toList());
            return result;
        }
    }

    static final class SdkFrames implements AutoCloseable {
        final java.net.URLClassLoader loader;
        final Class<?> collector, rectangle, element, pip;
        final Object state;
        final java.util.IdentityHashMap<Object,Object> payloads = new java.util.IdentityHashMap<>();
        final java.util.IdentityHashMap<Object,String> labels = new java.util.IdentityHashMap<>();
        final java.util.IdentityHashMap<Object,Object> nodes = new java.util.IdentityHashMap<>();
        final java.lang.reflect.Field current;
        final String namespace = "net.minecraft.client.renderer.state.gui.";
        SdkFrames() throws Exception {
            var resource = CpmExtractionBodyProbe.class.getClassLoader().getResource(namespace.replace('.','/') + "GuiRenderState.class");
            check(resource != null && resource.getProtocol().equals("jar"), "SDK frame dependency JAR");
            var jar = ((java.net.JarURLConnection)resource.openConnection()).getJarFileURL();
            byte[] flags = sdkDebugFlags(jar, namespace);
            loader = new java.net.URLClassLoader(new java.net.URL[]{jar}, CpmExtractionBodyProbe.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.equals("net.minecraft.client.Minecraft") || name.equals("net.minecraft.server.Bootstrap"))
                        throw new ClassNotFoundException("SDK frame data-only boundary: " + name);
                    if (!(name.startsWith(namespace) || name.startsWith("net.minecraft.client.gui.navigation.") || name.equals("net.minecraft.SharedConstants")))
                        return super.loadClass(name,resolve);
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = name.equals("net.minecraft.SharedConstants") ? defineClass(name,flags,0,flags.length) : findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
            };
            collector = loader.loadClass(namespace+"GuiRenderState");
            check(collector.getClassLoader()==loader && collector.getProtectionDomain().getCodeSource().getLocation().equals(jar),"SDK frame genuine owner");
            rectangle = loader.loadClass("net.minecraft.client.gui.navigation.ScreenRectangle");
            element = loader.loadClass(namespace+"GuiElementRenderState");
            pip = loader.loadClass(namespace+"pip.PictureInPictureRenderState");
            state = collector.getConstructor().newInstance();
            current = collector.getDeclaredField("current"); current.setAccessible(true);
            System.out.println("BOUND SDK frames JAR="+jar+" collectorSHA="+digest(resource.openStream().readAllBytes()));
        }
        Object area(Class<?> type,int[] bounds) throws Exception {
            return sdkArea(loader,type,bounds==null?null:rectangle.getConstructor(int.class,int.class,int.class,int.class)
                    .newInstance(bounds[0],bounds[1],bounds[2]-bounds[0],bounds[3]-bounds[1]));
        }
        void fill(int[] bounds,String label) throws Exception {
            Object proxy=area(element,bounds); labels.put(proxy,label);
            collector.getMethod("addGuiElement",element).invoke(state,proxy);
        }
        void submit(Object payload,int[] bounds) throws Exception {
            Object proxy=area(pip,bounds); payloads.put(proxy,payload);
            collector.getMethod("addPicturesInPictureState",pip).invoke(state,proxy);
        }
        void next() throws Exception {collector.getMethod("nextStratum").invoke(state);}
        java.util.List<Object> prepareOrder() throws Exception {
            var result=new java.util.ArrayList<Object>();
            collector.getMethod("forEachPictureInPicture",Consumer.class).invoke(state,(Consumer<Object>) proxy -> {
                Object payload=payloads.get(proxy);check(payload!=null,"SDK exact payload mapping");result.add(payload);
                try {nodes.put(payload,current.get(state));}catch(IllegalAccessException failure){throw new AssertionError(failure);}
            });
            return result;
        }
        void blit(Object payload,int[] bounds,String label) throws Exception {
            Object proxy=area(element,bounds);labels.put(proxy,label);
            Object node=nodes.get(payload);check(node!=null,"SDK blit selected node");
            var add=node.getClass().getDeclaredMethod("addGuiElement",element);add.setAccessible(true);add.invoke(node,proxy);
        }
        java.util.List<String> elements() throws Exception {
            var result=new java.util.ArrayList<String>();
            Class<?> range=loader.loadClass(namespace+"GuiRenderState$TraverseRange");
            collector.getMethod("forEachElement",Consumer.class,range).invoke(state,(Consumer<Object>) proxy -> result.add(labels.get(proxy)),range.getField("ALL").get(null));
            return result;
        }
        void reset() throws Exception {collector.getMethod("reset").invoke(state);payloads.clear();labels.clear();nodes.clear();}
        public void close() throws java.io.IOException {loader.close();}
    }

    private static byte[] sdkDebugFlags(java.net.URL jar, String namespace) throws Exception {
        String owner = "net/minecraft/SharedConstants";
        var expected = java.util.Set.of("DEBUG_RENDER_UI_LAYERING_RECTANGLES", "DEBUG_SHUFFLE_UI_RENDERING_ORDER");
        var counts = new HashMap<String, Integer>();
        try (var dependency = new java.util.jar.JarFile(new java.io.File(jar.toURI()))) {
            var constants = new ClassNode();
            var constantsResource = CpmExtractionBodyProbe.class.getClassLoader().getResource(owner + ".class");
            check(constantsResource != null && constantsResource.getProtocol().equals("jar"), "SDK debug scalar declaration must come from its dependency JAR");
            try (var input = constantsResource.openStream()) {
                new ClassReader(input).accept(constants, 0);
            }
            for (String field : expected) {
                var actual = constants.fields.stream().filter(value -> value.name.equals(field)).toList();
                check(actual.size() == 1 && actual.get(0).desc.equals("Z")
                        && actual.get(0).access == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL),
                        "SDK debug scalar field ABI: " + field);
            }
            for (var entry : java.util.Collections.list(dependency.entries())) {
                String name = entry.getName();
                if (!name.endsWith(".class") || !(name.startsWith(namespace.replace('.', '/'))
                        || name.startsWith("net/minecraft/client/gui/navigation/"))) continue;
                var node = new ClassNode();
                try (var input = dependency.getInputStream(entry)) { new ClassReader(input).accept(node, 0); }
                for (var method : node.methods) for (var instruction : method.instructions) {
                    if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field && field.owner.equals(owner)) {
                        sdkDebugAccess(field.getOpcode(), field.name, field.desc);
                        check(node.name.equals(namespace.replace('.', '/') + "GuiRenderState"),
                                "SDK debug scalar collector owner");
                        counts.merge(field.name, 1, Integer::sum);
                    }
                    if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call)
                        check(!call.owner.equals(owner), "SDK data-only closure cannot invoke SharedConstants");
                }
            }
        }
        check(counts.equals(Map.of("DEBUG_RENDER_UI_LAYERING_RECTANGLES", 1, "DEBUG_SHUFFLE_UI_RENDERING_ORDER", 1)),
                "SDK debug scalar exact GETSTATIC counts");
        String own = Type.getInternalName(SdkDebugFlags.class);
        var flags = new ClassNode();
        try (var input = CpmExtractionBodyProbe.class.getClassLoader().getResourceAsStream(own + ".class")) {
            new ClassReader(input).accept(flags, 0);
        }
        check(flags.fields.size() == 2 && flags.fields.stream().allMatch(field -> expected.contains(field.name)
                && field.desc.equals("Z") && Integer.valueOf(0).equals(field.value))
                && flags.methods.stream().noneMatch(method -> method.name.equals("<clinit>")), "own SDK debug scalar false initialization");
        flags.nestHostClass = null;
        flags.nestMembers = null;
        flags.innerClasses.clear();
        var writer = new ClassWriter(0);
        flags.accept(new ClassRemapper(writer, new Remapper() {
            @Override public String map(String name) { return name.equals(own) ? owner : name; }
        }));
        return writer.toByteArray();
    }

    private static void sdkDebugAccess(int opcode, String name, String descriptor) {
        check(opcode == Opcodes.GETSTATIC && descriptor.equals("Z")
                && (name.equals("DEBUG_RENDER_UI_LAYERING_RECTANGLES") || name.equals("DEBUG_SHUFFLE_UI_RENDERING_ORDER")),
                "unadmitted SDK debug scalar access");
    }

    public static final class SdkDebugFlags {
        public static final boolean DEBUG_RENDER_UI_LAYERING_RECTANGLES = false;
        public static final boolean DEBUG_SHUFFLE_UI_RENDERING_ORDER = false;
    }

    private static Object sdkArea(ClassLoader loader, Class<?> type, Object bounds) {
        return java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{type},(proxy,method,args) -> switch(method.getName()) {
            case "bounds" -> bounds;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "captured-native-area";
            default -> throw new AssertionError("SDK area terminal: " + method.getName());
        });
    }

    private static void nativeAccessoryFeedback(Class<?> rendererType, Object template,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture original, Consumer<GuiState> execute, boolean ownedCpm) throws Exception {
        nativeAccessoryFeedback(rendererType, template, original, execute, ownedCpm, false);
    }

    private static void nativeAccessoryFeedback(Class<?> rendererType, Object template,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture original, Consumer<GuiState> execute, boolean ownedCpm, boolean competing) throws Exception {
        try (var subject = ownedCpm ? Boolean.getBoolean("nclskins.cpm.nativeMeasurement") && !competing
                ? original.freshOwnedCpm(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(System.getProperty("nclskins.aba.carrier"))))
                : original.freshOwnedCpm() : original.fresh();
             var profiles = ownedCpm ? CpmBehaviorProbe.nativeProfileScope() : (AutoCloseable) () -> {}) {
            subject.enableCpmCape();
            if (competing) subject.localCapeWinner();
            String scenario = ownedCpm ? "present" : System.getProperty("nclskins.accessoryFeedback", "present");
            AccessoryProjection.cape = !scenario.equals("absent");
            AccessoryProjection.elytra = !scenario.equals("absent");
            var environment = com.naocraftlab.skins.runtime.ClientRuntime.class.getDeclaredField("skinExtensionEnvironment"); environment.setAccessible(true);
            environment.set(subject.runtime, new com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment(1, Map.of(), !scenario.equals("cpm-off")));
            Object renderer = create(rendererType);
            for (var field : rendererType.getDeclaredFields()) {
                field.setAccessible(true);
                if (field.getName().equals("closed")) continue;
                field.set(renderer, field.getName().equals("session") ? new EditorPreviewSession()
                        : field.getName().equals("gallerySlots") ? new java.util.LinkedHashMap<>() : field.get(template));
            }
            @SuppressWarnings("unchecked") var production = (PreviewRenderer<Graphics>) renderer;
            var definitions = new java.util.ArrayList<AutoCloseable>();
            Class<?> hostType = CpmPreviewScreenSlice.loadNativeLifecycle(PREFIX + "NclSkinsScreen", rendererType);
            var registry = new CpmOwnedPreviewDispatchProbe.Registry();
            try (var skins = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry, com.naocraftlab.skins.client.TextureRegistry.TextureKind.PLAYER_SKIN, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
                 var capes = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry, com.naocraftlab.skins.client.TextureRegistry.TextureKind.IMAGE, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
                Object host = hostType.getConstructor().newInstance();
                for (var field : hostType.getDeclaredFields()) {
                    Object value = switch (field.getName()) {
                        case "runtime" -> subject.runtime;
                        case "editorRenderer" -> renderer;
                        case "skinTextures" -> skins;
                        case "capeTextures" -> capes;
                        case "textureRegistry" -> registry;
                        case "scrollController" -> new CpmOwnedPreviewDispatchProbe.ScrollTerminal();
                        case "focusRequests" -> new com.naocraftlab.skins.runtime.FocusRequestLedger();
                        case "destination" -> com.naocraftlab.skins.client.ScreenDestination.GALLERY;
                        case "nativeDispatchDepth" -> 1;
                        default -> Map.class.isAssignableFrom(field.getType()) ? new HashMap<>()
                                : java.util.List.class.isAssignableFrom(field.getType()) ? new java.util.ArrayList<>() : null;
                    };
                    if (value != null) field.set(host, value);
                }
                var nativeHost = (CpmOwnedPreviewDispatchProbe.ScreenTerminal) host;
                nativeHost.width = 854; nativeHost.height = 480;
                nativeHost.font = new CpmOwnedPreviewDispatchProbe.FontTerminal();
                nativeHost.minecraft = new CpmOwnedPreviewDispatchProbe.ClientTerminal();
                nativeHost.minecraft.screen = nativeHost;
                Method init = hostType.getDeclaredMethod("init"); init.setAccessible(true);
                Method draw = hostType.getDeclaredMethod("drawPreviews", Graphics.class, com.naocraftlab.skins.runtime.ViewSpec.class); draw.setAccessible(true);
                int[] publications = {0}; boolean[] armed = {false};
                var cached = com.naocraftlab.skins.runtime.ClientRuntime.class.getDeclaredField("galleryAdmissionSource"); cached.setAccessible(true);
                var slotsField = rendererType.getDeclaredField("gallerySlots"); slotsField.setAccessible(true);
                String[] firstSource = {null};
                PreviewRenderer.PreviewRequest[] firstRequest = {null};
                java.util.function.BooleanSupplier[] firstInput = {null};
                var bounded = subject.runtime.subscribe(ignored -> {
                    if (!armed[0]) return;
                    try {
                        String source = firstSource[0];
                        Object slot = nativeSlot(renderer,source);
                        boolean retained = false; boolean displayed = false;
                        PreviewRenderer.LiveReason rawReason = PreviewRenderer.LiveReason.LIVE_UNAVAILABLE;
                        if (slot != null) {
                            var sessionMethod = slot.getClass().getDeclaredMethod("session"); sessionMethod.setAccessible(true);
                            var galleryField = rendererType.getDeclaredField("gallery"); galleryField.setAccessible(true);
                            var owner = (EditorPreviewSession.Gallery)galleryField.get(renderer);
                            var nativeSession = (EditorPreviewSession)sessionMethod.invoke(slot);
                            retained = owner.retains(nativeSession);
                            var playerMethod = slot.getClass().getDeclaredMethod("player"); playerMethod.setAccessible(true);
                            var levelMethod = slot.getClass().getDeclaredMethod("level"); levelMethod.setAccessible(true);
                            rawReason = nativeSession.liveReason(firstRequest[0].intent(), Client.current.level != null && Client.current.player != null,
                                    com.naocraftlab.skins.client.NativePlayerSkinLifecycle.isReady(firstRequest[0].appearance().skin().location()),
                                    playerMethod.invoke(slot) != null && levelMethod.invoke(slot) == Client.current.level);
                            var shown = EditorPreviewSession.Gallery.class.getDeclaredField("displayed"); shown.setAccessible(true);
                            displayed = shown.get(owner) != null;
                        }
                        var value = production.galleryAccessories(source, firstRequest[0], firstInput[0]);
                        System.out.println("TRACE accessory feedback publication=" + (++publications[0])
                                + " availability=" + value.availability() + " generation=" + value.generation()
                                + " reason=" + value.liveReason() + " rawReason=" + rawReason + " cape=" + value.cape() + " elytra=" + value.elytra()
                                + " bodyKeyChanged=" + !source.equals(cached.get(subject.runtime))
                                + " retained=" + retained + " commonAck=" + displayed);
                        check(publications[0] <= 8, "ACCESSORY_FIXED_POINT: stable native cape/elytra metadata must converge before common ACK");
                    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                });
                try {
                    call(init, host); subject.drainWorkers();
                    check(hostType.getField("subscription").get(host) != null, "compiled host owns its actual snapshotChanged subscriber");
                    if (competing) {
                        Method sync = hostType.getDeclaredMethod("syncPreviewAssets", com.naocraftlab.skins.runtime.ViewSpec.class); sync.setAccessible(true);
                        call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                        call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                    }
                    check(subject.runtime.galleryPreviewCandidate().orElseThrow().bodySource().orElseThrow().cpmExpected() == ownedCpm,
                            "own selected carrier determines CPM expectation before metadata or display");
                    firstSource[0] = subject.runtime.previewBodySource(subject.runtime.galleryPreviewCandidate().orElseThrow());
                    if (ownedCpm) {
                        var candidate = subject.runtime.galleryPreviewCandidate().orElseThrow();
                        var handle = registry.registered.entrySet().stream().filter(e -> java.util.Arrays.equals(e.getValue(), subject.ownedBytes(candidate)))
                                .map(Map.Entry::getKey).findFirst().orElseThrow();
                        var appearance = new PreviewRenderer.PreviewAppearance(handle, com.naocraftlab.skins.client.SkinModel.CLASSIC,
                                Optional.empty(), PreviewRenderer.CapeMode.OFF, candidate.outerLayerVisibility(), candidate.capeHasElytra(), false, candidate.nativeAccessories());
                        firstRequest[0] = new PreviewRenderer.PreviewRequest(appearance, 0, 0, 160, 180, 0, 0, 1, candidate.intent());
                    } else firstRequest[0] = subject.request();
                    firstInput[0] = subject.runtime.galleryAccessoryAdmission(subject.runtime.galleryPreviewCandidate().orElseThrow());
                    if (ownedCpm) definitions.add(prepareOwnedFeedback(subject, host, rendererType, renderer, firstRequest[0].appearance().skin()));
                    armed[0] = true;
                    call(draw, host, new Graphics(), subject.runtime.view(854,480,0,0));
                    if (ownedCpm) subject.drainWorkers();
                    subject.runtime.view(854,480,0,0);
                    check(scenario.equals("cpm-off") || publications[0] > 0, "Accessory discriminator must read the prepared native candidate");
                    var p1 = subject.runtime.galleryPreviewCandidate().orElseThrow();
                    String key = subject.runtime.previewBodySource(p1);
                    boolean present = scenario.equals("present");
                    check(p1.nativeAccessories() == (present && !competing), "current recipe follows legitimate metadata/default winner, not body permission");
                    check(subject.displayed() == PreviewRenderer.DisplayKind.UNCONFIRMED, "metadata cannot acknowledge P0");
                    AccessoryProjection.forcedAvailability = com.naocraftlab.skins.client.PreviewAccessories.Availability.UNKNOWN;
                    if (ownedCpm) definitions.add(prepareOwnedFeedback(subject, host, rendererType, renderer, firstRequest[0].appearance().skin()));
                    call(draw, host, new Graphics(), subject.runtime.view(854,480,0,0));
                    if (ownedCpm) subject.drainWorkers();
                    check(key.equals(subject.runtime.previewBodySource(subject.runtime.galleryPreviewCandidate().orElseThrow())),
                            "NEXT_FRAME_FIXED_POINT: cold P1 metadata cannot revert its own recipe");
                    check(competing || !present || !subject.runtime.view(854,480,0,0).widget("gallery.preview_mode").orElseThrow().enabled(),
                            "cold P1 never borrows historical live control authority");
                    AccessoryProjection.forcedAvailability = null;
                    subject.runtime.view(854,480,0,0);
                    check(key.equals(subject.runtime.previewBodySource(subject.runtime.galleryPreviewCandidate().orElseThrow())),
                            "P1 live confirmation keeps the same full key");
                    GuiState preparedP1 = ownedCpm ? awaitOwnedPreparation(subject, host, draw, execute) : null;
                    if (ownedCpm && !competing && Boolean.getBoolean("nclskins.cpm.nativeMeasurement")) {
                        check(subject.numericCreates == 1 && subject.durableNumericFit().status()
                                        == com.naocraftlab.skins.runtime.PreviewModelFitStore.Status.PRESENT,
                                "own measured BODY creates exactly one durable numeric winner");
                        System.out.println("OWN_FIT_DURABLE firstCreates=" + subject.numericCreates
                                + " persisted=" + subject.durableNumericFit().status());
                    }
                    if (preparedP1 == null) call(draw, host, new Graphics(), subject.runtime.view(854,480,0,0));
                    if (ownedCpm) subject.drainWorkers();
                    GuiState p1State = preparedP1 == null ? Graphics.queued : preparedP1;
                    if (ownedCpm) ownedFeedbackBinding(subject, production, key, rendererType, renderer, p1State);
                    execute.accept(p1State);
                    check(subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"P1 hidden own material cannot common ACK");
                    call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));p1State=Graphics.queued;execute.accept(p1State);
                    check(production.body().current().getAsBoolean() && subject.displayed() == (ownedCpm ? PreviewRenderer.DisplayKind.CPM : PreviewRenderer.DisplayKind.ORDINARY),
                            "P1 own actual native pass/material/common ACK, not metadata readiness");
                    check(lastMaterial != null && !lastMaterial.texture.closed
                            && lastMaterial.texture.data.equals(((Avatar)p1State.renderState()).appearance), "P1 actual native material belongs to its own request");
                    check(key.equals(subject.runtime.previewBodySource(subject.preview())), "ACK publishes the same stable P1 recipe");
                    check(!ownedCpm || subject.runtime.view(854,480,0,0).widget("gallery.preview_mode").orElseThrow().enabled(), "own CPM BODY restores current cape control");
                    if (ownedCpm && !competing && Boolean.getBoolean("nclskins.cpm.nativeMeasurement")
                            && System.getProperty("nclskins.ownerLifecycle") != null) {
                        publications[0] = 0;
                        ownerLifecycle(System.getProperty("nclskins.ownerLifecycle"), subject, host, draw, execute,
                                rendererType, renderer, production, key, definitions);
                        return;
                    }
                    subject.layersVisible(!ownedCpm);
                    int stablePublications = publications[0];
                    for (int frame = 0; frame < 3; frame++) {
                        call(draw, host, new Graphics(), subject.runtime.view(854,480,0,0));
                        execute.accept(Graphics.queued);
                        check(key.equals(subject.runtime.previewBodySource(subject.runtime.galleryPreviewCandidate().orElseThrow())), "successive own frames preserve exact full key");
                    }
                    check(publications[0] == stablePublications, "fresh equal metadata and own frames publish no redundant snapshots");
                    if (present && !competing) {
                        System.out.println("PASS initial accessory fixed point publications=" + publications[0]);
                        publications[0] = 0;
                        var held = subject.preview();
                        int refinementLoads=subject.numericLoads,refinementCreates=subject.numericCreates;
                        boolean aba = ownedCpm && Boolean.getBoolean("nclskins.cpm.nativeMeasurement");
                        Object abaOperation = aba ? ownedField(subject.runtime, "numericFitOperation") : null;
                        Object abaFit = aba ? ownedField(renderer, "nativeFit") : null;
                        Object abaSlot = nativeSlot(renderer,key);
                        int abaCaptures = aba ? nativeCounter("captures") : 0;
                        int abaCopies = aba ? nativeCounter("copies") : 0;
                        int abaDecodes = aba ? nativeCounter("decodes") : 0;
                        if (aba) check(key.equals(ownedField(abaOperation, "source")), "ABA starts at immutable original numeric source S0");
                        AccessoryProjection.elytra = false;
                        subject.runtime.view(854,480,0,0);
                        var capeOnly = subject.runtime.galleryPreviewCandidate().orElseThrow();
                        check(capeOnly.nativeAccessories() && !capeOnly.capeHasElytra() && !key.equals(subject.runtime.previewBodySource(capeOnly)),
                                "same skin/source IDs with changed native capability retain full BODY discrimination");
                        check(subject.preview().equals(held), "changed capability retains A until B own success");
                        if (ownedCpm) definitions.add(prepareOwnedFeedback(subject, host, rendererType, renderer, firstRequest[0].appearance().skin()));
                        GuiState preparedCape = ownedCpm ? awaitOwnedPreparation(subject, host, draw, execute) : null;
                        if (ownedCpm) {
                            var field=rendererType.getDeclaredField("nativeFit");field.setAccessible(true);
                            var fit=field.get(renderer);
                            var sourceField=fit.getClass().getDeclaredField("source");sourceField.setAccessible(true);
                            var valueField=fit.getClass().getDeclaredField("fit");valueField.setAccessible(true);
                            check(((PreviewRenderer.ModelFitPreparation)fit).current() && valueField.get(fit)!=null
                                            && sourceField.get(fit).equals(subject.runtime.previewBodySource(capeOnly))
                                            && subject.numericLoads==refinementLoads && subject.numericCreates==refinementCreates,
                                    "successful same-demand refinement owns current native fit without numeric I/O retry");
                        }
                        if (preparedCape == null) call(draw, host, new Graphics(), subject.runtime.view(854,480,0,0));
                        GuiState capeState = preparedCape == null ? Graphics.queued : preparedCape;
                        if (ownedCpm) ownedFeedbackBinding(subject, production, subject.runtime.previewBodySource(capeOnly), rendererType, renderer, capeState);
                        execute.accept(capeState);
                        check(subject.preview().equals(held) && subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"cape-only hidden own consumer preserves retained common projection");
                        call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));execute.accept(Graphics.queued);
                        check(subject.preview().equals(capeOnly) && subject.displayed() == (ownedCpm ? PreviewRenderer.DisplayKind.CPM : PreviewRenderer.DisplayKind.ORDINARY),
                                "cape-only successor commits its own native pass and coherent controls");
                        subject.layersVisible(!ownedCpm);
                        if (aba) {
                            check(!((Map<?,?>) slotsField.get(renderer)).containsValue(abaSlot)
                                            && !((PreviewRenderer.ModelFitPreparation)abaFit).current(),
                                    "S1 own ACK actually retires original S0 native slot and fit");
                            AccessoryProjection.elytra = true;
                            subject.runtime.view(854,480,0,0);
                            var returned = subject.runtime.galleryPreviewCandidate().orElseThrow();
                            check(key.equals(subject.runtime.previewBodySource(returned)), "accessory feedback returns exact S0 without a new semantic demand");
                            definitions.add(prepareOwnedFeedback(subject, host, rendererType, renderer, firstRequest[0].appearance().skin()));
                            Object freshSlot = nativeSlot(renderer,key);
                            check(freshSlot != null && freshSlot != abaSlot, "ABA creates a fresh native S0 slot, not retained original ownership");
                            awaitOwnedFit(subject, host, draw, execute);
                            Object freshFit = ownedField(renderer, "nativeFit");
                            System.out.println("ABA_RETURN fit=" + (freshFit != null) + " native=" + production.displayKind()
                                    + " common=" + subject.displayed() + " loads=" + (subject.numericLoads-refinementLoads)
                                    + " creates=" + (subject.numericCreates-refinementCreates));
                            check(freshFit != null && freshFit != abaFit && ((PreviewRenderer.ModelFitPreparation)freshFit).current()
                                            && key.equals(ownedField(freshFit,"source")) && ownedField(freshFit,"fit") != null,
                                    "ABA recreated S0 owns current source-matching native normalization, not FULL factor one");
                            var fit = (com.naocraftlab.skins.client.PreviewModelNormalization.Fit)ownedField(freshFit,"fit");
                            check(fit.normalization()<0.5 && fit.equals(ownedField(abaFit,"fit")), "ABA uses original successful numeric winner");
                            var requestMethod=hostType.getDeclaredMethod("galleryRequest",com.naocraftlab.skins.runtime.ViewSpec.Preview.class);
                            requestMethod.setAccessible(true);
                            @SuppressWarnings("unchecked") var actualRequest=((Optional<PreviewRenderer.PreviewRequest>)requestMethod.invoke(host,returned)).orElseThrow();
                            int settledClassifiers=nativeCounter("classifiers");
                            int abaVertices=0;
                            for (int frame=0;frame<10;frame++) {
                                call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                                var state=Graphics.queued;
                                ownedFeedbackBinding(subject,production,key,rendererType,renderer,state);
                                check(Math.abs(state.scale()/ (0.97*actualRequest.height()/2.125*actualRequest.scale())-fit.normalization())<0.001,
                                        "ABA actual native scale applies correct own normalization");
                                try (var live=(AutoCloseable)Class.forName("com.naocraftlab.skins.buildlogic.probe.CpmNativeMeasurementBackend").getMethod("liveScope").invoke(null)) {
                                    execute.accept(state);
                                }
                                var vertexField=Class.forName("com.naocraftlab.skins.buildlogic.probe.ReferenceBodyProbe").getDeclaredField("verticesCount");
                                vertexField.setAccessible(true);int emitted=vertexField.getInt(null);
                                check(emitted>0,"ABA own live native prepared geometry is nonempty");abaVertices+=emitted;
                                check(lastMaterial!=null && !lastMaterial.texture.closed
                                                && lastMaterial.texture.data.equals(((Avatar)state.renderState()).appearance)
                                                && production.body().current().getAsBoolean() && subject.displayed()==PreviewRenderer.DisplayKind.CPM,
                                        "ABA own current material/native pass/common ACK survives settled frames");
                                check(ownedField(renderer,"nativeFit")==freshFit && ownedField(subject.runtime,"numericFitOperation")==abaOperation,
                                        "ABA settled frames reuse exact native preparation and semantic operation");
                            }
                            check(subject.numericLoads==refinementLoads && subject.numericCreates==refinementCreates
                                            && nativeCounter("captures")==abaCaptures && nativeCounter("copies")==abaCopies
                                            && nativeCounter("decodes")==abaDecodes && nativeCounter("classifiers")==settledClassifiers,
                                    "ABA roundtrip has zero numeric load/create/capture/neutral-decode retry and settled classification");
                            var row = new java.util.LinkedHashMap<String,Object>();
                            row.put("sameOperation",true);row.put("originalSourceReturned",true);row.put("oldSlotRetired",true);
                            row.put("freshSlot",true);row.put("ownCurrentFit",true);row.put("settledFrames",10);
                            row.put("normalization",fit.normalization());row.put("displayKind",subject.displayed().name());
                            row.put("liveVertices",abaVertices);
                            row.put("numericLoads",0);row.put("numericCreates",0);row.put("captures",0);row.put("neutralDecodes",0);
                            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("probe.dir"),"same-demand-aba.json"),new com.google.gson.Gson().toJson(row));
                            System.out.println("PASS same-demand S0-S1-ownACK-S0 own-fit/material/normalization/ACK settled10 numericRetry0");
                        }
                    }
                    if (competing) {
                        var writable = subject.runtime.galleryPreviewCandidate().orElseThrow();
                        check(writable.capeId().isPresent() && !writable.nativeAccessories(), "UI09 actual writable local cape wins default native bundle");
                        var card = subject.runtime.view(854,480,0,0).widget("gallery.inspect.CPM").orElseThrow();
                        check(card.activationEnabled(), "UI09 exact source CPM card activation admitted");
                        var noEffects = subject.noEffectsCheckpoint();
                        int saves = Client.current.options.saves, broadcasts = Client.current.options.broadcasts;
                        publications[0]=0;
                        subject.runtime.dispatchWidget(card.id(),false,com.naocraftlab.skins.runtime.InteractionOrigin.POINTER);
                        var inspected = subject.runtime.galleryPreviewCandidate().orElseThrow();
                        check(inspected.bodySource().orElseThrow().inspectionIdentity().equals(Optional.of("CPM"))
                                && inspected.capeId().isEmpty(), "UI09 card frontdoor preserves native inspection through cold successor: inspection=" + inspected.bodySource().orElseThrow().inspectionIdentity() + " cape=" + inspected.capeId() + " native=" + inspected.nativeAccessories());
                        definitions.add(prepareOwnedFeedback(subject,host,rendererType,renderer,firstRequest[0].appearance().skin()));
                        call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                        check(Graphics.queued.x1()<0,"UI09 actual inspected source begins with hidden preparation");
                        execute.accept(Graphics.queued);
                        check(subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"UI09 hidden inspected source consumer cannot common ACK");
                        subject.runtime.view(854,480,0,0);
                        inspected = subject.runtime.galleryPreviewCandidate().orElseThrow();
                        check(inspected.nativeAccessories() && inspected.capeId().isEmpty(), "UI09 own fresh native facts override writable cape default");
                        String inspectedKey = subject.runtime.previewBodySource(inspected);
                        definitions.add(prepareOwnedFeedback(subject,host,rendererType,renderer,firstRequest[0].appearance().skin()));
                        for (int frame=0; frame<4; frame++) {
                            awaitOwnedFit(subject, host, draw, execute);
                            call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                            var state = Graphics.queued; ownedFeedbackBinding(subject,production,inspectedKey,rendererType,renderer,state);
                            execute.accept(state);
                            check(lastMaterial != null && !lastMaterial.texture.closed && lastMaterial.texture.data.equals(((Avatar)state.renderState()).appearance), "UI09 own inspected native material");
                            check(subject.displayed()==PreviewRenderer.DisplayKind.CPM && inspectedKey.equals(subject.runtime.previewBodySource(subject.preview())), "UI09 own CPM pass/common ACK/stable source");
                            var marker = subject.runtime.view(854,480,0,0).widget(card.id()).orElseThrow();
                            check(marker.value().equals(Optional.of("active")), "UI09 own bundle green marker after ACK");
                        }
                        noEffects.run();
                        check(saves==Client.current.options.saves && broadcasts==Client.current.options.broadcasts
                                && subject.runtime.view(854,480,0,0).widget("gallery.done").isPresent(), "UI09 no Options writes and no dirty draft");
                        System.out.println("PASS UI09 real CPM card dispatch -> own pinned CPM native bundle/material/pass/common ACK; writable cape default control; green marker/stable4/no durable/provider/Options writes");
                    }
                    System.out.println("PASS stable native accessory metadata -> cold P1 UNKNOWN -> live confirmation -> own material/pass/common ACK -> successive stable frames -> same-ID cape-only successor with actual compiled host ownedCpm=" + ownedCpm);
                } finally {
                    bounded.close();
                    if (hostType.getField("subscription").get(host) instanceof com.naocraftlab.skins.runtime.ClientRuntime.Subscription owned) owned.close();
                    nativeHost.minecraft.screen = null;
                    AccessoryProjection.cape = true;
                    AccessoryProjection.elytra = true;
                    AccessoryProjection.forcedAvailability = null;
                    submit = ignored -> {};
                    for (int i = definitions.size() - 1; i >= 0; i--) definitions.get(i).close();
                }
            }
        }
    }

    private static AutoCloseable prepareOwnedFeedback(com.naocraftlab.skins.runtime.GalleryNativePassFixture subject,
            Object host, Class<?> rendererType, Object renderer, com.naocraftlab.skins.client.TextureRegistry.TextureHandle skin) throws Exception {
        var candidate = subject.runtime.galleryPreviewCandidate().orElseThrow();
        String source = subject.runtime.previewBodySource(candidate);
        @SuppressWarnings("unchecked") var production = (PreviewRenderer<Graphics>) renderer;
        var capabilityField = host.getClass().getDeclaredField("bodyCapability"); capabilityField.setAccessible(true);
        @SuppressWarnings("unchecked") var capability = (java.util.function.Supplier<PreviewRenderer.DisplayKind>) capabilityField.get(host);
        if (capability == null) {
            capability = production::displayKind;
            capabilityField.set(host, capability);
        }
        var registration = subject.runtime.observePreviewBody(candidate, capability, () -> production.confirmGalleryBody(source), production::retireBody);
        var observerField = host.getClass().getDeclaredField("bodyObserver"); observerField.setAccessible(true);
        observerField.set(host, registration);
        production.observeBody(registration);
        check(candidate.bodySource().orElseThrow().cpmExpected()
                        && production.prepareGalleryBody(source, subject.runtime.previewBodyDemand(candidate),
                                subject.runtime.waitsForCpmBody(candidate), subject.runtime.galleryPreviewAdmission(candidate)),
                "genuine host registration precedes own preparation and pinned decode, never a no-op observer");
        var slots = rendererType.getDeclaredField("gallerySlots"); slots.setAccessible(true);
        Object slot = nativeSlot(renderer,source);
        check(slot != null, "compiled host prepares the actual owned slot before pinned CPM decode");
        var profileMethod = slot.getClass().getDeclaredMethod("profile"); profileMethod.setAccessible(true);
        var profile = (com.mojang.authlib.GameProfile) profileMethod.invoke(slot);
        var requestMethod = host.getClass().getDeclaredMethod("galleryRequest",com.naocraftlab.skins.runtime.ViewSpec.Preview.class);
        requestMethod.setAccessible(true);
        @SuppressWarnings("unchecked") var request = (Optional<PreviewRenderer.PreviewRequest>)requestMethod.invoke(host,candidate);
        var appearance = request.orElseThrow().appearance();
        check(appearance.skin().equals(skin), "own pinned definition uses actual compiled host skin handle");
        return CpmBehaviorProbe.nativeOwnedBody(subject.ownedBytes(candidate), CpmBehaviorProbe.nativeProfileId(profile), appearance);
    }

    public static int fitPrepares, fitAllocations, fitCompletes;

    public static void fitEvent(int event) {
        if (event == 0) fitPrepares++;
        else if (event == 1) fitAllocations++;
        else if (event == 2) fitCompletes++;
        else throw new AssertionError("Unknown fit event");
    }

    private static void ownerLifecycle(String mode, com.naocraftlab.skins.runtime.GalleryNativePassFixture subject,
            Object host, Method draw, Consumer<GuiState> execute, Class<?> rendererType, Object renderer,
            PreviewRenderer<Graphics> production, String source, java.util.List<AutoCloseable> definitions) throws Exception {
        check(java.util.Set.of("live", "decline").contains(mode), "known owner lifecycle case");
        var initial = subject.preview();
        var anchor = initial.anchorBounds();
        subject.runtime.pointerScrolled(anchor.x()+anchor.width()/2.0,anchor.y()+anchor.height()/2.0,0,1);
        check(subject.preview().scale()>initial.scale(),"owner lifecycle starts after admitted nondefault user zoom");
        Object operation = ownedField(subject.runtime, "numericFitOperation");
        Object originalFit = ownedField(renderer, "nativeFit");
        var originalSession = (EditorPreviewSession) ownedField(renderer, "session");
        var originalPreview = subject.preview();
        Object composition = ownerComposition(subject.runtime.view(854,480,0,0));
        var projections = new java.util.ArrayList<Object>();
        var subscription = subject.runtime.subscribe(ignored -> projections.add(ownerComposition(subject.runtime.view(854,480,0,0))));
        try {
        String demand = subject.runtime.previewBodyDemand(originalPreview);
        var gallery = (EditorPreviewSession.Gallery) ownedField(renderer, "gallery");
        var slots = (Map<?, ?>) ownedField(renderer, "gallerySlots");
        Object originalSlot = slots.get(originalSession);
        check(originalSlot != null, "original displayed session owns its native slot");
        int loads = subject.numericLoads, creates = subject.numericCreates, captures = nativeCounter("captures");
        int decodes = nativeCounter("decodes"), copies = nativeCounter("copies");

        AccessoryProjection.elytra = false;
        subject.runtime.view(854,480,0,0);
        var successor = subject.runtime.galleryPreviewCandidate().orElseThrow();
        String successorSource = subject.runtime.previewBodySource(successor);
        check(!source.equals(successorSource) && demand.equals(subject.runtime.previewBodyDemand(successor)),
                "actual accessory producer refines render identity without changing demand/content/variant");
        check(production.prepareGalleryBody(successorSource, demand, subject.runtime.waitsForCpmBody(successor),
                subject.runtime.galleryPreviewAdmission(successor)), "pending successor is genuinely admitted");
        definitions.add(prepareOwnedFeedback(subject,host,rendererType,renderer,
                ((PreviewRenderer.PreviewAppearance)ownedField(originalFit,"appearance")).skin()));
        Graphics.states.clear();
        call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
        GuiState lateSuccessor = Graphics.states.stream().filter(state -> state.renderState() instanceof Avatar avatar
                && avatar.appearance != null && !avatar.appearance.capeHasElytra()).findFirst().orElseThrow(
                        () -> new AssertionError("held S1 must have its own actual compiled-host deferred native pass before ACK"));
        var successorSession = (EditorPreviewSession) ownedField(renderer, "session");
        Object successorFit = ownedField(renderer, "nativeFit");
        check(successorFit != null && successorFit != originalFit && successorSession != originalSession
                        && gallery.displays(originalSession) && gallery.retains(originalSession)
                        && subject.preview().equals(originalPreview), "S1 stays unacknowledged while genuine S0 owner remains displayed/live");
        var row = new java.util.LinkedHashMap<String,Object>();
        if (mode.equals("live")) {
            AccessoryProjection.elytra = true;
            subject.runtime.view(854,480,0,0);
            var returned = subject.runtime.galleryPreviewCandidate().orElseThrow();
            check(source.equals(subject.runtime.previewBodySource(returned)), "pre-ACK return uses exact S0 identity");
            check(production.prepareGalleryBody(source,demand,subject.runtime.waitsForCpmBody(returned),
                    subject.runtime.galleryPreviewAdmission(returned)), "returned S0 remains admitted");
            Object logical = ownedField(gallery,"desired");
            var logicalSession = (EditorPreviewSession) ownedField(logical,"session");
            var selectedSession = (EditorPreviewSession) ownedField(renderer,"session");
            System.out.println("LIVE_RETAINED_RETURN coherent="+(logicalSession==selectedSession)+" reused="+(selectedSession==originalSession));
            Object returnedSlot = slots.get(originalSession);
            check(logicalSession == selectedSession && selectedSession == originalSession
                            && returnedSlot != null && ownedField(returnedSlot,"session") == originalSession
                            && ownedField(returnedSlot,"fit") == originalFit,
                    "LIVE_RETAINED_SLOT: logical desired and native selected slot reuse the lawful displayed S0 session");
            for (String resource : java.util.List.of("profile", "player", "level", "clock", "tickGate", "baked", "target"))
                check(ownedField(returnedSlot,resource) == ownedField(originalSlot,resource),
                        "LIVE_RETAINED_RESOURCE: returned S0 preserves its exact owned " + resource);
            check(ownedField(renderer,"nativeFit")==originalFit && ((PreviewRenderer.ModelFitPreparation)originalFit).current(),
                    "returned live S0 keeps its own source/normalization authority");
            var fit=(com.naocraftlab.skins.client.PreviewModelNormalization.Fit)ownedField(originalFit,"fit");
            check(fit.normalization()>0 && fit.normalization()<0.5,"live retained S0 owns the successful tall normalization");
            var requestMethod=host.getClass().getDeclaredMethod("galleryRequest",com.naocraftlab.skins.runtime.ViewSpec.Preview.class);requestMethod.setAccessible(true);
            @SuppressWarnings("unchecked") var request=((Optional<PreviewRenderer.PreviewRequest>)requestMethod.invoke(host,returned)).orElseThrow();
            int vertices = 0;
            for (int frame=0;frame<10;frame++) {
                call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                GuiState state=Graphics.queued;
                check(Math.abs(state.scale()/(0.97*request.height()/2.125*request.scale())-fit.normalization())<0.001,
                        "retained S0 actual native scale applies own normalization exactly once");
                ownedFeedbackBinding(subject,production,source,rendererType,renderer,state);
                try (var live=(AutoCloseable)Class.forName("com.naocraftlab.skins.buildlogic.probe.CpmNativeMeasurementBackend").getMethod("liveScope").invoke(null)) {
                    execute.accept(state);
                }
                var vertexField=Class.forName("com.naocraftlab.skins.buildlogic.probe.ReferenceBodyProbe").getDeclaredField("verticesCount");
                vertexField.setAccessible(true);int emitted=vertexField.getInt(null);check(emitted>0,"retained S0 genuine geometry");vertices+=emitted;
                check(production.body().current().getAsBoolean() && subject.displayed()==PreviewRenderer.DisplayKind.CPM
                                && lastMaterial!=null && !lastMaterial.texture.closed
                                && lastMaterial.texture.data.equals(((Avatar)state.renderState()).appearance)
                                && subject.runtime.view(854,480,0,0).widget("gallery.preview_mode").orElseThrow().enabled(),
                        "retained S0 own native material/common ACK/current controls remain coherent");
            }
            if(lateSuccessor!=null) execute.accept(lateSuccessor);
            check(ownedField(renderer,"session")==originalSession && gallery.displays(originalSession)
                            && subject.displayed()==PreviewRenderer.DisplayKind.CPM && production.body().current().getAsBoolean(),
                    "late pending S1 cannot publish or retire returned S0");
            row.put("coherentSession",true);row.put("reusedLiveSlot",true);row.put("liveVertices",vertices);
            row.put("displayKind",subject.displayed().name());row.put("stableFrames",10);row.put("lateSuccessorRejected",true);
            check(!projections.isEmpty() && projections.stream().allMatch(composition::equals)
                            && composition.equals(ownerComposition(subject.runtime.view(854,480,0,0)))
                            && subject.preview().scale()==originalPreview.scale(),
                    "whole subscriber-observed retained composition/header/control topology/scroll and zoom stay stable");
            row.put("compositionStable",true);row.put("zoom",subject.preview().scale());row.put("normalization",fit.normalization());
        } else {
            successorSession.declineFit();
            check(!((PreviewRenderer.ModelFitPreparation)successorFit).current(),"same native owner has terminal fit admission");
            int prepares=fitPrepares, allocations=fitAllocations, completes=fitCompletes;
            for(int frame=0;frame<100;frame++) {
                call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                check(ownedField(renderer,"session")==successorSession && slots.get(successorSession)!=null,
                        "terminal case cannot be disguised by owner replacement");
            }
            System.out.println("TERMINAL_REBIND mode="+mode+" prepares="+(fitPrepares-prepares)+" allocations="+(fitAllocations-allocations)+" completes="+(fitCompletes-completes));
            check(fitPrepares==prepares && fitAllocations==allocations && fitCompletes==completes
                            && ownedField(renderer,"nativeFit")==successorFit,
                    "TERMINAL_REBIND: 100 actual host frames must not prepare/complete/reallocate the same terminal owner");
            int vertices=0;
            for(int pass=-1;pass<3;pass++) {
                call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));
                var state=Graphics.queued;check(state!=null,"terminal normalization keeps permitted live fallback");
                try(var live=(AutoCloseable)Class.forName("com.naocraftlab.skins.buildlogic.probe.CpmNativeMeasurementBackend").getMethod("liveScope").invoke(null)) { execute.accept(state); }
                var count=Class.forName("com.naocraftlab.skins.buildlogic.probe.ReferenceBodyProbe").getDeclaredField("verticesCount");count.setAccessible(true);
                int emitted=count.getInt(null);check(emitted>0,"declined normalization preserves genuine own live geometry");vertices+=emitted;
                if (pass < 0) {
                    check(production.displayKind()==PreviewRenderer.DisplayKind.UNCONFIRMED
                                    && subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED
                                    && successorSession.fitSuppressed() && ownedField(renderer,"nativeFit")==successorFit
                                    && lastMaterial!=null && !lastMaterial.texture.closed
                                    && lastMaterial.texture.data.equals(((Avatar)state.renderState()).appearance),
                            "declined own hidden material permits a visible fallback but cannot common ACK");
                    continue;
                }
                check(production.displayKind()==PreviewRenderer.DisplayKind.ORDINARY && subject.displayed()==PreviewRenderer.DisplayKind.ORDINARY
                                && successorSession.fitSuppressed() && ownedField(renderer,"nativeFit")==successorFit
                                && lastMaterial!=null && !lastMaterial.texture.closed && lastMaterial.texture.data.equals(((Avatar)state.renderState()).appearance),
                        "terminal same owner has own material/common ordinary ACK, not false FULL or borrowed S0");
            }
            check(fitPrepares==prepares && fitAllocations==allocations && fitCompletes==completes,"fallback passes also perform zero fit retry");
            row.put("sameOwner",true);row.put("stableFrames",100);row.put("nativePrepares",0);row.put("nativeAllocations",0);row.put("nativeCompletes",0);
            row.put("terminal",mode);row.put("bodyStatus",successorSession.bodyStatus().name());
            row.put("hiddenPasses",1);row.put("fallbackPasses",3);row.put("liveVertices",vertices);row.put("displayKind",subject.displayed().name());
        }
        check(ownedField(subject.runtime,"numericFitOperation")==operation
                        && subject.numericLoads==loads && subject.numericCreates==creates && nativeCounter("captures")==captures
                        && nativeCounter("decodes")==decodes && nativeCounter("copies")==copies,
                "owner lifecycle preserves semantic operation and performs no numeric/capture/neutral-copy retry");
        row.put("sameOperation",true);row.put("sameDemand",true);row.put("numericLoads",0);row.put("numericCreates",0);row.put("captures",0);row.put("neutralDecodes",0);
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("probe.dir"),"owner-lifecycle-"+mode+".json"),new com.google.gson.Gson().toJson(row));
        System.out.println("PASS owner-lifecycle "+mode);
        } finally { subscription.close(); }
    }

    private static Object ownerComposition(com.naocraftlab.skins.runtime.ViewSpec view) {
        return java.util.List.of(view.panels(),view.texts().stream().map(text -> java.util.List.of(text.id(),text.bounds(),text.message())).toList(),
                view.widgets().stream().filter(com.naocraftlab.skins.runtime.ViewSpec.Widget::visible)
                        .map(widget -> java.util.List.of(widget.id(),widget.kind(),widget.bounds())).toList(),view.scrollSurfaces(),view.scrollbar());
    }

    private static Object ownedField(Object owner, String name) throws Exception {
        var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }

    private static int nativeCounter(String name) throws Exception {
        var field=Class.forName("com.naocraftlab.skins.buildlogic.probe.FitCountingObservation").getDeclaredField(name);
        field.setAccessible(true);return field.getInt(null);
    }

    private static GuiState awaitOwnedPreparation(com.naocraftlab.skins.runtime.GalleryNativePassFixture subject,
            Object host, Method draw, Consumer<GuiState> execute) throws Exception {
        CpmBehaviorProbe.drain(); subject.drainWorkers();
        if (!Boolean.getBoolean("nclskins.cpm.nativeMeasurement")) return null;
        String expected = subject.runtime.previewBodySource(subject.runtime.galleryPreviewCandidate().orElseThrow());
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            CpmBehaviorProbe.drain(); subject.drainWorkers();
            Object renderer = host.getClass().getField("editorRenderer").get(host);
            Object fitting = ownedField(renderer, "nativeFit");
            if (fitting instanceof PreviewRenderer.ModelFitPreparation preparation && preparation.current()
                    && expected.equals(ownedField(fitting, "source")) && ownedField(fitting, "fit") != null) return null;
            Graphics.queued = null;
            call(draw, host, new Graphics(), subject.runtime.view(854,480,0,0));
            fitting = ownedField(renderer, "nativeFit");
            if (fitting instanceof PreviewRenderer.ModelFitPreparation preparation && preparation.current()
                    && expected.equals(ownedField(fitting, "source")) && ownedField(fitting, "fit") != null) return Graphics.queued;
            if (Graphics.queued != null) execute.accept(Graphics.queued);
            Thread.sleep(1);
        }
        Object renderer=host.getClass().getField("editorRenderer").get(host);
        Object fitting=ownedField(renderer,"nativeFit");
        Object operation=ownedField(subject.runtime,"numericFitOperation");
        Object result=operation==null?null:ownedField(operation,"result");
        Object settled=result instanceof java.util.concurrent.CompletableFuture<?> future?future.getNow(null):null;
        System.out.println("OWN_PREPARATION_DIAGNOSTIC loads="+subject.numericLoads+" creates="+subject.numericCreates
                +" captures="+nativeCounter("captures")+" persisted="+(subject.numericKey==null?"NO_KEY":subject.durableNumericFit().status())
                +" settled="+(settled==null?"pending":((com.naocraftlab.skins.runtime.PreviewAssets.NumericFitResult)settled).status())
                +" fit="+(fitting!=null&&ownedField(fitting,"fit")!=null));
        throw new AssertionError("own current native numeric preparation before hidden image consumer");
    }

    private static void awaitOwnedFit(com.naocraftlab.skins.runtime.GalleryNativePassFixture subject,
            Object host, Method draw, Consumer<GuiState> execute) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        String expected = subject.runtime.previewBodySource(subject.runtime.galleryPreviewCandidate().orElseThrow());
        while ((subject.displayed() != PreviewRenderer.DisplayKind.CPM
                || !expected.equals(subject.runtime.previewBodySource(subject.preview()))) && System.nanoTime() < deadline) {
            CpmBehaviorProbe.drain(); subject.drainWorkers();
            Graphics.queued = null;
            call(draw, host, new Graphics(), subject.runtime.view(854,480,0,0));
            if (Graphics.queued != null) execute.accept(Graphics.queued);
            subject.drainWorkers();
            if (subject.displayed() != PreviewRenderer.DisplayKind.CPM) Thread.sleep(1);
        }
        if (subject.displayed() != PreviewRenderer.DisplayKind.CPM && Boolean.getBoolean("nclskins.cpm.nativeMeasurement")) {
            Object operation=ownedField(subject.runtime,"numericFitOperation");
            Object result=operation==null?null:ownedField(operation,"result");
            Object settled=result instanceof java.util.concurrent.CompletableFuture<?> f?f.getNow(null):null;
            Object renderer=host.getClass().getField("editorRenderer").get(host);
            Object fitting=ownedField(renderer,"nativeFit");
            var requirements=com.naocraftlab.skins.runtime.CapeProjection.previewBodyRequirements(AccessoryProjection.profile);
            System.out.println("OWN_FIT_DIAGNOSTIC displayed="+subject.displayed()+" loads="+subject.numericLoads
                    +" creates="+subject.numericCreates+" captures="+nativeCounter("captures")+" persisted="+(subject.numericKey==null?"NO_KEY":subject.durableNumericFit().status())
                    +" operation="+(operation!=null)+" settled="+(settled==null?"pending":settled.getClass().getMethod("status").invoke(settled))
                    +" requirements="+requirements+" fitting="+(fitting!=null)
                    +(fitting==null?"":" eligible="+ownedField(fitting,"eligible")+" custom="+ownedField(fitting,"custom")+" cpm="+ownedField(fitting,"cpm")+" earsRequired="+ownedField(fitting,"earsRequired")+" retired="+ownedField(fitting,"retired")));
        }
        check(subject.displayed() == PreviewRenderer.DisplayKind.CPM
                && expected.equals(subject.runtime.previewBodySource(subject.preview())), "own native measurement and durable fit reaches actual BODY ACK");
    }

    private static void ownedFeedbackBinding(com.naocraftlab.skins.runtime.GalleryNativePassFixture subject,
            PreviewRenderer<Graphics> production, String source, Class<?> rendererType, Object renderer, GuiState state) throws Exception {
        var slots = rendererType.getDeclaredField("gallerySlots"); slots.setAccessible(true);
        Object slot = nativeSlot(renderer,source);
        var profileMethod = slot.getClass().getDeclaredMethod("profile"); profileMethod.setAccessible(true);
        var profile = (com.mojang.authlib.GameProfile) profileMethod.invoke(slot);
        var appearance = ((Avatar) state.renderState()).appearance;
        var request = new PreviewRenderer.PreviewRequest(appearance, 0, 0, 160, 180, 0, 0, 1, subject.runtime.galleryPreviewCandidate().orElseThrow().intent());
        var value = production.galleryAccessories(source, request, subject.runtime.galleryAccessoryAdmission(subject.runtime.galleryPreviewCandidate().orElseThrow()));
        check(value.canInspect() && value.scope().equals(CpmBehaviorProbe.nativeProfileId(profile)) && AccessoryProjection.skin.equals(appearance.skin().location())
                && AccessoryProjection.model == appearance.model(), "own metadata matches actual NativeSlot profile/skin/model before CPM binding");
        check(java.util.Arrays.equals(subject.ownedBytes(subject.runtime.galleryPreviewCandidate().orElseThrow()), subject.ownedBytes(subject.preview())),
                "capability successor preserves its actual owned image bytes");
    }

    private static Object nativeSlot(Object renderer,String source) throws ReflectiveOperationException {
        var owner = renderer.getClass();
        var galleryField = owner.getDeclaredField("gallery"); galleryField.setAccessible(true);
        var gallery = (EditorPreviewSession.Gallery)galleryField.get(renderer);
        var slots = owner.getDeclaredField("gallerySlots"); slots.setAccessible(true);
        var values = (Map<?,?>)slots.get(renderer);
        Object desired = values.get(gallery.session(source));
        if (desired != null) return desired;
        var sourceField = EditorPreviewSession.class.getDeclaredField("bodySource"); sourceField.setAccessible(true);
        for (var entry : values.entrySet())
            if (java.util.Objects.equals(source,sourceField.get(entry.getKey()))) return entry.getValue();
        return null;
    }

    private static void nativeMetadataRebuild(Class<?> rendererType, Object renderer,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture subject, Consumer<GuiState> execute) throws Exception {
        Class<?> hostType = CpmPreviewScreenSlice.loadNativeLifecycle(PREFIX + "NclSkinsScreen", rendererType);
        var registry = new CpmOwnedPreviewDispatchProbe.Registry();
        try (var skins = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry, com.naocraftlab.skins.client.TextureRegistry.TextureKind.PLAYER_SKIN, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
             var capes = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry, com.naocraftlab.skins.client.TextureRegistry.TextureKind.IMAGE, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
            Object host = hostType.getConstructor().newInstance();
            for (var field : hostType.getDeclaredFields()) {
                Object value = switch (field.getName()) {
                    case "runtime" -> subject.runtime;
                    case "editorRenderer" -> renderer;
                    case "skinTextures" -> skins;
                    case "capeTextures" -> capes;
                    case "textureRegistry" -> registry;
                    case "scrollController" -> new CpmOwnedPreviewDispatchProbe.ScrollTerminal();
                    case "focusRequests" -> new com.naocraftlab.skins.runtime.FocusRequestLedger();
                    case "destination" -> com.naocraftlab.skins.client.ScreenDestination.GALLERY;
                    case "nativeDispatchDepth" -> 1;
                    default -> Map.class.isAssignableFrom(field.getType()) ? new HashMap<>()
                            : java.util.List.class.isAssignableFrom(field.getType()) ? new java.util.ArrayList<>() : null;
                };
                if (value != null) field.set(host, value);
            }
            var nativeHost = (CpmOwnedPreviewDispatchProbe.ScreenTerminal) host;
            nativeHost.width = 854; nativeHost.height = 480;
            nativeHost.font = new CpmOwnedPreviewDispatchProbe.FontTerminal();
            nativeHost.minecraft = new CpmOwnedPreviewDispatchProbe.ClientTerminal();
            nativeHost.minecraft.screen = nativeHost;
            Method init = hostType.getDeclaredMethod("init"); init.setAccessible(true);
            Method draw = hostType.getDeclaredMethod("drawPreviews", Graphics.class, com.naocraftlab.skins.runtime.ViewSpec.class); draw.setAccessible(true);
            var subscription = hostType.getField("subscription");
            try {
                call(init, host);
                check(subscription.get(host) != null && nativeHost.widgetAssemblies == 1, "actual compiled init installs its own publication subscriber and builds native widget terminals");
                call(draw, host, new Graphics(), subject.runtime.view(854,480,0,0)); execute.accept(Graphics.queued);
                call(draw, host, new Graphics(), subject.runtime.view(854,480,0,0)); execute.accept(Graphics.queued);
                @SuppressWarnings("unchecked") var production = (PreviewRenderer<Graphics>) renderer;
                var beforeBody = production.body();
                check(beforeBody.current().getAsBoolean(), "host metadata starts from actual displayed body");
                String source = subject.runtime.previewBodySource(subject.preview());
                var slotField = rendererType.getDeclaredField("gallerySlots"); slotField.setAccessible(true);
                Object slot = nativeSlot(renderer,source);
                Object[] identities = new Object[4];
                String[] names = {"profile", "player", "target", "baked"};
                for (int i=0; i<names.length; i++) { var method=slot.getClass().getDeclaredMethod(names[i]); method.setAccessible(true); identities[i]=method.invoke(slot); }
                TextureView material = lastMaterial;
                Object beforePayload = material.texture.data;
                check(beforePayload.equals(((Avatar) Graphics.queued.renderState()).appearance), "before-write material comes from the actual compiled host request");
                check(material != null && !material.texture.closed, "before-write actual native material is alive");
                int[] admittedRebuilds = {0}, admittedAssemblies = {0}; Object[] admittedSignature = {null};
                subject.favoriteDisplayed(() -> {
                    try {
                        admittedRebuilds[0] = nativeHost.rebuilds; admittedAssemblies[0] = nativeHost.widgetAssemblies;
                        admittedSignature[0] = hostType.getField("widgetSignature").get(host);
                        check(!((java.util.List<?>)admittedSignature[0]).isEmpty(), "metadata compares a real nonempty native signature");
                    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                });
                check(nativeHost.rebuilds > admittedRebuilds[0] && nativeHost.widgetAssemblies > admittedAssemblies[0],
                        "same admitted favorite completion must reach snapshotChanged -> signature -> rebuildWidgets -> actual init");
                check(!admittedSignature[0].equals(hostType.getField("widgetSignature").get(host)), "favorite changes actual host widget topology/signature");
                check(hostType.getField("editorRenderer").get(host) == renderer && beforeBody.current().getAsBoolean()
                        && source.equals(subject.runtime.previewBodySource(subject.preview())), "rebuild/reopen/assets preserve the same native BODY source and renderer");
                call(draw, host, new Graphics(), subject.runtime.view(854,480,0,0)); execute.accept(Graphics.queued);
                Object after = nativeSlot(renderer,source);
                for (int i=0; i<names.length; i++) { var method=after.getClass().getDeclaredMethod(names[i]); method.setAccessible(true); check(method.invoke(after)==identities[i], "same-write host rebuild preserves native "+names[i]); }
                check(lastMaterial == material && !material.texture.closed && material.texture.data.equals(beforePayload)
                        && material.texture.data.equals(((Avatar) Graphics.queued.renderState()).appearance)
                        && subject.displayed() == PreviewRenderer.DisplayKind.ORDINARY,
                        "next actual source pass reuses same host-produced appearance/material/proxy/profile after durable favorite host rebuild");
                System.out.println("PASS same admitted favorite -> durable readback -> compiled snapshotChanged/signature/rebuildWidgets/init/reopen/assets/subscriber -> same BODY source/profile/proxy/target/material -> next own native pass; synthetic widget construction/font/GPU terminals");
            } finally {
                if (subscription.get(host) instanceof com.naocraftlab.skins.runtime.ClientRuntime.Subscription owned) owned.close();
                nativeHost.minecraft.screen = null;
            }
        }
    }

    private static void nativeSupersededCandidate(Class<?> rendererType, Object renderer,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture subject, Consumer<GuiState> execute) throws Exception {
        materials.clear();
        Class<?> hostType = CpmPreviewScreenSlice.loadNative(PREFIX + "NclSkinsScreen", rendererType);
        var registry = new CpmOwnedPreviewDispatchProbe.Registry();
        try (var skins = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry, com.naocraftlab.skins.client.TextureRegistry.TextureKind.PLAYER_SKIN, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
             var capes = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry, com.naocraftlab.skins.client.TextureRegistry.TextureKind.IMAGE, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
            Object host = hostType.getConstructor().newInstance();
            for (var field : hostType.getDeclaredFields()) {
                Object value = switch (field.getName()) {
                    case "runtime" -> subject.runtime;
                    case "editorRenderer" -> renderer;
                    case "skinTextures" -> skins;
                    case "capeTextures" -> capes;
                    case "textureRegistry" -> registry;
                    default -> field.getType() == Map.class ? new HashMap<>() : null;
                };
                if (value != null) field.set(host, value);
            }
            Method draw = hostType.getDeclaredMethod("drawPreviews", Graphics.class, com.naocraftlab.skins.runtime.ViewSpec.class); draw.setAccessible(true);
            var slotsField = rendererType.getDeclaredField("gallerySlots"); slotsField.setAccessible(true);
            @SuppressWarnings("unchecked") var slots = (Map<EditorPreviewSession, ?>) slotsField.get(renderer);
            call(draw, host, new Graphics(), subject.runtime.view(854,480,0,0));
            execute.accept(Graphics.queued);
            Method releaseBody = hostType.getDeclaredMethod("releasePreviewBody"); releaseBody.setAccessible(true);
            call(releaseBody, host);
            subject.offlineCapeCapability(true);
            Method sync = hostType.getDeclaredMethod("syncPreviewAssets", com.naocraftlab.skins.runtime.ViewSpec.class); sync.setAccessible(true);
            call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
            call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
            var readyCape = subject.runtime.galleryPreviewCandidate().orElseThrow();
            check(capes.capeState(subject.runtime.previewCapeSurfaceKey(readyCape),readyCape.capeId().orElseThrow()).handle().isPresent(),
                    "COMMON-02 predecessor has an actual own registered cape before native execution");
            call(draw, host, new Graphics(), subject.runtime.view(854,480,0,0)); execute.accept(Graphics.queued);
            check(subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"same-ID cape predecessor hidden preparation cannot common ACK");
            call(draw, host, new Graphics(), subject.runtime.view(854,480,0,0)); execute.accept(Graphics.queued);
            var capabilityA = subject.preview();
            check(capabilityA.capeHasElytra(), "COMMON-02 real account readback establishes predecessor capability");
            subject.offlineCapeCapability(false);
            var capabilityB = subject.runtime.galleryPreviewCandidate().orElseThrow();
            check(capabilityA.bodySource().equals(capabilityB.bodySource())
                    && !subject.runtime.previewBodySource(capabilityA).equals(subject.runtime.previewBodySource(capabilityB)),
                    "COMMON-02 equal BodySource distinct exact account cape capability");
            call(sync,host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
            call(draw, host, new Graphics(), subject.runtime.view(854,480,0,0)); execute.accept(Graphics.queued);
            check(subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"same-ID cape successor hidden preparation cannot common ACK");
            call(draw, host, new Graphics(), subject.runtime.view(854,480,0,0)); execute.accept(Graphics.queued);
            check(!subject.preview().capeHasElytra(), "COMMON-02 actual host must execute same-ID successor capability pass");
            System.out.println("PASS COMMON-02 real durable account cape capability -> canonical data producer -> actual host same-ID candidate pass");
            var confirmed = subject.preview();
            String confirmedSource = subject.runtime.previewBodySource(confirmed);
            check((nativeSlot(renderer,confirmedSource) != null) && subject.displayed() != PreviewRenderer.DisplayKind.UNCONFIRMED,
                    "late candidate prerequisite: actual host/native pass confirms predecessor");
            subject.selectB();
            var queuedCandidate = subject.runtime.galleryPreviewCandidate().orElseThrow();
            String queuedSource = subject.runtime.previewBodySource(queuedCandidate);
            call(draw, host, new Graphics(), subject.runtime.view(854,480,0,0));
            GuiState queued = Graphics.queued;
            check(!queuedSource.equals(confirmedSource) && (nativeSlot(renderer,confirmedSource) != null) && (nativeSlot(renderer,queuedSource) != null),
                    "late candidate prerequisite: actual host enqueues hidden candidate and retains confirmed predecessor");
            subject.selectC();
            var newest = subject.runtime.galleryPreviewCandidate().orElseThrow();
            String newestSource = subject.runtime.previewBodySource(newest);
            check(!newestSource.equals(confirmedSource) && !newestSource.equals(queuedSource) && (nativeSlot(renderer,newestSource) == null),
                    "late candidate prerequisite: admitted second selection precedes native preparation");
            var captured = subject.runtime.view(854,480,0,0);
            check(captured.previews().stream().filter(p -> p.id().equals("gallery.preview")).findFirst().orElseThrow().equals(confirmed),
                    "late candidate prerequisite: captured actual common view retains confirmed predecessor");
            execute.accept(queued);
            check(subject.preview().equals(confirmed) && subject.runtime.previewBodySource(confirmed).equals(confirmedSource),
                    "late candidate must be rejected by common owner without changing source epoch");
            System.out.println("TRACE superseded native candidate: commonConfirmed=predecessor; appDesired=newest; nativeNewestPrepared="
                    + (nativeSlot(renderer,newestSource) != null) + "; nativeQueuedRetained=" + (nativeSlot(renderer,queuedSource) != null)
                    + "; nativeConfirmedRetained=" + (nativeSlot(renderer,confirmedSource) != null) + "; nativeSlots=" + slots.size());
            call(draw, host, new Graphics(), captured);
            check((nativeSlot(renderer,confirmedSource) != null), "superseded completion cannot retire the source still requested by actual captured view");
            GuiState acceptedCandidate = Graphics.queued;
            execute.accept(acceptedCandidate);
            check(subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED && subject.preview().equals(confirmed),"COMMON-01 hidden own C material cannot replace retained A projection");
            call(draw,host,new Graphics(),subject.runtime.view(854,480,0,0));acceptedCandidate=Graphics.queued;execute.accept(acceptedCandidate);
            check(subject.preview().bodySource().equals(newest.bodySource()) && (nativeSlot(renderer,newestSource) != null)
                    && (nativeSlot(renderer,confirmedSource) == null), "COMMON-01 actual C pass, not stale B, replaces and retires A");
            check(lastMaterial != null && !lastMaterial.texture.closed
                    && lastMaterial.texture.data.equals(((Avatar)acceptedCandidate.renderState()).appearance),
                    "COMMON-01 C owns its actual nonempty native material");
            subject.layersVisible(true);
            System.out.println("PASS COMMON-01 retained A actual captured host paint after rejected B -> own C pass/material/common ACK -> exact A retirement");
            Method releaseAccessories = hostType.getDeclaredMethod("releasePreviewAccessories"); releaseAccessories.setAccessible(true);
            call(releaseAccessories, host);
            call(releaseBody, host);
        }
    }

    private static void nativeAccessories(Class<?> rendererType, Object renderer,
            com.naocraftlab.skins.runtime.GalleryNativePassFixture subject) throws Exception {
        subject.enableCpmCape();
        var environment = com.naocraftlab.skins.runtime.ClientRuntime.class.getDeclaredField("skinExtensionEnvironment"); environment.setAccessible(true);
        environment.set(subject.runtime, new com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment(1, Map.of(), true));
        Class<?> hostType = CpmPreviewScreenSlice.loadNative(PREFIX + "NclSkinsScreen", rendererType);
        var registry = new CpmOwnedPreviewDispatchProbe.Registry();
        try (var skins = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry, com.naocraftlab.skins.client.TextureRegistry.TextureKind.PLAYER_SKIN, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
             var capes = new com.naocraftlab.skins.runtime.PreviewAssetCache<Object>(registry, com.naocraftlab.skins.client.TextureRegistry.TextureKind.IMAGE, com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
            Object host = hostType.getConstructor().newInstance();
            for (var field : hostType.getDeclaredFields()) {
                Object value = switch (field.getName()) {
                    case "runtime" -> subject.runtime;
                    case "editorRenderer" -> renderer;
                    case "skinTextures" -> skins;
                    case "capeTextures" -> capes;
                    case "textureRegistry" -> registry;
                    default -> field.getType() == Map.class ? new HashMap<>() : null;
                };
                if (value != null) field.set(host, value);
            }
            Method draw = hostType.getDeclaredMethod("drawPreviews", Graphics.class, com.naocraftlab.skins.runtime.ViewSpec.class); draw.setAccessible(true);
            Method release = hostType.getDeclaredMethod("releasePreviewAccessories"); release.setAccessible(true);
            var callback = com.naocraftlab.skins.runtime.ClientRuntime.class.getDeclaredField("accessoryCallback"); callback.setAccessible(true);
            var capability = com.naocraftlab.skins.runtime.ClientRuntime.class.getDeclaredField("accessoryCapability"); capability.setAccessible(true);
            Runnable drawHost = () -> {
                try {
                    var view = subject.runtime.view(854,480,0,0);
                    var components = view.getClass().getRecordComponents(); Object[] values = new Object[components.length]; Class<?>[] types = new Class<?>[components.length];
                    for (int i = 0; i < components.length; i++) {
                        types[i] = components[i].getType();
                        values[i] = components[i].getName().equals("previews") ? java.util.List.of(subject.preview()) : components[i].getAccessor().invoke(view);
                    }
                    call(draw, host, new Graphics(), view.getClass().getConstructor(types).newInstance(values));
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            };
            var unchanged = subject.noEffectsCheckpoint(); int saves = Client.current.options.saves;
            drawHost.run();
            @SuppressWarnings("unchecked") var old = (Consumer<com.naocraftlab.skins.client.PreviewAccessories>) callback.get(subject.runtime);
            @SuppressWarnings("unchecked") var supplierA = (java.util.function.Supplier<com.naocraftlab.skins.client.PreviewAccessories>) capability.get(subject.runtime);
            var positiveA = supplierA.get();
            check(positiveA.canInspect(), "actual host registers positive source-scoped native accessories"); old.accept(positiveA);
            subject.runtime.dispatchWidget("gallery.card." + new java.util.UUID(0,802)); drawHost.run();
            @SuppressWarnings("unchecked") var current = (Consumer<com.naocraftlab.skins.client.PreviewAccessories>) callback.get(subject.runtime);
            @SuppressWarnings("unchecked") var supplierB = (java.util.function.Supplier<com.naocraftlab.skins.client.PreviewAccessories>) capability.get(subject.runtime);
            var positiveB = supplierB.get();
            check(current != old && positiveB.canInspect() && !positiveA.scope().equals(positiveB.scope()), "new real native slot has its own positive profile");
            var slotsField = rendererType.getDeclaredField("gallerySlots"); slotsField.setAccessible(true);
            Object slot = nativeSlot(renderer,subject.runtime.previewBodySource(subject.runtime.galleryPreviewCandidate().orElseThrow()));
            var profileMethod = slot.getClass().getDeclaredMethod("profile"); profileMethod.setAccessible(true);
            var profile = (com.mojang.authlib.GameProfile) profileMethod.invoke(slot);
            check(AccessoryProjection.profile.equals(CpmBehaviorProbe.nativeProfileId(profile)) && AccessoryProjection.skin.equals(subject.request().appearance().skin().location())
                    && AccessoryProjection.model == subject.request().appearance().model(), "observation terminal receives exact production NativeSlot profile and appearance");
            current.accept(positiveB); old.accept(positiveA);
            check(callback.get(subject.runtime) == current && supplierB.get().scope().equals(positiveB.scope()), "late A cannot change B supplier or slot");
            boolean[] reentrant = {false};
            try (var subscription = subject.runtime.subscribe(ignored -> { if (reentrant[0]) { reentrant[0] = false; drawHost.run(); } })) {
                reentrant[0] = true; call(release, host);
                check(!reentrant[0] && callback.get(subject.runtime) != null && callback.get(subject.runtime) != current,
                        "actual host retirement preserves reentrant successor observer");
                Object next = callback.get(subject.runtime); current.accept(positiveB); check(callback.get(subject.runtime) == next, "retired callback cannot resurrect");
            }
            call(release, host); subject.runtime.closeScreen(); old.accept(positiveA);
            check(callback.get(subject.runtime) == null, "closed Gallery cannot recover stale accessory observer");
            subject.runtime.reopen(); drawHost.run();
            @SuppressWarnings("unchecked") var reopened = (java.util.function.Supplier<com.naocraftlab.skins.client.PreviewAccessories>) capability.get(subject.runtime);
            check(reopened != null && reopened.get().canInspect(), "reopened actual host has fresh positive source");
            old.accept(positiveA); check(capability.get(subject.runtime) == reopened, "pre-close callback cannot affect reopened host");
            call(release, host); unchanged.run(); check(Client.current.options.saves == saves, "accessory observation has no Options writes");
            System.out.println("PASS actual Gallery host -> observePreviewAccessories -> native source/profile supplier: positive A/B, stale, reentrant, close/reopen; no HTTP/storage/assignment/Options");
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private static Object callOuter(Method method, Object owner, Object... args) {
        if (method.getParameterCount() == 4 && args.length == 5) args = new Object[]{args[0],args[1],args[3],args[4]};
        return call(method, owner, args);
    }
    private static Object callPrepare(Method method, Object owner, Object... args) {
        if (method.getParameterCount() == 3 && args.length == 4) args = new Object[]{args[0],args[1],args[3]};
        return call(method, owner, args);
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
            if (!name.startsWith(PREFIX) && !name.startsWith("com.naocraftlab.skins.compat.client.identifier.preview.") && !name.equals(PIP) && !name.equals(GUI) && !name.equals(FRAME)
                    && !name.equals("com.naocraftlab.skins.compat.client.identifier.IdentifierOuterLayerVisibilityController")) return super.loadClass(name, resolve);
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (input == null) throw new ClassNotFoundException(name);
                    byte[] original = input.readAllBytes();
                    if (Boolean.getBoolean("nclskins.cpm.nativeMeasurement") && (name.equals(PIP) || name.equals(GUI) || name.equals(FRAME))) {
                        var resources = getParent().getResources(name.replace('.', '/') + ".class");
                        boolean found = false;
                        while (resources.hasMoreElements()) {
                            var resource = resources.nextElement();
                            if (!resource.getProtocol().equals("jar")) continue;
                            try (var raw = resource.openStream()) { original = raw.readAllBytes(); }
                            found = true; break;
                        }
                        if (!found) throw new AssertionError("Pinned raw native caller is missing: " + name);
                    }
                    var node = new ClassNode(); new ClassReader(original).accept(node, 0);
                    if (name.equals(PREFIX + "GuiExtraction")) {
                        CpmPreviewScreenSlice.sliceGuiAdapter(node, java.util.Set.of("blit"));
                        for (var method : node.methods) for (var instruction : method.instructions) {
                            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("blit")
                                    && call.owner.equals("net/minecraft/client/gui/GuiGraphicsExtractor")) {
                                call.desc = loadingDescriptor(call.desc);
                            }
                        }
                    }
                    if (name.equals(PREFIX + "AvatarRenderStatePreviewRenderer")) {
                        rendererTerminals(node);
                        rendererOperands.accept(node);
                    }
                    if (name.equals(PREFIX + "AvatarRenderStatePreviewRenderer") || name.equals(PREFIX + "AvatarRenderStatePreviewRenderer$NativeFit")) {
                        for (var method : node.methods) {
                            int event = name.endsWith("$NativeFit") ? method.name.equals("<init>") ? 1 : method.name.equals("complete") ? 2 : -1
                                    : method.name.equals("prepareModelFit") ? 0 : -1;
                            if (event < 0) continue;
                            var count = new org.objectweb.asm.tree.InsnList();
                            count.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0 + event));
                            count.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(CpmExtractionBodyProbe.class), "fitEvent", "(I)V", false));
                            method.instructions.insert(count);method.maxStack=Math.max(method.maxStack,1);
                        }
                    }
                    if (name.equals(FRAME)) frameTerminals(node);
                    if (name.equals(PIP) || name.equals(GUI)) {
                        if (name.equals(PIP)) for (var method : node.methods) for (var instruction : method.instructions) {
                            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                                    && call.name.equals("renderAllFeatures") && call.getOpcode() == Opcodes.INVOKESTATIC) staticFlush = true;
                        }
                        retainedNative(node, name.equals(PIP));
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
                    bindRenderPearl(node);
                    for (var method : node.methods) for (var instruction : method.instructions) {
                        if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) call.desc = call.desc.replace("Lorg/joml/Matrix3x2f;", "Lorg/joml/Matrix3x2fc;");
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

    static void frameTerminals(ClassNode node) {
        node.methods.removeIf(m -> !m.name.equals("render"));
        node.fields.removeIf(f -> !java.util.Set.of("renderState", "vertexBuffer", "vertexBuffers", "pictureInPictureRendererPools", "meshesToDraw", "draws", "firstDrawIndexAfterBlur", "cubeMap", "CLEAR_COLOR").contains(f.name));
        node.methods.get(0).name = "nclskins$nativeRender";
        init(node);
        String descriptor = node.methods.stream().filter(m -> m.name.equals("nclskins$nativeRender")).findFirst().orElseThrow().desc;
        forwarding(node, "render", descriptor, "retainedFrame", Type.getArgumentTypes(descriptor).length);
        for (String name : new String[]{"prepare", "draw", "clearUnusedOversizedItemRenderers"}) {
            var terminal = method(node, name, name.equals("draw") ? descriptor : "()V");
            if (name.equals("draw")) {
                terminal.visitVarInsn(Opcodes.ALOAD, 0);
                terminal.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(CpmExtractionBodyProbe.class), "retainedDraw", "(Ljava/lang/Object;)V", false);
            } else if (name.equals("prepare")) terminal.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(CpmExtractionBodyProbe.class), "framePreparation", "()V", false);
            terminal.visitInsn(Opcodes.RETURN); terminal.visitMaxs(1, name.equals("draw") ? 1 + Type.getArgumentTypes(descriptor).length : 1);
        }
    }

    public static void retainedFrame(Object[] args) { retainedFrame.accept(args); }
    public static void retainedDraw(Object owner) { retainedDraw.accept(owner); }
    public static void framePreparation() { framePreparation.run(); }

    private static final java.util.IdentityHashMap<Object, Object> consumerOwners = new java.util.IdentityHashMap<>();

    static void closeConsumerOwner(ClassLoader loader, Object owner) throws Exception {
        Object wrapper = consumerOwners.get(owner);
        if (wrapper == null) throw new AssertionError("Missing native GUI owner");
        var close = java.util.Arrays.stream(wrapper.getClass().getDeclaredMethods()).filter(m -> m.getName().equals("nclskins$closeOwner")).findFirst().orElseThrow();
        close.setAccessible(true);
        call(close, wrapper, (Operation<Void>) args -> null);
    }

    static void consumerFrame(ClassLoader loader, Runnable preparation, Runnable draw) throws Exception {
        consumerFrame(loader, loader, preparation, draw);
    }

    static void consumerFrame(ClassLoader loader, Object parentKey, Runnable preparation, Runnable draw) throws Exception {
        String modern = PREFIX + "mixin.GuiRendererMixin";
        String buffer = "com.naocraftlab.skins.compat.client.identifier.preview.mixin.GuiRendererMixin";
        String selected = loader.getResource(buffer.replace('.', '/') + ".class") != null ? buffer : modern;
        Class<?> wrapper = null;
        try { wrapper = loader.loadClass(selected); } catch (ClassNotFoundException absent) {}
        Object wrapperOwner = consumerOwners.get(parentKey);
        if (wrapperOwner == null && wrapper != null) { wrapperOwner = create(wrapper); consumerOwners.put(parentKey, wrapperOwner); }
        final Object stableWrapper = wrapperOwner;
        Method frame = wrapper == null ? null : java.util.Arrays.stream(wrapper.getDeclaredMethods()).filter(m -> m.getName().equals("nclskins$withConsumerFrame")).findFirst().orElseThrow();
        Method consumed = wrapper == null ? null : java.util.Arrays.stream(wrapper.getDeclaredMethods()).filter(m -> m.getName().equals("nclskins$afterConsumer")).findFirst().orElseThrow();
        if (frame != null) { frame.setAccessible(true); consumed.setAccessible(true); }
        Class<?> nativeType = loader.loadClass(FRAME);
        Object nativeFrame = create(nativeType);
        set(nativeFrame, "renderState", new GuiRenderState());
        if (java.util.Arrays.stream(nativeType.getDeclaredFields()).anyMatch(f -> f.getName().equals("pictureInPictureRendererPools"))) set(nativeFrame, "pictureInPictureRendererPools", new HashMap<>());
        if (java.util.Arrays.stream(nativeType.getDeclaredFields()).anyMatch(f -> f.getName().equals("vertexBuffer"))) set(nativeFrame, "vertexBuffer", new VertexBuffer());
        else { set(nativeFrame, "vertexBuffers", new HashMap<>()); set(nativeFrame, "meshesToDraw", new java.util.ArrayList<>()); }
        set(nativeFrame, "draws", new java.util.ArrayList<>());
        Method original = java.util.Arrays.stream(nativeType.getMethods()).filter(m -> m.getName().equals("nclskins$nativeRender")).findFirst().orElseThrow();
        Method render = java.util.Arrays.stream(nativeType.getMethods()).filter(m -> m.getName().equals("render")).findFirst().orElseThrow();
        boolean buffered = render.getParameterCount() == 1;
        Object slice = new BufferSlice();
        Runnable previousPreparation = framePreparation, previousDraw = consumerDraw;
        Consumer<Object[]> previousFrame = retainedFrame;
        Consumer<Object> previousConsumer = retainedDraw;
        framePreparation = preparation; consumerDraw = draw;
        retainedFrame = args -> {
            Operation<Void> operation = ignored -> { call(original, args[0], buffered ? new Object[]{slice} : new Object[]{}); return null; };
            if (frame == null) operation.call();
            else call(frame, stableWrapper, buffered ? new Object[]{slice, operation} : new Object[]{operation});
        };
        retainedDraw = owner -> {
            Operation<Void> operation = ignored -> { draw.run(); return null; };
            if (consumed == null) operation.call();
            else call(consumed, stableWrapper, buffered ? new Object[]{owner, slice, operation} : new Object[]{owner, operation});
        };
        try { call(render, nativeFrame, buffered ? new Object[]{slice} : new Object[]{}); }
        finally { framePreparation = previousPreparation; consumerDraw = previousDraw; retainedFrame = previousFrame; retainedDraw = previousConsumer; }
    }

    static void consumerControl(String scenario) {
        consumerFinished = false; requireConsumerFence = true;
        fenceFailures = scenario.equals("fence-failure") ? 1 : 0;
    }
    static void consumerSubmitted() { consumerFinished = true; }
    static void resetConsumerControl() { requireConsumerFence = false; fenceFailures = 0; }
    static java.util.List<TextureView> queuedMaterials() { return materials; }
    static void signalConsumers() { var submitted = new java.util.ArrayList<>(fences); fences.clear(); submitted.forEach(Runnable::run); }

    private static void retainedNative(ClassNode node, boolean pip) {
        node.access &= ~Opcodes.ACC_ABSTRACT;
        for (var ctor : node.methods) if (ctor.name.equals("<init>")) ctor.access = Opcodes.ACC_PUBLIC;
        node.signature = null;
        if (!pip) {
            var nativeBody = node.methods.stream().filter(m -> m.name.equals("renderToTexture") && (m.access & Opcodes.ACC_BRIDGE) == 0).findFirst().orElseThrow();
            nativeBody.name = "nclskins$nativeRenderToTexture";
            forwarding(node, "renderToTexture", nativeBody.desc, "retainedInner", Type.getArgumentTypes(nativeBody.desc).length);
            return;
        }
        node.methods.removeIf(m -> (m.access & Opcodes.ACC_ABSTRACT) != 0);
        var nativeBody = node.methods.stream().filter(m -> m.name.equals("prepare")).findFirst().orElseThrow();
        nativeBody.name = "nclskins$nativePrepare";
        forwarding(node, "prepare", nativeBody.desc, "retainedPrepare", Type.getArgumentTypes(nativeBody.desc).length);
        var ready = node.methods.stream().filter(m -> m.name.equals("textureIsReadyToBlit")).findFirst().orElseThrow();
        ready.instructions.clear(); ready.tryCatchBlocks.clear(); ready.localVariables = null;
        ready.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(CpmExtractionBodyProbe.class), "cached", "Z"); ready.visitInsn(Opcodes.IRETURN); ready.visitMaxs(1, 2);
        String state = "Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;";
        boolean deferred = Type.getArgumentTypes(nativeBody.desc).length == 4;
        MethodNode submit = method(node, "renderToTexture", "(" + state + "Lcom/mojang/blaze3d/vertex/PoseStack;" + (deferred ? "Lnet/minecraft/client/renderer/SubmitNodeCollector;" : "") + ")V");
        submit.visitVarInsn(Opcodes.ALOAD, 1); submit.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(GuiState.class));
        submit.visitVarInsn(Opcodes.ALOAD, 2); if (deferred) submit.visitVarInsn(Opcodes.ALOAD, 3); else { submit.visitTypeInsn(Opcodes.NEW, Type.getInternalName(Storage.class)); submit.visitInsn(Opcodes.DUP); submit.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(Storage.class), "<init>", "()V", false); }
        submit.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(CpmExtractionBodyProbe.class), "runInner", "(" + Type.getDescriptor(GuiState.class) + Type.getDescriptor(Pose.class) + Type.getDescriptor(Collector.class) + ")V", false);
        submit.visitInsn(Opcodes.RETURN); submit.visitMaxs(4, 4);
    }

    private static void forwarding(ClassNode node, String name, String desc, String hook, int arguments) {
        var method = method(node, name, desc);
        method.visitIntInsn(Opcodes.BIPUSH, arguments + 1); method.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        for (int i = 0; i <= arguments; i++) {
            method.visitInsn(Opcodes.DUP); method.visitIntInsn(Opcodes.BIPUSH, i);
            method.visitVarInsn(name.equals("prepare") && i == arguments ? Opcodes.ILOAD : Opcodes.ALOAD, i);
            if (name.equals("prepare") && i == arguments) method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
            method.visitInsn(Opcodes.AASTORE);
        }
        method.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(CpmExtractionBodyProbe.class), hook, "([Ljava/lang/Object;)V", false);
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(5, arguments + 1);
    }

    public static void retainedPrepare(Object[] args) { retainedPrepare.accept(args); }
    public static void retainedInner(Object[] args) { retainedInner.accept(args); }

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
        submit.visitVarInsn(Opcodes.ALOAD, 2); submit.visitVarInsn(Opcodes.ALOAD, 3);
        submit.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(CpmExtractionBodyProbe.class), "runInner", "(" + Type.getDescriptor(GuiState.class) + Type.getDescriptor(Pose.class) + Type.getDescriptor(Collector.class) + ")V", false);
        submit.visitInsn(Opcodes.RETURN); submit.visitMaxs(4, 4);
        MethodNode label = method(node, "lambda$prepare$0", "()Ljava/lang/String;"); label.visitLdcInsn("synthetic preview pass"); label.visitInsn(Opcodes.ARETURN); label.visitMaxs(1, 0);
        label.access |= Opcodes.ACC_STATIC;
    }

    static String loadingDescriptor(String descriptor) {
        String suffix="Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V";
        if (descriptor.equals("(Lcom/mojang/blaze3d/pipeline/RenderPipeline;"+suffix)
                || descriptor.equals("(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;"+suffix)
                || descriptor.equals("(Lnet/minecraft/client/gui/GuiPipeline;"+suffix))
            return "(Ljava/lang/Object;Ljava/lang/Object;IIFFIIIIIII)V";
        return descriptor.replace("Lnet/minecraft/resources/Identifier;","Ljava/lang/Object;");
    }

    private static void rendererTerminals(ClassNode node) {
        for (var method : node.methods) for (var instruction : method.instructions.toArray()) {
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode constructor
                    && constructor.owner.equals(Type.getInternalName(EditorPreviewSession.Gallery.class))
                    && constructor.name.equals("<init>") && constructor.desc.equals("()V")) {
                method.instructions.insertBefore(constructor,new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,
                        Type.getInternalName(CpmExtractionBodyProbe.class),"galleryClock","Ljava/util/function/LongSupplier;"));
                constructor.desc="(Ljava/util/function/LongSupplier;)V";
                method.maxStack++;
            }
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("blit")
                    && call.owner.equals("net/minecraft/client/gui/GuiGraphicsExtractor"))
                call.desc = loadingDescriptor(call.desc);
        }
        var helpers = node.methods.stream().filter(method -> java.util.Set.of("playerSkin", "previewPlayer", "applySettling", "createRenderState", "configurePreviewState", "previewElytraStack").contains(method.name)).toList();
        node.methods.removeIf(method -> !java.util.Set.of("render", "body", "prepareBody", "modelFitApplicable", "prepareModelFit", "suppressModelFit", "retireBody", "renderPrepared", "prepareImage", "prepareGalleryImage", "observeBody", "onLiveRenderFailure", "onLiveLayerFailure", "clearAccessoryInspection", "accessoryObserverFor",
                "prepareGalleryBody", "confirmGalleryBody", "renderGallery", "renderRetained", "transformRetained", "retainedTextures", "saveGallerySlot", "selectGallerySlot", "selectGallerySession", "retireGallerySlots", "galleryAccessories", "accessories", "observeAccessories", "close").contains(method.name)
                && !method.name.startsWith("lambda$"));

        init(node);
        node.fields.removeIf(field -> !java.util.Set.of("session", "closed", "previewPlayer", "previewLevel", "previewProfile", "previewClock", "bakedRenderer", "diagnostics", "accessoryObserver", "accessoryObserverSession", "accessoryPreparation", "layerFailureLogged",
                "gallery", "gallerySlots", "activeGallerySource", "bodyObserver", "previewTickGate", "nativeFit").contains(field.name));
        for (var old : helpers) {
            var helper = method(node, old.name, old.desc); helper.access = old.access;
            switch (old.name) {
                case "playerSkin" -> {
                    helper.visitTypeInsn(Opcodes.NEW, Type.getInternalName(Skin.class)); helper.visitInsn(Opcodes.DUP);
                    helper.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(Skin.class), "<init>", "()V", false); helper.visitInsn(Opcodes.ARETURN);
                }
                case "previewPlayer" -> {
                    helper.visitVarInsn(Opcodes.ALOAD, 0); helper.visitVarInsn(Opcodes.ALOAD, 1);
                    helper.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/client/Minecraft", "level", "Lnet/minecraft/client/multiplayer/ClientLevel;");
                    helper.visitFieldInsn(Opcodes.PUTFIELD, node.name, "previewLevel", "Lnet/minecraft/client/multiplayer/ClientLevel;");
                    helper.visitVarInsn(Opcodes.ALOAD, 0); helper.visitVarInsn(Opcodes.ALOAD, 1);
                    helper.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/client/Minecraft", "player", "Lnet/minecraft/client/player/LocalPlayer;");
                    helper.visitFieldInsn(Opcodes.PUTFIELD, node.name, "previewPlayer", "L" + node.name + "$PreviewPlayer;");
                    helper.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(Client.class), "getInstance", "()" + Type.getDescriptor(Client.class), false);
                    helper.visitFieldInsn(Opcodes.GETFIELD, Type.getInternalName(Client.class), "player", Type.getDescriptor(CpmPreviewScopeProbe.Avatar.class)); helper.visitInsn(Opcodes.ARETURN);
                }
                case "createRenderState" -> {
                    helper.visitVarInsn(Opcodes.ALOAD, 3); helper.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(CpmExtractionBodyProbe.class), "makeState", "(Ljava/lang/Object;)" + Type.getDescriptor(Avatar.class), false); helper.visitInsn(Opcodes.ARETURN);
                }
                case "previewElytraStack" -> { helper.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(CpmPreviewScopeProbe.Stack.class), "EMPTY", Type.getDescriptor(CpmPreviewScopeProbe.Stack.class)); helper.visitInsn(Opcodes.ARETURN); }

                default -> helper.visitInsn(Opcodes.RETURN);
            }
            helper.visitMaxs(2, 6);
        }
    }

    static void bindRenderPearl(ClassNode node) {
        for (var method : node.methods) for (var instruction : method.instructions) {
            if (!(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call)) continue;
            if (call.owner.equals("com/mojang/blaze3d/systems/RenderSystem") && call.name.equals("getDevice")
                    && call.desc.equals("()Lcom/mojang/renderpearl/api/device/GpuDevice;")) call.name = "getPearlDevice";
            if (call.owner.equals("com/mojang/renderpearl/api/textures/GpuTextureView")) {
                if (call.getOpcode() != Opcodes.INVOKEINTERFACE || !call.itf || !call.name.equals("close") || !call.desc.equals("()V"))
                    throw new AssertionError("Unexpected native texture-view operation: " + call.name + call.desc);
                call.setOpcode(Opcodes.INVOKEVIRTUAL); call.itf = false;
            }
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

    public static void runInner(GuiState state, Pose pose, Collector collector) {
        Pose previousPose = innerPose; Storage previousStorage = innerStorage;
        innerPose = pose; innerStorage = (Storage) collector;
        try { inner.accept(state); afterInner.run(); }
        finally { innerPose = previousPose; innerStorage = previousStorage; }
    }
    public static Avatar makeState(Object context) { return stateFactory.apply(context); }
    public static class State {}
    public static class Avatar extends State implements MaterialCarrier {
        public float ageInTicks;
        PreviewRenderer.PreviewAppearance appearance;
        public Skin skin = new Skin();
        public void nclskins$setCapeMaterial(Material material) {}
    }
    public interface MaterialCarrier { void nclskins$setCapeMaterial(Material material); }
    public static final class Material {
        public static Material preview(String texture, String selected, boolean cape, boolean glint,
                com.naocraftlab.skins.client.TextureUseValidity validity) { return new Material(); }
    }
    public static final class AccessoryProjection {
        static boolean cape = true, elytra = true;
        static com.naocraftlab.skins.client.PreviewAccessories.Availability forcedAvailability;
        static java.util.UUID profile;
        static String skin;
        static com.naocraftlab.skins.client.SkinModel model;
        static com.naocraftlab.skins.client.PreviewAccessories value;

        public static com.naocraftlab.skins.client.PreviewModelFrames.BodyRequirements previewBodyRequirements(java.util.UUID id) { return com.naocraftlab.skins.runtime.CapeProjection.previewBodyRequirements(id); }
        public static com.naocraftlab.skins.runtime.EffectiveSkinSource skinSource(java.util.UUID id) { return com.naocraftlab.skins.runtime.CapeProjection.skinSource(id); }
        public static com.naocraftlab.skins.client.PreviewAccessories previewAccessories(java.util.UUID id, String texture,
                com.naocraftlab.skins.client.SkinModel selectedModel, PreviewRenderer.LiveReason reason) {
            profile = id; skin = texture; model = selectedModel;
            if (forcedAvailability != null) return new com.naocraftlab.skins.client.PreviewAccessories(null, 0,
                    forcedAvailability, false, false, reason);
            value = reason == PreviewRenderer.LiveReason.AVAILABLE
                    ? new com.naocraftlab.skins.client.PreviewAccessories(id, 1, com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED, cape, elytra, reason)
                    : com.naocraftlab.skins.client.PreviewAccessories.unavailable(reason);
            return value;
        }
        public static void retireSkin(java.util.UUID id) {}
    }
    public static final class Graphics {
        static final java.util.List<CpmBakedDisplayProbe.LoadingFrame> loadingFrames = new java.util.ArrayList<>();

        public void blit(Object pipeline, CpmOwnedPreviewDispatchProbe.Text texture, int x, int y, float u, float v,
                int width, int height, int sourceWidth, int sourceHeight, int textureWidth, int textureHeight, int color) {
            loadingFrames.add(new CpmBakedDisplayProbe.LoadingFrame(texture.getString(), x, y, u, v, width, height,
                    sourceWidth, sourceHeight, textureWidth, textureHeight));
        }
        public void blit(Object pipeline, Object texture, int x, int y, float u, float v,
                int width, int height, int sourceWidth, int sourceHeight, int textureWidth, int textureHeight, int color) {
            loadingFrames.add(new CpmBakedDisplayProbe.LoadingFrame(texture.toString(), x, y, u, v, width, height,
                    sourceWidth, sourceHeight, textureWidth, textureHeight));
        }
        static GuiState queued;
        static final java.util.List<GuiState> states = new java.util.ArrayList<>();
        static final java.util.Map<GuiState,Integer> strata = new java.util.IdentityHashMap<>();
        private int stratum;
        public void nextStratum() { stratum++; }
        public void enableScissor(int left, int top, int right, int bottom) { throw new AssertionError("Unexpected Gallery PIP scissor"); }
        public void disableScissor() {}
        public void entity(State state, float scale, org.joml.Vector3fc offset, org.joml.Quaternionfc rotation, org.joml.Quaternionfc camera, int x0, int y0, int x1, int y1) {
            queued = new GuiState(state, x0, y0, x1, y1, scale, new org.joml.Vector3f(offset),
                    new org.joml.Quaternionf(rotation), camera == null ? null : new org.joml.Quaternionf(camera));
            states.add(queued); strata.put(queued,stratum);
        }
        public void entity(State state, float scale, org.joml.Vector3f offset, org.joml.Quaternionf rotation, org.joml.Quaternionf camera, int x0, int y0, int x1, int y1) {
            entity(state, scale, (org.joml.Vector3fc) offset, (org.joml.Quaternionfc) rotation,
                    (org.joml.Quaternionfc) camera, x0, y0, x1, y1);
        }
    }
    public static final class Baked {
        public void render(Graphics graphics, PreviewRenderer.PreviewRequest request) {}
        public void close() {}
    }
    public interface PipState { int x0(); int x1(); int y0(); int y1(); float scale(); default org.joml.Matrix3x2fc pose() { return new org.joml.Matrix3x2f(); } default Rectangle scissorArea() { return null; } }
    public record GuiState(State renderState, int x0, int y0, int x1, int y1, float scale,
            org.joml.Vector3f translation, org.joml.Quaternionf rotation, org.joml.Quaternionf overrideCameraAngle) implements PipState {
        public GuiState(State renderState, int x0, int y0, int x1, int y1, float scale) {
            this(renderState, x0, y0, x1, y1, scale, new org.joml.Vector3f(), new org.joml.Quaternionf(), null);
        }
        public GuiState(State renderState) { this(renderState, 0, 0, 16, 16, 1); }
    }
    public static final class GuiRenderState {
        public Panorama panoramaRenderState;
        public void addBlitToCurrentLayer(Blit blit) { blitBoundary.run(); lastMaterial = blit.setup.view; materials.add(lastMaterial); }
        public void submitBlitToCurrentLayer(Blit blit) { addBlitToCurrentLayer(blit); }
        public void reset() {}
    }
    static void bindAppearance(PreviewRenderer.PreviewAppearance appearance) { submit.accept(appearance); }

    static void submissionBoundaries(Runnable binding, Runnable cleanup) {
        submissionBoundaries(ignored -> binding.run(), cleanup);
    }

    static void submissionBoundaries(Consumer<PreviewRenderer.PreviewAppearance> binding, Runnable cleanup) {
        submit = binding; late = cleanup;
    }

    static void bakedBoundaries(Runnable lateBoundary, Runnable passBoundary, Runnable frameBoundary) {
        late = lateBoundary; passClose = passBoundary; frameClose = frameBoundary;
    }
    public interface Collector {
        default Collector order(int order) { return this; }
        default void submitModel(CpmBakedDisplayProbe.Model model, Object state, Pose pose,
                CpmBakedDisplayProbe.Identifier texture, int light, int overlay, int color) { bakedPayload(this, texture); CpmBakedDisplayProbe.primary(); }
        default void submitModel(CpmBakedDisplayProbe.Model model, Object state, Pose pose,
                CpmBakedDisplayProbe.Identifier texture, int light, int overlay, int color, CpmBakedDisplayProbe.Overlay crumbling) { bakedPayload(this, texture); CpmBakedDisplayProbe.primary(); }
        default void submitModel(CpmBakedDisplayProbe.Model model, Object state, Pose pose,
                CpmBakedDisplayProbe.RenderType type, int light, int overlay, int color) { bakedPayload(this, type.texture); CpmBakedDisplayProbe.primary(); }
        default void submitModel(CpmBakedDisplayProbe.Model model, Object state, Pose pose,
                CpmBakedDisplayProbe.RenderType type, int light, int overlay, int color, CpmBakedDisplayProbe.Overlay crumbling) { bakedPayload(this, type.texture); CpmBakedDisplayProbe.primary(); }
    }
    private static void bakedPayload(Collector collector, Object texture) {
        if (collector instanceof Storage storage) {
            storage.payload = texture;
            if (texture != null) storage.payloads.add(texture);
        }
        if (RenderSystem.outputColorTextureOverride != null) RenderSystem.outputColorTextureOverride.texture.data = texture;
    }
    public static final class Storage implements Collector {
        Object payload;
        final java.util.List<Object> payloads = new java.util.ArrayList<>();
    }
    public static final class Pose {
        public void pushPose() { CpmBakedDisplayProbe.push(); }
        public void popPose() { CpmBakedDisplayProbe.pop(); }
        public void translate(float x, float y, float z) {} public void scale(float x, float y, float z) {} public void mulPose(org.joml.Quaternionfc value) {} public void rotate(org.joml.Quaternionfc value) {}
    }
    public static final class Skin { public Texture cape() { return null; } }
    public static final class Texture {}
    public static final class Dispatcher {
        public State extractEntity(CpmPreviewScopeProbe.Entity entity, float partialTick) { return new Avatar(); }
        public void submit(State state, CpmPreviewScopeProbe.Camera camera, double x, double y, double z, Pose pose, Collector collector) {
            if (RenderSystem.outputColorTextureOverride != null) RenderSystem.outputColorTextureOverride.texture.data = state instanceof Avatar avatar && avatar.appearance != null ? avatar.appearance : state;
            if (collector instanceof Storage storage) {
                storage.payload = state instanceof Avatar avatar && avatar.appearance != null ? avatar.appearance : state;
                storage.payloads.add(storage.payload);
            }
            if (state instanceof Avatar avatar && measurement.test(avatar)) return;
            submits++; submit.accept(state instanceof Avatar avatar ? avatar.appearance : null);
        }
    }
    public static final class Features {
        public Storage getSubmitNodeStorage() { return new Storage(); }
        public void renderAllFeatures() { flushes++; late.run(); }
        public void renderAllFeatures(Storage storage) {
            if (RenderSystem.outputColorTextureOverride != null)
                RenderSystem.outputColorTextureOverride.texture.materialInputs = java.util.List.copyOf(storage.payloads);
            storage.payloads.clear();
            renderAllFeatures();
        }
        public PreparedFrame prepareFrame(Storage storage) {
            var frame = new PreparedFrame(storage.payload, storage.payloads);
            storage.payloads.clear();
            return frame;
        }
        public static void renderAllFeatures(Pass pass, PreparedFrame frame) {
            if (pass.target() != null && frame.payload != null) {
                pass.target().texture.data = frame.payload;
                pass.target().texture.materialInputs = frame.payloads;
            }
            flushes++; late.run();
        }
    }
    public static final class PreparedFrame implements AutoCloseable {
        final Object payload;
        final java.util.List<Object> payloads;
        public PreparedFrame(Object payload) { this(payload, payload == null ? java.util.List.of() : java.util.List.of(payload)); }
        public PreparedFrame(Object payload, java.util.List<Object> payloads) {
            this.payload = payload;
            this.payloads = java.util.List.copyOf(payloads);
        }
        public void close() { frameClose.run(); }
    }
    public static final class GameRenderer {
        public MainRenderTarget mainRenderTarget() { return Client.current.getMainRenderTarget(); }
        public CpmPreviewScopeProbe.Light getLighting() { return new CpmPreviewScopeProbe.Light(); }
        public CpmPreviewScopeProbe.Light lighting() { return getLighting(); }
        public Features getFeatureRenderDispatcher() { return new Features(); }
    }
    public static final class RenderBuffers { public CpmBakedDisplayProbe.Buffers bufferSource() { return new CpmBakedDisplayProbe.Buffers(); } }
    public static final class Client {
        public static Client current;
        public static Client getInstance() { return current; }
        public final GameRenderer gameRenderer = new GameRenderer();
        public CpmPreviewScopeProbe.Level level = new CpmPreviewScopeProbe.Level();
        public CpmPreviewScopeProbe.Avatar player;
        public final Options options = new Options();
        public Connection connection;
        public Connection getConnection() { return connection; }
        public boolean isSameThread() { return true; }
        public RenderBuffers renderBuffers() { return new RenderBuffers(); }
        public MainRenderTarget getMainRenderTarget() { return new MainRenderTarget(); }
        public Dispatcher getEntityRenderDispatcher() { return new Dispatcher(); }
    }

    public static final class Connection {}
    public static final class Options {
        final java.util.Set<CpmPreviewScopeProbe.Part> parts = java.util.EnumSet.allOf(CpmPreviewScopeProbe.Part.class);
        public String mainHand = "LEFT";
        public int saves, broadcasts;
        public boolean isModelPartEnabled(CpmPreviewScopeProbe.Part part) { return parts.contains(part); }
        public void setModelPart(CpmPreviewScopeProbe.Part part, boolean enabled) { if (enabled) parts.add(part); else parts.remove(part); }
        public void save() { saves++; }
        public void broadcastOptions() { broadcasts++; }
    }
    public interface DepthTarget { GpuTexture getDepthTexture(); }
    public static final class MainRenderTarget implements DepthTarget {
        public LegacyTexture getDepthTexture() { return new LegacyTexture(16, 16, Format.D32_FLOAT); }
    }
    public static final class LegacyTexture implements GpuTexture {
        Object data;
        java.util.List<Object> materialInputs = java.util.List.of();
        boolean closed;
        int closes;
        final int width, height;
        final Format format;
        public LegacyTexture() { this(16,16); }
        public LegacyTexture(int width, int height) { this(width, height, Format.RGBA8_UNORM); }
        public LegacyTexture(int width, int height, Format format) { this.width = width; this.height = height; this.format = format; }
        public Format getFormat() { return format; }
        public int getWidth(int mip) { return width; } public int getHeight(int mip) { return height; }
        public void close() { check(!closed, "duplicate physical native texture close"); closed = true; closes++; }
    }
    public interface GpuTexture { Format getFormat(); int getWidth(int mip); int getHeight(int mip); void close(); }
    public static final class TextureView {
        final LegacyTexture texture;
        public TextureView(LegacyTexture texture) { this.texture = texture; }
        public void close() {}
    }
    public enum Format { RGBA8_UNORM, D32_FLOAT, RGBA8, DEPTH32 }
    public enum Filter { NEAREST }
    public static final class Sampler {}
    public static final class SamplerCache { public Sampler getRepeat(Filter filter) { return new Sampler(); } }
    public static final class Pipeline {
        public static final Pipeline TEXTURED_PREMULTIPLIED_ALPHA = new Pipeline();
        public static void updateSortKeySeed() {}
    }
    public static final class Pipelines { public static final Pipeline GUI_TEXTURED = new Pipeline(); public static final Pipeline GUI_TEXTURED_PREMULTIPLIED_ALPHA = new Pipeline(); }
    public record TextureSetup(TextureView view) {
        public static TextureSetup singleTexture(TextureView view, Sampler sampler) { return new TextureSetup(view); }
        public static void updateSortKeySeed() {}
    }
    public record Blit(Pipeline pipeline, TextureSetup setup, org.joml.Matrix3x2fc pose,
            int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1, int color, Rectangle scissor, Rectangle bounds) {
        public Blit(Pipeline pipeline, TextureSetup setup, org.joml.Matrix3x2fc pose,
                int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1, int color,
                CpmBakedDisplayProbe.Rectangle scissor, CpmBakedDisplayProbe.Rectangle bounds) {
            this(pipeline, setup, pose, x0,y0,x1,y1,u0,u1,v0,v1,color,(Rectangle)null,null);
        }
    }
    public static final class Rectangle {}
    public static final class Projection { public void setupOrtho(float near, float far, float width, float height, boolean invert) {} }
    public static final class ProjectionBuffer {
        public ProjectionBuffer(String name) {}
        public ProjectionBuffer(String name, float near, float far, boolean flip) {}
        public BufferSlice getBuffer(float width, float height) { return new BufferSlice(); }
        public BufferSlice getBuffer(Projection projection) { return new BufferSlice(); }
        public void close() {}
    }
    public static final class BufferSlice {}
    public enum ProjectionType { ORTHOGRAPHIC }
    public interface ProfilerFiller { default void push(String name) {} default void popPush(String name) {} default void pop() {} }
    public static final class Profiler { public static ProfilerFiller get() { return new ProfilerFiller() {}; } }
    public static final class VertexBuffer { public void upload() {} public void endDraw() {} public void endFrame() {} }
    public static final class Constants { public static boolean DEBUG_SHUFFLE_UI_RENDERING_ORDER; }
    public static final class Panorama { public float spin() { return 0; } }
    public static final class CubeMap { public void render(float speed, float spin) {} }
    public interface LegacyDeviceApi {
        default LegacyTexture createTexture(java.util.function.Supplier<String> name, int usage, Format format, int width, int height, int layers, int levels) { return new LegacyTexture(width, height, format); }
        default TextureView createTextureView(LegacyTexture texture) { return new TextureView(texture); }
        default LegacyEncoderApi createCommandEncoder() { return new LegacyEncoder(); }
    }
    public interface LegacyEncoderApi {
        default void clearColorAndDepthTextures(LegacyTexture color, int clear, LegacyTexture depth, double clearDepth) { color.data = null; }
    }
    public static final class LegacyDevice implements LegacyDeviceApi {
        public LegacyTexture createTexture(java.util.function.Supplier<String> name, int usage, Format format, int width, int height, int layers, int levels) { return new LegacyTexture(width, height, format); }
        public TextureView createTextureView(LegacyTexture texture) { return new TextureView(texture); }
        public LegacyEncoder createCommandEncoder() { return new LegacyEncoder(); }
    }
    public static final class LegacyEncoder implements LegacyEncoderApi {
        public void clearColorAndDepthTextures(LegacyTexture color, int clear, LegacyTexture depth, double clearDepth) { color.data = null; }
        public void clearColorAndDepthTextures(LegacyTexture color, org.joml.Vector4fc clear, LegacyTexture depth, double clearDepth) { color.data = null; }
    }
    public interface Device {
        default GpuTexture createTexture(java.util.function.Supplier<String> name, int usage, Format format, int width, int height, int layers, int levels) { return new LegacyTexture(width, height, format); }
        default TextureView createTextureView(GpuTexture texture) { return new TextureView((LegacyTexture) texture); }
        default Encoder createCommandEncoder() { return new Encoder() {}; }
    }
    public interface Encoder {
        default Pass createRenderPass(java.util.function.Supplier<String> name, TextureView color, java.util.Optional<?> clear, TextureView depth, java.util.OptionalDouble clearDepth) { return new Pass() {
            public TextureView target() { return color; }
            public void close() { passClose.run(); }
        }; }
        default void clearColorAndDepthTextures(GpuTexture color, org.joml.Vector4fc clear, GpuTexture depth, double clearDepth) { ((LegacyTexture) color).data = null; }
        default Pass createRenderPass(java.util.function.Supplier<String> name, PassLayout layout,
                TextureView color, TextureView... attachments) {
            if (layout != PassLayout.COLOR_DEPTH || attachments.length != 1) {
                throw new AssertionError("Expected one depth attachment for the compiled preview pass");
            }
            return createRenderPass(name, color, java.util.Optional.empty(), attachments[0], java.util.OptionalDouble.empty());
        }
    }
    public interface Pass extends AutoCloseable { default TextureView target() { return null; } void close(); }
    static void bindRenderPassLayout(Map<String, Class<?>> types) {
        types.put("com/mojang/renderpearl/api/pipeline/RenderPassLayout", PassLayout.class);
        types.put("net/minecraft/client/renderer/RenderPassLayouts", PassLayouts.class);
    }
    public enum PassLayout { COLOR_DEPTH }
    public static final class PassLayouts {
        public static final PassLayout COLOR_DEPTH = PassLayout.COLOR_DEPTH;
    }
    public static final class RenderSystem {
        public static LegacyDeviceApi getLegacyDevice() { return new LegacyDevice(); }
        public static void queueFencedTask(Runnable completion) {
            if (requireConsumerFence && !shareBackend) check(consumerFinished, "fence is created after actual GUI consumer");
            if (fenceFailures > 0) { fenceFailures--; throw new IllegalStateException("synthetic fence enqueue failure"); }
            fences.add(completion);
        }
        public static TextureView outputColorTextureOverride;
        public static TextureView outputDepthTextureOverride;
        public static org.joml.Matrix4fStack getModelViewStack() { return new org.joml.Matrix4fStack(16); }
        public static LegacyDevice getDevice() { return new LegacyDevice(); }
        public static Device getPearlDevice() { return new Device() {}; }
        public static SamplerCache getSamplerCache() { return new SamplerCache(); }
        public static void setProjectionMatrix(BufferSlice buffer, ProjectionType type) {}
        public static void resizeAllAutoStorageIndexBuffers() {}
        public static void bindDefaultUniforms(Pass pass) {}
    }
}
