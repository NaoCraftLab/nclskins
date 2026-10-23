package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import com.naocraftlab.skins.compat.cpm.CpmFrame;
import com.naocraftlab.skins.compat.cpm.CpmIntegration;
import com.naocraftlab.skins.compat.cpm.CpmPlayerAccess;
import com.naocraftlab.skins.compat.cpm.CpmTextureLoader;
import com.naocraftlab.skins.compat.cpm.OwnedCpmDefinition;
import com.naocraftlab.skins.runtime.CapeProjection;
import com.tom.cpl.config.ModConfigFile;
import com.tom.cpl.tag.AllTagManagers;
import com.tom.cpl.util.Image;
import com.tom.cpl.util.ImageIO.IImageIO;
import com.tom.cpl.util.ILogger;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.MinecraftCommonAccess;
import com.tom.cpm.shared.MinecraftObjectHolder;
import com.tom.cpm.shared.animation.AnimationState;
import com.tom.cpm.shared.config.ConfigKeys;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.config.SocialConfig;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.definition.ModelDefinitionLoader;
import com.tom.cpm.shared.definition.SafetyException;
import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.io.SkinDataOutputStream;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.network.NetHandler;
import com.tom.cpm.shared.parts.IModelPart;
import com.tom.cpm.shared.parts.IResolvedModelPart;
import com.tom.cpm.shared.parts.ModelPartType;
import com.tom.cpm.shared.parts.ModelPartUUIDLockout;
import com.tom.cpm.shared.skin.PlayerTextureLoader;
import com.tom.cpm.shared.skin.TextureType;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class CpmBehaviorProbe {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PREVIEW = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final ConcurrentLinkedQueue<Runnable> GAME = new ConcurrentLinkedQueue<>();
    private static final Spy NET = new Spy();
    private static final AtomicInteger AUXILIARY = new AtomicInteger();
    private static final AtomicInteger RESOURCE = new AtomicInteger();
    private static ModelDefinitionLoader<UUID> loader;
    private static FakePlayer world;
    private static AllTagManagers tags;
    private static ModConfigFile config;

    static void initializeHost(File configuration) {
        config = new ModConfigFile(configuration);
        ILogger logger = proxy(ILogger.class, (name, values) -> null);
        MinecraftObjectHolder.setCommonObject(proxy(MinecraftCommonAccess.class, (name, values) -> switch (name) {
            case "getConfig" -> config;
            case "getLogger" -> logger;
            case "getBlockStateHandler" -> CpmHostTags.BLOCKS;
            case "getItemStackHandler" -> CpmHostTags.ITEMS;
            case "getEntityTypeHandler" -> CpmHostTags.ENTITIES;
            default -> null;
        }));
        IImageIO images = proxy(IImageIO.class, (name, values) -> {
            if (name.equals("write")) {
                Image image = (Image) values[0];
                var png = new java.awt.image.BufferedImage(image.getWidth(), image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                png.setRGB(0, 0, image.getWidth(), image.getHeight(), image.getData(), 0, image.getWidth());
                javax.imageio.ImageIO.write(png, "PNG", (java.io.OutputStream) values[1]);
                return null;
            }
            if (name.equals("getSize")) {
                var png = javax.imageio.ImageIO.read((java.io.InputStream) values[0]);
                return new com.tom.cpl.math.Vec2i(png.getWidth(), png.getHeight());
            }
            if (!name.equals("read")) throw new AssertionError("Unexpected image operation " + name);
            var image = values[0] instanceof java.io.InputStream in ? javax.imageio.ImageIO.read(in)
                    : javax.imageio.ImageIO.read((File) values[0]);
            return new Image(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()), image.getWidth());
        });
        MinecraftObjectHolder.setClientObject(proxy(MinecraftClientAccess.class, (name, values) -> switch (name) {
            case "getBuiltinTags" -> tags;
            case "getImageIO" -> images;
            case "getDefinitionLoader" -> loader;
            case "getCurrentClientPlayer", "getClientPlayer" -> world;
            case "getConnectedServer" -> "fixture.invalid";
            case "getNetHandler" -> NET;
            case "getProxy" -> java.net.Proxy.NO_PROXY;
            case "isInGame" -> true;
            case "requiresSelfEventForAnimation" -> false;
            case "setModelScale" -> throw new AssertionError("Preview must not mutate real-player scale");
            case "executeOnGameThread" -> { GAME.add((Runnable) values[0]); yield null; }
            default -> null;
        }));
        tags = new AllTagManagers();
        world = new FakePlayer(WORLD);
        loader = new ModelDefinitionLoader<>(FakePlayer::new, id -> id, id -> "Fixture");
    }

    public static void main(String[] args) throws Exception {
        initializeHost(new File(args[0]));
        carrierSemantics(new File(args[1]));
        CpmIntegration.initialize((collection, name, model) -> {
            RESOURCE.incrementAndGet();
            throw new IOException("Resource input unavailable in fake host");
        });
        try (var registration = CapeProjection.installEvents(events())) {
            if (args.length > 2 && args[2].equals("body")) {
                nonDrawingBodyReadinessAndPreviewStatus();
                System.out.println("PASS focused actual primary binding and displayed-body producers");
                return;
            }
            environmentSettings();
            typedTexturesAndPriority();
            generationScopedRefresh();
            previewPolicyAndPackets();
            frameRetirement();
            CpmMaterialBoundaryProbe.sharedSheets(loader, world, registration);
            CpmAccessoryProbe.run(loader, world, registration, new File(args[1]));
            nonDrawingBodyReadinessAndPreviewStatus();
            CpmMaterialBoundaryProbe.sneakyPreview(loader, new File(args[1]));
            if (Boolean.getBoolean("nclskins.cpm.materialProbe")) {
                var previousSource = ((CpmPlayerAccess) (Object) world).nclskins$source();
                try { CpmMaterialBoundaryProbe.run(loader, world, registration); }
                finally { ((CpmPlayerAccess) (Object) world).nclskins$source(previousSource); }
            }
            pausedPreviewAnimation(new File(args[1]));
            resolveRetirement();
            coldObservationDoesNotWriteConfig(new File(args[0] + ".cold"), new File(args[1]));
        }
        MinecraftCapeFallbackProbe.lifecycle(loader, CpmBehaviorProbe::drain, new File(args[1]));
        lateDecodeCannotSignalNewConnection();
        System.out.println("Transformed CPM behavior passed: textures, priority, subject policy, packets, decode/resolve/frame retirement");
    }

    static void assertDecodedSource(com.naocraftlab.skins.runtime.EffectiveSkinSource source) throws Exception {
        FakePlayer player = new FakePlayer(source.cacheId());
        ((CpmPlayerAccess) (Object) player).nclskins$source(source);
        int auxiliaryBefore = AUXILIARY.get();
        Image pixels = player.getTextures().getTexture(TextureType.SKIN).get(5, TimeUnit.SECONDS);
        check(pixels != null && java.util.Arrays.equals(pixels.getData(), source.pixels()), "CPM receives the captured generation without an official lookup");
        ModelDefinition definition = loader.loadModel(pixels, player);
        check(definition instanceof OwnedCpmDefinition && definition.getError() == null, "captured local/official source decodes through pinned CPM");
        definition.resolveAll();
        check(definition.doRender() && AUXILIARY.get() == auxiliaryBefore, "captured source resolves without fetching official skin");
        definition.cleanup();
    }

    private static void nonDrawingBodyReadinessAndPreviewStatus() throws Exception {
        UUID id = new UUID(0, 307);
        publish(id, WORLD, "fixture:body-readiness", 7, true);
        Player<?> player = loader.loadPlayer(id, ModelDefinitionLoader.PLAYER_UNIQUE);
        ModelDefinition definition = player.getDefinitionFuture().get(5, TimeUnit.SECONDS);
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureHandle("fixture:body-readiness", 64, 64),
                SkinModel.CLASSIC, Optional.empty(), PreviewRenderer.CapeMode.OFF, true);
        player.setModelDefinition(new CompletableFuture<>(), false);
        check(CpmIntegration.preparePreview(id, appearance).status() == PreviewRenderer.BodyStatus.PENDING,
                "hidden visible pass still observes pending native decode");
        player.setModelDefinition(CompletableFuture.completedFuture(definition), false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        com.naocraftlab.skins.client.PreviewModelFrames.Body ready;
        do {
            ready = CpmIntegration.preparePreview(id, appearance);
            drain();
            if (ready.status() == PreviewRenderer.BodyStatus.PENDING) Thread.sleep(1);
        } while (ready.status() == PreviewRenderer.BodyStatus.PENDING && System.nanoTime() < deadline);
        check(ready.status() == PreviewRenderer.BodyStatus.READY && ready.current().getAsBoolean(),
                "profile query advances real pinned CPM to body-ready without a drawing pass");
        int auxiliaryBefore = AUXILIARY.get(), resourceBefore = RESOURCE.get(), packetsBefore = NET.preview.get() + NET.world.get() + NET.events.get();
        try (var resources = ((OwnedCpmDefinition) definition).acquire()) {
            check(resources.body().status() == PreviewRenderer.BodyStatus.ORDINARY, "resource lease does not prove body");
        }
        for (var part : new com.tom.cpm.shared.model.render.VanillaModelPart[]{null,
                com.tom.cpm.shared.model.RootModelType.CAPE, com.tom.cpm.shared.model.RootModelType.ELYTRA_LEFT,
                com.tom.cpm.shared.model.RootModelType.ARMOR_BODY}) {
            Object model = new Object();
            var negativeManager = new com.tom.cpm.shared.model.render.ModelRenderManager<Object, Object, Object, Object>() {
                {
                    var holder = new RedirectHolder<Object, Object, Object, Object>(this, model) {
                        protected void swapIn0() {}
                        protected void swapOut0() {}
                    };
                    if (part != null) holder.redirectRenderers.add(proxy(RedirectRenderer.class,
                            (name, values) -> name.equals("getPart") ? part : null));
                    holders.put(model, holder);
                }
            };
            try (var frame = CpmFrame.open(appearance)) {
                negativeManager.bindModel(model, null, null, definition, player, new AnimationState());
                check(negativeManager.isBound(model) && frame.body().status() == PreviewRenderer.BodyStatus.ORDINARY,
                        "successful no-op/accessory/armor binding is not primary body");
            }
        }
        Object rejected = new Object();
        var failedBind = new com.tom.cpm.shared.model.render.ModelRenderManager<Object, Object, Object, Object>() {
            {
                holders.put(rejected, new RedirectHolder<Object, Object, Object, Object>(this, rejected) {
                    protected void swapIn0() { throw new IllegalStateException("synthetic bind failure"); }
                    protected void swapOut0() {}
                });
            }
        };
        try (var frame = CpmFrame.open(appearance)) {
            try {
                failedBind.bindModel(rejected, null, null, definition, player, new AnimationState());
                throw new AssertionError("native original bind must fail");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().equals("synthetic bind failure"), "original bind failure remains authoritative");
            }
            check(frame.body().status() == PreviewRenderer.BodyStatus.ORDINARY, "failed native bind never proves body");
        }
        Object unknown = new Object();
        var noHolder = new com.tom.cpm.shared.model.render.ModelRenderManager<Object, Object, Object, Object>() {};
        noHolder.setFactory(new com.tom.cpm.shared.model.render.ModelRenderManager.RedirectHolderFactory<Object, Object, Object>() {
            @Override public <M> com.tom.cpm.shared.model.render.ModelRenderManager.RedirectHolder<?, Object, Object, Object> create(M model, String arg) {
                return null;
            }
        });
        try (var frame = CpmFrame.open(appearance)) {
            noHolder.bindModel(unknown, null, null, definition, player, new AnimationState());
            check(!noHolder.isBound(unknown) && frame.body().status() == PreviewRenderer.BodyStatus.ORDINARY,
                    "factory-null original bind never creates body witness");
            noHolder.setFactory(null);
            CpmFrame.bound(noHolder, new Object(), null, (OwnedCpmDefinition) definition, player, () -> {});
            check(frame.body().status() == PreviewRenderer.BodyStatus.ORDINARY,
                    "passive readback make=false never calls missing factory");
        }
        check(AUXILIARY.get() == auxiliaryBefore && RESOURCE.get() == resourceBefore && (NET.preview.get() + NET.world.get() + NET.events.get()) == packetsBefore,
                "body bindings and queries create no resolution, resource or network demand");
        var display = new com.naocraftlab.skins.client.EditorPreviewSession();
        var completion = display.displayBody(() -> true);
        Object primary = new Object();
        var primaryManager = new com.tom.cpm.shared.model.render.ModelRenderManager<Object, Object, Object, Object>() {
            {
                var holder = new RedirectHolder<Object, Object, Object, Object>(this, primary) {
                    protected void swapIn0() {}
                    protected void swapOut0() {}
                };
                holder.redirectRenderers.add(proxy(RedirectRenderer.class, (name, values) ->
                        name.equals("getPart") ? com.tom.cpm.shared.model.PlayerModelParts.BODY : null));
                holders.put(primary, holder);
            }
        };
        com.naocraftlab.skins.client.PreviewModelFrames.Body candidate;
        try (var frame = CpmFrame.open(appearance)) {
            check(frame.body().status() == PreviewRenderer.BodyStatus.ORDINARY, "opening a frame is not a body binding");
            primaryManager.bindModel(primary, null, null, definition, player, new AnimationState());
            candidate = frame.body();
            check(candidate.status() == PreviewRenderer.BodyStatus.READY,
                    "successful genuine primary BODY binding exposes actual frame candidate");
            check(display.body().status() == PreviewRenderer.BodyStatus.ORDINARY, "binding alone is not completed display");
        }
        check(!primaryManager.isBound(primary), "candidate capture precedes owned unbind cleanup");
        completion.accept(candidate);
        check(display.body().status() == PreviewRenderer.BodyStatus.READY && candidate.current().getAsBoolean(),
                "completed native binding survives lexical frame cleanup without Editor wait");
        Runnable binding = () -> primaryManager.bindModel(primary, null, null, definition, player, new AnimationState());
        Runnable cleanupFailure = () -> {
            binding.run();
            CpmFrame.bound(primaryManager, primary, null, (OwnedCpmDefinition) definition, player,
                    () -> { throw new IllegalStateException("synthetic owned cleanup failure"); });
        };
        CpmPreviewScopeProbe.bodyProducer(appearance, binding, cleanupFailure);
        CpmPreviewScopeProbe.submissionBodyProducer(appearance, binding, cleanupFailure);
        CpmExtractionBodyProbe.bodyProducer(appearance, binding, cleanupFailure);
        CpmBakedDisplayProbe.bodyProducer(appearance);
        check(AUXILIARY.get() == auxiliaryBefore && RESOURCE.get() == resourceBefore
                        && (NET.preview.get() + NET.world.get() + NET.events.get()) == packetsBefore,
                "native body producers create no resolution, resource or network demand");
        Player.setEnableRendering(false);
        check(display.body().status() == PreviewRenderer.BodyStatus.ORDINARY, "passive body query rechecks rendering policy");
        check(CpmIntegration.preparePreview(id, appearance).status() == PreviewRenderer.BodyStatus.BLOCKED,
                "readiness cannot bypass global rendering policy");
        Player.setEnableRendering(true);
        var exceptional = new ModelDefinition(new IllegalStateException("synthetic-private-error"), player);
        player.setModelDefinition(CompletableFuture.completedFuture(exceptional), false);
        check(CpmIntegration.preparePreview(id, appearance).status() == PreviewRenderer.BodyStatus.FAILED,
                "non-owned exceptional native definition is failed, never body-ready");
        var manager = new com.tom.cpm.shared.model.RenderManager<UUID, Object, Object, Object>(null, loader, value -> (UUID) value);
        check(manager.getStatus(id, ModelDefinitionLoader.PLAYER_UNIQUE) == null,
                "actual transformed preview getStatus suppresses non-owned exceptional diagnostic");
        UUID ordinary = new UUID(0, 308);
        Player<?> worldPlayer = loader.loadPlayer(ordinary, ModelDefinitionLoader.PLAYER_UNIQUE);
        worldPlayer.setModelDefinition(CompletableFuture.completedFuture(new ModelDefinition(new IllegalStateException("synthetic-world-error"), worldPlayer)), false);
        check(manager.getStatus(ordinary, ModelDefinitionLoader.PLAYER_UNIQUE) != null,
                "actual transformed world/nonpreview status invokes original");
        check(!ready.current().getAsBoolean(), "replaced native definition retires captured body-ready frame");
        check(!candidate.current().getAsBoolean(), "replaced native definition retires captured actual body witness");
        CapeProjection.retireSkin(id);
        definition.cleanup();
        drain();
        System.out.println("Non-drawing BODY readiness and preview-only status: pending, ready, blocked, non-owned failure, world original and retired-frame controls passed");
    }

    private static void coldObservationDoesNotWriteConfig(File file, File fixtures) throws Exception {
        ModConfigFile previous = config;
        UUID id = new UUID(0, 191);
        var source = CapeProjection.effectiveSkin(id, id, "fixture:cold", SkinModel.CLASSIC, false);
        FakePlayer player = new FakePlayer(id);
        ((CpmPlayerAccess) (Object) player).nclskins$source(source);
        try {
            config = new ModConfigFile(file);
            var keys = java.util.Set.copyOf(config.keySet());
            player.setModelDefinition(CompletableFuture.completedFuture(null), false);
            player.getModelDefinition();
            check(java.util.Set.copyOf(config.keySet()).equals(keys),
                    "cold tick observation must not mutate upstream configuration maps");
            check(source.accessories().empty(), "resolved absence remains confirmed without policy re-evaluation");
            Image image;
            try (var input = new java.io.FileInputStream(new File(fixtures, "accessory-geometry-sheet.png"))) {
                image = Image.loadFrom(input);
            }
            ModelDefinition definition = loader.loadModel(image, player);
            definition.resolveAll();
            player.setModelDefinition(CompletableFuture.completedFuture(definition), false);
            config = new ModConfigFile(file);
            keys = java.util.Set.copyOf(config.keySet());
            player.getModelDefinition();
            CpmIntegration.accessories(player, definition);
            check(java.util.Set.copyOf(config.keySet()).equals(keys),
                    "render accessory gate must not mutate upstream configuration maps");
            definition.cleanup();
        } finally {
            config = previous;
            CapeProjection.retireSkin(id);
        }
    }

    private static void pausedPreviewAnimation(File fixtures) throws Exception {
        UUID id = new UUID(0, 192);
        var source = CapeProjection.effectiveSkin(id, WORLD, "fixture:animated-preview", SkinModel.CLASSIC, true);
        FakePlayer player = new FakePlayer(id);
        ((CpmPlayerAccess) (Object) player).nclskins$source(source);
        ModelDefinition definition;
        try (var input = new java.io.FileInputStream(new File(fixtures, "accessory-geometry-only.png"))) {
            definition = loader.loadModel(Image.loadFrom(input), player);
        }
        definition.resolveAll();
        player.setModelDefinition(CompletableFuture.completedFuture(definition), false);
        var registry = definition.getAnimations();
        var body = definition.getModelElementFor(com.tom.cpm.shared.model.PlayerModelParts.BODY).get();
        var animation = new com.tom.cpm.shared.animation.IAnimation() {
            public int getDuration(com.tom.cpm.shared.animation.AnimationEngine.AnimationMode mode) { return 10000; }
            public int getPriority(com.tom.cpm.shared.animation.AnimationEngine.AnimationMode mode) { return 1000; }
            public void animate(AnimationState state, long millis, ModelDefinition model,
                    com.tom.cpm.shared.animation.AnimationEngine.AnimationMode mode) {
                model.getModelElementFor(com.tom.cpm.shared.model.PlayerModelParts.BODY).get().setPosition(false, millis / 100F, 0, 0);
            }
        };
        registry.register(new com.tom.cpm.shared.animation.AnimationTrigger(registry,
                java.util.Set.of(com.tom.cpm.shared.animation.VanillaPose.STANDING), null, List.of(animation), true, false));
        var engine = new com.tom.cpm.shared.animation.AnimationEngine();
        var state = new AnimationState(com.tom.cpm.shared.animation.AnimationEngine.AnimationMode.PLAYER);
        player.persistentState.apply(state);
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureHandle("fixture:animated-preview", 64, 64),
                SkinModel.CLASSIC, Optional.empty(), PreviewRenderer.CapeMode.OFF, true);
        try {
            float first;
            try (var frame = CpmFrame.open(appearance)) {
                engine.prepareAnimations(player.persistentState, state, definition);
                engine.handleAnimation(state, player, definition);
                first = body.pos.x;
            }
            Thread.sleep(100);
            try (var frame = CpmFrame.open(appearance)) {
                engine.prepareAnimations(player.persistentState, state, definition);
                engine.handleAnimation(state, player, definition);
                check(body.pos.x > first, "paused in-world preview must advance actual CPM BODY animation");
            }
            check(engine.getTime() == 0 && engine.getTicks() == 0,
                    "preview animation must not tick the world animation engine");
            check(player.persistentState != world.persistentState, "preview animation state remains isolated");
        } finally {
            definition.cleanup();
            CapeProjection.retireSkin(id);
            drain();
        }
    }

    private static void environmentSettings() {
        AtomicInteger changes = new AtomicInteger();
        com.naocraftlab.skins.client.SkinCatalogSource resources = new com.naocraftlab.skins.client.SkinCatalogSource() {
            public byte[] load(String collection, String id, SkinModel model) throws IOException { throw new IOException("not a fixture input"); }
            public long generation() { return 1; }
            public Optional<byte[]> findResource(String id) { return Optional.empty(); }
        };
        var support = new com.naocraftlab.skins.compat.environment.SkinExtensionEnvironmentSupport(resources,
                com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.State.INACTIVE,
                com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.State.INACTIVE,
                com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.State.INACTIVE, CpmIntegration::modelLoadingState);
        var frozen = support.snapshot();
        try (var subscription = CpmIntegration.subscribe(changes::incrementAndGet)) {
            Player.setEnableRendering(false);
            var disabled = support.snapshot();
            check(changes.get() == 1 && disabled.generation() != frozen.generation(), "settings event changes snapshot generation");
            check(frozen.consumers().get(com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.Consumer.CUSTOMIZABLE_PLAYER_MODELS)
                    == com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.State.ACTIVE, "previous snapshot remains frozen");
            check(disabled.consumers().get(com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.Consumer.CUSTOMIZABLE_PLAYER_MODELS)
                    == com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.State.INACTIVE, "renderer setting reflected without resource reload");
        }
        Player.setEnableRendering(true);
        check(changes.get() == 1, "closed runtime listener cannot receive later events");
        System.out.println("Environment settings: event-driven change, frozen snapshots, close removes listener");
    }

    private static void typedTexturesAndPriority() throws Exception {
        publish(WORLD, WORLD, "nclskins:fixture-a", 7, false);
        var source = CapeProjection.skinSource(WORLD);
        var bridge = new CpmTextureLoader(source, auxiliary());
        check(bridge.load() == bridge.load(), "load must be idempotent");
        Image first = bridge.getTexture(TextureType.SKIN).get(5, TimeUnit.SECONDS);
        Image second = bridge.getTexture(TextureType.SKIN).get(5, TimeUnit.SECONDS);
        check(first != second && first.getRGB(0, 0) == second.getRGB(0, 0), "private copies share one generation");
        first.setRGB(0, 0, 0);
        check(second.getRGB(0, 0) != 0, "decode cannot mutate stitching source");
        check(AUXILIARY.get() == 0 && RESOURCE.get() == 0, "skin path must not start official or resource fetch");
        bridge.getTexture(TextureType.CAPE).get(5, TimeUnit.SECONDS);
        check(AUXILIARY.get() == 1, "auxiliary lookup must remain lazy");
        Player<?> a = loader.loadPlayer(WORLD, ModelDefinitionLoader.PLAYER_UNIQUE);
        ModelDefinition model = a.getDefinitionFuture().get(5, TimeUnit.SECONDS);
        check(model instanceof OwnedCpmDefinition, "factory must tag before the first texture load");
        byte[] explicit = payload(42);
        loader.setModel(WORLD, explicit, false);
        publish(WORLD, WORLD, "nclskins:fixture-b", 8, false);
        drain();
        check(java.util.Arrays.equals(explicit, loader.getModel(WORLD)), "refresh must preserve server assignment");
        check(!source.active() && bridge.getTexture(TextureType.SKIN).get() == null, "retired input cannot be republished");
        loader.setModel(WORLD, null, false);
        drain();
        System.out.println("Texture bridge: idempotent, private copies, no eager skin fetch; server priority survives refresh");
    }

    private static void previewPolicyAndPackets() throws Exception {
        publish(PREVIEW, WORLD, "nclskins:draft", 9, true);
        int worldRequestsBeforePreview = NET.world.get();
        FakePlayer preview = (FakePlayer) loader.loadPlayer(PREVIEW, ModelDefinitionLoader.PLAYER_UNIQUE);
        ModelDefinition definition = preview.getDefinitionFuture().get(5, TimeUnit.SECONDS);
        check(definition instanceof OwnedCpmDefinition, "preview definition is owned");
        check(preview.getUUID().equals(WORLD) && !preview.isClientPlayer(), "subject must not acquire local privilege");
        check(NET.preview.get() == 0 && NET.world.get() == worldRequestsBeforePreview, "preview must not request player state under either UUID");
        previewResources(preview);
        int before = NET.world.get();
        loader.loadModel(payload(1), world);
        check(NET.world.get() == before + 1, "real player with identical policy UUID must keep its request");
        config.getEntry(ConfigKeys.PLAYER_SETTINGS).getEntry(WORLD.toString()).setBoolean("models", false);
        ModelDefinition blocked = loader.loadModel(payload(1), preview);
        check(blocked.getResolveState() == ModelDefinition.ModelLoadingState.SAFETY_BLOCKED, "per-player policy uses real subject");
        config.getEntry(ConfigKeys.PLAYER_SETTINGS).getEntry(WORLD.toString()).clearValue("models");
        SocialConfig.blockPlayer(WORLD.toString(), "Fixture");
        blocked = loader.loadModel(payload(1), preview);
        check(blocked.getResolveState() == ModelDefinition.ModelLoadingState.SAFETY_BLOCKED, "blocked subject cannot bypass through cache UUID");
        SocialConfig.removeBlock(WORLD.toString());
        SocialConfig.addFriend(WORLD.toString(), "Fixture");
        config.getEntry(ConfigKeys.FRIEND_SETTINGS).setBoolean("models", false);
        blocked = loader.loadModel(payload(1), preview);
        check(blocked.getResolveState() == ModelDefinition.ModelLoadingState.SAFETY_BLOCKED, "friend policy uses real subject");
        config.getEntry(ConfigKeys.FRIEND_SETTINGS).clearValue("models");
        SocialConfig.removeFriend(WORLD.toString());
        config.getEntry(ConfigKeys.SERVER_SETTINGS).getEntry("fixture.invalid").setBoolean("models", false);
        blocked = loader.loadModel(payload(1), preview);
        check(blocked.getResolveState() == ModelDefinition.ModelLoadingState.SAFETY_BLOCKED, "server policy preserved");
        config.getEntry(ConfigKeys.SERVER_SETTINGS).getEntry("fixture.invalid").clearValue("models");
        new ModelPartUUIDLockout(WORLD).apply(definition);
        boolean rejected = false;
        try { new ModelPartUUIDLockout(PREVIEW).apply(definition); }
        catch (SafetyException expected) { rejected = true; }
        check(rejected, "synthetic identity must not unlock a model");
        preview.sendEventSubs();
        check(NET.events.get() == 0, "preview cannot publish subscriptions");
        publish(WORLD, WORLD, "nclskins:world-replacement", 15, false);
        drain();
        check(loader.loadPlayer(PREVIEW, ModelDefinitionLoader.PLAYER_UNIQUE) == preview
                && definition.getResolveState() != ModelDefinition.ModelLoadingState.CLEANED_UP,
                "world refresh cannot invalidate a preview with the same policy subject");
        System.out.println("Preview policy: blocked/player/friend/server/UUID-lock checked; zero preview requests and subscriptions; world request preserved");
    }

    static void writeResourceModel(java.nio.file.Path path, SkinType type) throws Exception {
        java.nio.file.Files.createDirectories(path.getParent());
        Class<?> writerType = Class.forName("com.tom.cpm.shared.editor.Exporter$ModelWriter");
        var constructor = writerType.getDeclaredConstructor(com.tom.cpl.gui.UI.class, File.class, boolean.class);
        constructor.setAccessible(true);
        Object writer = constructor.newInstance(null, path.toFile(), false);
        var description = writerType.getDeclaredMethod("setDesc", String.class, String.class, Image.class);
        description.setAccessible(true);
        description.invoke(writer, "Synthetic preview resource", "", null);
        var output = writerType.getDeclaredMethod("getOut");
        output.setAccessible(true);
        try (var stream = (java.io.OutputStream) output.invoke(writer)) { stream.write(resourcePayload(type)); }
        var finish = writerType.getDeclaredMethod("finish");
        finish.setAccessible(true);
        check(Boolean.TRUE.equals(finish.invoke(writer)), "native model writer produces preview resource fixture");
    }

    static void inspectDispatchedAppearance(File configuration, byte[] bytes, PreviewRenderer.PreviewAppearance appearance) throws Exception {
        var previousClient = MinecraftClientAccess.get();
        var previousCommon = MinecraftCommonAccess.get();
        initializeHost(configuration);
        loader = new ModelDefinitionLoader<>(FakePlayer::new, id -> id, id -> "Fixture") {
            @Override public java.io.InputStream load(com.tom.cpm.shared.definition.Link link,
                    com.tom.cpm.shared.config.ResourceLoader.ResourceEncoding encoding, ModelDefinition definition) {
                throw new AssertionError("Dispatched embedded preview must not load linked resources");
            }
            @Override public void clearCache() { throw new AssertionError("Preview must not reset the global CPM cache"); }
            @Override public void setModel(UUID profile, byte[] bytes, boolean forced) {
                throw new AssertionError("Preview must not assign a player model");
            }
        };
        int packets = NET.world.get() + NET.preview.get() + NET.events.get();
        var assignment = world.getDefinitionFuture();
        try (var registration = CapeProjection.installEvents(events())) {
            UUID cache = new UUID(0, 904);
            Image image = Image.loadFrom(new java.io.ByteArrayInputStream(bytes));
            Object texture = new Object();
            CapeProjection.skinTextureReady(appearance.skin().location(), image.getData(), texture);
            var source = CapeProjection.effectiveSkin(cache, WORLD, appearance.skin().location(), appearance.model(), true);
            Player<?> player = loader.loadPlayer(cache, ModelDefinitionLoader.PLAYER_UNIQUE);
            ModelDefinition definition = player.getDefinitionFuture().get(5, TimeUnit.SECONDS);
            check(definition instanceof OwnedCpmDefinition, "compiled loaded dispatch creates an owned native definition");
            if (definition.getError() != null) throw new AssertionError("compiled loaded dispatch decodes without native failure", definition.getError());
            check(((OwnedCpmDefinition) definition).selfResources(),
                    "compiled loaded dispatch reaches scoped native self resource policy");
            check(java.util.Arrays.equals(source.pixels(), image.getData())
                    && java.util.Arrays.equals(player.getTextures().getTexture(TextureType.SKIN).get(5, TimeUnit.SECONDS).getData(), image.getData()),
                    "actual loaded source and CPM texture preserve dispatched C pixels");
            definition.startResolve();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (definition.getResolveState() == ModelDefinition.ModelLoadingState.RESOLVING && System.nanoTime() < deadline) Thread.sleep(1);
            check(definition.doRender(), "compiled loaded dispatch resolves its actual native definition");
            Object body = new Object();
            var manager = new com.tom.cpm.shared.model.render.ModelRenderManager<Object, Object, Object, Object>() {{
                holders.put(body, new RedirectHolder<>(this, body) {
                    protected void swapIn0() {}
                    protected void swapOut0() {}
                });
            }};
            try (var frame = CpmFrame.open(appearance)) {
                check(CpmFrame.accepts((OwnedCpmDefinition) definition), "dispatched appearance frame accepts exact source C");
                manager.bindModel(body, null, null, definition, player, new AnimationState());
                check(manager.isBound(body), "dispatched native definition reaches actual transformed binding");
            }
            check(!manager.isBound(body) && !player.isClientPlayer(), "frame releases binding without granting global self identity");
            player.sendEventSubs();
            check(world.getDefinitionFuture() == assignment && packets == NET.world.get() + NET.preview.get() + NET.events.get(),
                    "compiled native preview preserves real assignment and emits no state/event packets");
            CapeProjection.retireSkin(cache);
            check(!((OwnedCpmDefinition) definition).selfResources(), "dispatched close retires resource permission");
            drain();
        } finally {
            MinecraftObjectHolder.setClientObject(previousClient);
            MinecraftObjectHolder.setCommonObject(previousCommon);
        }
    }

    private static void previewResources(Player<?> preview) throws Exception {
        var global = config.getEntry(ConfigKeys.GLOBAL_SETTINGS);
        String priorProfile = global.getString(ConfigKeys.SAFETY_PROFILE, "medium");
        global.setString(ConfigKeys.SAFETY_PROFILE, "custom:preview-resource-probe");
        var settings = config.getEntry(ConfigKeys.SAFETY_PROFILES).getEntry("preview-resource-probe");
        ConfigKeys.MAX_TEX_SHEET_SIZE.setValue(settings, 256);
        ConfigKeys.ENABLE_ANIMATED_TEXTURES.setValue(settings, false);
        ConfigKeys.MAX_LINK_SIZE.setValue(settings, 16);
        try {
            for (SkinType type : new SkinType[]{SkinType.DEFAULT, SkinType.SLIM}) {
                byte[] bytes = resourcePayload(type);
                ModelDefinition self = loader.loadModel(bytes, world);
                check(self.getError() == null, "native self admits a 512px texture under the remote 256px profile");
                self.resolveAll();
                check(self.doRender(), "native self resolves the resource control");
                Player<?> remote = fixturePlayer(UUID.fromString("00000000-0000-0000-0000-000000000009"));
                ModelDefinition denied = loader.loadModel(bytes, remote);
                check(denied.getResolveState() == ModelDefinition.ModelLoadingState.SAFETY_BLOCKED,
                        "native remote retains its texture ceiling");
                int packets = NET.world.get() + NET.preview.get();
                ModelDefinition owned = loader.loadModel(bytes, preview);
                check(owned instanceof OwnedCpmDefinition && owned.getError() == null,
                        "own draft must admit the same native resource as self, not the remote profile");
                owned.resolveAll();
                check(owned.doRender() && owned.getTexture(com.tom.cpm.shared.model.TextureSheetType.SKIN, true).getImage().getWidth() == 512,
                        "owned resource reaches native resolve intact");
                var animated = com.tom.cpm.shared.animation.AnimationRegistry.class.getDeclaredField("animatedTextures");
                animated.setAccessible(true);
                check(((List<?>) animated.get(self.getAnimations())).size() == 1, "native self animates despite remote profile");
                check(((List<?>) animated.get(owned.getAnimations())).size() == 1, "own draft preserves native self animated textures");
                check(!preview.isClientPlayer() && NET.world.get() + NET.preview.get() == packets,
                        "resource parity cannot grant player-state packets or global self identity");
                resourceScopeControls((OwnedCpmDefinition) owned, preview, bytes);
                linkedResources(self, false);
                linkedResources(owned, false);
                linkedResources(loader.loadModel(payload(1), remote), true);
                owned.cleanup();
                check(!((OwnedCpmDefinition) owned).selfResources(), "closed definition loses resource permission");
                self.cleanup();
                stitchResources(world, false);
                stitchResources(preview, false);
                stitchResources(remote, true);
            }
        } finally {
            settings.clearValue(ConfigKeys.MAX_TEX_SHEET_SIZE.getName());
            settings.clearValue(ConfigKeys.ENABLE_ANIMATED_TEXTURES.getName());
            settings.clearValue(ConfigKeys.MAX_LINK_SIZE.getName());
            global.setString(ConfigKeys.SAFETY_PROFILE, priorProfile);
        }
        System.out.println("Own-draft resources: native self positive, remote negative, both channels resolve without packet privilege");
    }

    private static void linkedResources(ModelDefinition definition, boolean blocked) throws Exception {
        byte[] payload = new byte[32 * 1024];
        java.util.Arrays.fill(payload, (byte) 73);
        AtomicInteger opened = new AtomicInteger(), disconnected = new AtomicInteger();
        var resource = new com.tom.cpm.shared.loaders.HttpResourceLoader() {
            protected java.net.URL createURL(String path) throws IOException {
                return new java.net.URL(null, "https://fixture.invalid/resource", new java.net.URLStreamHandler() {
                    protected java.net.URLConnection openConnection(java.net.URL url) {
                        opened.incrementAndGet();
                        return new java.net.HttpURLConnection(url) {
                            public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(payload); }
                            public void connect() {}
                            public void disconnect() { disconnected.incrementAndGet(); }
                            public boolean usingProxy() { return false; }
                        };
                    }
                    protected java.net.URLConnection openConnection(java.net.URL url, java.net.Proxy proxy) { return openConnection(url); }
                });
            }
        };
        boolean rejected = false;
        try {
            byte[] result = resource.loadResource("synthetic", com.tom.cpm.shared.config.ResourceLoader.ResourceEncoding.NO_ENCODING, definition);
            check(java.util.Arrays.equals(payload, result), "actual CPM resource consumer preserves terminal bytes");
        } catch (SafetyException expected) { rejected = true; }
        check(rejected == blocked && opened.get() == 1 && disconnected.get() == 1,
                "native resource limit has self/own parity and remote refusal with terminal-only transport and cleanup");
    }

    private static void resourceScopeControls(OwnedCpmDefinition owned, Player<?> preview, byte[] bytes) throws Exception {
        check(owned.selfResources(), "active exact own-draft source has self resource policy");
        owned.check(ConfigKeys.MAX_CUBE_COUNT, Integer.MAX_VALUE, SafetyException.BlockReason.TOO_MANY_CUBES);
        owned.check(ConfigKeys.MAX_LINK_SIZE, Integer.MAX_VALUE, SafetyException.BlockReason.LINK_OVERFLOW);
        owned.check(ConfigKeys.MAX_TEX_SHEET_SIZE, (java.util.function.Predicate<Integer>) value -> false,
                SafetyException.BlockReason.TEXTURE_OVERFLOW);
        boolean rejected = false;
        try { owned.check(ConfigKeys.ENABLE_MODEL_LOADING, (java.util.function.Predicate<Boolean>) value -> false,
                SafetyException.BlockReason.CONFIG_DISABLED); }
        catch (SafetyException expected) { rejected = true; }
        check(rejected, "resource exception does not bypass model-loading policy keys");
        var settings = config.getEntry(ConfigKeys.SAFETY_PROFILES).getEntry("preview-resource-probe");
        ConfigKeys.ENABLE_MODEL_LOADING.setValue(settings, false);
        try {
            check(loader.loadModel(bytes, preview).getResolveState() == ModelDefinition.ModelLoadingState.SAFETY_BLOCKED,
                    "global model loading disabled remains authoritative");
        } finally { settings.clearValue(ConfigKeys.ENABLE_MODEL_LOADING.getName()); }
        FakePlayer previous = world;
        world = new FakePlayer(new UUID(0, 901));
        try {
            check(!owned.selfResources(), "account identity change revokes old own-draft resource policy without a tick");
            check(loader.loadModel(bytes, preview).getResolveState() == ModelDefinition.ModelLoadingState.SAFETY_BLOCKED,
                    "old-account preview reverts to native limits");
        } finally { world = previous; }
        var access = (CpmPlayerAccess) preview;
        var source = access.nclskins$source();
        access.nclskins$source(null);
        try { check(!owned.selfResources(), "untagged/foreign definition cannot borrow a live source by UUID"); }
        finally { access.nclskins$source(source); }
        UUID foreignId = new UUID(0, 902);
        var foreignSource = CapeProjection.effectiveSkin(foreignId, new UUID(0, 903), "nclskins:foreign", SkinModel.CLASSIC, true);
        Player<?> foreign = fixturePlayer(foreignId);
        ((CpmPlayerAccess) foreign).nclskins$source(foreignSource);
        try {
            check(loader.loadModel(bytes, foreign).getResolveState() == ModelDefinition.ModelLoadingState.SAFETY_BLOCKED,
                    "preview flag alone does not grant self resource policy to another subject");
        } finally { CapeProjection.retireSkin(foreignId); }
    }

    private static void stitchResources(Player<?> player, boolean blocked) throws Exception {
        ModelDefinition definition = loader.loadModel(payload(1), player);
        definition.setParts(List.of(new IModelPart() {
            public IResolvedModelPart resolve() {
                return new IResolvedModelPart() {
                    public void stitch(com.tom.cpm.shared.util.TextureStitcher stitcher) {
                        for (int i = 0; i < 32; i++) stitcher.stitchImage(new Image(64, 64), null);
                    }
                };
            }
            public void write(IOHelper output) {}
            public ModelPartType getType() { return ModelPartType.END; }
        }));
        boolean rejected = false;
        try { definition.resolveAll(); }
        catch (SafetyException expected) { rejected = true; }
        check(rejected == blocked, "native stitcher must retain self capacity for own draft and remote limit otherwise");
        if (!blocked) check(definition.doRender(), "self-capacity stitch reaches renderable definition");
        definition.cleanup();
    }

    private static byte[] resourcePayload(SkinType type) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        bytes.write(83);
        try (var checksum = new com.tom.cpm.shared.io.ChecksumOutputStream(bytes)) {
            var io = new IOHelper(checksum);
            io.writeObjectBlock(new com.tom.cpm.shared.parts.ModelPartSkinType(type));
            io.writeEnum(ModelPartType.TEXTURE);
            try (var block = io.writeNextBlock()) {
                block.writeEnum(com.tom.cpm.shared.model.TextureSheetType.SKIN);
                Image image = new Image(512, 512);
                image.fill(0xff123456);
                new com.tom.cpm.shared.skin.TextureProvider(image, new com.tom.cpl.math.Vec2i(64, 64)).write(block);
            }
            var animation = new com.tom.cpm.shared.editor.anim.AnimatedTex(null, com.tom.cpm.shared.model.TextureSheetType.SKIN);
            animation.uvSize = new com.tom.cpl.math.Vec2i(1, 1);
            animation.frameCount = 2;
            animation.frameTime = 1;
            io.writeObjectBlock(new com.tom.cpm.shared.parts.ModelPartAnimatedTexture(com.tom.cpm.shared.model.TextureSheetType.SKIN, animation));
            io.writeEnum(ModelPartType.END);
            try (var end = io.writeNextBlock()) {}
        }
        return bytes.toByteArray();
    }

    private static void generationScopedRefresh() throws Exception {
        publish(WORLD, WORLD, "nclskins:cache-a", 13, false);
        drain();
        Player<?> old = loader.loadPlayer(WORLD, ModelDefinitionLoader.PLAYER_UNIQUE);
        old.getDefinitionFuture().get(5, TimeUnit.SECONDS);
        publish(WORLD, WORLD, "nclskins:cache-b", 14, false);
        String key = "model:" + java.util.Base64.getEncoder().encodeToString(payload(42));
        Player<?> fresh = loader.loadPlayer(WORLD, key);
        ModelDefinition freshModel = fresh.getDefinitionFuture().get(5, TimeUnit.SECONDS);
        drain();
        check(loader.getLoadedPlayer(WORLD) == null, "refresh must remove only old generation");
        check(loader.loadPlayer(WORLD, key) == fresh, "refresh must retain already-loaded current generation and explicit key");
        check(freshModel.getResolveState() != ModelDefinition.ModelLoadingState.CLEANED_UP,
                "queued refresh cannot clean current generation resources");
        check(((CpmPlayerAccess) (Object) old).nclskins$source().active() == false, "old source remains retired");
        Player<?> next = loader.loadPlayer(WORLD, ModelDefinitionLoader.PLAYER_UNIQUE);
        check(next != old && next.getDefinitionFuture().get(5, TimeUnit.SECONDS) instanceof OwnedCpmDefinition,
                "normal key reloads from current source");
        System.out.println("Generation refresh: obsolete cache entry removed; fresh entry and explicit key preserved in same connection");
    }

    private static void frameRetirement() throws Exception {
        drain();
        var source = CapeProjection.skinSource(PREVIEW);
        var definition = new OwnedCpmDefinition(loader, world, source);
        definition.setParts(List.of());
        definition.resolveAll();
        AtomicInteger freed = new AtomicInteger();
        var texture = trackedTexture(freed);
        definition.setTexture(com.tom.cpm.shared.model.TextureSheetType.CAPE, texture);
        definition.decoded();
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureHandle(source.location(), 64, 64), SkinModel.CLASSIC,
                Optional.empty(), PreviewRenderer.CapeMode.OFF, true);
        AtomicInteger unbound = new AtomicInteger();
        try (CpmFrame frame = CpmFrame.open(appearance)) {
            check(CpmFrame.accepts(definition), "frame accepts only its draft");
            Object renderModel = new Object();
            var manager = new com.tom.cpm.shared.model.render.ModelRenderManager<Object, Object, Object, Object>() {
                {
                    holders.put(renderModel, new RedirectHolder<>(this, renderModel) {
                        protected void swapIn0() {}
                        protected void swapOut0() { unbound.incrementAndGet(); }
                    });
                }
            };
            manager.bindModel(renderModel, null, null, definition, world, new AnimationState());
            check(manager.isBound(renderModel), "transformed manager must bind the preview");
            CpmFrame.bound(manager, renderModel, null, definition, world,
                    () -> { throw new IllegalStateException("injected unbind failure"); });
            CapeProjection.retireSkin(PREVIEW);
            drain();
            check(definition.acquire() == null && freed.get() == 0
                    && definition.getTexture(com.tom.cpm.shared.model.TextureSheetType.CAPE, false) == texture,
                    "retirement rejects binds but retains frame resources through host invalidation");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().equals("injected unbind failure"), "unexpected frame failure");
        }
        check(unbound.get() == 1 && !GAME.isEmpty(), "all frame bindings close even when one throws");
        drain();
        check(freed.get() == 1, "retired frame texture must free exactly once");
        try (CpmFrame frame = CpmFrame.open(appearance)) {
            check(!CpmFrame.accepts(definition), "next frame cannot inherit retired geometry");
        }
        System.out.println("Frame leases: resources retained through retirement; every finally cleanup runs after injected exception");
        publish(PREVIEW, WORLD, "nclskins:error", 9, true);
        var failed = new OwnedCpmDefinition(loader, world, CapeProjection.skinSource(PREVIEW));
        failed.setParts(List.of());
        failed.resolveAll();
        AtomicInteger errorFreed = new AtomicInteger();
        failed.setTexture(com.tom.cpm.shared.model.TextureSheetType.CAPE, trackedTexture(errorFreed));
        failed.decoded();
        var errorLease = failed.acquire();
        failed.setError(new IOException("injected resolution failure"));
        drain();
        check(errorFreed.get() == 0 && failed.getResolveState() == ModelDefinition.ModelLoadingState.ERRORRED,
                "error finalizer cannot clear live bound resources");
        errorLease.close();
        drain();
        check(errorFreed.get() == 1, "error finalizer frees resources after final lease");
    }

    private static void resolveRetirement() throws Exception {
        publish(PREVIEW, WORLD, "nclskins:resolve", 10, true);
        Player<?> preview = fixturePlayer(PREVIEW);
        ((CpmPlayerAccess) preview).nclskins$source(CapeProjection.skinSource(PREVIEW));
        var definition = new OwnedCpmDefinition(loader, preview, CapeProjection.skinSource(PREVIEW));
        check(definition.source().active(), "resolve fixture source must be active");
        check(definition.source().preview(), "resolve fixture source must be a preview");
        check(definition.source().subject().equals(MinecraftClientAccess.get().getCurrentClientPlayer().getUUID()),
                "resolve fixture subject must match current player");
        check(CpmIntegration.source(preview) == definition.source(), "resolve fixture player must retain exact source");
        check(definition.selfResources(), "resolving exact own draft starts with self resource policy");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger freed = new AtomicInteger();
        definition.setParts(List.of(new IModelPart() {
            public IResolvedModelPart resolve() throws IOException {
                entered.countDown();
                await(release);
                return new IResolvedModelPart() {
                    public void preApply(ModelDefinition model) {
                        model.setTexture(com.tom.cpm.shared.model.TextureSheetType.CAPE, trackedTexture(freed));
                        model.setTexture(com.tom.cpm.shared.model.TextureSheetType.SKIN,
                                new com.tom.cpm.shared.skin.TextureProvider(new Image(64, 64), new com.tom.cpl.math.Vec2i(64, 64)));
                    }
                };
            }
            public void write(IOHelper out) {}
            public ModelPartType getType() { return ModelPartType.SKIN_TYPE; }
        }));
        definition.decoded();
        drain();
        definition.startResolve();
        check(entered.await(5, TimeUnit.SECONDS), "resolve worker must enter");
        CapeProjection.retireSkin(PREVIEW);
        drain();
        check(freed.get() == 0, "must not free while resolve uses resources");
        check(!definition.selfResources(), "retirement revokes self resources while the worker is still held");
        release.countDown();
        waitForCleanup();
        check(definition.getResolveState() == ModelDefinition.ModelLoadingState.CLEANED_UP && !definition.doRender(), "late LOADED cannot resurrect publication");
        drain();
        check(freed.get() == 1, "late resolve resources must free exactly once on host thread");
        System.out.println("Resolve lease: late worker completion remains retired and cleans on host thread");
    }

    private static void lateDecodeCannotSignalNewConnection() throws Exception {
        var old = CapeProjection.installEvents(events());
        publish(WORLD, WORLD, "nclskins:old", 11, false);
        FakePlayer player = new FakePlayer(WORLD);
        ((CpmPlayerAccess) (Object) player).nclskins$source(CapeProjection.skinSource(WORLD));
        Image image = carrier(11);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Image delayed = new Image(image) {
            private boolean first = true;
            @Override public int getRGB(int x, int y) {
                if (first) { first = false; entered.countDown(); try { await(release); } catch (IOException failure) { throw new IllegalStateException(failure); } }
                return super.getRGB(x, y);
            }
        };
        var future = CompletableFuture.supplyAsync(() -> loader.loadModel(delayed, player));
        check(entered.await(5, TimeUnit.SECONDS), "decode must enter");
        old.close();
        drain();
        try (var next = CapeProjection.installEvents(events())) {
            publish(WORLD, WORLD, "nclskins:new", 12, false);
            drain();
            int before = NET.world.get();
            release.countDown();
            ModelDefinition retired = future.get(5, TimeUnit.SECONDS);
            check(retired instanceof OwnedCpmDefinition && retired.getResolveState() == ModelDefinition.ModelLoadingState.CLEANED_UP,
                    "late decode remains retired");
            check(NET.world.get() == before, "old decode cannot request state on new connection");
            check(CapeProjection.skinSource(WORLD).active(), "old cleanup cannot retire new generation");
            drain();
        }
        System.out.println("Decode lease: connection change suppresses late state request and preserves new generation");
    }

    private static void publish(UUID cache, UUID subject, String location, int marker, boolean preview) throws IOException {
        Image image = carrier(marker);
        CapeProjection.skinTextureReady(location, image.getData(), new Object());
        CapeProjection.effectiveSkin(cache, subject, location, SkinModel.CLASSIC, preview);
    }

    private static void carrierSemantics(File fixtures) throws Exception {
        for (String name : new String[] {"classic-a", "slim-a", "linked", "unknown-type", "transition"}) {
            var raster = javax.imageio.ImageIO.read(new File(fixtures, name + ".png"));
            int[] reads = new int[4096];
            Image image = new Image(raster.getRGB(0, 0, 64, 64, null, 0, 64), 64) {
                @Override public int getRGB(int x, int y) { reads[y * 64 + x]++; return super.getRGB(x, y); }
            };
            int channel = name.equals("slim-a") ? 0 : 1;
            var stream = new com.tom.cpm.shared.io.SkinDataInputStream(image, loader.getTemplate(), channel);
            check(stream.read() == 83, "upstream header: " + name);
            var checksum = new com.tom.cpm.shared.io.ChecksumInputStream(stream);
            var input = new IOHelper(checksum);
            for (int blocks = 0; ; blocks++) {
                check(blocks < 3360, "bounded fixture framing");
                int type = checksum.read();
                int length = input.readVarInt();
                check(length >= 0 && length <= 3360, "bounded fixture length");
                int skinType = -1;
                for (int i = 0; i < length; i++) {
                    int value = checksum.read();
                    check(value >= 0, "upstream fixture must not truncate");
                    if (i == 0) skinType = value;
                }
                if (type == 11 && skinType <= 1 && skinType >= 0) stream.setChannel(skinType);
                if (type == 0) { checksum.checkSum(); break; }
            }
            var report = new com.naocraftlab.skins.core.png.CpmCarrierReader().read(raster,
                    channel == 0 ? com.naocraftlab.skins.core.model.SkinVariant.SLIM : com.naocraftlab.skins.core.model.SkinVariant.CLASSIC);
            check(report.status() == com.naocraftlab.skins.core.png.CpmCarrierReader.Status.VALID, "core must accept upstream fixture " + name);
            for (int i = 0; i < reads.length; i++) {
                int channels = reads[i] == 4 ? -1 : (1 << reads[i] * 8) - 1;
                check(report.channelMask(i % 64, i / 64) == channels, "consumed byte-channel footprint: " + name + " pixel " + i);
            }
        }
        for (int channel = 0; channel <= 1; channel++) {
            var stream = new com.tom.cpm.shared.io.SkinDataInputStream(new Image(64, 64), loader.getTemplate(), channel);
            int count = 0;
            while (stream.read() >= 0) count++;
            check(count == (channel == 0 ? 3360 : 2912), "upstream template capacity");
        }
        System.out.println("Carrier fixtures match pinned upstream framing/checksum/channel switch, consumed footprint and exact capacities");
    }

    private static Image carrier(int marker) throws IOException {
        Image image = new Image(64, 64);
        try (SkinDataOutputStream out = new SkinDataOutputStream(image, loader.getTemplate(), 1)) { out.write(payload(marker)); }
        return image;
    }

    private static byte[] payload(int marker) {
        int sum = 11 + 1 + 1 + 254 + 1 + marker;
        return new byte[] {83, 11, 1, 1, (byte) 254, 1, (byte) marker, 0, 0, (byte) (sum >>> 8), (byte) sum};
    }

    private static PlayerTextureLoader auxiliary() {
        return new PlayerTextureLoader() {
            protected CompletableFuture<Void> load0() { AUXILIARY.incrementAndGet(); return CompletableFuture.completedFuture(null); }
            public CompletableFuture<Image> getTexture(TextureType type) { return CompletableFuture.completedFuture(new Image(64, 64)); }
            public boolean hasTexture(TextureType type) { return true; }
        };
    }

    static Player<?> fixturePlayer(UUID id) { return new FakePlayer(id); }

    private static final class FakePlayer extends Player<Object> {
        private final UUID id;
        FakePlayer(UUID id) { this.id = id; }
        public UUID getUUID() { return CpmIntegration.subject(this, id); }
        public String getName() { return "Fixture"; }
        public Object getGameProfile() { return id; }
        public SkinType getSkinType() {
            var source = CpmIntegration.source(this);
            return source != null && source.model() == SkinModel.SLIM ? SkinType.SLIM : SkinType.DEFAULT;
        }
        protected PlayerTextureLoader initTextures() {
            var source = ((CpmPlayerAccess) (Object) this).nclskins$source();
            return source == null ? auxiliary() : new CpmTextureLoader(source, auxiliary());
        }
        public void updateFromPlayer(AnimationState state, Object player) {}
        public void updateFromModel(AnimationState state, Object model) {}
    }

    private static final class Spy extends NetHandler<String, Object, Object> {
        final AtomicInteger world = new AtomicInteger(), preview = new AtomicInteger(), events = new AtomicInteger();
        Spy() { super((namespace, path) -> namespace + ':' + path); }
        @Override public void requestPlayerState(UUID id) { if (id.equals(WORLD)) world.incrementAndGet(); else preview.incrementAndGet(); }
        @Override public boolean hasServerCap(com.tom.cpm.shared.network.ServerCaps cap) { return false; }
        @Override public void sendEventSubs(ModelDefinition definition) { events.incrementAndGet(); }
    }

    private static CapeProjection.Events events() {
        return new CapeProjection.Events() {
            public void trackedPlayer(UUID id, String name) {}
            public void playerInfoUpdated(UUID id, String name) {}
            public void untrackedPlayer(UUID id) {}
            public void worldChanged() {}
            public void worldEntered() {}
        };
    }

    private static void drain() { Runnable task; while ((task = GAME.poll()) != null) task.run(); }
    private static com.tom.cpm.shared.skin.TextureProvider trackedTexture(AtomicInteger freed) {
        return new com.tom.cpm.shared.skin.TextureProvider() {
            @Override public void free() { freed.incrementAndGet(); }
        };
    }
    private static void waitForCleanup() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (GAME.isEmpty() && System.nanoTime() < deadline) Thread.sleep(1);
        check(!GAME.isEmpty(), "worker must schedule terminal cleanup");
    }
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("fixture timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Dispatch { Object call(String name, Object[] values) throws Exception; }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Dispatch dispatch) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (instance, method, args) -> dispatch.call(method.getName(), args));
    }
}
