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
    public int numericLoads, numericCreates, numericPublicationChecks;
    public Runnable beforeNumericPublication;
    public PreviewModelFitStore.Key numericKey;

    public PreviewModelFitStore.Result durableNumericFit() {
        if (numericKey == null) throw new AssertionError("No actual numeric lookup key");
        return new com.naocraftlab.skins.runtime.infrastructure.PreviewModelFitStorageAdapter(
                new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC())).load(numericKey);
    }
    private final Deque<Runnable> workers = new ArrayDeque<>();
    private final Deque<Runnable> previewWorkers = new ArrayDeque<>();
    private final PreviewRenderer.PreviewAppearance fixtureAppearance;
    private final byte[] fixtureSkin;
    private final CapeProviderCoordinator coordinator;

    public GalleryNativePassFixture fresh() throws Exception { return new GalleryNativePassFixture(fixtureAppearance, fixtureSkin, coordinator); }

    public GalleryNativePassFixture(PreviewRenderer.PreviewAppearance appearance, byte[] bundledSkin) throws Exception {
        this(appearance, bundledSkin, null);
    }

    private final Map<UUID, byte[]> ownedSkins = new HashMap<>();
    private LocalCapeReference capabilityCape;
    private String timedCape;
    public long capeNanos;

    public GalleryNativePassFixture freshOwned() throws Exception { return new GalleryNativePassFixture(fixtureAppearance, fixtureSkin, coordinator, true); }
    public GalleryNativePassFixture freshOwnedCpm() throws Exception { return new GalleryNativePassFixture(fixtureAppearance, fixtureSkin, coordinator, true, true); }
    public GalleryNativePassFixture freshOwnedCpm(byte[] content) throws Exception { return new GalleryNativePassFixture(fixtureAppearance, content, coordinator, true, true); }
    public void drainWorkers() {
        while (!workers.isEmpty() || !previewWorkers.isEmpty()) {
            while (!workers.isEmpty()) workers.remove().run();
            if (!previewWorkers.isEmpty()) previewWorkers.remove().run();
        }
    }
    public void worldChanged() { runtime.importWorldChanged(); }
    public boolean runNextWorker() {
        if (workers.isEmpty()) return false;
        workers.remove().run(); return true;
    }
    public byte[] ownedBytes(ViewSpec.Preview preview) { return ownedSkins.get(preview.presetId().orElseThrow()).clone(); }
    public void selectB() { runtime.dispatchWidget("gallery.card." + B); }

    public void retainedSources(byte[] second, byte[] third) throws Exception {
        var clock = Clock.systemUTC();
        var adapter = new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(storage);
        var library = new com.naocraftlab.skins.core.service.LibraryService(adapter,adapter,clock);
        var b = library.importSkin(ACCOUNT,"Retained B",SkinVariant.CLASSIC,SkinSource.IMPORTED,second).asset();
        var c = library.importSkin(ACCOUNT,"Retained C",SkinVariant.CLASSIC,SkinSource.IMPORTED,third).asset();
        UUID last = new UUID(0,804);
        storage.updateAccount(ACCOUNT,old -> new AccountState(old.schemaVersion(),old.accountId(),old.skinAssets(),old.personalSkins(),
                List.of(old.presets().stream().filter(p -> p.id().equals(B)).findFirst().orElseThrow(),
                        new AppearancePreset(C,"B",SkinReference.asset(b.id()),null,clock.instant(),clock.instant()),
                        new AppearancePreset(last,"C",SkinReference.asset(c.id()),null,clock.instant(),clock.instant())),old.updatedAt(),old.personalCapes()));
        ownedSkins.put(C,second.clone()); ownedSkins.put(last,third.clone());
        var accept=ClientRuntime.class.getDeclaredMethod("acceptInitialData",ClientOperations.InitialData.class,boolean.class);
        accept.setAccessible(true); accept.invoke(runtime,operations.initializeForRuntime(),false);
        runtime.dispatchWidget("gallery.reset"); drainWorkers();
    }

    public void selectLast() { runtime.dispatchWidget("gallery.card." + new UUID(0,804)); }

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

    private static CapeProviderCoordinator numericCoordinator;
    public static void closeNumericCoordinator() {
        if (numericCoordinator != null) numericCoordinator.close();
        numericCoordinator = null;
    }
    public static GalleryNativePassFixture numeric(PreviewRenderer.PreviewAppearance appearance, byte[] content, Path root) throws Exception {
        return numeric(appearance,content,root,false);
    }
    public static GalleryNativePassFixture numeric(PreviewRenderer.PreviewAppearance appearance, byte[] content, Path root, boolean holdPreviewAssets) throws Exception {
        var fixture = new GalleryNativePassFixture(appearance, content, numericCoordinator, true, true, root, holdPreviewAssets);
        numericCoordinator = fixture.coordinator;
        return fixture;
    }

    public static GalleryNativePassFixture cards(PreviewRenderer.PreviewAppearance appearance, byte[] content, Path root,
            SkinCatalogSource catalog) throws Exception {
        var fixture = new GalleryNativePassFixture(appearance, content, numericCoordinator, true, true, root, true, catalog);
        var adapter=new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(fixture.storage);
        var library=new com.naocraftlab.skins.core.service.LibraryService(adapter,adapter,Clock.systemUTC());
        library.savePresetWithPersonalSkin(ACCOUNT,Optional.of(B),"B","Owned personal",SkinVariant.CLASSIC,PersonalSkinSource.FILE,content,null);
        numericCoordinator = fixture.coordinator;
        return fixture;
    }

    public List<SkinCatalogSource.CollectionDescriptor> cardCollections() throws Exception { return operations.catalogCollections(); }

    public List<Map<String,Object>> cardLayoutRoundTrip() {
        var rows=new ArrayList<Map<String,Object>>();
        for(String owner:List.of("editor","providers")) {
            if(owner.equals("editor")) {
                var selected=runtime.snapshot().selectedPresetId().orElseThrow();
                runtime.dispatchWidget("gallery.preset."+selected+".more");runtime.dispatchWidget("gallery.preset."+selected+".edit");
            } else runtime.dispatchWidget("gallery.providers");
            drainWorkers();
            var view=runtime.view(854,720,0,0);
            var preview=view.previews().stream().filter(p->p.id().equals("editor.preview")).findFirst().orElseThrow();
            if(preview.layoutScale()==1 || preview.scale()!=1)throw new AssertionError("real nondefault layout / initial logical one "+owner);
            var anchor=preview.anchorBounds();
            runtime.pointerScrolled(anchor.x()+anchor.width()/2.0,anchor.y()+anchor.height()/2.0,0,1);
            var changed=runtime.view(854,720,0,0).previews().stream().filter(p->p.id().equals("editor.preview")).findFirst().orElseThrow();
            if(changed.scale()<=1 || changed.layoutScale()!=preview.layoutScale())throw new AssertionError("real Editor/Providers zoom preserves layout");
            rows.add(Map.of("owner",owner,"height",720,"layoutScale",changed.layoutScale(),"userZoom",changed.scale(),"screen",view.screenId()));
            runtime.dispatchWidget(owner.equals("editor")?"editor.cancel":"providers.back");drainWorkers();
            if(!runtime.view(854,480,0,0).screenId().equals("gallery"))throw new AssertionError("actual return to cards "+owner);
        }
        return rows;
    }

    public ExternalImportModel cardImport(Path root) throws Exception {
        var review=operations.prepareExternalAppearances(com.naocraftlab.skins.core.importing.ExternalImportSource.SKIN_SHUFFLE,Optional.of(root));
        var model=ExternalImportModel.open(ExternalImportModel.Category.MOD).withReview(review);
        var flowField=ClientRuntime.class.getDeclaredField("catalogImportFlow");flowField.setAccessible(true);
        Object flow=flowField.get(runtime);
        var stateField=flow.getClass().getDeclaredField("state");stateField.setAccessible(true);
        Object state=stateField.get(flow);
        var field=state.getClass().getDeclaredField("externalImport");field.setAccessible(true);field.set(state,model);
        return model;
    }

    private GalleryNativePassFixture(PreviewRenderer.PreviewAppearance appearance, byte[] bundledSkin, CapeProviderCoordinator sharedCoordinator, boolean owned, boolean cpm) throws Exception {
        this(appearance, bundledSkin, sharedCoordinator, owned, cpm, null);
    }

    private GalleryNativePassFixture(PreviewRenderer.PreviewAppearance appearance, byte[] bundledSkin, CapeProviderCoordinator sharedCoordinator, boolean owned, boolean cpm, Path root) throws Exception {
        this(appearance,bundledSkin,sharedCoordinator,owned,cpm,root,false);
    }

    private GalleryNativePassFixture(PreviewRenderer.PreviewAppearance appearance, byte[] bundledSkin, CapeProviderCoordinator sharedCoordinator, boolean owned, boolean cpm, Path root, boolean holdPreviewAssets) throws Exception {
        this(appearance,bundledSkin,sharedCoordinator,owned,cpm,root,holdPreviewAssets,null);
    }

    private GalleryNativePassFixture(PreviewRenderer.PreviewAppearance appearance, byte[] bundledSkin, CapeProviderCoordinator sharedCoordinator, boolean owned, boolean cpm, Path root, boolean holdPreviewAssets, SkinCatalogSource catalog) throws Exception {
        fixtureAppearance = appearance;
        fixtureSkin = bundledSkin.clone();
        directory = root == null ? Files.createTempDirectory("gallery-native-pass-") : Files.createDirectories(root).toRealPath();
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
            var variant = root != null && i == 0 && appearance.model() == SkinModel.SLIM ? SkinVariant.SLIM : i == 0 ? SkinVariant.CLASSIC : SkinVariant.SLIM;
            var asset = library.importSkin(ACCOUNT,"Owned " + i, variant, SkinSource.IMPORTED,png).asset();
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
                catalog == null ? (BundledSkinSource) model -> bundledSkin : catalog, clock, null);
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
        var runtimeOperations = sharedCoordinator == null && root == null ? operations : (ClientOperations) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ClientOperations.class}, (proxy, method, args) -> {
            if (method.getName().equals("close")) return null;
            if (method.getName().equals("openPreparedCapePreview") && timedCape != null && timedCape.equals(args[2])) {
                var owner = operations.acquirePreparedCapePreview(timedCape).orElseThrow();
                return Optional.of(PreparedCapePreview.open(owner, (java.util.concurrent.Executor) args[3],
                        (ClientExecutor) args[4], () -> capeNanos, (boolean) args[5]));
            }
            try { return method.invoke(operations, args); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
        CurrentPlayerAppearanceSource current = () -> new CurrentPlayerAppearanceSource.PlayerAppearance(
                appearance.skin(), appearance.model(), appearance.cape());
        var numericStore = new com.naocraftlab.skins.runtime.infrastructure.PreviewModelFitStorageAdapter(storage);
        var services = new DefaultRuntimeServices(cpm ? new PreviewModelFitStore() {
                    public Result load(Key key) { numericLoads++; numericKey=key; return numericStore.load(key); }
                    public Result create(Key key, Value value, java.util.function.BooleanSupplier current) {
                        numericCreates++;
                        return numericStore.create(key,value,() -> {
                            if (beforeNumericPublication != null && StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                                    frame.getClassName().equals("com.naocraftlab.skins.core.storage.AtomicFileWriter")
                                            && frame.getMethodName().equals("createImmutable")))) {
                                numericPublicationChecks++;
                                if (numericPublicationChecks == 2) {
                                    var action=beforeNumericPublication;
                                    beforeNumericPublication=null;
                                    action.run();
                                }
                            }
                            return current.getAsBoolean();
                        });
                    }
                } : null);
        var runtimeServices = !holdPreviewAssets ? services : (RuntimeServices) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{RuntimeServices.class}, (proxy, method, args) -> {
                    if (method.getName().equals("previewAssets")) {
                        args[1] = (java.util.concurrent.Executor) previewWorkers::add;
                        var assets=(PreviewAssets)method.invoke(services,args);
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreviewAssets.class},(ignored,assetMethod,assetArgs)-> {
                            if(assetMethod.getName().equals("installWarmed")) {
                                previewWorkers.add(()-> {
                                    try { assetMethod.invoke(assets,assetArgs); }
                                    catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
                                });
                                return null;
                            }
                            try { return assetMethod.invoke(assets,assetArgs); }
                            catch(java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                        });
                    }
                    try { return method.invoke(services,args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        runtime = new ClientRuntime(runtimeServices, runtimeOperations, client,
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

    public ClientRuntime coldMenu() {
        var menuOperations = (ClientOperations)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ClientOperations.class},(proxy,method,args)-> {
            if(method.getName().equals("close"))return null;
            try{return method.invoke(operations,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
        var store = new PreviewModelFitStore() {
            public Result load(Key key){throw new AssertionError("cold menu numeric lookup");}
            public Result create(Key key,Value value,java.util.function.BooleanSupplier current){throw new AssertionError("cold menu full first-create");}
        };
        return new ClientRuntime(new DefaultRuntimeServices(store),menuOperations,new ClientExecutor() {
            public boolean isClientThread(){return true;}public void execute(Runnable action){action.run();}
        },()->CompletableFuture.completedFuture(Optional.empty()),action->{throw new AssertionError("cold menu model worker demand");},UiMessage::key,
                Optional.of(()->new CurrentPlayerAppearanceSource.PlayerAppearance(fixtureAppearance.skin(),fixtureAppearance.model(),fixtureAppearance.cape())),
                Optional.empty(),Optional.of(new OuterLayerVisibilityController() {
                    public OuterLayerVisibility current(){return OuterLayerVisibility.allVisible();}
                    public void applyDurable(OuterLayerVisibility value){throw new AssertionError("cold menu durable write");}
                }),Optional.empty(),DiagnosticSinks.discarding());
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
        return runtime.observePreviewBody(preview, renderer::displayKind, () -> renderer.confirmGalleryBody(source), renderer::retireBody);
    }

    public void selectC() { runtime.dispatchWidget("gallery.card." + C); }

    public record StaticSurface(String role, ViewSpec view, ViewSpec.Preview preview, byte[] png) {}

    public ViewSpec capeSurface() throws Exception {
        localCapes();
        var more=runtime.view(854,480,0,0).widgets().stream().filter(w -> w.id().endsWith(".more") && w.id().startsWith("gallery.preset.") && w.visible() && w.enabled()).findFirst().orElseThrow();
        runtime.dispatchWidget(more.id());
        var edit=runtime.view(854,480,0,0).widgets().stream().filter(w -> w.id().endsWith(".edit") && w.id().startsWith("gallery.preset.") && w.visible() && w.enabled()).findFirst().orElseThrow();
        runtime.dispatchWidget(edit.id());runtime.dispatchWidget("editor.tab.cape");drainWorkers();
        return runtime.view(854,480,0,0);
    }

    public List<StaticSurface> staticSurfaces() throws Exception {
        var result = new ArrayList<StaticSurface>();
        var gallery = runtime.view(854,480,0,0);
        var card = gallery.previews().stream().filter(p -> p.id().startsWith("gallery.preset.")).findFirst().orElseThrow();
        result.add(new StaticSurface("gallery",gallery,card,ownedBytes(card)));
        var descriptor = new SkinCatalogSource.SkinDescriptor("owned", "Owned", Optional.empty(), Optional.empty(), List.of(SkinModel.CLASSIC));
        var collection = new SkinCatalogSource.CollectionDescriptor("fixture", "Fixture", Optional.empty(), Optional.empty(), List.of(descriptor));
        var model = AddSourceModel.open(AccountUiPreferences.defaults(ACCOUNT),List.of(collection),SkinVariant.CLASSIC,UiMessage::key).withSelectedTab(AddSourceTab.CATALOG);
        var catalog = new AddSourcePresenter().present(model,false,854,480);
        result.add(new StaticSurface("skin-catalog",catalog,catalog.previews().get(0),fixtureSkin.clone()));
        for(var category:ExternalImportModel.Category.values()) {
            var source = category == ExternalImportModel.Category.LAUNCHER ? com.naocraftlab.skins.core.importing.ExternalImportSource.MINECRAFT_LAUNCHER : com.naocraftlab.skins.core.importing.ExternalImportSource.SKIN_SHUFFLE;
            var candidate = new ImportOperations.ExternalImportCandidate("owned", "Owned",SkinVariant.CLASSIC,PersonalSkinSource.FILE,fixtureSkin.clone(),java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(fixtureSkin)),null,0,false);
            var review = ExternalImportModel.open(category).withReview(new ImportOperations.ExternalImportReview(source,List.of(candidate),0,0));
            var view = new ExternalImportPresenter().present(review,false,Optional.empty(),854,480);
            result.add(new StaticSurface("import-"+category,view,view.previews().get(0),fixtureSkin.clone()));
        }
        return List.copyOf(result);
    }

    public ViewSpec catalogFrame() {
        var descriptors = java.util.stream.IntStream.range(0,3).mapToObj(i -> new SkinCatalogSource.SkinDescriptor("owned"+i,"Owned "+i,Optional.empty(),Optional.empty(),List.of(SkinModel.CLASSIC))).toList();
        var collection = new SkinCatalogSource.CollectionDescriptor("fixture","Fixture",Optional.empty(),Optional.empty(),descriptors);
        var model = AddSourceModel.open(AccountUiPreferences.defaults(ACCOUNT),List.of(collection),SkinVariant.CLASSIC,UiMessage::key).withSelectedTab(AddSourceTab.CATALOG);
        if (Boolean.getBoolean("nclskins.rootBoundary")) {
            try {
                var flowField=ClientRuntime.class.getDeclaredField("catalogImportFlow");flowField.setAccessible(true);
                var flow=flowField.get(runtime);var stateField=flow.getClass().getDeclaredField("state");stateField.setAccessible(true);
                var state=stateField.get(flow);var modelField=state.getClass().getDeclaredField("addSource");modelField.setAccessible(true);modelField.set(state,model);
                return runtime.view(854,480,0,0);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
        return new AddSourcePresenter().present(model,false,854,480);
    }


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

    public void selectProviderCape() throws Exception {
        operations.usePreset(B);
        var accept = ClientRuntime.class.getDeclaredMethod("acceptInitialData", ClientOperations.InitialData.class, boolean.class);
        accept.setAccessible(true);
        accept.invoke(runtime, operations.initializeForRuntime(), false);
    }

    public void distinctPersonalCapes() throws Exception {
        for (int i = 0; i < 2; i++) {
            var image = new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            int[] pixels = new int[2048]; Arrays.fill(pixels, i == 0 ? 0xFF336699 : 0xFF996633);
            image.setRGB(0, 0, 64, 32, pixels, 0, 64);
            var bytes = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "PNG", bytes);
            storage.importCape(ACCOUNT, "Native transition " + i, bytes.toByteArray(), ignored -> {});
        }
        var accept = ClientRuntime.class.getDeclaredMethod("acceptInitialData", ClientOperations.InitialData.class, boolean.class);
        accept.setAccessible(true); accept.invoke(runtime, operations.initializeForRuntime(), false);
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

    public void sharedAnimatedCape() throws Exception {
        var path = directory.resolve("shared-animated-cape.gif");
        var writer = javax.imageio.ImageIO.getImageWritersByFormatName("gif").next();
        try (var output = new javax.imageio.stream.MemoryCacheImageOutputStream(Files.newOutputStream(path))) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            int[] pixels = new int[64 * 32];
            for (int color : new int[]{0xff224488, 0xffaa6644}) {
                Arrays.fill(pixels, color);
                var frame = new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                frame.setRGB(0, 0, 64, 32, pixels, 0, 64);
                writer.writeToSequence(new javax.imageio.IIOImage(frame, null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        var entry = operations.importCape(ACCOUNT, path, "Shared animated cape");
        storage.updateAccount(ACCOUNT, old -> new AccountState(old.schemaVersion(), old.accountId(),
                old.skinAssets(), old.personalSkins(), old.presets().stream().map(p -> p.withOfflineCape(entry.texture())).toList(),
                old.updatedAt(), old.personalCapes()));
        var accept = ClientRuntime.class.getDeclaredMethod("acceptInitialData", ClientOperations.InitialData.class, boolean.class);
        accept.setAccessible(true);
        accept.invoke(runtime, operations.initializeForRuntime(), false);
        runtime.dispatchWidget("gallery.reset");
        selectB();
        drainWorkers();
        timedCape = runtime.galleryPreviewCandidate().orElseGet(this::preview).capeId().orElseThrow();
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
