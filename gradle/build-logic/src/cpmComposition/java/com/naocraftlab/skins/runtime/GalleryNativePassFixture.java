package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.api.ProfileApi;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import com.naocraftlab.skins.runtime.composition.ClientCompositionRoot;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class GalleryNativePassFixture implements AutoCloseable {
    private static final UUID ACCOUNT = new UUID(0, 801);
    private static final UUID B = new UUID(0, 802);
    private static final UUID C = new UUID(0, 803);
    public final ClientRuntime runtime;
    private final DefaultClientOperations operations;
    private final NclSkinsStorage storage;
    private final Path directory;
    public OuterLayerVisibilityController layerController;
    public UUID sessionAccount = ACCOUNT;
    public Runnable verifyTeardown;
    private final Deque<Runnable> workers = new ArrayDeque<>();
    private final PreviewRenderer.PreviewAppearance fixtureAppearance;
    private final byte[] fixtureSkin;
    private final CapeProviderCoordinator coordinator;

    public GalleryNativePassFixture fresh() throws Exception { return new GalleryNativePassFixture(fixtureAppearance, fixtureSkin, coordinator); }

    public GalleryNativePassFixture(PreviewRenderer.PreviewAppearance appearance, byte[] bundledSkin) throws Exception {
        this(appearance, bundledSkin, null);
    }

    private final Map<UUID, byte[]> ownedSkins = new HashMap<>();
    private LocalCapeReference capabilityCape;

    public GalleryNativePassFixture freshOwned() throws Exception { return new GalleryNativePassFixture(fixtureAppearance, fixtureSkin, coordinator, true); }
    public GalleryNativePassFixture freshOwnedCpm() throws Exception { return new GalleryNativePassFixture(fixtureAppearance, fixtureSkin, coordinator, true, true); }
    public void drainWorkers() { while (!workers.isEmpty()) workers.remove().run(); }
    public boolean runNextWorker() {
        if (workers.isEmpty()) return false;
        workers.remove().run(); return true;
    }
    public byte[] ownedBytes(ViewSpec.Preview preview) { return ownedSkins.get(preview.presetId().orElseThrow()).clone(); }
    public void selectB() { runtime.dispatchWidget("gallery.card." + B); }

    public void enableCpmCape() throws Exception {
        operations.useCpmPresence(() -> true);
        var value = operations.enableProvider(com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE,
                com.naocraftlab.skins.core.provider.BuiltinProvider.CPM);
        var accept = ClientRuntime.class.getDeclaredMethod("acceptProviderChange", ClientOperations.DurableAppearance.class);
        accept.setAccessible(true); accept.invoke(runtime, value);
    }

    private GalleryNativePassFixture(PreviewRenderer.PreviewAppearance appearance, byte[] bundledSkin, CapeProviderCoordinator sharedCoordinator) throws Exception {
        this(appearance, bundledSkin, sharedCoordinator, false);
    }

    private GalleryNativePassFixture(PreviewRenderer.PreviewAppearance appearance, byte[] bundledSkin, CapeProviderCoordinator sharedCoordinator, boolean owned) throws Exception {
        this(appearance, bundledSkin, sharedCoordinator, owned, false);
    }

    private GalleryNativePassFixture(PreviewRenderer.PreviewAppearance appearance, byte[] bundledSkin, CapeProviderCoordinator sharedCoordinator, boolean owned, boolean cpm) throws Exception {
        fixtureAppearance = appearance;
        fixtureSkin = bundledSkin.clone();
        directory = Files.createTempDirectory("gallery-native-pass-");
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);
        storage = new NclSkinsStorage(directory, new PngValidator(), clock);
        storage.initialize();
        var references = new ArrayList<SkinReference>();
        for (int i = 0; i < 2; i++) {
            if (!owned) { references.add(SkinReference.accountDefault()); continue; }
            var image = new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            int[] pixels = new int[4096]; Arrays.fill(pixels, i == 0 ? 0xFF225588 : 0xFF883322);
            image.setRGB(0,0,64,64,pixels,0,64);
            var bytes = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image,"PNG",bytes);
            byte[] png = cpm && i == 0 ? bundledSkin.clone() : bytes.toByteArray();
            var adapter = new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(storage);
            var library = new com.naocraftlab.skins.core.service.LibraryService(adapter,adapter,clock);
            var asset = library.importSkin(ACCOUNT,"Owned " + i, i == 0 ? SkinVariant.CLASSIC : SkinVariant.SLIM, SkinSource.IMPORTED,png).asset();
            references.add(SkinReference.asset(asset.id())); ownedSkins.put(i == 0 ? B : C,png);
        }
        storage.updateAccount(ACCOUNT, old -> new AccountState(old.schemaVersion(), ACCOUNT, old.skinAssets(), List.of(
                new AppearancePreset(B, "B", references.get(0), null, clock.instant(), clock.instant()),
                new AppearancePreset(C, "C", references.get(1), null, OuterLayerVisibility.noneVisible(), clock.instant(), clock.instant())), clock.instant()));
        GameSessionTokenSource tokens = new GameSessionTokenSource() {
            public SessionIdentity currentSession() { return new SessionIdentity(sessionAccount, "Fixture"); }
            public <T,E extends Exception> T withAccessToken(TokenRequest<T,E> request) { throw new IllegalStateException("offline fixture"); }
        };
        ProfileApi api = (ProfileApi) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ProfileApi.class}, (p,m,a) -> {
            if (m.getName().equals("rateLimitRemaining")) return Optional.empty();
            throw new AssertionError("Unexpected HTTP: " + m.getName());
        });
        var graph = ClientCompositionRoot.operations(tokens, api, storage,
                (BundledSkinSource) model -> bundledSkin, clock, null);
        operations = graph.operations();
        if (cpm) {
            operations.useCpmPresence(() -> true);
            operations.enableProvider(com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE,
                    com.naocraftlab.skins.core.provider.BuiltinProvider.CPM);
        }
        operations.usePreset(B);
        ClientExecutor client = new ClientExecutor() {
            public boolean isClientThread() { return true; }
            public void execute(Runnable action) { action.run(); }
        };
        coordinator = sharedCoordinator == null ? new CapeProviderCoordinator(tokens, graph.appearances(), graph.assets(), graph.textures(),
                resolved -> PlayerAppearanceSink.ApplyResult.UPDATED, client, Runnable::run,
                new OptifineCapeReader(directory.resolve("optifine-staging")), new SkinMcCapeReader(directory.resolve("skinmc-staging")),
                operations::verifiedOfficialCapeUri) : sharedCoordinator;
        operations.attachCapeProviders(coordinator, null);
        var runtimeOperations = sharedCoordinator == null ? operations : (ClientOperations) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ClientOperations.class}, (proxy, method, args) -> {
            if (method.getName().equals("close")) return null;
            try { return method.invoke(operations, args); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
        CurrentPlayerAppearanceSource current = () -> new CurrentPlayerAppearanceSource.PlayerAppearance(
                appearance.skin(), appearance.model(), appearance.cape());
        runtime = new ClientRuntime(new DefaultRuntimeServices(), runtimeOperations, client,
                () -> CompletableFuture.completedFuture(Optional.empty()), workers::add, UiMessage::key,
                Optional.of(current), Optional.empty(), Optional.of(new OuterLayerVisibilityController() {
                    public OuterLayerVisibility current() { return layerController == null ? OuterLayerVisibility.allVisible() : layerController.current(); }
                    public void applyDurable(OuterLayerVisibility value) { if (layerController != null) layerController.applyDurable(value); }
                }), Optional.empty(), DiagnosticSinks.discarding());
        if (owned) runtime.useSkinExtensionEnvironmentSource(() -> new SkinExtensionEnvironmentSource.Snapshot(1,
                Map.of(SkinExtensionEnvironmentSource.Consumer.CUSTOMIZABLE_PLAYER_MODELS,SkinExtensionEnvironmentSource.State.ACTIVE),true));
        runtime.initialize();
        while (!workers.isEmpty()) workers.remove().run();
        if (runtime.snapshot().lifecycle() != ClientSnapshot.Lifecycle.READY)
            throw new AssertionError("Gallery fixture initialization: " + runtime.snapshot().lifecycle() + " / " + runtime.snapshot().status().key());
        var first = preview();
        if (first.bodySource().isEmpty()) {
            var identity = ClientRuntime.class.getDeclaredField("importIdentity"); identity.setAccessible(true);
            var open = ClientRuntime.class.getDeclaredMethod("galleryMainOpen"); open.setAccessible(true);
            throw new AssertionError("Gallery source missing: mainOpen=" + open.invoke(runtime) + ", identityPresent=" + (identity.get(runtime) != null)
                    + ", accountMatches=" + runtime.snapshot().account().map(a -> a.accountId().equals(ACCOUNT)).orElse(false)
                    + ", runtime=" + ClientRuntime.class.getProtectionDomain().getCodeSource().getLocation());
        }
    }

    public ViewSpec.Preview preview() {
        return runtime.view(854, 480, 0, 0).previews().stream().filter(p -> p.id().equals("gallery.preview")).findFirst().orElseThrow();
    }

    public PreviewRenderer.PreviewRequest request() {
        return request(runtime.galleryPreviewCandidate().orElseGet(this::preview));
    }

    public PreviewRenderer.PreviewRequest presentedRequest(int width, int height) {
        return request(runtime.view(width,height,0,0).previews().stream()
                .filter(p -> p.id().equals("gallery.preview")).findFirst().orElseThrow());
    }

    private PreviewRenderer.PreviewRequest request(ViewSpec.Preview preview) {
        var player = runtime.previewPlayerAppearance(preview).orElseThrow();
        var appearance = new PreviewRenderer.PreviewAppearance(player.skin(), player.model(), player.cape(),
                player.cape().isPresent() || preview.nativeAccessories() ? preview.capeMode() : PreviewRenderer.CapeMode.OFF,
                preview.outerLayerVisibility(), preview.capeHasElytra(), false, preview.nativeAccessories());
        return ViewHostPolicy.previewRequest(preview, Optional.empty(), Optional.of(appearance)).orElseThrow();
    }

    public java.util.function.Consumer<PreviewRenderer.BodyStatus> observeBody(ViewSpec.Preview preview, PreviewRenderer<?> renderer) {
        String source = runtime.previewBodySource(preview);
        return runtime.observePreviewBody(preview, renderer::displayKind, () -> renderer.confirmGalleryBody(source));
    }

    public void selectC() { runtime.dispatchWidget("gallery.card." + C); }

    public void localCapes() throws Exception {
        var references = new HashMap<UUID, LocalCapeReference>();
        for (var id : List.of(B, C)) {
            var image = new java.awt.image.BufferedImage(64,32,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            int[] pixels = new int[2048]; Arrays.fill(pixels,id.equals(B) ? 0xFF228833 : 0xFF884422);
            image.setRGB(0,0,64,32,pixels,0,64);
            var path = directory.resolve(id.equals(B) ? "cape-a.png" : "cape-b.png");
            javax.imageio.ImageIO.write(image,"PNG",path.toFile());
            var prepared = new com.naocraftlab.skins.core.png.CapeAssetDecoder().prepare(path,directory.resolve(id.equals(B) ? "frames-a" : "frames-b"));
            references.put(id,storage.importPreparedCape(ACCOUNT,"Owned cape",prepared,ignored -> {}).texture());
        }
        storage.updateAccount(ACCOUNT,old -> new AccountState(old.schemaVersion(),old.accountId(),old.skinAssets(),old.personalSkins(),
                old.presets().stream().map(p -> p.withOfflineCape(references.get(p.id()))).toList(),old.updatedAt(),old.personalCapes()));
        var accept = ClientRuntime.class.getDeclaredMethod("acceptInitialData",ClientOperations.InitialData.class,boolean.class);
        accept.setAccessible(true); accept.invoke(runtime,operations.initializeForRuntime(),false);
        runtime.dispatchWidget("gallery.reset"); drainWorkers();
    }

    public void localCapeWinner() throws Exception {
        var image = new java.awt.image.BufferedImage(64,32,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[2048]; Arrays.fill(pixels,0xFF338844); image.setRGB(0,0,64,32,pixels,0,64);
        var bytes = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image,"PNG",bytes);
        var entry = storage.importCape(ACCOUNT,"Competing local cape",bytes.toByteArray(),current -> {});
        storage.updateAccount(ACCOUNT,old -> new AccountState(old.schemaVersion(),old.accountId(),old.skinAssets(),old.personalSkins(),
                old.presets().stream().map(p -> p.id().equals(B) ? p.withOfflineCape(entry.texture()) : p).toList(),old.updatedAt(),old.personalCapes()));
        var accept = ClientRuntime.class.getDeclaredMethod("acceptInitialData",ClientOperations.InitialData.class,boolean.class);
        accept.setAccessible(true); accept.invoke(runtime,operations.initializeForRuntime(),false);
        runtime.dispatchWidget("gallery.reset"); drainWorkers();
    }

    public void offlineCapeCapability(boolean elytra) throws Exception {
        if (capabilityCape == null) {
            var image = new java.awt.image.BufferedImage(64,32,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            int[] pixels = new int[2048]; Arrays.fill(pixels,0xFF446688); image.setRGB(0,0,64,32,pixels,0,64);
            var bytes = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image,"PNG",bytes);
            capabilityCape = storage.importCape(ACCOUNT,"Capability cape",bytes.toByteArray(),current -> {}).texture();
        }
        UUID selected = runtime.snapshot().selectedPresetId().orElseThrow();
        storage.updateAccount(ACCOUNT, old -> new AccountState(old.schemaVersion(), old.accountId(), old.skinAssets(),old.personalSkins(),
                old.presets().stream().map(p -> p.id().equals(selected)
                        ? p.withOfflineCape(new LocalCapeReference(capabilityCape.entryId(),capabilityCape.sha256(),elytra)) : p).toList(), old.updatedAt(),old.personalCapes()));
        var data = operations.initializeForRuntime();
        var accept = ClientRuntime.class.getDeclaredMethod("acceptInitialData",ClientOperations.InitialData.class,boolean.class);
        accept.setAccessible(true); accept.invoke(runtime,data,false);
        var publish = ClientRuntime.class.getDeclaredMethod("publish"); publish.setAccessible(true); publish.invoke(runtime);
    }

    public void favoriteDisplayed() throws Exception { favoriteDisplayed(() -> {}); }

    public void favoriteDisplayed(Runnable admitted) throws Exception {
        var before = preview();
        var source = runtime.previewBodySource(before);
        var generation = runtime.snapshot().generation();
        var views = new ArrayList<ViewSpec>();
        var subscription = runtime.subscribe(ignored -> views.add(runtime.view(854,480,0,0)));
        try {
            String id = "gallery.preset." + before.presetId().orElseThrow();
            runtime.dispatchWidget(id + ".more");
            var star = runtime.view(854,480,0,0).widget(id + ".favorite").orElseThrow();
            if (!star.visible() || !star.enabled() || !star.activationEnabled()) throw new AssertionError("Star admission");
            runtime.dispatchWidget(star.id(),false,InteractionOrigin.POINTER);
            if (workers.size() != 1) throw new AssertionError("Actual favorite writer was not admitted");
            admitted.run();
            while (!workers.isEmpty()) workers.remove().run();
            var latest = operations.reloadEditorAccount(ACCOUNT).orElseThrow();
            if (!latest.presets().stream().filter(p -> p.id().equals(before.presetId().orElseThrow())).findFirst().orElseThrow().favorite())
                throw new AssertionError("Favorite durable readback");
            if (!source.equals(runtime.previewBodySource(runtime.galleryPreviewCandidate().orElseThrow()))
                    || runtime.snapshot().generation() != generation) throw new AssertionError("Favorite retired source/generation");
            for (var view : views) {
                if (!view.widget("gallery.providers").orElseThrow().enabled()
                        || !view.widget("gallery.search").orElseThrow().enabled()
                        || view.widget("gallery.outer_layer.head").filter(ViewSpec.Widget::enabled).isEmpty())
                    throw new AssertionError("Favorite published global busy/left flicker");
            }
        } finally { subscription.close(); }
    }

    public Runnable coherentAction(String action) {
        var before = runtime.view(854,480,0,0);
        var shown = preview();
        var control = before.widget(action).orElseThrow();
        if (!control.enabled() || !control.activationEnabled()) throw new AssertionError("Coherent action admission");
        var views = new ArrayList<ViewSpec>();
        var subscription = runtime.subscribe(ignored -> views.add(runtime.view(854,480,0,0)));
        views.clear();
        runtime.dispatchWidget(action,false,InteractionOrigin.POINTER);
        if (shown.bodySource().equals(runtime.galleryPreviewCandidate().orElseThrow().bodySource()))
            throw new AssertionError("Admitted coherent action did not produce new source");
        return () -> {
            subscription.close();
            if (views.isEmpty()) throw new AssertionError("No coherent publications");
            for (var view : views) {
                var retained = view.previews().stream().filter(p -> p.id().equals("gallery.preview")).findFirst().orElseThrow();
                if (!shown.equals(retained)) throw new AssertionError("Intermediate partial left composition");
                for (var widget : before.widgets()) if (widget.id().startsWith("gallery.outer_layer.") || widget.id().startsWith("gallery.inspect.")) {
                    var next = view.widget(widget.id()).orElseThrow();
                    if (!widget.bounds().equals(next.bounds()) || widget.enabled() != next.enabled() || next.activationEnabled())
                        throw new AssertionError("Coherent native callback control geometry/style/activation");
                }
            }
        };
    }

    public Runnable noEffectsCheckpoint() throws Exception {
        var files = inventory();
        var account = runtime.snapshot().account();
        var active = runtime.snapshot().activePresetId();
        var providers = runtime.snapshot().providers();
        return () -> {
            try {
                if (!files.equals(inventory()) || !account.equals(runtime.snapshot().account())
                        || !active.equals(runtime.snapshot().activePresetId()) || !providers.equals(runtime.snapshot().providers()))
                    throw new AssertionError("Gallery interaction changed NCL durable/account/provider state");
            } catch (Exception failure) { throw new AssertionError(failure); }
        };
    }

    private Map<String, String> inventory() throws Exception {
        var result = new TreeMap<String, String>();
        try (var files = Files.walk(directory)) {
            for (var file : files.filter(Files::isRegularFile).toList()) result.put(directory.relativize(file).toString(),
                    HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }
        return result;
    }

    public void verifyCycle(String group, OuterLayerVisibility before, boolean reverse) {
        if (!layerController.current().equals(EditorOuterLayerCycle.cycle(group,before,reverse ? -1 : 1)))
            throw new AssertionError("Native controller must cycle fresh current flags");
    }

    public void fallback() throws Exception {
        operations.deletePreset(new LibraryEditorPort.GalleryScope(operations.sessionIdentity().profileId(), 0, () -> true), B);
        runtime.dispatchWidget("gallery.reset");
        while (!workers.isEmpty()) workers.remove().run();
        if (runtime.snapshot().selectedPresetId().isPresent()) throw new AssertionError("Fallback must have no selected preset");
    }

    public void layersVisible(boolean expected) {
        var view = runtime.view(854,480,0,0);
        boolean actual = view.widget("gallery.outer_layer.body").filter(ViewSpec.Widget::enabled).isPresent();
        if (actual != expected) throw new AssertionError("Gallery layer admission expected=" + expected + " actual=" + actual);
        if (!expected && (view.texts().stream().anyMatch(t -> t.id().equals("gallery.layers"))
                || view.navigationNodes().stream().anyMatch(n -> n.id().startsWith("gallery.outer_layer."))))
            throw new AssertionError("Hidden Gallery layers retain header/navigation");
    }

    public PreviewRenderer.DisplayKind displayed() throws Exception {
        var field = ClientRuntime.class.getDeclaredField("galleryFlow"); field.setAccessible(true);
        var method = GalleryFlow.class.getDeclaredMethod("draftInput"); method.setAccessible(true);
        return ((GalleryPresenter.DraftInput) method.invoke(field.get(runtime))).displayedBodyKind();
    }

    @Override public void close() {
        try { if (verifyTeardown != null) verifyTeardown.run(); }
        finally { runtime.close(); }
    }
}
