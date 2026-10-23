package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.compatibility.SkinExtensionEnvironment;
import com.naocraftlab.skins.core.png.PngValidator;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Proxy;

public final class CpmCallerHarness {
    public static ViewSpec previewFailureView(String screenId, TextResolver resolver, boolean failed) {
        UUID asset = new UUID(0, 2);
        var account = new AccountState(AccountState.CURRENT_SCHEMA_VERSION, new UUID(0, 1),
                List.of(new SkinAsset(asset, "Synthetic preview", "a".repeat(64), SkinVariant.CLASSIC,
                        SkinSource.VANILLA_DEFAULT, Instant.EPOCH, Instant.EPOCH)), List.of(), List.of(), Instant.EPOCH);
        var editor = PresetEditorModel.open(account,
                Optional.empty(), Optional.empty(), Optional.empty(), resolver, 240, PreviewRenderer.CapeMode.OFF);
        var base = editor.present(320, 240);
        var view = new ViewSpec(screenId, base.title(), base.width(), base.height(), base.panels(), base.texts(),
                base.widgets(), base.previews(), base.scrollbar(), base.tabGroups(), base.focusRequest(), base.clipRegions(),
                base.backEquipmentPreviews(), base.iconDecorations(), base.scrollSurfaces(), base.tooltipRegions(),
                base.progressDecorations(), base.navigationNodes());
        return failed ? PlayerAppearanceStage.withError(view, UiMessage.error("nclskins.editor.cpm_preview_failed"), resolver) : view;
    }

    public static java.util.function.BiFunction<Integer, Integer, ViewSpec> quotaViews(TextResolver resolver) {
        var model = AddSourceModel.open(AccountUiPreferences.defaults(new UUID(0, 1)), List.of(),
                SkinVariant.CLASSIC, resolver).withSelectedTab(AddSourceTab.FILE);
        var presenter = new AddSourcePresenter();
        var feedback = Optional.of(UiMessage.error("nclskins.add.cpm_publication_count_quota"));
        return (width, height) -> {
            var first = presenter.present(model, false, feedback, width, height);
            return presenter.present(model.withScrollOffset((int) first.scrollSurfaces().get(0).maximumPixels()),
                    false, feedback, width, height);
        };
    }

    private final ArrayDeque<Runnable> workers = new ArrayDeque<>(), clients = new ArrayDeque<>();
    private final ArrayDeque<Path> picks = new ArrayDeque<>();
    private final UUID account = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final CpmModelFileConversion adapter;
    private CatalogImportFlow flow;
    private EditorDraftTransfer result;
    private long generation;
    private boolean busy;
    private String lane = "client";
    private static String currentLane = "client";
    public static void requireWorkerConversion() { require(currentLane.equals("worker"), "Actual initialized-template conversion runs on worker lane"); }
    public static void requireWorkerAdmission() { require(currentLane.equals("worker"), "Actual pinned parser runs on worker lane"); }

    private int publications, preferences, conversionCalls;
    private String failureReason = "none";
    private UiMessage feedback = UiMessage.info("nclskins.external_import.choose_source");
    private final List<String> stages = new ArrayList<>();
    private byte[] remotePng;
    private final Map<SkinModel,byte[]> catalogBytes = new EnumMap<>(SkinModel.class);
    private final List<SkinModel> catalogReads = new ArrayList<>();
    private boolean catalogPersonal;
    private final CatalogMaterialization materialization = new CatalogMaterialization() {
        public byte[] loadCatalogSkin(String c,String s,SkinModel model) throws Exception {
            catalogReads.add(model);
            byte[] bytes=catalogBytes.get(model);
            if(bytes==null) throw new java.io.IOException("Synthetic unavailable variant");
            return bytes.clone();
        }
        public Optional<UUID> reusableCatalogSkinAsset(String c,String s,SkinModel m) {
            return catalogPersonal ? Optional.of(UUID.fromString("00000000-0000-0000-0000-000000000002")) : Optional.empty();
        }
        public Optional<FrozenCatalogSelection> freezeCatalogSelection(String c,String s) {
            return Optional.of(new FrozenCatalogSelection(account,c,s,1,Map.of(),Map.of()));
        }
        public PersonalCapeEntry materializeResourceCape(UUID a,CatalogRead.ResourceCapeSelection c) { throw new AssertionError("No materialization"); }
    };
    public byte[] catalog(Path model, Path base, SkinVariant required, boolean personal, boolean missing,boolean filteredAlternate) throws Exception {
        catalogPersonal=personal;
        SkinModel type=SkinModel.valueOf(required.name());
        catalogBytes.put(type==SkinModel.CLASSIC?SkinModel.SLIM:SkinModel.CLASSIC,Files.readAllBytes(base));
        if(!missing)catalogBytes.put(type,Files.readAllBytes(base));
        var skin=new SkinCatalogSource.SkinDescriptor("fixture","Fixture",Optional.empty(),Optional.empty(),List.of(SkinModel.CLASSIC,SkinModel.SLIM));
        var collection=new SkinCatalogSource.CollectionDescriptor("fixture",CatalogText.literal("Fixture"),Optional.empty(),Optional.empty(),List.of(skin),
                personal?CatalogCollectionOrder.personal("fixture"):CatalogCollectionOrder.vanilla("fixture"));
        var environment=new SkinExtensionEnvironment(1,Map.of(com.naocraftlab.skins.core.compatibility.SkinConsumer.FRESH_MOVES,com.naocraftlab.skins.core.compatibility.SkinConsumerState.ACTIVE));
        Map<CatalogRead.CatalogVariant,com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence> evidence=filteredAlternate
                ? Map.of(new CatalogRead.CatalogVariant("fixture","fixture",required==SkinVariant.CLASSIC?SkinVariant.SLIM:SkinVariant.CLASSIC),new com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence(List.of(),List.of(com.naocraftlab.skins.core.compatibility.SkinConflictReason.MALFORMED_EXPRESSIVE_DATA))) : Map.of();
        flow.openCatalog(new AddSourceData(AccountUiPreferences.defaults(account).withSelectedAddSourceTab(AddSourceTab.FILE),List.of(collection),evidence),SkinVariant.CLASSIC,environment,filteredAlternate);
        picks.add(model);flow.chooseAddSourcePng();drain();
        require(flow.addSource().filter().name().equals(required.name()),"Catalog required filter before selection");
        flow.selectCatalogSkin("fixture:fixture");drain();
        if(missing) {
            require(result==null && publications==0,"Missing required catalog variant never falls back or publishes");
            require(catalogReads.equals(List.of(type)),"Required catalog failure does not read alternate");
            flow.closeScreens();return null;
        }
        require(result!=null && publications==1,"Catalog same-type reaches converted Editor");
        require(result.draft().catalogOrigin().isEmpty(),"Catalog base never becomes converted origin");
        require(!result.draft().variantAvailable(required==SkinVariant.CLASSIC?SkinVariant.SLIM:SkinVariant.CLASSIC),"Catalog result is converted pair");
        require(catalogReads.equals(List.of(type)),"Only explicitly selected required variant read");
        require(preferences==0,"Catalog consumer no scoped preference writes");
        byte[] png=result.draft().png().orElseThrow().bytes();flow.closeScreens();return png;
    }
    public void lockRefresh(Path model, SkinVariant required) {
        var cards=new ArrayList<SkinCatalogSource.SkinDescriptor>();
        for(int i=0;i<100;i++)cards.add(new SkinCatalogSource.SkinDescriptor("fixture"+i,"parent query "+i,Optional.empty(),Optional.empty(),List.of(SkinModel.CLASSIC,SkinModel.SLIM)));
        var data=new AddSourceData(AccountUiPreferences.defaults(account).withSelectedAddSourceTab(AddSourceTab.FILE),
                List.of(new SkinCatalogSource.CollectionDescriptor("fixture","Fixture",Optional.empty(),Optional.empty(),cards)),Map.of());
        flow.openCatalog(data,SkinVariant.CLASSIC,SkinExtensionEnvironment.unknown(0),false);
        flow.searchCatalog("parent query");
        flow.navigateToOffset(23);
        picks.add(model);flow.chooseAddSourcePng();drain();
        require(flow.addSource().filter().name().equals(required.name()),"First chooser required filter");
        flow.searchCatalog("chooser query");
        flow.cycleCatalogFilter(false);flow.cycleCatalogFilter(true);
        require(flow.addSource().filter().name().equals(required.name()),"Both filter dispatch directions locked");
        flow.preferredVariantChanged(required==SkinVariant.CLASSIC?SkinVariant.SLIM:SkinVariant.CLASSIC);
        flow.openEmptyCatalog(AccountUiPreferences.defaults(account),SkinVariant.CLASSIC,SkinExtensionEnvironment.unknown(1),false);
        require(flow.addSource().filter().name().equals(required.name()),"Empty refresh cannot reset required filter");
        require(flow.addSource().query().equals("chooser query"),"Refresh preserves chooser query");
        flow.openCatalog(data,SkinVariant.CLASSIC,SkinExtensionEnvironment.unknown(2),false);
        require(flow.addSource().filter().name().equals(required.name()),"Catalog refresh cannot reset required filter");
        flow.selectAddSourceTab(AddSourceTab.FILE);flow.selectAddSourceTab(AddSourceTab.CATALOG);
        flow.cancelAddSource();
        require(flow.addSource().filter()==AddSourceModel.CatalogFilter.ALL && flow.addSource().selectedTab()==AddSourceTab.FILE,"Return restores unlocked ordinary parent tab/filter");
        require(flow.addSource().query().equals("parent query") && flow.addSource().scrollOffset()==23,"Return restores exact parent query/scroll");
        require(preferences==0,"Scoped refresh/tab/filter/return never persist preferences");
        require(feedback.equals(UiMessage.info("nclskins.external_import.choose_source")),"Parent feedback restored exactly after chooser");
        require(flow.addSourceScrollPosition()==23 && flow.addSourceScrollTarget()==23,"Parent scroll driver restored with model");
        flow.closeScreens();
    }
    private int externalOpens, externalEffects;
    private byte[] externalPng;
    private Path externalRoot;
    private boolean externalReviewReady;
    public void staleExternalPreparation(Path model,String route) {
        var source=com.naocraftlab.skins.core.importing.ExternalImportSource.MINECRAFT_LAUNCHER;
        flow.openExternalCategory(ExternalImportModel.Category.LAUNCHER);
        flow.acceptAutomaticProbes(Map.of(source,com.naocraftlab.skins.core.importing.ExternalImportProbe.AVAILABLE));
        flow.prepareExternalImport(source);drain();
        require(externalEffects==1,"Ordinary underlying source preparation is exercised");
        if(route.equals("commit")) {
            externalReviewReady=true;flow.prepareExternalImport(source);drain();
            require(flow.externalImport().review().isPresent(),"Ordinary preparation publishes a real review");
            flow.commitExternalImport();
        } else if(route.equals("folder")) {
            externalRoot=model.getParent();flow.chooseExternalImportFolder(source);clientStep();
        } else flow.prepareExternalImport(source);
        Runnable old=workers.remove();
        while(flow.externalImport()!=null)flow.cancelExternalImport();
        picks.add(model);flow.chooseAddSourcePng();drain();
        require(flow.addSource().filter()!=AddSourceModel.CatalogFilter.ALL,"Actual model admitted before old source worker");
        int before=externalEffects;
        workers.add(old);drain();
        require(externalEffects==before,"Queued external source cannot read DB/files after CPM base admission");
        require(flow.addSource().importContext().purpose()==AddSourceModel.Purpose.CPM_BASE,"Stale external continuation preserves chooser");
        flow.closeScreens();
    }
    public void blockedExternal(Path model) {
        require(flow.addSource().importContext().purpose()==AddSourceModel.Purpose.CREATE_PRESET
                && flow.addSource().importContext().allows(AddSourceModel.SourceAction.LAUNCHER),"Ordinary projection permits external imports");
        flow.dispatchCatalogImportAction("add.external.launcher",false,InteractionOrigin.PROGRAMMATIC);
        require(externalOpens==1,"Ordinary external dispatch positive control");
        picks.add(model);flow.chooseAddSourcePng();drain();flow.selectAddSourceTab(AddSourceTab.FILE);
        require(!flow.addSource().importContext().allows(AddSourceModel.SourceAction.LAUNCHER)
                && !flow.addSource().importContext().allows(AddSourceModel.SourceAction.MOD)
                && flow.addSource().importContext().allows(AddSourceModel.SourceAction.PNG),"Neutral base action policy blocks only external sources");
        flow.dispatchCatalogImportAction("add.external.launcher",false,InteractionOrigin.PROGRAMMATIC);
        flow.dispatchCatalogImportAction("add.external.mod",false,InteractionOrigin.PROGRAMMATIC);
        require(externalOpens==1,"CPM base dispatch blocks external imports before any probe");
        flow.openExternalCategory(ExternalImportModel.Category.LAUNCHER);
        require(flow.externalImport()==null,"Underlying external category also blocked");
        for(var source:com.naocraftlab.skins.core.importing.ExternalImportSource.values()) {
            flow.prepareExternalImport(source);flow.chooseExternalImportFolder(source);
        }
        flow.commitExternalImport();drain();
        require(externalOpens==1,"No underlying external effects");flow.closeScreens();
    }
    private boolean remoteRejected, publicFailureMapped;
    public void remoteFailure(Path model,Path base,boolean player) throws Exception {
        remotePng=Files.readAllBytes(base);remoteRejected=true;
        picks.add(model);flow.chooseAddSourcePng();drain();flow.selectAddSourceTab(AddSourceTab.FILE);
        if(player)flow.editPlayerInput("Fixture");else flow.editUrlInput("https://example.invalid/base.png");
        flow.loadRemoteImport(player);drain();
        require(result==null && publicFailureMapped,"Scoped remote preparation preserves public failure mapper");
        require(flow.addSource().importContext().filterLocked() && !busy,"Public failure retains model lock and settles");
        flow.closeScreens();
    }
    private final ImportOperations imports = (ImportOperations) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{ImportOperations.class}, (p,m,a) -> {
                var images = new com.naocraftlab.skins.core.importing.PublicSkinImageSource() {
                    public Acquired acquireUrl(String url) throws Exception { return new PngInput(loadUntrusted(url)); }
                    public Acquired acquireFile(java.nio.file.Path path) throws Exception {
                        return com.naocraftlab.skins.core.api.PublicSkinImageAdapter.acquireLocal(path);
                    }
                    public com.naocraftlab.skins.core.png.NormalizedSkin loadUntrusted(String url) throws Exception {
                        if(remoteRejected)throw new com.naocraftlab.skins.core.api.PublicSkinImportException(com.naocraftlab.skins.core.api.PublicSkinImportException.Code.UNSAFE_URL,"Synthetic rejection");
                        return new PngValidator().projectImport(remotePng);
                    }
                    public com.naocraftlab.skins.core.png.NormalizedSkin loadVerified(com.naocraftlab.skins.core.importing.PublicProfileLookup.VerifiedTexture texture) throws Exception {
                        if(remoteRejected)throw new com.naocraftlab.skins.core.api.PublicSkinImportException(com.naocraftlab.skins.core.api.PublicSkinImportException.Code.UNSAFE_URL,"Synthetic rejection");
                        return new PngValidator().projectImport(remotePng);
                    }
                };
                var lookup = (com.naocraftlab.skins.core.importing.PublicProfileLookup) input ->
                        new com.naocraftlab.skins.core.importing.PublicProfileLookup.Observation("Fixture",
                                Optional.of(new com.naocraftlab.skins.core.importing.PublicProfileLookup.VerifiedTexture() {}),
                                SkinVariant.SLIM, Optional.empty());
                var service = new PublicSkinImportService(Optional.of(lookup), images, (c,s,v) -> remotePng);
                return switch (m.getName()) {
                    case "acquireFile" -> {
                        require(lane.equals("worker"), "Actual file acquisition runs on worker");
                        var acquired = service.acquireFile((Path)a[0], (Optional<CpmModelFileConversion>)a[1], (CpmModelFileConversion.Reason)a[2]);
                        if (acquired instanceof ImportOperations.AcquiredSkin && flow.addSource().importContext().purpose() == AddSourceModel.Purpose.CPM_BASE) stages.add("decode");
                        yield acquired;
                    }
                    case "acquireUrl" -> service.acquireUrl((String)a[0], (Optional<CpmModelFileConversion>)a[1], (CpmModelFileConversion.Reason)a[2]);
                    case "loadUrlSkin" -> service.loadUrl((String)a[0]);
                    case "loadPlayerSkin" -> service.loadPlayer((String)a[0]);
                    case "prepareExternalAppearances" -> {
                        externalEffects++;
                        if(!externalReviewReady)throw new java.io.IOException("Synthetic external failure");
                        yield new ImportOperations.ExternalImportReview((com.naocraftlab.skins.core.importing.ExternalImportSource)a[0],
                                List.of(new ImportOperations.ExternalImportCandidate("fixture","Fixture",SkinVariant.CLASSIC,PersonalSkinSource.FILE,externalPng,"a".repeat(64),null,0,false)),0,0);
                    }
                    case "commitExternalAppearances" -> {externalEffects++;throw new java.io.IOException("Synthetic external failure");}
                    case "probeExternalSource" -> { externalEffects++;yield com.naocraftlab.skins.core.importing.ExternalImportProbe.AVAILABLE; }
                    default -> throw new AssertionError("Forbidden import effect " + m.getName());
                };
            });

    @SuppressWarnings("unchecked")
    private void submit(Object[] args) {
        busy = true;
        long revision = ++generation;
        workers.add(() -> {
            Object value = null; Throwable failure = null;
            try { value = ((ThrowingSupplier<?>)args[1]).get(); }
            catch (Exception problem) { failure = problem; }
            Object completed = value; Throwable problem = failure;
            clients.add(() -> {
                if (revision != generation) return;
                busy = false;
                if (problem == null) ((java.util.function.Consumer<Object>)args[2]).accept(completed);
                else ((java.util.function.Consumer<Throwable>)args[3]).accept(problem);
            });
        });
    }

    public byte[] remote(Path model, Path base, boolean player, boolean mismatch) throws Exception {
        remotePng = Files.readAllBytes(base);
        picks.add(model); flow.chooseAddSourcePng(); drain();
        require(result == null && !busy, "Valid model admission before remote base");
        flow.selectAddSourceTab(AddSourceTab.FILE);
        if (player) flow.editPlayerInput("Fixture"); else flow.editUrlInput("https://example.invalid/base.png");
        flow.loadRemoteImport(player); drain();
        if (mismatch) {
            require(result == null && publications == 0, "Remote mismatch is rejected before Editor");
            require(failureReason.equals("VARIANT_UNSUPPORTED"), "Remote mismatch retains typed reason");
            require(preferences == 0 && !busy && conversionCalls==0, "Remote mismatch writes no preferences, never calls converter and settles");
            return null;
        }
        require(result != null && publications == 1, "Remote same-type reaches converted Editor");
        require(!result.draft().variantAvailable(result.draft().variant() == SkinVariant.CLASSIC ? SkinVariant.SLIM : SkinVariant.CLASSIC), "Remote result is converted pair, not raw base");
        require(result.draft().catalogOrigin().isEmpty() && preferences == 0, "Remote converted pair has no base reuse or scoped preference writes");
        byte[] bytes = result.draft().png().orElseThrow().bytes();flow.closeScreens();return bytes;
    }

    public byte[] retryRemote(Path base,boolean player) throws Exception {
        remotePng=Files.readAllBytes(base);flow.loadRemoteImport(player);drain();
        require(result!=null && conversionCalls==1,"Same admitted model accepts next explicit same-type remote source");
        require(flow.addSource().importContext().failure().isEmpty(),"Successful explicit retry clears previous typed mismatch");
        require(preferences==0,"Same-context retry no preference writes");
        byte[] png=result.draft().png().orElseThrow().bytes();flow.closeScreens();return png;
    }

    public CpmCallerHarness(CpmModelFileConversion adapter, byte[] bundledPng) throws Exception {
        externalPng=bundledPng.clone();
        this.adapter = new CpmModelFileConversion() {
            public Availability availability() { return adapter.availability(); }
            public AdmittedModel admit(java.io.InputStream source) throws Failure { requireWorkerAdmission();return adapter.admit(source); }
            public AdmittedModel admitFile(Path source) throws Failure { requireWorkerAdmission();return adapter.admitFile(source); }
            public Preparation prepare(AdmittedModel model,Raster base,long revision) throws Failure { requireWorkerConversion();conversionCalls++;return adapter.prepare(model,base,revision); }
        };
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bundledPng));
        var asset = new SkinAsset(UUID.fromString("00000000-0000-0000-0000-000000000002"), "Bundled fixture", hash,
                SkinVariant.CLASSIC, SkinSource.VANILLA_DEFAULT, Instant.EPOCH, Instant.EPOCH);
        var preparedAccount = new AccountState(AccountState.CURRENT_SCHEMA_VERSION, account, List.of(asset), List.of(), List.of(), Instant.EPOCH);
        var picker = new FilePicker() {
            private final FilePickerCoordinator coordinator = new FilePickerCoordinator(Runnable::run);
            public CompletableFuture<Optional<Path>> chooseDirectory() {
                return coordinator.chooseDirectory(() -> Optional.of(externalRoot));
            }
            public CompletableFuture<Optional<Path>> chooseSkinPng() {
                return coordinator.choose(() -> Optional.of(picks.remove()));
            }
            public CompletableFuture<Optional<Path>> chooseSkinImportFile(boolean allowCpmModel) {
                return allowCpmModel ? coordinator.chooseSkinImport(() -> Optional.of(picks.remove())) : chooseSkinPng();
            }
        };
        CatalogImportFlow.Context context = (CatalogImportFlow.Context) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{CatalogImportFlow.Context.class}, (p,m,a) -> switch(m.getName()) {
            case "captureImportScope" -> new CatalogImportFlow.ImportScope(account, 1, 1, 1);
            case "currentImportScope", "currentPublicationScope" -> true;
            case "captureEditorSaveScope" -> new LibraryEditorPort.EditorSaveScope(account, 1, () -> true);
            case "filePicker" -> picker;
            case "unwrap" -> {
                Throwable problem=(Throwable)a[0];
                while(problem.getCause()!=null)problem=problem.getCause();
                yield problem;
            }
            case "openExternalImport" -> { externalOpens++; yield null; }
            case "importOperations" -> imports;
            case "catalogMaterialization" -> materialization;
            case "publicImportFailureKey" -> {publicFailureMapped=true;yield "nclskins.error.png";}
            case "editorProfile" -> Optional.empty();
            case "editorOwnedCapes" -> List.of();
            case "submit" -> { submit(a); yield null; }
            case "modelFileConversion" -> Optional.of(this.adapter);
            case "publishCpmModel" -> throw new AssertionError("Inline-only caller must not request publication");
            case "cpmPresent" -> true;
            case "worker" -> (Executor) workers::add;
            case "onClient" -> { clients.add((Runnable)a[0]); yield null; }
            case "busy" -> busy;
            case "editor" -> null;
            case "addSourceRoot" -> false;
            case "disposed" -> false;
            case "beginScreenOperation" -> { busy = true; yield new ScreenOperationTicket(++generation); }
            case "captureScreenOperation" -> new ScreenOperationTicket(generation);
            case "current" -> ((ScreenOperationTicket)a[0]).generation() == generation;
            case "finishScreenOperation" -> { busy = false; yield null; }
            case "cancelScreenOperation" -> { generation++; busy = false; yield null; }
            case "textResolver" -> (TextResolver) UiMessage::key;
            case "viewportHeight" -> 480;
            case "preferredCapeMode" -> PreviewRenderer.CapeMode.CAPE;
            case "account" -> preparedAccount;
            case "uiPreferences" -> AccountUiPreferences.defaults(account).withSelectedAddSourceTab(AddSourceTab.FILE);
            case "pendingPresetName" -> null;
            case "createEditor" -> PresetEditorModel.open(preparedAccount, Optional.empty(), Optional.empty(), Optional.empty(), UiMessage::key, 480, PreviewRenderer.CapeMode.CAPE);
            case "applyPendingPresetName" -> a[0];
            case "acceptDraft" -> {
                require(lane.equals("client"), "Draft publication must run on client");
                result = (EditorDraftTransfer)a[0]; publications++; yield null;
            }
            case "rememberPreferredSkinVariant", "persistUiPreference", "addSourceTabSelected" -> { preferences++; yield null; }
            case "fileImportFailure" -> {
                Throwable failure = (Throwable)a[0];
                while (failure.getCause() != null) failure = failure.getCause();
                failureReason = failure instanceof CpmModelFileConversion.Failure typed ? typed.reason().name() : failure.getClass().getSimpleName();
                yield UiMessage.error("nclskins.error.png");
            }
            case "status" -> feedback;
            case "showFeedback" -> { feedback=(UiMessage)a[0];yield null; }
            case "publish", "diagnose", "clearRuntimeFocus" -> null;
            default -> throw new AssertionError("Unexpected caller effect " + m.getName());
        });
        flow = new CatalogImportFlow(context);
        flow.openEmptyCatalog(AccountUiPreferences.defaults(account).withSelectedAddSourceTab(AddSourceTab.FILE), SkinVariant.CLASSIC, SkinExtensionEnvironment.unknown(0), false);
    }
    public static String saveThroughRuntime(CpmModelFileConversion adapter, byte[] bundled,
            Path model, Path base, Path root, SkinVariant variant, String mode) throws Exception {
        return saveThroughRuntime(adapter, bundled, model, base, root, variant, mode, null);
    }

    public static String saveThroughRuntime(CpmModelFileConversion adapter, byte[] bundled,
            Path model, Path base, Path root, SkinVariant variant, String mode,
            PublicSkinImports dependencyImports) throws Exception {
        UUID initiating = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000009");
        var identity = new java.util.concurrent.atomic.AtomicReference<>(new GameSessionTokenSource.SessionIdentity(initiating, "Fixture"));
        GameSessionTokenSource tokens = mode.contains("automatic") ? new GameSessionTokenSource() {
            public SessionIdentity currentSession() { return identity.get(); }
            public <T,E extends Exception> T withAccessToken(TokenRequest<T,E> action) { throw new AssertionError("No credential read for automatic model import/Save"); }
        } : syntheticTokens(identity);
        var uploads = new ArrayList<byte[]>();
        var api = syntheticApi(identity, uploads, new ArrayList<>());
        var storage = new com.naocraftlab.skins.core.storage.NclSkinsStorage(root, new PngValidator(), java.time.Clock.systemUTC());
        var graph = com.naocraftlab.skins.runtime.composition.ClientCompositionRoot.operations(tokens, api, storage,
                (BundledSkinSource) modelType -> bundled.clone(), java.time.Clock.systemUTC(), null);
        var operations = graph.operations();
        PlayerAppearanceSink<Object> sink = new PlayerAppearanceSink<>() {
            public ApplyResult apply(SignedProfileResolver.ResolvedProfile<Object> profile) { return ApplyResult.UPDATED; }
        };
        ClientExecutor inlineClient = new ClientExecutor() { public boolean isClientThread() { return true; } public void execute(Runnable action) { action.run(); } };
        operations.attachCapeProviders(new CapeProviderCoordinator(tokens, graph.appearances(), graph.assets(), graph.textures(),
                sink, inlineClient, Runnable::run, new OptifineCapeReader(root.resolve("optifine-staging")),
                new SkinMcCapeReader(root.resolve("skinmc-staging")), operations::verifiedOfficialCapeUri), null);
        var queue = new ArrayDeque<Runnable>();
        var clientQueue = new ArrayDeque<Runnable>();
        boolean[] holdClient = {false};
        ClientExecutor client = new ClientExecutor() {
            public boolean isClientThread() { return currentLane.equals("client"); }
            public void execute(Runnable action) {
                Runnable admitted = () -> { String old=currentLane; currentLane="client"; try { action.run(); } finally { currentLane=old; } };
                if (holdClient[0]) clientQueue.add(admitted); else admitted.run();
            }
        };
        var picker = new ArrayDeque<Path>(); picker.add(model); picker.add(base);
        var dialogs = new FilePicker() {
            public CompletableFuture<Optional<Path>> chooseSkinPng() { return CompletableFuture.completedFuture(Optional.of(picker.remove())); }
            public CompletableFuture<Optional<Path>> chooseSkinImportFile(boolean allow) { return chooseSkinPng(); }
        };
        int[] nativeCalls = {0, 0};
        boolean[] localLinkFailure = {false};
        var preparedWires = new ArrayList<byte[]>();
        var published = new ArrayList<ClientSnapshot>();
        var publishedViews = new ArrayList<ViewSpec>();
        var publicationClock = new java.util.concurrent.atomic.AtomicLong();
        CpmModelFileConversion observed = new CpmModelFileConversion.DependencyAdmission() {
            public Availability availability() { return adapter.availability(); }
            public Admission admit(java.io.InputStream input, Map<String, byte[]> dependencies) throws Failure {
                requireWorkerAdmission(); nativeCalls[0]++;
                return adapter instanceof DependencyAdmission dependency ? dependency.admit(input, dependencies) : new Ready(adapter.admit(input));
            }
            public AdmittedModel admit(java.io.InputStream input) throws Failure { requireWorkerAdmission(); nativeCalls[0]++; return adapter.admit(input); }
            public AdmittedModel admitFile(Path input) throws Failure { requireWorkerAdmission(); nativeCalls[0]++; return adapter.admitFile(input); }
            public Preparation prepare(AdmittedModel input, Raster baseRaster, long revision) throws Failure {
                requireWorkerConversion(); nativeCalls[1]++;
                Preparation prepared = adapter.prepare(input, baseRaster, revision);
                if (dependencyImports != null) {
                    try { CpmDependencyCallerProbe.nativeFidelity(input); }
                    catch (Exception failure) { throw new AssertionError("Acquired native template fidelity", failure); }
                }
                if (prepared instanceof Publication publication) {
                    require(publication.revision() == revision && publication.variant() == baseRaster.variant(), "Native continuation retains caller revision and channel");
                    return observe(publication, 0);
                }
                return prepared;
            }
            private Publication observe(Publication publication, int step) {
                preparedWires.add(publication.wire());
                return new Publication() {
                    public SkinModel variant() { return publication.variant(); }
                    public long revision() { return publication.revision(); }
                    public byte[] wire() { return publication.wire(); }
                    public Preparation link(String id) throws Failure {
                        assertPublicationBusy(published.get(published.size() - 1), publishedViews.get(publishedViews.size() - 1));
                        if (step == 1 && mode.equals("publication-multi-retry") && !localLinkFailure[0]) {
                            localLinkFailure[0] = true; throw new Failure(Reason.CONVERSION_FAILED);
                        }
                        Preparation linked = publication.link(id);
                        return linked instanceof Publication next ? observe(next, step + 1) : linked;
                    }
                    public void close() { publication.close(); }
                };
            }
        };
        ClientOperations runtimeOperations = operations;
        if (mode.contains("automatic")) {
            ClientOperations local = runtimeOperations;
            runtimeOperations = (ClientOperations) Proxy.newProxyInstance(ClientOperations.class.getClassLoader(), new Class<?>[]{ClientOperations.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("uiPreferences")) return AccountUiPreferences.defaults(initiating).withPreferredSkinVariant(variant);
                if (method.getName().equals("loadUrlSkin") || method.getName().equals("loadPlayerSkin")) throw new AssertionError("Unconfigured ordinary image/profile terminal");
                try { return method.invoke(local, arguments); }
                catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
        if (mode.startsWith("url") || (mode.equals("publication-url") || mode.equals("publication-multi-url"))) {
            var publicImports = com.naocraftlab.skins.core.api.CpmUrlIngressFixture.imports(root.resolve("url-cache"), Files.readAllBytes(model));
            ClientOperations local = runtimeOperations;
            runtimeOperations = (ClientOperations) Proxy.newProxyInstance(ClientOperations.class.getClassLoader(),
                    new Class<?>[]{ClientOperations.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("acquireUrl"))
                            return publicImports.acquireUrl((String) arguments[0], Optional.of(observed), CpmModelFileConversion.Reason.CPM_UNAVAILABLE);
                        try { return method.invoke(local, arguments); }
                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
        if (dependencyImports != null) {
            ClientOperations local = runtimeOperations;
            runtimeOperations = (ClientOperations) Proxy.newProxyInstance(ClientOperations.class.getClassLoader(),
                    new Class<?>[]{ClientOperations.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("acquireUrl"))
                            return dependencyImports.acquireUrl((String) arguments[0], Optional.of(observed), CpmModelFileConversion.Reason.CPM_UNAVAILABLE);
                        if (method.getName().equals("acquireFile"))
                            return dependencyImports.acquireFile((Path) arguments[0], Optional.of(observed), CpmModelFileConversion.Reason.CPM_UNAVAILABLE);
                        try { return method.invoke(local, arguments); }
                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
        var runtime = new ClientRuntime(new DefaultRuntimeServices(), runtimeOperations, client, dialogs,
                action -> {
                    if (mode.startsWith("publication") && !published.isEmpty() && published.get(published.size() - 1).addSource().isPresent()
                            && published.get(published.size() - 1).editor().isEmpty()
                            && published.get(published.size() - 1).status().key().equals("nclskins.add.cpm_publication_loading"))
                        assertPublicationBusy(published.get(published.size() - 1), publishedViews.get(publishedViews.size() - 1));
                    queue.add(() -> { String old=currentLane; currentLane="worker"; try { action.run(); } finally { currentLane=old; } });
                },
                UiMessage::key, Optional.empty(), Optional.empty(), com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
        runtime.useSkinExtensionEnvironmentSource(new SkinExtensionEnvironmentSource() {
            public Snapshot snapshot() { return new Snapshot(1, Map.of(), true); }
            public Optional<CpmModelFileConversion> modelFileConversion() { return Optional.of(observed); }
        });
        var pasteEffects = new ArrayList<String>();
        var pasteUploads = new ArrayList<byte[]>();
        int[] credentialReads = {0};
        if (mode.startsWith("publication")) runtime.importClock(publicationClock::get);
        boolean[] publicationPending = {false};
        runtime.subscribe(snapshot -> {
            var view = runtime.view(854, 480, 0, 0);
            published.add(snapshot); publishedViews.add(view);
            require(view.widgets().stream().noneMatch(widget -> widget.id().startsWith("add.cpm.publication.")), "No published Paste confirmation controls");
            if (snapshot.status().key().equals("nclskins.add.cpm_publication_loading") && snapshot.addSource().isPresent() && snapshot.editor().isEmpty()) {
                assertPublicationBusy(snapshot, view);
                publicationPending[0] = true;
            } else if (publicationPending[0]) {
                require(snapshot.editor().isPresent() || snapshot.addSource().isEmpty()
                        || snapshot.status().severity() == UiMessage.Severity.ERROR, "Publication batch cannot publish a prompt or unlocked gap between wires");
                publicationPending[0] = false;
            }
        });
        if (mode.startsWith("publication")) {
            GameSessionTokenSource publicationTokens = new GameSessionTokenSource() {
                public SessionIdentity currentSession() { return mode.equals("publication-missing") ? null : identity.get(); }
                public <T,E extends Exception> T withAccessToken(TokenRequest<T,E> action) throws E {
                    assertPublicationBusy(published.get(published.size() - 1), publishedViews.get(publishedViews.size() - 1));
                    credentialReads[0]++;
                    return action.execute("synthetic-disposable-not-a-credential");
                }
            };
            runtime.useCpmModelPublication(com.naocraftlab.skins.runtime.composition.ClientCompositionRoot.cpmPublication(
                    publicationTokens, (method, uri, headers, body, timeout, maximum) -> {
                requireWorkerConversion();
                assertPublicationBusy(published.get(published.size() - 1), publishedViews.get(publishedViews.size() - 1));
                require(timeout.toMillis() > 0 && timeout.toMillis() <= 30000, "Publication terminal has finite timeout");
                if (mode.startsWith("publication-multi") && !pasteUploads.isEmpty())
                    require(timeout.toMillis() <= 20000, "Later actual native wire receives only remainder after first forty seconds");
                if (uri.getHost().equals("sessionserver.mojang.com")) {
                    require(uri.toString().equals("https://sessionserver.mojang.com/session/minecraft/join") && method.equals("POST"), "Fixed session proof terminal");
                    require(pasteEffects.subList(pasteUploads.size() * 5, pasteEffects.size()).equals(List.of("connect")), "Proof follows connect before each model upload");
                    pasteEffects.add("join");
                    return new com.naocraftlab.skins.core.api.PublicHttpsImageFetcher.Response(204, new byte[0], Map.of());
                }
                require(uri.getHost().equals("paste.tom5454.com") && uri.getScheme().equals("https"), "Fixed Paste terminal only");
                require(!headers.containsKey("Authorization") && !headers.containsKey("Cookie"), "No account credential at Paste");
                String step = uri.getPath().substring(5);
                pasteEffects.add(step);
                if (step.equals("upload")) {
                    require(pasteEffects.subList(pasteUploads.size() * 5, pasteEffects.size()).equals(List.of("connect", "join", "session", "list", "upload")), "Both proofs and quota check precede each import-authorized upload");
                    require(method.equals("POST") && headers.get("File-Name").equals(
                            mode.equals("publication-url") || mode.equals("publication-multi-url") ? "renamed.PNG" : model.getFileName().toString())
                            && headers.get("Content-Length").equals(Integer.toString(body.length)), "Exact model upload metadata");
                    byte[] wire = Base64.getDecoder().decode(body);
                    require(preparedWires.stream().anyMatch(prepared -> Arrays.equals(wire, prepared)), "Upload contains exact real native preparation wire, not container");
                    require(pasteUploads.stream().noneMatch(sent -> Arrays.equals(sent, wire)), "Confirmed wire is not uploaded twice");
                    pasteUploads.add(wire);
                    if (mode.startsWith("publication-multi") && pasteUploads.size() == 1)
                        publicationClock.set(TimeUnit.SECONDS.toNanos(40));
                } else require(method.equals("GET") && body.length == 0, "No model bytes in proof or quota requests");
                if (!step.equals("connect")) require("synthetic-review-session-0".equals(headers.get("Session")), "Scoped Paste session");
                String response = switch (step) {
                    case "connect" -> "{\"id\":\"synthetic-review-session-0\",\"key\":\"AAECAwQFBgcICQoLDA0ODw==\"}";
                    case "session" -> "{}";
                    case "list" -> "{\"files\":[],\"maxSize\":1048576,\"maxFiles\":1}";
                    case "upload" -> (mode.equals("publication-uncertain") || mode.equals("publication-multi-uncertain") && pasteUploads.size() == 2) ? "{}"
                            : "{\"id\":\"" + (mode.equals("publication-long-id") ? "x".repeat(300) : mode.startsWith("publication-multi") ? "step" + pasteUploads.size() : "synthetic") + "\"}";
                    default -> throw new AssertionError("Unexpected publication terminal");
                };
                return new com.naocraftlab.skins.core.api.PublicHttpsImageFetcher.Response(200,
                        response.getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of());
            }, publicationClock::get));
        }
        runtime.reopen(ScreenDestination.SKIN_IMPORT); drain(queue, clientQueue);
        storage.updateAccount(other, value -> value);
        storage.updateAccount(other, value -> value);
        byte[] otherState=Files.readAllBytes(storage.layout().accountState(other));
        byte[] otherBackup=Files.readAllBytes(storage.layout().accountBackup(other));
        if (mode.startsWith("url") || (mode.equals("publication-url") || mode.equals("publication-multi-url"))) {
            picker.remove();
            runtime.dispatchText("add.url.input", "https://example.com/renamed.PNG");
            runtime.dispatchWidget("add.url.load");
        } else runtime.dispatchWidget("add.file.choose");
        if (mode.startsWith("cancel-acquire-")) {
            retireNative(runtime, identity, mode.substring("cancel-acquire-".length()));
            drain(queue, clientQueue);
            require(nativeCalls[0] == 0 && nativeCalls[1] == 0, "Invalid queued native acquisition never creates SDK state");
            runtime.close(); drain(queue, clientQueue); return null;
        }
        if (mode.contains("automatic-stale")) {
            queue.remove().run();
            require(nativeCalls[0] == 1 && nativeCalls[1] == 0 && !queue.isEmpty(), "Native acquisition queues automatic conversion separately");
            holdClient[0] = true;
            queue.remove().run();
            require(nativeCalls[0] == 1 && nativeCalls[1] > 0 && !clientQueue.isEmpty(), "Automatic native conversion completed before held publication");
            runtime.closeScreen(); holdClient[0] = false; drain(queue,clientQueue);
            require(runtime.snapshot().editor().isEmpty() && uploads.isEmpty()
                    && storage.loadOrCreateAccount(initiating).presets().isEmpty(), "Closed automatic import cannot resurrect draft or Save");
            runtime.close(); drain(queue,clientQueue); return null;
        }
        if (!mode.startsWith("publication")) drain(queue, clientQueue);
        if (dependencyImports != null) {
            require(runtime.snapshot().editor().isEmpty() && uploads.isEmpty(), "Dependency discovery cannot publish or upload");
            String confirm = runtime.view(854, 480, 0, 0).widgets().stream().map(w -> w.id())
                    .filter(id -> id.startsWith("add.cpm.dependency.confirm.")).findFirst().orElseThrow();
            Files.delete(model);
            runtime.dispatchWidget(confirm); runtime.dispatchWidget(confirm);
            drain(queue, clientQueue);
            require(!Files.exists(model), "Native dependency replay uses frozen source after original deletion");
        }
        if (mode.startsWith("publication")) {
            int steps = 0;
            while (preparedWires.isEmpty() || !runtime.snapshot().status().key().equals("nclskins.add.cpm_publication_loading")) {
                require(++steps < 30 && !queue.isEmpty(), "Native acquisition reaches automatic publication admission");
                queue.remove().run();
                while (!clientQueue.isEmpty()) clientQueue.remove().run();
            }
            require(nativeCalls[0] == 1 && nativeCalls[1] == 1, "Raw native input reaches preparation before automatic publication");
            assertPublicationBusy(published.get(published.size() - 1), publishedViews.get(publishedViews.size() - 1));
            require(pasteEffects.isEmpty() && credentialReads[0] == 0 && !queue.isEmpty(), "Published outer busy precedes first queued credential/proof/upload effect");
            runtime.dispatchWidget("add.cpm.publication.confirm.1");
            runtime.dispatchWidget("add.cpm.publication.cancel.1");
            runtime.dispatchWidget("add.cancel");
            require(runtime.snapshot().busy() && pasteEffects.isEmpty(), "Removed Paste actions and voluntary Cancel cannot disturb automatic publication");
            require(uploads.isEmpty() && storage.loadOrCreateAccount(initiating).presets().isEmpty(), "Preparation has no account upload or personal Save");
            if (mode.equals("publication-cancel")) {
                runtime.closeScreen(); drain(queue, clientQueue);
                require(pasteEffects.isEmpty() && credentialReads[0] == 0 && runtime.snapshot().editor().isEmpty(), "Forced retirement before first effect has zero remote effects");
                require(storage.loadOrCreateAccount(initiating).presets().isEmpty(), "Retired native preparation never Saves");
                System.out.println("PASS native publication before-first-effect retirement " + variant);
                runtime.close(); drain(queue, clientQueue); return null;
            }
            if (mode.equals("publication-multi-cancel") || mode.equals("publication-multi-stale")) {
                steps = 0;
                while (preparedWires.size() < 2) {
                    require(++steps < 30 && !queue.isEmpty(), "Confirmed first native wire produces second native continuation");
                    queue.remove().run();
                    while (!clientQueue.isEmpty()) clientQueue.remove().run();
                }
                require(pasteUploads.size() == 1 && credentialReads[0] == 1 && !queue.isEmpty(), "Second distinct wire is queued after confirmed first without another grant");
                assertPublicationBusy(published.get(published.size() - 1), publishedViews.get(publishedViews.size() - 1));
                if (mode.equals("publication-multi-cancel")) runtime.closeScreen();
                else {
                    publicationClock.set(TimeUnit.SECONDS.toNanos(60) - 1);
                    runtime.tick();
                    require(runtime.snapshot().busy(), "Native second wire remains pending immediately before original deadline");
                    publicationClock.incrementAndGet(); runtime.tick();
                    require(!runtime.snapshot().busy() && runtime.snapshot().status().key().equals("nclskins.add.cpm_publication_timeout"),
                            "Confirmed first plus queued second expires NOT_SENT at original sixty seconds, not a reset interval");
                    require(runtime.snapshot().addSource().orElseThrow().importContext().purpose() == AddSourceModel.Purpose.CREATE_PRESET,
                            "Native batch deadline restores outer ordinary Import error");
                    System.out.println("PASS native first-40s queued-second original-60s NOT_SENT " + variant);
                }
                drain(queue, clientQueue);
                require(pasteUploads.size() == 1 && credentialReads[0] == 1 && runtime.snapshot().editor().isEmpty(), "Forced retirement before second effect preserves first and forbids second upload/Editor");
                require(storage.loadOrCreateAccount(initiating).presets().isEmpty(), "Retired multi-wire preparation has no partial personal Save");
                System.out.println("PASS native publication before-second-effect " + mode + " " + variant);
                runtime.close(); drain(queue, clientQueue); return null;
            }
            drain(queue, clientQueue);
            if (Set.of("publication-missing", "publication-uncertain", "publication-long-id").contains(mode)) {
                require(runtime.snapshot().editor().isEmpty() && !runtime.snapshot().busy(), "Publication failure preserves Import without a false Editor or busy ticket");
                require(runtime.snapshot().addSource().orElseThrow().importContext().purpose() == AddSourceModel.Purpose.CREATE_PRESET,
                        "Failure restores ordinary outer Import without Retry");
                if (mode.equals("publication-missing"))
                    require(pasteEffects.isEmpty() && credentialReads[0] == 0, "Missing current session has zero connect/credential/upload effects");
                else {
                    require(pasteUploads.size() == 1, "One upload before uncertain/native-link failure");
                    picker.clear(); picker.add(model); runtime.dispatchWidget("add.file.choose"); drain(queue, clientQueue);
                    require(pasteUploads.size() == 1 && credentialReads[0] == 1 && pasteEffects.size() == 5,
                            "Explicit same-wire recovery cannot resend or authenticate after uncertain/confirmed result");
                }
                require(runtime.snapshot().editor().isEmpty() && storage.loadOrCreateAccount(initiating).presets().isEmpty(), "Failure never creates or saves partial carrier");
                runtime.close(); drain(queue, clientQueue); return null;
            }
            if (mode.equals("publication-multi-uncertain") || mode.equals("publication-multi-retry")) {
                require(pasteUploads.size() == 2 && runtime.snapshot().editor().isEmpty(), "Second result failure retains prior confirmed wire without partial draft");
                require(runtime.snapshot().addSource().orElseThrow().importContext().purpose() == AddSourceModel.Purpose.CREATE_PRESET
                        && !runtime.snapshot().busy(), "Later failure restores outer Import without Retry");
                picker.clear(); picker.add(model); runtime.dispatchWidget("add.file.choose"); drain(queue, clientQueue);
                require(pasteUploads.size() == 2 && credentialReads[0] == 2, "Explicit same-wire recovery never resends either confirmed wire");
                if (mode.equals("publication-multi-uncertain")) {
                    require(runtime.snapshot().editor().isEmpty(), "Uncertain second wire never yields partial Editor");
                    runtime.close(); drain(queue, clientQueue); return null;
                }
            }
            int expectedPublications = mode.equals("publication-multi-three") ? 3 : mode.startsWith("publication-multi") ? 2 : 1;
            require(pasteUploads.size() == expectedPublications && credentialReads[0] == expectedPublications && nativeCalls[0] == (mode.equals("publication-multi-retry") ? 2 : 1) && nativeCalls[1] == (mode.equals("publication-multi-retry") ? 2 : 1), "Explicit import handles all necessary unique native wires and bounded explicit local recovery");
            require(runtime.snapshot().editor().map(PresetEditorModel::convertedCreation).orElse(false)
                    && runtime.view(854,480,0,0).previews().get(0).requiresLoadedSkin(), "Automatically published native linked carrier opens same owned-preview Editor");
            require(picker.size() == (mode.equals("publication-multi-retry") ? 0 : 1), "Dense owned atlas does not require unrelated base selection");
            System.out.println("PASS native automatic publication " + mode + " " + variant + " unique-wires=" + expectedPublications);
        }
        if (mode.equals("url-error")) {
            require(runtime.snapshot().editor().isEmpty() && !runtime.snapshot().busy(), "Actual malformed native URL settles without false Editor");
            require(storage.loadOrCreateAccount(initiating).presets().isEmpty(), "URL native failure never saves a personal result");
            runtime.close(); drain(queue, clientQueue); return null;
        }
        if (!mode.equals("url-png")) require(runtime.snapshot().editor().map(PresetEditorModel::convertedCreation).orElseGet(() ->
                runtime.snapshot().addSource().orElseThrow().importContext().purpose() == AddSourceModel.Purpose.CPM_BASE),
                "Actual native input must reach its base chooser or native converted draft before the base is selected: "
                        + runtime.snapshot().status().key() + " " + runtime.snapshot().addSource().map(s -> s.importContext().failure()));
        if (mode.equals("borrowed")) require(runtime.snapshot().editor().isEmpty()
                && runtime.snapshot().addSource().orElseThrow().importContext().purpose() == AddSourceModel.Purpose.CPM_BASE,
                "Actual borrowed/cape-only/icon input requires existing base chooser");
        if (mode.contains("automatic")) {
            require(runtime.snapshot().editor().isPresent(), "Self-contained native owned SKIN must bypass mandatory base chooser");
            require(picker.size() == 1 && runtime.view(854,480,0,0).previews().get(0).requiresLoadedSkin(),
                    "Automatic import never picks a base and uses the actual common owned-preview predicate");
        }
        if (runtime.snapshot().editor().isEmpty()) {
            runtime.dispatchWidget("add.tab.file"); runtime.dispatchWidget("add.file.choose");
            if (mode.equals("blocked")) {
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var problem = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                com.naocraftlab.skins.buildlogic.probe.CpmModelConversionProbe.conversionGate(() -> {
                    entered.countDown();
                    try { require(release.await(5, TimeUnit.SECONDS), "Owned blocking fixture released within its finite bound"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                });
                holdClient[0] = true;
                Thread worker = new Thread(() -> { try { queue.remove().run(); } catch (Throwable failure) { problem.set(failure); } }, "synthetic-native-worker");
                Thread closer = new Thread(() -> { currentLane = "client"; runtime.closeScreen(); }, "synthetic-client-close");
                boolean responsive = false;
                try {
                    worker.start();
                    require(entered.await(2, TimeUnit.SECONDS), "Actual pinned Exporter.convert reached while native handle monitor is held");
                    closer.start(); closer.join(500);
                    responsive = !closer.isAlive();
                } finally {
                    release.countDown(); worker.join(2000); closer.join(2000);
                    com.naocraftlab.skins.buildlogic.probe.CpmModelConversionProbe.conversionGate(() -> {});
                }
                require(responsive && !worker.isAlive() && !closer.isAlive() && problem.get() == null,
                        "Client close returns before blocked actual native conversion and all owned threads terminate");
                holdClient[0] = false; drain(queue, clientQueue);
                require(runtime.snapshot().editor().isEmpty() && uploads.isEmpty(), "Blocked late conversion cannot publish Editor or effect");
                runtime.close(); drain(queue, clientQueue); return null;
            }
            if (mode.startsWith("cancel-convert-") || mode.startsWith("cancel-complete-")) {
                boolean completed = mode.startsWith("cancel-complete-");
                if (completed) {
                    holdClient[0] = true;
                    queue.remove().run();
                    require(nativeCalls[1] == 1 && !clientQueue.isEmpty(), "Actual native conversion completed before held client publication");
                    holdClient[0] = false;
                }
                retireNative(runtime, identity, mode.substring(completed ? "cancel-complete-".length() : "cancel-convert-".length()));
                drain(queue, clientQueue);
                require(nativeCalls[0] == 1 && nativeCalls[1] == (completed ? 1 : 0), "Cancelled queued native conversion never executes; completed old result is discarded");
                require(runtime.snapshot().editor().isEmpty(), "No stale native Editor after lifecycle retirement");
                require(storage.loadOrCreateAccount(initiating).presets().isEmpty()
                        && storage.loadOrCreateAccount(other).presets().isEmpty(), "Native cancellation has no personal Save effects");
                runtime.close(); drain(queue, clientQueue); return null;
            }
            drain(queue, clientQueue);
        }
        if (mode.contains("pairs")) {
            var paired = runtime.snapshot().editor().orElseThrow();
            require(paired.variantAvailable(SkinVariant.CLASSIC) && paired.variantAvailable(SkinVariant.SLIM), "Actual UNKNOWN project exposes two native prepared pairs in one draft");
            runtime.dispatchWidget("editor.model_choice.classic");
            byte[] classic = runtime.snapshot().editor().orElseThrow().png().orElseThrow().bytes();
            runtime.dispatchWidget("editor.model_choice.slim");
            byte[] slim = runtime.snapshot().editor().orElseThrow().png().orElseThrow().bytes();
            require(!Arrays.equals(classic, slim), "Actual pair switching changes prepared channel bytes");
            runtime.dispatchWidget(variant == SkinVariant.SLIM ? "editor.model_choice.slim" : "editor.model_choice.classic");
            var chosen = runtime.snapshot().editor().orElseThrow();
            require(Arrays.equals(chosen.png().orElseThrow().bytes(), variant == SkinVariant.SLIM ? slim : classic)
                    && chosen.saveRequest().exactCarrierReuse(), "Selected native pair preserves exact bytes and lossless Save policy");
        }
        if (mode.equals("retired-fallback")) {
            require(runtime.snapshot().editor().isPresent(), "Native conversion opens retained editor");
            require(runtime.view(240, 240, 0, 0).widget("editor.cpm.fallback.choose").isEmpty(), "Removed editor base action has no narrow-layout ghost");
            runtime.dispatchText("editor.name", "Retained native draft");
            var retained = runtime.snapshot().editor().orElseThrow();
            int conversions = nativeCalls[1], picks = picker.size(), publications = pasteUploads.size();
            runtime.dispatchWidget("editor.cpm.fallback.choose");
            require(runtime.snapshot().editor().orElseThrow() == retained && nativeCalls[1] == conversions
                    && picker.size() == picks && pasteUploads.size() == publications,
                    "Retired internal action cannot open picker, reconvert, publish or replace retained draft");
        }
        var draft = runtime.snapshot().editor().orElseThrow(() -> new AssertionError("Actual runtime converted Editor required: " + runtime.snapshot().status().key() + " " + runtime.snapshot().addSource().map(s -> s.importContext().failure())));
        require(draft.variant()==variant && draft.originalPresetId().isEmpty() && draft.catalogOrigin().isEmpty(), "Actual runtime selected creation pair has no base origin");
        byte[] selected=draft.png().orElseThrow().bytes();
        if (mode.equals("preview-dispatch")) {
            var beforePreview = storage.loadOrCreateAccount(initiating);
            com.naocraftlab.skins.buildlogic.probe.CpmOwnedPreviewDispatchProbe.observe(runtime, bundled, Files.readAllBytes(base), variant);
            require(nativeCalls[0] == 1 && nativeCalls[1] > 0,
                    "Preview proof follows production native acquisition/conversion");
            require(beforePreview.equals(storage.loadOrCreateAccount(initiating)) && uploads.isEmpty(),
                    "Screen dispatch cannot Save or change account assignment");
            runtime.close(); drain(queue, clientQueue); return null;
        }
        if (mode.equals("collision")) {
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(selected));
            boolean changed = false;
            for (int y = 0; y < 64 && !changed; y++) for (int x = 0; x < 64 && !changed; x++) {
                int pixel = image.getRGB(x, y);
                if ((pixel >>> 24) == 0) { image.setRGB(x, y, pixel ^ 1); changed = true; }
            }
            require(changed, "Native carrier has significant transparent RGB discriminator");
            var bytes = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "png", bytes);
            byte[] otherCarrier = bytes.toByteArray();
            require(!Arrays.equals(selected, otherCarrier)
                    && new PngValidator().renderSha256(selected).equals(new PngValidator().renderSha256(otherCarrier)),
                    "Distinct native carrier collides only in old ordinary render projection");
            var storagePort = new com.naocraftlab.skins.core.storage.LibraryStorageAdapter(storage);
            new com.naocraftlab.skins.core.service.LibraryService(storagePort, storagePort, java.time.Clock.systemUTC())
                    .savePresetWithPersonalSkin(initiating, Optional.empty(), "Existing", "Existing", variant,
                            PersonalSkinSource.FILE, otherCarrier, null);
        }
        var preview = runtime.view(854, 480, 0, 0).previews().get(0);
        var displaySession = new com.naocraftlab.skins.client.EditorPreviewSession();
        displaySession.beginBody(runtime.previewBodySource(preview), false, false);
        com.naocraftlab.skins.client.PreviewRenderer<Object> displayRenderer = new com.naocraftlab.skins.client.PreviewRenderer<>() {
            public void render(Object graphics, PreviewRequest request) { throw new AssertionError(); }
            public com.naocraftlab.skins.client.PreviewModelFrames.Body body() { return displaySession.body(); }
        };
        displaySession.observeBody(runtime.observePreviewBody(preview, displayRenderer::displayKind));
        displaySession.ordinaryDisplay(() -> true).accept(new com.naocraftlab.skins.client.PreviewModelFrames.Body(
                com.naocraftlab.skins.client.PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        runtime.dispatchWidget("editor.outer_layer.body", true);
        require(!runtime.snapshot().editor().orElseThrow().preview().outerLayerVisibility().equals(OuterLayerVisibility.allVisible()), "Preview masks differ while Save/upload must retain carrier");
        var before=inventory(root);
        var accountBefore=storage.loadOrCreateAccount(initiating);
        int presetsBefore=accountBefore.presets().size();
        int blobsBefore=inventory(storage.layout().assetsSha256()).size();
        require(uploads.isEmpty(), "Conversion and Editor are not Apply");
        int conversionsBeforeSave = nativeCalls[1];
        runtime.dispatchText("editor.name", "Selected fixture");
        Path accountPath=storage.layout().accountState(initiating), retained=root.resolve("retained-account.json");
        if(mode.equals("retry")) { Files.move(accountPath,retained); Files.createDirectory(accountPath); }
        runtime.dispatchWidget("editor.save"); runtime.dispatchWidget("editor.save");
        require(runtime.snapshot().busy(), "Actual Save is queued before worker admission");
        if(mode.equals("swap")) identity.set(new GameSessionTokenSource.SessionIdentity(other,"Other"));
        if(mode.equals("callback")) {
            holdClient[0]=true;
            while(!queue.isEmpty())queue.remove().run();
            require(!clientQueue.isEmpty(), "Actual successful Save callback held separately");
            identity.set(new GameSessionTokenSource.SessionIdentity(other,"Other"));
            holdClient[0]=false;
        }
        drain(queue,clientQueue);
        require(Arrays.equals(otherState,Files.readAllBytes(storage.layout().accountState(other)))
                && Arrays.equals(otherBackup,Files.readAllBytes(storage.layout().accountBackup(other))), "Both B account and backup remain unchanged");
        if(mode.equals("swap")) {
            require(before.equals(inventory(root)), "R17 no-tick Save leaves both accounts, assets, blobs and backups unchanged");
            require(storage.loadOrCreateAccount(other).presets().isEmpty(), "R17 no current-account fallback");
            require(runtime.snapshot().editor().isEmpty() && runtime.snapshot().addSource().isEmpty(), "R17 no stale draft Retry or parent reopen");
            require(uploads.isEmpty(), "R17 no upload"); runtime.close(); return null;
        }
        if(mode.equals("callback")) {
            require(runtime.snapshot().editor().isEmpty() && runtime.snapshot().addSource().isEmpty(), "Stale success callback cannot reopen parent or retain draft");
            require(runtime.snapshot().account().orElseThrow().presets().size()==presetsBefore, "Stale success never publishes saved account into B");
            require(uploads.isEmpty(), "Stale success never Apply");runtime.close();return null;
        }
        if(mode.equals("retry")) {
            require(runtime.snapshot().editor().isPresent() && !runtime.snapshot().editor().orElseThrow().busy(), "Valid A I/O failure retains complete Retry draft");
            require(Arrays.equals(selected,runtime.snapshot().editor().orElseThrow().png().orElseThrow().bytes()), "Retry retains selected bytes");
            Files.delete(accountPath);Files.move(retained,accountPath);
            runtime.dispatchWidget("editor.save");drain(queue,clientQueue);
        }
        var state=storage.loadOrCreateAccount(initiating);
        int newPairs=1;
        for (var entry : accountBefore.personalSkins())
            if (Arrays.equals(selected, storage.readAsset(entry.sha256()))) newPairs=0;
        require(state.presets().size()==presetsBefore+1 && state.personalSkins().size()==accountBefore.personalSkins().size()+newPairs, "Exactly one selected inactive personal pair");
        require(state.skinAssets().size()==accountBefore.skinAssets().size()+newPairs
                && inventory(storage.layout().assetsSha256()).size()==blobsBefore+newPairs, "Only selected asset/blob, no model/base/unselected candidate");
        if(newPairs==0)require(state.personalSkins().stream().map(e -> e.sha256()+":"+e.displayName()).toList()
                .equals(accountBefore.personalSkins().stream().map(e -> e.sha256()+":"+e.displayName()).toList()), "Reuse preserves original card name/order");
        var saved=state.presets().get(state.presets().size()-1);
        var asset=state.skinAssets().stream().filter(a->a.id().equals(saved.skin().assetId())).findFirst().orElseThrow();
        require(asset.variant()==variant && Arrays.equals(selected,storage.readAsset(asset.sha256())), "Actual EditorFlow application Save preserves selected pair");
        if (mode.equals("url-png")) require(state.personalSkins().stream().anyMatch(entry -> entry.sha256().equals(asset.sha256())
                && entry.source() == PersonalSkinSource.URL), "Ordinary URL retains URL source through real Editor/Save");
        else require(state.personalSkins().stream().anyMatch(entry -> entry.sha256().equals(asset.sha256())
                && entry.source() == PersonalSkinSource.FILE), "Native converted model/project retains FILE source regardless of acquisition route");
        require(uploads.isEmpty(), "Zero upload before separately explicit test Apply");
        require(Arrays.equals(otherState,Files.readAllBytes(storage.layout().accountState(other))), "Same A never writes B");
        runtime.close();
        drain(queue, clientQueue);
        if (mode.startsWith("publication")) {
            int count = mode.equals("publication-multi-three") ? 3 : mode.startsWith("publication-multi") ? 2 : 1;
            require(pasteEffects.size() == count * 5 && pasteUploads.size() == count && credentialReads[0] == count
                    && nativeCalls[1] == conversionsBeforeSave, "Editor Save/close never repeats native preparation, credential proof or upload");
            Files.write(root.resolve("publication.wire"), pasteUploads.get(0));
            for (int i = 0; i < pasteUploads.size(); i++) Files.write(root.resolve("publication-" + i + ".wire"), pasteUploads.get(i));
        }
        System.out.println("PASS actual Runtime→EditorFlow→DefaultClientOperations→storage " + mode + " " + variant);
        return asset.sha256();
    }

    private static void assertPublicationBusy(ClientSnapshot snapshot, ViewSpec view) {
        var add = snapshot.addSource().orElseThrow();
        require(snapshot.busy() && snapshot.editor().isEmpty()
                && snapshot.status().key().equals("nclskins.add.cpm_publication_loading"), "Last subscribed frame is already publication busy before effect/enqueue");
        require(add.importContext().purpose() == AddSourceModel.Purpose.CREATE_PRESET && add.selectedTab() == AddSourceTab.FILE
                && add.importContext().requiredVariant().isEmpty(), "Publication blocks original outer ordinary Import, not a base chooser");
        require(view.title().key().equals("nclskins.add_source.title") && view.widget("add.catalog.filter").isEmpty(), "Outer Import has no chooser title or lock");
        require(view.widgets().stream().noneMatch(ViewSpec.Widget::enabled)
                && view.tabGroups().stream().flatMap(group -> group.tabs().stream()).noneMatch(ViewSpec.Tab::enabled), "Published controls, tabs and Cancel disabled");
        require(view.texts().stream().filter(text -> text.message().key().equals("nclskins.add.cpm_publication_loading")).count() == 1
                && view.widgets().stream().noneMatch(widget -> widget.id().startsWith("add.cpm.publication.")), "One loading status and no Paste prompt");
    }

    private static void retireNative(ClientRuntime runtime,
            java.util.concurrent.atomic.AtomicReference<GameSessionTokenSource.SessionIdentity> identity, String event) {
        require(runtime.snapshot().busy(), "Retirement controls begin with the native operation pending");
        switch (event) {
            case "close" -> runtime.closeScreen();
            case "account" -> {
                identity.set(new GameSessionTokenSource.SessionIdentity(UUID.fromString("00000000-0000-0000-0000-000000000009"), "Other"));
                runtime.tick();
            }
            case "reload" -> runtime.resourcesReloaded();
            case "world" -> runtime.importWorldChanged();
            case "back" -> runtime.dispatchWidget("add.cancel");
            default -> throw new AssertionError("Unknown controlled retirement");
        }
    }

    private static void drain(ArrayDeque<Runnable> workers, ArrayDeque<Runnable> clients) {
        int steps=0;
        while(!workers.isEmpty() || !clients.isEmpty()) {
            require(++steps<300,"Bounded actual runtime queues");
            if(!workers.isEmpty())workers.remove().run();
            if(!clients.isEmpty())clients.remove().run();
        }
    }

    private static Map<String,String> inventory(Path root) throws Exception {
        var result=new TreeMap<String,String>();
        try(var files=Files.walk(root)) {
            for(Path path:files.filter(Files::isRegularFile).toList()) {
                if(path.getFileName().toString().endsWith(".lock"))continue;
                result.put(root.relativize(path).toString(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
        }
        return result;
    }

    private static GameSessionTokenSource syntheticTokens(java.util.concurrent.atomic.AtomicReference<GameSessionTokenSource.SessionIdentity> identity) {
        return new GameSessionTokenSource() {
            public SessionIdentity currentSession() { return identity.get(); }
            public <T,E extends Exception> T withAccessToken(TokenRequest<T,E> action) throws E { return action.execute("synthetic-disposable-not-a-credential"); }
        };
    }

    private static com.naocraftlab.skins.core.api.ProfileApi syntheticApi(
            java.util.concurrent.atomic.AtomicReference<GameSessionTokenSource.SessionIdentity> identity,
            List<byte[]> bytes, List<SkinVariant> variants) {
        return new com.naocraftlab.skins.core.api.ProfileApi() {
            public RemoteProfile getProfile(String ignored) { return new RemoteProfile(identity.get().profileId(),"Fixture",List.of(),List.of(),Set.of()); }
            public void uploadSkin(String ignored,SkinVariant variant,byte[] png) { bytes.add(png.clone());variants.add(variant); }
            public void resetSkin(String ignored) { throw new AssertionError("No reset"); }
            public void activateCape(String ignored,String id) { throw new AssertionError("No cape activation"); }
            public void deactivateCape(String ignored) {}
        };
    }

    public static byte[] explicitTestApply(Path root, byte[] bundled, SkinVariant variant) throws Exception {
        return explicitTestApply(root, bundled, variant, true);
    }

    public static byte[] explicitTestApply(Path root, byte[] bundled, SkinVariant variant, boolean directControl) throws Exception {
        UUID account=UUID.fromString("00000000-0000-0000-0000-000000000001");
        var identity=new java.util.concurrent.atomic.AtomicReference<>(new GameSessionTokenSource.SessionIdentity(account,"Fixture"));
        var bytes=new ArrayList<byte[]>();var variants=new ArrayList<SkinVariant>();
        var tokens=syntheticTokens(identity);var api=syntheticApi(identity,bytes,variants);
        var storage=new com.naocraftlab.skins.core.storage.NclSkinsStorage(root,new PngValidator(),java.time.Clock.systemUTC());
        var operations=com.naocraftlab.skins.runtime.composition.ClientCompositionRoot.operations(tokens,api,storage,
                (BundledSkinSource) model -> bundled.clone(),java.time.Clock.systemUTC(),null).operations();
        var state=storage.loadOrCreateAccount(account);
        require(bytes.isEmpty(),"Fresh reopen does not upload");
        var selected=state.presets().get(state.presets().size()-1);
        byte[] saved=storage.readAsset(state.skinAssets().stream().filter(a->a.id().equals(selected.skin().assetId())).findFirst().orElseThrow().sha256());
        if (directControl) {
            new com.naocraftlab.skins.core.api.ProfileSessionAdapter(api).withSession(tokens,effects->{
                try { effects.uploadSkin(variant,saved); } catch(Exception failure) { throw new AssertionError(failure); }return null;
            });
            require(bytes.size()==1 && Arrays.equals(saved,bytes.get(0)) && variants.get(0)==variant,"Separate direct Effects API positive control");
            bytes.clear();variants.clear();
        }
        operations.usePreset(selected.id());
        require(bytes.isEmpty(),"Durable local Apply intent is not upload by itself");
        operations.reconcileAppearance(com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger.LOCAL_INTENT).orElseThrow();
        require(bytes.size()==1 && variants.equals(List.of(variant)),"Explicit Apply through reconciliation captures exactly one actual Effects upload variant");
        require(Arrays.equals(saved,bytes.get(0)),"R09 actual AccountMutationExecutor upload preparation preserves exact saved carrier bytes");
        System.out.println("PASS source-free explicit Apply→AccountMutationExecutor→AppearanceMutationService→ProfileSessionPort.Effects.uploadSkin " + variant);
        return bytes.get(0);
    }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void pickerSetup(Path model, Path png) throws Exception {
        var coordinator = new FilePickerCoordinator(Runnable::run);
        require(coordinator.choose(() -> Optional.of(png)).join().orElseThrow().equals(png.toAbsolutePath()), "Ordinary coordinator PNG setup");
        boolean rejected = false;
        try { coordinator.choose(() -> Optional.of(model)).join(); }
        catch (CompletionException expected) { rejected = true; }
        require(rejected, "Base/replacement ordinary coordinator remains PNG-only");
        CompletableFuture<Optional<Path>> selected = coordinator.chooseSkinImport(() -> Optional.of(model));
        require(!selected.isCompletedExceptionally() && selected.join().orElseThrow().equals(model.toAbsolutePath()), "Outer import coordinator admits actual model path after ordinary PNG-only controls");
        System.out.println("PASS purpose-specific coordinator model/PNG and ordinary PNG-only controls");
    }
    private void drain() {
        int steps = 0;
        while (!workers.isEmpty() || !clients.isEmpty()) {
            require(++steps < 100, "Bounded queue completion");
            if (!workers.isEmpty()) { lane = currentLane = "worker"; workers.remove().run(); }
            if (!clients.isEmpty()) { lane = currentLane = "client"; clients.remove().run(); }
        }
        lane = currentLane = "client";
    }
    public void ordinary(Path base) {
        picks.add(base); flow.chooseAddSourcePng(); drain();
        require(result != null && publications == 1, "Ordinary PNG setup reaches same real Editor consumer; failure=" + failureReason + " feedback=" + feedback.key());
        require(result.draft().png().isPresent(), "Ordinary PNG setup prepares actual draft bytes");
        flow.closeScreens();
        System.out.println("PASS ordinary PNG same-harness bundled-account setup");
    }
    private void workerStep() { lane = currentLane = "worker"; workers.remove().run(); lane = currentLane = "client"; }
    private void clientStep() { lane = currentLane = "client"; clients.remove().run(); }
    public void stale(Path model, Path base, int stage) {
        picks.add(model); flow.chooseAddSourcePng(); drain();
        require(flow.addSource().filter() != AddSourceModel.CatalogFilter.ALL, "Stale control starts with actually admitted model");
        flow.selectAddSourceTab(AddSourceTab.FILE);
        picks.add(base); flow.chooseAddSourcePng();
        if (stage >= 1) clientStep();
        if (stage >= 2) workerStep();
        require(result == null, "Stale control retires before client publication");
        require(stage < 2 || conversionCalls == 1, "Completed worker performs actual conversion before held client callback");
        flow.closeScreens();
        generation++; busy = true;
        drain();
        require(result == null && publications == 0, "Stale valid result never publishes Editor");
        require(busy, "Stale completion never unlocks newer operation");
        require(preferences == 0, "Stale result writes no preferences");
    }
    public void mismatch(Path model, Path wrongBase) {
        picks.add(model); flow.chooseAddSourcePng(); drain();
        flow.selectAddSourceTab(AddSourceTab.FILE);
        picks.add(wrongBase); flow.chooseAddSourcePng(); drain();
        require(result == null && publications == 0 && failureReason.equals("VARIANT_UNSUPPORTED"), "Actual caller rejects detected mismatch before exporter/Editor");
        require(flow.addSource() != null && !busy && preferences == 0, "Mismatch retains chooser without writes");
        flow.closeScreens();
    }
    public void terminal(Path model, Path base) {
        picks.add(model); flow.chooseAddSourcePng(); drain();
        flow.selectAddSourceTab(AddSourceTab.FILE);
        picks.add(base); flow.chooseAddSourcePng(); drain();
        require(result == null && publications == 0 && failureReason.equals("CONVERSION_FAILED"), "Actual caller single safe exporter failure, no false Editor");
        require(!busy && preferences == 0, "Terminal failure settles once without preference effects");
        flow.closeScreens();
    }
    public void cancel(Path model, Path base) {
        picks.add(model); flow.chooseAddSourcePng(); drain();
        flow.cancelAddSource();
        require(flow.addSource() != null && flow.addSource().selectedTab() == AddSourceTab.FILE
                && flow.addSource().filter() == AddSourceModel.CatalogFilter.ALL, "Cancel model chooser returns exact ordinary parent rather than dropping entire flow");
        require(preferences == 0 && result == null && !busy, "Chooser cancel has zero publication/preference effects");
        picks.add(base); flow.chooseAddSourcePng(); drain();
        require(result != null && result.draft().variantAvailable(SkinVariant.SLIM), "Cancel retires model: subsequent ordinary PNG retains manual override");
        flow.closeScreens();
    }
    public byte[] convert(Path model, Path base, SkinVariant required) {
        picks.add(model); flow.chooseAddSourcePng(); drain();
        require(result == null, "Model admission must not publish a raw/base Editor");
        require(flow.addSource().filter().name().equals(required.name()), "Actual outer model admission selects required chooser filter");
        flow.selectAddSourceTab(AddSourceTab.FILE);
        picks.add(base); flow.chooseAddSourcePng(); drain();
        require(result != null && publications == 1, "Actual caller publishes exactly one converted pair; required=" + required + " failure=" + failureReason);
        var draft = result.draft();
        require(draft.variant() == required, "Initial variant must preserve source authority");
        require(draft.variantAvailable(required) && !draft.variantAvailable(required == SkinVariant.SLIM ? SkinVariant.CLASSIC : SkinVariant.SLIM), "Unsupported alternate disabled");
        require(draft.catalogOrigin().isEmpty() && draft.originalPresetId().isEmpty(), "Converted result has no catalog/base reuse origin");
        require(preferences == 0, "Scoped chooser and converted publication must not write preferences");
        require(stages.equals(List.of("decode")), "Real worker base decode once");
        require(conversionCalls == 1, "Exactly one actual worker conversion for selected concrete base");
        flow.closeScreens();
        return draft.png().orElseThrow().bytes();
    }
}
