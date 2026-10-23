package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger;
import com.naocraftlab.skins.client.CapeCatalogSource;
import com.naocraftlab.skins.client.CatalogCollectionOrder;
import com.naocraftlab.skins.client.CatalogText;
import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.EncodedTextureLimit;
import com.naocraftlab.skins.client.ExpectedAppearance;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.MinecraftSkinCatalog;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.PersonalSkinCatalog;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.PlayerAppearanceSink.CapeSource;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.ScreenDestination;
import com.naocraftlab.skins.client.ServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.client.SignedProfileResolver;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.api.PublicSkinImportException;
import com.naocraftlab.skins.core.importing.ExternalImportProbe;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.EditorTab;
import com.naocraftlab.skins.core.model.LocalCapeReference;
import com.naocraftlab.skins.core.model.MutationResult;
import com.naocraftlab.skins.core.model.OwnedCapeEntry;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.model.PersonalCapeEntry;
import com.naocraftlab.skins.core.model.PersonalSkinEntry;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.RemoteAssetState;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderCape;
import com.naocraftlab.skins.core.provider.ProviderObservation;
import com.naocraftlab.skins.core.provider.ProviderSkin;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.storage.TextureCache;
import com.naocraftlab.skins.core.service.ApplicationPhase;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.RecoveryAction;
import com.naocraftlab.skins.core.service.RemoteAppearanceImpact;
import com.naocraftlab.skins.core.service.SessionCheckPhase;
import com.naocraftlab.skins.core.service.SessionFailureContext;
import com.naocraftlab.skins.core.service.SessionStatus;
import com.naocraftlab.skins.core.service.SessionValidation;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientRuntimeTest {
    @Test
    void frozenIngressRenamedPngUsesActualBytesInProductionCaller(@TempDir Path directory) throws Exception {
        Path source = Files.write(directory.resolve("renamed.cpmproject"), opaqueSkinPng(false));
        var operations = new FakeOperations();
        var runtime = runtime(operations, () -> CompletableFuture.completedFuture(Optional.of(source)));
        try {
            runtime.reopen(ScreenDestination.SKIN_IMPORT);
            runtime.dispatchWidget("add.file.choose");
            assertTrue(runtime.snapshot().editor().isPresent(), "Renamed PNG must reach the actual creation consumer");
        } finally { runtime.close(); }
    }

    @Test
    void frozenIngressExplicitUrlModelReachesActualAdmissionCaller(@TempDir Path directory) throws Exception {
        var operations = new FakeOperations();
        operations.acquiredImports = com.naocraftlab.skins.core.api.FrozenIngressNetworkFixture.imports(directory, validModel(false));
        var runtime = runtime(operations, () -> CompletableFuture.completedFuture(Optional.empty()));
        useBaseConverter(runtime, new BaseOnlyConverter(SkinModel.CLASSIC));
        runtime.reopen(ScreenDestination.SKIN_IMPORT);
        runtime.dispatchText("add.url.input", "https://example.com/renamed.PNG");
        runtime.dispatchWidget("add.url.load");
        assertEquals(AddSourceModel.Purpose.CPM_BASE, runtime.snapshot().addSource().orElseThrow().importContext().purpose());
        assertTrue(runtime.snapshot().editor().isEmpty());
        assertEquals(0, operations.editorSaveCalls);
        runtime.close();
    }

    @Test
    void frozenIngressFileAndUrlMatrixReachesActualTerminalConsumers(@TempDir Path directory) throws Exception {
        var zipBytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(zipBytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("config.json"));
            zip.write("{\"version\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[][] bodies = {opaqueSkinPng(false), validModel(false), zipBytes.toByteArray()};
        for (boolean url : List.of(false, true)) for (int format = 0; format < bodies.length; format++) {
            var operations = new FakeOperations();
            var before = operations.account;
            var picker = new ComposedReasonPicker(true);
            var runtime = runtime(operations, picker);
            var converter = new BaseOnlyConverter(SkinModel.CLASSIC);
            useBaseConverter(runtime, converter);
            runtime.reopen(ScreenDestination.SKIN_IMPORT);
            if (url) {
                operations.acquiredImports = com.naocraftlab.skins.core.api.FrozenIngressNetworkFixture.imports(directory, bodies[format]);
                runtime.dispatchText("add.url.input", "https://example.com/source.PNG");
                runtime.dispatchWidget("add.url.load");
            } else {
                Path source = Files.write(directory.resolve("source-" + format + ".data"), bodies[format]);
                picker.selections.add(CompletableFuture.completedFuture(Optional.of(source)));
                runtime.dispatchWidget("add.file.choose");
            }
            if (format == 0) assertTrue(runtime.snapshot().editor().isPresent());
            else {
                var input = runtime.snapshot().addSource().orElseThrow().importContext();
                assertTrue(runtime.snapshot().editor().isEmpty());
                if (format == 1) assertEquals(AddSourceModel.Purpose.CPM_BASE, input.purpose());
                else assertEquals(Optional.of(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.MODEL_UNSUPPORTED), input.failure());
            }
            assertEquals(before, operations.account);
            assertEquals(0, operations.editorSaveCalls);
            assertEquals(0, operations.applyCalls);
            assertEquals(0, converter.conversions);
            runtime.close();
        }
    }

    @Test
    void frozenIngressRenamedModelReachesOptionalAdmission(@TempDir Path directory) throws Exception {
        Path source = Files.copy(Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-embedded.cpmmodel"),
                directory.resolve("model.PNG"));
        var operations = new FakeOperations();
        var runtime = runtime(operations, () -> CompletableFuture.completedFuture(Optional.of(source)));
        useBaseConverter(runtime, new com.naocraftlab.skins.client.CpmModelFileConversion() {
            public Availability availability() { return Availability.READY; }
            public AdmittedModel admitFile(Path path) { throw new AssertionError("Original path must not be reopened"); }
            public AdmittedModel admit(java.io.InputStream input) throws Failure {
                assertTrue(input instanceof com.naocraftlab.skins.client.FrozenImportInput.Reader);
                try { assertEquals(83, input.read()); }
                catch (java.io.IOException failure) { throw new Failure(Reason.IO_FAILED); }
                return new AdmittedModel() {
                    public Optional<SkinModel> requiredVariant() { return Optional.of(SkinModel.CLASSIC); }
                    public Optional<Raster> automaticBase(SkinModel preferred) { return Optional.empty(); }
                    public void close() { }
                };
            }
            public Preparation prepare(AdmittedModel model, Raster base, long revision) { throw new AssertionError("Admission-only test"); }
        });
        try {
            runtime.reopen(ScreenDestination.SKIN_IMPORT);
            runtime.dispatchWidget("add.file.choose");
            assertEquals(Optional.of(SkinModel.CLASSIC), runtime.snapshot().addSource().orElseThrow()
                    .importContext().requiredVariant(), "Content-sniffed model must reach optional admission");
        } finally { runtime.close(); }
    }

    @Test
    void frozenIngressProjectGoesToNativeAdapterWithoutIndependentArchiveGate(@TempDir Path directory) throws Exception {
        for (boolean traversal : List.of(false, true)) {
            Path source = directory.resolve(traversal ? "unsafe.PNG" : "project.bin");
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(source))) {
                zip.putNextEntry(new java.util.zip.ZipEntry(traversal ? "../config.json" : "config.json"));
                zip.write("{\"version\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            var runtime = runtime(new FakeOperations(), () -> CompletableFuture.completedFuture(Optional.of(source)));
            useBaseConverter(runtime, new BaseOnlyConverter(SkinModel.CLASSIC));
            try {
                runtime.reopen(ScreenDestination.SKIN_IMPORT);
                runtime.dispatchWidget("add.file.choose");
                assertTrue(runtime.snapshot().editor().isEmpty());
                assertEquals(Optional.of(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.MODEL_UNSUPPORTED),
                        runtime.snapshot().addSource().orElseThrow().importContext().failure(),
                        "Native test adapter receives the project; no NCL archive parser is an admission authority");
            } finally { runtime.close(); }
        }
    }

    private static final DirectClientExecutor CLIENT = new DirectClientExecutor();
    private static final FilePicker CANCELLED_PICKER =
            () -> CompletableFuture.completedFuture(Optional.empty());
    private static final TextResolver TEXT = message -> switch (message.key()) {
        case "nclskins.editor.default_name" -> "Preset " + message.arguments().get(0);
        case "nclskins.gallery.copy_name" -> "Copy of " + message.arguments().get(0);
        default -> message.key();
    };

    @Test
    void sneakyCatalogProducerAndReusablePersonalVariantPublishExactBytes() throws Exception {
        FakeOperations operations = new FakeOperations();
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY);
        operations.catalogPng = SneakyPreviewPreparationTest.skin(0x7f123456);
        operations.skinPreviewPng = SneakyPreviewPreparationTest.skin(0x7f654321);
        operations.personalCatalogPng = operations.skinPreviewPng;
        String personalHash = operations.seedPersonalSkin("Reusable", PersonalSkinSource.FILE);
        try (ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty())) {
            runtime.initialize();
            runtime.dispatchWidget("gallery.add");
            runtime.dispatchWidget("add.tab.catalog");
            runtime.dispatchWidget("add.catalog.skin:minecraft:steve");
            var catalog = runtime.snapshot().editor().orElseThrow();
            assertTrue(catalog.png().isPresent());
            assertArrayEquals(operations.catalogPng, catalog.png().orElseThrow().bytes());
            String first = catalog.capeCatalog().defaultBundle().texture().orElseThrow();
            assertArrayEquals(new com.naocraftlab.skins.core.png.SneakyCapeDecoder().decode(operations.catalogPng).orElseThrow().bytes(),
                    runtime.loadCapePreview(first).join().orElseThrow());
            runtime.dispatchWidget("editor.model_choice.slim");
            var slim = runtime.snapshot().editor().orElseThrow();
            assertEquals(SkinVariant.SLIM, slim.variant());
            assertTrue(slim.capeCatalog().sneakyTicket() > catalog.capeCatalog().sneakyTicket());
            assertArrayEquals(operations.catalogPng, slim.png().orElseThrow().bytes());
            assertTrue(runtime.loadCapePreview(first).join().isEmpty());
            runtime.dispatchWidget("editor.cancel");
            runtime.dispatchWidget("add.catalog.skin:" + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash);
            var reusable = runtime.snapshot().editor().orElseThrow();
            assertArrayEquals(operations.personalCatalogPng, reusable.png().orElseThrow().bytes());
            assertEquals(operations.personalCatalogAssetId, reusable.skin().optionalAssetId().orElseThrow());
            assertArrayEquals(new com.naocraftlab.skins.core.png.SneakyCapeDecoder().decode(operations.skinPreviewPng).orElseThrow().bytes(),
                    runtime.loadCapePreview(reusable.capeCatalog().defaultBundle().texture().orElseThrow()).join().orElseThrow());
            assertEquals(0, operations.skinPreviewCalls);
            assertEquals(0, operations.applyCalls);
            assertEquals(0, operations.resourceCapeMaterializations);
        }
    }

    @Test
    void sneakyResourceOfflineCandidateResolvesAtItsActualPriorityWithoutMaterialization() throws Exception {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY);
        operations.skinPreviewPng = SneakyPreviewPreparationTest.skin(0);
        operations.resourceCapeCollections = List.of(resourceCapeCollection());
        operations.resourceCapeHashes = Map.of(new ClientOperations.ResourceCapeKey("event", "hero"), "c".repeat(64));
        try (ClientRuntime runtime = runtime(operations, CANCELLED_PICKER)) {
            runtime.initialize();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            runtime.dispatchWidget("editor.tab.cape");
            runtime.dispatchWidget("editor.cape_item.RESOURCE.event.hero");
            runtime.dispatchWidget("editor.tab.cape");
            var before = runtime.snapshot().editor().orElseThrow().capeCatalog();
            assertEquals(BuiltinProvider.OFFLINE, before.defaultBundle().source());
            assertTrue(before.defaultBundle().texture().orElseThrow().startsWith("resource:cape:1:event:hero:"));
            var accept = ClientRuntime.class.getDeclaredMethod("acceptProviderChange", ClientOperations.DurableAppearance.class);
            accept.setAccessible(true);
            operations.providers = operations.providers.move(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY, -1);
            operations.providers = operations.providers.move(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY, -1);
            accept.invoke(runtime, operations.providerState());
            var after = runtime.snapshot().editor().orElseThrow().capeCatalog();
            assertEquals(BuiltinProvider.SNEAKY, after.defaultBundle().source());
            assertEquals(before.selectedResource(), after.selectedResource());
            assertEquals(before.sneakyTicket(), after.sneakyTicket());
            assertEquals(before.sneaky(), after.sneaky());
            assertFalse(after.defaultBundle().hasElytra());
            assertEquals(1, operations.skinPreviewCalls);
            assertEquals(0, operations.resourceCapeMaterializations);
            runtime.dispatchWidget("editor.cancel");
            assertEquals(0, operations.resourceCapeMaterializations);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void providerFailureCaptionConnectsStoredProducerToChainAndSelectedSkin(boolean selected, @TempDir Path directory) throws Exception {
        try (var fixture = new ProviderFailureFixture(directory, selected, CLIENT, Runnable::run)) {
            var runtime = fixture.runtime;
            var initial = runtime.view(854, 480, 0, 0);
            assertEquals("providers", initial.screenId());
            assertTrue(runtime.snapshot().editor().isEmpty());
            assertEquals(0, fixture.operations.skinPreviewCalls);
            assertTrue(runtime.snapshot().assetEvidence().get(TestFixtures.CLASSIC_ID).supportedFeatures()
                    .contains(com.naocraftlab.skins.core.compatibility.SkinFeature.CUSTOMIZABLE_PLAYER_MODELS));
            assertEquals(fixture.cpm.sha256(), runtime.snapshot().account().orElseThrow().skinAssets().get(0).sha256());
            if (selected) runtime.dispatchWidget("providers.row.MINECRAFT");
            var preview = runtime.view(854, 480, 0, 0).previews().get(0);
            assertEquals("provider:skin:" + fixture.cpm.sha256(), preview.imageRevision());
            var account = fixture.operations.account;
            var providers = fixture.operations.providers;
            int reads = fixture.operations.evidenceReads;
            try (var projection = CapeProjection.installEvents(TestCapeProjection.events())) {
                assertTrue(projection.active());
                var source = CapeProjection.effectiveSkin(new UUID(0, 731), account.accountId(), preview.imageRevision(), SkinModel.CLASSIC, true);
                var producer = CapeProjection.cpmObserver(source, "SyntheticPreview");
                java.util.function.Supplier<com.naocraftlab.skins.client.PreviewAccessories> supplier = () ->
                        CapeProjection.previewAccessories(source.cacheId(), preview.imageRevision(), SkinModel.CLASSIC, PreviewRenderer.LiveReason.AVAILABLE);
                var callback = runtime.observePreviewAccessories(preview, supplier);
                assertFalse(hasFailureCaption(runtime.view(854, 480, 0, 0)));
                assertTrue(producer.publish(1, CpmAccessoryObservation.Availability.FAILED, false, false));
                callback.accept(supplier.get());
                var failed = runtime.view(854, 480, 0, 0);
                assertTrue(hasFailureCaption(failed), "Current FAILED must reach caption for " + (selected ? "selected SKIN" : "chain-winning SKIN"));
                assertEquals(preview, failed.previews().get(0));
                assertFalse(runtime.waitsForCpmBody(failed.previews().get(0)));
                var history = new ArrayList<ViewSpec>();
                runtime.subscribe(ignored -> history.add(runtime.view(854, 480, 0, 0)));
                int notifications = history.size();
                for (int i = 0; i < 8; i++) {
                    assertTrue(hasFailureCaption(runtime.view(854, 480, 0, 0)));
                    callback.accept(supplier.get());
                }
                assertEquals(notifications, history.size(), "Repeated FAILED is not a publication loop");
                history.clear();
                runtime.dispatchWidget("providers.refresh");
                assertFalse(history.isEmpty());
                history.forEach(view -> {
                    assertTrue(hasFailureCaption(view), "Refresh keeps every current failure projection");
                    assertEquals(preview.imageRevision(), view.previews().get(0).imageRevision());
                });
                for (var availability : List.of(CpmAccessoryObservation.Availability.UNKNOWN, CpmAccessoryObservation.Availability.PENDING,
                        CpmAccessoryObservation.Availability.BLOCKED, CpmAccessoryObservation.Availability.CONFIRMED)) {
                    assertTrue(producer.publish(2, availability, false, false));
                    callback.accept(supplier.get());
                    assertFalse(hasFailureCaption(runtime.view(854, 480, 0, 0)), availability.name());
                }
                producer.publish(3, CpmAccessoryObservation.Availability.FAILED, false, false);
                callback.accept(supplier.get());
                assertTrue(hasFailureCaption(runtime.view(854, 480, 0, 0)));
                runtime.dispatchWidget("providers.row." + (selected ? "OFFLINE" : "MINECRAFT"));
                assertFalse(hasFailureCaption(runtime.view(854, 480, 0, 0)), "Ordinary inspection rejects old chain failure");
                runtime.dispatchWidget("providers.tab.SKIN");
                assertFalse(hasFailureCaption(runtime.view(854, 480, 0, 0)), "Reset does not revive retained FAILED");
                assertEquals(providers, fixture.operations.providers);
                assertEquals(account, fixture.operations.account);
                assertEquals(reads, fixture.operations.evidenceReads);
                assertEquals(0, fixture.operations.skinPreviewCalls);
                assertEquals(0, fixture.operations.applyCalls);
                assertEquals(0, fixture.operations.editorSaveCalls);
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ordinary", "absent", "carrier-only", "worldless", "no-evidence", "cape-only"})
    void providerFailureCaptionRequiresCarrierLibraryAndCurrentModelEvidence(String negative, @TempDir Path directory) throws Exception {
        try (var fixture = new ProviderFailureFixture(directory, negative.equals("cape-only"), CLIENT, Runnable::run)) {
            var runtime = fixture.runtime;
            if (negative.equals("ordinary")) runtime.dispatchWidget("providers.row.MINECRAFT");
            if (negative.equals("absent")) {
                fixture.cpmPresent.set(false);
                runtime.resourcesReloaded();
            }
            if (negative.equals("no-evidence")) {
                var account = fixture.operations.account;
                fixture.operations.account = new AccountState(AccountState.CURRENT_SCHEMA_VERSION, account.accountId(),
                        List.of(), account.personalSkins(), account.presets(), account.updatedAt());
                runtime.closeScreen();
                runtime.reopen(ScreenDestination.PROVIDERS);
                assertTrue(runtime.snapshot().account().orElseThrow().skinAssets().isEmpty());
            }
            if (negative.equals("cape-only")) {
                runtime.dispatchWidget("providers.row.MINECRAFT");
                runtime.dispatchWidget("providers.tab.CAPE");
            }
            var preview = runtime.view(854, 480, 0, 0).previews().get(0);
            int effects = fixture.operations.applyCalls;
            var observed = negative.equals("carrier-only") || negative.equals("worldless")
                    ? com.naocraftlab.skins.client.PreviewAccessories.unavailable(PreviewRenderer.LiveReason.WORLD_REQUIRED)
                    : new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 735), 1,
                            com.naocraftlab.skins.client.PreviewAccessories.Availability.FAILED, false, false, PreviewRenderer.LiveReason.AVAILABLE);
            var callback = runtime.observePreviewAccessories(preview, () -> observed);
            callback.accept(observed);
            for (int i = 0; i < 5; i++) assertFalse(hasFailureCaption(runtime.view(854, 480, 0, 0)), negative);
            assertEquals(effects, fixture.operations.applyCalls);
            assertEquals(0, fixture.operations.skinPreviewCalls);
        }
    }

    @Test
    void providerFailureCaptionIsIndependentOfCapeInspectionAndBodyAuthority(@TempDir Path directory) throws Exception {
        try (var fixture = new ProviderFailureFixture(directory, false, CLIENT, Runnable::run)) {
            var runtime = fixture.runtime;
            fixture.operations.providers = new AppearanceProviders(fixture.operations.providers.skin(),
                    fixture.operations.providers.cape().enable(BuiltinProvider.OFFLINE)
                            .select(1, new ProviderCape("synthetic-cape", null, false)));
            runtime.dispatchWidget("gallery.providers");
            runtime.dispatchWidget("providers.tab.CAPE");
            runtime.dispatchWidget("providers.row.OFFLINE");
            var preview = runtime.view(854, 480, 0, 0).previews().get(0);
            var failure = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 737), 1,
                    com.naocraftlab.skins.client.PreviewAccessories.Availability.FAILED, false, false, PreviewRenderer.LiveReason.AVAILABLE);
            var live = new AtomicReference<>(failure);
            var callback = runtime.observePreviewAccessories(preview, live::get);
            assertTrue(hasFailureCaption(runtime.view(854, 480, 0, 0)));
            var body = new AtomicReference<>(PreviewRenderer.DisplayKind.CPM);
            var bodyCallback = runtime.observePreviewBody(preview, body::get);
            bodyCallback.accept(PreviewRenderer.BodyStatus.READY);
            assertTrue(hasFailureCaption(runtime.view(854, 480, 0, 0)));
            body.set(PreviewRenderer.DisplayKind.ORDINARY);
            bodyCallback.accept(PreviewRenderer.BodyStatus.ORDINARY);
            assertTrue(hasFailureCaption(runtime.view(854, 480, 0, 0)));
            live.set(com.naocraftlab.skins.client.PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LOADING));
            callback.accept(failure);
            assertFalse(hasFailureCaption(runtime.view(854, 480, 0, 0)), "Old callback payload must not replace the current supplier observation");
            assertEquals(0, fixture.operations.applyCalls);
            assertEquals(0, fixture.operations.skinPreviewCalls);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"world", "resources", "model", "source", "roundtrip", "session", "account", "reopen"})
    void providerFailedAccessoryRegistrationRejectsRetiredSameRevision(String transition, @TempDir Path directory) throws Exception {
        TrackingClientExecutor client = new TrackingClientExecutor();
        QueuedExecutor worker = new QueuedExecutor();
        QueuedExecutor nativeCallbacks = new QueuedExecutor();
        try (var fixture = new ProviderFailureFixture(directory, false, client, worker)) {
            drainRefreshQueues(worker, client);
            var runtime = fixture.runtime;
            var preview = refreshView(runtime, client).previews().get(0);
            var failure = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 732), 1,
                    com.naocraftlab.skins.client.PreviewAccessories.Availability.FAILED, false, false, PreviewRenderer.LiveReason.AVAILABLE);
            var reads = new AtomicInteger();
            var callback = new AtomicReference<java.util.function.Consumer<com.naocraftlab.skins.client.PreviewAccessories>>();
            client.execute(() -> callback.set(runtime.observePreviewAccessories(preview, () -> { reads.incrementAndGet(); return failure; })));
            drainRefreshQueues(worker, client);
            assertTrue(hasFailureCaption(refreshView(runtime, client)));
            nativeCallbacks.execute(() -> callback.get().accept(failure));
            var originalSession = fixture.operations.session;
            switch (transition) {
                case "world" -> runtime.importWorldChanged();
                case "resources" -> runtime.resourcesReloaded();
                case "model" -> {
                    fixture.operations.providers = new AppearanceProviders(fixture.operations.providers.skin().select(1,
                            new ProviderSkin(fixture.cpm.sha256(), SkinVariant.SLIM)), fixture.operations.providers.cape());
                    runtime.dispatchWidget("gallery.providers");
                }
                case "source" -> runtime.dispatchWidget("providers.row.MINECRAFT");
                case "roundtrip" -> {
                    runtime.dispatchWidget("providers.row.MINECRAFT");
                    runtime.dispatchWidget("providers.row.OFFLINE");
                }
                case "session", "account" -> {
                    fixture.operations.session = new SessionValidation(SessionStatus.OFFLINE_OR_INVALID,
                            new GameSessionTokenSource.SessionIdentity(transition.equals("session")
                                    ? originalSession.sessionIdentity().profileId() : new UUID(0, 733), "SyntheticOther"),
                            null, (SessionFailureContext) null, "synthetic");
                    runtime.tick();
                }
                case "reopen" -> { runtime.closeScreen(); runtime.reopen(ScreenDestination.PROVIDERS); }
                default -> throw new AssertionError(transition);
            }
            drainRefreshQueues(worker, client);
            var history = new ArrayList<ViewSpec>();
            runtime.subscribe(ignored -> history.add(refreshView(runtime, client)));
            drainRefreshQueues(worker, client);
            int beforeOldRead = reads.get();
            assertFalse(hasFailureCaption(refreshView(runtime, client)), transition);
            while (nativeCallbacks.size() > 0) nativeCallbacks.runFirst();
            drainRefreshQueues(worker, client);
            assertFalse(hasFailureCaption(refreshView(runtime, client)), transition);
            assertEquals(beforeOldRead, reads.get(), "Retired supplier cannot execute: " + transition);
            if (transition.equals("session") || transition.equals("account")) {
                fixture.operations.session = originalSession; runtime.tick(); drainRefreshQueues(worker, client);
                assertFalse(hasFailureCaption(refreshView(runtime, client)), "Account A-B-A does not revive failure");
            }
            history.forEach(view -> assertFalse(hasFailureCaption(view), "Stale subscriber caption: " + transition));
            var fresh = refreshView(runtime, client).previews().get(0);
            var next = new AtomicReference<java.util.function.Consumer<com.naocraftlab.skins.client.PreviewAccessories>>();
            client.execute(() -> next.set(runtime.observePreviewAccessories(fresh,
                    () -> com.naocraftlab.skins.client.PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LOADING))));
            drainRefreshQueues(worker, client);
            callback.get().accept(failure); drainRefreshQueues(worker, client);
            assertFalse(hasFailureCaption(refreshView(runtime, client)));
            assertEquals(0, fixture.operations.applyCalls);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"world", "resources", "replace", "close"})
    void providerFailedAccessorySupplierReentrancyCannotOverwriteReplacement(String transition, @TempDir Path directory) throws Exception {
        try (var fixture = new ProviderFailureFixture(directory, false, CLIENT, Runnable::run)) {
            var runtime = fixture.runtime;
            var preview = runtime.view(854, 480, 0, 0).previews().get(0);
            var armed = new AtomicBoolean();
            var oldFailure = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 734), 1,
                    com.naocraftlab.skins.client.PreviewAccessories.Availability.FAILED, false, false, PreviewRenderer.LiveReason.AVAILABLE);
            var replacement = com.naocraftlab.skins.client.PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LOADING);
            var nextReads = new AtomicInteger();
            var old = runtime.observePreviewAccessories(preview, () -> {
                if (armed.getAndSet(false)) {
                    switch (transition) {
                        case "world" -> runtime.importWorldChanged();
                        case "resources" -> runtime.resourcesReloaded();
                        case "close" -> runtime.closeScreen();
                        case "replace" -> runtime.observePreviewAccessories(preview, () -> { nextReads.incrementAndGet(); return replacement; });
                        default -> throw new AssertionError(transition);
                    }
                }
                return oldFailure;
            });
            assertTrue(hasFailureCaption(runtime.view(854, 480, 0, 0)));
            armed.set(true);
            var view = runtime.view(854, 480, 0, 0);
            assertFalse(hasFailureCaption(view), "The same reentrant query must reject old failure: " + transition);
            var publications = new AtomicInteger();
            runtime.subscribe(ignored -> publications.incrementAndGet());
            int before = publications.get();
            old.accept(oldFailure);
            for (int i = 0; i < 5; i++) assertFalse(hasFailureCaption(runtime.view(854, 480, 0, 0)));
            assertEquals(before, publications.get());
            if (transition.equals("replace")) assertTrue(nextReads.get() >= 5, "Old result/release must not clear replacement");
        }
    }

    @Test
    void providerAccessoryRegistrationReentrancyPreservesNewCallbackIdentity(@TempDir Path directory) throws Exception {
        try (var fixture = new ProviderFailureFixture(directory, false, CLIENT, Runnable::run)) {
            var runtime = fixture.runtime;
            var preview = runtime.view(854, 480, 0, 0).previews().get(0);
            java.util.function.Supplier<com.naocraftlab.skins.client.PreviewAccessories> newer = () ->
                    com.naocraftlab.skins.client.PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LOADING);
            var replacement = new AtomicReference<java.util.function.Consumer<com.naocraftlab.skins.client.PreviewAccessories>>();
            var identityCalls = new AtomicInteger();
            fixture.operations.sessionIdentityHook = () -> {
                if (identityCalls.incrementAndGet() == 2) {
                    fixture.operations.sessionIdentityHook = null;
                    replacement.set(runtime.observePreviewAccessories(preview, newer));
                }
            };
            var failed = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 736), 1,
                    com.naocraftlab.skins.client.PreviewAccessories.Availability.FAILED, false, false, PreviewRenderer.LiveReason.AVAILABLE);
            var old = runtime.observePreviewAccessories(preview, () -> failed);
            assertSame(replacement.get(), runtime.observePreviewAccessories(preview, newer), "Reentrant registration keeps its own callback");
            old.accept(failed);
            assertFalse(hasFailureCaption(runtime.view(854, 480, 0, 0)));
        }
    }

    @Test
    void queuedOldAccessoryReleaseCannotClearReopenedCurrentRegistration(@TempDir Path directory) throws Exception {
        TrackingClientExecutor client = new TrackingClientExecutor();
        QueuedExecutor worker = new QueuedExecutor();
        QueuedExecutor nativeRelease = new QueuedExecutor();
        try (var fixture = new ProviderFailureFixture(directory, false, client, worker)) {
            drainRefreshQueues(worker, client);
            var runtime = fixture.runtime;
            var oldFailure = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 738), 1,
                    com.naocraftlab.skins.client.PreviewAccessories.Availability.FAILED, false, false, PreviewRenderer.LiveReason.AVAILABLE);
            var old = new AtomicReference<java.util.function.Consumer<com.naocraftlab.skins.client.PreviewAccessories>>();
            client.execute(() -> old.set(runtime.observePreviewAccessories(refreshView(runtime, client).previews().get(0), () -> oldFailure)));
            drainRefreshQueues(worker, client);
            assertTrue(hasFailureCaption(refreshView(runtime, client)));
            var oldRegistration = old.get();
            nativeRelease.execute(() -> client.execute(() -> runtime.invalidatePreviewAccessories(oldRegistration)));
            runtime.closeScreen();
            drainRefreshQueues(worker, client);
            runtime.reopen(ScreenDestination.PROVIDERS);
            drainRefreshQueues(worker, client);
            var newFailure = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 739), 1,
                    com.naocraftlab.skins.client.PreviewAccessories.Availability.FAILED, false, false, PreviewRenderer.LiveReason.AVAILABLE);
            var current = new AtomicReference<java.util.function.Consumer<com.naocraftlab.skins.client.PreviewAccessories>>();
            client.execute(() -> current.set(runtime.observePreviewAccessories(refreshView(runtime, client).previews().get(0), () -> newFailure)));
            drainRefreshQueues(worker, client);
            assertTrue(hasFailureCaption(refreshView(runtime, client)), "Reopened source has its own current FAILED");
            old.get().accept(oldFailure);
            drainRefreshQueues(worker, client);
            assertTrue(hasFailureCaption(refreshView(runtime, client)), "Old callback cannot alter replacement");
            nativeRelease.runFirst();
            drainRefreshQueues(worker, client);
            assertTrue(hasFailureCaption(refreshView(runtime, client)), "Old host release must not clear the new accessory registration");
            current.get().accept(newFailure);
            drainRefreshQueues(worker, client);
            assertTrue(hasFailureCaption(refreshView(runtime, client)));
        }
    }

    @Test
    void accessoryReleaseRequiresCurrentIdentityAndDoesNotResurrectAfterReleaseOrClose(@TempDir Path directory) throws Exception {
        try (var fixture = new ProviderFailureFixture(directory, false, CLIENT, Runnable::run)) {
            var runtime = fixture.runtime;
            var preview = runtime.view(854, 480, 0, 0).previews().get(0);
            var failure = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 740), 1,
                    com.naocraftlab.skins.client.PreviewAccessories.Availability.FAILED, false, false, PreviewRenderer.LiveReason.AVAILABLE);
            var registration = runtime.observePreviewAccessories(preview, () -> failure);
            var history = new ArrayList<Boolean>();
            runtime.subscribe(ignored -> history.add(hasFailureCaption(runtime.view(854, 480, 0, 0))));
            int before = history.size();
            runtime.invalidatePreviewAccessories(null);
            runtime.invalidatePreviewAccessories(ignored -> {});
            assertTrue(hasFailureCaption(runtime.view(854, 480, 0, 0)));
            assertEquals(before, history.size(), "Null/foreign release cannot publish");
            runtime.invalidatePreviewAccessories(registration);
            assertFalse(hasFailureCaption(runtime.view(854, 480, 0, 0)));
            assertEquals(before + 1, history.size(), "Current release publishes the cleared projection once");
            assertFalse(history.get(history.size() - 1));
            before = history.size();
            runtime.invalidatePreviewAccessories(registration);
            registration.accept(failure);
            assertFalse(hasFailureCaption(runtime.view(854, 480, 0, 0)));
            assertEquals(before, history.size(), "Repeated release/callback cannot publish or resurrect");
            var replacement = runtime.observePreviewAccessories(preview, () -> failure);
            assertTrue(hasFailureCaption(runtime.view(854, 480, 0, 0)));
            runtime.closeScreen();
            before = history.size();
            runtime.invalidatePreviewAccessories(replacement);
            replacement.accept(failure);
            assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
            assertFalse(hasFailureCaption(runtime.view(854, 480, 0, 0)));
            assertEquals(before, history.size());
            assertEquals(0, fixture.operations.applyCalls);
            assertEquals(0, fixture.operations.skinPreviewCalls);
        }
    }

    @Test
    void accessoryReleasePublicationReentrancyKeepsReplacementRegistration(@TempDir Path directory) throws Exception {
        try (var fixture = new ProviderFailureFixture(directory, false, CLIENT, Runnable::run)) {
            var runtime = fixture.runtime;
            var preview = runtime.view(854, 480, 0, 0).previews().get(0);
            var failure = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 741), 1,
                    com.naocraftlab.skins.client.PreviewAccessories.Availability.FAILED, false, false, PreviewRenderer.LiveReason.AVAILABLE);
            var registration = runtime.observePreviewAccessories(preview, () -> failure);
            var armed = new AtomicBoolean();
            var replacement = new AtomicReference<java.util.function.Consumer<com.naocraftlab.skins.client.PreviewAccessories>>();
            var history = new ArrayList<Boolean>();
            runtime.subscribe(ignored -> {
                boolean caption = hasFailureCaption(runtime.view(854, 480, 0, 0));
                history.add(caption);
                if (!caption && armed.getAndSet(false)) {
                    replacement.set(runtime.observePreviewAccessories(preview, () -> failure));
                    runtime.invalidatePreviewAccessories(registration);
                }
            });
            armed.set(true);
            runtime.invalidatePreviewAccessories(registration);
            org.junit.jupiter.api.Assertions.assertNotNull(replacement.get());
            assertTrue(hasFailureCaption(runtime.view(854, 480, 0, 0)), "Reentrant replacement survives old cleanup");
            assertEquals(List.of(true, false, true), history);
            int before = history.size();
            runtime.invalidatePreviewAccessories(registration);
            registration.accept(failure);
            assertEquals(before, history.size());
            runtime.invalidatePreviewAccessories(replacement.get());
            assertFalse(hasFailureCaption(runtime.view(854, 480, 0, 0)));
            replacement.get().accept(failure);
            assertFalse(hasFailureCaption(runtime.view(854, 480, 0, 0)));
            assertEquals(0, fixture.operations.applyCalls);
            assertEquals(0, fixture.operations.skinPreviewCalls);
        }
    }

    private static boolean hasFailureCaption(ViewSpec view) {
        return view.texts().stream().anyMatch(text -> text.id().equals("editor.preview.error")
                && text.message().equals(UiMessage.error("nclskins.editor.cpm_preview_failed")));
    }

    private static final class ProviderFailureFixture implements AutoCloseable {
        final FakeOperations operations = new FakeOperations();
        final ClientRuntime runtime;
        final ProviderSkin cpm;
        final ProviderSkin ordinary;
        final AtomicBoolean cpmPresent = new AtomicBoolean(true);
        ProviderFailureFixture(Path directory, boolean selected, ClientExecutor client, Executor worker) throws Exception {
            var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);
            var store = new NclSkinsStorage(directory, new PngValidator(), clock);
            store.initialize();
            byte[] carrier = Files.readAllBytes(Path.of("../core/src/test/resources/cpm/classic-a.png"));
            byte[] clean = skinPng();
            cpm = new ProviderSkin(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(carrier)), SkinVariant.CLASSIC);
            ordinary = new ProviderSkin(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(clean)), SkinVariant.CLASSIC);
            Files.write(store.assetPath(cpm.sha256()), carrier);
            Files.write(store.assetPath(ordinary.sha256()), clean);
            var assets = List.of(new SkinAsset(TestFixtures.CLASSIC_ID, "Synthetic CPM", cpm.sha256(), SkinVariant.CLASSIC,
                            SkinSource.IMPORTED, clock.instant(), clock.instant()),
                    new SkinAsset(TestFixtures.SLIM_ID, "Synthetic ordinary", ordinary.sha256(), SkinVariant.CLASSIC,
                            SkinSource.IMPORTED, clock.instant(), clock.instant()));
            operations.account = store.updateAccount(TestFixtures.ACCOUNT_ID, current -> new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION, TestFixtures.ACCOUNT_ID, assets, List.of(), List.of(), clock.instant()));
            var skin = new com.naocraftlab.skins.core.provider.ProviderChannel<ProviderSkin>(
                    List.of(BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFT),
                    ProviderObservation.observed(selected ? ordinary : cpm), ProviderObservation.observed(selected ? cpm : ordinary),
                    0, 0, null, new com.naocraftlab.skins.core.provider.ProviderDelivery(0, 0,
                    com.naocraftlab.skins.core.provider.ProviderDelivery.Status.IDLE));
            operations.providers = new AppearanceProviders(skin, operations.providers.cape()
                    .disable(BuiltinProvider.OFFLINE).disable(BuiltinProvider.MINECRAFT));
            GameSessionTokenSource tokens = new GameSessionTokenSource() {
                public SessionIdentity currentSession() { return operations.sessionIdentity(); }
                public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) { throw new AssertionError("No token access"); }
            };
            var api = (com.naocraftlab.skins.core.api.ProfileApi) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{com.naocraftlab.skins.core.api.ProfileApi.class},
                    (proxy, method, args) -> { throw new AssertionError("No network " + method.getName()); });
            operations.evidenceProducer = TestClientGraph.operations(tokens, api, store,
                    (com.naocraftlab.skins.client.BundledSkinSource) ignored -> { throw new AssertionError("No catalog warmup"); }, clock);
            runtime = new ClientRuntime(new DefaultRuntimeServices(), operations, client, CANCELLED_PICKER, worker, TEXT,
                    Optional.empty(), DiagnosticSinks.discarding());
            runtime.useSkinExtensionEnvironmentSource(() -> new com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.Snapshot(1, Map.of(), cpmPresent.get()));
            runtime.reopen(ScreenDestination.PROVIDERS);
        }
        public void close() { runtime.close(); operations.evidenceProducer.close(); }
    }

    @Test
    void cpmEmbeddedInspectionIsTransientAndRejectsLiveLossAndLateCallbacks() {
        DuplicateFixture fixture = duplicateFixture(true, true);
        FakeOperations operations = new FakeOperations();
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM);
        operations.account = fixture.account();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.useSkinExtensionEnvironmentSource(() -> new com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.Snapshot(1, Map.of(), true));
        runtime.initialize();
        runtime.dispatchWidget("gallery.preset." + fixture.source().id() + ".edit");
        runtime.dispatchWidget("editor.tab.cape");
        var before = runtime.snapshot().editor().orElseThrow();
        var preview = runtime.view(854, 480, 0, 0).previews().get(0);
        var live = new java.util.concurrent.atomic.AtomicReference<>(new com.naocraftlab.skins.client.PreviewAccessories(
                new UUID(0, 901), 1, com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED,
                false, true, PreviewRenderer.LiveReason.AVAILABLE));
        var observer = runtime.observePreviewAccessories(preview, live::get);
        var view = runtime.view(854, 480, 0, 0);
        String card = view.widgets().stream().filter(w -> w.id().startsWith("editor.cape_item.CPM.")).findFirst().orElseThrow().id();
        runtime.dispatchWidget(card);
        var inspected = runtime.snapshot().editor().orElseThrow();
        assertEquals(before.capeCatalog().offline(), inspected.capeCatalog().offline());
        assertEquals(before.capeCatalog().minecraft(), inspected.capeCatalog().minecraft());
        assertEquals(before.skin(), inspected.skin());
        assertTrue(runtime.view(854, 480, 0, 0).previews().get(0).nativeAccessories());
        assertFalse(runtime.view(854, 480, 0, 0).widget("preview.inspection").isPresent());
        runtime.dispatchWidget("editor.tab.cape");
        assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().inspected());
        runtime.dispatchWidget(card);
        live.set(new com.naocraftlab.skins.client.PreviewAccessories(live.get().scope(), 1,
                live.get().availability(), false, true, PreviewRenderer.LiveReason.WORLD_REQUIRED));
        view = runtime.view(854, 480, 0, 0);
        assertFalse(view.previews().get(0).nativeAccessories());
        assertTrue(view.widget(card).orElseThrow().enabled());
        assertFalse(view.widget(card).orElseThrow().activationEnabled());
        assertTrue(view.navigationNode(card).orElseThrow().activationActionId().isEmpty());
        assertFalse(runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, card));
        runtime.dispatchWidget(card, false, InteractionOrigin.POINTER);
        runtime.dispatchWidget(card, false, InteractionOrigin.KEYBOARD);
        assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().inspected());
        live.set(new com.naocraftlab.skins.client.PreviewAccessories(live.get().scope(), 1,
                live.get().availability(), false, true, PreviewRenderer.LiveReason.AVAILABLE));
        assertFalse(runtime.view(854, 480, 0, 0).widget("preview.inspection").isPresent());
        assertTrue(runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, card));
        runtime.invalidatePreviewAccessories(observer);
        observer.accept(live.get());
        assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().inspected());
        assertFalse(runtime.view(854, 480, 0, 0).previews().get(0).nativeAccessories());
        live.set(com.naocraftlab.skins.client.PreviewAccessories.unavailable(PreviewRenderer.LiveReason.WORLD_REQUIRED));
        assertFalse(runtime.view(854, 480, 0, 0).widgets().stream().anyMatch(w -> w.id().contains("EMBEDDED")));
        runtime.dispatchWidget("editor.cancel");
        observer.accept(new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 901), 2,
                com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED, true, true, PreviewRenderer.LiveReason.AVAILABLE));
        assertTrue(runtime.snapshot().editor().isEmpty());
        assertEquals(0, operations.applyCalls);
        assertEquals(fixture.account(), runtime.snapshot().account().orElseThrow());
        runtime.close();
    }

    @Test
    void cpmInspectSavePayloadAndLateCallbackStayScoped() {
        DuplicateFixture fixture = duplicateFixture(true, true);
        FakeOperations operations = new FakeOperations();
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM);
        operations.account = fixture.account();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.useSkinExtensionEnvironmentSource(() -> new com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.Snapshot(1, Map.of(), true));
        runtime.initialize();
        runtime.dispatchWidget("gallery.preset." + fixture.source().id() + ".edit");
        runtime.dispatchWidget("editor.tab.cape");
        var draft = runtime.snapshot().editor().orElseThrow();
        var capability = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 903), 1,
                com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED, true, true,
                PreviewRenderer.LiveReason.AVAILABLE);
        var callback = runtime.observePreviewAccessories(runtime.view(854, 480, 0, 0).previews().get(0), () -> capability);
        String card = runtime.view(854, 480, 0, 0).widgets().stream()
                .filter(w -> w.id().startsWith("editor.cape_item.CPM.")).findFirst().orElseThrow().id();
        runtime.dispatchWidget(card);
        assertEquals(draft.saveRequest(), runtime.snapshot().editor().orElseThrow().saveRequest());
        assertEquals(draft.preview(), runtime.snapshot().editor().orElseThrow().preview());
        runtime.dispatchWidget("editor.save");
        assertEquals(draft.saveRequest(), operations.lastEditorSaveRequest);
        assertTrue(runtime.snapshot().editor().isEmpty());
        callback.accept(capability);
        assertTrue(runtime.snapshot().editor().isEmpty());
        assertEquals(0, operations.applyCalls);
        runtime.close();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"direct", "queued", "failure", "null", "worker-rejected", "client-rejected"})
    void cpmRefreshPreservesEveryPublishedInspectionWithOneLiveRegistration(String mode) {
        boolean queued = !mode.equals("direct");
        FakeOperations operations = new FakeOperations();
        operations.providers = new AppearanceProviders(operations.providers.skin(), operations.providers.cape().initializeCpm());
        QueuedExecutor worker = new QueuedExecutor();
        TrackingClientExecutor client = new TrackingClientExecutor();
        ClientRuntime runtime = queued
                ? new ClientRuntime(new DefaultRuntimeServices(), operations, client,
                        CANCELLED_PICKER, worker, TEXT, Optional.empty(), DiagnosticSinks.discarding())
                : runtime(operations, Runnable::run, Optional.empty());
        runtime.useSkinExtensionEnvironmentSource(() -> new com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.Snapshot(1, Map.of(), true));
        runtime.initialize();
        drainRefreshQueues(worker, client);
        runtime.dispatchWidget("gallery.providers");
        drainRefreshQueues(worker, client);
        runtime.dispatchWidget("providers.tab.CAPE");
        drainRefreshQueues(worker, client);
        var live = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 902), 1,
                com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED, true, true,
                PreviewRenderer.LiveReason.AVAILABLE);
        if (queued) {
            client.execute(() -> runtime.observePreviewAccessories(runtime.view(320, 240, 0, 0).previews().get(0), () -> live));
            client.runFirst();
        } else runtime.observePreviewAccessories(runtime.view(320, 240, 0, 0).previews().get(0), () -> live);
        runtime.dispatchWidget("providers.row.CPM");
        drainRefreshQueues(worker, client);
        assertCpmRefreshInspection(refreshView(runtime, client));
        var delivered = new ArrayList<ViewSpec>();
        runtime.subscribe(snapshot -> delivered.add(runtime.view(320, 240, 0, 0)));
        drainRefreshQueues(worker, client);
        operations.refreshSourceFailure = mode.equals("failure");
        operations.refreshSourceNull = mode.equals("null");
        worker.reject = mode.equals("worker-rejected");
        runtime.dispatchWidget("providers.refresh");
        while (!client.tasks.isEmpty()) client.runFirst();
        assertCpmRefreshInspection(refreshView(runtime, client));
        runtime.dispatchWidget("providers.refresh");
        while (!client.tasks.isEmpty()) client.runFirst();
        client.reject = mode.equals("client-rejected");
        while (worker.size() > 0) worker.runFirst();
        assertCpmRefreshInspection(refreshView(runtime, client));
        client.reject = false;
        worker.reject = false;
        runtime.tick();
        drainRefreshQueues(worker, client);
        assertCpmRefreshInspection(refreshView(runtime, client));
        assertFalse(delivered.isEmpty());
        delivered.forEach(ClientRuntimeTest::assertCpmRefreshInspection);
        assertTrue(refreshView(runtime, client).widget("providers.refresh").orElseThrow().enabled());
        assertTrue(operations.providerRefreshRequests.stream().allMatch(request -> request.equals(
                ProviderRefreshRequest.one(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM))));
        assertEquals(0, operations.optifineRefreshes);
        assertEquals(0, operations.skinMcRefreshes);
        assertEquals(0, operations.applyCalls);
        runtime.close();
        drainRefreshQueues(worker, client);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"row", "tab", "live", "pending", "failed", "blocked", "empty", "mod", "disable", "remove", "reopen", "resources", "world", "session", "reload", "model", "source", "terminal-close"})
    void cpmRefreshStillRejectsRealRetirementWithoutRestoringOldSelection(String transition) {
        FakeOperations operations = new FakeOperations();
        operations.providers = new AppearanceProviders(operations.providers.skin(), operations.providers.cape().initializeCpm());
        QueuedExecutor worker = new QueuedExecutor();
        var environment = new java.util.concurrent.atomic.AtomicBoolean(true);
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        runtime.useSkinExtensionEnvironmentSource(() -> new com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.Snapshot(1, Map.of(), environment.get()));
        runtime.initialize();
        while (worker.size() > 0) worker.runFirst();
        runtime.dispatchWidget("gallery.providers");
        while (worker.size() > 0) worker.runFirst();
        runtime.dispatchWidget("providers.tab.CAPE");
        var first = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 902), 1,
                com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED, true, true,
                PreviewRenderer.LiveReason.AVAILABLE);
        var live = new java.util.concurrent.atomic.AtomicReference<>(first);
        var initialPreview = runtime.view(320, 240, 0, 0).previews().get(0);
        String source = runtime.previewBodySource(initialPreview);
        var callback = runtime.observePreviewAccessories(initialPreview, () -> source.equals(runtime.previewBodySource(initialPreview))
                ? live.get() : com.naocraftlab.skins.client.PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LOADING));
        runtime.dispatchWidget("providers.row.CPM");
        assertCpmRefreshInspection(runtime.view(320, 240, 0, 0));
        runtime.dispatchWidget("providers.refresh");
        switch (transition) {
            case "row" -> runtime.dispatchWidget("providers.row.OFFLINE");
            case "tab" -> runtime.dispatchWidget("providers.tab.SKIN");
            case "live" -> live.set(com.naocraftlab.skins.client.PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LIVE_UNAVAILABLE));
            case "pending" -> live.set(com.naocraftlab.skins.client.PreviewAccessories.unavailable(PreviewRenderer.LiveReason.LOADING));
            case "failed", "blocked" -> live.set(new com.naocraftlab.skins.client.PreviewAccessories(first.scope(), first.generation(),
                    com.naocraftlab.skins.client.PreviewAccessories.Availability.valueOf(transition.toUpperCase(java.util.Locale.ROOT)),
                    false, false, PreviewRenderer.LiveReason.LIVE_UNAVAILABLE));
            case "empty" -> live.set(new com.naocraftlab.skins.client.PreviewAccessories(first.scope(), first.generation(),
                    first.availability(), false, false, PreviewRenderer.LiveReason.AVAILABLE));
            case "mod" -> { environment.set(false); runtime.resourcesReloaded(); }
            case "disable" -> runtime.dispatchWidget("providers.remove.CPM");
            case "remove" -> runtime.dispatchWidget("providers.remove.CPM");
            case "reopen" -> {
                runtime.closeScreen(); runtime.reopen();
                while (worker.size() > 0) worker.runFirst();
                runtime.dispatchWidget("gallery.providers");
                while (worker.size() > 0) worker.runFirst();
                runtime.dispatchWidget("providers.tab.CAPE");
            }
            case "resources" -> runtime.resourcesReloaded();
            case "world" -> runtime.importWorldChanged();
            case "terminal-close" -> runtime.close();
            case "reload" -> {
                runtime.dispatchWidget("gallery.providers");
                worker.tasks.remove(worker.tasks.size() - 1).run();
            }
            case "model", "source" -> {
                operations.providers = new AppearanceProviders(operations.providers.skin().select(1,
                        new ProviderSkin("a".repeat(64), transition.equals("model") ? SkinVariant.SLIM : SkinVariant.CLASSIC)),
                        operations.providers.cape());
                runtime.dispatchWidget("gallery.providers");
                worker.tasks.remove(worker.tasks.size() - 1).run();
            }
            case "session" -> {
                var original = operations.session;
                operations.session = new SessionValidation(SessionStatus.OFFLINE_OR_INVALID,
                        new GameSessionTokenSource.SessionIdentity(new UUID(0, 903), "SyntheticB"),
                        null, (SessionFailureContext) null, "synthetic switch");
                runtime.tick(); operations.session = original; runtime.tick();
            }
            default -> throw new AssertionError(transition);
        }
        while (worker.size() > 0) worker.runFirst();
        if (transition.equals("terminal-close")) {
            assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
            callback.accept(first);
            assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
            assertTrue(operations.providerRefreshRequests.isEmpty());
            return;
        }
        var view = runtime.view(320, 240, 0, 0);
        if (transition.equals("empty")) {
            assertEquals("nclskins.providers.refresh", view.widget("providers.refresh").orElseThrow().label().key());
            assertFalse(view.previews().get(0).nativeAccessories());
        } else if (transition.equals("tab")) {
            assertEquals("nclskins.providers.refresh_all", view.widget("providers.refresh").orElseThrow().label().key());
            runtime.dispatchWidget("providers.tab.CAPE");
            callback.accept(first);
            assertEquals("nclskins.providers.refresh_all", runtime.view(320, 240, 0, 0).widget("providers.refresh").orElseThrow().label().key());
        } else {
            assertFalse(view.previews().get(0).nativeAccessories(), transition);
            callback.accept(first);
            if (!transition.equals("row")) assertFalse(runtime.view(320, 240, 0, 0).previews().get(0).nativeAccessories(), transition);
        }
        assertTrue(runtime.view(320, 240, 0, 0).widget("providers.refresh").orElseThrow().enabled(), transition);
        assertEquals(0, operations.optifineRefreshes);
        assertEquals(0, operations.skinMcRefreshes);
        assertEquals(0, operations.applyCalls);
        runtime.close();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"throw", "null-future", "null-result", "exceptional", "rejected-null"})
    void refreshSupplierTerminalsUseClientHandoffAndReleaseTheirOwnPendingToken(String terminal) throws Exception {
        FakeOperations operations = new FakeOperations();
        QueuedExecutor worker = new QueuedExecutor();
        TrackingClientExecutor client = new TrackingClientExecutor();
        ClientRuntime runtime = new ClientRuntime(new DefaultRuntimeServices(), operations, client,
                CANCELLED_PICKER, worker, TEXT, Optional.empty(), DiagnosticSinks.discarding());
        runtime.initialize(); drainRefreshQueues(worker, client);
        runtime.dispatchWidget("gallery.providers"); drainRefreshQueues(worker, client);
        var future = new CompletableFuture<ProviderRefreshResult>();
        var submit = ClientRuntime.class.getDeclaredMethod("submitProviderRefresh", UiMessage.class, java.util.function.Supplier.class);
        submit.setAccessible(true);
        client.execute(() -> {
            try {
                submit.invoke(runtime, UiMessage.info("nclskins.providers.refresh"),
                        (java.util.function.Supplier<CompletableFuture<ProviderRefreshResult>>) () -> {
                            if (terminal.equals("throw")) throw new IllegalStateException("Synthetic admission failure");
                            return terminal.equals("null-future") ? null : future;
                        });
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        client.runFirst();
        if (!terminal.equals("throw") && !terminal.equals("null-future")) {
            assertFalse(refreshView(runtime, client).widget("providers.refresh").orElseThrow().enabled());
            client.reject = terminal.equals("rejected-null");
            if (terminal.equals("exceptional")) future.completeExceptionally(new IllegalStateException("Synthetic completion failure"));
            else future.complete(null);
            assertFalse(refreshView(runtime, client).widget("providers.refresh").orElseThrow().enabled());
            client.reject = false;
            runtime.tick(); drainRefreshQueues(worker, client);
        }
        assertTrue(refreshView(runtime, client).widget("providers.refresh").orElseThrow().enabled());
        assertEquals(UiMessage.Severity.ERROR, runtime.snapshot().status().severity());
        runtime.close(); drainRefreshQueues(worker, client);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"same-generation", "reopen"})
    void oldRefreshTerminalCannotClearNewSerialOrReplaceItsFeedback(String ordering) throws Exception {
        FakeOperations operations = new FakeOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        runtime.initialize(); while (worker.size() > 0) worker.runFirst();
        runtime.dispatchWidget("gallery.providers"); while (worker.size() > 0) worker.runFirst();
        var first = new CompletableFuture<ProviderRefreshResult>();
        var next = new CompletableFuture<ProviderRefreshResult>();
        var submit = ClientRuntime.class.getDeclaredMethod("submitProviderRefresh", UiMessage.class, java.util.function.Supplier.class);
        submit.setAccessible(true);
        submit.invoke(runtime, UiMessage.info("nclskins.providers.refresh"),
                (java.util.function.Supplier<CompletableFuture<ProviderRefreshResult>>) () -> first);
        var armed = new java.util.concurrent.atomic.AtomicBoolean(ordering.equals("same-generation"));
        runtime.subscribe(snapshot -> {
            if (snapshot.status().key().equals("nclskins.error.save") && armed.compareAndSet(true, false)) {
                try { submit.invoke(runtime, UiMessage.info("nclskins.status.loading"),
                        (java.util.function.Supplier<CompletableFuture<ProviderRefreshResult>>) () -> next); }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            }
        });
        if (ordering.equals("reopen")) {
            runtime.closeScreen(); runtime.reopen();
            while (worker.size() > 0) worker.runFirst();
            runtime.dispatchWidget("gallery.providers"); while (worker.size() > 0) worker.runFirst();
            submit.invoke(runtime, UiMessage.info("nclskins.status.loading"),
                    (java.util.function.Supplier<CompletableFuture<ProviderRefreshResult>>) () -> next);
        }
        first.complete(null);
        assertEquals("nclskins.status.loading", runtime.snapshot().status().key());
        assertFalse(runtime.view(320, 240, 0, 0).widget("providers.refresh").orElseThrow().enabled());
        next.complete(null);
        assertEquals("nclskins.error.save", runtime.snapshot().status().key());
        assertTrue(runtime.view(320, 240, 0, 0).widget("providers.refresh").orElseThrow().enabled());
        runtime.close();
    }

    private static ViewSpec refreshView(ClientRuntime runtime, TrackingClientExecutor client) {
        boolean previous = client.clientThread;
        client.clientThread = true;
        try { return runtime.view(320, 240, 0, 0); }
        finally { client.clientThread = previous; }
    }

    private static void assertCpmRefreshInspection(ViewSpec view) {
        assertEquals("nclskins.providers.refresh", view.widget("providers.refresh").orElseThrow().label().key());
        assertTrue(view.previews().get(0).nativeAccessories(), "Refresh must not retire the registered live inspection");
    }

    private static void drainRefreshQueues(QueuedExecutor worker, TrackingClientExecutor client) {
        while (!client.tasks.isEmpty() || worker.size() > 0) {
            if (!client.tasks.isEmpty()) client.runFirst();
            else worker.runFirst();
        }
    }

    @Test
    void cpmProviderCanConfigureWorldlessButOnlyInspectCurrentLiveBundle() {
        FakeOperations operations = new FakeOperations();
        operations.providers = new AppearanceProviders(operations.providers.skin(), operations.providers.cape().initializeCpm());
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.useSkinExtensionEnvironmentSource(() -> new com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.Snapshot(1, Map.of(), true));
        runtime.initialize();
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.tab.CAPE");
        var preview = runtime.view(320, 240, 0, 0).previews().get(0);
        var live = new java.util.concurrent.atomic.AtomicReference<>(com.naocraftlab.skins.client.PreviewAccessories.unavailable(
                PreviewRenderer.LiveReason.WORLD_REQUIRED));
        runtime.observePreviewAccessories(preview, live::get);
        var view = runtime.view(320, 240, 0, 0);
        assertTrue(view.widget("providers.row.CPM").orElseThrow().enabled());
        assertFalse(view.widget("providers.row.CPM").orElseThrow().activationEnabled());
        assertTrue(view.widget("providers.remove.CPM").orElseThrow().activationEnabled());
        runtime.dispatchWidget("providers.row.CPM");
        assertFalse(runtime.view(320, 240, 0, 0).widget("preview.inspection").isPresent());
        live.set(new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 902), 1,
                com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED, false, false,
                PreviewRenderer.LiveReason.AVAILABLE));
        runtime.view(320, 240, 0, 0);
        runtime.dispatchWidget("providers.row.CPM");
        assertFalse(runtime.view(320, 240, 0, 0).widget("preview.inspection").isPresent());
        assertFalse(runtime.view(320, 240, 0, 0).previews().get(0).nativeAccessories());
        live.set(new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 902), 2,
                com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED, true, true,
                PreviewRenderer.LiveReason.AVAILABLE));
        assertFalse(runtime.view(320, 240, 0, 0).widget("preview.inspection").isPresent());
        assertTrue(runtime.view(320, 240, 0, 0).widget("providers.row.CPM").orElseThrow().hint().isEmpty());
        runtime.dispatchWidget("providers.row.CPM");
        assertTrue(runtime.view(320, 240, 0, 0).previews().get(0).nativeAccessories());
        runtime.dispatchWidget("providers.tab.CAPE");
        assertFalse(runtime.view(320, 240, 0, 0).widget("preview.inspection").isPresent());
        assertEquals(operations.providers, runtime.snapshot().providers());
        assertEquals(0, operations.applyCalls);
        runtime.close();
    }

    @Test
    void readySavedProviderStateWarmsSkinWithoutFullCapePoster() {
        FakeOperations operations = new FakeOperations();
        String skinHash = "a".repeat(64);
        String capeHash = "b".repeat(64);
        operations.providers = new AppearanceProviders(
                operations.providers.skin().select(1,
                        new ProviderSkin(skinHash, SkinVariant.CLASSIC)),
                operations.providers.cape().select(1,
                        new ProviderCape("saved", capeHash)));
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        assertEquals(List.of("skin:" + skinHash), operations.providerPreviewLoads);
        runtime.loadProviderTexture(new ViewSpec.ProviderTexture(skinHash, true, false)).join();
        assertEquals(List.of("skin:" + skinHash), operations.providerPreviewLoads,
                "opening the Providers screen must reuse the saved skin bytes");
        runtime.close();
    }

    @Test
    void savedCapePosterIsAvailableOnFirstReadyBeforeAnotherWorkerRun() {
        FakeOperations operations = new FakeOperations();
        String capeHash = "b".repeat(64);
        operations.providers = new AppearanceProviders(operations.providers.skin(),
                operations.providers.cape().select(1, new ProviderCape("saved", capeHash)));
        operations.providerPosters = Map.of(capeHash,
                new ProviderCapePoster(1, 1, new byte[]{1, 2, 3, (byte) 255},
                        "0".repeat(64), CapePreparationQueue.tryReserveCpu(4)));
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        runtime.initialize();
        worker.runFirst();
        var texture = new ViewSpec.ProviderTexture(capeHash, false, false);
        assertTrue(runtime.loadProviderThumbnail(texture).isDone(),
                "first READY must already contain the saved cape thumbnail");
        assertEquals(1, runtime.loadProviderThumbnail(texture).join().orElseThrow().rgba8()[0]);
        assertTrue(operations.providerPreviewLoads.isEmpty(),
                "the bounded row thumbnail must not read full player cape bytes");
        runtime.close();
    }

    @Test
    void savedSkinIsAvailableOnFirstReadyBeforeAnotherWorkerRun() {
        FakeOperations operations = new FakeOperations();
        String skinHash = "a".repeat(64);
        operations.providers = new AppearanceProviders(
                operations.providers.skin().select(1,
                        new ProviderSkin(skinHash, SkinVariant.CLASSIC)),
                operations.providers.cape());
        operations.savedSkinPreviews = Map.of("provider:skin:" + skinHash,
                new byte[] {1, 2, 3});
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        runtime.initialize();
        worker.runFirst();
        var texture = new ViewSpec.ProviderTexture(skinHash, true, false);
        assertTrue(runtime.loadProviderTexture(texture).isDone(),
                "first READY must contain saved skin preview bytes");
        assertEquals(1, runtime.loadProviderTexture(texture).join().orElseThrow()[0]);
        assertTrue(operations.providerPreviewLoads.isEmpty(),
                "opening or switching the Providers tab must reuse warmed skin bytes");
        runtime.close();
    }

    @Test
    void discardedInitializationReleasesUninstalledSavedCapePoster() {
        FakeOperations operations = new FakeOperations();
        String capeHash = "b".repeat(64);
        operations.providerPosters = Map.of(capeHash,
                new ProviderCapePoster(1, 1, new byte[]{1, 2, 3, (byte) 255},
                        "0".repeat(64), CapePreparationQueue.tryReserveCpu(4)));
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        runtime.initialize();
        runtime.closeScreen();
        worker.runFirst();
        runtime.close();
        try (CapePreparationQueue.Reservation full =
                CapePreparationQueue.tryReserveCpu(64L * 1024 * 1024)) {
            assertTrue(full != null, "stale initialization must release its poster quota");
        }
    }

    @Test
    void disabledProviderObservationIsNotWarmedAcrossChannels() {
        String skinHash = "a".repeat(64);
        String capeHash = "b".repeat(64);
        var skin = AppearanceProviders.initial().skin().select(1,
                new ProviderSkin(skinHash, SkinVariant.CLASSIC));
        var cape = AppearanceProviders.initial().cape().select(1,
                new ProviderCape("saved", capeHash));
        assertTrue(skin.disable(BuiltinProvider.OFFLINE)
                .observation(BuiltinProvider.OFFLINE).value() != null);
        assertTrue(cape.disable(BuiltinProvider.OFFLINE)
                .observation(BuiltinProvider.OFFLINE).value() != null);

        FakeOperations capeOnly = new FakeOperations();
        capeOnly.providers = new AppearanceProviders(skin.disable(BuiltinProvider.OFFLINE), cape);
        ClientRuntime capeRuntime = runtime(capeOnly, Runnable::run, Optional.empty());
        capeRuntime.initialize();
        assertTrue(capeOnly.providerPreviewLoads.isEmpty(),
                "full-size cape bytes are not a bounded provider-row poster");
        capeRuntime.close();

        FakeOperations skinOnly = new FakeOperations();
        skinOnly.providers = new AppearanceProviders(skin, cape.disable(BuiltinProvider.OFFLINE));
        ClientRuntime skinRuntime = runtime(skinOnly, Runnable::run, Optional.empty());
        skinRuntime.initialize();
        assertEquals(List.of("skin:" + skinHash), skinOnly.providerPreviewLoads);
        skinRuntime.close();
    }

    @Test
    void externalCatalogEditorCancelReturnsToCatalogAndSaveExitsRoot() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.reopen(ScreenDestination.SKIN_CATALOG);
        runtime.dispatchWidget("add.catalog.skin:minecraft:steve");
        assertTrue(runtime.snapshot().editor().isPresent());
        runtime.dispatchWidget("editor.cancel");
        assertEquals("add_source", runtime.view(854, 480, 0, 0).screenId());
        runtime.dispatchWidget("add.catalog.skin:minecraft:steve");
        runtime.dispatchWidget("editor.save");
        assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
    }

    @Test
    void allExternalEntriesGateBothProviderChannelsWithoutOverwritingAddPreference() {
        for (ScreenDestination destination : ScreenDestination.values()) {
            for (int mask = 0; mask < 4; mask++) {
                FakeOperations operations = new FakeOperations();
                operations.uiPreferences = operations.uiPreferences.withSelectedAddSourceTab(AddSourceTab.FILE);
                for (AppearanceProviders.Component component : AppearanceProviders.Component.values()) {
                    if ((mask & (1 << component.ordinal())) == 0) {
                        operations.providers = operations.providers.disable(component, BuiltinProvider.OFFLINE)
                                .disable(component, BuiltinProvider.MINECRAFT);
                    }
                }
                ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
                runtime.reopen(destination);
                if (mask != 3 || destination == ScreenDestination.PROVIDERS) {
                    assertEquals("providers", runtime.view(854, 480, 0, 0).screenId());
                    assertEquals(AddSourceTab.FILE, operations.uiPreferences.selectedAddSourceTab());
                    runtime.escapePressed();
                    assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
                } else {
                    assertNotEquals("providers", runtime.view(854, 480, 0, 0).screenId());
                }
            }
        }
    }

    @Test
    void repeatedDestinationDoesNotReplaceActiveDraft() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(2);
        operations.activePresetId = Optional.of(operations.account.presets().get(0).id());
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.reopen(ScreenDestination.ACTIVE_EDITOR);
        PresetEditorModel draft = runtime.snapshot().editor().orElseThrow();
        runtime.reopen(ScreenDestination.SKIN_IMPORT);
        assertSame(draft, runtime.snapshot().editor().orElseThrow());
    }

    @Test
    void closingDuringExternalInitializationDiscardsDestination() {
        FakeOperations operations = new FakeOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        runtime.reopen(ScreenDestination.SKIN_IMPORT);
        assertEquals("loading", runtime.view(854, 480, 0, 0).screenId());
        runtime.escapePressed();
        while (worker.size() > 0) worker.runFirst();
        assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
        assertTrue(runtime.snapshot().addSource().isEmpty());
    }

    @Test
    void externalDestinationsOpenTheirRootAndReturnWithoutGallery() {
        for (ScreenDestination destination : ScreenDestination.values()) {
            FakeOperations operations = new FakeOperations();
            operations.account = TestFixtures.account(2);
            operations.activePresetId = Optional.of(operations.account.presets().get(0).id());
            ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
            runtime.reopen(destination);
            String expected = switch (destination) {
                case GALLERY -> "gallery";
                case PROVIDERS -> "providers";
                case ACTIVE_EDITOR -> "preset_editor";
                case SKIN_CATALOG, SKIN_IMPORT -> "add_source";
            };
            assertEquals(expected, runtime.view(854, 480, 0, 0).screenId(), destination.name());
            if (destination == ScreenDestination.SKIN_IMPORT) {
                assertEquals(AddSourceTab.FILE, runtime.snapshot().addSource().orElseThrow().selectedTab());
            }
            runtime.escapePressed();
            assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle(), destination.name());
        }
    }

    @Test
    void externalEditorWithoutActivePresetAlwaysUsesImport() {
        for (AddSourceTab remembered : AddSourceTab.values()) {
            for (Optional<UUID> active : List.of(Optional.<UUID>empty(), Optional.of(UUID.randomUUID()))) {
                FakeOperations operations = new FakeOperations();
                operations.activePresetId = active;
                operations.uiPreferences = operations.uiPreferences.withSelectedAddSourceTab(remembered);
                ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
                runtime.reopen(ScreenDestination.ACTIVE_EDITOR);
                assertEquals(AddSourceTab.FILE, runtime.snapshot().addSource().orElseThrow().selectedTab());
                assertEquals(AddSourceTab.FILE, operations.uiPreferences.selectedAddSourceTab());
                assertTrue(runtime.snapshot().editor().isEmpty());
            }
        }
    }

    @Test
    void providersRememberOrdinaryTabAcrossRuntimeInstances() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime first = runtime(operations, Runnable::run, Optional.empty());
        first.initialize();
        first.dispatchWidget("gallery.providers");
        first.dispatchWidget("providers.tab.CAPE");
        assertEquals(AppearanceProviders.Component.CAPE, operations.uiPreferences.selectedProvidersTab());
        ClientRuntime second = runtime(operations, Runnable::run, Optional.empty());
        second.reopen(ScreenDestination.PROVIDERS);
        assertEquals("providers.tab.CAPE", second.view(854, 480, 0, 0).tabGroups().get(0).tabs().stream().filter(ViewSpec.Tab::selected).findFirst().orElseThrow().id());
    }

    @Test
    void resourceCapeWarmupRunsOnStartupAndReloadSignalButNeverPollsFromClientTicks() {
        FakeOperations operations = new FakeOperations();
        operations.startupResourceCapeWarmupSupported = true;
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();

        for (int tick = 0; tick < 20; tick++) {
            runtime.tick();
        }
        assertEquals(1, operations.capeCatalogGenerationCalls);
        assertEquals(1, operations.capeCatalogWarmups);

        runtime.resourcesReloaded();
        assertEquals(2, operations.capeCatalogGenerationCalls);
        assertEquals(0, operations.resourceCapeCatalogWarmups,
                "account warmup already prepares the resource catalog once");
        assertEquals(2, operations.capeCatalogWarmups);
    }

    @Test
    void initialResourceReloadWarmsDiscoveryBeforeAccountInitialization() {
        FakeOperations operations = new FakeOperations();
        operations.startupResourceCapeWarmupSupported = true;
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());

        runtime.resourcesReloaded();

        assertEquals(1, operations.capeCatalogGenerationCalls);
        assertEquals(1, operations.resourceCapeCatalogWarmups);
        assertEquals(0, operations.capeCatalogWarmups);
        runtime.initialize();
        assertEquals(1, operations.capeCatalogWarmups,
                "the account established after the reload must receive the prepared resource catalog");
    }

    @Test
    void enabledResourceCapesAppearOnColdEditorOpenWithoutManualReload() {
        FakeOperations operations = new FakeOperations();
        operations.startupResourceCapeWarmupSupported = true;
        operations.account = TestFixtures.account(2);
        operations.resourceCapeCollections = List.of(resourceCapeCollection());
        operations.resourceCapeHashes = Map.of(
                new ClientOperations.ResourceCapeKey("event", "hero"), "c".repeat(64));
        operations.publishWarmedCapeEditorOnWarmup = true;
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());

        initializeQueuedRuntime(runtime, worker);
        assertEquals(1, operations.capeCatalogWarmups);
        openQueuedCapeProviderEditor(runtime, worker);
        assertTrue(runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream()
                .anyMatch(card -> card.resource() != null),
                "cold startup must publish the resource cape catalog before editor inspection");
    }

    @Test
    void coldCapacityRecoveryUpdatesAlreadyOpenEditor() throws Exception {
        FakeOperations operations = new FakeOperations();
        operations.startupResourceCapeWarmupSupported = true;
        AtomicInteger packGeneration = new AtomicInteger(1);
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, 0xff123456);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "png", encoded);
        byte[] sourceCape = encoded.toByteArray();
        SkinCatalogSource source = new SkinCatalogSource() {
            @Override public byte[] load(String collection, String skin, SkinModel model) {
                throw new AssertionError("No skin source is selected");
            }
            @Override public List<CollectionDescriptor> collections() { return List.of(); }
            @Override public long generation() { return packGeneration.get(); }
            @Override public long capeGeneration() { return packGeneration.get(); }
            @Override public List<CapeCatalogSource.CollectionDescriptor> capeCollections() {
                return com.naocraftlab.skins.client.ResourcePackCapeCatalog.build(List.of(
                        new com.naocraftlab.skins.client.ResourcePackCapeCatalog.Variant(
                                "event", "hero", "pack", 0,
                                "event:textures/entity/cape/hero.png")));
            }
            @Override public byte[] loadCape(String collection, String id) {
                return sourceCape.clone();
            }
        };
        CatalogAccountAccess accounts = new CatalogAccountAccess() {
            @Override public UUID currentAccountId() { return operations.account.accountId(); }
            @Override public AccountState load(UUID accountId) throws IOException {
                requireCurrent(accountId);
                return operations.account;
            }
            @Override public byte[] readAsset(String hash) throws IOException { throw new IOException("absent"); }
            @Override public byte[] resolveSkin(AccountState account, UUID assetId) throws IOException {
                throw new IOException("absent");
            }
            @Override public PersonalCapeEntry importCape(UUID accountId, String name, byte[] bytes,
                    com.naocraftlab.skins.core.service.AccountWriteGuard guard) {
                throw new AssertionError("No import in this scenario");
            }
            @Override public void requireCurrent(UUID accountId) throws IOException {
                if (!operations.account.accountId().equals(accountId)) throw new IOException("stale account");
            }
        };
        try (PreparedCatalogService catalog = new PreparedCatalogService(source, accounts,
                new FileCapeStaging())) {
            operations.preparedCatalog = catalog;
            QueuedExecutor worker = new QueuedExecutor();
            ClientRuntime runtime = runtime(operations, worker, Optional.empty());
            var pressure = CapePreparationQueue.tryReserveScratch(512L * 1024 * 1024);
            assertTrue(pressure != null);
            try (pressure) {
                initializeQueuedRuntime(runtime, worker);
                assertTrue(catalog.warmedCapeEditorData(operations.account.accountId()).isEmpty());
                openQueuedCapeProviderEditor(runtime, worker);
                drainWorker(worker);
                assertTrue(runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream()
                        .noneMatch(card -> card.resource() != null));
            }
            drainWorker(worker);
            assertTrue(runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream()
                    .anyMatch(card -> card.resource() != null),
                    "an already-open editor must recover when cold capacity clears without F3+T");
            packGeneration.incrementAndGet();
            var closingPressure = CapePreparationQueue.tryReserveScratch(512L * 1024 * 1024);
            assertTrue(closingPressure != null);
            try (closingPressure) {
                runtime.resourcesReloaded();
                drainWorker(worker);
                assertTrue(catalog.capeCatalogCapacityIncomplete());
                runtime.close();
            }
            int warmupsAfterClose = operations.capeCatalogWarmups;
            drainWorker(worker);
            assertEquals(warmupsAfterClose, operations.capeCatalogWarmups,
                    "a closed runtime must cancel its queued capacity recovery");
        }
    }

    @Test
    void skinProviderGalleryDoneAndEscapeReturnToSkinProviders() {
        for (BuiltinProvider provider : BuiltinProvider.values()) {
            if (!provider.supportsSkin() || !provider.canWrite(AppearanceProviders.Component.SKIN)) continue;
            for (String exit : List.of("done", "escape")) {
                FakeOperations operations = new FakeOperations();
                operations.account = TestFixtures.account(1);
                ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
                runtime.initialize();
                runtime.dispatchWidget("gallery.providers");
                runtime.dispatchWidget("providers.edit." + provider);
                assertEquals("gallery", runtime.view(854, 480, 0, 0).screenId());

                if (exit.equals("escape")) {
                    runtime.escapePressed();
                } else {
                    runtime.dispatchWidget("gallery.done");
                }

                ViewSpec returned = runtime.view(854, 480, 0, 0);
                assertEquals("providers", returned.screenId());
                assertEquals(Optional.of("selected"),
                        returned.widget("providers.tab.SKIN").orElseThrow().value());
            }
        }
    }

    @Test
    void skinProviderGalleryCloseClearsGalleryFocusBeforeProviders() {
        for (String exit : List.of("done", "escape")) {
            FakeOperations operations = new FakeOperations();
            operations.account = TestFixtures.account(1);
            ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
            runtime.initialize();
            runtime.dispatchWidget("gallery.providers");
            runtime.dispatchWidget("providers.edit.OFFLINE");
            assertTrue(runtime.dispatchNavigation(
                    ViewSpec.NavigationCommand.TAB_FORWARD, "gallery.search"));

            if (exit.equals("escape")) {
                runtime.escapePressed();
            } else {
                runtime.dispatchWidget("gallery.done");
            }

            ViewSpec providers = runtime.view(854, 480, 0, 0);
            assertEquals("providers", providers.screenId());
            assertTrue(providers.widgets().stream()
                    .noneMatch(widget -> widget.id().startsWith("gallery.")));
            runtime.dispatchWidget("providers.back");
            assertTrue(runtime.view(854, 480, 0, 0).focusRequest().isEmpty());
        }
    }

    @Test
    void offlineProviderEditRevealsObservedResourceCapeAndScrollsWithoutChangingDraft() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(2);
        UUID active = operations.account.presets().get(1).id();
        operations.activePresetId = Optional.of(active);
        operations.resourceCapeCollections = List.of(resourceCapeCollection());
        operations.resourceCapeHashes = Map.of(
                new ClientOperations.ResourceCapeKey("event", "hero"), "c".repeat(64));
        operations.providers = AppearanceProviders.initial().select(
                1,
                null,
                new ProviderCape("observed", "c".repeat(64), false),
                new ProviderCape("pending", null));
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.tab.CAPE");
        runtime.dispatchWidget("providers.edit.OFFLINE");

        CapeCatalogModel catalog = runtime.snapshot().editor().orElseThrow().capeCatalog();
        CapeCatalogModel.Card resource = catalog.resourceOwner("b".repeat(64)).orElseThrow();
        assertEquals(resource, catalog.inspected());
        assertFalse(catalog.collapsed().contains(resource.collectionId()));
        assertTrue(catalog.offline() == null);
        assertEquals(Optional.of("cape-1"), catalog.minecraft());
        assertEquals(operations.providers, runtime.snapshot().providers());

        ViewSpec view = runtime.view(320, 240, 0, 0);
        assertTrue(view.scrollSurface("editor.capes").orElseThrow().offsetPixels() > 0.0);
        assertTrue(view.widget(resource.widgetId()).isPresent());
    }

    @Test
    void providerGalleryExitPublishesProvidersToSubscribedHost() {
        for (BuiltinProvider provider : List.of(BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFT)) {
            for (String exit : List.of("pointer", "keyboard", "escape")) {
                FakeOperations operations = new FakeOperations();
                operations.account = TestFixtures.account(1);
                ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
                runtime.initialize();
                runtime.dispatchWidget("gallery.providers");
                runtime.dispatchWidget("providers.edit." + provider.name());
                List<ViewSpec> delivered = new ArrayList<>();
                runtime.subscribe(snapshot -> delivered.add(runtime.view(854, 480, 0, 0)));
                assertEquals("gallery", delivered.get(delivered.size() - 1).screenId());
                delivered.clear();
                if (exit.equals("escape")) runtime.escapePressed();
                else runtime.dispatchWidget("gallery.done", false,
                        exit.equals("pointer") ? InteractionOrigin.POINTER : InteractionOrigin.KEYBOARD);
                assertEquals(1, delivered.size(), provider + " / " + exit);
                assertEquals("providers", delivered.get(0).screenId());
                assertTrue(delivered.get(0).widgets().stream()
                        .noneMatch(widget -> widget.id().startsWith("gallery.")));
                assertEquals(operations.account, runtime.snapshot().account().orElseThrow());
            }
        }
    }

    @Test
    void queuedOfflineProviderEditRevealsResourceCapeAfterColdCatalogReload() {
        FakeOperations operations = new FakeOperations();
        operations.uiPreferences = operations.uiPreferences.withCollapsedCapeCollections(Set.of("resource:event"));
        operations.account = TestFixtures.account(2);
        operations.activePresetId = Optional.of(operations.account.presets().get(1).id());
        operations.resourceCapeCollections = List.of(resourceCapeCollection());
        operations.resourceCapeHashes = Map.of(
                new ClientOperations.ResourceCapeKey("event", "hero"), "c".repeat(64));
        operations.providers = AppearanceProviders.initial().select(
                1, null, new ProviderCape("observed", "c".repeat(64), false),
                new ProviderCape("pending", null));
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        initializeQueuedRuntime(runtime, worker);
        openQueuedCapeProviderEditor(runtime, worker);

        assertTrue(worker.size() > 0);
        CapeCatalogModel cold = runtime.snapshot().editor().orElseThrow().capeCatalog();
        assertTrue(cold.cards().stream().noneMatch(card -> card.resource() != null));
        assertTrue(cold.collapsed().contains("resource:event"));
        AppearanceProviders assignments = runtime.snapshot().providers();

        drainWorker(worker);

        CapeCatalogModel catalog = runtime.snapshot().editor().orElseThrow().capeCatalog();
        CapeCatalogModel.Card resource = catalog.resourceOwner("b".repeat(64)).orElseThrow();
        assertEquals(resource, catalog.inspected());
        assertFalse(catalog.collapsed().contains(resource.collectionId()));
        assertEquals(assignments, runtime.snapshot().providers());
        ViewSpec view = runtime.view(320, 240, 0, 0);
        ViewSpec.ScrollSurface surface = view.scrollSurface("editor.capes").orElseThrow();
        Bounds cardBounds = view.widget(resource.widgetId()).orElseThrow().bounds();
        assertTrue(surface.offsetPixels() > 0.0);
        assertTrue(cardBounds.bottom() > surface.viewport().y());
        assertTrue(cardBounds.y() < surface.viewport().bottom());
    }

    @Test
    void offlineProviderEditUsesPersonalRenderIdentityWithColdAndWarmedCatalog() {
        for (boolean warmed : List.of(false, true)) {
            LocalCapeReference local = new LocalCapeReference(new UUID(7, 1), "a".repeat(64), false);
            PersonalCapeEntry personal = new PersonalCapeEntry(local, "b".repeat(64), "Saved copy", Instant.EPOCH);
            FakeOperations operations = new FakeOperations();
            operations.capeEditorDataWarmed = warmed;
            operations.account = TestFixtures.account(2).withPersonalCapes(List.of(personal));
            operations.activePresetId = Optional.of(operations.account.presets().get(1).id());
            operations.resourceCapeCollections = List.of(resourceCapeCollection());
            operations.resourceCapeHashes = Map.of(
                    new ClientOperations.ResourceCapeKey("event", "hero"), "c".repeat(64));
            operations.providers = AppearanceProviders.initial().select(1, null,
                    new ProviderCape(local.entryId().toString(), local.sha256(), false),
                    new ProviderCape("pending", null));
            QueuedExecutor worker = new QueuedExecutor();
            ClientRuntime runtime = runtime(operations, worker, Optional.empty());
            initializeQueuedRuntime(runtime, worker);
            openQueuedCapeProviderEditor(runtime, worker);
            drainWorker(worker);
            CapeCatalogModel catalog = runtime.snapshot().editor().orElseThrow().capeCatalog();
            assertEquals(catalog.resourceOwner("b".repeat(64)).orElseThrow(), catalog.inspected());
            assertNull(catalog.offline());
            assertEquals(Optional.of("cape-1"), catalog.minecraft());
        }
    }

    @Test
    void deferredProviderRevealIsCancelledByEditorInteraction() {
        for (String action : List.of("tab", "choice", "close")) {
            FakeOperations operations = new FakeOperations();
            operations.account = TestFixtures.account(2);
            operations.activePresetId = Optional.of(operations.account.presets().get(1).id());
            operations.resourceCapeCollections = List.of(resourceCapeCollection());
            operations.resourceCapeHashes = Map.of(
                    new ClientOperations.ResourceCapeKey("event", "hero"), "c".repeat(64));
            operations.providers = AppearanceProviders.initial().select(
                    1, null, new ProviderCape("observed", "c".repeat(64), false),
                    new ProviderCape("pending", null));
            QueuedExecutor worker = new QueuedExecutor();
            ClientRuntime runtime = runtime(operations, worker, Optional.empty());
            initializeQueuedRuntime(runtime, worker);
            openQueuedCapeProviderEditor(runtime, worker);

            if (action.equals("tab")) {
                runtime.dispatchWidget("editor.tab.appearance");
            } else if (action.equals("choice")) {
                runtime.dispatchWidget("editor.cape_item.OFFLINE.none");
            } else {
                runtime.dispatchWidget("editor.cancel");
            }
            drainWorker(worker);

            if (action.equals("close")) {
                assertTrue(runtime.snapshot().editor().isEmpty());
            } else {
                CapeCatalogModel catalog = runtime.snapshot().editor().orElseThrow().capeCatalog();
                if (action.equals("tab")) assertNull(catalog.inspected());
                else assertEquals("none", catalog.inspected().key());
                assertTrue(action.equals("tab")
                        ? runtime.snapshot().editor().orElseThrow().selectedEditorTab() == EditorTab.APPEARANCE
                        : catalog.cards().stream().filter(card -> card.key().equals("none"))
                                .anyMatch(catalog::selected));
            }
        }
    }

    @Test
    void deferredProviderRevealIsCancelledWhenOpeningAnotherEditor() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(2);
        operations.activePresetId = Optional.of(operations.account.presets().get(1).id());
        operations.resourceCapeCollections = List.of(resourceCapeCollection());
        operations.resourceCapeHashes = Map.of(
                new ClientOperations.ResourceCapeKey("event", "hero"), "c".repeat(64));
        operations.providers = AppearanceProviders.initial().select(
                1, null, new ProviderCape("observed", "c".repeat(64), false),
                new ProviderCape("pending", null));
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        initializeQueuedRuntime(runtime, worker);
        openQueuedCapeProviderEditor(runtime, worker);
        runtime.dispatchWidget("editor.cancel");
        runtime.dispatchWidget("providers.back");
        runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
        drainWorker(worker);

        assertTrue(runtime.snapshot().editor().isPresent());
        assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().inspected());
    }

    private static void initializeQueuedRuntime(ClientRuntime runtime, QueuedExecutor worker) {
        runtime.initialize();
        drainWorker(worker);
    }

    private static void openQueuedCapeProviderEditor(ClientRuntime runtime, QueuedExecutor worker) {
        runtime.dispatchWidget("gallery.providers");
        drainWorker(worker);
        runtime.view(320, 240, 0, 0);
        runtime.dispatchWidget("providers.tab.CAPE");
        runtime.dispatchWidget("providers.edit.OFFLINE");
    }

    private static void drainWorker(QueuedExecutor worker) {
        int remaining = 100;
        while (worker.size() > 0 && remaining-- > 0) worker.runFirst();
        assertEquals(0, worker.size());
    }

    @Test
    void capePreviewModeStaysSynchronizedBetweenEditorAndProviders() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(2);
        operations.activePresetId = Optional.of(operations.account.presets().get(1).id());
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();

        runtime.dispatchWidget("gallery.preset."
                + operations.activePresetId.orElseThrow() + ".edit");
        PreviewRenderer.CapeMode initialMode =
                runtime.snapshot().editor().orElseThrow().preview().capeMode();
        runtime.dispatchWidget("editor.preview_mode");
        assertNotEquals(initialMode,
                runtime.snapshot().editor().orElseThrow().preview().capeMode());
        runtime.dispatchWidget("editor.cancel");

        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.preview_mode");
        runtime.dispatchWidget("providers.tab.CAPE");
        runtime.dispatchWidget("providers.edit.OFFLINE");
        assertEquals(initialMode,
                runtime.snapshot().editor().orElseThrow().preview().capeMode());
    }

    @Test
    void capeProviderEditorReturnsToCapeProvidersWithoutChangingOrdinaryEditorReturn() {
        for (String exit : List.of("save", "cancel", "escape")) for (var provider : BuiltinProvider.values()) {
            if (!provider.canWrite(AppearanceProviders.Component.CAPE)) continue;
            FakeOperations operations = new FakeOperations();
            operations.account = TestFixtures.account(2);
            UUID active = operations.account.presets().get(1).id();
            operations.activePresetId = Optional.of(active);
            ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
            runtime.initialize();
            runtime.dispatchWidget("gallery.providers");
            runtime.dispatchWidget("providers.tab.CAPE");
            runtime.dispatchWidget("providers.row." + provider);
            runtime.dispatchWidget("providers.edit." + provider);
            runtime.dispatchText("editor.name", "Changed");
            if (exit.equals("escape")) runtime.escapePressed();
            else runtime.dispatchWidget("editor." + exit);
            var returned = runtime.view(854, 480, 0, 0);
            assertEquals("providers", returned.screenId());
            assertTrue(runtime.snapshot().editor().isEmpty());
            assertEquals(Optional.of("selected"), returned.widget("providers.tab.CAPE").orElseThrow().value());
            assertTrue(returned.widget("providers.row." + provider).orElseThrow().value().isEmpty());
            assertEquals(exit.equals("save") ? "Changed" : "Preset 2", findPreset(runtime.snapshot(), active).name());
            runtime.dispatchWidget("providers.back");
            for (String ordinaryExit : List.of("save", "cancel", "escape")) {
                runtime.dispatchWidget("gallery.preset." + active + ".edit");
                assertTrue(runtime.snapshot().editor().isPresent());
                if (ordinaryExit.equals("escape")) runtime.escapePressed();
                else runtime.dispatchWidget("editor." + ordinaryExit);
                assertEquals("gallery", runtime.view(854, 480, 0, 0).screenId());
            }
        }
    }

    @Test
    void capeProviderEditorRetainsReturnAfterSaveFailureButClearsItOnScreenClose() {
        for (String exit : List.of("save", "cancel", "close")) {
            FakeOperations operations = new FakeOperations();
            operations.account = TestFixtures.account(1);
            ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
            runtime.initialize();
            runtime.dispatchWidget("gallery.providers");
            runtime.dispatchWidget("providers.tab.CAPE");
            runtime.dispatchWidget("providers.edit.OFFLINE");
            operations.failEditorSave = true;
            runtime.dispatchWidget("editor.save");
            assertEquals("preset_editor", runtime.view(854, 480, 0, 0).screenId());
            assertTrue(runtime.snapshot().editor().isPresent());
            operations.failEditorSave = false;
            if (exit.equals("close")) {
                runtime.closeScreen();
                runtime.reopen();
                UUID preset = runtime.snapshot().account().orElseThrow().presets().get(0).id();
                runtime.dispatchWidget("gallery.preset." + preset + ".edit");
                runtime.dispatchWidget("editor.cancel");
                assertEquals("gallery", runtime.view(854, 480, 0, 0).screenId());
            } else {
                runtime.dispatchWidget("editor." + exit);
                assertEquals("providers", runtime.view(854, 480, 0, 0).screenId());
            }
        }
    }

    @Test
    void providerEditOpensGalleryOrActiveCapeDraftWithoutApplying() {
        for (var provider : BuiltinProvider.values()) for (int activeIndex = 0; activeIndex < 2; activeIndex++) {
            if (!provider.canWrite(AppearanceProviders.Component.CAPE)) continue;
            FakeOperations operations = new FakeOperations();
            operations.account = TestFixtures.account(2);
            var active = operations.account.presets().get(activeIndex);
            operations.activePresetId = Optional.of(active.id());
            ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
            runtime.initialize();
            runtime.dispatchWidget("gallery.providers");
            runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, "providers.edit." + provider);
            assertEquals("gallery", runtime.view(854, 480, 0, 0).screenId());
            assertTrue(runtime.snapshot().editor().isEmpty());
            runtime.dispatchWidget("gallery.providers");
            runtime.dispatchWidget("providers.tab.CAPE");
            runtime.dispatchWidget("providers.row." + provider);
            runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, "providers.edit." + provider);
            assertEquals("preset_editor", runtime.view(854, 480, 0, 0).screenId());
            var editor = runtime.snapshot().editor().orElseThrow();
            assertEquals(Optional.of(active.id()), editor.originalPresetId());
            assertEquals(EditorTab.CAPE, editor.selectedEditorTab());
            assertEquals(active.optionalCapeId(), editor.capeId());
            assertEquals(operations.providers, runtime.snapshot().providers());
            assertEquals(Optional.of(active.id()), runtime.snapshot().activePresetId());
        }
    }

    @Test
    void readOnlyOptifineRejectsForgedEditAndPreparesAccountLinkOnce() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        var tasks = new java.util.ArrayList<Runnable>();
        UUID accountId = operations.account.accountId();
        var sessions = new GameSessionTokenSource() {
            @Override public SessionIdentity currentSession() {
                return new SessionIdentity(accountId, "Player_1");
            }
            @Override public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                return request.execute("current-token");
            }
        };
        runtime.useOptiFineAccountLink(new OptiFineAccountLink(sessions, tasks::add,
                (profileId, accessToken, proof) -> { }, new java.security.SecureRandom(), java.time.Clock.systemUTC()));
        runtime.initialize();
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.tab.CAPE");
        runtime.dispatchWidget("providers.edit.OPTIFINE");
        assertEquals("providers", runtime.view(854, 480, 0, 0).screenId());
        assertTrue(runtime.snapshot().editor().isEmpty());
        runtime.dispatchWidget("providers.account.OPTIFINE");
        runtime.dispatchWidget("providers.account.OPTIFINE");
        assertEquals(1, tasks.size());
        assertEquals("nclskins.providers.link_preparing", runtime.view(854, 480, 0, 0).texts().stream()
                .filter(text -> text.id().equals("providers.account.feedback"))
                .findFirst().orElseThrow().message().key());
        tasks.remove(0).run();
        assertTrue(runtime.consumeReadyOptiFineAccountLink().isPresent());
        assertTrue(runtime.consumeReadyOptiFineAccountLink().isEmpty());
        runtime.finishOptiFineAccountLink();
        assertTrue(runtime.currentOptiFineAccountLink().isEmpty());
        assertEquals("providers", runtime.view(854, 480, 0, 0).screenId());
    }

    @Test
    void accountLinkCannotOpenAfterProviderNavigationOrLateCompletion() {
        for (String navigation : List.of("providers.back", "providers.tab.SKIN",
                "providers.add", "providers.edit.OFFLINE", "providers.remove.OPTIFINE")) {
            FakeOperations operations = new FakeOperations();
            operations.account = TestFixtures.account(1);
            operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE,
                    BuiltinProvider.OPTIFINE);
            ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
            var tasks = new ArrayList<Runnable>();
            UUID accountId = operations.account.accountId();
            GameSessionTokenSource sessions = new GameSessionTokenSource() {
                @Override public SessionIdentity currentSession() {
                    return new SessionIdentity(accountId, "Player_1");
                }
                @Override public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                    return request.execute("current-token");
                }
            };
            runtime.useOptiFineAccountLink(new OptiFineAccountLink(sessions, tasks::add,
                    (profileId, accessToken, proof) -> { }, new java.security.SecureRandom(),
                    java.time.Clock.systemUTC()));
            runtime.initialize();
            runtime.dispatchWidget("gallery.providers");
            runtime.dispatchWidget("providers.tab.CAPE");
            runtime.dispatchWidget("providers.account.OPTIFINE");
            assertEquals(1, tasks.size(), navigation);
            if (navigation.equals("providers.back") || navigation.equals("providers.remove.OPTIFINE")) {
                tasks.remove(0).run();
                if (navigation.equals("providers.back")) {
                    assertTrue(runtime.consumeReadyOptiFineAccountLink().isPresent());
                }
            }
            runtime.dispatchWidget(navigation);
            if (!tasks.isEmpty()) tasks.remove(0).run();
            if (navigation.equals("providers.remove.OPTIFINE")) {
                runtime.dispatchWidget("providers.add");
                runtime.dispatchWidget("providers.row.OPTIFINE");
            }
            assertTrue(runtime.consumeReadyOptiFineAccountLink().isEmpty(), navigation);
            assertTrue(runtime.currentOptiFineAccountLink().isEmpty(), navigation);
        }
    }

    @Test
    void skinMcAccountUsesFixedConfirmationUriAndExpiresOnNavigation() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE,
                BuiltinProvider.SKINMC);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.account.SKINMC");
        assertTrue(runtime.consumeReadySkinMcAccountLink().isEmpty());

        runtime.dispatchWidget("providers.tab.CAPE");
        runtime.dispatchWidget("providers.account.SKINMC");
        assertEquals(java.net.URI.create("https://skinmc.net/account/capes"),
                runtime.consumeReadySkinMcAccountLink().orElseThrow());
        assertTrue(runtime.consumeReadySkinMcAccountLink().isEmpty());
        assertEquals(java.net.URI.create("https://skinmc.net/account/capes"),
                runtime.currentSkinMcAccountLink().orElseThrow());
        runtime.dispatchWidget("providers.tab.SKIN");
        assertTrue(runtime.currentSkinMcAccountLink().isEmpty());
        runtime.dispatchWidget("providers.tab.CAPE");
        assertTrue(runtime.consumeReadySkinMcAccountLink().isEmpty());

        runtime.dispatchWidget("providers.account.SKINMC");
        assertTrue(runtime.consumeReadySkinMcAccountLink().isPresent());
        runtime.finishSkinMcAccountLink();
        assertTrue(runtime.currentSkinMcAccountLink().isEmpty());
        assertTrue(runtime.consumeReadySkinMcAccountLink().isEmpty());
    }

    @Test
    void sneakyEditorUsesFixedConfirmationUriAndExpiresOnNavigation() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE,
                BuiltinProvider.SNEAKY);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.tab.CAPE");
        runtime.dispatchWidget("providers.account.SNEAKY");
        assertEquals(java.net.URI.create("https://penguinspy.neocities.org/projects/loom/"),
                runtime.consumeReadySneakyEditorLink().orElseThrow());
        assertTrue(runtime.consumeReadySneakyEditorLink().isEmpty());
        assertTrue(runtime.currentSneakyEditorLink().isPresent());
        runtime.finishSneakyEditorLink();
        assertTrue(runtime.currentSneakyEditorLink().isEmpty());
        runtime.dispatchWidget("providers.account.SNEAKY");
        runtime.dispatchWidget("providers.tab.SKIN");
        assertTrue(runtime.consumeReadySneakyEditorLink().isEmpty());
    }

    @Test
    void providerRowsWheelAndKeyboardKeepFinalCapeVisibleAtShortHeight() {
        FakeOperations operations = new FakeOperations();
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE,
                BuiltinProvider.OPTIFINE);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.tab.CAPE");
        ViewSpec first = runtime.view(200, 191, 0, 0);
        Bounds viewport = first.scrollSurface("providers.rows").orElseThrow().viewport();
        assertTrue(first.scrollSurface("providers.rows").orElseThrow().maximumPixels() > 0);
        runtime.pointerScrolled(viewport.x() + 2, viewport.y() + 2, 0, -4);
        ViewSpec scrolled = runtime.view(200, 191, 0, 0);
        assertTrue(scrolled.scrollSurface("providers.rows").orElseThrow().offsetPixels() > 0);
        runtime.nativeScrollPositionChanged("providers.rows", 0);
        assertTrue(runtime.dispatchNavigation(ViewSpec.NavigationCommand.DOWN, "providers.row.MINECRAFT"));
        ViewSpec focused = runtime.view(200, 191, 0, 0);
        assertTrue(focused.scrollSurface("providers.rows").orElseThrow().offsetPixels() > 0);
        assertTrue(focused.widget("providers.row.OPTIFINE").orElseThrow().bounds().bottom()
                <= focused.scrollSurface("providers.rows").orElseThrow().viewport().bottom());
    }

    @Test
    void asyncOptifineCompletionUpdatesProvidersThenOrdinaryApplyRefreshesSelfCandidate(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.localFirst = true;
        operations.presetUseProviders = true;
        UUID self = operations.account.accountId();
        NclSkinsStorage storage = new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
        storage.loadOrCreateAccount(self);
        operations.providers = storage.updateAppearance(self, current -> current.withProviders(
                current.providers().enable(AppearanceProviders.Component.CAPE,
                        BuiltinProvider.OPTIFINE))).providers();
        GameSessionTokenSource tokens = new GameSessionTokenSource() {
            @Override public SessionIdentity currentSession() { return operations.sessionIdentity(); }
            @Override public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) {
                throw new AssertionError("Public cape observation cannot request a token");
            }
        };
        byte[] capeBytes = testCapePng();
        var jobs = new ArrayList<Runnable>();
        var sink = new PlayerAppearanceSink<Object>() {
            @Override public ApplyResult apply(SignedProfileResolver.ResolvedProfile<Object> profile) {
                return ApplyResult.UPDATED;
            }
            @Override public Optional<String> registerCapeTexture(UUID profileId, CapeSource source,
                    String sha256, byte[] png) {
                return Optional.of("nclskins:" + source.name().toLowerCase() + "/" + sha256);
            }
        };
        TextureCache cache = new TextureCache(storage);
        var reader = CapeTestComposition.optifine(storage, (uri, timeout, maximum) ->
                new OptifineCapeReader.Response(200, capeBytes), java.time.Clock.systemUTC());
        operations.optifineCoordinator = CapeTestComposition.create(tokens, storage, cache,
                sink, CLIENT, jobs::add, reader);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.tab.CAPE");
        assertFalse(runtime.snapshot().providers().cape().optifine().known());
        while (!jobs.isEmpty()) jobs.remove(0).run();
        assertTrue(runtime.snapshot().providers().cape().optifine().known());
        assertTrue(runtime.view(854, 480, 0, 0).iconDecorations().stream()
                .anyMatch(icon -> icon.ownerWidgetId().equals("providers.row.OPTIFINE")
                        && icon.providerTexture().isPresent()));

        operations.providers = runtime.snapshot().providers();
        var localPng = new PngValidator().projectCape(testCapePng());
        String localKey = cache.storeObservedCape(localPng);
        ProviderCape localCape = new ProviderCape("local", localKey, localPng.hasElytra());
        operations.providers = new AppearanceProviders(operations.providers.skin(),
                operations.providers.cape().select(operations.providers.cape().intentRevision() + 1,
                        localCape));
        runtime.dispatchWidget("providers.back");
        runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".apply");
        while (!jobs.isEmpty()) jobs.remove(0).run();
        assertEquals(BuiltinProvider.OFFLINE,
                CapeProjection.resolve(self, operations.sessionIdentity().profileName(),
                        null, null, true).provider());
        operations.optifineCoordinator.close();
    }

    private static byte[] testCapePng() throws IOException {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, 0xff123456);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    @Test
    void providerEditCannotBypassMissingWritersAndNoActivePresetUsesDefaultDraft() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.tab.CAPE");
        runtime.dispatchWidget("providers.edit.OFFLINE");
        assertEquals(EditorTab.CAPE, runtime.snapshot().editor().orElseThrow().selectedEditorTab());
        assertTrue(runtime.snapshot().editor().orElseThrow().capeId().isEmpty());
        for (var missing : AppearanceProviders.Component.values()) {
            FakeOperations blocked = new FakeOperations();
            blocked.providers = blocked.providers.disable(missing, BuiltinProvider.OFFLINE).disable(missing, BuiltinProvider.MINECRAFT);
            ClientRuntime unavailable = runtime(blocked, Runnable::run, Optional.empty());
            unavailable.initialize();
            for (var component : AppearanceProviders.Component.values()) {
                unavailable.dispatchWidget("providers.tab." + component);
                unavailable.dispatchWidget("providers.edit.OFFLINE");
                assertEquals("providers", unavailable.view(854, 480, 0, 0).screenId());
                assertTrue(unavailable.snapshot().editor().isEmpty());
            }
        }
    }

    @Test
    void disablingMinecraftHidesCooldownWithoutClearingItsRetryAfter() {
        FakeOperations operations = new FakeOperations();
        operations.rateLimited = true;
        operations.rateLimitRemaining = Duration.ofSeconds(60);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.tick();
        assertTrue(runtime.snapshot().rateLimitProgress().isPresent());
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.remove.MINECRAFT");
        runtime.dispatchWidget("providers.tab.CAPE");
        runtime.dispatchWidget("providers.remove.MINECRAFT");
        runtime.tick();
        assertTrue(runtime.snapshot().rateLimitProgress().isEmpty());
        assertTrue(operations.rateLimited);
        runtime.dispatchWidget("providers.add");
        runtime.dispatchWidget("providers.row.MINECRAFT");
        runtime.tick();
        assertEquals(Duration.ofSeconds(60), runtime.snapshot().rateLimitProgress().orElseThrow().remaining());
    }

    @Test
    void readdingMinecraftCapesBehindOfflineKeepsMaterialTiedToInspectedSource() {
        FakeOperations operations = new FakeOperations();
        String hash = "a".repeat(64);
        var capes = operations.providers.cape()
                .select(1, new ProviderCape("offline", hash, true),
                        new ProviderCape("offline", hash, true))
                .enable(BuiltinProvider.MINECRAFTCAPES)
                .observeMinecraftcapes(new ProviderCape("mc", hash, true, true))
                .move(BuiltinProvider.MINECRAFTCAPES, -1)
                .move(BuiltinProvider.MINECRAFTCAPES, -1);
        operations.providers = new AppearanceProviders(operations.providers.skin(), capes);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.tab.CAPE");
        ViewSpec.Preview initial = runtime.view(854, 480, 0, 0).previews().get(0);
        assertEquals(Optional.of("provider:cape:" + hash), initial.capeId());
        assertTrue(runtime.previewCapeGlint(initial));

        runtime.dispatchWidget("providers.remove.MINECRAFTCAPES");
        ViewSpec.Preview without = runtime.view(854, 480, 0, 0).previews().get(0);
        assertEquals(initial.capeId(), without.capeId(), "same pixels do not carry provider material");
        assertFalse(runtime.previewCapeGlint(without));
        runtime.dispatchWidget("providers.add");
        runtime.dispatchWidget("providers.row.MINECRAFTCAPES");
        ViewSpec.Preview restored = runtime.view(854, 480, 0, 0).previews().get(0);
        assertEquals(initial.capeId(), restored.capeId());
        assertFalse(runtime.previewCapeGlint(restored),
                "the effective Offline source is still selected after append");
        runtime.dispatchWidget("providers.row.OFFLINE");
        assertFalse(runtime.previewCapeGlint(runtime.view(854, 480, 0, 0).previews().get(0)));
        runtime.dispatchWidget("providers.row.MINECRAFTCAPES");
        assertTrue(runtime.previewCapeGlint(runtime.view(854, 480, 0, 0).previews().get(0)));
    }

    @Test
    void durableMinecraftCapesRemoveAndReaddKeepsWorldAndSelectedPreviewGlint(
            @TempDir Path directory) throws Exception {
        FakeOperations operations = new FakeOperations();
        UUID self = operations.account.accountId();
        NclSkinsStorage storage = new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
        operations.account = storage.loadOrCreateAccount(self);
        operations.providerStorage = storage;
        operations.providers = storage.updateAppearance(self, state -> state.withProviders(
                state.providers().disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                        .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT)
                        .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFTCAPES))).providers();
        byte[] png = testCapePng();
        String imageUri = "https://textures.minecraftcapes.net/sprite/" + "b".repeat(64);
        var reader = new MinecraftCapesCapeReader(new MinecraftCapesCapeReader.Transport() {
            @Override public MinecraftCapesCapeReader.ProfileResponse profile(
                    java.net.URI uri, Duration timeout, long maxBytes) throws IOException {
                byte[] body = ("{\"cape_url\":\"" + imageUri + "\",\"capeGlint\":true}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                return new MinecraftCapesCapeReader.ProfileResponse(200,
                        MinecraftCapesCapeReader.parse(body), Map.of());
            }
            @Override public MinecraftCapesCapeReader.Response image(
                    java.net.URI uri, Duration timeout, long maxBytes, Path destination) throws IOException {
                Files.write(destination, png);
                return new MinecraftCapesCapeReader.Response(200, new byte[0], Map.of());
            }
        }, new com.naocraftlab.skins.core.png.CapeAssetDecoder(), Clock.systemUTC(),
                System::nanoTime, new java.security.SecureRandom(), directory.resolve("mc-staging"));
        GameSessionTokenSource tokens = new GameSessionTokenSource() {
            @Override public SessionIdentity currentSession() { return operations.sessionIdentity(); }
            @Override public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) {
                throw new AssertionError("Public cape observation cannot request a token");
            }
        };
        var sink = new PlayerAppearanceSink<Object>() {
            @Override public ApplyResult apply(SignedProfileResolver.ResolvedProfile<Object> profile) {
                return ApplyResult.UPDATED;
            }
            @Override public Optional<String> registerCapeFrame(UUID profileId, CapeSource source,
                    String sha256, int width, int height, byte[] rgba8,
                    com.naocraftlab.skins.client.TextureRegistry.CpuLease cpuLease) {
                cpuLease.close();
                return Optional.of("nclskins:observed/" + sha256);
            }
        };
        var jobs = new ArrayList<Runnable>();
        operations.optifineCoordinator = CapeTestComposition.create(tokens, storage,
                new TextureCache(storage), sink, CLIENT, jobs::add,
                CapeTestComposition.optifine(storage,
                        (uri, timeout, maximum) -> new OptifineCapeReader.Response(404, new byte[0]),
                        Clock.systemUTC()),
                new SkinMcCapeReader(directory.resolve("skinmc-staging")), reader,
                (id, cape) -> Optional.empty());
        try {
            ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
            runtime.initialize();
            while (!jobs.isEmpty()) jobs.remove(0).run();
            runtime.dispatchWidget("gallery.providers");
            runtime.dispatchWidget("providers.tab.CAPE");
            ViewSpec.Preview initial = runtime.view(854, 480, 0, 0).previews().get(0);
            assertTrue(runtime.previewCapeGlint(initial));
            assertTrue(CapeProjection.resolveSelf(self, operations.sessionIdentity().profileName())
                    .orElseThrow().capeGlint());
            runtime.dispatchWidget("providers.remove.MINECRAFTCAPES");
            runtime.dispatchWidget("providers.add");
            runtime.dispatchWidget("providers.row.MINECRAFTCAPES");
            while (!jobs.isEmpty()) jobs.remove(0).run();
            ViewSpec.Preview restored = runtime.view(854, 480, 0, 0).previews().get(0);
            assertEquals(initial.capeId(), restored.capeId());
            assertTrue(runtime.previewCapeGlint(restored));
            assertTrue(CapeProjection.resolveSelf(self, operations.sessionIdentity().profileName())
                    .orElseThrow().capeGlint());
        } finally {
            operations.optifineCoordinator.close();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void removingExternalProviderThroughUiPreservesPendingPersistedMinecraftCandidate(boolean resourcesReloaded,
            @TempDir Path directory) throws Exception {
        FakeOperations operations = new FakeOperations();
        UUID self = operations.account.accountId();
        NclSkinsStorage storage = new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
        operations.account = storage.loadOrCreateAccount(self);
        operations.providerStorage = storage;
        TextureCache cache = new TextureCache(storage);
        String key = cache.storeObservedCape(new PngValidator().projectCape(testCapePng()));
        operations.providers = storage.updateAppearance(self, state -> state.withProviders(
                new AppearanceProviders(state.providers().skin(), state.providers().cape()
                        .disable(BuiltinProvider.OFFLINE).enable(BuiltinProvider.OPTIFINE)
                        .observeMinecraft(new ProviderCape("official", key))))).providers();
        GameSessionTokenSource tokens = new GameSessionTokenSource() {
            @Override public SessionIdentity currentSession() { return operations.sessionIdentity(); }
            @Override public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) {
                throw new AssertionError("Candidate preparation cannot request a token");
            }
        };
        AtomicInteger minecraftWrites = new AtomicInteger();
        var sink = new PlayerAppearanceSink<Object>() {
            @Override public ApplyResult apply(SignedProfileResolver.ResolvedProfile<Object> profile) {
                return ApplyResult.UPDATED;
            }
            @Override public Optional<String> registerCapeTexture(UUID profileId, CapeSource source, String sha256, byte[] png) {
                if (source == CapeSource.MINECRAFT) minecraftWrites.incrementAndGet();
                return Optional.of("nclskins:observed/" + sha256);
            }
        };
        var jobs = new ArrayList<Runnable>();
        operations.optifineCoordinator = CapeTestComposition.create(tokens, storage, cache, sink, CLIENT, jobs::add,
                CapeTestComposition.optifine(storage, (uri, timeout, maximum) ->
                        new OptifineCapeReader.Response(404, new byte[0]), Clock.systemUTC()),
                CapeTestComposition.skinMc(storage, (uri, timeout, maximum) ->
                        new OptifineCapeReader.Response(404, new byte[0]), Clock.systemUTC()),
                (id, cape) -> { throw new AssertionError("Cached candidate must not create HTTP demand"); });
        try {
            ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
            runtime.initialize();
            long generation = operations.optifineCoordinator.observationGeneration();
            runtime.dispatchWidget("gallery.providers");
            runtime.dispatchWidget("providers.tab.CAPE");
            if (resourcesReloaded) runtime.resourcesReloaded();
            runtime.dispatchWidget("providers.remove.OPTIFINE");
            assertFalse(storage.loadAppearance(self).providers().cape().enabled(BuiltinProvider.OPTIFINE));
            assertTrue(operations.optifineCoordinator.observationGeneration() > generation);
            while (!jobs.isEmpty()) jobs.remove(0).run();
            assertEquals(1, minecraftWrites.get());
            assertTrue(CapeProjection.resolveSelf(self, operations.sessionIdentity().profileName()).isPresent());
        } finally {
            operations.optifineCoordinator.close();
        }
    }

    @Test
    void providerRowsFollowTheExistingCooldownAcrossTabsAndExpiry() {
        FakeOperations operations = new FakeOperations();
        operations.rateLimited = true;
        operations.rateLimitRemaining = Duration.ofSeconds(60);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.tick();
        runtime.dispatchWidget("gallery.providers");
        assertEquals(1.0, runtime.view(854, 480, 0, 0).progressDecorations().get(0).fraction());
        operations.rateLimitRemaining = Duration.ofSeconds(30);
        runtime.tick();
        runtime.dispatchWidget("providers.tab.CAPE");
        assertEquals(0.5, runtime.view(854, 480, 0, 0).progressDecorations().get(0).fraction());
        runtime.dispatchWidget("providers.add");
        assertTrue(runtime.view(854, 480, 0, 0).progressDecorations().isEmpty());
        runtime.escapePressed();
        operations.rateLimited = false;
        operations.rateLimitRemaining = Duration.ZERO;
        runtime.tick();
        assertTrue(runtime.view(854, 480, 0, 0).progressDecorations().isEmpty());
    }

    @Test
    void providerKeyboardMutationsRestoreASurvivingRowFocus() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, "providers.down.OFFLINE");
        assertEquals(BuiltinProvider.OFFLINE, runtime.snapshot().providers().skin().order().get(1));
        assertEquals("providers.row.OFFLINE", runtime.view(854, 480, 0, 0).focusRequest().orElseThrow().widgetId());
        runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, "providers.remove.OFFLINE");
        assertEquals("providers.row.MINECRAFT", runtime.view(854, 480, 0, 0).focusRequest().orElseThrow().widgetId());
        runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, "providers.remove.MINECRAFT");
        assertEquals("providers.add", runtime.view(854, 480, 0, 0).focusRequest().orElseThrow().widgetId());
    }

    @Test
    void providerMovesAndRemovalStayInteractiveAndSerialize() {
        FakeOperations operations = new FakeOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        runtime.initialize();
        while (worker.size() > 0) worker.runFirst();
        runtime.dispatchWidget("gallery.providers");
        while (worker.size() > 0) worker.runFirst();
        runtime.dispatchWidget("providers.down.OFFLINE");
        assertFalse(runtime.snapshot().busy());
        runtime.dispatchWidget("providers.up.OFFLINE");
        assertFalse(runtime.snapshot().busy());
        while (worker.size() > 0) worker.runFirst();
        assertEquals(List.of(BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFT), runtime.snapshot().providers().skin().order());
        runtime.dispatchWidget("providers.remove.MINECRAFT");
        assertFalse(runtime.snapshot().busy());
        while (worker.size() > 0) worker.runFirst();
        assertEquals(List.of(BuiltinProvider.OFFLINE), runtime.snapshot().providers().skin().order());
    }

    @Test
    void providerChooserKeyboardScrollAndEscapeKeepConfiguration() {
        FakeOperations operations = new FakeOperations();
        operations.providers = operations.providers.disable(AppearanceProviders.Component.SKIN, BuiltinProvider.OFFLINE)
                .disable(AppearanceProviders.Component.SKIN, BuiltinProvider.MINECRAFT);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("providers.add");
        runtime.view(320, 100, 0, 0);
        assertTrue(runtime.dispatchNavigation(ViewSpec.NavigationCommand.DOWN, "providers.row.OFFLINE"));
        assertTrue(runtime.view(320, 100, 0, 0).scrollSurface("providers.chooser").orElseThrow().offsetPixels() > 0);
        runtime.escapePressed();
        assertEquals("providers", runtime.view(320, 100, 0, 0).screenId());
        assertTrue(runtime.snapshot().providers().skin().order().isEmpty());
        runtime.dispatchWidget("providers.add");
        assertEquals(0, runtime.view(320, 100, 0, 0).scrollSurface("providers.chooser").orElseThrow().offsetPixels());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"editor,false,false", "providers-skin,false,false", "providers-cape,false,false",
            "editor,true,false", "providers-skin,true,false", "providers-cape,true,false",
            "editor,false,true", "providers-skin,false,true", "providers-cape,false,true",
            "editor,true,true", "providers-skin,true,true", "providers-cape,true,true"})
    void openingAndRebindRequireActualCurrentOrdinaryDisplay(String route, boolean cpm, boolean queued) {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        if (!route.equals("editor")) operations.providers = operations.providers
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        TrackingClientExecutor client = new TrackingClientExecutor();
        QueuedExecutor worker = new QueuedExecutor();
        var visibility = new ProviderTestVisibility();
        var runtime = new ClientRuntime(new DefaultRuntimeServices(), operations, queued ? client : CLIENT, CANCELLED_PICKER,
                queued ? worker : Runnable::run, TEXT,
                Optional.empty(), Optional.of(visibility), Optional.empty(), DiagnosticSinks.discarding());
        runtime.initialize();
        drainRefreshQueues(worker, client);
        if (route.equals("editor")) runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
        if (route.equals("providers-cape")) runtime.dispatchWidget("providers.tab.CAPE");
        drainRefreshQueues(worker, client);
        String prefix = route.equals("editor") ? "editor.outer_layer." : "providers.outer_layer.";
        var history = new ArrayList<Long>();
        runtime.subscribe(ignored -> history.add(refreshView(runtime, client).widgets().stream()
                .filter(widget -> widget.id().startsWith(prefix)).count()));
        var initial = refreshView(runtime, client);
        assertEquals(0, initial.widgets().stream().filter(widget -> widget.id().startsWith(prefix)).count(),
                "Opening before native registration must not show layers: " + route);
        var initialDraft = runtime.snapshot().editor().map(PresetEditorModel::saveRequest);
        var account = operations.account;
        for (String group : List.of("head", "body", "legs")) {
            runtime.dispatchWidget(prefix + group);
            runtime.dispatchWidget(prefix + group, true, InteractionOrigin.KEYBOARD);
            client.execute(() -> assertFalse(runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, prefix + group)));
        }
        drainRefreshQueues(worker, client);
        assertEquals(initialDraft, runtime.snapshot().editor().map(PresetEditorModel::saveRequest));
        assertEquals(account, operations.account);
        assertTrue(visibility.applied.isEmpty());
        assertEquals(0, operations.editorSaveCalls);
        var session = new com.naocraftlab.skins.client.EditorPreviewSession();
        PreviewRenderer<Object> renderer = new PreviewRenderer<>() {
            public void render(Object graphics, PreviewRequest request) { throw new AssertionError("Native queue is held"); }
            public com.naocraftlab.skins.client.PreviewModelFrames.Body body() { return session.body(); }
        };
        var registration = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<PreviewRenderer.BodyStatus>>();
        client.execute(() -> registration.set(runtime.observePreviewBody(initial.previews().get(0), renderer::displayKind)));
        drainRefreshQueues(worker, client);
        session.observeBody(registration.get());
        session.beginBody("first", cpm, cpm);
        assertEquals(0, refreshView(runtime, client).widgets().stream().filter(widget -> widget.id().startsWith(prefix)).count());
        if (cpm) session.body(new com.naocraftlab.skins.client.PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        java.util.function.Consumer<com.naocraftlab.skins.client.PreviewModelFrames.Body> complete = cpm
                ? session.displayBody(() -> true) : session.ordinaryDisplay(() -> true);
        var nativeQueue = new QueuedExecutor();
        nativeQueue.execute(() -> complete.accept(new com.naocraftlab.skins.client.PreviewModelFrames.Body(
                cpm ? PreviewRenderer.BodyStatus.READY : PreviewRenderer.BodyStatus.ORDINARY, () -> true)));
        registration.get().accept(PreviewRenderer.BodyStatus.READY);
        drainRefreshQueues(worker, client);
        assertTrue(history.stream().allMatch(count -> count == 0), "Preparation/enqueue is not display: " + history);
        nativeQueue.runFirst();
        drainRefreshQueues(worker, client);
        assertEquals(cpm ? 0 : 3, refreshView(runtime, client).widgets().stream().filter(widget -> widget.id().startsWith(prefix)).count());
        var transitions = new ArrayList<Long>();
        for (long count : history) if (transitions.isEmpty() || transitions.get(transitions.size() - 1) != count) transitions.add(count);
        assertEquals(cpm ? List.of(0L) : List.of(0L, 3L), transitions, "Every published opening view: " + history);
        int publications = history.size();
        registration.get().accept(cpm ? PreviewRenderer.BodyStatus.READY : PreviewRenderer.BodyStatus.ORDINARY);
        drainRefreshQueues(worker, client);
        assertEquals(publications, history.size(), "Unchanged display must not publish");
        runtime.resourcesReloaded();
        drainRefreshQueues(worker, client);
        assertEquals(0, refreshView(runtime, client).widgets().stream().filter(widget -> widget.id().startsWith(prefix)).count(),
                "READY-to-READY replacement must close controls before native work");
        registration.get().accept(PreviewRenderer.BodyStatus.READY);
        drainRefreshQueues(worker, client);
        assertEquals(0, refreshView(runtime, client).widgets().stream().filter(widget -> widget.id().startsWith(prefix)).count());
        runtime.close();
        drainRefreshQueues(worker, client);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "failed-query", "unconfirmed", "cpm", "ordinary"})
    void displayInputFailsClosedWithoutCpmInstallationOrLicensedSession(String outcome) {
        FakeOperations operations = new FakeOperations();
        operations.session = session(SessionStatus.UNCHECKED);
        operations.providers = operations.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        var visibility = new ProviderTestVisibility();
        var runtime = providerLayerRuntime(operations, Runnable::run, Optional.of(visibility), new AtomicInteger());
        runtime.initialize();
        assertFalse(runtime.snapshot().skinExtensionEnvironment().cpmPresent());
        var preview = runtime.view(854, 480, 0, 0).previews().get(0);
        var callback = runtime.observePreviewBody(preview, () -> switch (outcome) {
            case "null" -> null;
            case "failed-query" -> throw new IllegalStateException("synthetic query");
            case "unconfirmed" -> PreviewRenderer.DisplayKind.UNCONFIRMED;
            case "cpm" -> PreviewRenderer.DisplayKind.CPM;
            case "ordinary" -> PreviewRenderer.DisplayKind.ORDINARY;
            default -> throw new AssertionError(outcome);
        });
        callback.accept(PreviewRenderer.BodyStatus.READY);
        assertEquals(outcome.equals("ordinary"), runtime.view(854, 480, 0, 0).widget("providers.outer_layer.head").isPresent());
        runtime.dispatchWidget("providers.outer_layer.head", true, InteractionOrigin.KEYBOARD);
        assertEquals(outcome.equals("ordinary") ? 1 : 0, visibility.applied.size());
        runtime.close();
    }

    @Test
    void rebindDoesNotPublishUnconfirmedOrdinaryControls() {
        FakeOperations operations = new FakeOperations();
        operations.providers = operations.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        var runtime = providerLayerRuntime(operations, Runnable::run, Optional.of(new ProviderTestVisibility()), new AtomicInteger());
        runtime.initialize();
        var session = new com.naocraftlab.skins.client.EditorPreviewSession();
        session.beginBody("first", false, false);
        session.ordinaryDisplay(() -> true).accept(new com.naocraftlab.skins.client.PreviewModelFrames.Body(
                PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        PreviewRenderer<Object> renderer = new PreviewRenderer<>() {
            public void render(Object graphics, PreviewRequest request) { throw new AssertionError(); }
            public com.naocraftlab.skins.client.PreviewModelFrames.Body body() { return session.body(); }
        };
        var callback = runtime.observePreviewBody(runtime.view(854, 480, 0, 0).previews().get(0), renderer::displayKind);
        session.observeBody(callback);
        callback.accept(PreviewRenderer.BodyStatus.ORDINARY);
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.outer_layer.head").isPresent());
        var history = new ArrayList<Long>();
        runtime.subscribe(ignored -> history.add(runtime.view(854, 480, 0, 0).widgets().stream()
                .filter(widget -> widget.id().startsWith("providers.outer_layer.")).count()));
        history.clear();
        session.beginBody("replacement", false, false);
        assertEquals(0, runtime.view(854, 480, 0, 0).widgets().stream()
                .filter(widget -> widget.id().startsWith("providers.outer_layer.")).count(), "Rebind must hide unconfirmed display");
        assertTrue(history.stream().allMatch(count -> count == 0));
        runtime.close();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(AppearanceProviders.Component.class)
    void displayedProviderBodyUsesCurrentSupplierInsteadOfPreparationPayload(AppearanceProviders.Component component) throws Exception {
        FakeOperations operations = new FakeOperations();
        operations.providers = operations.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        var visibility = new ProviderTestVisibility();
        var runtime = providerLayerRuntime(operations, Runnable::run, Optional.of(visibility), new AtomicInteger());
        runtime.useSkinExtensionEnvironmentSource(() -> new com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.Snapshot(1, Map.of(), true));
        runtime.initialize();
        runtime.dispatchWidget("providers.tab." + component);
        var preview = runtime.view(854, 480, 0, 0).previews().get(0);
        var displayed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Supplier<PreviewRenderer.DisplayKind> supplier = () -> displayed.get()
                ? PreviewRenderer.DisplayKind.CPM : PreviewRenderer.DisplayKind.ORDINARY;
        var callback = runtime.observePreviewBody(preview, supplier);
        callback.accept(PreviewRenderer.BodyStatus.READY);
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.outer_layer.head").isPresent());
        runtime.dispatchWidget("providers.outer_layer.head", false, InteractionOrigin.KEYBOARD);
        var applied = List.copyOf(visibility.applied);
        displayed.set(true);
        assertTrue(runtime.snapshot().skinExtensionEnvironment().cpmPresent());
        callback.accept(PreviewRenderer.BodyStatus.ORDINARY);
        var hidden = runtime.view(854, 480, 0, 0);
        assertTrue(hidden.widget("providers.outer_layer.head").isEmpty(), "actual Providers body must hide controls independently of preparation enum");
        assertTrue(hidden.navigationNodes().stream().noneMatch(node -> node.id().startsWith("providers.outer_layer.")));
        assertEquals("providers.tab." + component, hidden.focusRequest().orElseThrow().widgetId());
        for (String group : List.of("head", "body", "legs")) {
            runtime.dispatchWidget("providers.outer_layer." + group);
            runtime.dispatchWidget("providers.outer_layer." + group, true, InteractionOrigin.KEYBOARD);
            runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, "providers.outer_layer." + group);
        }
        assertEquals(applied, visibility.applied);
        runtime.dispatchWidget("providers.tab.CAPE");
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.outer_layer.head").isEmpty());
        displayed.set(false);
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.outer_layer.head").isPresent());
        assertEquals(applied, visibility.applied);
        runtime.close();
    }

    @Test
    void editorFailureOverlayKeepsRuntimeDragScrollAndFocusAtCaptionCoordinates() {
        for (var status : List.of(PreviewRenderer.BodyStatus.FAILED, PreviewRenderer.BodyStatus.BLOCKED,
                PreviewRenderer.BodyStatus.TIMED_OUT)) {
            for (int[] size : List.of(new int[]{854, 480}, new int[]{240, 240}, new int[]{854, 180}, new int[]{1280, 240})) {
                FakeOperations first = new FakeOperations();
                first.account = TestFixtures.account(1);
                FakeOperations second = new FakeOperations();
                second.account = first.account;
                try (var baseline = runtime(first, Runnable::run, Optional.empty());
                     var overlay = runtime(second, Runnable::run, Optional.empty())) {
                    baseline.initialize(); overlay.initialize();
                    String edit = "gallery.preset." + first.account.presets().get(0).id() + ".edit";
                    baseline.dispatchWidget(edit); overlay.dispatchWidget(edit);
                    var before = baseline.view(size[0], size[1], 0, 0);
                    var preview = overlay.view(size[0], size[1], 0, 0).previews().get(0);
                    overlay.observePreviewBody(preview, () -> PreviewRenderer.DisplayKind.UNCONFIRMED).accept(status);
                    var failed = overlay.view(size[0], size[1], 0, 0);
                    Bounds caption = failed.texts().stream().filter(t -> t.id().equals("editor.preview.error")).findFirst().orElseThrow().bounds();
                    assertEquals(before.previews(), failed.previews());
                    double x = caption.x() + caption.width() / 2.0;
                    double y = caption.bottom() - 1;
                    assertTrue(PointerRouting.hit(before, x, y).anyPreview());
                    assertEquals(PointerRouting.hit(before, x, y), PointerRouting.hit(failed, x, y));
                    baseline.pointerPressed(x, y, 0); overlay.pointerPressed(x, y, 0);
                    baseline.pointerDragged(x + 12, y - 7, 0, 12, -7);
                    overlay.pointerDragged(x + 12, y - 7, 0, 12, -7);
                    baseline.pointerReleased(x + 12, y - 7, 0); overlay.pointerReleased(x + 12, y - 7, 0);
                    baseline.pointerScrolled(x, y, 0, 1); overlay.pointerScrolled(x, y, 0, 1);
                    var normalAfter = baseline.view(size[0], size[1], 0, 0);
                    var failedAfter = overlay.view(size[0], size[1], 0, 0);
                    assertEquals(normalAfter.previews(), failedAfter.previews());
                    assertNotEquals(before.previews().get(0).yawDegrees(), normalAfter.previews().get(0).yawDegrees());
                    assertNotEquals(before.previews().get(0).scale(), normalAfter.previews().get(0).scale());
                    assertEquals(normalAfter.focusRequest(), failedAfter.focusRequest());
                    assertEquals(normalAfter.widgets(), failedAfter.widgets());
                    assertEquals(first.account, second.account);
                    assertEquals(0, first.applyCalls + second.applyCalls);
                }
            }
        }
    }

    @Test
    void editorBodyHidesOnlyLayersRejectsStaleActionsAndRestoresDraftControls() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        var runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.useSkinExtensionEnvironmentSource(() -> new com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.Snapshot(1, Map.of(), true));
        runtime.initialize();
        runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
        var preview = runtime.view(854, 480, 0, 0).previews().get(0);
        var body = new java.util.concurrent.atomic.AtomicReference<>(new com.naocraftlab.skins.client.PreviewModelFrames.Body(
                PreviewRenderer.BodyStatus.ORDINARY, () -> true));
        PreviewRenderer<Object> renderer = new PreviewRenderer<>() {
            public void render(Object graphics, PreviewRequest request) { throw new AssertionError("No rendering"); }
            public com.naocraftlab.skins.client.PreviewModelFrames.Body body() { return body.get(); }
        };
        var callback = runtime.observePreviewBody(preview, renderer::displayKind);
        callback.accept(PreviewRenderer.BodyStatus.READY);
        runtime.dispatchWidget("editor.outer_layer.body", false, InteractionOrigin.KEYBOARD);
        var focused = runtime.view(854, 480, 0, 0);
        assertTrue(focused.focusRequest().isPresent(), "keyboard layer activation must preserve logical Editor focus");
        assertEquals("editor.outer_layer.body", focused.focusRequest().orElseThrow().widgetId());
        var draft = runtime.snapshot().editor().orElseThrow().saveRequest();
        var account = operations.account;
        body.set(new com.naocraftlab.skins.client.PreviewModelFrames.Body(PreviewRenderer.BodyStatus.READY, () -> true));
        callback.accept(PreviewRenderer.BodyStatus.ORDINARY);
        var hidden = runtime.view(854, 480, 0, 0);
        assertTrue(hidden.widgets().stream().noneMatch(widget -> widget.id().startsWith("editor.outer_layer.")));
        assertTrue(hidden.navigationNodes().stream().noneMatch(node -> node.id().startsWith("editor.outer_layer.")));
        assertEquals("editor.tab.appearance", hidden.focusRequest().orElseThrow().widgetId());
        assertTrue(hidden.widget("editor.save").isPresent());
        assertTrue(hidden.widget("editor.cancel").isPresent());
        assertTrue(hidden.widget("editor.model_choice.slim").isPresent());
        assertEquals(preview.anchorBounds(), hidden.previews().get(0).anchorBounds());
        for (String group : List.of("head", "body", "legs")) {
            runtime.dispatchWidget("editor.outer_layer." + group);
            runtime.dispatchWidget("editor.outer_layer." + group, true, InteractionOrigin.KEYBOARD);
            assertFalse(runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, "editor.outer_layer." + group));
        }
        assertEquals(draft, runtime.snapshot().editor().orElseThrow().saveRequest());
        assertEquals(account, operations.account);
        for (var status : List.of(PreviewRenderer.BodyStatus.ORDINARY, PreviewRenderer.BodyStatus.PENDING,
                PreviewRenderer.BodyStatus.FAILED, PreviewRenderer.BodyStatus.BLOCKED, PreviewRenderer.BodyStatus.TIMED_OUT)) {
            body.set(new com.naocraftlab.skins.client.PreviewModelFrames.Body(status, () -> true));
            callback.accept(status);
            var fallback = runtime.view(854, 480, 0, 0);
            assertEquals(status == PreviewRenderer.BodyStatus.ORDINARY,
                    fallback.widget("editor.outer_layer.head").isPresent(), status.name());
            assertEquals(draft, runtime.snapshot().editor().orElseThrow().saveRequest());
            body.set(new com.naocraftlab.skins.client.PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true));
            callback.accept(status);
            assertTrue(runtime.view(854, 480, 0, 0).widget("editor.outer_layer.head").isPresent(), "Actual fallback after " + status);
        }
        runtime.dispatchWidget("editor.cancel");
        assertEquals(account, operations.account);
        assertEquals(0, operations.editorSaveCalls);
        runtime.close();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"resources", "world", "model-roundtrip", "reopen", "session"})
    void oldEditorBodyRegistrationCannotCrossScopeRetirement(String transition) {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        var runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.useSkinExtensionEnvironmentSource(() -> new com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.Snapshot(1, Map.of(), true));
        runtime.initialize();
        String edit = "gallery.preset." + operations.account.presets().get(0).id() + ".edit";
        runtime.dispatchWidget(edit);
        var preview = runtime.view(854, 480, 0, 0).previews().get(0);
        AtomicInteger reads = new AtomicInteger();
        var old = runtime.observePreviewBody(preview, () -> {
            reads.incrementAndGet();
            return PreviewRenderer.DisplayKind.CPM;
        });
        old.accept(PreviewRenderer.BodyStatus.READY);
        assertTrue(runtime.view(854, 480, 0, 0).widget("editor.outer_layer.head").isEmpty());
        switch (transition) {
            case "resources" -> runtime.resourcesReloaded();
            case "world" -> runtime.importWorldChanged();
            case "model-roundtrip" -> {
                String originalVariant = preview.variant() == SkinVariant.SLIM ? "slim" : "classic";
                runtime.dispatchWidget("editor.model_choice." + (preview.variant() == SkinVariant.SLIM ? "classic" : "slim"));
                runtime.dispatchWidget("editor.model_choice." + originalVariant);
            }
            case "reopen" -> {
                runtime.dispatchWidget("editor.cancel");
                runtime.dispatchWidget(edit);
            }
            case "session" -> {
                operations.session = new SessionValidation(SessionStatus.OFFLINE_OR_INVALID,
                        new GameSessionTokenSource.SessionIdentity(new UUID(0, 903), "SyntheticB"),
                        null, (SessionFailureContext) null, "synthetic switch");
            }
            default -> throw new AssertionError(transition);
        }
        int retiredReads = reads.get();
        old.accept(PreviewRenderer.BodyStatus.READY);
        assertTrue(runtime.view(854, 480, 0, 0).widget("editor.outer_layer.head").isEmpty(), transition);
        assertEquals(retiredReads, reads.get(), "retired suppliers must not be queried");
        runtime.close();
    }

    @Test
    void queuedBodyCallbackAndOldReleaseCannotRetireNewRegistrationOrPublishAFrameLoop() {
        FakeOperations operations = new FakeOperations();
        operations.providers = operations.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        TrackingClientExecutor client = new TrackingClientExecutor();
        QueuedExecutor worker = new QueuedExecutor();
        var runtime = new ClientRuntime(new DefaultRuntimeServices(), operations, client, CANCELLED_PICKER, worker, TEXT,
                Optional.empty(), DiagnosticSinks.discarding());
        runtime.useSkinExtensionEnvironmentSource(() -> new com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.Snapshot(1, Map.of(), true));
        runtime.initialize();
        drainRefreshQueues(worker, client);
        var old = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<PreviewRenderer.BodyStatus>>();
        var next = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<PreviewRenderer.BodyStatus>>();
        var first = new java.util.concurrent.atomic.AtomicBoolean();
        client.execute(() -> old.set(runtime.observePreviewBody(runtime.view(854, 480, 0, 0).previews().get(0),
                () -> first.get() ? PreviewRenderer.DisplayKind.CPM : PreviewRenderer.DisplayKind.UNCONFIRMED)));
        client.runFirst();
        first.set(true);
        old.get().accept(PreviewRenderer.BodyStatus.READY);
        client.execute(() -> next.set(runtime.observePreviewBody(runtime.view(854, 480, 0, 0).previews().get(0), () -> PreviewRenderer.DisplayKind.CPM)));
        client.runFirst();
        client.runFirst();
        client.execute(() -> runtime.reconcilePreviewBody(next.get()));
        client.runFirst();
        AtomicInteger publications = new AtomicInteger();
        runtime.subscribe(snapshot -> publications.incrementAndGet());
        drainRefreshQueues(worker, client);
        int before = publications.get();
        for (int index = 0; index < 5; index++) {
            old.get().accept(PreviewRenderer.BodyStatus.FAILED);
            next.get().accept(PreviewRenderer.BodyStatus.ORDINARY);
            client.execute(() -> runtime.invalidatePreviewBody(old.get()));
        }
        drainRefreshQueues(worker, client);
        assertTrue(refreshView(runtime, client).widget("providers.outer_layer.head").isEmpty());
        assertEquals(before, publications.get());
        client.execute(() -> runtime.invalidatePreviewBody(next.get()));
        client.runFirst();
        assertTrue(refreshView(runtime, client).widget("providers.outer_layer.head").isEmpty());
        runtime.close();
        drainRefreshQueues(worker, client);
        old.get().accept(PreviewRenderer.BodyStatus.READY);
        next.get().accept(PreviewRenderer.BodyStatus.READY);
        drainRefreshQueues(worker, client);
        assertTrue(runtime.closed());
    }

    @Test
    void displayedBodyQueryRechecksScopeAndRegistrationAfterLiveInput() throws Exception {
        FakeOperations operations = new FakeOperations();
        operations.providers = operations.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        var runtime = providerLayerRuntime(operations, Runnable::run, Optional.of(new ProviderTestVisibility()), new AtomicInteger());
        runtime.useSkinExtensionEnvironmentSource(() -> new com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.Snapshot(1, Map.of(), true));
        runtime.initialize();
        var preview = runtime.view(854, 480, 0, 0).previews().get(0);
        var retire = new java.util.concurrent.atomic.AtomicBoolean(true);
        var old = runtime.observePreviewBody(preview, () -> {
            if (retire.getAndSet(false)) runtime.importWorldChanged();
            return PreviewRenderer.DisplayKind.ORDINARY;
        });
        old.accept(PreviewRenderer.BodyStatus.READY);
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.outer_layer.head").isEmpty());
        var replace = new java.util.concurrent.atomic.AtomicBoolean(true);
        var replacement = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<PreviewRenderer.BodyStatus>>();
        var fresh = runtime.view(854, 480, 0, 0).previews().get(0);
        var superseded = runtime.observePreviewBody(fresh, () -> {
            if (replace.getAndSet(false)) replacement.set(runtime.observePreviewBody(fresh, () -> PreviewRenderer.DisplayKind.ORDINARY));
            return PreviewRenderer.DisplayKind.CPM;
        });
        superseded.accept(PreviewRenderer.BodyStatus.READY);
        runtime.invalidatePreviewBody(superseded);
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.outer_layer.head").isPresent());
        replacement.get().accept(PreviewRenderer.BodyStatus.READY);
        var capability = ClientRuntime.class.getDeclaredField("bodyCapability");
        capability.setAccessible(true);
        assertTrue(capability.get(runtime) != null);
        runtime.invalidatePreviewBody(replacement.get());
        assertNull(capability.get(runtime), "Retirement releases the live renderer input");
        runtime.close();
    }

    @Test
    void displayedBodyRetiresInputWhenSessionChangesInsideSupplier() throws Exception {
        FakeOperations operations = new FakeOperations();
        operations.providers = operations.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        var runtime = providerLayerRuntime(operations, Runnable::run, Optional.of(new ProviderTestVisibility()), new AtomicInteger());
        runtime.useSkinExtensionEnvironmentSource(() -> new com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.Snapshot(1, Map.of(), true));
        runtime.initialize();
        var preview = runtime.view(854, 480, 0, 0).previews().get(0);
        var callback = runtime.observePreviewBody(preview, () -> {
            operations.session = new SessionValidation(SessionStatus.OFFLINE_OR_INVALID,
                    new GameSessionTokenSource.SessionIdentity(new UUID(0, 903), "SyntheticB"),
                    null, (SessionFailureContext) null, "synthetic switch");
            return PreviewRenderer.DisplayKind.ORDINARY;
        });
        callback.accept(PreviewRenderer.BodyStatus.READY);
        var capability = ClientRuntime.class.getDeclaredField("bodyCapability");
        capability.setAccessible(true);
        assertNull(capability.get(runtime), "Post-query scope retirement must release the renderer immediately");
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.outer_layer.head").isEmpty());
        runtime.close();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"resources", "world", "source", "model"})
    void providerBodyRetirementRequiresNewCurrentDisplayEvenForReadyReplacement(String transition) {
        FakeOperations operations = new FakeOperations();
        operations.providers = operations.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        var visibility = new ProviderTestVisibility();
        var runtime = providerLayerRuntime(operations, Runnable::run, Optional.of(visibility), new AtomicInteger());
        runtime.useSkinExtensionEnvironmentSource(() -> new com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.Snapshot(1, Map.of(), true));
        runtime.initialize();
        var preview = runtime.view(854, 480, 0, 0).previews().get(0);
        var reads = new AtomicInteger();
        var old = runtime.observePreviewBody(preview, () -> { reads.incrementAndGet(); return PreviewRenderer.DisplayKind.CPM; });
        old.accept(PreviewRenderer.BodyStatus.READY);
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.outer_layer.head").isEmpty());
        switch (transition) {
            case "resources" -> runtime.resourcesReloaded();
            case "world" -> runtime.importWorldChanged();
            case "source", "model" -> {
                operations.providers = new AppearanceProviders(operations.providers.skin().select(1,
                        new ProviderSkin("a".repeat(64), transition.equals("model") ? SkinVariant.SLIM : SkinVariant.CLASSIC)),
                        operations.providers.cape());
                runtime.dispatchWidget("gallery.providers");
            }
            default -> throw new AssertionError(transition);
        }
        int retiredReads = reads.get();
        old.accept(PreviewRenderer.BodyStatus.READY);
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.outer_layer.head").isEmpty(), transition);
        assertEquals(retiredReads, reads.get());
        var replacement = runtime.observePreviewBody(runtime.view(854, 480, 0, 0).previews().get(0), () -> PreviewRenderer.DisplayKind.CPM);
        replacement.accept(PreviewRenderer.BodyStatus.ORDINARY);
        runtime.invalidatePreviewBody(old);
        old.accept(PreviewRenderer.BodyStatus.FAILED);
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.outer_layer.head").isEmpty(), "Fresh actual READY is independent of preparation enum");
        assertTrue(visibility.applied.isEmpty());
        runtime.close();
    }

    @Test
    void providerLayersUseFreshOptionsAndPreserveAppearanceThroughBackEscapeAndReopen() {
        for (boolean connected : List.of(false, true)) {
            FakeOperations operations = new FakeOperations();
            operations.account = TestFixtures.account(1);
            operations.localFirst = true;
            operations.activePresetId = Optional.of(operations.account.presets().get(0).id());
            operations.appearanceRevision = 7;
            operations.appearanceSyncStatus = AppearanceSyncStatus.PARTIAL;
            operations.providers = operations.providers.select(7,
                    new com.naocraftlab.skins.core.provider.ProviderSkin("a".repeat(64), SkinVariant.CLASSIC), null);
            operations.providers = operations.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                    .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
            operations.durable = Optional.of(new ClientOperations.DurableAppearance(
                    operations.account.accountId(), operations.appearanceRevision, operations.appearanceSyncStatus,
                    operations.activePresetId, Optional.empty(),
                    Optional.of(operations.account.presets().get(0).outerLayerVisibility()), operations.providers));
            if (!connected) operations.session = session(SessionStatus.OFFLINE_OR_INVALID);
            var visibility = new ProviderTestVisibility();
            AtomicInteger notifications = new AtomicInteger();
            ClientRuntime runtime = providerLayerRuntime(operations, Runnable::run, Optional.of(visibility), notifications);
            runtime.initialize();
            visibility.applied.clear();
            runtime.view(854, 480, 0, 0);
            runtime.dispatchWidget("providers.row.OFFLINE");
            runtime.pointerScrolled(100, 100, 0, -1);
            TestFixtures.confirmOrdinaryDisplay(runtime);
            var beforeView = runtime.view(854, 480, 0, 0);
            var before = runtime.snapshot();
            var account = operations.account;
            var durable = operations.durable;
            int reconciliations = operations.reconciliationCalls;
            int refreshes = notifications.get();
            visibility.value = com.naocraftlab.skins.client.OuterLayerVisibility.noneVisible();
            int reads = visibility.reads;
            runtime.dispatchWidget("providers.outer_layer.body", true, InteractionOrigin.KEYBOARD);
            assertTrue(visibility.reads > reads);
            assertEquals(List.of(EditorOuterLayerCycle.cycle("body",
                    com.naocraftlab.skins.client.OuterLayerVisibility.noneVisible(), -1)), visibility.applied);
            var afterView = runtime.view(854, 480, 0, 0);
            assertEquals(visibility.value, afterView.previews().get(0).outerLayerVisibility());
            assertEquals("providers.outer_layer.body", afterView.focusRequest().orElseThrow().widgetId());
            assertEquals(beforeView.previews().get(0).yawDegrees(), afterView.previews().get(0).yawDegrees());
            assertEquals(beforeView.previews().get(0).pitchDegrees(), afterView.previews().get(0).pitchDegrees());
            assertEquals(beforeView.previews().get(0).scale(), afterView.previews().get(0).scale());
            assertEquals(beforeView.widget("providers.row.OFFLINE").orElseThrow().value(),
                    afterView.widget("providers.row.OFFLINE").orElseThrow().value());
            runtime.dispatchWidget("providers.outer_layer.head");
            assertTrue(runtime.view(854, 480, 0, 0).iconDecorations().stream()
                    .filter(icon -> icon.providerTexture().map(ViewSpec.ProviderTexture::skin).orElse(false))
                    .allMatch(icon -> icon.providerTexture().orElseThrow().overlay()));
            runtime.dispatchWidget("providers.tab.CAPE");
            assertTrue(runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, "providers.outer_layer.legs"));
            assertEquals(account, operations.account);
            assertEquals(durable, operations.durable);
            assertEquals(before.providers(), runtime.snapshot().providers());
            assertEquals(before.activePresetId(), runtime.snapshot().activePresetId());
            assertEquals(before.intentRevision(), runtime.snapshot().intentRevision());
            assertEquals(before.syncStatus(), runtime.snapshot().syncStatus());
            assertEquals(0, operations.applyCalls);
            assertEquals(0, operations.editorSaveCalls);
            assertNull(operations.providerRefresh);
            assertEquals(0, operations.optifineRefreshes);
            assertEquals(0, operations.skinMcRefreshes);
            assertEquals(reconciliations, operations.reconciliationCalls);
            assertEquals(refreshes, notifications.get());
            var savedOptions = visibility.value;
            for (boolean escape : List.of(false, true)) {
                if (escape) runtime.escapePressed(); else runtime.dispatchWidget("providers.back");
                assertEquals(savedOptions, visibility.value);
                runtime.reopen();
                assertEquals(savedOptions, runtime.view(854, 480, 0, 0).previews().get(0).outerLayerVisibility());
            }
            assertEquals(3, visibility.applied.size());
            runtime.close();
        }
    }

    @Test
    void layerActionKeepsReadOnlyRowInspectionAndCapeMode() {
        FakeOperations operations = new FakeOperations();
        var restricted = operations.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        operations.providers = new AppearanceProviders(restricted.skin(), restricted.cape().enable(BuiltinProvider.OPTIFINE)
                .observeOptifine(new com.naocraftlab.skins.core.provider.ProviderCape("fixture", "b".repeat(64), true)));
        var visibility = new ProviderTestVisibility();
        var runtime = providerLayerRuntime(operations, Runnable::run, Optional.of(visibility), new AtomicInteger());
        runtime.initialize();
        runtime.dispatchWidget("providers.tab.CAPE");
        runtime.dispatchWidget("providers.row.OPTIFINE");
        runtime.dispatchWidget("providers.preview_mode");
        TestFixtures.confirmOrdinaryDisplay(runtime);
        var before = runtime.view(854, 480, 0, 0);
        var providers = runtime.snapshot().providers();
        runtime.dispatchWidget("providers.outer_layer.head");
        var after = runtime.view(854, 480, 0, 0);
        assertEquals(before.widget("providers.row.OPTIFINE").orElseThrow().value(), after.widget("providers.row.OPTIFINE").orElseThrow().value());
        assertEquals(before.previews().get(0).capeId(), after.previews().get(0).capeId());
        assertEquals(before.previews().get(0).capeMode(), after.previews().get(0).capeMode());
        assertEquals(providers, runtime.snapshot().providers());
        assertEquals(1, visibility.applied.size());
        assertFalse(after.previews().get(0).outerLayerVisibility().visible(OuterLayerPart.HEAD));
        runtime.close();
    }

    @Test
    void providerLayersRemainAvailableDuringRefreshAndRejectHiddenStaleChooserAndInvalidActions() {
        FakeOperations operations = new FakeOperations();
        operations.providers = operations.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        var visibility = new ProviderTestVisibility();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = providerLayerRuntime(operations, worker, Optional.of(visibility), new AtomicInteger());
        runtime.initialize();
        runtime.dispatchWidget("providers.outer_layer.head");
        assertTrue(visibility.applied.isEmpty());
        drainWorker(worker);
        TestFixtures.confirmOrdinaryDisplay(runtime);
        runtime.dispatchWidget("providers.refresh");
        runtime.dispatchWidget("providers.outer_layer.head");
        assertEquals(1, visibility.applied.size());
        drainWorker(worker);
        runtime.dispatchWidget("providers.outer_layer.invalid");
        runtime.dispatchWidget("providers.add");
        runtime.dispatchWidget("providers.outer_layer.head");
        assertEquals(1, visibility.applied.size());
        runtime.escapePressed();
        TestFixtures.confirmOrdinaryDisplay(runtime);
        runtime.dispatchWidget("providers.outer_layer.head", false, InteractionOrigin.KEYBOARD);
        assertEquals(2, visibility.applied.size());
        runtime.dispatchWidget("providers.tab.CAPE");
        runtime.dispatchWidget("providers.add");
        runtime.dispatchWidget("providers.row.OFFLINE");
        drainWorker(worker);
        var visibleWriters = runtime.view(854, 480, 0, 0);
        assertTrue(visibleWriters.widget("providers.outer_layer.head").isEmpty());
        assertTrue(visibleWriters.focusRequest().stream().noneMatch(request -> request.widgetId().startsWith("providers.outer_layer.")));
        assertTrue(visibleWriters.navigationNodes().stream().noneMatch(node -> node.id().startsWith("providers.outer_layer.")));
        runtime.dispatchWidget("providers.outer_layer.head");
        runtime.dispatchWidget("providers.back");
        runtime.dispatchWidget("providers.outer_layer.head");
        assertEquals(2, visibility.applied.size());
        runtime.dispatchWidget("gallery.providers");
        drainWorker(worker);
        runtime.dispatchWidget("providers.remove.OFFLINE");
        drainWorker(worker);
        TestFixtures.confirmOrdinaryDisplay(runtime);
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.outer_layer.head").isPresent());
        assertEquals(2, visibility.applied.size());
        runtime.close();
        ClientRuntime withoutController = providerLayerRuntime(operations, Runnable::run, Optional.empty(), new AtomicInteger());
        withoutController.initialize();
        TestFixtures.confirmOrdinaryDisplay(withoutController);
        assertFalse(withoutController.view(854, 480, 0, 0).widget("providers.outer_layer.head").orElseThrow().enabled());
        withoutController.dispatchWidget("providers.outer_layer.head");
        withoutController.close();
    }

    private static ClientRuntime providerLayerRuntime(FakeOperations operations, Executor worker,
            Optional<com.naocraftlab.skins.client.OuterLayerVisibilityController> visibility, AtomicInteger notifications) {
        return new ClientRuntime(new DefaultRuntimeServices(), operations, CLIENT, CANCELLED_PICKER, worker, TEXT,
                Optional.of(new AppearanceRefresh() {
                    public CompletableFuture<Result> afterMutation(PresetApplicationOutcome outcome, java.util.function.Consumer<Result> publisher) {
                        notifications.incrementAndGet();
                        return CompletableFuture.completedFuture(Result.NOT_APPLICABLE);
                    }
                    public CompletableFuture<Result> afterReconnect(AppliedAppearance appearance, java.util.function.Consumer<Result> publisher) {
                        notifications.incrementAndGet();
                        return CompletableFuture.completedFuture(Result.NOT_APPLICABLE);
                    }
                    public void providerVisibility(com.naocraftlab.skins.client.ProviderVisibility visibility) {}
                    public void close() {}
                }), visibility, Optional.of(notifications::incrementAndGet), DiagnosticSinks.discarding());
    }

    private static final class ProviderTestVisibility implements com.naocraftlab.skins.client.OuterLayerVisibilityController {
        private com.naocraftlab.skins.client.OuterLayerVisibility value = com.naocraftlab.skins.client.OuterLayerVisibility.allVisible();
        private final List<com.naocraftlab.skins.client.OuterLayerVisibility> applied = new ArrayList<>();
        private int reads;
        public com.naocraftlab.skins.client.OuterLayerVisibility current() { reads++; return value; }
        public void applyDurable(com.naocraftlab.skins.client.OuterLayerVisibility mask) { value = mask; applied.add(mask); }
    }

    @Test
    void persistedEmptyProvidersRouteDirectlyAndAddingWritersStaysOnProviders() {
        FakeOperations operations = new FakeOperations();
        for (var component : AppearanceProviders.Component.values()) for (var provider : BuiltinProvider.values()) {
            operations.providers = operations.providers.disable(component, provider);
        }
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.verifyStorageAccess();
        assertEquals("providers", runtime.view(854, 480, 0, 0).screenId());
        runtime.initialize();
        runtime.dispatchWidget("providers.add");
        assertEquals("provider_chooser", runtime.view(320, 100, 0, 0).screenId());
        runtime.nativeScrollPositionChanged("providers.chooser", 20);
        assertTrue(runtime.view(320, 100, 0, 0).scrollSurface("providers.chooser").orElseThrow().offsetPixels() > 0);
        runtime.dispatchWidget("providers.row.OFFLINE");
        runtime.dispatchWidget("providers.tab.CAPE");
        runtime.dispatchWidget("providers.add");
        runtime.dispatchWidget("providers.row.OFFLINE");
        assertTrue(runtime.snapshot().providers().galleryAvailable());
        assertEquals("providers", runtime.view(854, 480, 0, 0).screenId());
        runtime.dispatchWidget("providers.back");
        assertEquals("gallery", runtime.view(854, 480, 0, 0).screenId());
    }

    @Test
    void enteringProvidersReloadsSharedStateAndTabClicksResetRowInspection() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        operations.providers = operations.providers.move(AppearanceProviders.Component.SKIN, BuiltinProvider.MINECRAFT, -1);
        runtime.dispatchWidget("gallery.providers");
        assertEquals(operations.providers, runtime.snapshot().providers());
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.search").isEmpty());
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.row.MINECRAFT").orElseThrow().value().isEmpty());
        runtime.dispatchWidget("providers.row.MINECRAFT");
        assertEquals(Optional.of("selected"), runtime.view(854, 480, 0, 0).widget("providers.row.MINECRAFT").orElseThrow().value());
        runtime.dispatchWidget("providers.tab.CAPE");
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.row.MINECRAFT").orElseThrow().value().isEmpty());
        runtime.dispatchWidget("providers.refresh");
        assertEquals(AppearanceProviders.Component.CAPE, operations.providerRefresh);
        runtime.dispatchWidget("providers.tab.SKIN");
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.row.MINECRAFT").orElseThrow().value().isEmpty());
    }

    @Test
    void refreshScopeTracksSelectionWithoutFollowingHoverOrFocusAndResetsWithTabRemoveReload() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.providers");
        for (var component : AppearanceProviders.Component.values()) {
            runtime.dispatchWidget("providers.tab." + component);
            assertEquals("nclskins.providers.refresh_all", runtime.view(320, 240, 0, 0).widget("providers.refresh").orElseThrow().label().key());
            runtime.dispatchWidget("providers.refresh");
            assertEquals(ProviderRefreshRequest.all(component), operations.providerRefreshRequests.get(operations.providerRefreshRequests.size() - 1));
            runtime.dispatchWidget("providers.row.OFFLINE");
            var other = runtime.view(320, 240, 0, 0).widget("providers.row.MINECRAFT").orElseThrow().bounds();
            runtime.view(320, 240, other.x() + 1, other.y() + 1);
            runtime.dispatchNavigation(ViewSpec.NavigationCommand.DOWN, "providers.row.OFFLINE");
            assertEquals("nclskins.providers.refresh", runtime.view(320, 240, 0, 0).widget("providers.refresh").orElseThrow().label().key());
            runtime.dispatchWidget("providers.refresh");
            assertEquals(ProviderRefreshRequest.one(component, BuiltinProvider.OFFLINE), operations.providerRefreshRequests.get(operations.providerRefreshRequests.size() - 1));
            runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, "providers.tab." + component);
            assertEquals("nclskins.providers.refresh_all", runtime.view(320, 240, 0, 0).widget("providers.refresh").orElseThrow().label().key());
            runtime.dispatchWidget("providers.row.OFFLINE");
            runtime.dispatchWidget("providers.remove.OFFLINE");
            assertEquals("nclskins.providers.refresh_all", runtime.view(320, 240, 0, 0).widget("providers.refresh").orElseThrow().label().key());
            runtime.dispatchWidget("providers.row.MINECRAFT");
            operations.providers = operations.providers.disable(component, BuiltinProvider.MINECRAFT);
            runtime.dispatchWidget("gallery.providers");
            assertEquals("nclskins.providers.refresh_all", runtime.view(320, 240, 0, 0).widget("providers.refresh").orElseThrow().label().key());
            assertTrue(runtime.view(320, 240, 0, 0).widget("providers.row.MINECRAFT").isEmpty());
        }
        runtime.close();
    }

    @Test
    void selectedCooldownExpiresOnTickWithoutNewDemandOrSelectionChange() {
        FakeOperations operations = new FakeOperations();
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.tab.CAPE");
        runtime.dispatchWidget("providers.row.SKINMC");
        operations.capeCooldowns.put(BuiltinProvider.SKINMC, Duration.ofNanos(1));
        runtime.tick();
        var limited = runtime.view(200, 191, 0, 0).widget("providers.refresh").orElseThrow();
        assertFalse(limited.enabled());
        assertEquals("nclskins.providers.refresh", limited.label().key());
        assertEquals("nclskins.providers.refresh_rate_limited", limited.hint().orElseThrow().key());
        runtime.dispatchWidget("providers.refresh");
        runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, "providers.refresh");
        assertTrue(operations.providerRefreshRequests.isEmpty());
        assertEquals(0, operations.skinMcRefreshes);
        operations.capeCooldowns.clear();
        runtime.tick();
        var available = runtime.view(200, 191, 0, 0).widget("providers.refresh").orElseThrow();
        assertTrue(available.enabled());
        assertEquals("nclskins.providers.refresh", available.hint().orElseThrow().key());
        assertTrue(operations.providerRefreshRequests.isEmpty());
        operations.capeCooldowns.put(BuiltinProvider.SKINMC, Duration.ofSeconds(2));
        runtime.dispatchWidget("providers.row.OFFLINE");
        assertTrue(runtime.view(200, 191, 0, 0).widget("providers.refresh").orElseThrow().enabled());
        runtime.dispatchWidget("providers.row.SKINMC");
        runtime.tick();
        assertFalse(runtime.view(200, 191, 0, 0).widget("providers.refresh").orElseThrow().enabled());
        runtime.dispatchWidget("providers.tab.CAPE");
        assertTrue(runtime.view(200, 191, 0, 0).widget("providers.refresh").orElseThrow().enabled());
        runtime.close();
    }

    @Test
    void cooldownHintExpiresDuringInitialRefreshWithoutReleasingRefreshOrSubmittingAnotherRequest() {
        FakeOperations operations = new FakeOperations();
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC);
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        runtime.initialize();
        while (worker.size() > 0) worker.runFirst();
        runtime.dispatchWidget("gallery.providers");
        while (worker.size() > 0) worker.runFirst();
        runtime.dispatchWidget("providers.tab.CAPE");
        runtime.dispatchWidget("providers.row.SKINMC");
        runtime.dispatchWidget("providers.refresh");
        assertFalse(runtime.snapshot().busy());
        assertFalse(runtime.view(320, 240, 0, 0).widget("providers.refresh").orElseThrow().enabled());
        operations.capeCooldowns.put(BuiltinProvider.SKINMC, Duration.ofSeconds(1));
        runtime.tick();
        assertEquals("nclskins.providers.refresh_rate_limited", runtime.view(320, 240, 0, 0)
                .widget("providers.refresh").orElseThrow().hint().orElseThrow().key());
        operations.capeCooldowns.clear();
        runtime.tick();
        var button = runtime.view(320, 240, 0, 0).widget("providers.refresh").orElseThrow();
        assertFalse(button.enabled());
        assertEquals("nclskins.providers.refresh", button.hint().orElseThrow().key());
        assertEquals(0, operations.skinMcRefreshes);
        assertTrue(operations.providerRefreshRequests.isEmpty());
        while (worker.size() > 0) worker.runFirst();
        assertFalse(runtime.snapshot().busy());
        assertEquals(1, operations.skinMcRefreshes);
        assertEquals(1, operations.providerRefreshRequests.size());
        runtime.close();
    }

    @Test
    void transientClientRejectionDefersOneTerminalRefreshUntilAcceptedTick() {
        FakeOperations operations = new FakeOperations();
        QueuedExecutor worker = new QueuedExecutor();
        TrackingClientExecutor client = new TrackingClientExecutor();
        ClientRuntime runtime = new ClientRuntime(new DefaultRuntimeServices(), operations, client,
                CANCELLED_PICKER, worker, TEXT, Optional.empty(), DiagnosticSinks.discarding());
        runtime.initialize();
        while (!client.tasks.isEmpty() || worker.size() > 0) {
            if (!client.tasks.isEmpty()) client.runFirst();
            else worker.runFirst();
        }
        runtime.dispatchWidget("gallery.providers");
        while (!client.tasks.isEmpty() || worker.size() > 0) {
            if (!client.tasks.isEmpty()) client.runFirst();
            else worker.runFirst();
        }
        var publishedRefreshEnabled = new java.util.concurrent.atomic.AtomicBoolean(true);
        runtime.subscribe(snapshot -> publishedRefreshEnabled.set(runtime.view(320, 240, 0, 0)
                .widget("providers.refresh").orElseThrow().enabled()));
        client.runFirst();
        runtime.dispatchWidget("providers.row.OFFLINE");
        client.runFirst();
        runtime.dispatchWidget("providers.refresh");
        client.runFirst();
        assertFalse(runtime.snapshot().busy());
        assertFalse(publishedRefreshEnabled.get());
        client.reject = true;
        while (worker.size() > 0) worker.runFirst();
        assertFalse(runtime.snapshot().busy());
        assertFalse(publishedRefreshEnabled.get());
        assertEquals(1, operations.providerRefreshRequests.size());
        client.reject = false;
        runtime.tick();
        client.runFirst();
        assertFalse(runtime.snapshot().busy());
        assertTrue(publishedRefreshEnabled.get());
        assertEquals(1, operations.providerRefreshRequests.size());
        runtime.tick();
        client.runFirst();
        assertEquals(1, operations.providerRefreshRequests.size());
        runtime.close();
        client.runFirst();
    }

    @Test
    void allCooldownReleasesAtFirstExpiryWithoutPointerOrSelectionChange() {
        FakeOperations operations = new FakeOperations();
        operations.providers = operations.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC);
        operations.capeCooldowns.put(BuiltinProvider.OPTIFINE, Duration.ofSeconds(2));
        operations.capeCooldowns.put(BuiltinProvider.SKINMC, Duration.ofSeconds(10));
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.tab.CAPE");
        runtime.tick();
        assertFalse(runtime.view(320, 240, 0, 0).widget("providers.refresh").orElseThrow().enabled());
        runtime.dispatchWidget("providers.refresh");
        assertTrue(operations.providerRefreshRequests.isEmpty());
        operations.capeCooldowns.remove(BuiltinProvider.OPTIFINE);
        runtime.tick();
        var button = runtime.view(320, 240, 0, 0).widget("providers.refresh").orElseThrow();
        assertTrue(button.enabled());
        assertEquals("nclskins.providers.refresh_all", button.hint().orElseThrow().key());
        assertEquals(0, operations.optifineRefreshes);
        assertEquals(0, operations.skinMcRefreshes);
        runtime.close();
    }

    @Test
    void capeRefreshOffersOneScopedCandidateCheckpointWithUnchangedInputsAndSkinRefreshOffersNone() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.tab.CAPE");
        runtime.dispatchWidget("providers.row.MINECRAFT");
        runtime.dispatchWidget("providers.refresh");
        assertEquals(List.of(Set.of(BuiltinProvider.MINECRAFT)), operations.candidateCheckpoints);
        runtime.tick(); runtime.view(320, 240, 0, 0);
        assertEquals(1, operations.candidateCheckpoints.size(), "publishes and ticks do not replay the checkpoint");
        runtime.dispatchWidget("providers.refresh");
        assertEquals(2, operations.candidateCheckpoints.size(), "a later explicit checkpoint can recover failed preparation");
        runtime.dispatchWidget("providers.tab.SKIN");
        runtime.dispatchWidget("providers.row.MINECRAFT");
        runtime.dispatchWidget("providers.refresh");
        assertEquals(2, operations.candidateCheckpoints.size(), "skin observation cannot grant cape download permission");
        runtime.closeScreen();
        assertFalse(runtime.snapshot().busy());
        runtime.close();
    }

    @Test
    void refreshKeepsTheScreenAvailableAndPinsSelectionBeforeWorker() {
        FakeOperations operations = new FakeOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        runtime.initialize();
        while (worker.size() > 0) worker.runFirst();
        runtime.dispatchWidget("gallery.providers");
        while (worker.size() > 0) worker.runFirst();
        runtime.dispatchWidget("providers.row.OFFLINE");
        runtime.dispatchWidget("providers.refresh");
        assertFalse(runtime.snapshot().busy());
        assertFalse(runtime.view(320, 240, 0, 0).widget("providers.refresh").orElseThrow().enabled());
        runtime.dispatchWidget("providers.tab.CAPE");
        while (worker.size() > 0) worker.runFirst();
        assertFalse(runtime.snapshot().busy());
        assertEquals(ProviderRefreshRequest.one(AppearanceProviders.Component.SKIN, BuiltinProvider.OFFLINE), operations.providerRefreshRequests.get(operations.providerRefreshRequests.size() - 1));
        assertEquals("nclskins.providers.refresh_all", runtime.view(320, 240, 0, 0).widget("providers.refresh").orElseThrow().label().key());
        runtime.close();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = BuiltinProvider.class,
            names = {"OPTIFINE", "SKINMC", "MINECRAFTCAPES"})
    void refreshLocksOnlyItsButtonUntilTheActualScopedOperationCompletes(BuiltinProvider provider) {
        FakeOperations operations = new FakeOperations();
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, provider);
        operations.deferCapeRefresh = true;
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        runtime.initialize();
        while (worker.size() > 0) worker.runFirst();
        runtime.dispatchWidget("gallery.providers");
        while (worker.size() > 0) worker.runFirst();
        runtime.dispatchWidget("providers.tab.CAPE");
        runtime.dispatchWidget("providers.row." + provider.name());
        var delivered = new java.util.ArrayList<ViewSpec>();
        runtime.subscribe(snapshot -> delivered.add(runtime.view(320, 240, 0, 0)));
        runtime.dispatchWidget("providers.refresh");
        assertFalse(runtime.snapshot().busy(), "Refresh must not set whole-screen busy");
        var initial = runtime.view(320, 240, 0, 0);
        assertFalse(initial.widget("providers.refresh").orElseThrow().enabled());
        assertTrue(initial.widget("providers.add").orElseThrow().enabled());
        assertTrue(initial.widget("providers.remove." + provider.name()).orElseThrow().enabled());
        runtime.dispatchWidget("providers.add");
        assertEquals("provider_chooser", runtime.view(320, 240, 0, 0).screenId());
        runtime.dispatchWidget("providers.back");
        assertEquals("providers", runtime.view(320, 240, 0, 0).screenId());
        while (worker.size() > 0) worker.runFirst();
        assertEquals(1, operations.providerRefreshRequests.size());
        assertTrue(operations.capeRefreshCompletions.containsKey(provider));
        assertFalse(runtime.view(320, 240, 0, 0).widget("providers.refresh").orElseThrow().enabled());
        runtime.dispatchWidget("providers.refresh");
        runtime.dispatchWidget("providers.refresh");
        assertEquals(0, worker.size());
        assertEquals(1, operations.providerRefreshRequests.size());
        assertTrue(delivered.stream().allMatch(view -> view.widget("providers.add")
                .map(ViewSpec.Widget::enabled).orElse(true)));
        runtime.dispatchWidget("providers.row.OFFLINE");
        assertTrue(runtime.view(320, 240, 0, 0).widget("providers.refresh").orElseThrow().enabled(),
                "A different scope is independent of the pending cape read");
        runtime.dispatchWidget("providers.row." + provider.name());
        runtime.tick();
        assertFalse(delivered.get(delivered.size() - 1).widget("providers.refresh").orElseThrow().enabled());
        operations.capeRefreshCompletions.remove(provider).accept(null);
        runtime.tick();
        assertTrue(delivered.get(delivered.size() - 1).widget("providers.refresh").orElseThrow().enabled(),
                "The first client tick must publish terminal availability without another click");
        assertEquals(1, operations.providerRefreshRequests.size());
        runtime.close();
    }

    @Test
    void publicCapeCooldownsStayProviderScopedAcrossTicksAndManualRefresh() {
        FakeOperations operations = new FakeOperations();
        operations.providers = operations.providers
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC);
        operations.capeCooldowns.put(BuiltinProvider.OPTIFINE, Duration.ofMillis(1500));
        operations.capeCooldowns.put(BuiltinProvider.SKINMC, Duration.ofSeconds(61));
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.tick();
        assertEquals(Duration.ofSeconds(2), runtime.snapshot().capeProviderCooldowns().get(BuiltinProvider.OPTIFINE));
        assertEquals(Duration.ofSeconds(61), runtime.snapshot().capeProviderCooldowns().get(BuiltinProvider.SKINMC));
        assertFalse(runtime.snapshot().rateLimited());
        assertTrue(runtime.snapshot().rateLimitProgress().isEmpty());
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.tab.CAPE");
        assertTrue(runtime.view(320, 240, 0, 0).texts().stream().anyMatch(text ->
                text.id().equals("providers.row.SKINMC.cooldown")));

        operations.capeCooldowns.remove(BuiltinProvider.OPTIFINE);
        runtime.tick();
        runtime.dispatchWidget("providers.refresh");
        assertEquals(AppearanceProviders.Component.CAPE, operations.providerRefresh);
        assertEquals(1, operations.optifineRefreshes);
        assertEquals(1, operations.skinMcRefreshes);
        assertEquals(Map.of(BuiltinProvider.SKINMC, Duration.ofSeconds(61)),
                runtime.snapshot().capeProviderCooldowns());

        operations.capeCooldowns.put(BuiltinProvider.SKINMC, Duration.ofMillis(1));
        runtime.tick();
        assertEquals(Map.of(BuiltinProvider.SKINMC, Duration.ofSeconds(1)),
                runtime.snapshot().capeProviderCooldowns());
        operations.capeCooldowns.clear();
        runtime.tick();
        assertTrue(runtime.snapshot().capeProviderCooldowns().isEmpty());
        assertTrue(runtime.view(320, 240, 0, 0).texts().stream().noneMatch(text ->
                text.id().endsWith(".cooldown")));

        operations.capeCooldowns.put(BuiltinProvider.SKINMC, Duration.ofSeconds(30));
        runtime.tick();
        operations.session = new SessionValidation(SessionStatus.OFFLINE_OR_INVALID,
                new GameSessionTokenSource.SessionIdentity(UUID.randomUUID(), "switched"),
                null, (SessionFailureContext) null, "session switched");
        runtime.tick();
        assertTrue(runtime.snapshot().capeProviderCooldowns().isEmpty());
    }

    @Test
    void explicitApplyRecoversUnknownThroughFreshCheckpoint() {
        FakeOperations operations = new FakeOperations();
        operations.localFirst = true;
        operations.selectionStatus = AppearanceSyncStatus.UNKNOWN;
        operations.account = TestFixtures.account(1);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".apply");
        assertTrue(operations.reconciliationTriggers.contains(Trigger.EXPLICIT_RETRY));
    }

    @Test
    void settledActiveSavePublishesLatestLocalStateWithoutSchedulingCheckpoint() {
        FakeOperations operations = new FakeOperations();
        operations.localFirst = true;
        operations.settledActiveSave = true;
        operations.account = TestFixtures.account(1);
        UUID activePreset = operations.account.presets().get(0).id();
        operations.activePresetId = Optional.of(activePreset);
        operations.appearanceRevision = 4;
        operations.appearanceSyncStatus = AppearanceSyncStatus.OFFICIAL;
        operations.durable = Optional.of(new ClientOperations.DurableAppearance(
                TestFixtures.ACCOUNT_ID,
                operations.appearanceRevision,
                AppearanceSyncStatus.OFFICIAL,
                operations.activePresetId,
                Optional.of(AppliedAppearance.accountDefault(TestFixtures.ACCOUNT_ID, Optional.empty())),
                Optional.of(com.naocraftlab.skins.client.OuterLayerVisibility.allVisible())));
        AtomicInteger signalCalls = new AtomicInteger();
        ClientRuntime runtime = runtime(
                operations,
                Runnable::run,
                Optional.empty(),
                Optional.of(signalCalls::incrementAndGet));

        runtime.initialize();
        runtime.dispatchWidget("gallery.preset." + activePreset + ".edit");
        runtime.dispatchText("editor.name", "Settled edit");
        runtime.dispatchWidget("editor.save");

        assertEquals(Optional.of(activePreset), runtime.snapshot().activePresetId());
        assertEquals("Settled edit", findPreset(runtime.snapshot(), activePreset).name());
        assertEquals(AppearanceSyncStatus.OFFICIAL, runtime.snapshot().syncStatus());
        assertEquals(0, operations.reconciliationCalls);
        assertEquals(4L + 1L, runtime.snapshot().intentRevision());
        assertEquals(com.naocraftlab.skins.client.OuterLayerVisibility.allVisible(),
                findPreset(runtime.snapshot(), activePreset).outerLayerVisibility());
        assertEquals(0, signalCalls.get());
    }

    @Test
    void storagePreflightIsSynchronousAndPropagatesFatalRuntimeFailures() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());

        runtime.verifyStorageAccess();

        assertEquals(1, operations.storagePreflightCalls);
        IllegalStateException expected = new IllegalStateException("fatal storage failure");
        operations.storagePreflightFailure = expected;
        assertSame(expected, assertThrows(IllegalStateException.class, runtime::verifyStorageAccess));
        assertEquals(2, operations.storagePreflightCalls);
    }

    @Test
    void unmatchedGalleryQuerySeedsTheCreatedPresetNameAfterCatalogSelection() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(854, 480, 0, 0);

        runtime.dispatchText("gallery.search", "Wanted preset");
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        runtime.dispatchWidget("add.catalog.skin:minecraft:steve");

        assertEquals(
                "Wanted preset",
                runtime.snapshot().editor().orElseThrow().name());
    }

    @Test
    void galleryNavigationPublishesOneShotFocusAndDoesNotActivatePresetAnchors() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(3);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(320, 240, 0, 0);

        assertTrue(runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, null));
        ViewSpec searchFocused = runtime.view(320, 240, 0, 0);
        assertEquals(Optional.of("gallery.search"),
                searchFocused.focusRequest().map(ViewSpec.FocusRequest::widgetId));
        runtime.acknowledgeViewRendered(searchFocused);
        assertEquals(Optional.of("gallery.search"),
                runtime.view(320, 240, 0, 0).focusRequest().map(ViewSpec.FocusRequest::widgetId),
                "rendering alone must not acknowledge an unapplied native focus request");
        runtime.acknowledgeFocusApplied(
                searchFocused.screenId(), searchFocused.focusRequest().orElseThrow());
        assertTrue(runtime.view(320, 240, 0, 0).focusRequest().isEmpty());

        assertTrue(runtime.dispatchNavigation(
                ViewSpec.NavigationCommand.TAB_FORWARD, "gallery.search"));
        String visibleAnchor = runtime.view(320, 240, 0, 0).focusRequest()
                .orElseThrow().widgetId();
        assertTrue(visibleAnchor.startsWith("gallery.card."),
                "Tab from Search must enter at the central visible card");
        assertTrue(runtime.dispatchNavigation(
                ViewSpec.NavigationCommand.RIGHT, visibleAnchor));
        String presetAnchor = runtime.view(320, 240, 0, 0).focusRequest()
                .orElseThrow().widgetId();
        assertTrue(presetAnchor.startsWith("gallery.card."));
        assertFalse(runtime.dispatchNavigation(
                ViewSpec.NavigationCommand.ACTIVATE, presetAnchor));
        assertTrue(runtime.snapshot().addSource().isEmpty());
        assertTrue(runtime.snapshot().editor().isEmpty());
        assertEquals(0, operations.applyCalls);

        assertTrue(runtime.dispatchNavigation(
                ViewSpec.NavigationCommand.LEFT, presetAnchor));
        assertTrue(runtime.dispatchNavigation(
                ViewSpec.NavigationCommand.ACTIVATE, "gallery.add"));
        assertTrue(runtime.snapshot().addSource().isPresent());
    }

    @Test
    void galleryDeleteFocusLifecycleOnlyRunsForKeyboardOrigin() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(3);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(854, 480, 0, 0);
        UUID pointerPreset = operations.account.presets().get(0).id();

        runtime.dispatchWidget(
                "gallery.preset." + pointerPreset + ".delete",
                false,
                InteractionOrigin.POINTER);
        assertTrue(runtime.view(854, 480, 0, 0).focusRequest().isEmpty());
        runtime.dispatchWidget(
                "gallery.preset." + pointerPreset + ".delete_cancel",
                false,
                InteractionOrigin.POINTER);
        assertTrue(runtime.view(854, 480, 0, 0).focusRequest().isEmpty());

        UUID keyboardPreset = operations.account.presets().get(1).id();
        String prefix = "gallery.preset." + keyboardPreset;
        runtime.dispatchWidget(prefix + ".delete", false, InteractionOrigin.KEYBOARD);
        assertEquals(Optional.of(prefix + ".delete_cancel"),
                runtime.view(854, 480, 0, 0).focusRequest().map(ViewSpec.FocusRequest::widgetId));
        runtime.dispatchWidget(prefix + ".delete_cancel", false, InteractionOrigin.KEYBOARD);
        assertEquals(Optional.of(prefix + ".delete"),
                runtime.view(854, 480, 0, 0).focusRequest().map(ViewSpec.FocusRequest::widgetId));

        runtime.dispatchWidget(prefix + ".delete", false, InteractionOrigin.KEYBOARD);
        runtime.dispatchWidget(prefix + ".delete_confirm", false, InteractionOrigin.KEYBOARD);
        String remainingFocus = runtime.view(854, 480, 0, 0).focusRequest()
                .orElseThrow().widgetId();
        assertTrue(remainingFocus.equals("gallery.add")
                || remainingFocus.startsWith("gallery.card."));
        assertFalse(remainingFocus.equals("gallery.card." + keyboardPreset));
    }

    @Test
    void personalCatalogPointerDeleteNeverCreatesKeyboardFocusIntent() {
        FakeOperations operations = new FakeOperations();
        String hash = operations.seedPersonalSkin("Pointer skin");
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(854, 480, 0, 0);
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        String delete = "add.catalog.delete:"
                + PersonalSkinCatalog.COLLECTION_ID + ":" + hash;
        Optional<ViewSpec.FocusRequest> baselineFocus = runtime.view(854, 480, 0, 0).focusRequest();

        runtime.dispatchWidget(delete, false, InteractionOrigin.POINTER);
        assertEquals(baselineFocus, runtime.view(854, 480, 0, 0).focusRequest());
        runtime.dispatchWidget(
                "add.catalog.delete.cancel", false, InteractionOrigin.POINTER);
        assertEquals(baselineFocus, runtime.view(854, 480, 0, 0).focusRequest());

        runtime.dispatchWidget(delete, false, InteractionOrigin.POINTER);
        runtime.dispatchWidget(
                "add.catalog.delete.confirm", false, InteractionOrigin.POINTER);
        assertEquals(baselineFocus, runtime.view(854, 480, 0, 0).focusRequest());
        assertFalse(operations.account.personalSkins().get(0).visible());
    }

    @Test
    void fullCommandStateMachineUsesOneImmutableSnapshotSurface() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        List<ClientSnapshot> publications = new ArrayList<>();
        runtime.subscribe(publications::add);

        runtime.initialize();
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        assertEquals(2, runtime.snapshot().account().orElseThrow().skinAssets().size());
        assertFalse(runtime.snapshot().busy());

        runtime.importSkin("Imported", SkinVariant.CLASSIC, skinPng());
        UUID importedId = runtime.snapshot().selectedSkinId().orElseThrow();
        runtime.renameSkin(importedId, "Renamed");
        assertEquals("Renamed", findSkin(runtime.snapshot(), importedId).name());
        runtime.toggleSkinVariant(importedId);
        assertEquals(SkinVariant.SLIM, findSkin(runtime.snapshot(), importedId).variant());
        int skinsBeforeCopy = runtime.snapshot().account().orElseThrow().skinAssets().size();
        runtime.duplicateSkin(importedId, "Copy");
        assertEquals(skinsBeforeCopy + 1, runtime.snapshot().account().orElseThrow().skinAssets().size());
        runtime.deleteSkin(importedId);
        assertNull(findSkinOrNull(runtime.snapshot(), importedId));

        runtime.view(854, 480, 0, 0);
        runtime.dispatchWidget("gallery.add");
        assertTrue(runtime.snapshot().addSource().isPresent());
        runtime.dispatchWidget("add.tab.catalog");
        runtime.dispatchWidget("add.catalog.skin:minecraft:steve");
        assertTrue(runtime.snapshot().editor().isPresent());
        runtime.dispatchText("editor.name", "Created");
        runtime.dispatchWidget("editor.model_choice.slim");
        runtime.dispatchWidget("editor.save");
        UUID createdPreset = runtime.snapshot().selectedPresetId().orElseThrow();
        assertEquals("Created", findPreset(runtime.snapshot(), createdPreset).name());
        assertEquals(SkinVariant.SLIM, findSkin(
                        runtime.snapshot(), findPreset(runtime.snapshot(), createdPreset).skin().assetId())
                .variant());

        runtime.dispatchWidget("gallery.preset." + createdPreset + ".apply");
        assertEquals(Optional.of(createdPreset), runtime.snapshot().activePresetId());
        int appliesBeforeEdit = operations.applyCalls;
        runtime.dispatchWidget("gallery.preset." + createdPreset + ".edit");
        runtime.dispatchText("editor.name", "Active changed");
        runtime.dispatchWidget("editor.save");
        assertEquals(appliesBeforeEdit, operations.applyCalls);
        assertEquals(Optional.of(createdPreset), runtime.snapshot().activePresetId());
        assertEquals("Active changed", findPreset(runtime.snapshot(), createdPreset).name());

        runtime.dispatchWidget("gallery.preset." + createdPreset + ".edit");
        runtime.dispatchText("editor.name", "Cancelled change");
        runtime.dispatchWidget("editor.cancel");
        assertEquals("Active changed", findPreset(runtime.snapshot(), createdPreset).name());

        int presetsBeforeCopy = runtime.snapshot().account().orElseThrow().presets().size();
        AppearancePreset sourceBeforeCopy = findPreset(runtime.snapshot(), createdPreset);
        runtime.dispatchWidget("gallery.preset." + createdPreset + ".duplicate");
        PresetEditorModel duplicateDraft = runtime.snapshot().editor().orElseThrow();
        assertEquals(presetsBeforeCopy, runtime.snapshot().account().orElseThrow().presets().size());
        assertTrue(duplicateDraft.originalPresetId().isEmpty());
        assertEquals("Copy of " + sourceBeforeCopy.name(), duplicateDraft.name());
        assertEquals(sourceBeforeCopy.skin(), duplicateDraft.skin());
        assertEquals(sourceBeforeCopy.optionalCapeId(), duplicateDraft.capeId());
        assertEquals(sourceBeforeCopy.outerLayerVisibility(), duplicateDraft.preview().outerLayerVisibility());
        runtime.dispatchWidget("editor.cancel");
        assertEquals(presetsBeforeCopy, runtime.snapshot().account().orElseThrow().presets().size());

        runtime.dispatchWidget("gallery.preset." + createdPreset + ".duplicate");
        runtime.dispatchWidget("editor.save");
        UUID duplicated = runtime.snapshot().selectedPresetId().orElseThrow();
        assertNotEquals(createdPreset, duplicated);
        assertEquals(presetsBeforeCopy + 1, runtime.snapshot().account().orElseThrow().presets().size());
        assertEquals(sourceBeforeCopy, findPreset(runtime.snapshot(), createdPreset));
        assertEquals(Optional.of(createdPreset), runtime.snapshot().activePresetId());

        operations.result = MutationResult.PARTIAL;
        operations.recovery = Set.of(
                RecoveryAction.RETRY_CAPE,
                RecoveryAction.RESTORE_PREVIOUS_APPEARANCE);
        runtime.dispatchWidget("gallery.preset." + createdPreset + ".apply");
        assertTrue(runtime.snapshot().recoveryActions().contains(RecoveryAction.RETRY_CAPE));
        assertFalse(runtime.snapshot().recoveryActions()
                .contains(RecoveryAction.RESTORE_PREVIOUS_APPEARANCE));
        assertTrue(runtime.view(854, 480, 0, 0).widget("gallery.restore").isEmpty());
        assertTrue(runtime.snapshot().activePresetId().isEmpty());
        operations.result = MutationResult.APPLIED;
        operations.recovery = Set.of();
        runtime.dispatchWidget("gallery.restore");
        assertEquals(0, operations.restoreCalls);

        operations.session = session(SessionStatus.EXPIRED);
        int retriesBeforeStaleDispatch = operations.retrySessionCalls;
        runtime.dispatchWidget("gallery.retry_session");
        assertEquals(retriesBeforeStaleDispatch, operations.retrySessionCalls);
        assertTrue(runtime.snapshot().session().orElseThrow().valid());
        operations.rateLimited = true;
        runtime.tick();
        assertTrue(runtime.snapshot().rateLimited());

        runtime.closeScreen();
        assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
        runtime.reopen();
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        assertTrue(publications.size() > 15);
        assertTrue(publications.stream().allMatch(snapshot -> snapshot.generation() >= 0));
    }

    @Test
    void duplicateSaveFailureKeepsTheUnsavedDraftAndOriginalLibrary() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        AppearancePreset source = operations.account.presets().get(0);

        runtime.dispatchWidget("gallery.preset." + source.id() + ".duplicate");
        operations.failEditorSave = true;
        runtime.dispatchWidget("editor.save");

        PresetEditorModel draft = runtime.snapshot().editor().orElseThrow();
        assertTrue(draft.originalPresetId().isEmpty());
        assertEquals("Copy of " + source.name(), draft.name());
        assertFalse(draft.busy());
        assertEquals(List.of(source), runtime.snapshot().account().orElseThrow().presets());
    }

    @Test
    void duplicateDispatchRetainsTheSourceOfflineCapeInTheDraft() {
        FakeOperations operations = new FakeOperations();
        AccountState base = TestFixtures.account(2);
        AppearancePreset basePreset = base.presets().get(1);
        LocalCapeReference offline = new LocalCapeReference(
                new UUID(7, 1), "a".repeat(64), false);
        PersonalCapeEntry personal = new PersonalCapeEntry(
                offline, "b".repeat(64), "Saved cape", Instant.EPOCH);
        AppearancePreset source = new AppearancePreset(
                basePreset.id(),
                basePreset.name(),
                basePreset.skin(),
                "minecraft-cape",
                com.naocraftlab.skins.client.OuterLayerVisibility.allVisible()
                        .with(OuterLayerPart.HEAD, false),
                basePreset.createdAt(),
                basePreset.updatedAt(),
                offline);
        operations.account = new AccountState(
                AccountState.CURRENT_SCHEMA_VERSION,
                base.accountId(),
                base.skinAssets(),
                base.personalSkins(),
                List.of(base.presets().get(0), source),
                base.updatedAt(),
                List.of(personal));
        operations.activePresetId = Optional.of(source.id());
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        long intentRevision = runtime.snapshot().intentRevision();

        runtime.dispatchWidget("gallery.preset." + source.id() + ".duplicate");

        PresetEditorModel draft = runtime.snapshot().editor().orElseThrow();
        assertEquals(offline, draft.capeCatalog().offline());
        assertEquals(source.capeId(), draft.capeCatalog().minecraft().orElseThrow());
        assertEquals(source.skin(), draft.skin());
        assertEquals(source.outerLayerVisibility(), draft.preview().outerLayerVisibility());
        assertEquals(List.of(personal), runtime.snapshot().account().orElseThrow().personalCapes());
        assertEquals(0, operations.applyCalls);
        assertEquals(intentRevision, runtime.snapshot().intentRevision());
        assertEquals(Optional.of(source.id()), runtime.snapshot().activePresetId());
    }

    @Test
    void duplicateOfflineCapeSurvivesColdAndWarmedReloadWithoutProviderMutation() {
        for (boolean warmed : List.of(false, true)) {
            for (boolean disabled : List.of(false, true)) {
                DuplicateFixture fixture = duplicateFixture(true, false);
                FakeOperations operations = new FakeOperations();
                operations.account = fixture.account();
                operations.capeEditorDataWarmed = warmed;
                if (disabled) {
                    operations.providers = operations.providers.disable(
                            AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE);
                }
                AppearanceProviders providers = operations.providers;
                QueuedExecutor worker = new QueuedExecutor();
                ClientRuntime runtime = runtime(operations, worker, Optional.empty());
                initializeQueuedRuntime(runtime, worker);

                runtime.dispatchWidget(
                        "gallery.preset." + fixture.source().id() + ".duplicate");

                assertEquals(fixture.offline(), runtime.snapshot().editor().orElseThrow()
                        .capeCatalog().offline());
                assertEquals(providers, runtime.snapshot().providers());
                assertEquals(fixture.personalCapes(),
                        runtime.snapshot().account().orElseThrow().personalCapes());
                assertEquals(0, operations.applyCalls);
                drainWorker(worker);
                assertEquals(fixture.offline(), runtime.snapshot().editor().orElseThrow()
                        .capeCatalog().offline());
                assertEquals(providers, runtime.snapshot().providers());
                assertEquals(fixture.personalCapes(),
                        runtime.snapshot().account().orElseThrow().personalCapes());
            }
        }
    }

    @Test
    void duplicateWithoutCapeOrSessionKeepsNoCapeAcrossDelayedReload() {
        for (boolean withCape : List.of(false, true)) {
            DuplicateFixture fixture = duplicateFixture(withCape, false);
            FakeOperations operations = new FakeOperations();
            operations.account = fixture.account();
            SessionValidation valid = TestFixtures.validSession();
            operations.session = new SessionValidation(
                    SessionStatus.OFFLINE_OR_INVALID,
                    valid.sessionIdentity(),
                    null,
                    new SessionFailureContext(
                            SessionCheckPhase.TOKEN_SOURCE,
                            ApiFailureKind.TOKEN_UNAVAILABLE,
                            null),
                    "no token");
            QueuedExecutor worker = new QueuedExecutor();
            ClientRuntime runtime = runtime(operations, worker, Optional.empty());
            initializeQueuedRuntime(runtime, worker);

            runtime.dispatchWidget(
                    "gallery.preset." + fixture.source().id() + ".duplicate");
            assertEquals(fixture.offline(), runtime.snapshot().editor().orElseThrow()
                    .capeCatalog().offline());
            drainWorker(worker);
            assertEquals(fixture.offline(), runtime.snapshot().editor().orElseThrow()
                    .capeCatalog().offline());
            assertEquals(fixture.personalCapes(),
                    runtime.snapshot().account().orElseThrow().personalCapes());
            assertEquals(AppearanceProviders.initial(), runtime.snapshot().providers());
            assertEquals(0, operations.applyCalls);
        }
    }

    @Test
    void delayedDuplicateReloadKeepsLatestSelectionAndRejectsStaleEditors() {
        DuplicateFixture fixture = duplicateFixture(true, true);
        FakeOperations operations = new FakeOperations();
        operations.account = fixture.account();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        initializeQueuedRuntime(runtime, worker);

        runtime.dispatchWidget("gallery.preset." + fixture.source().id() + ".duplicate");
        UUID alternateId = fixture.alternate().orElseThrow().texture().entryId();
        runtime.dispatchWidget("editor.cape_item.OFFLINE." + alternateId);
        assertEquals(fixture.alternate().orElseThrow().texture(),
                runtime.snapshot().editor().orElseThrow().capeCatalog().offline());
        drainWorker(worker);
        assertEquals(fixture.alternate().orElseThrow().texture(),
                runtime.snapshot().editor().orElseThrow().capeCatalog().offline());
        assertEquals(fixture.source().capeId(),
                runtime.snapshot().editor().orElseThrow().capeCatalog().minecraft().orElseThrow());
        assertEquals(fixture.source(), findPreset(runtime.snapshot(), fixture.source().id()));

        runtime.dispatchWidget("editor.cape_item.OFFLINE.none");
        assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().offline());
        runtime.dispatchWidget("editor.cancel");

        runtime.dispatchWidget(
                "gallery.preset." + fixture.account().presets().get(0).id() + ".duplicate");
        assertTrue(runtime.snapshot().editor().isPresent());
        runtime.dispatchWidget("editor.cancel");
        runtime.dispatchWidget(
                "gallery.preset." + fixture.account().presets().get(0).id() + ".edit");
        assertEquals(fixture.account().presets().get(0).id(),
                runtime.snapshot().editor().orElseThrow().originalPresetId().orElseThrow());
        drainWorker(worker);
        assertEquals(fixture.account().presets().get(0).id(),
                runtime.snapshot().editor().orElseThrow().originalPresetId().orElseThrow());
        assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().offline());
        assertEquals(fixture.source(), findPreset(runtime.snapshot(), fixture.source().id()));
    }

    @Test
    void delayedDuplicateReloadKeepsExplicitNoCapeSelection() {
        DuplicateFixture fixture = duplicateFixture(true, false);
        FakeOperations operations = new FakeOperations();
        operations.account = fixture.account();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        initializeQueuedRuntime(runtime, worker);

        runtime.dispatchWidget("gallery.preset." + fixture.source().id() + ".duplicate");
        runtime.dispatchWidget("editor.cape_item.OFFLINE.none");

        assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().offline());
        assertEquals(fixture.source().capeId(), runtime.snapshot().editor().orElseThrow()
                .capeCatalog().minecraft().orElseThrow());
        assertEquals(fixture.source(), findPreset(runtime.snapshot(), fixture.source().id()));

        drainWorker(worker);

        assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().offline());
        assertEquals(fixture.source().capeId(), runtime.snapshot().editor().orElseThrow()
                .capeCatalog().minecraft().orElseThrow());
        assertEquals(fixture.source(), findPreset(runtime.snapshot(), fixture.source().id()));
        assertEquals(fixture.personalCapes(),
                runtime.snapshot().account().orElseThrow().personalCapes());
        assertEquals(0, operations.applyCalls);
    }

    @Test
    void duplicateResourceCapeRemapsWithoutMaterializingAndSaveReusesPersonalIdentity() {
        DuplicateFixture fixture = duplicateFixture(true, false);
        FakeOperations cancelOperations = new FakeOperations();
        cancelOperations.account = fixture.account();
        cancelOperations.capeEditorDataWarmed = true;
        cancelOperations.resourceCapeCollections = List.of(resourceCapeCollection());
        cancelOperations.resourceCapeHashes = Map.of(
                new ClientOperations.ResourceCapeKey("event", "hero"), "c".repeat(64));
        ClientRuntime cancelled = runtime(cancelOperations, Runnable::run, Optional.empty());
        cancelled.initialize();
        cancelled.dispatchWidget("gallery.preset." + fixture.source().id() + ".duplicate");
        CapeCatalogModel remapped = cancelled.snapshot().editor().orElseThrow().capeCatalog();
        assertTrue(remapped.selectedResource().isPresent());
        assertNull(remapped.offline().entryId());
        assertEquals(0, cancelOperations.resourceCapeMaterializations);
        cancelled.dispatchWidget("editor.cancel");
        assertEquals(0, cancelOperations.resourceCapeMaterializations);
        assertEquals(fixture.personalCapes(),
                cancelled.snapshot().account().orElseThrow().personalCapes());

        FakeOperations saveOperations = new FakeOperations();
        saveOperations.account = fixture.account();
        saveOperations.capeEditorDataWarmed = true;
        saveOperations.resourceCapeCollections = List.of(resourceCapeCollection());
        saveOperations.resourceCapeHashes = Map.of(
                new ClientOperations.ResourceCapeKey("event", "hero"), "c".repeat(64));
        ClientRuntime saved = runtime(saveOperations, Runnable::run, Optional.empty());
        saved.initialize();
        saved.dispatchWidget("gallery.preset." + fixture.source().id() + ".duplicate");
        saved.dispatchWidget("editor.save");

        UUID duplicateId = saved.snapshot().selectedPresetId().orElseThrow();
        assertNotEquals(fixture.source().id(), duplicateId);
        assertEquals(fixture.offline(), findPreset(saved.snapshot(), duplicateId).offlineCape());
        assertEquals(fixture.source(), findPreset(saved.snapshot(), fixture.source().id()));
        assertEquals(fixture.personalCapes(),
                saved.snapshot().account().orElseThrow().personalCapes());
        assertEquals(1, saveOperations.resourceCapeMaterializations);
    }

    @Test
    void duplicateMissingCapeReferenceIsClearedAndSaveFailureRetainsTheDraft() {
        DuplicateFixture fixture = duplicateFixture(true, false);
        AccountState accountWithoutCape = new AccountState(
                AccountState.CURRENT_SCHEMA_VERSION,
                fixture.account().accountId(),
                fixture.account().skinAssets(),
                fixture.account().personalSkins(),
                fixture.account().presets(),
                fixture.account().updatedAt());
        FakeOperations missing = new FakeOperations();
        missing.account = accountWithoutCape;
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(missing, worker, Optional.empty());
        initializeQueuedRuntime(runtime, worker);
        runtime.dispatchWidget("gallery.preset." + fixture.source().id() + ".duplicate");
        assertEquals(fixture.offline(), runtime.snapshot().editor().orElseThrow()
                .capeCatalog().offline());
        drainWorker(worker);
        assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().offline());
        assertEquals(0, missing.applyCalls);

        FakeOperations failed = new FakeOperations();
        failed.account = fixture.account();
        failed.failEditorSave = true;
        ClientRuntime failedRuntime = runtime(failed, Runnable::run, Optional.empty());
        failedRuntime.initialize();
        failedRuntime.dispatchWidget("gallery.preset." + fixture.source().id() + ".duplicate");
        failedRuntime.dispatchWidget("editor.save");
        PresetEditorModel draft = failedRuntime.snapshot().editor().orElseThrow();
        assertEquals(fixture.offline(), draft.capeCatalog().offline());
        assertEquals(fixture.source(), findPreset(failedRuntime.snapshot(), fixture.source().id()));
        assertEquals(fixture.account().presets().size(),
                failedRuntime.snapshot().account().orElseThrow().presets().size());
    }

    @Test
    void explicitSessionRetryPublishesConnectingThenReturnsToOfflineOrClearsRecovery() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.session = session(SessionStatus.OFFLINE_OR_INVALID);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());

        runtime.initialize();

        ViewSpec offline = runtime.view(854, 480, 427, 180);
        assertEquals(
                UiMessage.info("nclskins.session.offline"),
                offline.texts().stream()
                        .filter(text -> text.id().equals("gallery.offline"))
                        .findFirst()
                        .orElseThrow()
                        .message());
        assertTrue(offline.widget("gallery.retry_session").orElseThrow().enabled());

        operations.retrySessionFailure = new IOException("session still unavailable");
        runtime.dispatchWidget("gallery.retry_session");

        assertFalse(runtime.snapshot().busy());
        assertEquals(
                ClientSnapshot.SessionActivity.RECONNECTING,
                runtime.snapshot().sessionActivity());
        assertEquals(UiMessage.info("nclskins.status.checking_session"), runtime.snapshot().status());
        ViewSpec connectingBeforeFailure = runtime.view(854, 480, 427, 180);
        assertEquals(
                UiMessage.info("nclskins.session.connecting"),
                connectingBeforeFailure.texts().stream()
                        .filter(text -> text.id().equals("gallery.offline"))
                        .findFirst()
                        .orElseThrow()
                        .message());
        assertFalse(connectingBeforeFailure.widget("gallery.retry_session").orElseThrow().enabled());
        assertFalse(runtime.snapshot().busy(), "session feedback must not own global busy");

        runtime.acknowledgeViewRendered(connectingBeforeFailure);
        assertSessionRetryConnectingForFiveTicks(runtime);
        runtime.tick();

        assertFalse(runtime.snapshot().busy());
        ViewSpec failed = runtime.view(854, 480, 427, 180);
        assertEquals(
                UiMessage.info("nclskins.session.offline"),
                failed.texts().stream()
                        .filter(text -> text.id().equals("gallery.offline"))
                        .findFirst()
                        .orElseThrow()
                        .message());
        assertTrue(failed.widget("gallery.retry_session").orElseThrow().enabled());

        operations.retrySessionFailure = null;
        operations.session = TestFixtures.validSession();
        runtime.dispatchWidget("gallery.retry_session");

        ViewSpec connectingBeforeSuccess = runtime.view(854, 480, 427, 180);
        assertEquals(
                UiMessage.info("nclskins.session.connecting"),
                connectingBeforeSuccess.texts().stream()
                        .filter(text -> text.id().equals("gallery.offline"))
                        .findFirst()
                        .orElseThrow()
                        .message());
        assertFalse(connectingBeforeSuccess.widget("gallery.retry_session").orElseThrow().enabled());
        assertFalse(runtime.snapshot().busy(), "session feedback must not own global busy");

        runtime.acknowledgeViewRendered(connectingBeforeSuccess);
        assertSessionRetryConnectingForFiveTicks(runtime);
        runtime.tick();

        assertFalse(runtime.snapshot().busy());
        ViewSpec connected = runtime.view(854, 480, 427, 180);
        assertTrue(connected.texts().stream().noneMatch(text -> text.id().equals("gallery.offline")));
        assertTrue(connected.widget("gallery.retry_session").isEmpty());
        assertEquals(2, operations.retrySessionCalls);
    }

    @Test
    void repeatedGalleryOpenKeepsLocalControlsWithoutSessionTasks() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.localFirst = true;
        operations.session = session(SessionStatus.OFFLINE_OR_INVALID);
        QueuedExecutor sessionWorker = new QueuedExecutor();
        ClientRuntime runtime = new ClientRuntime(new com.naocraftlab.skins.runtime.DefaultRuntimeServices(), operations, CLIENT, CANCELLED_PICKER,
                Runnable::run, Runnable::run, sessionWorker, TEXT, Optional.empty(), Optional.empty(),
                DiagnosticSinks.discarding());
        for (int attempt = 0; attempt < 5; attempt++) {
            runtime.reopen();
            assertFalse(runtime.snapshot().busy());
            assertEquals(ClientSnapshot.SessionActivity.NONE, runtime.snapshot().sessionActivity());
            ViewSpec view = runtime.view(854, 480, 427, 180);
            assertEquals(UiMessage.info("nclskins.session.offline"), view.texts().stream()
                    .filter(text -> text.id().equals("gallery.offline")).findFirst().orElseThrow().message());
            assertTrue(view.widget("gallery.retry_session").orElseThrow().enabled());
            UUID presetId = operations.account.presets().get(0).id();
            for (String action : List.of("apply", "edit", "duplicate", "delete")) {
                assertTrue(view.widget("gallery.preset." + presetId + "." + action).orElseThrow().enabled());
            }
            runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
            assertTrue(runtime.snapshot().editor().isPresent());
            runtime.escapePressed();
            runtime.escapePressed();
            assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
            assertEquals(0, sessionWorker.size());
        }
        assertEquals(0, operations.reconciliationCalls);
        assertEquals(0, operations.retrySessionCalls);
    }

    @Test
    void reconnectingSessionDoesNotBlockLocalGalleryActions() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(2);
        operations.session = session(SessionStatus.OFFLINE_OR_INVALID);
        operations.localFirst = true;
        QueuedExecutor sessionWorker = new QueuedExecutor();
        ClientRuntime runtime = new ClientRuntime(new com.naocraftlab.skins.runtime.DefaultRuntimeServices(),
                operations,
                CLIENT,
                CANCELLED_PICKER,
                Runnable::run,
                Runnable::run,
                sessionWorker,
                TEXT,
                Optional.empty(),
                Optional.empty(),
                DiagnosticSinks.discarding());
        runtime.initialize();

        runtime.dispatchWidget("gallery.retry_session");

        ViewSpec reconnecting = runtime.view(1600, 720, 800, 200);
        UUID firstPresetId = operations.account.presets().get(0).id();
        UUID secondPresetId = operations.account.presets().get(1).id();
        assertFalse(runtime.snapshot().busy());
        assertEquals(
                ClientSnapshot.SessionActivity.RECONNECTING,
                runtime.snapshot().sessionActivity());
        assertTrue(reconnecting.widget("gallery.done").orElseThrow().enabled());
        assertFalse(reconnecting.widget("gallery.retry_session").orElseThrow().enabled());
        assertTrue(reconnecting.widget("gallery.preset." + firstPresetId + ".apply")
                .orElseThrow().enabled());
        assertTrue(reconnecting.widget("gallery.preset." + firstPresetId + ".edit")
                .orElseThrow().enabled());
        assertTrue(reconnecting.widget("gallery.preset." + firstPresetId + ".duplicate")
                .orElseThrow().enabled());
        assertTrue(reconnecting.widget("gallery.preset." + firstPresetId + ".delete")
                .orElseThrow().enabled());

        runtime.dispatchWidget("gallery.preset." + firstPresetId + ".apply");
        runtime.dispatchWidget("gallery.preset." + secondPresetId + ".apply");
        assertEquals(Optional.of(secondPresetId), runtime.snapshot().activePresetId());
        assertEquals(2, runtime.snapshot().intentRevision());
        assertEquals(ClientSnapshot.SessionActivity.RECONNECTING, runtime.snapshot().sessionActivity());

        runtime.dispatchWidget("gallery.preset." + firstPresetId + ".delete");
        assertTrue(runtime.view(1600, 720, 800, 200)
                .widget("gallery.preset." + firstPresetId + ".delete_confirm")
                .isPresent());
        runtime.escapePressed();
        assertTrue(runtime.view(1600, 720, 800, 200)
                .widget("gallery.preset." + firstPresetId + ".delete_confirm")
                .isEmpty());

        operations.session = TestFixtures.validSession();
        sessionWorker.runFirst();
        runtime.acknowledgeViewRendered(runtime.view(1600, 720, 800, 200));
        advanceTicks(runtime, 6);
        assertEquals(ClientSnapshot.SessionActivity.NONE, runtime.snapshot().sessionActivity());
        assertTrue(runtime.view(1600, 720, 800, 200).widget("gallery.retry_session").isEmpty());
        assertEquals(Optional.of(secondPresetId), runtime.snapshot().activePresetId());
        assertEquals(2, operations.reconciliationKeys.get(operations.reconciliationKeys.size() - 1)
                .intentRevision());
        assertEquals(Trigger.SESSION_REFRESHED,
                operations.reconciliationTriggers.get(operations.reconciliationTriggers.size() - 1));
    }

    @Test
    void lateExplicitSessionRefreshCannotOverwriteAConcurrentLocalLibraryMutation() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.retryInitialDataOverride = operations.initial();
        QueuedExecutor sessionWorker = new QueuedExecutor();
        ClientRuntime runtime = new ClientRuntime(new com.naocraftlab.skins.runtime.DefaultRuntimeServices(),
                operations,
                CLIENT,
                CANCELLED_PICKER,
                Runnable::run,
                Runnable::run,
                sessionWorker,
                TEXT,
                Optional.empty(),
                Optional.empty(),
                DiagnosticSinks.discarding());
        operations.session = session(SessionStatus.OFFLINE_OR_INVALID);
        runtime.initialize();
        runtime.dispatchWidget("gallery.retry_session");
        runtime.acknowledgeViewRendered(runtime.view(854, 480, 0, 0));
        UUID sourceId = operations.account.presets().get(0).id();

        runtime.dispatchWidget("gallery.preset." + sourceId + ".duplicate");
        runtime.dispatchWidget("editor.save");
        assertEquals(2, runtime.snapshot().account().orElseThrow().presets().size());

        sessionWorker.runFirst();
        advanceTicks(runtime, 6);

        assertEquals(2, runtime.snapshot().account().orElseThrow().presets().size());
        assertEquals(2, operations.account.presets().size());
        assertEquals(ClientSnapshot.SessionActivity.NONE, runtime.snapshot().sessionActivity());
    }

    @Test
    void unavailableOrExpiredTokenShowsOnlyOfflineAndCannotDispatchSessionRetry() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        SessionValidation valid = TestFixtures.validSession();
        operations.session = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                valid.sessionIdentity(),
                null,
                new SessionFailureContext(
                        SessionCheckPhase.TOKEN_SOURCE,
                        ApiFailureKind.TOKEN_UNAVAILABLE,
                        null),
                "no token");
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());

        runtime.initialize();
        ViewSpec view = runtime.view(854, 480, 427, 180);

        assertEquals(
                UiMessage.info("nclskins.session.offline"),
                view.texts().stream()
                        .filter(text -> text.id().equals("gallery.offline"))
                        .findFirst()
                        .orElseThrow()
                        .message());
        assertTrue(view.widget("gallery.retry_session").isEmpty());
        runtime.dispatchWidget("gallery.retry_session");
        assertEquals(0, operations.retrySessionCalls);

        operations.session = new SessionValidation(
                SessionStatus.EXPIRED,
                valid.sessionIdentity(),
                null,
                new SessionFailureContext(
                        SessionCheckPhase.PROFILE,
                        ApiFailureKind.SESSION_EXPIRED,
                        401),
                "restart required");
        ClientRuntime expiredRuntime = runtime(operations, Runnable::run, Optional.empty());
        expiredRuntime.initialize();
        ViewSpec expiredView = expiredRuntime.view(854, 480, 427, 180);
        assertEquals(
                UiMessage.info("nclskins.session.offline"),
                expiredView.texts().stream()
                        .filter(text -> text.id().equals("gallery.offline"))
                        .findFirst()
                        .orElseThrow()
                        .message());
        assertTrue(expiredView.widget("gallery.retry_session").isEmpty());
        expiredRuntime.dispatchWidget("gallery.retry_session");
        assertEquals(0, operations.retrySessionCalls);
    }

    @Test
    void invalidSessionRetryResultReturnsOfflineWithoutReconciliationAfterSixTicks() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.session = session(SessionStatus.OFFLINE_OR_INVALID);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();

        runtime.dispatchWidget("gallery.retry_session");
        ViewSpec connecting = runtime.view(854, 480, 427, 180);
        runtime.acknowledgeViewRendered(connecting);

        assertSessionRetryConnectingForFiveTicks(runtime);
        assertEquals(0, operations.reconciliationCalls);
        runtime.tick();

        assertFalse(runtime.snapshot().busy());
        assertFalse(runtime.snapshot().syncInProgress());
        assertFalse(runtime.snapshot().session().orElseThrow().valid());
        assertEquals(0, operations.reconciliationCalls);
        ViewSpec offline = runtime.view(854, 480, 427, 180);
        assertEquals(
                UiMessage.info("nclskins.session.offline"),
                offline.texts().stream()
                        .filter(text -> text.id().equals("gallery.offline"))
                        .findFirst()
                        .orElseThrow()
                        .message());
        assertTrue(offline.widget("gallery.retry_session").orElseThrow().enabled());
    }

    @Test
    void validUnknownAppearanceDoesNotExposeSessionRecovery() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.appearanceSyncStatus = AppearanceSyncStatus.UNKNOWN;
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());

        runtime.initialize();
        worker.runFirst();
        ViewSpec view = runtime.view(854, 480, 427, 180);
        assertTrue(runtime.snapshot().session().orElseThrow().valid());
        assertTrue(view.widget("gallery.retry_session").isEmpty());
        assertTrue(view.texts().stream().noneMatch(text -> text.id().equals("gallery.offline")));
    }

    @Test
    void unknownRetryDoesNotLatchSyncWhileLocalRebindHasNotHandedOffToReconciliation() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.appearanceRevision = 3;
        operations.appearanceSyncStatus = AppearanceSyncStatus.UNKNOWN;
        operations.session = session(SessionStatus.OFFLINE_OR_INVALID);
        CompletableFuture<Optional<SignedProfileResolver.ResolvedProfile<String>>> resolution =
                new CompletableFuture<>();
        AppearanceRefreshCoordinator<String> refresh = new AppearanceRefreshCoordinator<>(
                CLIENT,
                ignored -> resolution,
                ignored -> PlayerAppearanceSink.ApplyResult.UPDATED,
                DiagnosticSinks.discarding());
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.of(refresh));

        runtime.initialize();
        worker.runFirst();
        operations.session = TestFixtures.validSession();
        AppliedAppearance local = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID,
                "a".repeat(64),
                SkinVariant.CLASSIC,
                Optional.empty());
        operations.durable = Optional.of(new ClientOperations.DurableAppearance(
                TestFixtures.ACCOUNT_ID,
                operations.appearanceRevision,
                AppearanceSyncStatus.UNKNOWN,
                Optional.empty(),
                Optional.of(local),
                Optional.empty()));

        runtime.dispatchWidget("gallery.retry_session");
        ViewSpec connecting = runtime.view(854, 480, 427, 180);
        runtime.acknowledgeViewRendered(connecting);
        advanceTicks(runtime, 6);

        assertFalse(runtime.snapshot().syncInProgress());
        assertEquals(0, worker.size(), "reconciliation must wait for the local rebind");
        assertTrue(runtime.view(854, 480, 427, 180)
                .widget("gallery.retry_session")
                .isEmpty());
        assertEquals(1, operations.retrySessionCalls);
        assertEquals(0, operations.reconciliationCalls);

        resolution.complete(Optional.empty());
        assertEquals(1, worker.size());
        assertTrue(runtime.snapshot().syncInProgress());

        worker.runFirst();

        assertFalse(runtime.snapshot().syncInProgress());
        assertEquals(
                List.of(Trigger.SESSION_REFRESHED),
                operations.reconciliationTriggers);
    }

    @Test
    void retryFailureHidesSessionRecoveryDuringCooldownAndCoalescesDirectRedispatch() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.session = session(SessionStatus.OFFLINE_OR_INVALID);
        operations.retrySessionFailure = new IOException("rate limited");
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());

        runtime.initialize();
        worker.runFirst();
        runtime.dispatchWidget("gallery.retry_session");
        ViewSpec connecting = runtime.view(854, 480, 427, 180);
        runtime.acknowledgeViewRendered(connecting);
        operations.rateLimited = true;

        advanceTicks(runtime, 6);

        assertTrue(runtime.snapshot().rateLimited());
        ViewSpec rateLimited = runtime.view(854, 480, 427, 180);
        assertTrue(rateLimited.widget("gallery.retry_session").isEmpty());
        assertTrue(rateLimited.texts().stream()
                .noneMatch(text -> text.id().equals("gallery.offline")));
        runtime.dispatchWidget("gallery.retry_session");
        assertEquals(1, operations.retrySessionCalls);
    }

    @Test
    void eachAddOpenReloadsCrossInstancePreferencesAndExplicitModelChoicesPersist() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        operations.uiPreferences = new AccountUiPreferences(
                AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                TestFixtures.ACCOUNT_ID,
                AddSourceTab.CATALOG,
                Optional.of(SkinVariant.SLIM),
                Set.of(MinecraftSkinCatalog.COLLECTION_ID));

        runtime.dispatchWidget("gallery.add");

        AddSourceModel reloaded = runtime.snapshot().addSource().orElseThrow();
        assertEquals(AddSourceTab.CATALOG, reloaded.selectedTab());
        assertEquals(SkinVariant.SLIM, reloaded.preferredVariant());
        assertTrue(reloaded.collectionCollapsed(MinecraftSkinCatalog.COLLECTION_ID));

        runtime.dispatchWidget("add.catalog.filter");
        assertEquals(
                Optional.of(SkinVariant.CLASSIC),
                operations.uiPreferences.preferredSkinVariant());
        runtime.dispatchWidget("add.catalog.filter", true);
        assertEquals(AddSourceModel.CatalogFilter.ALL,
                runtime.snapshot().addSource().orElseThrow().filter());
        runtime.dispatchWidget("add.catalog.filter", true);
        assertEquals(AddSourceModel.CatalogFilter.SLIM,
                runtime.snapshot().addSource().orElseThrow().filter());
        assertEquals(
                Optional.of(SkinVariant.SLIM),
                operations.uiPreferences.preferredSkinVariant());
    }

    @Test
    void perOpenReloadDoesNotReviveAStalePreferenceRemovedByAnotherInstance() {
        FakeOperations operations = new FakeOperations();
        operations.uiPreferences = new AccountUiPreferences(
                AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                TestFixtures.ACCOUNT_ID,
                AddSourceTab.FILE,
                Optional.of(SkinVariant.SLIM),
                Set.of());
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        operations.uiPreferences = AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID);

        runtime.dispatchWidget("gallery.add");

        assertEquals(
                SkinVariant.CLASSIC,
                runtime.snapshot().addSource().orElseThrow().preferredVariant());
    }

    @Test
    void cancellingCatalogEditorRestoresTheExactTransientCatalogState() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        runtime.dispatchText("add.catalog.search", "e");
        runtime.dispatchWidget("add.catalog.filter");
        runtime.dispatchWidget("add.catalog.filter");
        AddSourceModel beforeEditor = runtime.snapshot().addSource().orElseThrow();

        runtime.dispatchWidget("add.catalog.skin:minecraft:steve");
        assertTrue(runtime.snapshot().editor().isPresent());
        runtime.dispatchWidget("editor.cancel");

        assertSame(beforeEditor, runtime.snapshot().addSource().orElseThrow());
        assertEquals("e", runtime.snapshot().addSource().orElseThrow().query());
        assertEquals(
                AddSourceModel.CatalogFilter.SLIM,
                runtime.snapshot().addSource().orElseThrow().filter());
    }

    @Test
    void catalogSelectionKeepsAValidPairVariantAndTotalFailureLeavesCatalogUsable() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        operations.failedCatalogModels = Set.of(SkinModel.CLASSIC);

        runtime.dispatchWidget("add.catalog.skin:minecraft:steve");

        assertEquals(SkinVariant.SLIM, runtime.snapshot().editor().orElseThrow().variant());
        assertEquals(
                Set.of(SkinVariant.SLIM),
                runtime.snapshot().editor().orElseThrow().availableCatalogVariants());

        runtime.dispatchWidget("editor.cancel");
        operations.failedCatalogModels = Set.of(SkinModel.CLASSIC, SkinModel.SLIM);
        runtime.dispatchWidget("add.catalog.skin:minecraft:steve");

        assertTrue(runtime.snapshot().editor().isEmpty());
        assertTrue(runtime.snapshot().addSource().isPresent());
    }

    @Test
    void offlineCatalogEditorPublishesSelectedDraftPreviewBeforeSave() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");

        runtime.dispatchWidget("add.catalog.skin:minecraft:steve");

        ViewSpec.Preview preview = runtime.view(854, 480, 0, 0).previews().stream()
                .filter(candidate -> candidate.id().equals("editor.preview"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                Optional.of(new ViewSpec.CatalogImage("minecraft", "steve")),
                preview.catalogImage());
        assertArrayEquals(
                operations.catalogPng,
                runtime.loadSkinPreview(preview).join().orElseThrow());
        assertTrue(runtime.snapshot().selectedPresetId().isEmpty());
    }

    @Test
    void reopeningAddReloadsTheSameCatalogPreviewAfterTheResourceStackChanges() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(854, 480, 0, 0);
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");

        ViewSpec.Preview firstPreview = runtime.view(854, 480, 0, 0).previews().stream()
                .filter(preview -> preview.catalogImage().isPresent())
                .findFirst()
                .orElseThrow();
        byte[] first = runtime.loadSkinPreview(firstPreview).join().orElseThrow();
        assertEquals(1, operations.catalogPreviewCalls);

        runtime.dispatchWidget("add.cancel");
        operations.catalogPng = skinPng(0xFF2266AA);
        runtime.dispatchWidget("gallery.add");
        ViewSpec.Preview reloadedPreview = runtime.view(854, 480, 0, 0).previews().stream()
                .filter(preview -> preview.catalogImage().isPresent())
                .findFirst()
                .orElseThrow();
        byte[] reloaded = runtime.loadSkinPreview(reloadedPreview).join().orElseThrow();

        assertEquals(2, operations.catalogPreviewCalls);
        assertFalse(java.util.Arrays.equals(first, reloaded));
        assertArrayEquals(operations.catalogPng, reloaded);
    }

    @Test
    void personalCatalogReusesItsAssetAndManualRemovalKeepsPresetsAndData() {
        FakeOperations operations = new FakeOperations();
        String hash = operations.seedPersonalSkin("From file");
        UUID personalAssetId = operations.account.personalSkins().get(0)
                .optionalAssetId(SkinVariant.CLASSIC)
                .orElseThrow();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(854, 480, 0, 0);

        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");

        AddSourceModel opened = runtime.snapshot().addSource().orElseThrow();
        assertEquals(
                PersonalSkinCatalog.COLLECTION_ID,
                opened.collections().get(0).id());
        ViewSpec catalog = runtime.view(854, 480, 0, 0);
        assertTrue(catalog.widgets().stream().anyMatch(widget ->
                widget.id().equals("add.catalog.delete:"
                        + PersonalSkinCatalog.COLLECTION_ID + ":" + hash)
                        && widget.kind() == ViewSpec.WidgetKind.ICON_BUTTON));

        int assetsBeforeReuse = operations.account.skinAssets().size();
        runtime.dispatchWidget("add.catalog.skin:" + PersonalSkinCatalog.COLLECTION_ID + ":" + hash);

        PresetEditorModel editor = runtime.snapshot().editor().orElseThrow();
        assertEquals(SkinReference.asset(personalAssetId), editor.skin());
        assertTrue(editor.png().isPresent());
        assertTrue(editor.saveRequest().pngBytes().isEmpty());
        runtime.dispatchWidget("editor.save");

        assertEquals(assetsBeforeReuse, operations.account.skinAssets().size());
        assertEquals(1, operations.account.presets().size());
        assertEquals(
                SkinReference.asset(personalAssetId),
                operations.account.presets().get(0).skin());

        runtime.dispatchWidget("gallery.add");
        int assetsBeforeRemoval = operations.account.skinAssets().size();
        int presetsBeforeRemoval = operations.account.presets().size();
        runtime.dispatchWidget(
                "add.catalog.delete:" + PersonalSkinCatalog.COLLECTION_ID + ":" + hash,
                false,
                InteractionOrigin.KEYBOARD);

        assertEquals("add_source", runtime.view(854, 480, 0, 0).screenId());
        assertEquals(0, operations.removePersonalCalls);
        runtime.dispatchWidget(
                "add.catalog.delete.cancel", false, InteractionOrigin.KEYBOARD);
        ViewSpec restored = runtime.view(854, 480, 0, 0);
        assertEquals("add_source", restored.screenId());
        assertEquals(
                Optional.of("add.catalog.delete:"
                        + PersonalSkinCatalog.COLLECTION_ID + ":" + hash),
                restored.focusRequest().map(ViewSpec.FocusRequest::widgetId));

        runtime.dispatchWidget("add.catalog.delete:"
                + PersonalSkinCatalog.COLLECTION_ID + ":" + hash);
        operations.failPersonalRemoval = true;
        runtime.dispatchWidget("add.catalog.delete.confirm");

        ViewSpec failed = runtime.view(854, 480, 0, 0);
        assertEquals("add_source", failed.screenId());
        assertTrue(failed.texts().stream().anyMatch(text ->
                text.id().equals("add.catalog.status")
                        && text.message().severity() == UiMessage.Severity.ERROR));
        assertTrue(operations.account.personalSkins().get(0).visible());

        operations.failPersonalRemoval = false;
        runtime.dispatchWidget("add.catalog.delete.confirm");

        assertEquals(2, operations.removePersonalCalls);
        assertFalse(operations.account.personalSkins().get(0).visible());
        assertEquals(assetsBeforeRemoval, operations.account.skinAssets().size());
        assertEquals(presetsBeforeRemoval, operations.account.presets().size());
        assertFalse(runtime.snapshot().addSource().orElseThrow().collections().stream()
                .anyMatch(collection -> PersonalSkinCatalog.isCollection(collection.id())));
    }

    @Test
    void otherPlayersCollectionRenameAndDeleteTargetThatExactCollection() {
        FakeOperations operations = new FakeOperations();
        String hash = operations.seedPersonalSkin("jeb_", PersonalSkinSource.PLAYER_NAME);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");

        String renameId = "add.catalog.rename:"
                + PersonalSkinCatalog.OTHER_PLAYERS_COLLECTION_ID + ':' + hash;
        String deleteId = "add.catalog.delete:"
                + PersonalSkinCatalog.OTHER_PLAYERS_COLLECTION_ID + ':' + hash;
        ViewSpec catalog = runtime.view(854, 480, 0, 0);
        assertTrue(catalog.widget(renameId).isPresent());
        assertTrue(catalog.widget(deleteId).isPresent());

        runtime.dispatchWidget(renameId);
        ViewSpec rename = runtime.view(854, 480, 0, 0);
        assertEquals(Optional.of("add.catalog.rename.name"),
                rename.focusRequest().map(ViewSpec.FocusRequest::widgetId));
        assertTrue(rename.widget("add.catalog.rename.name").orElseThrow().selectAllOnFocusAcquire());
        runtime.dispatchText("add.catalog.rename.name", "Dinnerbone");
        runtime.dispatchWidget("add.catalog.rename.save");
        assertEquals("Dinnerbone", operations.account.personalSkins().get(0).displayName());

        runtime.dispatchWidget(deleteId);
        assertEquals(PersonalSkinCatalog.OTHER_PLAYERS_COLLECTION_ID,
                runtime.snapshot().addSource().orElseThrow().personalSkinDeletion()
                        .orElseThrow().collectionId());
        runtime.dispatchWidget("add.catalog.delete.confirm");
        assertFalse(operations.account.personalSkins().get(0).visible());
        assertEquals(1, operations.removePersonalCalls);
    }

    @Test
    void personalSkinRenameKeepsActivationSeparateFromFocusedFieldSubmit() {
        FakeOperations operations = new FakeOperations();
        String hash = operations.seedPersonalSkin("Original name");
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        runtime.dispatchWidget("add.catalog.rename:"
                + PersonalSkinCatalog.COLLECTION_ID + ':' + hash);

        runtime.dispatchText("add.catalog.rename.name", "Name with space");
        ViewSpec rename = runtime.view(854, 480, 0, 0);
        ViewSpec.Widget field = rename.widget("add.catalog.rename.name").orElseThrow();
        assertEquals(Optional.of("add.catalog.rename.save"), field.submitActionId());
        assertFalse(runtime.dispatchNavigation(
                ViewSpec.NavigationCommand.ACTIVATE, field.id()));
        assertEquals("Original name", operations.account.personalSkins().get(0).displayName());
        assertEquals(Optional.of("Name with space"), runtime.view(854, 480, 0, 0)
                .widget(field.id()).orElseThrow().value());

        String submit = ViewHostPolicy.submitAction(
                rename, field.id(), true, "Name with space").orElseThrow();
        runtime.dispatchWidget(submit, false, InteractionOrigin.KEYBOARD);
        assertEquals("Name with space", operations.account.personalSkins().get(0).displayName());
        assertTrue(runtime.view(854, 480, 0, 0).widget(field.id()).isEmpty());
    }

    @Test
    void personalSkinRenameKeepsScrolledCardAndFieldGeometryAcrossTextUpdates() {
        FakeOperations operations = new FakeOperations();
        String hash = operations.seedPersonalSkin("Original name");
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(320, 240, 0, 0);
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        runtime.dispatchWidget("add.catalog.rename:"
                + PersonalSkinCatalog.COLLECTION_ID + ':' + hash);
        runtime.nativeScrollPositionChanged("add.catalog", 16.0);

        String cardId = "add.catalog.skin:" + PersonalSkinCatalog.COLLECTION_ID + ':' + hash;
        ViewSpec before = runtime.view(320, 240, 0, 0);
        double offset = before.scrollSurface("add.catalog").orElseThrow().offsetPixels();
        assertTrue(offset > 0.0);
        Bounds cardBefore = before.widget(cardId).orElseThrow().bounds();
        Bounds fieldBefore = before.widget("add.catalog.rename.name").orElseThrow().bounds();
        Bounds relativeBefore = new Bounds(
                fieldBefore.x() - cardBefore.x(),
                fieldBefore.y() - cardBefore.y(),
                fieldBefore.width(),
                fieldBefore.height());

        for (String value : List.of("Renamed", "Ren", "Renamed again")) {
            runtime.dispatchText("add.catalog.rename.name", value);
            ViewSpec updated = runtime.view(320, 240, 0, 0);
            Bounds card = updated.widget(cardId).orElseThrow().bounds();
            Bounds field = updated.widget("add.catalog.rename.name").orElseThrow().bounds();
            assertEquals(offset, updated.scrollSurface("add.catalog").orElseThrow().offsetPixels());
            assertEquals(cardBefore, card);
            assertEquals(relativeBefore, new Bounds(
                    field.x() - card.x(), field.y() - card.y(), field.width(), field.height()));
            assertEquals(Optional.of("add.catalog.rename.name"),
                    updated.focusRequest().map(ViewSpec.FocusRequest::widgetId));
            assertEquals(Optional.of(value), updated.widget("add.catalog.rename.name").orElseThrow().value());
        }
    }

    @Test
    void personalCatalogModesResetAtWorkspaceBoundariesAndRemainMutuallyExclusive() {
        FakeOperations operations = new FakeOperations();
        String hash = operations.seedPersonalSkin("Workspace skin");
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");

        String collectionId = PersonalSkinCatalog.COLLECTION_ID;
        String renameId = "add.catalog.rename:" + collectionId + ':' + hash;
        String deleteId = "add.catalog.delete:" + collectionId + ':' + hash;

        runtime.dispatchWidget(renameId);
        assertTrue(runtime.view(854, 480, 0, 0).widget("add.catalog.rename.name").isPresent());
        runtime.dispatchWidget("add.tab.file");
        assertEquals(AddSourceTab.FILE,
                runtime.snapshot().addSource().orElseThrow().selectedTab());
        runtime.dispatchWidget("add.tab.catalog");
        assertTrue(runtime.view(854, 480, 0, 0).widget(renameId).isPresent());
        assertTrue(runtime.view(854, 480, 0, 0).widget("add.catalog.rename.name").isEmpty());

        runtime.dispatchWidget(renameId);
        runtime.dispatchWidget(deleteId);
        assertTrue(runtime.view(854, 480, 0, 0).widget("add.catalog.rename.name").isEmpty());
        assertTrue(runtime.snapshot().addSource().orElseThrow().personalSkinDeletion().isPresent());
        runtime.dispatchWidget(renameId);
        assertTrue(runtime.snapshot().addSource().orElseThrow().personalSkinDeletion().isEmpty());
        assertTrue(runtime.view(854, 480, 0, 0).widget("add.catalog.rename.name").isPresent());

        runtime.dispatchWidget("add.catalog.collection:" + collectionId);
        assertTrue(runtime.snapshot().addSource().orElseThrow().collectionCollapsed(collectionId));
        assertTrue(runtime.view(854, 480, 0, 0).widget("add.catalog.rename.name").isEmpty());
        runtime.dispatchWidget("add.catalog.collection:" + collectionId);
        assertTrue(runtime.view(854, 480, 0, 0).widget(renameId).isPresent());

        runtime.dispatchWidget(deleteId);
        runtime.dispatchWidget("add.cancel");
        assertTrue(runtime.snapshot().addSource().isEmpty(),
                "footer Cancel must close the flow in the same dispatch");
        runtime.dispatchWidget("gallery.add");
        assertTrue(runtime.snapshot().addSource().orElseThrow().personalSkinDeletion().isEmpty());
        assertTrue(runtime.view(854, 480, 0, 0).widget("add.catalog.rename.name").isEmpty());
    }

    @Test
    void catalogBulkDisclosurePersistsOneReplacementAndPreservesWorkspaceState() {
        FakeOperations operations = new FakeOperations();
        operations.uiPreferences = new AccountUiPreferences(
                AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                TestFixtures.ACCOUNT_ID,
                AddSourceTab.CATALOG,
                Set.of("future:unknown"));
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        runtime.dispatchText("add.catalog.search", "Steve");
        runtime.dispatchWidget("add.catalog.filter");
        runtime.view(320, 240, 0, 0);

        runtime.dispatchWidget("add.catalog.disclosure");

        AddSourceModel collapsed = runtime.snapshot().addSource().orElseThrow();
        assertTrue(collapsed.anyAvailableCollectionCollapsed());
        assertEquals("Steve", collapsed.query());
        assertEquals(AddSourceModel.CatalogFilter.CLASSIC, collapsed.filter());
        assertTrue(operations.uiPreferences.collapsedCollectionIds().contains("future:unknown"));
        assertTrue(operations.uiPreferences.collapsedCollectionIds()
                .containsAll(collapsed.availableCollectionIds()));
        assertEquals(1, operations.collapsedReplacementCalls);
        assertEquals(UiMessage.info("nclskins.collection.expand_all"),
                runtime.view(320, 240, 0, 0)
                        .widget("add.catalog.disclosure").orElseThrow().label());

        runtime.dispatchWidget("add.catalog.disclosure");
        assertFalse(runtime.snapshot().addSource().orElseThrow().anyAvailableCollectionCollapsed());
        assertEquals(Set.of("future:unknown"), operations.uiPreferences.collapsedCollectionIds());
        assertEquals(2, operations.collapsedReplacementCalls);
    }

    @Test
    void keyboardCatalogBulkDisclosureKeepsFocusOnItsStableId() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        ViewSpec initial = runtime.view(320, 240, 0, 0);
        Optional<ViewSpec.FocusRequest> baselineFocus = initial.focusRequest();

        runtime.dispatchWidget(
                "add.catalog.disclosure", false, InteractionOrigin.POINTER);
        assertEquals(baselineFocus, runtime.view(320, 240, 0, 0).focusRequest());

        runtime.dispatchWidget(
                "add.catalog.disclosure", false, InteractionOrigin.KEYBOARD);

        assertEquals(Optional.of("add.catalog.disclosure"),
                runtime.view(320, 240, 0, 0).focusRequest().map(ViewSpec.FocusRequest::widgetId));
    }

    @Test
    void catalogBulkDisclosureDoesNotPublishAGlobalDisabledIntermediateView() {
        FakeOperations operations = new FakeOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        runtime.initialize();
        worker.runFirst();
        runtime.dispatchWidget("gallery.add");
        worker.runFirst();

        ViewSpec before = runtime.view(320, 240, 0, 0);
        assertTrue(before.widget("add.catalog.search").orElseThrow().enabled());
        assertTrue(before.widget("add.catalog.filter").orElseThrow().enabled());
        assertTrue(before.widget("add.catalog.disclosure").orElseThrow().enabled());
        assertTrue(before.tabGroups().stream()
                .flatMap(group -> group.tabs().stream())
                .allMatch(ViewSpec.Tab::enabled));

        runtime.dispatchWidget(
                "add.catalog.disclosure", false, InteractionOrigin.KEYBOARD);

        ViewSpec optimistic = runtime.view(320, 240, 0, 0);
        assertTrue(optimistic.widget("add.catalog.search").orElseThrow().enabled());
        assertTrue(optimistic.widget("add.catalog.filter").orElseThrow().enabled());
        assertTrue(optimistic.widget("add.catalog.disclosure").orElseThrow().enabled());
        assertTrue(optimistic.tabGroups().stream()
                .flatMap(group -> group.tabs().stream())
                .allMatch(ViewSpec.Tab::enabled));
        assertEquals(1, worker.size(), "durable replacement remains asynchronous");

        worker.runFirst();
        assertEquals(1, operations.collapsedReplacementCalls);
    }

    @Test
    void catalogBulkDisclosureRollsBackTheWholeWorkspaceOnPersistenceFailure() {
        FakeOperations operations = new FakeOperations();
        String hash = operations.seedPersonalSkin("Rollback skin");
        operations.uiPreferences = new AccountUiPreferences(
                AccountUiPreferences.CURRENT_SCHEMA_VERSION,
                TestFixtures.ACCOUNT_ID,
                AddSourceTab.CATALOG,
                Set.of("future:unknown"));
        operations.collapsedReplacementFailure = new IOException("simulated replacement failure");
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        runtime.dispatchText("add.catalog.search", "Rollback");
        runtime.view(320, 240, 0, 0);
        runtime.dispatchWidget("add.catalog.rename:"
                + PersonalSkinCatalog.COLLECTION_ID + ':' + hash);
        assertTrue(runtime.view(320, 240, 0, 0)
                .widget("add.catalog.rename.name").isPresent());

        runtime.dispatchWidget("add.catalog.disclosure");

        AddSourceModel rolledBack = runtime.snapshot().addSource().orElseThrow();
        assertFalse(rolledBack.anyAvailableCollectionCollapsed());
        assertEquals(Set.of("future:unknown"), rolledBack.collapsedCollectionIds());
        assertEquals("Rollback", rolledBack.query());
        assertEquals(Set.of("future:unknown"), operations.uiPreferences.collapsedCollectionIds());
        assertEquals(1, operations.collapsedReplacementCalls);
        assertEquals(UiMessage.error("nclskins.add_source.disclosure_failed"),
                runtime.snapshot().status());
        assertTrue(runtime.view(320, 240, 0, 0)
                .widget("add.catalog.rename.name").isPresent());
        assertTrue(runtime.view(320, 240, 0, 0).texts().stream().anyMatch(text ->
                text.id().equals("add.catalog.status")
                        && text.message().equals(
                        UiMessage.error("nclskins.add_source.disclosure_failed"))));
    }

    @Test
    void galleryDeleteConfirmationDoesNotSurviveLeavingGallery() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(2);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();
        String prefix = "gallery.preset." + presetId;

        runtime.dispatchWidget(prefix + ".delete");
        assertTrue(runtime.view(854, 480, 0, 0)
                .widget(prefix + ".delete_cancel").isPresent());
        runtime.dispatchWidget("gallery.add");
        assertTrue(runtime.snapshot().addSource().isPresent());
        runtime.dispatchWidget("add.cancel");

        ViewSpec gallery = runtime.view(854, 480, 0, 0);
        assertTrue(gallery.widgets().stream()
                .noneMatch(widget -> widget.id().endsWith(".delete_cancel")));
        assertTrue(gallery.widgets().stream()
                .noneMatch(widget -> widget.id().endsWith(".delete_confirm")));
        assertTrue(gallery.widgets().stream()
                .filter(widget -> widget.id().endsWith(".delete"))
                .allMatch(ViewSpec.Widget::enabled));
    }

    @Test
    void addCancelPreservesTheFractionalGalleryPositionAndQuery() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());

        runtime.initialize();
        ViewSpec before = runtime.view(320, 240, 0, 0);
        int initialX = panelX(before, "gallery.card.add");
        runtime.dispatchText("gallery.search", "Preset");
        runtime.pointerScrolled(160, 100, 0.0, -0.5);
        int preservedX = panelX(runtime.view(320, 240, 0, 0), "gallery.card.add");
        assertTrue(preservedX < initialX);

        runtime.dispatchWidget("gallery.add");
        assertTrue(runtime.snapshot().addSource().isPresent());
        runtime.dispatchWidget("add.cancel");

        assertTrue(runtime.snapshot().editor().isEmpty());
        assertTrue(runtime.snapshot().addSource().isEmpty());
        ViewSpec restored = runtime.view(320, 240, 0, 0);
        assertEquals(preservedX, panelX(restored, "gallery.card.add"));
        assertEquals("Preset", restored.widget("gallery.search").orElseThrow().value().orElseThrow());
        assertEquals(5, runtime.snapshot().account().orElseThrow().presets().size());
    }

    @Test
    void duplicateDraftEscapePreservesFractionalGalleryPositionAndQuery() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        UUID sourceId = operations.account.presets().get(0).id();

        runtime.dispatchText("gallery.search", "Preset");
        runtime.view(320, 240, 0, 0);
        runtime.pointerScrolled(160, 100, 0.0, -0.5);
        ViewSpec before = runtime.view(320, 240, 0, 0);
        int fractionalX = panelX(before, "gallery.card.add");
        assertEquals("Preset", before.widget("gallery.search").orElseThrow().value().orElseThrow());

        runtime.dispatchWidget("gallery.preset." + sourceId + ".duplicate");
        runtime.escapePressed();

        ViewSpec restored = runtime.view(320, 240, 0, 0);
        assertEquals(fractionalX, panelX(restored, "gallery.card.add"));
        assertEquals("Preset", restored.widget("gallery.search").orElseThrow().value().orElseThrow());
        assertEquals(5, runtime.snapshot().account().orElseThrow().presets().size());
    }

    @Test
    void galleryWheelPreservesMagnitudeAndAppliesFractionalBoundedPositionsImmediately() {
        FakeOperations halfOperations = new FakeOperations();
        halfOperations.account = TestFixtures.account(5);
        ClientRuntime half = runtime(halfOperations, Runnable::run, Optional.empty());
        half.initialize();
        int startX = panelX(half.view(320, 240, 0, 0), "gallery.card.add");

        half.pointerScrolled(160, 100, 0.0, -0.5);

        assertEquals(startX - 16, panelX(half.view(320, 240, 0, 0), "gallery.card.add"));
        assertEquals(16, half.snapshot().galleryOffset(),
                "a fractional wheel target must remain an exact pixel position");

        FakeOperations fullOperations = new FakeOperations();
        fullOperations.account = TestFixtures.account(5);
        ClientRuntime full = runtime(fullOperations, Runnable::run, Optional.empty());
        full.initialize();
        full.view(320, 240, 0, 0);
        full.pointerScrolled(160, 100, 0.0, -1.0);

        assertEquals(startX - 32, panelX(full.view(320, 240, 0, 0), "gallery.card.add"));
    }

    @Test
    void nativeScrollFeedbackUsesAbsolutePixelsAndRejectsStaleSurfaceIds() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        int startX = panelX(runtime.view(320, 240, 0, 0), "gallery.card.add");

        runtime.nativeScrollPositionChanged("gallery.cards", 16.0);
        assertEquals(startX - 16, panelX(runtime.view(320, 240, 0, 0), "gallery.card.add"));
        runtime.nativeScrollPositionChanged("gallery.cards", 32.0);
        assertEquals(startX - 32, panelX(runtime.view(320, 240, 0, 0), "gallery.card.add"),
                "native feedback is an absolute pixel offset, not another wheel delta");

        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        runtime.view(320, 240, 160, 100);
        runtime.nativeScrollPositionChanged("gallery.cards", 96.0);
        assertEquals(0, runtime.snapshot().addSource().orElseThrow().scrollOffset(),
                "feedback from the previous screen must be ignored");
        runtime.nativeScrollPositionChanged("add.catalog", 16.0);
        assertEquals(16, runtime.snapshot().addSource().orElseThrow().scrollOffset());
        runtime.nativeScrollPositionChanged("add.catalog", 48.0);
        assertEquals(48, runtime.snapshot().addSource().orElseThrow().scrollOffset());

        runtime.nativeScrollPositionChanged("unknown.surface", 100.0);
        assertEquals(48, runtime.snapshot().addSource().orElseThrow().scrollOffset());
        assertThrows(IllegalArgumentException.class,
                () -> runtime.nativeScrollPositionChanged("add.catalog", Double.NaN));
    }

    @Test
    void nativeGalleryScrollKeepsPartialNeighborsWhenSmoothScrollingSettlesOnACardBoundary() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();

        ViewSpec initial = runtime.view(854, 480, 0, 0);
        ViewSpec.ScrollSurface surface = initial.scrollSurface("gallery.cards").orElseThrow();
        double cardStride = surface.maximumPixels()
                / initial.scrollbar().orElseThrow().maximum();
        runtime.nativeScrollPositionChanged("gallery.cards", cardStride * 20.0);

        ViewSpec settled = runtime.view(854, 480, 0, 0);
        Bounds viewport = settled.scrollSurface("gallery.cards").orElseThrow().viewport();
        assertTrue(settled.panels().stream()
                .filter(panel -> panel.style() == ViewSpec.Panel.Style.VANILLA_LIST)
                .map(ViewSpec.Panel::bounds)
                .anyMatch(bounds -> bounds.x() < viewport.x()
                        && bounds.right() > viewport.x()));
        assertTrue(settled.panels().stream()
                .filter(panel -> panel.style() == ViewSpec.Panel.Style.VANILLA_LIST)
                .map(ViewSpec.Panel::bounds)
                .anyMatch(bounds -> bounds.x() < viewport.right()
                        && bounds.right() > viewport.right()));
    }

    @Test
    void nativeCapeScrollFeedbackIsBoundedAndOnlyAppliesInsideTheEditor() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.ownedCapes = capeInventory(5);
        operations.session = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                TestFixtures.validSession().sessionIdentity(),
                null,
                (SessionFailureContext) null,
                "offline");
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(320, 240, 0, 0);
        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        runtime.dispatchWidget("editor.tab.cape");
        int maximum = runtime.snapshot().editor().orElseThrow().maximumCapeScroll(320, 240);
        assertTrue(maximum > 16);

        runtime.nativeScrollPositionChanged("editor.capes", 16.0);
        assertEquals(16, runtime.view(320, 240, 0, 0).scrollbar().orElseThrow().offset());
        runtime.nativeScrollPositionChanged("editor.capes", maximum + 100.0);
        assertEquals(maximum, runtime.view(320, 240, 0, 0).scrollbar().orElseThrow().offset());

        runtime.dispatchWidget("editor.cancel");
        runtime.nativeScrollPositionChanged("editor.capes", 0.0);
        assertTrue(runtime.snapshot().editor().isEmpty());
    }

    @Test
    void runtimePropagatesTargetChromeIntoCapePaneSizing() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(2);
        operations.ownedCapes = capeInventory(5);
        operations.session = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                TestFixtures.validSession().sessionIdentity(),
                null,
                (SessionFailureContext) null,
                "offline");
        ClientRuntime runtime = runtime(
                operations,
                () -> CompletableFuture.completedFuture(Optional.of(Path.of("cape.png"))));
        ViewChromeMetrics targetChrome = new ViewChromeMetrics(38);
        runtime.initialize();
        runtime.view(427, 240, 0, 0, targetChrome);
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        ViewSpec skinCatalog = runtime.view(427, 240, 0, 0);
        Bounds skinCard = skinCatalog.widget("add.catalog.skin:minecraft:steve").orElseThrow().bounds();
        assertEquals(74, skinCard.width());
        assertEquals(93, skinCard.height());

        runtime.dispatchWidget("add.cancel");
        UUID presetId = operations.account.presets().get(1).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        runtime.dispatchWidget("editor.tab.cape");

        ViewSpec initial = runtime.view(427, 240, 0, 0);
        ViewSpec.ScrollSurface initialSurface = initial.scrollSurface("editor.capes").orElseThrow();
        ViewSpec.NavigationNode selected = initial.navigationNode(
                "editor.cape_item.MINECRAFT.cape-1").orElseThrow();
        assertEquals(new Bounds(229, 68, 184, 139), initialSurface.viewport());
        assertEquals(new Bounds(229, 91, 89, 93), selected.bounds());
        assertEquals(336.0, initialSurface.maximumPixels(), 0.0);
        assertEquals(246.0, initialSurface.offsetPixels(), 0.001);
        assertTrue(initial.navigationNodes().stream()
                .filter(node -> node.id().startsWith("editor.cape_item."))
                .allMatch(node -> node.bounds().width() == 89 && node.bounds().height()
                        == (node.id().startsWith("editor.cape_item.OFFLINE.") ? 116 : 93)));
        assertTrue(selected.bounds().y() >= initialSurface.viewport().y());
        assertTrue(selected.bounds().bottom() <= initialSurface.viewport().bottom());
        assertEquals(initialSurface.viewport().y() + initialSurface.viewport().height() / 2.0,
                selected.bounds().y() + selected.bounds().height() / 2.0, 1.0);
        assertEquals(207, initial.panels().stream()
                .filter(panel -> panel.id().equals("footer"))
                .findFirst().orElseThrow().bounds().y());

        runtime.nativeScrollPositionChanged("editor.capes", 0.0);
        ViewSpec atTop = runtime.view(427, 240, 0, 0);
        Bounds capeViewport = atTop.scrollSurface("editor.capes").orElseThrow().viewport();
        runtime.pointerScrolled(
                capeViewport.x() + capeViewport.width() / 2.0,
                capeViewport.y() + capeViewport.height() / 2.0,
                0.0,
                -0.5);
        ViewSpec afterWheel = runtime.view(427, 240, 0, 0);
        assertEquals(16.0, afterWheel.scrollSurface("editor.capes").orElseThrow().offsetPixels(), 0.001);

        runtime.nativeScrollPositionChanged(
                "editor.capes", afterWheel.scrollSurface("editor.capes").orElseThrow().maximumPixels() + 100.0);
        ViewSpec atEnd = runtime.view(427, 240, 0, 0);
        assertEquals(atEnd.scrollSurface("editor.capes").orElseThrow().maximumPixels(),
                atEnd.scrollSurface("editor.capes").orElseThrow().offsetPixels(), 0.0);

        assertTrue(runtime.dispatchNavigation(
                ViewSpec.NavigationCommand.DOWN, "editor.cape_item.MINECRAFT.cape-1"));
        ViewSpec focused = runtime.view(427, 240, 0, 0);
        assertEquals(Optional.of("editor.cape_item.MINECRAFT.cape-3"),
                focused.focusRequest().map(ViewSpec.FocusRequest::widgetId));
        assertTrue(focused.navigationNode("editor.cape_item.MINECRAFT.cape-3").orElseThrow()
                .bounds().bottom() <= focused.scrollSurface("editor.capes").orElseThrow().viewport().bottom());

        runtime.dispatchWidget("editor.cape_disclosure");
        ViewSpec collapsed = runtime.view(427, 240, 0, 0);
        assertEquals(0.0, collapsed.scrollSurface("editor.capes").orElseThrow().maximumPixels(), 0.0);
        runtime.dispatchWidget("editor.cape_disclosure");
        ViewSpec expanded = runtime.view(427, 240, 0, 0);
        assertTrue(expanded.scrollSurface("editor.capes").orElseThrow().maximumPixels() > 0.0);

        runtime.dispatchWidget("editor.cape_item.OFFLINE.import");
        UUID importedId = operations.account.personalCapes().get(0).texture().entryId();
        assertEquals(TestFixtures.ACCOUNT_ID, operations.capeImportAccount);
        ViewSpec imported = runtime.view(427, 240, 0, 0);
        assertEquals(89, imported.widget("editor.cape_item.OFFLINE." + importedId).orElseThrow()
                .bounds().width());
        assertEquals(116, imported.widget("editor.cape_item.OFFLINE." + importedId).orElseThrow()
                .bounds().height());

        runtime.dispatchWidget("editor.cape_action.delete." + importedId);
        runtime.dispatchWidget("editor.cape_action.confirm." + importedId);
        ViewSpec deleted = runtime.view(427, 240, 0, 0);
        assertTrue(deleted.widget("editor.cape_item.OFFLINE." + importedId).isEmpty());
        assertEquals(116, deleted.navigationNode("editor.cape_item.OFFLINE.none").orElseThrow()
                .bounds().height());
    }

    @Test
    void keyboardCapeNavigationScrollsToAnOffscreenLogicalCard() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.ownedCapes = capeInventory(5);
        operations.session = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                TestFixtures.validSession().sessionIdentity(),
                null,
                (SessionFailureContext) null,
                "offline");
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(320, 240, 0, 0);
        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        runtime.dispatchWidget("editor.tab.cape");
        ViewSpec start = runtime.view(320, 240, 0, 0);
        assertEquals(0.0, start.scrollSurface("editor.capes").orElseThrow().offsetPixels());

        assertTrue(runtime.dispatchNavigation(
                ViewSpec.NavigationCommand.DOWN, "editor.cape_item.MINECRAFT.cape-2"));

        ViewSpec scrolled = runtime.view(320, 240, 0, 0);
        assertTrue(scrolled.scrollSurface("editor.capes").orElseThrow().offsetPixels() > 0.0);
        assertEquals(Optional.of("editor.cape_item.MINECRAFT.cape-4"),
                scrolled.focusRequest().map(ViewSpec.FocusRequest::widgetId));
        assertTrue(scrolled.widget("editor.cape_item.MINECRAFT.cape-4").isPresent());
        assertTrue(runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, "editor.cape_item.MINECRAFT.cape-4"));
        assertEquals(Optional.of("cape-4"), runtime.snapshot().editor().orElseThrow().capeCatalog().previewCape());
    }

    @Test
    void pressingTheFractionalGalleryThumbDoesNotJumpToTheRoundedSnapshotOffset() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(320, 240, 0, 0);
        runtime.pointerScrolled(160, 100, 0.0, -0.5);

        ViewSpec before = runtime.view(320, 240, 0, 0);
        Bounds thumb = before.scrollbar().orElseThrow().thumb();
        int cardX = panelX(before, "gallery.card.add");
        double pressX = thumb.x() + thumb.width() * 0.37;
        double pressY = thumb.y() + thumb.height() / 2.0;

        runtime.pointerPressed(pressX, pressY, 0);
        runtime.pointerReleased(0);

        ViewSpec after = runtime.view(320, 240, 0, 0);
        assertEquals(cardX, panelX(after, "gallery.card.add"));
        assertEquals(
                before.scrollbar().orElseThrow().offset(),
                after.scrollbar().orElseThrow().offset());
    }

    @Test
    void applyingAnotherPresetKeepsItVisibleAndDiscardsTheFractionalGalleryTarget() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        int initialX = panelX(runtime.view(320, 240, 0, 0), "gallery.card.add");
        runtime.pointerScrolled(160, 100, 0.0, -0.5);
        assertEquals(initialX - 16, panelX(runtime.view(320, 240, 0, 0), "gallery.card.add"));

        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        ViewSpec aligned = runtime.view(320, 240, 0, 0);
        assertHorizontallyVisible(
                aligned.panels().stream()
                        .filter(panel -> panel.id().equals("gallery.card." + presetId))
                        .findFirst()
                        .orElseThrow()
                        .bounds(),
                320);
        assertEquals(0, runtime.snapshot().galleryOffset());
        int alignedAddX = panelX(aligned, "gallery.card.add");
        settleScroll(runtime);
        assertEquals(alignedAddX, panelX(runtime.view(320, 240, 0, 0), "gallery.card.add"),
                "the stale fractional target must not restore the previous scroll position");
    }

    @Test
    void externalOpenAndReopenKeepTheActivePresetVisibleAtEverySupportedViewport() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        UUID active = operations.account.presets().get(4).id();
        operations.activePresetId = Optional.of(active);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());

        runtime.initialize();
        for (int[] viewport : List.of(
                new int[]{240, 240},
                new int[]{320, 240},
                new int[]{427, 240},
                new int[]{854, 480})) {
            ViewSpec view = runtime.view(viewport[0], viewport[1], 0, 0);
            assertHorizontallyVisible(
                    view.panels().stream()
                            .filter(panel -> panel.id().equals("gallery.card." + active))
                            .findFirst()
                            .orElseThrow()
                            .bounds(),
                    viewport[0]);
        }

        runtime.view(320, 240, 0, 0);
        runtime.pointerScrolled(160, 100, 0.0, -0.5);
        runtime.closeScreen();
        runtime.reopen();
        ViewSpec reopened = runtime.view(320, 240, 0, 0);
        assertHorizontallyVisible(
                reopened.panels().stream()
                        .filter(panel -> panel.id().equals("gallery.card." + active))
                        .findFirst()
                        .orElseThrow()
                        .bounds(),
                320);
    }

    @Test
    void coldOpenPublishesOnlyANeutralLoadingShellUntilInitializationCompletes() {
        FakeOperations operations = new FakeOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());

        runtime.initialize();

        ClientSnapshot loading = runtime.snapshot();
        assertEquals(ClientSnapshot.Lifecycle.INITIALIZING, loading.lifecycle());
        assertTrue(loading.account().isEmpty());
        ViewSpec view = runtime.view(320, 240, 0, 0);
        assertTrue(view.texts().stream().anyMatch(text -> text.id().equals("gallery.loading")));
        assertTrue(view.widget("gallery.search").isEmpty());
        assertTrue(view.widget("gallery.retry_session").isEmpty());
        assertTrue(view.texts().stream().noneMatch(text -> text.id().equals("gallery.offline")));
        assertTrue(view.panels().stream().noneMatch(panel ->
                panel.id().startsWith("gallery.card.")));

        worker.runFirst();

        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        assertTrue(runtime.view(320, 240, 0, 0).widget("gallery.search").isPresent());
    }

    @Test
    void warmedOpenPublishesCardsAndAnchorAtomicallyWithoutTransientOfflineChrome() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        UUID warmedActive = operations.account.presets().get(4).id();
        operations.activePresetId = Optional.of(warmedActive);
        operations.session = session(SessionStatus.OFFLINE_OR_INVALID);
        operations.warmedInitialData = Optional.of(operations.initial());
        operations.activePresetId = Optional.empty();
        operations.session = TestFixtures.validSession();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        List<ClientSnapshot> publications = new ArrayList<>();
        runtime.subscribe(publications::add);

        runtime.initialize();

        ClientSnapshot seeded = runtime.snapshot();
        assertEquals(ClientSnapshot.Lifecycle.INITIALIZING, seeded.lifecycle());
        assertEquals(Optional.of(warmedActive), seeded.activePresetId());
        assertEquals(0, seeded.galleryOffset());
        ViewSpec seededView = runtime.view(320, 240, 0, 0);
        assertHorizontallyVisible(
                seededView.panels().stream()
                        .filter(panel -> panel.id().equals("gallery.card." + warmedActive))
                        .findFirst()
                        .orElseThrow()
                        .bounds(),
                320);
        assertTrue(seededView.widget("gallery.retry_session").isEmpty());
        assertTrue(seededView.texts().stream().noneMatch(text ->
                text.id().equals("gallery.offline")));

        worker.runFirst();

        ClientSnapshot ready = runtime.snapshot();
        assertEquals(ClientSnapshot.Lifecycle.READY, ready.lifecycle());
        assertTrue(ready.activePresetId().isEmpty());
        assertEquals(0, ready.galleryOffset());
        assertEquals(12, panelX(runtime.view(320, 240, 0, 0), "gallery.card.add"));
        publications.stream()
                .filter(snapshot -> snapshot.account().isPresent())
                .forEach(snapshot -> assertEquals(
                        0,
                        snapshot.galleryOffset(),
                        "account data and its gallery anchor must share one publication"));
    }

    @Test
    void repeatedOpenUsesTheLastReadyCardsAndFreshUnchangedDataDoesNotMoveThem() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        UUID active = operations.account.presets().get(4).id();
        operations.activePresetId = Optional.of(active);
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        runtime.initialize();
        worker.runFirst();
        runtime.view(320, 240, 0, 0);
        runtime.pointerScrolled(160, 100, 0.0, -0.5);
        runtime.closeScreen();

        runtime.reopen();

        ClientSnapshot reopening = runtime.snapshot();
        assertEquals(ClientSnapshot.Lifecycle.INITIALIZING, reopening.lifecycle());
        assertEquals(Optional.of(active), reopening.activePresetId());
        ViewSpec seeded = runtime.view(320, 240, 0, 0);
        assertHorizontallyVisible(
                seeded.panels().stream()
                        .filter(panel -> panel.id().equals("gallery.card." + active))
                        .findFirst()
                        .orElseThrow()
                        .bounds(),
                320);
        int seededAddX = panelX(seeded, "gallery.card.add");

        worker.runFirst();

        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        assertEquals(seededAddX, panelX(
                runtime.view(320, 240, 0, 0), "gallery.card.add"));
    }

    @Test
    void identicalTextDispatchesDoNotPublishOrResetGalleryScroll() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        String personalHash = operations.seedPersonalSkin("Personal");
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        AtomicInteger publications = new AtomicInteger();
        runtime.initialize();
        runtime.subscribe(ignored -> publications.incrementAndGet());

        runtime.dispatchText("gallery.search", "Preset");
        runtime.view(320, 240, 0, 0);
        runtime.pointerScrolled(160, 100, 0.0, -0.5);
        int scrolledX = panelX(runtime.view(320, 240, 0, 0), "gallery.card.add");
        int afterScrollPublications = publications.get();
        long generation = runtime.snapshot().generation();

        runtime.dispatchText("gallery.search", "Preset");

        assertEquals(afterScrollPublications, publications.get());
        assertEquals(generation, runtime.snapshot().generation());
        assertEquals(scrolledX, panelX(runtime.view(320, 240, 0, 0), "gallery.card.add"));

        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        String editorName = runtime.snapshot().editor().orElseThrow().name();
        assertNoPublication(publications, () -> runtime.dispatchText("editor.name", editorName));
        runtime.dispatchWidget("editor.cancel");

        runtime.dispatchWidget("gallery.add");
        AddSourceModel addSource = runtime.snapshot().addSource().orElseThrow();
        assertNoPublication(publications, () ->
                runtime.dispatchText("add.catalog.search", addSource.query()));
        assertNoPublication(publications, () ->
                runtime.dispatchText("add.player.input", addSource.playerInput()));
        assertNoPublication(publications, () ->
                runtime.dispatchText("add.url.input", addSource.urlInput()));

        runtime.dispatchWidget("add.tab.catalog");
        runtime.dispatchWidget("add.catalog.rename:"
                + PersonalSkinCatalog.COLLECTION_ID + ":" + personalHash);
        ViewSpec renameView = runtime.view(320, 240, 0, 0);
        assertEquals(
                Optional.of("add.catalog.rename.name"),
                renameView.focusRequest().map(ViewSpec.FocusRequest::widgetId));
        assertTrue(renameView.widget("add.catalog.rename.name")
                .orElseThrow()
                .selectAllOnFocusAcquire());
        String renameValue = renameView.widget("add.catalog.rename.name")
                .orElseThrow()
                .value()
                .orElseThrow();
        assertNoPublication(publications, () ->
                runtime.dispatchText("add.catalog.rename.name", renameValue));
    }

    @Test
    void typedPublicImportFailuresExposeOnlySafeLocalizationKeys() {
        Map<PublicSkinImportException.Code, String> playerFailures = Map.of(
                PublicSkinImportException.Code.INVALID_IDENTIFIER,
                "nclskins.add_source.player_invalid_identifier",
                PublicSkinImportException.Code.PROFILE_NOT_FOUND,
                "nclskins.add_source.player_not_found",
                PublicSkinImportException.Code.RATE_LIMITED,
                "nclskins.add_source.player_rate_limited",
                PublicSkinImportException.Code.SERVICE_UNAVAILABLE,
                "nclskins.add_source.player_service_unavailable",
                PublicSkinImportException.Code.NETWORK_FAILURE,
                "nclskins.add_source.player_service_unavailable",
                PublicSkinImportException.Code.PROFILE_REJECTED,
                "nclskins.add_source.player_rejected",
                PublicSkinImportException.Code.OVERSIZED,
                "nclskins.add_source.player_oversized");
        playerFailures.forEach((code, key) -> assertImportFailure(true, code, key));

        Map<PublicSkinImportException.Code, String> urlFailures = Map.of(
                PublicSkinImportException.Code.UNSAFE_URL,
                "nclskins.add_source.url_unsafe",
                PublicSkinImportException.Code.REDIRECT_REJECTED,
                "nclskins.add_source.url_redirect_rejected",
                PublicSkinImportException.Code.SITE_BLOCKED,
                "nclskins.add_source.url_site_blocked",
                PublicSkinImportException.Code.NETWORK_FAILURE,
                "nclskins.add_source.url_network_failure",
                PublicSkinImportException.Code.SERVICE_UNAVAILABLE,
                "nclskins.add_source.url_network_failure",
                PublicSkinImportException.Code.RATE_LIMITED,
                "nclskins.add_source.url_rate_limited",
                PublicSkinImportException.Code.OVERSIZED,
                "nclskins.add_source.url_oversized",
                PublicSkinImportException.Code.INVALID_PNG,
                "nclskins.add_source.url_invalid_file");
        urlFailures.forEach((code, key) -> assertImportFailure(false, code, key));
    }

    @Test
    void publicPlayerLookupRemainsAvailableForAnOfflineCurrentSession() {
        FakeOperations operations = new FakeOperations();
        operations.playerImportVariant = SkinVariant.SLIM;
        operations.session = session(SessionStatus.OFFLINE_OR_INVALID);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();

        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");
        runtime.dispatchText("add.player.input", "Notch");

        ViewSpec importView = runtime.view(320, 240, 0, 0);
        assertTrue(importView.widget("add.player.input").orElseThrow().enabled());
        assertTrue(importView.widget("add.player.load").orElseThrow().enabled());

        runtime.dispatchWidget("add.player.load");

        assertEquals(1, operations.playerImportCalls);
        PresetEditorModel editor = runtime.snapshot().editor().orElseThrow();
        assertEquals(SkinVariant.SLIM, editor.variant());
        assertEquals(SkinVariant.SLIM, editor.saveRequest().initialVariant());
        assertEquals(Optional.of(SkinVariant.SLIM), operations.uiPreferences.preferredSkinVariant());
        assertFalse(runtime.snapshot().session().orElseThrow().valid());
        assertTrue(runtime.view(320, 240, 0, 0).texts().stream()
                .noneMatch(text -> text.id().equals("editor.status")));
    }

    @Test
    void cpmBaseReturnDoesNotResurrectConfirmedPersonalRemoval(@TempDir Path directory) throws Exception {
        Path model=Files.write(directory.resolve("fixture.cpmmodel"),validModel(false));
        var operations=new FakeOperations();String hash=operations.seedPersonalSkin("Fixture");
        var runtime=runtime(operations,()->CompletableFuture.completedFuture(Optional.of(model)));
        useBaseConverter(runtime,new BaseOnlyConverter(com.naocraftlab.skins.client.SkinModel.CLASSIC));
        runtime.reopen(ScreenDestination.SKIN_IMPORT);runtime.dispatchWidget("add.file.choose");
        runtime.dispatchWidget("add.catalog.delete:"+PersonalSkinCatalog.COLLECTION_ID+":"+hash);
        runtime.dispatchWidget("add.catalog.delete.confirm");
        assertFalse(operations.account.personalSkins().get(0).visible());
        runtime.dispatchWidget("add.cancel");
        assertEquals(AddSourceModel.Purpose.CREATE_PRESET,runtime.snapshot().addSource().orElseThrow().importContext().purpose());
        assertTrue(runtime.snapshot().addSource().orElseThrow().collections().stream().noneMatch(c->PersonalSkinCatalog.isCollection(c.id())));
        runtime.close();
    }

    @Test
    void cpmBaseQueuedAutomaticProbeStopsAtRealRuntimeBoundary(@TempDir Path directory) throws Exception {
        Path model=Files.write(directory.resolve("fixture.cpmmodel"),validModel(false));
        var queued=new java.util.ArrayDeque<Runnable>();boolean[] hold={false};
        Executor worker=action->{if(hold[0])queued.add(action);else action.run();};
        var operations=new FakeOperations();
        var runtime=new ClientRuntime(new DefaultRuntimeServices(),operations,CLIENT,
                ()->CompletableFuture.completedFuture(Optional.of(model)),worker,TEXT,Optional.empty(),Optional.empty(),DiagnosticSinks.discarding());
        useBaseConverter(runtime,new BaseOnlyConverter(com.naocraftlab.skins.client.SkinModel.CLASSIC));
        runtime.reopen(ScreenDestination.SKIN_IMPORT);
        hold[0]=true;runtime.dispatchWidget("add.external.launcher");
        assertFalse(queued.isEmpty());
        runtime.escapePressed();hold[0]=false;runtime.dispatchWidget("add.file.choose");
        assertEquals(AddSourceModel.Purpose.CPM_BASE,runtime.snapshot().addSource().orElseThrow().importContext().purpose());
        assertEquals(null,operations.lastExternalSource);
        queued.remove().run();
        assertEquals(null,operations.lastExternalSource);
        assertFalse(runtime.snapshot().busy());runtime.close();
    }

    @Test
    void pickerReasonNestedSyncReachesActualChooser(@TempDir Path directory) throws Exception {
        assertComposedNestedReason(directory, false);
    }

    @Test
    void pickerReasonNestedAsyncReachesActualChooser(@TempDir Path directory) throws Exception {
        assertComposedNestedReason(directory, true);
    }

    @Test
    void pickerReasonMissingSyncReachesActualImport(@TempDir Path directory) throws Exception {
        assertComposedDependencyReason(directory, false, false, false);
    }

    @Test
    void pickerReasonMissingAsyncReachesActualImport(@TempDir Path directory) throws Exception {
        assertComposedDependencyReason(directory, true, false, false);
    }

    @Test
    void pickerReasonUnavailableSyncReachesActualImport(@TempDir Path directory) throws Exception {
        assertComposedDependencyReason(directory, false, true, false);
    }

    @Test
    void pickerReasonUnavailableAsyncReachesActualImport(@TempDir Path directory) throws Exception {
        assertComposedDependencyReason(directory, true, true, false);
    }

    @Test
    void pickerReasonDirectMissingUsesEnvironmentPresence(@TempDir Path directory) throws Exception {
        assertComposedDependencyReason(directory, false, false, true);
    }

    @Test
    void pickerReasonDirectUnavailableUsesEnvironmentPresence(@TempDir Path directory) throws Exception {
        assertComposedDependencyReason(directory, false, true, true);
    }

    @Test
    void pickerReasonHealthyModelAndBaseBeforeCorrection(@TempDir Path directory) throws Exception {
        for (boolean async : List.of(false, true)) {
            var picker = new ComposedReasonPicker(async);
            var operations = new FakeOperations();
            var runtime = runtime(operations, picker);
            var adapter = new BaseOnlyConverter(com.naocraftlab.skins.client.SkinModel.CLASSIC);
            var carrier = javax.imageio.ImageIO.read(Path.of("../core/src/test/resources/cpm/classic-a.png").toFile());
            adapter.output = carrier.getRGB(0, 0, 64, 64, null, 0, 64);
            useBaseConverter(runtime, adapter);
            try {
                Path model = Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-embedded.cpmmodel");
                Path png = Files.write(directory.resolve("healthy-" + async + ".PNG"), opaqueSkinPng(false));
                var before = operations.account;
                picker.selections.add(CompletableFuture.completedFuture(Optional.of(model)));
                runtime.reopen(ScreenDestination.SKIN_IMPORT);
                runtime.dispatchWidget("add.file.choose");
                assertTrue(picker.allowModel);
                assertEquals(AddSourceModel.Purpose.CPM_BASE, runtime.snapshot().addSource().orElseThrow().importContext().purpose());
                runtime.dispatchWidget("add.tab.file");
                picker.selections.add(CompletableFuture.completedFuture(Optional.of(png)));
                runtime.dispatchWidget("add.file.choose");
                assertFalse(picker.last.isCompletedExceptionally());
                assertTrue(runtime.snapshot().editor().isPresent());
                assertEquals(1, adapter.conversions);
                assertEquals(0, adapter.closes);
                assertEquals(before, operations.account);
                assertEquals(0, operations.editorSaveCalls);
                assertEquals(0, operations.applyCalls);
            } finally {
                runtime.close();
            }
            assertEquals(1, adapter.closes);
        }
    }

    @Test
    void pickerReasonNativeAndInvalidSelectionsStayGeneric(@TempDir Path directory) throws Exception {
        for (boolean async : List.of(false, true)) {
            var picker = new ComposedReasonPicker(async);
            var operations = new FakeOperations();
            var runtime = runtime(operations, picker);
            runtime.reopen(ScreenDestination.SKIN_IMPORT);
            var before = operations.account;
            var failure = new CompletableFuture<Optional<Path>>();
            failure.completeExceptionally(new IllegalStateException("synthetic native failure"));
            var nullResult = new CompletableFuture<Optional<Path>>();
            nullResult.complete(null);
            Path unsupported = Files.write(directory.resolve("unsupported-" + async + ".cpmproject"), new byte[]{1});
            Path nonregular = Files.createDirectory(directory.resolve("directory-" + async + ".cpmmodel"));
            for (var selection : List.of(failure, nullResult,
                    CompletableFuture.completedFuture(Optional.of(unsupported)),
                    CompletableFuture.completedFuture(Optional.of(nonregular)),
                    CompletableFuture.completedFuture(Optional.of(directory.resolve("missing.cpmmodel"))))) {
                picker.selections.add(selection);
                runtime.dispatchWidget("add.file.choose");
                assertTrue(picker.last.isCompletedExceptionally());
                assertEquals("nclskins.error.picker", runtime.snapshot().status().key());
                assertTrue(runtime.snapshot().addSource().orElseThrow().importContext().failure().isEmpty());
                assertTrue(runtime.snapshot().editor().isEmpty());
                assertFalse(runtime.snapshot().busy());
                assertEquals(before, operations.account);
            }
            picker.selections.add(CompletableFuture.completedFuture(Optional.empty()));
            runtime.dispatchWidget("add.file.choose");
            assertEquals("nclskins.status.cancelled", runtime.snapshot().status().key());
            runtime.close();
        }
    }

    @Test
    void pickerReasonStaleCoordinatorCompletionKeepsNativeGuardAndNewScope(@TempDir Path directory) throws Exception {
        for (boolean async : List.of(false, true)) {
            var queued = new java.util.ArrayDeque<Runnable>();
            boolean[] hold = {false};
            var picker = new ComposedReasonPicker(async, action -> { if (hold[0]) queued.add(action); else action.run(); });
            var operations = new FakeOperations();
            var work = new java.util.ArrayDeque<Runnable>();
            boolean[] holdWork = {false};
            var runtime = new ClientRuntime(new DefaultRuntimeServices(), operations, CLIENT, picker,
                    action -> { if (holdWork[0]) work.add(action); else action.run(); }, TEXT,
                    Optional.empty(), Optional.empty(), DiagnosticSinks.discarding());
            var adapter = new BaseOnlyConverter(com.naocraftlab.skins.client.SkinModel.CLASSIC);
            useBaseConverter(runtime, adapter);
            Path model = Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-embedded.cpmmodel");
            picker.selections.add(CompletableFuture.completedFuture(Optional.of(model)));
            runtime.reopen(ScreenDestination.SKIN_IMPORT);
            runtime.dispatchWidget("add.file.choose");
            runtime.dispatchWidget("add.tab.file");
            var nativeResult = async ? new CompletableFuture<Optional<Path>>()
                    : CompletableFuture.completedFuture(Optional.of(model));
            picker.selections.add(nativeResult);
            if (!async) hold[0] = true;
            runtime.dispatchWidget("add.file.choose");
            assertTrue(runtime.snapshot().busy());
            var stale = picker.last;
            runtime.escapePressed();
            assertFalse(stale.isDone(), "UI cancel does not close the native operation");
            runtime.dispatchWidget("add.file.choose");
            assertTrue(picker.last.isCompletedExceptionally(), "Still-open native operation blocks a second picker");
            assertEquals("nclskins.error.picker", runtime.snapshot().status().key());
            holdWork[0] = true;
            runtime.dispatchText("add.url.input", "https://example.test/skin.png");
            runtime.dispatchWidget("add.url.load");
            assertEquals(1, work.size());
            assertTrue(runtime.snapshot().busy());
            if (async) nativeResult.complete(Optional.of(model)); else queued.remove().run();
            hold[0] = false;
            assertTrue(stale.isCompletedExceptionally());
            assertTrue(runtime.snapshot().busy(), "Stale native rejection cannot unlock current URL preparation");
            assertTrue(runtime.snapshot().editor().isEmpty());
            assertEquals(AddSourceModel.Purpose.CREATE_PRESET, runtime.snapshot().addSource().orElseThrow().importContext().purpose());
            assertTrue(runtime.snapshot().addSource().orElseThrow().importContext().failure().isEmpty(), "Old nested failure cannot enter the new parent scope");
            holdWork[0] = false;
            work.remove().run();
            assertFalse(runtime.snapshot().busy());
            assertTrue(runtime.snapshot().editor().isPresent(), "Current synthetic URL preparation still completes");
            runtime.dispatchWidget("editor.cancel");
            picker.selections.add(CompletableFuture.completedFuture(Optional.of(model)));
            runtime.dispatchWidget("add.file.choose");
            assertEquals(AddSourceModel.Purpose.CPM_BASE, runtime.snapshot().addSource().orElseThrow().importContext().purpose());
            assertEquals(1, adapter.closes);
            assertEquals(0, adapter.conversions);
            runtime.close();
            assertEquals(2, adapter.closes);
        }
    }

    private static void assertComposedNestedReason(Path directory, boolean async) throws Exception {
        Path model = Files.copy(Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-embedded.cpmmodel"),
                directory.resolve("fixture.CPMMODEL"));
        Path base = Files.write(directory.resolve("base.PNG"), opaqueSkinPng(false));
        var operations = new FakeOperations();
        var picker = new ComposedReasonPicker(async);
        var runtime = runtime(operations, picker);
        var adapter = new BaseOnlyConverter(com.naocraftlab.skins.client.SkinModel.CLASSIC);
        var carrier = javax.imageio.ImageIO.read(Path.of("../core/src/test/resources/cpm/classic-a.png").toFile());
        adapter.output = carrier.getRGB(0, 0, 64, 64, null, 0, 64);
        useBaseConverter(runtime, adapter);
        try {
            picker.selections.add(CompletableFuture.completedFuture(Optional.of(model)));
            runtime.reopen(ScreenDestination.SKIN_IMPORT);
            var parent = runtime.snapshot().addSource().orElseThrow();
            runtime.dispatchWidget("add.file.choose");
            assertFalse(picker.last.isCompletedExceptionally(), "Actual outer coordinator admits the existing model file");
            assertEquals(AddSourceModel.Purpose.CPM_BASE, runtime.snapshot().addSource().orElseThrow().importContext().purpose());
            runtime.dispatchWidget("add.tab.file");
            var chooser = runtime.snapshot().addSource().orElseThrow();
            var before = operations.account;
            picker.selections.add(CompletableFuture.completedFuture(Optional.of(model)));
            runtime.dispatchWidget("add.file.choose");
            assertTrue(picker.last.isCompletedExceptionally(), "PNG-only coordinator must reject, never return a model as PNG");
            assertFalse(runtime.snapshot().busy());
            assertTrue(runtime.snapshot().editor().isEmpty());
            assertEquals(before, operations.account);
            assertEquals(0, adapter.conversions);
            assertEquals(0, adapter.closes);
            assertEquals(chooser.importContext().requiredVariant(), runtime.snapshot().addSource().orElseThrow().importContext().requiredVariant());
            assertEquals(chooser.filter(), runtime.snapshot().addSource().orElseThrow().filter());
            assertEquals("nclskins.add_source.cpm.nested_file",
                    runtime.view(240, 240, 0, 0).texts().stream().filter(text -> text.id().equals("add.import.status"))
                            .findFirst().orElseThrow().message().key(),
                    "Real coordinator rejection must reach the exact nested-model explanation");
            assertEquals(Optional.of(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.NESTED_MODEL),
                    runtime.snapshot().addSource().orElseThrow().importContext().failure());
            picker.selections.add(CompletableFuture.completedFuture(Optional.of(base)));
            runtime.dispatchWidget("add.file.choose");
            assertTrue(runtime.snapshot().editor().isPresent(), "Valid same-type PNG remains accepted after rejected nested selection");
            assertEquals(1, adapter.conversions);
            assertEquals(before, operations.account);
            assertEquals(0, operations.editorSaveCalls);
            runtime.dispatchWidget("editor.cancel");
            runtime.dispatchWidget("add.cancel");
            var returned = runtime.snapshot().addSource().orElseThrow();
            assertEquals(parent.importContext(), returned.importContext());
            assertEquals(parent.filter(), returned.filter());
            assertEquals(parent.selectedTab(), returned.selectedTab());
        } finally {
            runtime.close();
        }
        assertEquals(1, adapter.closes);
    }

    private static void assertComposedDependencyReason(Path directory, boolean async, boolean present, boolean direct) throws Exception {
        Path model = Files.copy(Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/classic-embedded.cpmmodel"),
                directory.resolve("fixture.cpmmodel"));
        Path png = Files.write(directory.resolve("ordinary.PNG"), opaqueSkinPng(true));
        var operations = new FakeOperations();
        operations.providers = operations.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM);
        assertFalse(operations.providers.cape().enabled(BuiltinProvider.CPM));
        var picker = new ComposedReasonPicker(async);
        FilePicker selectedPicker = direct ? () -> CompletableFuture.completedFuture(Optional.of(model)) : picker;
        var runtime = runtime(operations, selectedPicker);
        runtime.useSkinExtensionEnvironmentSource(new com.naocraftlab.skins.client.SkinExtensionEnvironmentSource() {
            public Snapshot snapshot() {
                return new Snapshot(1, Map.of(Consumer.CUSTOMIZABLE_PLAYER_MODELS, State.INACTIVE), present);
            }
            public Optional<com.naocraftlab.skins.client.CpmModelFileConversion> modelFileConversion() {
                return Optional.empty();
            }
        });
        try {
            runtime.reopen(ScreenDestination.SKIN_IMPORT);
            var before = operations.account;
            if (!direct) {
                picker.selections.add(CompletableFuture.completedFuture(Optional.of(png)));
                runtime.dispatchWidget("add.file.choose");
                assertFalse(picker.allowModel, "Unavailable/absent CPM never broadens native picker purpose");
                assertTrue(runtime.snapshot().editor().isPresent(), "Ordinary PNG is a healthy setup control");
                assertEquals(SkinVariant.SLIM, runtime.snapshot().editor().orElseThrow().variant());
                runtime.dispatchWidget("editor.cancel");
                picker.selections.add(CompletableFuture.completedFuture(Optional.of(model)));
            }
            runtime.dispatchWidget("add.file.choose");
            if (!direct) assertTrue(picker.last.isCompletedExceptionally(), "Model stays rejected by real PNG-only admission");
            assertTrue(runtime.snapshot().editor().isEmpty());
            assertFalse(runtime.snapshot().busy());
            assertEquals(before, operations.account);
            assertEquals(0, operations.editorSaveCalls);
            assertEquals(0, operations.applyCalls);
            assertEquals(present ? "nclskins.add_source.cpm.adapter_unavailable" : "nclskins.add_source.cpm.dependency_missing",
                    runtime.view(240, 240, 0, 0).texts().stream().filter(text -> text.id().equals("add.import.status"))
                            .findFirst().orElseThrow().message().key(),
                    "Missing dependency and installed-but-unusable adapter need distinct visible reasons");
            assertEquals(Optional.of(present ? com.naocraftlab.skins.client.CpmModelFileConversion.Reason.CPM_UNAVAILABLE
                            : com.naocraftlab.skins.client.CpmModelFileConversion.Reason.CPM_MISSING),
                    runtime.snapshot().addSource().orElseThrow().importContext().failure());
        } finally {
            runtime.close();
        }
    }

    private static final class ComposedReasonPicker implements FilePicker {
        final FilePickerCoordinator coordinator;
        final java.util.ArrayDeque<CompletableFuture<Optional<Path>>> selections = new java.util.ArrayDeque<>();
        final boolean async;
        boolean allowModel;
        CompletableFuture<Optional<Path>> last;
        ComposedReasonPicker(boolean async) { this(async, Runnable::run); }
        ComposedReasonPicker(boolean async, Executor worker) {
            this.async = async;
            coordinator = new FilePickerCoordinator(worker);
        }
        public CompletableFuture<Optional<Path>> chooseSkinPng() { return choose(false); }
        public CompletableFuture<Optional<Path>> chooseSkinImportFile(boolean allow) {
            allowModel = allow;
            return choose(allow);
        }
        private CompletableFuture<Optional<Path>> choose(boolean allow) {
            last = async ? allow ? coordinator.chooseSkinImportAsync(selections::remove, Runnable::run)
                    : coordinator.chooseAsync(selections::remove, Runnable::run)
                    : allow ? coordinator.chooseSkinImport(() -> selections.remove().join())
                    : coordinator.choose(() -> selections.remove().join());
            return last;
        }
    }

    @Test
    void cpmBaseNestedModelHasDistinctNeutralReasonAndKeepsOriginalHandle(@TempDir Path directory) throws Exception {
        Path model=Files.write(directory.resolve("fixture.CPMMODEL"),validModel(false));
        var operations=new FakeOperations();
        var runtime=runtime(operations,()->CompletableFuture.completedFuture(Optional.of(model)));
        var adapter=new BaseOnlyConverter(com.naocraftlab.skins.client.SkinModel.CLASSIC);useBaseConverter(runtime,adapter);
        runtime.reopen(ScreenDestination.SKIN_IMPORT);runtime.dispatchWidget("add.file.choose");
        runtime.dispatchWidget("add.tab.file");runtime.dispatchWidget("add.file.choose");
        assertEquals("NESTED_MODEL",runtime.snapshot().addSource().orElseThrow().importContext().failure().orElseThrow().name());
        assertEquals(0,adapter.closes);assertEquals(0,adapter.conversions);
        runtime.close();assertEquals(1,adapter.closes);
    }

    @Test
    void cpmBaseManualUnsupportedSuffixCannotBypassOuterPurposeAdmission(@TempDir Path directory) throws Exception {
        Path disguised=Files.write(directory.resolve("fixture.cpmproject"),opaqueSkinPng(false));
        var operations = new FakeOperations();
        var before = operations.account;
        var runtime=runtime(operations,()->CompletableFuture.completedFuture(Optional.of(disguised)));
        runtime.reopen(ScreenDestination.SKIN_IMPORT);runtime.dispatchWidget("add.file.choose");
        assertTrue(runtime.snapshot().editor().isPresent());
        assertEquals(before, operations.account);
        assertEquals(0, operations.editorSaveCalls);
        runtime.close();
    }

    @Test
    void cpmBasePendingEscapeReturnsToOrdinaryDirectImportWithoutClosingNativePicker(@TempDir Path directory) throws Exception {
        Path model=Files.write(directory.resolve("fixture.cpmmodel"),validModel(true));
        var pending=new CompletableFuture<Optional<Path>>();
        var picks=new java.util.ArrayDeque<CompletableFuture<Optional<Path>>>();
        picks.add(CompletableFuture.completedFuture(Optional.of(model)));picks.add(pending);
        var operations=new FakeOperations();
        var runtime=runtime(operations,()->picks.remove());
        var adapter=new BaseOnlyConverter(com.naocraftlab.skins.client.SkinModel.SLIM);
        useBaseConverter(runtime,adapter);
        runtime.reopen(ScreenDestination.SKIN_IMPORT);
        runtime.dispatchWidget("add.file.choose");
        assertEquals(AddSourceModel.Purpose.CPM_BASE,runtime.snapshot().addSource().orElseThrow().importContext().purpose());
        runtime.dispatchWidget("add.tab.file");runtime.dispatchWidget("add.file.choose");
        assertTrue(runtime.snapshot().busy());
        runtime.escapePressed();
        assertFalse(runtime.snapshot().busy());
        assertEquals(AddSourceModel.Purpose.CREATE_PRESET,runtime.snapshot().addSource().orElseThrow().importContext().purpose());
        assertFalse(pending.isDone());
        assertEquals(1,adapter.closes);
        pending.complete(Optional.of(model));
        assertTrue(runtime.snapshot().editor().isEmpty());
        assertEquals(AddSourceModel.Purpose.CREATE_PRESET,runtime.snapshot().addSource().orElseThrow().importContext().purpose());
        runtime.close();
    }

    @Test
    void cpmBaseMismatchProjectsRequiredTypeWithoutConverterOrPersonalWrites(@TempDir Path directory) throws Exception {
        for(var required:com.naocraftlab.skins.client.SkinModel.values()) {
            Path model=Files.write(directory.resolve(required+".cpmmodel"),validModel(required == SkinModel.SLIM));
            Path wrong=Files.write(directory.resolve(required+".png"),opaqueSkinPng(required==com.naocraftlab.skins.client.SkinModel.CLASSIC));
            var picks=new java.util.ArrayDeque<Path>();picks.add(model);picks.add(wrong);
            var operations=new FakeOperations();
            var runtime=runtime(operations,()->CompletableFuture.completedFuture(Optional.of(picks.remove())));
            var adapter=new BaseOnlyConverter(required);useBaseConverter(runtime,adapter);
            runtime.reopen(ScreenDestination.SKIN_IMPORT);
            runtime.dispatchWidget("add.file.choose");
            var account=runtime.snapshot().account().orElseThrow();
            var preferences=operations.uiPreferences;
            runtime.dispatchWidget("add.tab.file");runtime.dispatchWidget("add.file.choose");
            var projection=runtime.snapshot().addSource().orElseThrow().importContext();
            assertEquals(Optional.of(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.VARIANT_UNSUPPORTED),projection.failure());
            assertEquals(Optional.of(required),projection.requiredVariant());
            assertTrue(projection.filterLocked());
            assertEquals(0,adapter.conversions);
            assertTrue(runtime.snapshot().editor().isEmpty());
            assertEquals(account,runtime.snapshot().account().orElseThrow());
            assertEquals(preferences,operations.uiPreferences);
            runtime.close();
        }
    }

    @Test
    void cpmBaseRawIdentityTickRetiresPendingAdmissionAcrossDeliveredAtoBtoA(@TempDir Path directory) throws Exception {
        Path model = Files.write(directory.resolve("fixture.cpmmodel"), validModel(false));
        var queued = new java.util.ArrayDeque<Runnable>();
        boolean[] hold = {false};
        Executor worker = action -> { if (hold[0]) queued.add(action); else action.run(); };
        var operations = new FakeOperations();
        var runtime = new ClientRuntime(new DefaultRuntimeServices(), operations, CLIENT,
                () -> CompletableFuture.completedFuture(Optional.of(model)), worker, TEXT,
                Optional.empty(), Optional.empty(), DiagnosticSinks.discarding());
        var adapter = new BaseOnlyConverter(com.naocraftlab.skins.client.SkinModel.CLASSIC);
        useBaseConverter(runtime, adapter);
        runtime.reopen(ScreenDestination.SKIN_IMPORT);
        var original = operations.session;
        hold[0] = true;
        runtime.dispatchWidget("add.file.choose");
        assertTrue(runtime.snapshot().busy());
        assertFalse(queued.isEmpty());
        operations.session = new SessionValidation(SessionStatus.OFFLINE_OR_INVALID,
                new GameSessionTokenSource.SessionIdentity(UUID.fromString("00000000-0000-0000-0000-000000000002"), "Other"),
                null, (SessionFailureContext) null, "synthetic switch");
        runtime.tick();
        operations.session = original;
        runtime.tick();
        hold[0] = false;
        while (!queued.isEmpty()) queued.remove().run();
        assertTrue(runtime.snapshot().addSource().isEmpty()
                || runtime.snapshot().addSource().orElseThrow().importContext().purpose() == AddSourceModel.Purpose.CREATE_PRESET,
                "Raw identity samples retire the original pending import even when A returns");
        assertTrue(runtime.snapshot().editor().isEmpty());
        assertEquals(0, adapter.admissions, "Invalid queued work never creates native state even after delivered ABA");
        assertEquals(0, adapter.closes, "No handle was acquired to release");
        runtime.close();
    }

    @Test
    void cpmBaseUnchangedEnvironmentReloadRejectsPendingBaseBeforeConversion(@TempDir Path directory) throws Exception {
        Path model = Files.write(directory.resolve("fixture.cpmmodel"), validModel(false));
        Path base = Files.write(directory.resolve("base.png"), opaqueSkinPng(false));
        var picks = new java.util.ArrayDeque<Path>(); picks.add(model); picks.add(base);
        var queued = new java.util.ArrayDeque<Runnable>(); boolean[] hold = {false};
        Executor worker = action -> { if (hold[0]) queued.add(action); else action.run(); };
        var runtime = new ClientRuntime(new DefaultRuntimeServices(), new FakeOperations(), CLIENT,
                () -> CompletableFuture.completedFuture(Optional.of(picks.remove())), worker, TEXT,
                Optional.empty(), Optional.empty(), DiagnosticSinks.discarding());
        var adapter = new BaseOnlyConverter(com.naocraftlab.skins.client.SkinModel.CLASSIC);
        useBaseConverter(runtime, adapter);
        runtime.reopen(ScreenDestination.SKIN_IMPORT); runtime.dispatchWidget("add.file.choose");
        assertTrue(runtime.snapshot().addSource().orElseThrow().importContext().filterLocked());
        runtime.dispatchWidget("add.tab.file"); hold[0] = true; runtime.dispatchWidget("add.file.choose");
        assertTrue(runtime.snapshot().busy());
        runtime.resourcesReloaded();
        hold[0] = false; while (!queued.isEmpty()) queued.remove().run();
        assertEquals(0, adapter.conversions, "Reload must reach the actual pending base conversion guard");
        assertFalse(runtime.snapshot().busy());
        assertTrue(runtime.snapshot().editor().isEmpty());
        assertTrue(runtime.snapshot().addSource().orElseThrow().importContext().filterLocked());
        runtime.close();
    }

    @Test
    void cpmBaseSameConnectionWorldSwitchRejectsPendingBaseThroughApplicationHost(@TempDir Path directory) throws Exception {
        Path model = Files.write(directory.resolve("fixture.cpmmodel"), validModel(false));
        Path base = Files.write(directory.resolve("base.png"), opaqueSkinPng(false));
        var picks = new java.util.ArrayDeque<Path>(); picks.add(model); picks.add(base);
        var queued = new java.util.ArrayDeque<Runnable>(); boolean[] hold = {false};
        Executor worker = action -> { if (hold[0]) queued.add(action); else action.run(); };
        var runtime = new ClientRuntime(new DefaultRuntimeServices(), new FakeOperations(), CLIENT,
                () -> CompletableFuture.completedFuture(Optional.of(picks.remove())), worker, TEXT,
                Optional.empty(), Optional.empty(), DiagnosticSinks.discarding());
        var adapter = new BaseOnlyConverter(com.naocraftlab.skins.client.SkinModel.CLASSIC);
        useBaseConverter(runtime, adapter);
        var host = new ClientApplicationHost<Object>(new DefaultRuntimeServices(), runtime, () -> {});
        Object connection = new Object(), world = new Object();
        tickWorld(host, connection, world);
        runtime.reopen(ScreenDestination.SKIN_IMPORT); runtime.dispatchWidget("add.file.choose");
        runtime.dispatchWidget("add.tab.file"); hold[0] = true; runtime.dispatchWidget("add.file.choose");
        assertTrue(runtime.snapshot().busy());
        tickWorld(host, connection, new Object());
        hold[0] = false; while (!queued.isEmpty()) queued.remove().run();
        assertEquals(0, adapter.conversions, "Same connection with a different world retires pending conversion");
        assertFalse(runtime.snapshot().busy());
        assertTrue(runtime.snapshot().editor().isEmpty());
        host.close();
    }

    private static void tickWorld(ClientApplicationHost<Object> host, Object connection, Object world) throws Exception {
        host.tick(connection, world, true);
    }

    @Test
    void cpmBaseQueuedSaveRejectsBeforeResourceCapeAndRetiresStaleDraft(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory);
        fixture.operations.resourceCapeCollections = List.of(resourceCapeCollection());
        fixture.operations.resourceCapeHashes = Map.of(new ClientOperations.ResourceCapeKey("event", "hero"), "c".repeat(64));
        fixture.openConverted();
        fixture.runtime.dispatchWidget("editor.tab.cape");
        fixture.runtime.dispatchWidget("editor.cape_item.RESOURCE.event.hero");
        assertTrue(fixture.runtime.snapshot().editor().orElseThrow().capeCatalog().selectedResource().isPresent());
        fixture.hold = true;
        fixture.runtime.dispatchWidget("editor.save");
        fixture.operations.session = new SessionValidation(SessionStatus.OFFLINE_OR_INVALID,
                new GameSessionTokenSource.SessionIdentity(UUID.randomUUID(), "Other"), null, (SessionFailureContext) null, "synthetic");
        fixture.drain();
        assertEquals(0, fixture.operations.resourceCapeMaterializations, "Worker admission precedes resource-cape persistence");
        assertNull(fixture.operations.lastEditorSaveRequest);
        assertTrue(fixture.runtime.snapshot().editor().isEmpty(), "Stale draft cannot offer retry under B");
        assertTrue(fixture.runtime.snapshot().addSource().isEmpty(), "Stale parent chooser must not reopen");
        fixture.runtime.close();
    }

    @Test
    void cpmBaseSaveRequestCarriesInitiatingEditScopeAcrossCopies(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory);
        fixture.openConverted();
        fixture.runtime.dispatchText("editor.name", "Scoped result");
        fixture.runtime.dispatchWidget("editor.save");
        var request = fixture.operations.lastEditorSaveRequest;
        assertTrue(request != null, "Healthy converted draft reaches the existing Save request consumer");
        var scope = request.saveScope();
        assertTrue(scope.isPresent(), "Actual converted Save request preserves initiating edit scope");
        assertEquals(scope, request.withOfflineCape(null).withFrozenCatalogSelection(Optional.empty()).saveScope());
        assertEquals(fixture.operations.account.accountId(), scope.orElseThrow().initiatingAccountId());
        fixture.runtime.close();
    }

    @Test
    void cpmBaseReplacementCancelDoesNotDiscardPendingDraftCatalog(@TempDir Path directory) throws Exception {
        for (int outcome = 0; outcome < 3; outcome++) {
            var fixture = new CpmLifecycleFixture(directory); fixture.openConverted();
            byte[] before = fixture.runtime.snapshot().editor().orElseThrow().png().orElseThrow().bytes();
            fixture.operations.resourceCapeCollections = List.of(resourceCapeCollection());
            fixture.operations.resourceCapeHashes = Map.of(new ClientOperations.ResourceCapeKey("event", "hero"), "c".repeat(64));
            fixture.hold = true; fixture.runtime.resourcesReloaded();
            assertFalse(fixture.workers.isEmpty()); fixture.workers.remove().run();
            assertFalse(fixture.workers.isEmpty(), "Actual Editor catalog publication is still pending");
            assertTrue(fixture.runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream().noneMatch(card -> card.resource() != null));
            fixture.picks.add(outcome == 0 ? CompletableFuture.completedFuture(Optional.empty())
                    : outcome == 1 ? CompletableFuture.failedFuture(new IOException("synthetic picker failure"))
                    : CompletableFuture.completedFuture(Optional.of(Files.write(directory.resolve("invalid.png"), new byte[]{1}))));
            fixture.runtime.dispatchWidget("editor.png.choose");
            assertTrue(fixture.picks.isEmpty(), "Replacement dispatch must reach the existing PNG picker owner");
            fixture.drain();
            assertFalse(fixture.runtime.snapshot().busy());
            var editor = fixture.runtime.snapshot().editor().orElseThrow();
            assertFalse(editor.busy()); assertArrayEquals(before, editor.png().orElseThrow().bytes());
            assertFalse(editor.variantAvailable(SkinVariant.SLIM));
            assertEquals(0, fixture.adapter.closes); assertEquals(1, fixture.adapter.conversions);
            assertTrue(editor.capeCatalog().cards().stream().anyMatch(card -> card.resource() != null),
                    "Independent replacement cancellation/failure must not discard unchanged draft publication");
            fixture.runtime.close();
        }
    }

    @Test
    void cpmBaseSuccessfulPngReplacementRetiresModelWithoutRetiringEditScope(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory); fixture.openConverted();
        fixture.operations.failEditorSave = true;
        fixture.runtime.dispatchWidget("editor.save");
        var scope = fixture.operations.lastEditorSaveRequest.saveScope().orElseThrow();
        assertTrue(scope.stillCurrent().getAsBoolean());
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
        fixture.runtime.dispatchWidget("editor.png.choose");
        assertTrue(fixture.picks.isEmpty());
        assertEquals(1, fixture.adapter.closes, "Successful ordinary replacement retires the old model handle");
        assertTrue(fixture.runtime.snapshot().editor().orElseThrow().variantAvailable(SkinVariant.SLIM));
        assertEquals(1, fixture.adapter.conversions, "Replacement must not reconvert");
        assertTrue(scope.stillCurrent().getAsBoolean(), "Edit lifetime is not retained-model lifetime");
        var original = fixture.operations.session;
        fixture.operations.session = new SessionValidation(SessionStatus.OFFLINE_OR_INVALID,
                new GameSessionTokenSource.SessionIdentity(UUID.randomUUID(), "Other"), null, (SessionFailureContext) null, "synthetic");
        fixture.runtime.tick(); fixture.operations.session = original; fixture.runtime.tick();
        assertFalse(scope.stillCurrent().getAsBoolean());
        assertTrue(fixture.runtime.snapshot().editor().isEmpty());
        fixture.runtime.close();
    }

    @Test
    void cpmBaseConvertedTransferRejectsMissingInitiatingScope(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory); fixture.openConverted();
        var draft = fixture.runtime.snapshot().editor().orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> new EditorDraftTransfer(draft, null, Optional.of(com.naocraftlab.skins.core.model.PersonalSkinSource.FILE), null),
                "Converted continuation cannot take the ordinary unscoped transfer path");
        fixture.runtime.close();
    }

    @Test
    void cpmBaseConvertedAlternateHasNeutralUnavailableReason(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory); fixture.openConverted();
        var draft = fixture.runtime.snapshot().editor().orElseThrow();
        var reason = draft.variantUnavailableReason(SkinVariant.SLIM);
        assertEquals(Optional.of(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.VARIANT_UNSUPPORTED), reason,
                "Unsupported converted alternate has an owner-projected neutral reason, not invented UI state");
        fixture.runtime.close();
    }

    @Test
    void cpmBaseReadyBytesAndEditScopeSurviveWorldAndResourceChanges(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory); fixture.openConverted();
        fixture.operations.failEditorSave = true; fixture.runtime.dispatchWidget("editor.save");
        var scope = fixture.operations.lastEditorSaveRequest.saveScope().orElseThrow();
        byte[] before = fixture.runtime.snapshot().editor().orElseThrow().png().orElseThrow().bytes();
        fixture.runtime.resourcesReloaded();
        var host = new ClientApplicationHost<Object>(new DefaultRuntimeServices(), fixture.runtime, () -> {});
        Object connection = new Object();
        host.tick(connection, new Object(), true); host.tick(connection, new Object(), true);
        assertArrayEquals(before, fixture.runtime.snapshot().editor().orElseThrow().png().orElseThrow().bytes());
        assertEquals(SkinVariant.CLASSIC, fixture.runtime.snapshot().editor().orElseThrow().variant());
        assertTrue(scope.stillCurrent().getAsBoolean());
        assertEquals(1, fixture.adapter.conversions);
        assertEquals(0, fixture.adapter.closes);
        fixture.runtime.dispatchWidget("editor.cancel");
        assertFalse(scope.stillCurrent().getAsBoolean());
        assertTrue(fixture.runtime.snapshot().addSource().orElseThrow().importContext().filterLocked());
        assertEquals(AddSourceTab.FILE, fixture.runtime.snapshot().addSource().orElseThrow().selectedTab());
        host.close();
    }

    @Test
    void cpmBaseReadOnlyScopeHasWorkerVisibilityAndFreshSessionCheck(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory); fixture.openConverted();
        fixture.operations.failEditorSave = true; fixture.runtime.dispatchWidget("editor.save");
        var scope = fixture.operations.lastEditorSaveRequest.saveScope().orElseThrow();
        var ready = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var observed = new CompletableFuture<List<Boolean>>();
        Thread worker = new Thread(() -> {
            boolean active = scope.stillCurrent().getAsBoolean(); ready.countDown();
            try { release.await(); observed.complete(List.of(active, scope.stillCurrent().getAsBoolean())); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); observed.completeExceptionally(failure); }
        }, "synthetic-edit-scope-reader");
        worker.start(); assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
        var original = fixture.operations.session;
        fixture.operations.session = new SessionValidation(SessionStatus.OFFLINE_OR_INVALID,
                new GameSessionTokenSource.SessionIdentity(UUID.randomUUID(), "Other"), null, (SessionFailureContext) null, "synthetic");
        assertFalse(scope.stillCurrent().getAsBoolean(), "Read-only scope observes fresh neutral identity without a tick; not a storage gate");
        fixture.operations.session = original;
        fixture.runtime.dispatchWidget("editor.cancel");
        release.countDown(); worker.join(5000); assertFalse(worker.isAlive());
        assertEquals(List.of(true, false), observed.get(5, java.util.concurrent.TimeUnit.SECONDS));
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
        fixture.runtime.dispatchWidget("add.file.choose");
        fixture.runtime.dispatchWidget("editor.save");
        var next = fixture.operations.lastEditorSaveRequest.saveScope().orElseThrow();
        assertTrue(next.stillCurrent().getAsBoolean());
        assertFalse(scope.stillCurrent().getAsBoolean(), "A later edit cannot revive an old scope");
        fixture.runtime.close(); assertFalse(next.stillCurrent().getAsBoolean());
    }

    @Test
    void cpmBaseFreshAccountBoundaryBeforeReplacementRetiresDraftWithoutPicker(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory); fixture.openConverted();
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
        fixture.operations.session = new SessionValidation(SessionStatus.OFFLINE_OR_INVALID,
                new GameSessionTokenSource.SessionIdentity(UUID.randomUUID(), "Other"), null, (SessionFailureContext) null, "synthetic");
        fixture.runtime.dispatchWidget("editor.png.choose");
        assertEquals(1, fixture.picks.size(), "Stale initiating account must be observed before a new picker effect");
        assertTrue(fixture.runtime.snapshot().editor().isEmpty());
        assertFalse(fixture.runtime.snapshot().busy()); fixture.runtime.close();
    }

    @Test
    void cpmBaseDelayedClientConversionAndPublicationCannotUnlockNewOperation(@TempDir Path directory) throws Exception {
        for (boolean encoded : List.of(false, true)) {
            var fixture = new CpmLifecycleFixture(directory); fixture.openConverted();
            fixture.runtime.dispatchWidget("editor.cancel"); fixture.hold = true;
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
            fixture.runtime.dispatchWidget("add.file.choose");
            if (encoded) {
                fixture.holdClient = true;
                fixture.workers.remove().run();
                assertFalse(fixture.clients.isEmpty(), "Valid converted/encoded result waits on the real client executor");
            } else assertFalse(fixture.workers.isEmpty(), "Native preparation has not started");
            assertTrue(fixture.runtime.snapshot().busy());
            fixture.runtime.resourcesReloaded();
            assertFalse(fixture.runtime.snapshot().busy());
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
            fixture.runtime.dispatchWidget("add.file.choose");
            assertTrue(fixture.runtime.snapshot().busy());
            if (!encoded) {
                fixture.holdClient = true;
                fixture.workers.remove().run();
            }
            fixture.clients.remove().run();
            assertTrue(fixture.runtime.snapshot().busy(), "Old completion cannot unlock the newer base operation");
            assertTrue(fixture.runtime.snapshot().editor().isEmpty());
            fixture.holdClient = false; fixture.drain();
            assertTrue(fixture.runtime.snapshot().editor().isPresent(), "Current-scope explicit selection still publishes");
            assertFalse(fixture.runtime.snapshot().busy());
            assertEquals(encoded ? 3 : 2, fixture.adapter.conversions);
            fixture.runtime.close();
        }
    }

    @Test
    void cpmChooserImportWheelConsumesPresentedSurface(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory);
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
        fixture.runtime.dispatchWidget("add.file.choose");
        assertTrue(fixture.runtime.snapshot().editor().isEmpty());
        assertTrue(fixture.runtime.snapshot().addSource().orElseThrow().importContext().filterLocked());
        fixture.runtime.dispatchWidget("add.tab.file");
        var view = fixture.runtime.view(240, 240, 0, 0);
        var surface = view.scrollSurface("add.import").orElseThrow();
        assertTrue(surface.maximumPixels() > 16, "Valid scoped Import has overflow");
        assertEquals(0, surface.offsetPixels());
        fixture.runtime.pointerScrolled(surface.viewport().x() + 2, surface.viewport().y() + 2, 0, -0.5);
        assertEquals(16, fixture.runtime.snapshot().addSource().orElseThrow().scrollOffset(),
                "Scoped Import wheel must use its presented surface, not ordinary Catalog clip");
        assertEquals(16, fixture.runtime.view(240, 240, 0, 0).scrollSurface("add.import").orElseThrow().offsetPixels());
        fixture.runtime.close();
    }

    @Test
    void cpmChooserNativeScrollUsesCurrentStatusAndRejectsOtherTab(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory);
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
        fixture.runtime.dispatchWidget("add.file.choose");
        fixture.runtime.dispatchWidget("add.tab.file");
        Path invalid = Files.write(directory.resolve("invalid.png"), new byte[] {1});
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(invalid)));
        fixture.runtime.dispatchWidget("add.file.choose");
        assertEquals("nclskins.error.png", fixture.runtime.snapshot().status().key());
        assertTrue(fixture.runtime.snapshot().addSource().orElseThrow().importContext().failure().isEmpty());
        var view = fixture.runtime.view(180, 180, 0, 0);
        var surface = view.scrollSurface("add.import").orElseThrow();
        assertTrue(surface.maximumPixels() > 0);
        fixture.runtime.nativeScrollPositionChanged("add.catalog", 99);
        assertEquals(0, fixture.runtime.snapshot().addSource().orElseThrow().scrollOffset());
        fixture.runtime.nativeScrollPositionChanged("add.import", 100000);
        assertEquals((int) surface.maximumPixels(), fixture.runtime.snapshot().addSource().orElseThrow().scrollOffset(),
                "Native Import uses CURRENT supplied error geometry");
        fixture.runtime.tick();
        assertEquals((int) surface.maximumPixels(), fixture.runtime.snapshot().addSource().orElseThrow().scrollOffset());
        assertEquals("nclskins.error.png", fixture.runtime.snapshot().status().key());
        fixture.runtime.close();
    }

    @Test
    void cpmChooserKeyboardReachesBlockedSourceTooltipWithoutImporting(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory);
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
        fixture.runtime.dispatchWidget("add.file.choose");
        fixture.runtime.dispatchWidget("add.tab.file");
        var before = fixture.runtime.view(240, 240, 0, 0);
        assertTrue(before.scrollSurface("add.import").orElseThrow().maximumPixels() > 0);
        assertTrue(fixture.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, "add.file.choose"));
        var after = fixture.runtime.view(240, 240, 0, 0);
        assertEquals("add.external.launcher", after.focusRequest().orElseThrow().widgetId());
        var node = after.navigationNode("add.external.launcher").orElseThrow();
        var surface = after.scrollSurface("add.import").orElseThrow();
        assertTrue(node.bounds().y() >= surface.viewport().y());
        assertTrue(node.bounds().bottom() <= surface.viewport().bottom(), "Keyboard focus keeps the source tooltip anchor visible without a reserved reason row");
        assertTrue(node.bounds().bottom() <= surface.viewport().bottom());
        assertFalse(fixture.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, node.id()));
        assertEquals(0, fixture.adapter.conversions);
        assertNull(fixture.operations.lastExternalSource);
        fixture.runtime.close();
    }

    @Test
    void cpmChooserScrollbarDragMatchesPresentedImportGeometry(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory);
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
        fixture.runtime.dispatchWidget("add.file.choose");
        fixture.runtime.dispatchWidget("add.tab.file");
        for (int[] size : List.of(new int[] {240,240}, new int[] {180,180}, new int[] {480,160})) {
            var view = fixture.runtime.view(size[0], size[1], 0, 0);
            var surface = view.scrollSurface("add.import").orElseThrow();
            var bar = view.scrollbar().orElseThrow();
            assertTrue(surface.maximumPixels() > 0);
            fixture.runtime.pointerPressed(bar.thumb().x()+1, bar.thumb().y()+1, 0);
            fixture.runtime.pointerDragged(bar.thumb().x()+1, bar.track().bottom()+200, 0, 0, 200);
            assertEquals((int) surface.maximumPixels(), fixture.runtime.snapshot().addSource().orElseThrow().scrollOffset(),
                    "Drag reaches presented Import maximum");
            fixture.runtime.pointerDragged(bar.thumb().x()+1, bar.track().y()-200, 0, 0, -400);
            assertEquals(0, fixture.runtime.snapshot().addSource().orElseThrow().scrollOffset());
            fixture.runtime.pointerReleased(0);
        }
        fixture.runtime.close();
    }

    @Test
    void cpmChooserDragUsesUntypedErrorPublishedDuringGesture(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory);
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
        fixture.runtime.dispatchWidget("add.file.choose");
        fixture.runtime.dispatchWidget("add.tab.file");
        var initial = fixture.runtime.view(180, 180, 0, 0).scrollbar().orElseThrow();
        fixture.runtime.pointerPressed(initial.thumb().x()+1, initial.thumb().y()+1, 0);
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(
                Files.write(directory.resolve("bad.png"), new byte[]{1}))));
        fixture.runtime.dispatchWidget("add.file.choose");
        assertEquals("nclskins.error.png", fixture.runtime.snapshot().status().key());
        var current = fixture.runtime.view(180, 180, 0, 0);
        var bar = current.scrollbar().orElseThrow();
        var surface = current.scrollSurface("add.import").orElseThrow();
        assertTrue(bar.maximum() > initial.maximum(), "Current error adds genuine overflow");
        fixture.runtime.pointerDragged(bar.thumb().x()+1, bar.track().bottom()+100, 0, 0, 200);
        assertEquals((int) surface.maximumPixels(), fixture.runtime.snapshot().addSource().orElseThrow().scrollOffset(),
                "Held gesture must map CURRENT error track/thumb/maximum");
        fixture.runtime.pointerReleased(0);
        fixture.runtime.close();
    }

    @Test
    void cpmChooserReturnRestoresIndependentParentAndChooserFocus(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory);
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
        var parentFocus = fixture.runtime.view(240, 240, 0, 0).focusRequest();
        assertTrue(parentFocus.isEmpty(), "Ordinary direct Import starts without a synthetic request");
        fixture.runtime.dispatchWidget("add.file.choose");
        fixture.runtime.dispatchWidget("add.tab.file");
        fixture.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, "add.file.choose");
        var chooser = fixture.runtime.snapshot().addSource().orElseThrow();
        assertEquals("add.external.launcher", fixture.runtime.view(240,240,0,0).focusRequest().orElseThrow().widgetId());
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
        fixture.runtime.dispatchWidget("add.file.choose");
        assertTrue(fixture.runtime.snapshot().editor().isPresent());
        fixture.runtime.dispatchWidget("editor.cancel");
        var returned = fixture.runtime.view(240,240,0,0);
        assertEquals(Optional.of("add.external.launcher"), returned.focusRequest().map(ViewSpec.FocusRequest::widgetId),
                "Editor Cancel restores the chooser's actual keyboard focus");
        assertEquals(chooser.scrollOffset(), fixture.runtime.snapshot().addSource().orElseThrow().scrollOffset());
        fixture.runtime.dispatchWidget("add.cancel");
        assertEquals(AddSourceModel.Purpose.CREATE_PRESET, fixture.runtime.snapshot().addSource().orElseThrow().importContext().purpose());
        assertEquals(parentFocus, fixture.runtime.view(240,240,0,0).focusRequest(),
                "Chooser focus must not overwrite the captured ordinary parent focus");
        fixture.runtime.close();
    }

    @Test
    void cpmChooserImportResizeClampsSingleOffsetOnTick(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory);
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
        fixture.runtime.dispatchWidget("add.file.choose");
        fixture.runtime.dispatchWidget("add.tab.file");
        var small = fixture.runtime.view(180,180,0,0).scrollSurface("add.import").orElseThrow();
        fixture.runtime.nativeScrollPositionChanged("add.import", small.maximumPixels());
        assertTrue(fixture.runtime.snapshot().addSource().orElseThrow().scrollOffset() > 0);
        var enlarged = fixture.runtime.view(854,480,0,0).scrollSurface("add.import").orElseThrow();
        assertEquals(0, enlarged.maximumPixels());
        fixture.runtime.tick();
        assertEquals(0, fixture.runtime.snapshot().addSource().orElseThrow().scrollOffset(),
                "Scoped Import resize clamps the real offset owner, not only the painted surface");
        fixture.runtime.close();
    }

    @Test
    void cpmChooserBothTabsUseOnePresentedRuntimeGeometry(@TempDir Path directory) throws Exception {
        for (var required : com.naocraftlab.skins.client.SkinModel.values()) {
            Path model = Files.write(directory.resolve(required + ".cpmmodel"), validModel(required == SkinModel.SLIM));
            Path mismatch = Files.write(directory.resolve(required + "-wrong.png"),
                    opaqueSkinPng(required == com.naocraftlab.skins.client.SkinModel.CLASSIC));
            var operations = new FakeOperations();
            operations.uiPreferences = AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID)
                    .withPreferredSkinVariant(required == com.naocraftlab.skins.client.SkinModel.CLASSIC ? SkinVariant.SLIM : SkinVariant.CLASSIC);
            var picks = new java.util.ArrayDeque<Path>(); picks.add(model); picks.add(mismatch);
            var runtime = runtime(operations, () -> CompletableFuture.completedFuture(Optional.of(picks.remove())));
            var adapter = new BaseOnlyConverter(required); useBaseConverter(runtime, adapter);
            runtime.reopen(ScreenDestination.SKIN_IMPORT); runtime.dispatchWidget("add.file.choose");
            var preferences = operations.uiPreferences;
            var first = runtime.view(240,240,0,0);
            assertEquals(required.name(), runtime.snapshot().addSource().orElseThrow().filter().name());
            assertFalse(first.widget("add.catalog.filter").orElseThrow().enabled());
            for (var origin : InteractionOrigin.values()) {
                runtime.dispatchWidget("add.catalog.filter", false, origin);
                runtime.dispatchWidget("add.catalog.filter", true, origin);
                assertEquals(required.name(), runtime.snapshot().addSource().orElseThrow().filter().name());
            }
            runtime.dispatchWidget("add.tab.file"); runtime.dispatchWidget("add.file.choose");
            assertEquals(0, adapter.conversions);
            for (var tab : AddSourceTab.values()) {
                runtime.dispatchWidget(tab == AddSourceTab.FILE ? "add.tab.file" : "add.tab.catalog");
                String surfaceId = tab == AddSourceTab.FILE ? "add.import" : "add.catalog";
                String statusId = tab == AddSourceTab.FILE ? "add.import.status" : "add.catalog.status";
                for (int[] size : List.of(new int[]{240,240},new int[]{180,180},new int[]{480,160},new int[]{427,240})) {
                    if (tab == AddSourceTab.FILE) {
                        picks.add(mismatch);
                        runtime.dispatchWidget("add.file.choose");
                    } else {
                        adapter.failure = com.naocraftlab.skins.client.CpmModelFileConversion.Reason.CONVERSION_FAILED;
                        runtime.dispatchWidget("add.catalog.skin:minecraft:steve");
                        adapter.failure = null;
                    }
                    var view = runtime.view(size[0],size[1],0,0);
                    var surface = view.scrollSurface(surfaceId).orElseThrow();
                    var title = view.texts().stream().filter(t -> t.id().equals("add.context.title")).findFirst().orElseThrow();
                    assertTrue(title.bounds().bottom() <= surface.viewport().y());
                    assertEquals(tab == AddSourceTab.FILE ? "nclskins.add_source.cpm.required_"+required.name().toLowerCase(java.util.Locale.ROOT)
                                    : "nclskins.add_source.cpm.conversion_failed",
                            view.texts().stream().filter(text -> text.id().equals(statusId)).findFirst().orElseThrow().message().key());
                    assertEquals(tab == AddSourceTab.CATALOG, view.widget(statusId+".info").isPresent());
                    assertEquals(tab == AddSourceTab.CATALOG, view.navigationNode(statusId+".info").isPresent());
                    runtime.nativeScrollPositionChanged(surfaceId, 0);
                    runtime.pointerScrolled(surface.viewport().x()+2, surface.viewport().y()+2, 0, -0.5);
                    assertEquals((int)Math.min(16,surface.maximumPixels()), runtime.snapshot().addSource().orElseThrow().scrollOffset());
                    int beforeOutside = runtime.snapshot().addSource().orElseThrow().scrollOffset();
                    runtime.pointerScrolled(2, size[1]-2, 0, -10);
                    assertEquals(beforeOutside, runtime.snapshot().addSource().orElseThrow().scrollOffset());
                    runtime.nativeScrollPositionChanged(surfaceId, 100000);
                    var end = runtime.view(size[0],size[1],0,0);
                    var status = end.texts().stream().filter(t -> t.id().equals(statusId)).findFirst().orElseThrow();
                    assertTrue(status.bounds().bottom() <= surface.viewport().bottom());
                    assertEquals(surface.viewport(), end.clipFor(statusId).orElseThrow());
                    assertTrue(end.clipFor("add.cancel").isEmpty());
                    assertFalse(ViewHostPolicy.pointerInsideClip(end, statusId+".info", 2, size[1]-2));
                    end.scrollbar().ifPresent(bar -> {
                        runtime.pointerPressed(bar.thumb().x()+1,bar.thumb().y()+1,0);
                        var current = runtime.view(size[0],size[1],0,0).scrollSurface(surfaceId).orElseThrow();
                        assertTrue(runtime.snapshot().addSource().orElseThrow().importContext().failure().isEmpty());
                        runtime.pointerDragged(bar.thumb().x()+1,bar.track().y()-100,0,0,-200);
                        assertEquals(0,runtime.snapshot().addSource().orElseThrow().scrollOffset());
                        runtime.pointerDragged(bar.thumb().x()+1,bar.track().bottom()+100,0,0,400);
                        assertEquals((int)current.maximumPixels(),runtime.snapshot().addSource().orElseThrow().scrollOffset());
                        runtime.pointerReleased(0);
                    });
                    assertFalse(runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, statusId+".info"));
                    runtime.dispatchWidget(statusId+".info");
                    assertTrue(runtime.snapshot().editor().isEmpty());
                    assertEquals(0, adapter.conversions);
                }
            }
            assertEquals(preferences, operations.uiPreferences);
            runtime.close();
        }
    }

    @Test
    void cpmChooserConvertedDraftUsesPendingNameOrSafeModelFilename(@TempDir Path directory) throws Exception {
        for (boolean pending : List.of(false,true)) {
            var fixture = new CpmLifecycleFixture(directory);
            Path named = Files.write(directory.resolve("snake_model.CPMMODEL"), validModel(false));
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(named)));
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
            if (pending) {
                fixture.runtime.initialize();
                fixture.runtime.dispatchText("gallery.search", "Pending creation");
                fixture.runtime.dispatchWidget("gallery.add");
                fixture.runtime.dispatchWidget("add.tab.file");
            } else fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
            fixture.runtime.dispatchWidget("add.file.choose");
            fixture.runtime.dispatchWidget("add.tab.file"); fixture.runtime.dispatchWidget("add.file.choose");
            var editor = fixture.runtime.snapshot().editor().orElseThrow();
            assertEquals(pending ? "Pending creation" : "Snake Model", editor.name());
            assertEquals("Snake Model.png", editor.png().orElseThrow().fileName());
            assertEquals(EditorTab.APPEARANCE, editor.selectedEditorTab());
            assertTrue(editor.originalPresetId().isEmpty()); assertTrue(editor.catalogOrigin().isEmpty());
            assertEquals(SkinVariant.CLASSIC, editor.variant());
            assertEquals(PersonalSkinSource.FILE, editor.saveRequest().personalSkinSource());
            var view = fixture.runtime.view(240,240,0,0);
            var request = view.focusRequest().orElseThrow();
            assertEquals("editor.name", request.widgetId());
            fixture.runtime.acknowledgeFocusApplied(view.screenId(), request);
            assertTrue(fixture.runtime.view(240,240,0,0).focusRequest().isEmpty());
            assertArrayEquals(editor.png().orElseThrow().bytes(), fixture.runtime.loadSkinPreview(view.previews().get(0)).join().orElseThrow());
            fixture.runtime.close();
        }
    }

    @Test
    void cpmChooserBusyCancelKeepsActualNativeCoordinatorGuard(@TempDir Path directory) throws Exception {
        Path model = Files.write(directory.resolve("dialog.cpmmodel"), validModel(false));
        var coordinator = new FilePickerCoordinator(Runnable::run);
        var pending = new CompletableFuture<Optional<Path>>();
        int[] opened = {0};
        FilePicker picker = new FilePicker() {
            public CompletableFuture<Optional<Path>> chooseSkinPng() {
                return coordinator.chooseAsync(() -> { opened[0]++; return pending; }, Runnable::run);
            }
            public CompletableFuture<Optional<Path>> chooseSkinImportFile(boolean allowCpm) {
                return coordinator.chooseSkinImportAsync(() -> { opened[0]++; return CompletableFuture.completedFuture(Optional.of(model)); }, Runnable::run);
            }
        };
        var runtime = runtime(new FakeOperations(), picker);
        useBaseConverter(runtime,new BaseOnlyConverter(com.naocraftlab.skins.client.SkinModel.CLASSIC));
        runtime.reopen(ScreenDestination.SKIN_IMPORT); runtime.dispatchWidget("add.file.choose");
        runtime.dispatchWidget("add.tab.file"); runtime.dispatchWidget("add.file.choose");
        assertTrue(runtime.snapshot().busy()); assertEquals(2,opened[0]);
        assertTrue(runtime.view(240,240,0,0).widget("add.cancel").orElseThrow().enabled());
        runtime.dispatchWidget("add.cancel");
        assertFalse(runtime.snapshot().busy()); assertFalse(pending.isDone());
        runtime.dispatchWidget("add.file.choose");
        assertEquals(2,opened[0], "UI Cancel does not release the still-open native dialog");
        assertFalse(runtime.snapshot().busy());
        pending.complete(Optional.empty());
        assertTrue(runtime.snapshot().editor().isEmpty());
        runtime.dispatchWidget("add.file.choose");
        assertEquals(3,opened[0], "Actual callback releases guard for the next explicit dialog");
        assertTrue(runtime.snapshot().addSource().orElseThrow().importContext().filterLocked());
        runtime.close();
    }

    @Test
    void cpmChooserCatalogFailureAndPendingCancelUseCurrentView(@TempDir Path directory) throws Exception {
        for (boolean cancelPending : List.of(false,true)) {
            var fixture = new CpmLifecycleFixture(directory);
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
            fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT); fixture.runtime.dispatchWidget("add.file.choose");
            var view = fixture.runtime.view(240,240,0,0);
            assertTrue(view.navigationNode("add.catalog.skin:minecraft:steve").isPresent());
            var accountBefore = fixture.runtime.snapshot().account();
            if (cancelPending) fixture.hold = true;
            else fixture.operations.failedCatalogModels = Set.of(SkinModel.CLASSIC);
            fixture.runtime.dispatchWidget("add.catalog.skin:minecraft:steve");
            if (cancelPending) {
                assertTrue(fixture.runtime.snapshot().busy());
                assertFalse(fixture.workers.isEmpty());
                assertTrue(fixture.runtime.view(240,240,0,0).widget("add.cancel").orElseThrow().enabled());
                fixture.runtime.dispatchWidget("add.cancel");
                assertFalse(fixture.runtime.snapshot().busy());
                fixture.drain();
                assertEquals(AddSourceModel.Purpose.CREATE_PRESET, fixture.runtime.snapshot().addSource().orElseThrow().importContext().purpose());
            } else {
                assertFalse(fixture.runtime.snapshot().busy());
                var error = fixture.runtime.snapshot().status();
                assertEquals(UiMessage.Severity.ERROR,error.severity());
                var failed = fixture.runtime.view(180,180,0,0);
                var surface = failed.scrollSurface("add.catalog").orElseThrow();
                fixture.runtime.nativeScrollPositionChanged("add.catalog",100000);
                var end = fixture.runtime.view(180,180,0,0);
                var text = end.texts().stream().filter(t -> t.id().equals("add.catalog.status")).findFirst().orElseThrow();
                assertEquals(error,text.message());
                assertTrue(text.bounds().bottom() <= surface.viewport().bottom());
                assertEquals((int)surface.maximumPixels(), fixture.runtime.snapshot().addSource().orElseThrow().scrollOffset());
                assertTrue(fixture.runtime.snapshot().addSource().orElseThrow().importContext().filterLocked());
            }
            assertEquals(0,fixture.adapter.conversions);
            assertTrue(fixture.runtime.snapshot().editor().isEmpty());
            assertEquals(accountBefore,fixture.runtime.snapshot().account());
            fixture.runtime.close();
        }
    }

    @Test
    void cpmChooserWheelStartsAtPresentedOffsetAfterTabChange(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory);
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT); fixture.runtime.dispatchWidget("add.file.choose");
        var catalog = fixture.runtime.view(180,180,0,0).scrollSurface("add.catalog").orElseThrow();
        fixture.runtime.nativeScrollPositionChanged("add.catalog",catalog.maximumPixels());
        fixture.runtime.dispatchWidget("add.tab.file");
        var surface = fixture.runtime.view(180,180,0,0).scrollSurface("add.import").orElseThrow();
        assertTrue(catalog.maximumPixels()>surface.maximumPixels());
        assertTrue(surface.offsetPixels()>16);
        fixture.runtime.pointerScrolled(surface.viewport().x()+2,surface.viewport().y()+2,0,0.5);
        assertEquals((int)surface.offsetPixels()-16,fixture.runtime.snapshot().addSource().orElseThrow().scrollOffset(),
                "Wheel starts at current presented offset, not the stale larger Catalog driver");
        fixture.runtime.close();
    }

    @Test
    void finalCorrectionRetiredOuterPickerCannotCancelGalleryDelete(@TempDir Path directory) throws Exception {
        for (int terminal = 0; terminal < 4; terminal++) {
            var fixture = new CpmLifecycleFixture(directory);
            fixture.operations.account = TestFixtures.account(1);
            fixture.runtime.initialize();
            UUID preset = fixture.operations.account.presets().get(0).id();
            if (terminal != 0) {
                fixture.runtime.dispatchWidget("gallery.add");
                fixture.runtime.dispatchWidget("add.tab.file");
                fixture.picks.add(terminal == 1 ? CompletableFuture.completedFuture(Optional.empty())
                        : terminal == 2 ? CompletableFuture.failedFuture(new IOException("synthetic picker failure"))
                        : CompletableFuture.completedFuture(Optional.of(Files.write(directory.resolve("bad.png"), new byte[]{1}))));
                fixture.runtime.dispatchWidget("add.file.choose");
                assertFalse(fixture.runtime.snapshot().busy());
                fixture.runtime.dispatchWidget("add.cancel");
                assertTrue(fixture.runtime.snapshot().addSource().isEmpty());
            }
            fixture.hold = true;
            fixture.runtime.dispatchWidget("gallery.preset." + preset + ".delete");
            fixture.runtime.dispatchWidget("gallery.preset." + preset + ".delete_confirm");
            assertTrue(fixture.runtime.snapshot().busy(), "Confirmed Gallery delete is genuinely pending");
            fixture.runtime.resourcesReloaded();
            assertTrue(fixture.runtime.snapshot().busy(), "Retired import cannot unlock unrelated Gallery mutation");
            fixture.drain();
            assertFalse(fixture.runtime.snapshot().busy());
            assertTrue(fixture.runtime.snapshot().account().orElseThrow().presets().stream().noneMatch(p -> p.id().equals(preset)),
                    "Actual Gallery completion must publish its changed account");
            assertEquals("nclskins.status.deleted", fixture.runtime.snapshot().status().key());
            fixture.runtime.close();
        }
    }

    @Test
    void finalCorrectionManagementRetainsOwnerAcrossCancelAndReload(@TempDir Path directory) throws Exception {
        for (boolean rename : List.of(false, true)) for (int attempt = 0; attempt < 5; attempt++) {
            var fixture = new CpmLifecycleFixture(directory);
            String hash = fixture.operations.seedPersonalSkin("Before");
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
            fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
            fixture.runtime.dispatchWidget("add.file.choose");
            String prefix = rename ? "add.catalog.rename:" : "add.catalog.delete:";
            fixture.runtime.dispatchWidget(prefix + PersonalSkinCatalog.COLLECTION_ID + ":" + hash);
            if (rename) fixture.runtime.dispatchText("add.catalog.rename.name", "After");
            fixture.hold = true;
            fixture.runtime.dispatchWidget(rename ? "add.catalog.rename.save" : "add.catalog.delete.confirm");
            assertTrue(fixture.runtime.snapshot().busy(), "Actual confirmed management worker is held");
            var pending = fixture.runtime.view(854,480,0,0);
            assertFalse(pending.widget("add.cancel").orElseThrow().enabled(), "Footer Cancel must respect durable management phase");
            if (attempt == 0) fixture.runtime.dispatchWidget("add.cancel");
            else if (attempt == 1) fixture.runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE,"add.cancel");
            else if (attempt == 2) {
                var b = pending.widget("add.cancel").orElseThrow().bounds();
                fixture.runtime.pointerPressed(b.x()+1,b.y()+1,0);
            } else if (attempt == 3) fixture.runtime.resourcesReloaded();
            else fixture.runtime.importWorldChanged();
            assertTrue(fixture.runtime.snapshot().busy(), "Cancel/reload/world cannot retire management completion");
            assertEquals(AddSourceModel.Purpose.CPM_BASE,fixture.runtime.snapshot().addSource().orElseThrow().importContext().purpose());
            fixture.drain();
            assertFalse(fixture.runtime.snapshot().busy());
            var entry = fixture.runtime.snapshot().account().orElseThrow().personalSkins().stream().filter(s -> s.sha256().equals(hash)).findFirst().orElseThrow();
            if (rename) assertEquals("After",entry.displayName()); else assertFalse(entry.visible());
            fixture.runtime.dispatchWidget("add.cancel");
            var parent = fixture.runtime.snapshot().addSource().orElseThrow();
            assertEquals(AddSourceModel.Purpose.CREATE_PRESET,parent.importContext().purpose());
            if (!rename) assertTrue(parent.collections().stream().noneMatch(c -> PersonalSkinCatalog.isCollection(c.id())),"Removed card must not resurrect in parent");
            else assertTrue(parent.collections().stream().flatMap(c -> c.skins().stream()).anyMatch(s -> s.name().toString().contains("After")));
            fixture.runtime.close();
        }
    }

    @Test
    void finalCorrectionTypedFeedbackDismissesWithoutLosingModel(@TempDir Path directory) throws Exception {
        for (int interaction = 0; interaction < 3; interaction++) {
            var fixture = new CpmLifecycleFixture(directory);
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
            fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT); fixture.runtime.dispatchWidget("add.file.choose");
            fixture.runtime.dispatchWidget("add.tab.file");
            Path slim = Files.write(directory.resolve("mismatch.png"),opaqueSkinPng(true));
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(slim)));
            fixture.runtime.dispatchWidget("add.file.choose");
            var before = fixture.runtime.snapshot().addSource().orElseThrow();
            assertEquals(Optional.of(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.VARIANT_UNSUPPORTED),before.importContext().failure());
            assertTrue(fixture.runtime.view(240,240,0,0).texts().stream().anyMatch(t -> t.id().equals("add.import.status")));
            if (interaction == 0) fixture.runtime.dispatchText("add.player.input","Synthetic");
            else if (interaction == 1) fixture.runtime.pointerPressed(239,239,0);
            else fixture.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD,"add.player.input");
            var after = fixture.runtime.snapshot().addSource().orElseThrow();
            assertTrue(after.importContext().failure().isEmpty(),"Next actual interaction dismisses transient typed feedback");
            assertTrue(after.importContext().filterLocked());
            assertEquals(before.importContext().requiredVariant(),after.importContext().requiredVariant());
            assertEquals(0,fixture.adapter.closes);
            assertTrue(fixture.runtime.view(240,240,0,0).texts().stream().noneMatch(t -> t.id().equals("add.import.status")));
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(slim)));
            fixture.runtime.dispatchWidget("add.file.choose");
            assertEquals(Optional.of(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.VARIANT_UNSUPPORTED),fixture.runtime.snapshot().addSource().orElseThrow().importContext().failure());
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
            fixture.runtime.dispatchWidget("add.file.choose");
            assertTrue(fixture.runtime.snapshot().editor().isPresent(),"Same-type explicit Retry still converts");
            fixture.runtime.close();
        }
    }

    @Test
    void ownedPreviewRequestCannotBorrowAReplacementDraft(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory);
        fixture.openConverted();
        var original = fixture.runtime.view(320, 240, 0, 0).previews().get(0);
        byte[] converted = fixture.runtime.snapshot().editor().orElseThrow().saveRequest().pngBytes().orElseThrow();
        assertTrue(original.requiresLoadedSkin());
        assertArrayEquals(converted, fixture.runtime.loadSkinPreview(original).join().orElseThrow());
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
        fixture.runtime.dispatchWidget("editor.png.choose");
        var replacement = fixture.runtime.view(320, 240, 0, 0).previews().get(0);
        byte[] replacementBytes = fixture.runtime.snapshot().editor().orElseThrow().saveRequest().pngBytes().orElseThrow();
        assertFalse(java.util.Arrays.equals(converted, replacementBytes));
        assertNotEquals(original.imageRevision(), replacement.imageRevision());
        assertTrue(fixture.runtime.loadSkinPreview(original).join().isEmpty(),
                "Retired owned revision must not return the next draft under the old texture key");
        assertArrayEquals(replacementBytes, fixture.runtime.loadSkinPreview(replacement).join().orElseThrow());
        fixture.runtime.resourcesReloaded();
        fixture.runtime.importWorldChanged();
        assertArrayEquals(replacementBytes, fixture.runtime.loadSkinPreview(replacement).join().orElseThrow());
        fixture.runtime.dispatchWidget("editor.cancel");
        assertTrue(fixture.runtime.loadSkinPreview(replacement).join().isEmpty());
        fixture.runtime.close();
        assertTrue(fixture.runtime.loadSkinPreview(replacement).join().isEmpty());
    }

    @Test
    void finalCorrectionCapeCatalogSurvivesReplacementAndVariantOrdering(@TempDir Path directory) throws Exception {
        for (boolean ordinary : List.of(false,true)) for (boolean catalogFirst : List.of(true,false)) {
            var fixture = new CpmLifecycleFixture(directory);
            if (ordinary) {
                fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
                fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT); fixture.runtime.dispatchWidget("add.file.choose");
                assertTrue(fixture.runtime.snapshot().editor().orElseThrow().variantAvailable(SkinVariant.SLIM));
            } else fixture.openConverted();
            fixture.operations.resourceCapeCollections = List.of(resourceCapeCollection());
            fixture.operations.resourceCapeHashes = Map.of(new ClientOperations.ResourceCapeKey("event","hero"),"c".repeat(64));
            fixture.hold = true; fixture.runtime.resourcesReloaded();
            assertFalse(fixture.workers.isEmpty()); fixture.workers.remove().run();
            assertFalse(fixture.workers.isEmpty(),"Real resource catalog producer is pending");
            assertTrue(fixture.runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream().noneMatch(c -> c.resource()!=null));
            if (catalogFirst) fixture.workers.remove().run();
            if (ordinary) {
                fixture.runtime.dispatchWidget("editor.model_choice.slim");
                assertEquals(SkinVariant.SLIM,fixture.runtime.snapshot().editor().orElseThrow().variant());
            } else {
                byte[] before = fixture.runtime.snapshot().editor().orElseThrow().png().orElseThrow().bytes();
                fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
                fixture.runtime.dispatchWidget("editor.png.choose");
                fixture.workers.removeLast().run();
                assertFalse(java.util.Arrays.equals(before,fixture.runtime.snapshot().editor().orElseThrow().png().orElseThrow().bytes()));
                assertEquals(0,fixture.adapter.closes, "Replacement does not wait on native retirement from client");
            }
            fixture.drain();
            if (!ordinary) assertEquals(1,fixture.adapter.closes, "Owned retirement executes once on worker");
            assertFalse(fixture.runtime.snapshot().busy());
            assertEquals(1,fixture.runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream().filter(c -> c.resource()!=null).count(),
                    "Skin-result revision cannot discard independent current cape catalog completion");
            fixture.runtime.close();
        }
    }

    @Test
    void finalCorrectionTypedCategoriesDismissOnBothTabsAndOuter(@TempDir Path directory) throws Exception {
        for (var reason : List.of(com.naocraftlab.skins.client.CpmModelFileConversion.Reason.INVALID_MODEL,
                com.naocraftlab.skins.client.CpmModelFileConversion.Reason.IO_FAILED,
                com.naocraftlab.skins.client.CpmModelFileConversion.Reason.CONVERSION_FAILED)) {
            for (boolean catalog : List.of(false,true)) {
                var fixture = new CpmLifecycleFixture(directory);
                fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
                fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT); fixture.runtime.dispatchWidget("add.file.choose");
                fixture.adapter.failure = reason;
                if (catalog) fixture.runtime.dispatchWidget("add.catalog.skin:minecraft:steve");
                else {
                    fixture.runtime.dispatchWidget("add.tab.file");
                    fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
                    fixture.runtime.dispatchWidget("add.file.choose");
                }
                String status = catalog ? "add.catalog.status" : "add.import.status";
                assertEquals(Optional.of(reason),fixture.runtime.snapshot().addSource().orElseThrow().importContext().failure());
                var feedback = fixture.runtime.view(180,180,0,0);
                assertTrue(feedback.texts().stream().anyMatch(text -> text.id().equals(status)));
                assertEquals(catalog, feedback.widget(status+".info").isPresent());
                assertEquals(catalog, feedback.navigationNode(status+".info").isPresent());
                var preferences = fixture.operations.uiPreferences;
                fixture.runtime.dispatchText(catalog ? "add.catalog.search" : "add.url.input","Synthetic");
                assertTrue(fixture.runtime.snapshot().addSource().orElseThrow().importContext().failure().isEmpty());
                assertTrue(fixture.runtime.view(180,180,0,0).widget(status+".info").isEmpty());
                assertTrue(fixture.runtime.view(180,180,0,0).texts().stream().noneMatch(text -> text.id().equals(status)));
                assertTrue(fixture.runtime.snapshot().addSource().orElseThrow().importContext().filterLocked());
                assertEquals(preferences,fixture.operations.uiPreferences);
                assertEquals(0,fixture.adapter.closes);
                fixture.adapter.failure = null;
                fixture.runtime.dispatchWidget("add.cancel");
                assertEquals(AddSourceModel.Purpose.CREATE_PRESET,fixture.runtime.snapshot().addSource().orElseThrow().importContext().purpose());
                fixture.runtime.close();
            }
            var fixture = new CpmLifecycleFixture(directory);
            fixture.adapter.failure = reason;
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
            fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT); fixture.runtime.dispatchWidget("add.file.choose");
            assertEquals(Optional.of(reason),fixture.runtime.snapshot().addSource().orElseThrow().importContext().failure());
            fixture.runtime.dispatchText("add.player.input","Synthetic");
            assertTrue(fixture.runtime.snapshot().addSource().orElseThrow().importContext().failure().isEmpty());
            assertFalse(fixture.runtime.snapshot().addSource().orElseThrow().importContext().filterLocked());
            fixture.runtime.close();
        }
    }

    @Test
    void finalCorrectionCapeCatalogRejectsRetiredEditAccountResourceAndRequest(@TempDir Path directory) throws Exception {
        for (int retirement = 0; retirement < 4; retirement++) {
            var fixture = new CpmLifecycleFixture(directory); fixture.openConverted();
            fixture.operations.resourceCapeCollections = List.of(resourceCapeCollection());
            fixture.operations.resourceCapeHashes = Map.of(new ClientOperations.ResourceCapeKey("event","hero"),"c".repeat(64));
            fixture.hold = true; fixture.runtime.resourcesReloaded(); fixture.workers.remove().run();
            assertEquals(1,fixture.workers.size());
            fixture.holdClient = true; fixture.workers.remove().run();
            assertEquals(1,fixture.clients.size());
            Runnable stale = fixture.clients.remove();
            fixture.holdClient = false; fixture.hold = false;
            fixture.operations.resourceCapeCollections = List.of();
            if (retirement == 0 || retirement == 1) {
                if (retirement == 1) {
                    var original = fixture.operations.session;
                    fixture.operations.session = new SessionValidation(SessionStatus.OFFLINE_OR_INVALID,
                            new GameSessionTokenSource.SessionIdentity(UUID.randomUUID(),"Other"),null,(SessionFailureContext)null,"synthetic");
                    fixture.runtime.tick(); fixture.operations.session = original; fixture.runtime.tick();
                    assertTrue(fixture.runtime.snapshot().editor().isEmpty());
                }
                fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
                fixture.runtime.closeScreen();
                fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT); fixture.runtime.dispatchWidget("add.file.choose");
            } else if (retirement == 2) fixture.operations.resourceCapeGeneration++;
            else {
                fixture.operations.resourceCapeCollections = List.of();
                fixture.runtime.resourcesReloaded();
            }
            assertTrue(fixture.runtime.snapshot().editor().isPresent(), "Current editor setup mode=" + retirement
                    + " busy=" + fixture.runtime.snapshot().busy() + " status=" + fixture.runtime.snapshot().status().key()
                    + " add=" + fixture.runtime.snapshot().addSource().isPresent() + " picks=" + fixture.picks.size());
            assertTrue(fixture.runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream().noneMatch(c -> c.resource()!=null));
            stale.run();
            assertTrue(fixture.runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream().noneMatch(c -> c.resource()!=null),
                    "Late catalog completion cannot cross edit/account/resource/superseded-request scope");
            fixture.operations.resourceCapeCollections = List.of(resourceCapeCollection());
            fixture.runtime.resourcesReloaded();
            assertEquals(1,fixture.runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream().filter(c -> c.resource()!=null).count(),
                    "A current actual reload remains admitted after stale rejection");
            fixture.runtime.close();
        }
    }

    @Test
    void nativePreparationAndConversionStayOnWorkerWhileDraftPublishesOnClient(@TempDir Path directory) throws Exception {
        for (boolean automatic : List.of(false, true)) {
            var fixture = new CpmLifecycleFixture(directory);
            var nativeLanes = new java.util.ArrayList<Boolean>();
            fixture.adapter.nativeWork = () -> nativeLanes.add(fixture.clientLane);
            fixture.adapter.automatic = automatic;
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
            fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
            fixture.hold = true;
            fixture.holdClient = true;
            fixture.runtime.dispatchWidget("add.file.choose");
            fixture.drain();
            if (!automatic) {
                fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
                fixture.runtime.dispatchWidget("add.tab.file");
                fixture.runtime.dispatchWidget("add.file.choose");
                fixture.drain();
            }
            assertEquals(List.of(false, false), nativeLanes, "Native preparation/conversion must not run on client");
            assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
            assertEquals(1, fixture.adapter.conversions);
            fixture.runtime.close();
            fixture.drain();
            assertEquals(1, fixture.adapter.closes);
        }
    }

    @Test
    void invalidQueuedNativeWorkNeverCreatesOrConvertsAndLateCompletionCannotPublish(@TempDir Path directory) throws Exception {
        for (int phase = 0; phase < 3; phase++) for (int event = 0; event < 4; event++) {
            var fixture = new CpmLifecycleFixture(directory);
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
            fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
            fixture.hold = true;
            fixture.runtime.dispatchWidget("add.file.choose");
            if (phase > 0) {
                fixture.drain();
                assertEquals(1, fixture.adapter.admissions);
                fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
                fixture.runtime.dispatchWidget("add.tab.file");
                fixture.runtime.dispatchWidget("add.file.choose");
                if (phase == 2) {
                    fixture.holdClient = true;
                    fixture.workers.remove().run();
                    assertEquals(1, fixture.adapter.conversions);
                    assertFalse(fixture.clients.isEmpty());
                }
            }
            assertTrue(fixture.runtime.snapshot().busy());
            fixture.holdClient = false;
            if (event == 0) fixture.runtime.closeScreen();
            if (event == 1) {
                fixture.operations.session = new SessionValidation(SessionStatus.OFFLINE_OR_INVALID,
                        new GameSessionTokenSource.SessionIdentity(UUID.randomUUID(), "Other"), null, (SessionFailureContext) null, "synthetic");
                fixture.runtime.tick();
            }
            if (event == 2) fixture.runtime.resourcesReloaded();
            if (event == 3) fixture.runtime.importWorldChanged();
            fixture.drain();
            assertEquals(phase == 0 ? 0 : 1, fixture.adapter.admissions, "Cancelled queued acquisition must never create SDK state");
            assertEquals(phase == 2 ? 1 : 0, fixture.adapter.conversions, "Cancelled queued conversion must not execute");
            assertTrue(fixture.runtime.snapshot().editor().isEmpty(), "Stale native completion cannot publish Editor");
            fixture.runtime.close();
            fixture.drain();
            assertEquals(phase == 0 ? 0 : 1, fixture.adapter.closes, "Only admitted owned handle retires exactly once");
        }
    }

    @Test
    void unknownAutomaticModelKeepsBothPreparedPairsAcrossSelectionCopiesAndReplacement(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory, null);
        fixture.adapter.automatic = true;
        for (SkinModel type : SkinModel.values()) {
            var image = javax.imageio.ImageIO.read(Path.of("../core/src/test/resources/cpm/"
                    + (type == SkinModel.SLIM ? "slim-a.png" : "classic-a.png")).toFile());
            fixture.adapter.outputs.put(type, image.getRGB(0, 0, 64, 64, null, 0, 64));
        }
        fixture.operations.uiPreferences = fixture.operations.uiPreferences.withPreferredSkinVariant(SkinVariant.SLIM);
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
        var preferences = fixture.operations.uiPreferences;
        fixture.runtime.dispatchWidget("add.file.choose");
        var draft = fixture.runtime.snapshot().editor().orElseThrow();
        assertEquals(SkinVariant.SLIM, draft.variant());
        assertTrue(draft.variantAvailable(SkinVariant.CLASSIC), "UNKNOWN exposes the actually prepared alternate pair");
        byte[] slim = draft.png().orElseThrow().bytes();
        fixture.runtime.dispatchText("editor.name", "Owned pair");
        fixture.runtime.dispatchWidget("editor.model_choice.classic");
        draft = fixture.runtime.snapshot().editor().orElseThrow();
        assertEquals(SkinVariant.CLASSIC, draft.variant());
        assertEquals("Owned pair", draft.name());
        byte[] classic = draft.png().orElseThrow().bytes();
        assertFalse(java.util.Arrays.equals(slim, classic), "Pair selection changes prepared bytes, never a retag");
        assertTrue(draft.saveRequest().exactCarrierReuse());
        assertTrue(draft.saveRequest().withSaveScope(draft.saveRequest().saveScope()).exactCarrierReuse());
        assertEquals(2, fixture.adapter.conversions, "Selection and copies never reconvert");
        fixture.runtime.dispatchWidget("editor.model_choice.slim");
        assertArrayEquals(slim, fixture.runtime.snapshot().editor().orElseThrow().png().orElseThrow().bytes());
        assertEquals(preferences, fixture.operations.uiPreferences, "Scoped pair selection does not persist ordinary preferences");
        var replaced = draft.withImportedPng("ordinary.png", Files.readAllBytes(fixture.base), SkinVariant.CLASSIC);
        assertFalse(replaced.saveRequest().exactCarrierReuse());
        fixture.runtime.close();
        assertEquals(1, fixture.adapter.closes);
    }

    @Test
    void automaticEditorCancelReturnsOuterImportAndChosenBaseReturnsChooser(@TempDir Path directory) throws Exception {
        for (boolean automatic : List.of(true, false)) {
            var fixture = new CpmLifecycleFixture(directory);
            fixture.adapter.automatic = automatic;
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
            fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
            fixture.runtime.dispatchText("add.url.input", "https://example.com/outer");
            fixture.runtime.dispatchWidget("add.file.choose");
            if (!automatic) {
                fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
                fixture.runtime.dispatchWidget("add.tab.file");
                fixture.runtime.dispatchWidget("add.file.choose");
            }
            assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
            fixture.runtime.dispatchWidget("editor.cancel");
            var parent = fixture.runtime.snapshot().addSource().orElseThrow();
            assertEquals(automatic ? AddSourceModel.Purpose.CREATE_PRESET : AddSourceModel.Purpose.CPM_BASE,
                    parent.importContext().purpose(), "Cancel returns the actual outer or chosen-base parent");
            assertEquals("https://example.com/outer", parent.urlInput());
            if (automatic) assertEquals(com.naocraftlab.skins.core.model.AddSourceTab.FILE, parent.selectedTab());
            fixture.runtime.close();
            assertEquals(1, fixture.adapter.closes);
        }
    }

    @Test
    void removedFallbackKeepsRetainedDraftAndSaveScopeAcrossReload(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory);
        fixture.openConverted();
        fixture.runtime.dispatchText("editor.name", "Retained draft");
        fixture.operations.failEditorSave = true;
        fixture.runtime.dispatchWidget("editor.save");
        var before = fixture.runtime.snapshot().editor().orElseThrow();
        var scope = fixture.operations.lastEditorSaveRequest.saveScope().orElseThrow();
        for (int[] size : List.of(new int[]{240,240}, new int[]{854,180}, new int[]{1280,240}, new int[]{854,480})) {
            var view = fixture.runtime.view(size[0], size[1], 0, 0);
            assertTrue(view.widget("editor.cpm.fallback.choose").isEmpty());
            assertTrue(view.navigationNode("editor.cpm.fallback.choose").isEmpty());
            assertTrue(view.clipFor("editor.cpm.fallback.choose").isEmpty());
            fixture.runtime.dispatchWidget("editor.cpm.fallback.choose");
            assertSame(before, fixture.runtime.snapshot().editor().orElseThrow());
        }
        fixture.operations.resourceCapeCollections = List.of(resourceCapeCollection());
        fixture.runtime.resourcesReloaded();
        assertTrue(fixture.runtime.snapshot().editor().isPresent());
        assertTrue(scope.stillCurrent().getAsBoolean());
        assertEquals(1, fixture.adapter.conversions);
        assertEquals(0, fixture.adapter.closes);
        fixture.runtime.close();
        assertEquals(1, fixture.adapter.closes);
        assertFalse(scope.stillCurrent().getAsBoolean());
    }

    @Test
    void cpmDependencyTabProjectsTheRequestedFocus(@TempDir Path directory) throws Exception {
        var dependency = new CpmDependencyFixture(directory);
        var fixture = dependency.fixture;
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
        fixture.runtime.dispatchWidget("add.file.choose");
        String confirm = fixture.runtime.view(240, 240, 0, 0).widgets().stream()
                .filter(widget -> widget.id().startsWith("add.cpm.dependency.confirm.")).findFirst().orElseThrow().id();
        String cancel = confirm.replace(".confirm.", ".cancel.");
        assertTrue(fixture.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, confirm));
        assertEquals(cancel, fixture.runtime.view(240, 240, 0, 0).focusRequest().orElseThrow().widgetId());
        assertTrue(fixture.runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_BACKWARD, cancel));
        assertEquals(confirm, fixture.runtime.view(240, 240, 0, 0).focusRequest().orElseThrow().widgetId());
        fixture.runtime.close();
    }

    @Test
    void cpmExplicitPublicationAndLocalRetryNeverPublishTwice(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory);
        fixture.adapter.automatic = true;
        fixture.adapter.publication = true;
        fixture.adapter.linkFailures = 1;
        var uploads = new AtomicInteger();
        fixture.runtime.useCpmModelPublication((request, current) -> {
            assertTrue(current.getAsBoolean());
            uploads.incrementAndGet();
            return new com.naocraftlab.skins.client.CpmModelPublication.Result(
                    com.naocraftlab.skins.client.CpmModelPublication.Status.CONFIRMED,
                    com.naocraftlab.skins.client.CpmModelPublication.Reason.NONE, Optional.of("fixture-id"));
        });
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
        fixture.runtime.dispatchWidget("add.file.choose");
        assertEquals(1, uploads.get());
        assertTrue(fixture.runtime.snapshot().editor().isEmpty());
        assertFalse(fixture.runtime.snapshot().busy());
        String confirm = "add.cpm.publication.confirm.1";
        fixture.runtime.dispatchWidget(confirm);
        assertEquals(1, uploads.get());
        assertTrue(fixture.runtime.snapshot().editor().isEmpty());
        assertTrue(fixture.runtime.view(240, 240, 0, 0).widgets().stream()
                .noneMatch(widget -> widget.id().startsWith("add.cpm.publication.")));
        assertEquals(AddSourceModel.Purpose.CREATE_PRESET, fixture.runtime.snapshot().addSource().orElseThrow().importContext().purpose());
        fixture.runtime.dispatchWidget(confirm);
        assertEquals(1, fixture.adapter.links);
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.dispatchWidget("add.file.choose");
        assertEquals(1, uploads.get());
        assertEquals(2, fixture.adapter.links);
        assertEquals(2, fixture.adapter.publicationCloses);
        assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
        assertTrue(fixture.runtime.snapshot().editor().orElseThrow().saveRequest().exactCarrierReuse());
        fixture.runtime.dispatchWidget("editor.save");
        assertEquals(1, uploads.get());
        fixture.runtime.close();
    }

    @Test
    void cpmPublicationForcedCloseAndResourceRetirementHaveNoRemoteEffect(@TempDir Path directory) throws Exception {
        for (boolean reload : List.of(false, true)) {
            var fixture = new CpmLifecycleFixture(directory);
            fixture.adapter.automatic = true;
            fixture.adapter.publication = true;
            fixture.runtime.useCpmModelPublication((request, current) -> { throw new AssertionError("Retired before first effect"); });
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
            fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
            fixture.hold = true;
            fixture.runtime.dispatchWidget("add.file.choose");
            fixture.workers.remove().run(); fixture.workers.remove().run();
            String confirm = "add.cpm.publication.confirm.1";
            if (reload) fixture.runtime.resourcesReloaded();
            else fixture.runtime.closeScreen();
            fixture.runtime.dispatchWidget(confirm);
            fixture.drain();
            assertTrue(fixture.runtime.snapshot().editor().isEmpty());
            assertFalse(fixture.runtime.snapshot().busy());
            assertEquals(1, fixture.adapter.publicationCloses);
            fixture.runtime.close();
        }
    }

    @Test
    void cpmBatchUsesOriginalDeadlineAndQueuedSecondIsNotSent(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory);
        fixture.adapter.automatic = true;
        fixture.adapter.publication = true;
        fixture.adapter.publicationSteps = 2;
        var clock = new java.util.concurrent.atomic.AtomicLong();
        fixture.runtime.importClock(clock::get);
        var sent = new ArrayList<Integer>();
        fixture.runtime.useCpmModelPublication((request, current) -> {
            var control = (com.naocraftlab.skins.client.CpmModelPublication.Control) current;
            assertTrue(control.beginSend());
            sent.add((int) request.wire()[0]);
            if (sent.size() == 1) clock.set(java.time.Duration.ofSeconds(40).toNanos());
            return new com.naocraftlab.skins.client.CpmModelPublication.Result(
                    com.naocraftlab.skins.client.CpmModelPublication.Status.CONFIRMED,
                    com.naocraftlab.skins.client.CpmModelPublication.Reason.NONE, Optional.of("fixture-id"));
        });
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
        fixture.hold = true;
        fixture.runtime.dispatchWidget("add.file.choose");
        for (int step = 0; step < 4; step++) fixture.workers.remove().run();
        assertEquals(List.of(1), sent);
        assertTrue(fixture.runtime.snapshot().busy());
        clock.set(java.time.Duration.ofSeconds(60).toNanos());
        fixture.runtime.view(240, 240, 0, 0);
        assertFalse(fixture.runtime.snapshot().busy(), "Second payload cannot restart the first 60-second budget");
        assertEquals("nclskins.add.cpm_publication_timeout", fixture.runtime.snapshot().status().key(),
                "First confirmed send cannot make the queued second payload uncertain");
        fixture.drain();
        assertEquals(List.of(1), sent);
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.dispatchWidget("add.file.choose");
        fixture.drain();
        assertEquals(List.of(1, 2), sent, "Confirmed first reuses its link; definitely-unsent second needs only the new explicit import");
        assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
        fixture.assertPublicationHistory();
        fixture.runtime.close(); fixture.drain();
    }

    @Test
    void cpmLateSecondSettlementCannotUnlockNewImportOrOverwriteFirstSettlement(@TempDir Path directory) throws Exception {
        for (boolean startB : List.of(false, true)) {
        var fixture = new CpmLifecycleFixture(directory);
        fixture.adapter.automatic = true; fixture.adapter.publication = true; fixture.adapter.publicationSteps = 2;
        var clock = new java.util.concurrent.atomic.AtomicLong();
        fixture.runtime.importClock(clock::get);
        var sent = new ArrayList<Integer>();
        fixture.runtime.useCpmModelPublication((request, current) -> {
            var control = (com.naocraftlab.skins.client.CpmModelPublication.Control) current;
            assertTrue(control.beginSend()); sent.add((int) request.wire()[0]);
            if (sent.size() == 1) clock.set(java.time.Duration.ofSeconds(40).toNanos());
            else {
                assertEquals(java.time.Duration.ofSeconds(20).toNanos(), control.remainingNanos());
                clock.set(java.time.Duration.ofSeconds(60).toNanos());
                fixture.runtime.tick();
                assertFalse(fixture.runtime.snapshot().busy());
                assertEquals("nclskins.add.cpm_publication_uncertain", fixture.runtime.snapshot().status().key());
                if (startB) {
                    fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
                    fixture.runtime.dispatchWidget("add.file.choose");
                    assertTrue(fixture.runtime.snapshot().busy());
                }
            }
            return new com.naocraftlab.skins.client.CpmModelPublication.Result(
                    com.naocraftlab.skins.client.CpmModelPublication.Status.CONFIRMED,
                    com.naocraftlab.skins.client.CpmModelPublication.Reason.NONE, Optional.of("fixture-id"));
        });
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
        fixture.hold = true;
        fixture.runtime.dispatchWidget("add.file.choose");
        for (int step = 0; step < 5; step++) fixture.workers.remove().run();
        assertEquals(List.of(1, 2), sent);
        assertEquals(startB, fixture.runtime.snapshot().busy(), "Old accepted reply must not unlock B");
        assertTrue(fixture.runtime.snapshot().editor().isEmpty());
        if (!startB) {
            assertEquals("nclskins.add.cpm_publication_uncertain", fixture.runtime.snapshot().status().key(),
                    "Late accepted reply updates only its own settlement, not the terminal error");
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
            fixture.runtime.dispatchWidget("add.file.choose");
        }
        fixture.drain();
        assertEquals(List.of(1, 2), sent, "Both own confirmed settlements are reused by the new explicit import");
        assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
        fixture.runtime.close(); fixture.drain();
        }
    }

    @Test
    void cpmPublicationAlternatePairsAutomaticallyPublishOnlyDistinctPayloads(@TempDir Path directory) throws Exception {
        for (boolean shared : List.of(false, true)) {
            var fixture = new CpmLifecycleFixture(directory, null);
            fixture.adapter.automatic = true;
            fixture.adapter.publication = true;
            fixture.adapter.sharedWire = shared;
            var uploads = new AtomicInteger();
            fixture.runtime.useCpmModelPublication((request, current) -> {
                uploads.incrementAndGet();
                return new com.naocraftlab.skins.client.CpmModelPublication.Result(
                        com.naocraftlab.skins.client.CpmModelPublication.Status.CONFIRMED,
                        com.naocraftlab.skins.client.CpmModelPublication.Reason.NONE, Optional.of("fixture-id"));
            });
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
            fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
            fixture.runtime.dispatchWidget("add.file.choose");
            var draft = fixture.runtime.snapshot().editor().orElseThrow();
            assertTrue(draft.variantAvailable(SkinVariant.CLASSIC) && draft.variantAvailable(SkinVariant.SLIM));
            assertEquals(shared ? 1 : 2, uploads.get());
            assertEquals(2, fixture.adapter.links);
            assertEquals(2, fixture.adapter.publicationCloses);
            fixture.runtime.close();
        }
    }

    @Test
    void cpmSequentialPublicationAutomaticallyContinuesAndReusesConfirmedWires(@TempDir Path directory) throws Exception {
        for (int steps : List.of(2, 3)) for (boolean repeated : List.of(false, true)) for (boolean pairs : List.of(false, true)) {
            var fixture = new CpmLifecycleFixture(directory, pairs ? null : SkinModel.CLASSIC);
            fixture.adapter.automatic = true; fixture.adapter.publication = true;
            fixture.adapter.publicationSteps = steps; fixture.adapter.repeatedWire = repeated; fixture.adapter.sharedWire = pairs;
            if (!repeated) fixture.adapter.failLinkAt = pairs ? 3 : 2;
            var sent = new ArrayList<byte[]>();
            fixture.runtime.useCpmModelPublication((request, current) -> {
                assertTrue(current.getAsBoolean()); sent.add(request.wire());
                return new com.naocraftlab.skins.client.CpmModelPublication.Result(
                        com.naocraftlab.skins.client.CpmModelPublication.Status.CONFIRMED,
                        com.naocraftlab.skins.client.CpmModelPublication.Reason.NONE, Optional.of("fixture-id"));
            });
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
            fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT); fixture.runtime.dispatchWidget("add.file.choose");
            if (!repeated) {
                assertTrue(fixture.runtime.snapshot().editor().isEmpty());
                assertEquals(2, sent.size());
                fixture.runtime.dispatchWidget("add.cpm.publication.confirm.1"); assertEquals(2, sent.size());
                fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
                fixture.runtime.dispatchWidget("add.file.choose");
            }
            assertEquals(repeated ? 1 : steps, sent.size());
            var editor = fixture.runtime.snapshot().editor().orElseThrow();
            assertTrue(editor.convertedCreation());
            if (pairs) assertTrue(editor.variantAvailable(SkinVariant.CLASSIC) && editor.variantAvailable(SkinVariant.SLIM));
            fixture.assertPublicationHistory();
            fixture.runtime.close();
        }
    }

    @Test
    void cpmSequentialPublicationUncertainSecondCannotResendFirstOrSecond(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory);
        fixture.adapter.automatic = true; fixture.adapter.publication = true; fixture.adapter.publicationSteps = 2;
        var calls = new AtomicInteger();
        fixture.runtime.useCpmModelPublication((request, current) -> calls.incrementAndGet() == 1
                ? new com.naocraftlab.skins.client.CpmModelPublication.Result(
                    com.naocraftlab.skins.client.CpmModelPublication.Status.CONFIRMED,
                    com.naocraftlab.skins.client.CpmModelPublication.Reason.NONE, Optional.of("fixture-id"))
                : new com.naocraftlab.skins.client.CpmModelPublication.Result(
                    com.naocraftlab.skins.client.CpmModelPublication.Status.UNCERTAIN,
                    com.naocraftlab.skins.client.CpmModelPublication.Reason.NETWORK, Optional.empty()));
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT); fixture.runtime.dispatchWidget("add.file.choose");
        assertTrue(fixture.runtime.view(240, 240, 0, 0).widgets().stream()
                .noneMatch(widget -> widget.id().startsWith("add.cpm.publication.")));
        assertEquals("nclskins.add.cpm_publication_uncertain", fixture.runtime.snapshot().status().key());
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.dispatchWidget("add.file.choose");
        assertEquals(2, calls.get()); assertEquals(2, fixture.adapter.links);
        assertTrue(fixture.runtime.snapshot().editor().isEmpty());
        fixture.runtime.close(); assertEquals(4, fixture.adapter.publicationCloses);
    }

    @Test
    void cpmSequentialPublicationRetiresUnpublishedNextContinuationAfterDelayedClient(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory);
        fixture.adapter.automatic = true; fixture.adapter.publication = true; fixture.adapter.publicationSteps = 2;
        var calls = new AtomicInteger();
        fixture.runtime.useCpmModelPublication((request, current) -> {
            calls.incrementAndGet();
            return new com.naocraftlab.skins.client.CpmModelPublication.Result(
                    com.naocraftlab.skins.client.CpmModelPublication.Status.CONFIRMED,
                    com.naocraftlab.skins.client.CpmModelPublication.Reason.NONE, Optional.of("fixture-id"));
        });
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
        fixture.hold = true;
        fixture.runtime.dispatchWidget("add.file.choose");
        for (int step = 0; step < 3; step++) fixture.workers.remove().run();
        assertEquals(1, calls.get()); assertEquals(0, fixture.adapter.links);
        fixture.holdClient = true; fixture.workers.remove().run();
        assertEquals(1, fixture.adapter.links); assertFalse(fixture.clients.isEmpty());
        fixture.runtime.closeScreen(); fixture.holdClient = false; fixture.drain();
        assertEquals(2, fixture.adapter.publicationCloses);
        assertTrue(fixture.runtime.snapshot().editor().isEmpty()); assertEquals(1, calls.get());
        fixture.runtime.close(); fixture.drain(); assertEquals(2, fixture.adapter.publicationCloses);
    }

    @Test
    void cpmGenuineBaseWaitCannotRecreatePublicationIntentAfterResourceOrWorldRetirement(@TempDir Path directory) throws Exception {
        for (boolean world : List.of(false, true)) {
            var fixture = new CpmLifecycleFixture(directory);
            fixture.adapter.publication = true;
            var sends = new AtomicInteger();
            fixture.runtime.useCpmModelPublication((request, current) -> {
                sends.incrementAndGet();
                return new com.naocraftlab.skins.client.CpmModelPublication.Result(
                        com.naocraftlab.skins.client.CpmModelPublication.Status.CONFIRMED,
                        com.naocraftlab.skins.client.CpmModelPublication.Reason.NONE, Optional.of("fixture-id"));
            });
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
            fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT); fixture.runtime.dispatchWidget("add.file.choose");
            if (world) fixture.runtime.importWorldChanged(); else fixture.runtime.resourcesReloaded();
            fixture.runtime.dispatchWidget("add.tab.file"); fixture.runtime.dispatchWidget("add.file.choose");
            assertEquals(0, sends.get(), "Choosing a base cannot authorize an old model after scope retirement");
            assertTrue(fixture.runtime.snapshot().editor().isEmpty());
            assertFalse(fixture.runtime.snapshot().busy());
            assertEquals(Optional.of(world ? com.naocraftlab.skins.client.CpmModelFileConversion.Reason.STALE_WORLD
                    : com.naocraftlab.skins.client.CpmModelFileConversion.Reason.STALE_RESOURCES),
                    fixture.runtime.snapshot().addSource().orElseThrow().importContext().failure());
            fixture.runtime.dispatchWidget("add.cancel");
            assertEquals(AddSourceModel.Purpose.CREATE_PRESET, fixture.runtime.snapshot().addSource().orElseThrow().importContext().purpose());
            fixture.runtime.close();
        }
    }

    @Test
    void cpmPublishedEditorCancelDoesNotResurrectManagedChooserCards(@TempDir Path directory) throws Exception {
        var fixture = new CpmLifecycleFixture(directory);
        String hash = fixture.operations.seedPersonalSkin("Synthetic managed card");
        fixture.adapter.publication = true;
        fixture.runtime.useCpmModelPublication((request, current) ->
                new com.naocraftlab.skins.client.CpmModelPublication.Result(
                        com.naocraftlab.skins.client.CpmModelPublication.Status.CONFIRMED,
                        com.naocraftlab.skins.client.CpmModelPublication.Reason.NONE, Optional.of("fixture-id")));
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.base)));
        fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
        fixture.runtime.dispatchWidget("add.file.choose");
        fixture.runtime.dispatchWidget("add.catalog.delete:" + PersonalSkinCatalog.COLLECTION_ID + ":" + hash);
        fixture.runtime.dispatchWidget("add.catalog.delete.confirm");
        fixture.runtime.dispatchWidget("add.tab.file");
        fixture.runtime.dispatchText("add.url.input", "https://example.invalid/chooser");
        fixture.runtime.dispatchWidget("add.file.choose");
        assertTrue(fixture.runtime.snapshot().editor().orElseThrow().convertedCreation());
        fixture.runtime.dispatchWidget("editor.cancel");
        var chooser = fixture.runtime.snapshot().addSource().orElseThrow();
        assertEquals(AddSourceModel.Purpose.CPM_BASE, chooser.importContext().purpose());
        assertEquals("https://example.invalid/chooser", chooser.urlInput());
        assertTrue(chooser.collections().stream().noneMatch(collection -> PersonalSkinCatalog.isCollection(collection.id())));
        fixture.runtime.dispatchWidget("add.cancel");
        assertEquals(AddSourceModel.Purpose.CREATE_PRESET, fixture.runtime.snapshot().addSource().orElseThrow().importContext().purpose());
        assertTrue(fixture.runtime.snapshot().addSource().orElseThrow().collections().stream()
                .noneMatch(collection -> PersonalSkinCatalog.isCollection(collection.id())));
        fixture.runtime.close();
    }

    @Test
    void cpmPublicationIntentRetiresBeforeFirstOrSecondEffectAcrossEveryScopeFence(@TempDir Path directory) throws Exception {
        for (boolean second : List.of(false, true)) for (int event = 0; event < 6; event++) {
            var fixture = new CpmLifecycleFixture(directory);
            fixture.adapter.automatic = true; fixture.adapter.publication = true; fixture.adapter.publicationSteps = 2;
            var sends = new AtomicInteger();
            fixture.runtime.useCpmModelPublication((request, current) -> {
                assertTrue(current.getAsBoolean()); sends.incrementAndGet();
                return new com.naocraftlab.skins.client.CpmModelPublication.Result(
                        com.naocraftlab.skins.client.CpmModelPublication.Status.CONFIRMED,
                        com.naocraftlab.skins.client.CpmModelPublication.Reason.NONE, Optional.of("fixture-id"));
            });
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
            fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT); fixture.hold = true;
            fixture.runtime.dispatchWidget("add.file.choose");
            for (int step = 0; step < (second ? 4 : 2); step++) fixture.workers.remove().run();
            var original = fixture.operations.session;
            if (event == 0) fixture.runtime.closeScreen();
            if (event == 1) fixture.runtime.resourcesReloaded();
            if (event == 2) fixture.runtime.importWorldChanged();
            if (event >= 3) {
                fixture.operations.session = new SessionValidation(SessionStatus.OFFLINE_OR_INVALID,
                        new GameSessionTokenSource.SessionIdentity(event == 3 ? TestFixtures.ACCOUNT_ID : UUID.randomUUID(), "Changed"),
                        null, (SessionFailureContext) null, "synthetic");
                if (event != 5) fixture.runtime.tick();
                if (event == 4) { fixture.operations.session = original; fixture.runtime.tick(); }
            }
            fixture.runtime.dispatchWidget("add.cpm.publication.confirm.1");
            fixture.drain();
            assertEquals(second ? 1 : 0, sends.get(), "scope fence " + event);
            assertTrue(fixture.runtime.snapshot().editor().isEmpty());
            assertFalse(fixture.runtime.snapshot().busy());
            assertEquals(1, fixture.adapter.closes, "Retire model at scope fence " + event + ", second=" + second);
            assertEquals(second ? 2 : 1, fixture.adapter.publicationCloses, "Retire continuations at scope fence " + event);
            fixture.runtime.close(); fixture.drain();
        }
    }

    @Test
    void cpmDependencyAccountChangeDuringResponseRetiresOnlyOnClient(@TempDir Path directory) throws Exception {
        assertDependencyIdentityRetirement(directory, true);
    }

    @Test
    void cpmDependencyAccountChangeBeforeRequestRetiresOnlyOnClient(@TempDir Path directory) throws Exception {
        assertDependencyIdentityRetirement(directory, false);
    }

    @Test
    void cpmDependencyDeliveredAbaPreservesNewWorkBeforeOldClientCompletion(@TempDir Path directory) throws Exception {
        var dependency = new CpmDependencyFixture(directory);
        var fixture = dependency.fixture;
        var original = fixture.operations.session;
        dependency.confirm();
        dependency.response = dependency::switchAccount;
        fixture.workers.remove().run();
        assertEquals(1, dependency.fetches);
        assertEquals(1, dependency.admissions);
        assertEquals(1, fixture.clients.size());
        fixture.runtime.tick();
        fixture.operations.session = original;
        fixture.runtime.tick();
        assertTrue(fixture.runtime.snapshot().addSource().isEmpty());
        dependency.response = () -> { };
        fixture.hold = false;
        fixture.holdClient = false;
        fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
        fixture.runtime.closeScreen();
        fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
        fixture.runtime.dispatchWidget("add.tab.file");
        fixture.runtime.dispatchWidget("add.file.choose");
        assertEquals(2, dependency.admissions);
        fixture.hold = true;
        fixture.holdClient = true;
        dependency.confirmCurrent();
        var newer = fixture.runtime.snapshot();
        assertTrue(newer.busy());
        fixture.clients.remove().run();
        assertSame(newer, fixture.runtime.snapshot(), "Old completion cannot unlock or close new work after delivered ABA");
        fixture.workers.remove().run();
        assertSame(newer, fixture.runtime.snapshot(), "Old cleanup cannot publish into new work");
        assertThrows(java.io.IOException.class, () -> dependency.inputs.get(0).open());
        try (var reader = dependency.inputs.get(1).open()) { assertEquals(83, reader.read()); }
        fixture.drain();
        assertEquals(2, dependency.fetches);
        assertEquals(3, dependency.admissions);
        assertEquals(1, fixture.adapter.admissions);
        assertEquals(1, fixture.adapter.conversions);
        assertEquals(0, fixture.adapter.closes, "Old cleanup cannot close the new native model");
        assertTrue(fixture.runtime.snapshot().editor().isPresent());
        assertFalse(fixture.runtime.snapshot().busy());
        fixture.runtime.close();
        fixture.drain();
        assertEquals(1, fixture.adapter.closes);
    }

    private static void assertDependencyIdentityRetirement(Path directory, boolean duringResponse) throws Exception {
        var dependency = new CpmDependencyFixture(directory);
        var fixture = dependency.fixture;
        dependency.confirm();
        var pending = fixture.runtime.snapshot();
        var offClientPublications = new AtomicInteger();
        fixture.runtime.subscribe(snapshot -> { if (!fixture.clientLane) offClientPublications.incrementAndGet(); });
        try (var registration = CapeProjection.installEvents(new CapeProjection.Events() {
            public void trackedPlayer(UUID id, String name) { }
            public void playerInfoUpdated(UUID id, String name) { }
            public void untrackedPlayer(UUID id) { }
            public void worldChanged() { }
            public void worldEntered() { }
        })) {
            assertTrue(registration.active());
            var preview = CapeProjection.effectiveSkin(UUID.randomUUID(), UUID.randomUUID(),
                    "nclskins:dependency-preview", SkinModel.CLASSIC, true);
            assertTrue(preview != null);
            if (duringResponse) dependency.response = dependency::switchAccount;
            else dependency.switchAccount();
            fixture.workers.remove().run();
            assertEquals(duringResponse ? 1 : 0, dependency.fetches);
            assertEquals(1, dependency.admissions, "A stale response must not replay native admission");
            assertEquals(0, offClientPublications.get(), "Dependency currentness must not publish from the worker");
            assertSame(pending, fixture.runtime.snapshot(), "Worker must leave busy, screens and lifecycle untouched");
            assertTrue(preview.active(), "Worker must not retire native preview state");
            assertFalse(fixture.clients.isEmpty(), "Retirement returns through the client completion");
            fixture.drain();
            assertFalse(fixture.runtime.snapshot().busy());
            assertTrue(fixture.runtime.snapshot().editor().isEmpty());
            assertTrue(fixture.runtime.snapshot().addSource().isEmpty());
            assertFalse(preview.active());
            assertThrows(java.io.IOException.class, () -> dependency.inputs.get(0).open());
            assertEquals(0, fixture.adapter.admissions);
            assertEquals(0, fixture.adapter.conversions);
        } finally {
            fixture.runtime.close();
            fixture.drain();
        }
    }

    private static final class CpmDependencyFixture {
        final CpmLifecycleFixture fixture;
        final List<com.naocraftlab.skins.client.ImportInput> inputs = new ArrayList<>();
        int fetches, admissions;
        Runnable response = () -> { };

        CpmDependencyFixture(Path directory) throws Exception {
            fixture = new CpmLifecycleFixture(directory);
            fixture.adapter.automatic = true;
            var converter = new com.naocraftlab.skins.client.CpmModelFileConversion.DependencyAdmission() {
                public Availability availability() { return Availability.READY; }
                public AdmittedModel admitFile(Path path) { throw new AssertionError("Use frozen input"); }
                public AdmittedModel admit(java.io.InputStream input) { throw new AssertionError("Use dependency admission"); }
                public Admission admit(java.io.InputStream input, Map<String, byte[]> dependencies) throws Failure {
                    admissions++;
                    return dependencies.isEmpty()
                            ? new Dependency("synthetic", java.net.URI.create("https://example.invalid/dependency"))
                            : new Ready(fixture.adapter.admit(input));
                }
                public Preparation prepare(AdmittedModel model, Raster base, long revision) throws Failure {
                    return fixture.adapter.prepare(model, base, revision);
                }
            };
            var images = new com.naocraftlab.skins.core.importing.PublicSkinImageSource() {
                public Acquired acquireFile(Path path) throws Exception {
                    var input = com.naocraftlab.skins.client.FrozenImportInput.capture(path);
                    inputs.add(input);
                    return new ModelInput(input);
                }
                public Acquired acquireUrl(String url) { throw new AssertionError("File fixture"); }
                public com.naocraftlab.skins.core.png.NormalizedSkin loadVerified(
                        com.naocraftlab.skins.core.importing.PublicProfileLookup.VerifiedTexture texture) {
                    throw new AssertionError("No profile lookup");
                }
                public com.naocraftlab.skins.core.png.NormalizedSkin loadUntrusted(String url) {
                    throw new AssertionError("No image lookup");
                }
            };
            fixture.operations.dependencyImports = new PublicSkinImportService(Optional.empty(), images,
                    (collection, skin, model) -> { throw new AssertionError("No catalog load"); },
                    Optional.of((uri, timeout, maximum) -> {
                        assertFalse(fixture.clientLane);
                        fetches++;
                        response.run();
                        return new byte[]{1};
                    }), System::nanoTime);
            useBaseConverter(fixture.runtime, converter);
        }

        void confirm() {
            fixture.picks.add(CompletableFuture.completedFuture(Optional.of(fixture.model)));
            fixture.runtime.reopen(ScreenDestination.SKIN_IMPORT);
            fixture.runtime.dispatchWidget("add.file.choose");
            assertEquals(1, admissions);
            assertEquals(0, fetches);
            fixture.hold = true;
            fixture.holdClient = true;
            confirmCurrent();
            assertTrue(fixture.runtime.snapshot().busy());
            assertEquals(1, fixture.workers.size());
        }

        void confirmCurrent() {
            fixture.runtime.dispatchWidget(fixture.runtime.view(854, 480, 0, 0).widgets().stream()
                    .filter(widget -> widget.id().startsWith("add.cpm.dependency.confirm."))
                    .findFirst().orElseThrow().id());
        }

        void switchAccount() {
            fixture.operations.session = new SessionValidation(SessionStatus.OFFLINE_OR_INVALID,
                    new GameSessionTokenSource.SessionIdentity(UUID.fromString("00000000-0000-0000-0000-000000000002"), "Other"),
                    null, (SessionFailureContext) null, "synthetic switch");
        }
    }


    private static final class CpmLifecycleFixture {
        final FakeOperations operations = new FakeOperations();
        final java.util.ArrayDeque<Runnable> workers = new java.util.ArrayDeque<>();
        final java.util.ArrayDeque<CompletableFuture<Optional<Path>>> picks = new java.util.ArrayDeque<>();
        boolean hold;
        boolean holdClient;
        boolean clientLane = true;
        final java.util.ArrayDeque<Runnable> clients = new java.util.ArrayDeque<>();
        final ClientRuntime runtime;
        final List<ClientSnapshot> history = new ArrayList<>();
        final BaseOnlyConverter adapter;
        final Path model, base;
        CpmLifecycleFixture(Path directory) throws Exception { this(directory, SkinModel.CLASSIC); }
        CpmLifecycleFixture(Path directory, SkinModel required) throws Exception {
            adapter = new BaseOnlyConverter(required);
            model = Files.write(directory.resolve("fixture.cpmmodel"), validModel(false));
            base = Files.write(directory.resolve("base.png"), opaqueSkinPng(false));
            var carrier = javax.imageio.ImageIO.read(Path.of("../core/src/test/resources/cpm/classic-a.png").toFile());
            adapter.output = carrier.getRGB(0, 0, 64, 64, null, 0, 64);
            ClientExecutor client = new ClientExecutor() {
                public boolean isClientThread() { return clientLane; }
                public void execute(Runnable action) {
                    Runnable admitted = () -> { boolean previous = clientLane; clientLane = true; try { action.run(); } finally { clientLane = previous; } };
                    if (holdClient) clients.add(admitted); else admitted.run();
                }
            };
            runtime = new ClientRuntime(new DefaultRuntimeServices(), operations, client, () -> picks.remove(),
                    action -> {
                        Runnable admitted = () -> { boolean previous = clientLane; clientLane = false; try { action.run(); } finally { clientLane = previous; } };
                        if (hold) workers.add(admitted); else admitted.run();
                    }, TEXT,
                    Optional.empty(), Optional.empty(), DiagnosticSinks.discarding());
            useBaseConverter(runtime, adapter);
            runtime.subscribe(snapshot -> {
                if (snapshot.addSource().isPresent()) {
                    history.add(snapshot);
                }
            });
        }
        void assertPublicationHistory() {
            boolean active = false;
            int intervals = 0;
            for (int index = 0; index < history.size(); index++) {
                var snapshot = history.get(index);
                var view = new AddSourcePresenter().present(snapshot.addSource().orElseThrow(), snapshot.busy(),
                        Optional.of(snapshot.status()), 854, 480);
                assertTrue(view.widgets().stream().noneMatch(widget -> widget.id().startsWith("add.cpm.publication.")));
                if (snapshot.status().key().equals("nclskins.add.cpm_publication_loading") && snapshot.editor().isEmpty()) {
                    if (!active) intervals++;
                    active = true;
                    assertTrue(snapshot.busy(), "No published unlock between payloads");
                    assertEquals(AddSourceModel.Purpose.CREATE_PRESET, snapshot.addSource().orElseThrow().importContext().purpose());
                    assertEquals(com.naocraftlab.skins.core.model.AddSourceTab.FILE, snapshot.addSource().orElseThrow().selectedTab());
                    assertFalse(view.widget("add.cancel").orElseThrow().enabled());
                } else if (active) {
                    assertTrue(snapshot.editor().isPresent() || snapshot.status().severity() == UiMessage.Severity.ERROR,
                            "A publication interval ends only in Editor or terminal error");
                    active = false;
                }
            }
            assertTrue(intervals > 0);
        }
        void openConverted() {
            picks.add(CompletableFuture.completedFuture(Optional.of(model)));
            picks.add(CompletableFuture.completedFuture(Optional.of(base)));
            runtime.reopen(ScreenDestination.SKIN_IMPORT); runtime.dispatchWidget("add.file.choose");
            runtime.dispatchWidget("add.tab.file"); runtime.dispatchWidget("add.file.choose");
            assertTrue(runtime.snapshot().editor().isPresent(), "Valid carrier fixture reaches actual converted Editor");
        }
        void drain() {
            int steps = 0;
            while (!workers.isEmpty() || !clients.isEmpty()) {
                assertTrue(++steps < 100);
                if (!workers.isEmpty()) workers.remove().run();
                if (!clients.isEmpty()) clients.remove().run();
            }
        }
    }

    private static void useBaseConverter(ClientRuntime runtime,com.naocraftlab.skins.client.CpmModelFileConversion adapter) {
        runtime.useSkinExtensionEnvironmentSource(new com.naocraftlab.skins.client.SkinExtensionEnvironmentSource() {
            public Snapshot snapshot(){return new Snapshot(1,Map.of(),true);}
            public Optional<com.naocraftlab.skins.client.CpmModelFileConversion> modelFileConversion(){return Optional.of(adapter);}
        });
    }

    private static byte[] validModel(boolean slim) throws java.io.IOException {
        return Files.readAllBytes(Path.of("../gradle/build-logic/src/cpmComposition/fixtures/model-conversion/"
                + (slim ? "slim" : "classic") + "-embedded.cpmmodel"));
    }

    private static final class BaseOnlyConverter implements com.naocraftlab.skins.client.CpmModelFileConversion {
        private final com.naocraftlab.skins.client.SkinModel required;
        private int closes,conversions,admissions;
        private int[] output;
        private Reason failure;
        private boolean automatic;
        private boolean publication, sharedWire, repeatedWire;
        private int publicationSteps = 1;
        private int links, linkFailures, publicationCloses, failLinkAt;
        private final Map<SkinModel, int[]> outputs = new java.util.EnumMap<>(SkinModel.class);
        private Runnable nativeWork = () -> { };
        BaseOnlyConverter(com.naocraftlab.skins.client.SkinModel required){this.required=required;}
        public Availability availability(){return Availability.READY;}
        public AdmittedModel admitFile(Path path) { throw new AssertionError("Original path must not be reopened"); }
        public AdmittedModel admit(java.io.InputStream input) throws Failure {
            admissions++;
            assertTrue(input instanceof com.naocraftlab.skins.client.FrozenImportInput.Reader);
            if (((com.naocraftlab.skins.client.ImportInput.Reader) input).kind() == com.naocraftlab.skins.client.ImportInput.Kind.PROJECT)
                throw new Failure(Reason.MODEL_UNSUPPORTED);
            try { assertEquals(83, input.read()); }
            catch (java.io.IOException problem) { throw new Failure(Reason.IO_FAILED); }
            if (failure != null) throw new Failure(failure);
            return new AdmittedModel(){
            public Optional<com.naocraftlab.skins.client.SkinModel> requiredVariant(){return Optional.ofNullable(required);}
            public Optional<Raster> automaticBase(SkinModel preferred){nativeWork.run();return automatic ? Optional.of(new Raster(required == null ? preferred : required, outputs.getOrDefault(required == null ? preferred : required, output))) : Optional.empty();}
            public void close(){closes++;}
        };}
        public Preparation prepare(AdmittedModel model,Raster base,long revision) throws Failure {
            nativeWork.run();
            if (failure != null) throw new Failure(failure);
            conversions++;
            if (publication) return publication(base, revision, 0);
            if (output != null) return new Inline(new Raster(required == null ? base.variant() : required, outputs.getOrDefault(base.variant(), output)));
            throw new AssertionError("Mismatch must stop before converter");
        }
        private Publication publication(Raster base, long revision, int step) {
            return new Publication() {
                private boolean closed;
                public SkinModel variant() { return base.variant(); }
                public long revision() { return revision; }
                public byte[] wire() { return new byte[]{(byte) (repeatedWire ? 1 : step + 1), (byte) (sharedWire ? 0 : base.variant().ordinal())}; }
                public Preparation link(String id) throws Failure {
                    assertFalse(closed);
                    assertEquals("fixture-id", id);
                    links++;
                    if (linkFailures-- > 0 || links == failLinkAt) throw new Failure(Reason.CONVERSION_FAILED);
                    return step + 1 < publicationSteps ? publication(base, revision, step + 1)
                            : new Inline(new Raster(base.variant(), outputs.getOrDefault(base.variant(), output)));
                }
                public void close() { assertFalse(closed); closed = true; publicationCloses++; }
            };
        }
    }

    @Test
    void invalidFileImportShowsDismissibleFormatFeedbackAndKeepsEditorClosed(@TempDir Path directory) throws Exception {
        Path selected = Files.write(directory.resolve("invalid.png"), new byte[]{1, 2, 3});
        ClientRuntime runtime = runtime(new FakeOperations(),
                () -> CompletableFuture.completedFuture(Optional.of(selected)));
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");
        runtime.dispatchWidget("add.file.choose");
        assertTrue(runtime.snapshot().editor().isEmpty());
        assertEquals("nclskins.error.png", runtime.snapshot().status().key());
        runtime.view(854, 480, 0, 0);
        assertEquals("nclskins.error.png", runtime.snapshot().status().key());
        runtime.pointerPressed(850, 470, 0);
        assertEquals(UiMessage.Severity.INFO, runtime.snapshot().status().severity());
        Files.delete(selected);
        runtime.dispatchWidget("add.file.choose");
        assertEquals("nclskins.add_source.file_io_error", runtime.snapshot().status().key());
        runtime.dispatchText("add.player.input", "Player");
        assertEquals(UiMessage.Severity.INFO, runtime.snapshot().status().severity());
    }

    @Test
    void fileImportDetectsVariantAndManualOverrideRemainsAvailable(@TempDir Path directory)
            throws Exception {
        FakeOperations operations = new FakeOperations();
        Path selected = Files.write(directory.resolve("slim.png"), opaqueSkinPng(true));
        FilePicker picker = () -> CompletableFuture.completedFuture(Optional.of(selected));
        ClientRuntime runtime = runtime(operations, picker);
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");

        runtime.dispatchWidget("add.file.choose");

        PresetEditorModel editor = runtime.snapshot().editor().orElseThrow();
        assertEquals(SkinVariant.SLIM, editor.variant());
        assertEquals(SkinVariant.SLIM, editor.saveRequest().initialVariant());
        assertEquals(Optional.of(SkinVariant.SLIM), operations.uiPreferences.preferredSkinVariant());
        assertTrue(runtime.view(854, 480, 0, 0).widget("editor.model_choice.classic").orElseThrow().enabled());
        assertTrue(runtime.view(854, 480, 0, 0).texts().stream()
                .noneMatch(text -> text.id().equals("editor.status")));

        runtime.dispatchWidget("editor.model_choice.classic");

        assertEquals(SkinVariant.CLASSIC, runtime.snapshot().editor().orElseThrow().variant());
        assertEquals(Optional.of(SkinVariant.CLASSIC), operations.uiPreferences.preferredSkinVariant());
        runtime.dispatchText("editor.name", "Detected file");
        runtime.dispatchWidget("editor.save");
        AppearancePreset saved = findPreset(
                runtime.snapshot(), runtime.snapshot().selectedPresetId().orElseThrow());
        assertEquals(
                SkinVariant.CLASSIC,
                findSkin(runtime.snapshot(), saved.skin().assetId()).variant());
    }

    @Test
    void cancelledFilePickerDoesNotChangePreferredVariant() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, CANCELLED_PICKER);
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");

        runtime.dispatchWidget("add.file.choose");

        assertTrue(runtime.snapshot().editor().isEmpty());
        assertTrue(operations.uiPreferences.preferredSkinVariant().isEmpty());
        assertEquals(UiMessage.info("nclskins.status.cancelled"), runtime.snapshot().status());
    }

    @Test
    void unavailableModSourceOffersFolderAndBackReturnsToImportTab() {
        FakeOperations operations = new FakeOperations();
        operations.externalSourceAvailable = false;
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");

        assertTrue(runtime.view(320, 240, 0, 0).widget("add.external.mod").isPresent());
        runtime.dispatchWidget("add.external.mod");
        ViewSpec chooser = runtime.view(320, 240, 0, 0);
        assertEquals(UiMessage.info("nclskins.external_import.mod_title"), chooser.title());
        assertFalse(chooser.widget("external.source.skin_shuffle").orElseThrow().enabled());
        assertTrue(chooser.widget("external.folder.skin_shuffle").orElseThrow().enabled());
        assertEquals(ExternalImportSource.QUICK_SKIN, operations.lastExternalSource);
        assertEquals(Optional.empty(), operations.lastExternalRoot);

        runtime.dispatchWidget("external.back");
        ViewSpec restored = runtime.view(320, 240, 0, 0);
        assertEquals(UiMessage.info("nclskins.add_source.title"), restored.title());
        assertTrue(restored.widget("add.external.mod").isPresent());
    }

    @Test
    void externalImportRetriesSelectedFolderAndReturnsGallery(@TempDir Path directory) {
        FakeOperations operations = new FakeOperations();
        operations.externalSourceAvailable = false;
        FilePicker picker = new FilePicker() {
            @Override
            public CompletableFuture<Optional<Path>> chooseSkinPng() {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<Optional<Path>> chooseDirectory() {
                return CompletableFuture.completedFuture(Optional.of(directory));
            }
        };
        ClientRuntime runtime = runtime(operations, picker);
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");
        runtime.dispatchWidget("add.external.launcher");
        operations.externalSourceAvailable = true;
        runtime.dispatchWidget("external.folder.prism_launcher");
        runtime.dispatchWidget("external.source.prism_launcher");
        assertEquals("external_review", runtime.view(320, 240, 0, 0).screenId());
        assertEquals(0, operations.externalCommitCalls);
        runtime.dispatchWidget("external.review.cancel");
        assertEquals("external_chooser", runtime.view(320, 240, 0, 0).screenId());
        assertEquals(0, operations.externalCommitCalls);
        runtime.dispatchWidget("external.source.prism_launcher");
        operations.externalImportResult = new ClientOperations.ExternalImportResult(
                operations.account, 2, 3, 1, 1);
        runtime.dispatchWidget("external.review.commit");

        assertEquals("gallery", runtime.view(320, 240, 0, 0).screenId());
        assertEquals(
                UiMessage.success("nclskins.external_import.complete", 2, 1, 3, 1),
                runtime.snapshot().status());
        assertEquals(Optional.of(directory.toAbsolutePath().normalize()),
                operations.lastExternalRoot.map(path -> path.toAbsolutePath().normalize()));
        assertEquals(1, operations.externalCommitCalls);
    }

    @Test
    void noValidExternalLooksExplainsCurrentAccountOnlyForAccountScopedLaunchers() {
        FakeOperations accountScopedOperations = new FakeOperations();
        ClientRuntime accountScopedRuntime = runtime(
                accountScopedOperations, Runnable::run, Optional.empty());
        accountScopedRuntime.initialize();
        accountScopedRuntime.dispatchWidget("gallery.add");
        accountScopedRuntime.dispatchWidget("add.tab.file");
        accountScopedRuntime.dispatchWidget("add.external.launcher");
        accountScopedOperations.externalImportFailure = new ExternalImportException(
                ExternalImportException.Code.NO_VALID_APPEARANCES, "empty");

        accountScopedRuntime.dispatchWidget("external.source.curseforge_app");

        assertEquals(
                UiMessage.error("nclskins.external_import.no_valid_current_account"),
                accountScopedRuntime.snapshot().status());

        FakeOperations sharedLibraryOperations = new FakeOperations();
        ClientRuntime sharedLibraryRuntime = runtime(
                sharedLibraryOperations, Runnable::run, Optional.empty());
        sharedLibraryRuntime.initialize();
        sharedLibraryRuntime.dispatchWidget("gallery.add");
        sharedLibraryRuntime.dispatchWidget("add.tab.file");
        sharedLibraryRuntime.dispatchWidget("add.external.launcher");
        sharedLibraryOperations.externalImportFailure = new ExternalImportException(
                ExternalImportException.Code.NO_VALID_APPEARANCES, "empty");

        sharedLibraryRuntime.dispatchWidget("external.source.prism_launcher");

        assertEquals(
                UiMessage.error("nclskins.external_import.no_valid"),
                sharedLibraryRuntime.snapshot().status());
    }

    @Test
    void externalChooserWarningDoesNotLeakToImportTabAfterBackOrEscape() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");
        runtime.dispatchWidget("add.external.launcher");
        operations.externalImportFailure = new ExternalImportException(
                ExternalImportException.Code.NO_VALID_APPEARANCES, "empty");
        runtime.dispatchWidget("external.source.curseforge_app");
        assertTrue(runtime.view(320, 240, 0, 0).texts().stream()
                .anyMatch(text -> text.id().equals("external.status")));

        runtime.dispatchWidget("external.back");

        ViewSpec afterBack = runtime.view(320, 240, 0, 0);
        assertEquals("add_source", afterBack.screenId());
        assertTrue(afterBack.texts().stream()
                .noneMatch(text -> text.id().equals("add.import.status")));
        assertEquals(
                UiMessage.info("nclskins.external_import.choose_source"),
                runtime.snapshot().status());

        operations.externalImportFailure = null;
        runtime.dispatchWidget("add.external.launcher");
        operations.externalImportFailure = new ExternalImportException(
                ExternalImportException.Code.NO_VALID_APPEARANCES, "empty");
        runtime.dispatchWidget("external.source.curseforge_app");

        runtime.escapePressed();

        ViewSpec afterEscape = runtime.view(320, 240, 0, 0);
        assertEquals("add_source", afterEscape.screenId());
        assertTrue(afterEscape.texts().stream()
                .noneMatch(text -> text.id().equals("add.import.status")));
        assertEquals(
                UiMessage.info("nclskins.external_import.choose_source"),
                runtime.snapshot().status());
    }

    @Test
    void nextExternalChooserSourceAndFolderActionsReplaceWarningImmediately() {
        CompletableFuture<Optional<Path>> folderSelection = new CompletableFuture<>();
        FilePicker picker = new FilePicker() {
            @Override
            public CompletableFuture<Optional<Path>> chooseSkinPng() {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<Optional<Path>> chooseSqliteDatabase() {
                return folderSelection;
            }
        };
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, picker);
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");
        runtime.dispatchWidget("add.external.launcher");
        operations.externalImportFailure = new ExternalImportException(
                ExternalImportException.Code.NO_VALID_APPEARANCES, "empty");
        runtime.dispatchWidget("external.source.curseforge_app");

        operations.externalImportFailure = null;
        runtime.dispatchWidget("external.source.modrinth_app");

        assertEquals("external_review", runtime.view(320, 240, 0, 0).screenId());
        assertEquals(
                UiMessage.info("nclskins.external_import.review_ready"),
                runtime.snapshot().status());

        runtime.dispatchWidget("external.review.cancel");
        operations.externalImportFailure = new ExternalImportException(
                ExternalImportException.Code.NO_VALID_APPEARANCES, "empty");
        runtime.dispatchWidget("external.source.curseforge_app");
        operations.externalImportFailure = null;

        runtime.dispatchWidget("external.folder.curseforge_app");

        ViewSpec choosingFolder = runtime.view(320, 240, 0, 0);
        assertEquals("external_chooser", choosingFolder.screenId());
        assertTrue(choosingFolder.texts().stream()
                .noneMatch(text -> text.id().equals("external.status")));
        assertEquals(
                UiMessage.info("nclskins.external_import.choose_folder_status"),
                runtime.snapshot().status());
        folderSelection.complete(Optional.empty());
        assertEquals(
                UiMessage.info("nclskins.external_import.choose_source"),
                runtime.snapshot().status());
    }

    @Test
    void externalReviewBulkDisclosureIsTransientAndPreservesSelection() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");
        runtime.dispatchWidget("add.external.mod");
        runtime.dispatchWidget("external.source.skin_shuffle");
        ViewSpec expandedView = runtime.view(320, 240, 0, 0);
        assertTrue(expandedView.widget("external.review.card:candidate-0")
                .orElseThrow().selectableCardSelected());
        assertEquals(UiMessage.info("nclskins.collection.collapse_all"),
                expandedView.widget("external.review.disclosure").orElseThrow().label());

        runtime.dispatchWidget("external.review.disclosure");

        ViewSpec collapsed = runtime.view(320, 240, 0, 0);
        assertTrue(collapsed.widget("external.review.card:candidate-0").isEmpty());
        assertTrue(collapsed.widget("external.review.commit").orElseThrow().enabled());
        assertEquals(0.0, collapsed.scrollSurface("external.review").orElseThrow().offsetPixels());
        assertEquals(UiMessage.info("nclskins.collection.expand_all"),
                collapsed.widget("external.review.disclosure").orElseThrow().label());

        runtime.dispatchWidget("external.review.disclosure");
        ViewSpec reopened = runtime.view(320, 240, 0, 0);
        assertTrue(reopened.widget("external.review.card:candidate-0")
                .orElseThrow().selectableCardSelected());
    }

    @Test
    void keyboardExternalBulkDisclosureKeepsFocusOnItsStableId() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");
        runtime.dispatchWidget("add.external.mod");
        runtime.dispatchWidget("external.source.skin_shuffle");
        runtime.view(320, 240, 0, 0);

        runtime.dispatchWidget(
                "external.review.disclosure", false, InteractionOrigin.POINTER);
        assertTrue(runtime.view(320, 240, 0, 0).focusRequest().isEmpty());

        runtime.dispatchWidget(
                "external.review.disclosure", false, InteractionOrigin.KEYBOARD);

        assertEquals(Optional.of("external.review.disclosure"),
                runtime.view(320, 240, 0, 0).focusRequest().map(ViewSpec.FocusRequest::widgetId));
    }

    @Test
    void missingSqliteDependencyBlocksImportAndDatabasePicker() {
        FakeOperations operations = new FakeOperations();
        operations.externalSourceProbes.put(
                ExternalImportSource.MINECRAFT_LAUNCHER, ExternalImportProbe.UNAVAILABLE);
        operations.externalSourceProbes.put(
                ExternalImportSource.CURSEFORGE_APP, ExternalImportProbe.DEPENDENCY_MISSING);
        operations.externalSourceProbes.put(
                ExternalImportSource.MODRINTH_APP, ExternalImportProbe.DEPENDENCY_MISSING);
        operations.externalSourceProbes.put(
                ExternalImportSource.PRISM_LAUNCHER, ExternalImportProbe.UNAVAILABLE);
        java.util.concurrent.atomic.AtomicInteger pickerCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        FilePicker picker = new FilePicker() {
            @Override
            public CompletableFuture<Optional<Path>> chooseSkinPng() {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<Optional<Path>> chooseSqliteDatabase() {
                pickerCalls.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.empty());
            }
        };
        ClientRuntime runtime = runtime(operations, picker);
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");
        runtime.dispatchWidget("add.external.launcher");

        ViewSpec chooser = runtime.view(240, 240, 0, 0);
        assertFalse(chooser.widget("external.source.curseforge_app").orElseThrow().enabled());
        assertFalse(chooser.widget("external.folder.modrinth_app").orElseThrow().enabled());
        runtime.dispatchWidget("external.folder.modrinth_app");
        runtime.dispatchWidget("external.source.curseforge_app");

        assertEquals(0, pickerCalls.get());
        assertEquals("external_chooser", runtime.view(240, 240, 0, 0).screenId());
    }

    @Test
    void leavingExternalImportFencesLateWorkerResult() {
        FakeOperations operations = new FakeOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());
        runtime.initialize();
        worker.runFirst();
        runtime.dispatchWidget("gallery.add");
        worker.runFirst();
        runtime.dispatchWidget("add.tab.file");
        runtime.dispatchWidget("add.external.launcher");
        assertTrue(runtime.snapshot().busy());
        runtime.dispatchWidget("external.back");
        assertFalse(runtime.snapshot().busy());
        assertEquals(UiMessage.info("nclskins.status.cancelled"), runtime.snapshot().status());

        worker.runFirst();

        ViewSpec restored = runtime.view(320, 240, 0, 0);
        assertEquals(UiMessage.info("nclskins.add_source.title"), restored.title());
        assertTrue(restored.widget("add.external.launcher").isPresent());
        assertEquals(UiMessage.info("nclskins.status.cancelled"), runtime.snapshot().status());
    }

    @Test
    void urlImportUsesDetectedVariantAndPreferenceSurvivesEditorCancel() {
        FakeOperations operations = new FakeOperations();
        operations.urlImportVariant = SkinVariant.SLIM;
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");
        runtime.dispatchText("add.url.input", "https://example.test/slim.png");

        runtime.dispatchWidget("add.url.load");

        assertEquals(SkinVariant.SLIM, runtime.snapshot().editor().orElseThrow().variant());
        assertEquals(Optional.of(SkinVariant.SLIM), operations.uiPreferences.preferredSkinVariant());
        assertTrue(runtime.view(320, 240, 0, 0).texts().stream()
                .noneMatch(text -> text.id().equals("editor.status")));
        runtime.dispatchWidget("editor.cancel");
        assertEquals(
                SkinVariant.SLIM,
                runtime.snapshot().addSource().orElseThrow().preferredVariant());
    }

    @Test
    void pixelGalleryRangeReclampsAcrossViewportExpansionLikeTheCapeRange() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        operations.ownedCapes = capeInventory(5);
        operations.session = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                TestFixtures.validSession().sessionIdentity(),
                null,
                (SessionFailureContext) null,
                "offline");
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(320, 240, 0, 0);

        runtime.pointerScrolled(160, 100, 0.0, -100.0);
        settleScroll(runtime);
        assertEquals(370, runtime.snapshot().galleryOffset());

        runtime.view(854, 480, 0, 0);
        runtime.tick();
        assertEquals(370, runtime.snapshot().galleryOffset());
        runtime.view(320, 240, 0, 0);
        settleScroll(runtime);
        assertEquals(370, runtime.snapshot().galleryOffset(),
                "returning to a narrower viewport must not resurrect a stale pixel target");

        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        runtime.dispatchWidget("editor.tab.cape");
        PresetEditorModel editorModel = runtime.snapshot().editor().orElseThrow();
        int narrowCapeMaximum = editorModel.maximumCapeScroll(320, 240);
        int expandedCapeMaximum = editorModel.maximumCapeScroll(854, 480);
        assertTrue(expandedCapeMaximum < narrowCapeMaximum);
        ViewSpec editor = runtime.view(320, 240, 0, 0);
        Bounds capeViewport = editor.clipRegions().stream()
                .filter(region -> region.id().equals("editor.capes"))
                .findFirst()
                .orElseThrow()
                .bounds();
        runtime.pointerScrolled(
                capeViewport.x() + capeViewport.width() / 2.0,
                capeViewport.y() + capeViewport.height() / 2.0,
                0.0,
                -0.5);
        assertEquals(16, runtime.view(320, 240, 0, 0).scrollbar().orElseThrow().offset(),
                "cape wheel movement must apply the direct half-step magnitude");

        runtime.pointerScrolled(
                capeViewport.x() + capeViewport.width() / 2.0,
                capeViewport.y() + capeViewport.height() / 2.0,
                0.0,
                -100.0);
        assertEquals(narrowCapeMaximum,
                runtime.view(320, 240, 0, 0).scrollbar().orElseThrow().offset());

        runtime.view(854, 480, 0, 0);
        runtime.tick();
        ViewSpec expandedEditor = runtime.view(854, 480, 0, 0);
        if (expandedCapeMaximum == 0) {
            assertTrue(expandedEditor.scrollbar().isEmpty());
        } else {
            assertEquals(expandedCapeMaximum, expandedEditor.scrollbar().orElseThrow().offset());
        }
        runtime.view(320, 240, 0, 0);
        settleScroll(runtime);
        assertEquals(expandedCapeMaximum,
                runtime.view(320, 240, 0, 0).scrollbar().orElseThrow().offset(),
                "restoring the narrow viewport must not resurrect the stale cape target");
    }

    @Test
    void pixelGalleryRangeReclampsWhenCardWidthChangesWithHeight() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(5);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.view(320, 360, 0, 0);

        runtime.pointerScrolled(160, 160, 0.0, -100.0);
        settleScroll(runtime);
        assertEquals(910, runtime.snapshot().galleryOffset());

        runtime.view(320, 240, 0, 0);
        runtime.tick();
        assertEquals(370, runtime.snapshot().galleryOffset());

        runtime.view(320, 360, 0, 0);
        settleScroll(runtime);
        assertEquals(
                370,
                runtime.snapshot().galleryOffset(),
                "restoring the taller viewport must not resurrect the stale pixel target");
    }

    @Test
    void catalogWheelAppliesDirectBoundedPixelStepsWithoutPageJumps() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());

        runtime.initialize();
        runtime.view(320, 240, 0, 0);
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.catalog");
        runtime.view(320, 240, 160, 100);

        runtime.pointerScrolled(160, 40, 0.0, -1.0);
        assertEquals(0, runtime.snapshot().addSource().orElseThrow().scrollOffset(),
                "the search/filter row is outside the vanilla catalog scroll viewport");

        runtime.pointerScrolled(160, 100, 0.0, -0.5);
        assertEquals(16, runtime.snapshot().addSource().orElseThrow().scrollOffset(),
                "a half wheel step must move the catalog immediately by half the normal delta");
        runtime.tick();
        int first = runtime.snapshot().addSource().orElseThrow().scrollOffset();
        assertEquals(16, first, "ticks must not ease or otherwise change direct wheel movement");

        runtime.pointerScrolled(160, 100, 0.0, -1.0);
        assertEquals(48, runtime.snapshot().addSource().orElseThrow().scrollOffset(),
                "successive wheel steps must accumulate at event time");
        runtime.tick();
        assertEquals(48, runtime.snapshot().addSource().orElseThrow().scrollOffset());
    }

    @Test
    void editorPreviewClickDoesNotChangeLayersAndCycleButtonsHonorShiftDirection() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());

        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");

        TestFixtures.confirmOrdinaryDisplay(runtime);
        PresetEditorModel before = runtime.snapshot().editor().orElseThrow();
        runtime.pointerPressed(215, 150, 0);
        runtime.pointerReleased(215, 150, 0);

        PresetEditorModel afterClick = runtime.snapshot().editor().orElseThrow();
        assertEquals(
                before.preview().outerLayerVisibility(),
                afterClick.preview().outerLayerVisibility());

        runtime.dispatchWidget("editor.outer_layer.body", true);

        PresetEditorModel afterReverse = runtime.snapshot().editor().orElseThrow();
        assertTrue(afterReverse.preview().outerLayerVisibility().visible(OuterLayerPart.BODY));
        assertFalse(afterReverse.preview().outerLayerVisibility().visible(OuterLayerPart.LEFT_ARM));
        assertTrue(afterReverse.preview().outerLayerVisibility().visible(OuterLayerPart.RIGHT_ARM));

        runtime.dispatchWidget("editor.outer_layer.body");
        PresetEditorModel wrapped = runtime.snapshot().editor().orElseThrow();
        assertTrue(wrapped.preview().outerLayerVisibility().visible(OuterLayerPart.BODY));
        assertTrue(wrapped.preview().outerLayerVisibility().visible(OuterLayerPart.LEFT_ARM));
        assertTrue(wrapped.preview().outerLayerVisibility().visible(OuterLayerPart.RIGHT_ARM));

        runtime.dispatchWidget("editor.outer_layer.body");
        PresetEditorModel afterForward = runtime.snapshot().editor().orElseThrow();
        assertFalse(afterForward.preview().outerLayerVisibility().visible(OuterLayerPart.BODY));
        assertFalse(afterForward.preview().outerLayerVisibility().visible(OuterLayerPart.LEFT_ARM));
        assertFalse(afterForward.preview().outerLayerVisibility().visible(OuterLayerPart.RIGHT_ARM));
    }

    @Test
    void closeSupersedesAnUnpublishedWorkerResultAndReopenStartsFreshGeneration() {
        FakeOperations operations = new FakeOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker, Optional.empty());

        runtime.initialize();
        assertEquals(ClientSnapshot.Lifecycle.INITIALIZING, runtime.snapshot().lifecycle());
        runtime.closeScreen();
        worker.runFirst();
        assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
        assertTrue(runtime.snapshot().account().isEmpty());

        long closedGeneration = runtime.snapshot().generation();
        runtime.reopen();
        worker.runFirst();
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        assertTrue(runtime.snapshot().generation() > closedGeneration);
    }

    @Test
    void terminalCloseMaterializesClosedWithoutReenteringScreenSubscribers() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        AtomicInteger terminalCallbacks = new AtomicInteger();
        runtime.subscribe(snapshot -> {
            if (snapshot.lifecycle() == ClientSnapshot.Lifecycle.CLOSED) {
                terminalCallbacks.incrementAndGet();
            }
        });
        runtime.initialize();

        runtime.close();

        assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
        assertTrue(runtime.closed());
        assertEquals(0, terminalCallbacks.get());
        assertThrows(IllegalStateException.class, () -> runtime.view(320, 240, 0, 0));
    }

    @Test
    void ordinaryScreenCloseStillPublishesClosedAndCanReopen() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        AtomicInteger closedCallbacks = new AtomicInteger();
        runtime.subscribe(snapshot -> {
            if (snapshot.lifecycle() == ClientSnapshot.Lifecycle.CLOSED) {
                closedCallbacks.incrementAndGet();
            }
        });
        runtime.initialize();

        runtime.closeScreen();

        assertEquals(1, closedCallbacks.get());
        assertFalse(runtime.closed());
        runtime.reopen();
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
    }

    @Test
    void offlineRateLimitedApplyStaysEnabledAndPublishesLocalPendingBeforeCheckpoint() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.session = session(SessionStatus.OFFLINE_OR_INVALID);
        operations.rateLimited = true;
        operations.localFirst = true;
        QueuedExecutor worker = new QueuedExecutor();
        AtomicInteger notifications = new AtomicInteger();
        AtomicReference<ExpectedAppearance> installed = new AtomicReference<>();
        AppearanceRefreshCoordinator<String> refresh = new AppearanceRefreshCoordinator<>(
                CLIENT,
                expected -> CompletableFuture.completedFuture(Optional.of(
                        new SignedProfileResolver.ResolvedProfile<>(
                                expected.profileId(), expected, "local"))),
                resolved -> {
                    installed.set(resolved.expectedAppearance());
                    return PlayerAppearanceSink.ApplyResult.UPDATED;
                },
                DiagnosticSinks.discarding());
        ClientRuntime runtime = runtime(
                operations,
                worker,
                Optional.of(refresh),
                Optional.of(notifications::incrementAndGet));

        runtime.initialize();
        worker.runFirst();
        UUID presetId = operations.account.presets().get(0).id();
        ViewSpec gallery = runtime.view(854, 480, 0, 0);

        assertTrue(gallery.widget("gallery.preset." + presetId + ".apply")
                .orElseThrow()
                .enabled());

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");
        worker.runFirst();

        ClientSnapshot pending = runtime.snapshot();
        assertEquals(Optional.of(presetId), pending.activePresetId());
        assertEquals(1, pending.intentRevision());
        assertEquals(AppearanceSyncStatus.PENDING, pending.syncStatus());
        assertFalse(pending.syncInProgress());
        assertTrue(installed.get().skinTexture().isEmpty());
        assertEquals(Optional.of("a".repeat(64)), installed.get().localSkinSha256());
        assertEquals(0, operations.applyCalls);
        assertEquals(0, notifications.get());

        assertEquals(0, worker.size());
        assertEquals(0, operations.reconciliationCalls);
        assertEquals(AppearanceSyncStatus.PENDING, runtime.snapshot().syncStatus());
        assertFalse(runtime.snapshot().syncInProgress());
        assertEquals(0, operations.applyCalls);
        assertEquals(0, notifications.get());
    }

    @Test
    void rateLimitCountdownCoalescesLatestIntentAndRecoversAfterClosedGallery() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(3);
        operations.localFirst = true;
        operations.reconcileWithOutcome = true;
        operations.rateLimited = true;
        operations.rateLimitRemaining = Duration.ofSeconds(60);
        QueuedExecutor reconciliation = new QueuedExecutor();
        AtomicInteger notifications = new AtomicInteger();
        ClientRuntime runtime = new ClientRuntime(new com.naocraftlab.skins.runtime.DefaultRuntimeServices(),
                operations,
                CLIENT,
                CANCELLED_PICKER,
                Runnable::run,
                reconciliation,
                Runnable::run,
                TEXT,
                Optional.empty(),
                Optional.of(notifications::incrementAndGet),
                DiagnosticSinks.discarding());
        runtime.initialize();
        runtime.tick();

        UUID first = operations.account.presets().get(0).id();
        UUID second = operations.account.presets().get(1).id();
        UUID third = operations.account.presets().get(2).id();
        runtime.dispatchWidget("gallery.preset." + first + ".apply");
        runtime.dispatchWidget("gallery.preset." + second + ".apply");
        runtime.dispatchWidget("gallery.preset." + third + ".apply");

        assertEquals(0, reconciliation.size());
        assertEquals(Optional.of(third), runtime.snapshot().activePresetId());
        assertEquals(3, runtime.snapshot().intentRevision());
        assertEquals(1.0, runtime.snapshot().rateLimitProgress().orElseThrow().fraction());
        ViewSpec latestGallery = runtime.view(854, 480, 0, 0);
        assertTrue(latestGallery.progressDecorations().stream().anyMatch(progress ->
                progress.ownerWidgetId().equals("gallery.preset." + third + ".apply")));
        assertTrue(latestGallery.widget("gallery.preset." + third + ".apply").orElseThrow().hint().isPresent());

        operations.rateLimitRemaining = Duration.ofSeconds(30);
        runtime.tick();
        assertEquals(0.5, runtime.snapshot().rateLimitProgress().orElseThrow().fraction());

        operations.rateLimitRemaining = Duration.ofSeconds(90);
        runtime.tick();
        ClientSnapshot.RateLimitProgress extended =
                runtime.snapshot().rateLimitProgress().orElseThrow();
        assertEquals(Duration.ofSeconds(90), extended.remaining());
        assertEquals(Duration.ofSeconds(90), extended.total());
        assertEquals(1.0, extended.fraction());

        operations.rateLimitRemaining = Duration.ofSeconds(45);
        runtime.tick();
        assertEquals(0.5, runtime.snapshot().rateLimitProgress().orElseThrow().fraction());

        runtime.dispatchWidget("gallery.preset." + third + ".apply");
        assertEquals(3, runtime.snapshot().intentRevision());
        assertEquals(0, reconciliation.size());

        runtime.closeScreen();
        operations.rateLimited = false;
        runtime.tick();
        assertEquals(1, reconciliation.size());

        reconciliation.runFirst();
        assertEquals(
                List.of(Trigger.RATE_LIMIT_EXPIRED),
                operations.reconciliationTriggers);
        assertEquals(3, operations.reconciliationKeys.get(0).intentRevision());
        assertEquals(1, notifications.get());
    }

    @Test
    void retryCapeUsesExplicitDurableReconciliationAndNeverLegacyCapeMutation() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.localFirst = true;
        operations.reconcileWithOutcome = true;
        operations.result = MutationResult.PARTIAL;
        operations.recovery = Set.of(RecoveryAction.RETRY_CAPE);
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(1, operations.reconciliationCalls);
        assertTrue(runtime.snapshot().recoveryActions().contains(RecoveryAction.RETRY_CAPE));

        operations.result = MutationResult.APPLIED;
        operations.recovery = Set.of();
        runtime.dispatchWidget("gallery.retry_cape");

        assertEquals(2, operations.reconciliationCalls);
        assertEquals(0, operations.retryCapeCalls);
        assertEquals(
                List.of(
                        Trigger.LOCAL_INTENT,
                        Trigger.EXPLICIT_RETRY),
                operations.reconciliationTriggers);
        assertEquals(AppearanceSyncStatus.OFFICIAL, runtime.snapshot().syncStatus());
    }

    @Test
    void queuedExplicitTriggerForOldRevisionCannotRecoverNewerRevision() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(2);
        operations.localFirst = true;
        QueuedExecutor reconciliation = new QueuedExecutor();
        ClientRuntime runtime = new ClientRuntime(new com.naocraftlab.skins.runtime.DefaultRuntimeServices(),
                operations,
                CLIENT,
                CANCELLED_PICKER,
                Runnable::run,
                reconciliation,
                Runnable::run,
                TEXT,
                Optional.empty(),
                Optional.empty(),
                DiagnosticSinks.discarding());
        runtime.initialize();
        UUID first = operations.account.presets().get(0).id();
        UUID second = operations.account.presets().get(1).id();

        runtime.dispatchWidget("gallery.preset." + first + ".apply");
        runtime.dispatchWidget("gallery.retry_cape");
        runtime.dispatchWidget("gallery.preset." + second + ".apply");

        assertEquals(1, reconciliation.size());
        assertEquals(2, runtime.snapshot().intentRevision());
        assertTrue(runtime.snapshot().syncInProgress());

        reconciliation.runFirst();

        assertEquals(1, operations.reconciliationCalls);
        assertEquals(
                List.of(Trigger.LOCAL_INTENT),
                operations.reconciliationTriggers);
        assertEquals(
                List.of(new ClientOperations.ReconciliationKey(
                        TestFixtures.ACCOUNT_ID, 2)),
                operations.reconciliationKeys);
        assertEquals(0, operations.retryCapeCalls);
        assertEquals(Optional.of(second), runtime.snapshot().activePresetId());
        assertEquals(2, runtime.snapshot().intentRevision());
        assertFalse(runtime.snapshot().syncInProgress());
    }

    @Test
    void confirmedReconciliationNotifiesServerExactlyOnceAndUnknownOrNoOpDoNotNotify() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.localFirst = true;
        operations.reconcileWithOutcome = true;
        AtomicInteger notifications = new AtomicInteger();
        ClientRuntime runtime = runtime(
                operations,
                Runnable::run,
                Optional.empty(),
                Optional.of(notifications::incrementAndGet));
        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();

        operations.remoteAppearanceImpact = RemoteAppearanceImpact.CONFIRMED_CHANGED;
        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(1, notifications.get());
        assertEquals(0, operations.applyCalls);

        operations.result = MutationResult.UNKNOWN;
        operations.remoteAppearanceImpact = RemoteAppearanceImpact.UNCERTAIN;
        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(1, notifications.get());

        operations.result = MutationResult.APPLIED;
        operations.remoteAppearanceImpact = RemoteAppearanceImpact.NONE;
        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(1, notifications.get());
    }

    @Test
    void confirmedReconciliationStillNotifiesAfterGalleryCloses() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.localFirst = true;
        operations.reconcileWithOutcome = true;
        operations.remoteAppearanceImpact = RemoteAppearanceImpact.CONFIRMED_CHANGED;
        QueuedExecutor worker = new QueuedExecutor();
        AtomicInteger notifications = new AtomicInteger();
        ClientRuntime runtime = runtime(
                operations,
                worker,
                Optional.empty(),
                Optional.of(notifications::incrementAndGet));
        runtime.initialize();
        worker.runFirst();
        UUID presetId = operations.account.presets().get(0).id();

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");
        worker.runFirst();
        runtime.closeScreen();
        worker.runFirst();

        assertEquals(1, notifications.get());
        assertEquals(ClientSnapshot.Lifecycle.CLOSED, runtime.snapshot().lifecycle());
        assertEquals(0, operations.applyCalls);
    }

    @Test
    void postMutationLocalFailureStillNotifiesServerWithoutPublishingOutcomeData() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.localFirst = true;
        operations.settlementFailureImpact = RemoteAppearanceImpact.CONFIRMED_CHANGED;
        AtomicInteger notifications = new AtomicInteger();
        ClientRuntime runtime = runtime(
                operations,
                Runnable::run,
                Optional.empty(),
                Optional.of(notifications::incrementAndGet));
        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(1, notifications.get());
        assertEquals(AppearanceSyncStatus.OFFICIAL, runtime.snapshot().syncStatus());
        assertTrue(runtime.snapshot().lastMutation().isEmpty());
        assertFalse(runtime.snapshot().busy());
    }

    @Test
    void postNoOpLocalFailureDoesNotNotifyServer() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.localFirst = true;
        operations.settlementFailureImpact = RemoteAppearanceImpact.NONE;
        AtomicInteger notifications = new AtomicInteger();
        ClientRuntime runtime = runtime(
                operations,
                Runnable::run,
                Optional.empty(),
                Optional.of(notifications::incrementAndGet));
        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(0, notifications.get());
        assertEquals(AppearanceSyncStatus.OFFICIAL, runtime.snapshot().syncStatus());
        assertTrue(runtime.snapshot().lastMutation().isEmpty());
    }

    @Test
    void postUncertainSettlementFailurePublishesUnknownWithoutNotifyingServer() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.localFirst = true;
        operations.settlementFailureImpact = RemoteAppearanceImpact.UNCERTAIN;
        AtomicInteger notifications = new AtomicInteger();
        ClientRuntime runtime = runtime(
                operations,
                Runnable::run,
                Optional.empty(),
                Optional.of(notifications::incrementAndGet));
        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(0, notifications.get());
        assertEquals(AppearanceSyncStatus.UNKNOWN, runtime.snapshot().syncStatus());
        assertTrue(runtime.snapshot().lastMutation().isEmpty());
    }

    @Test
    void settlementFailureCarrierContainsOnlyRemoteImpact() {
        RemoteMutationSettlementException failure =
                new RemoteMutationSettlementException(RemoteAppearanceImpact.CONFIRMED_CHANGED);

        assertEquals(RemoteAppearanceImpact.CONFIRMED_CHANGED, failure.remoteAppearanceImpact());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("session-token"));
        assertFalse(failure.toString().contains(TestFixtures.ACCOUNT_ID.toString()));
        assertFalse(failure.toString().contains("textures.minecraft.net"));
    }

    @Test
    void notifierFailureDoesNotInvalidateAcceptedMutation() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.remoteAppearanceImpact = RemoteAppearanceImpact.CONFIRMED_CHANGED;
        ServerAppearanceRefreshNotifier failing = () -> {
            throw new IllegalStateException("connection changed");
        };
        ClientRuntime runtime = runtime(
                operations,
                Runnable::run,
                Optional.empty(),
                Optional.of(failing));
        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(MutationResult.APPLIED, runtime.snapshot().lastMutation().orElseThrow().result());
        assertFalse(runtime.snapshot().busy());
    }

    @Test
    void notifierIsDeliveredOnlyThroughClientExecutor() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        QueuedExecutor worker = new QueuedExecutor();
        TrackingClientExecutor client = new TrackingClientExecutor();
        AtomicInteger notifications = new AtomicInteger();
        ClientRuntime runtime = new ClientRuntime(new com.naocraftlab.skins.runtime.DefaultRuntimeServices(),
                operations,
                client,
                CANCELLED_PICKER,
                worker,
                worker,
                Runnable::run,
                TEXT,
                Optional.empty(),
                Optional.of(() -> {
                    assertTrue(client.isClientThread());
                    notifications.incrementAndGet();
                }),
                DiagnosticSinks.discarding());

        runtime.initialize();
        client.runFirst();
        worker.runFirst();
        client.runFirst();
        UUID presetId = operations.account.presets().get(0).id();

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");
        client.runFirst();
        worker.runFirst();

        assertEquals(0, notifications.get());
        client.runFirst();
        assertEquals(1, notifications.get());
    }

    @Test
    void reconnectUsesLatestDurableAppearanceWithoutSessionRetryAndPreviewBytesAreCached() {
        FakeOperations operations = new FakeOperations();
        AppliedAppearance durable = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID,
                "a".repeat(64),
                SkinVariant.CLASSIC,
                Optional.empty());
        operations.durable = Optional.of(new ClientOperations.DurableAppearance(
                TestFixtures.ACCOUNT_ID,
                4,
                AppearanceSyncStatus.OFFICIAL,
                Optional.empty(),
                Optional.of(durable),
                Optional.empty()));
        AtomicInteger resolves = new AtomicInteger();
        SignedProfileResolver<String> resolver = expected -> {
            resolves.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of(
                    new SignedProfileResolver.ResolvedProfile<>(
                            expected.profileId(), expected, "profile")));
        };
        PlayerAppearanceSink<String> sink = ignored -> PlayerAppearanceSink.ApplyResult.UPDATED;
        AppearanceRefreshCoordinator<String> coordinator =
                new AppearanceRefreshCoordinator<>(
                        CLIENT, resolver, sink, DiagnosticSinks.discarding());
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.of(coordinator));

        assertEquals(
                AppearanceRefresh.Result.UPDATED,
                runtime.afterReconnect().join());
        assertEquals(1, resolves.get());
        assertEquals(0, operations.retrySessionCalls);
        assertEquals(0, operations.reconciliationCalls);

        byte[] first = runtime.loadSkinPreview(SkinReference.asset(TestFixtures.CLASSIC_ID))
                .join()
                .orElseThrow();
        first[0] = 99;
        byte[] second = runtime.loadSkinPreview(SkinReference.asset(TestFixtures.CLASSIC_ID))
                .join()
                .orElseThrow();
        assertArrayEquals(new byte[] {7, 8, 9}, second);
        assertEquals(1, operations.skinPreviewCalls);
    }

    @Test
    void reconnectCompletesLocalRebindBeforeStartingItsCheckpoint() {
        FakeOperations operations = new FakeOperations();
        operations.appearanceRevision = 5;
        operations.appearanceSyncStatus = AppearanceSyncStatus.PENDING;
        AppliedAppearance pending = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID,
                "a".repeat(64),
                SkinVariant.CLASSIC,
                Optional.empty());
        operations.durable = Optional.of(new ClientOperations.DurableAppearance(
                TestFixtures.ACCOUNT_ID,
                operations.appearanceRevision,
                operations.appearanceSyncStatus,
                Optional.empty(),
                Optional.of(pending),
                Optional.empty()));
        AtomicBoolean rebound = new AtomicBoolean();
        operations.reconciliationPrecondition = rebound::get;
        AppearanceRefreshCoordinator<String> refresh = new AppearanceRefreshCoordinator<>(
                CLIENT,
                expected -> CompletableFuture.completedFuture(Optional.of(
                        new SignedProfileResolver.ResolvedProfile<>(
                                expected.profileId(), expected, "rebound"))),
                resolved -> {
                    rebound.set(true);
                    return PlayerAppearanceSink.ApplyResult.UPDATED;
                },
                DiagnosticSinks.discarding());
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.of(refresh));

        assertEquals(AppearanceRefresh.Result.UPDATED, runtime.afterReconnect().join());

        assertTrue(rebound.get());
        assertEquals(1, operations.reconciliationCalls);
        assertFalse(operations.reconciliationBeforePrecondition);
    }

    @Test
    void reconnectRejectsOldDurableOwnerAfterSessionSwitchBeforeScheduling() {
        FakeOperations operations = new FakeOperations();
        AppliedAppearance oldLocal = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID,
                "a".repeat(64),
                SkinVariant.CLASSIC,
                Optional.empty());
        operations.durable = Optional.of(new ClientOperations.DurableAppearance(
                TestFixtures.ACCOUNT_ID,
                7,
                AppearanceSyncStatus.PENDING,
                Optional.empty(),
                Optional.of(oldLocal),
                Optional.empty()));
        QueuedExecutor worker = new QueuedExecutor();
        TrackingClientExecutor client = new TrackingClientExecutor();
        AtomicInteger localResolves = new AtomicInteger();
        AppearanceRefreshCoordinator<String> refresh = new AppearanceRefreshCoordinator<>(
                client,
                expected -> {
                    localResolves.incrementAndGet();
                    return CompletableFuture.completedFuture(Optional.empty());
                },
                ignored -> PlayerAppearanceSink.ApplyResult.UPDATED,
                DiagnosticSinks.discarding());
        ClientRuntime runtime = new ClientRuntime(new com.naocraftlab.skins.runtime.DefaultRuntimeServices(),
                operations,
                client,
                CANCELLED_PICKER,
                worker,
                worker,
                Runnable::run,
                TEXT,
                Optional.of(refresh),
                Optional.empty(),
                DiagnosticSinks.discarding());

        CompletableFuture<AppearanceRefresh.Result> reconnect =
                runtime.afterReconnect();
        client.runFirst();
        worker.runFirst();

        UUID switchedAccountId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        operations.session = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                new GameSessionTokenSource.SessionIdentity(switchedAccountId, "switched"),
                null,
                (SessionFailureContext) null,
                "session switched");
        operations.durable = Optional.of(new ClientOperations.DurableAppearance(
                switchedAccountId,
                0,
                AppearanceSyncStatus.LOCAL_ONLY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
        client.runFirst();

        assertEquals(AppearanceRefresh.Result.DEFERRED, reconnect.join());
        assertEquals(0, worker.size());
        assertEquals(0, localResolves.get());
        assertEquals(0, operations.reconciliationCalls);
        assertEquals(0, operations.applyCalls);
        assertEquals(0, operations.retrySessionCalls);
    }

    @Test
    void unresolvedLocalReconnectRunsOneReconciliationPerConnectionAndTicksNeverRetryIt() {
        FakeOperations operations = new FakeOperations();
        operations.appearanceRevision = 7;
        operations.appearanceSyncStatus = AppearanceSyncStatus.PENDING;
        AppliedAppearance pending = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID,
                "a".repeat(64),
                SkinVariant.CLASSIC,
                Optional.empty());
        operations.durable = Optional.of(new ClientOperations.DurableAppearance(
                TestFixtures.ACCOUNT_ID,
                operations.appearanceRevision,
                operations.appearanceSyncStatus,
                Optional.empty(),
                Optional.of(pending),
                Optional.empty()));
        AppearanceRefreshCoordinator<String> refresh = new AppearanceRefreshCoordinator<>(
                CLIENT,
                expected -> CompletableFuture.completedFuture(Optional.empty()),
                ignored -> PlayerAppearanceSink.ApplyResult.DEFERRED,
                DiagnosticSinks.discarding());
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.of(refresh));
        ClientApplicationHost<Object> host = new ClientApplicationHost<>(new com.naocraftlab.skins.runtime.DefaultRuntimeServices(), runtime, () -> {});
        Object connection = new Object();

        for (int tick = 0; tick < 200; tick++) {
            host.tick(connection, null, true);
        }

        assertEquals(1, operations.reconciliationCalls);

        host.tick(new Object(), null, true);

        assertEquals(2, operations.reconciliationCalls);
    }

    @Test
    void deletingActiveFinalPresetPublishesPendingAccountDefaultWithoutRemoteSignal() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.localFirst = true;
        AtomicInteger notifications = new AtomicInteger();
        AtomicReference<ExpectedAppearance> installed = new AtomicReference<>();
        SignedProfileResolver<String> resolver = expected -> CompletableFuture.completedFuture(Optional.of(
                new SignedProfileResolver.ResolvedProfile<>(
                        expected.profileId(), expected, "account-default")));
        PlayerAppearanceSink<String> sink = new PlayerAppearanceSink<>() {
            @Override
            public ApplyResult apply(SignedProfileResolver.ResolvedProfile<String> resolved) {
                installed.set(resolved.expectedAppearance());
                return ApplyResult.UPDATED;
            }

            @Override
            public ApplyResult reset(ExpectedAppearance expected) {
                installed.set(expected);
                return ApplyResult.UPDATED;
            }
        };
        ClientRuntime runtime = runtime(
                operations,
                Runnable::run,
                Optional.of(new AppearanceRefreshCoordinator<>(
                        CLIENT, resolver, sink, DiagnosticSinks.discarding())),
                Optional.of(notifications::incrementAndGet));
        runtime.initialize();
        UUID presetId = operations.account.presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        runtime.dispatchWidget("gallery.preset." + presetId + ".delete");
        runtime.dispatchWidget("gallery.preset." + presetId + ".delete_confirm");

        assertTrue(runtime.snapshot().account().orElseThrow().presets().isEmpty());
        assertTrue(runtime.snapshot().activePresetId().isEmpty());
        assertEquals(2, runtime.snapshot().intentRevision());
        assertEquals(AppearanceSyncStatus.PENDING, runtime.snapshot().syncStatus());
        assertTrue(runtime.snapshot().lastMutation().isEmpty());
        ExpectedAppearance accountDefault = installed.get();
        assertTrue(accountDefault.skinTexture().isEmpty());
        assertTrue(accountDefault.localSkinSha256().isEmpty());
        assertTrue(accountDefault.skinModel().isEmpty());
        assertEquals("nclskins.status.deleted", runtime.snapshot().status().key());
        assertEquals(0, operations.applyCalls);
        assertEquals(2, operations.reconciliationCalls);
        assertEquals(0, notifications.get());
        assertHorizontallyCentered(
                runtime.view(320, 240, 0, 0).panels().stream()
                        .filter(panel -> panel.id().equals("gallery.card.add"))
                        .findFirst()
                        .orElseThrow()
                        .bounds(),
                320);
    }

    private static void assertSessionRetryConnectingForFiveTicks(ClientRuntime runtime) {
        for (int tick = 1; tick <= 5; tick++) {
            runtime.tick();
            assertFalse(runtime.snapshot().busy(), "session retry must not own global busy");
            assertEquals(
                    ClientSnapshot.SessionActivity.RECONNECTING,
                    runtime.snapshot().sessionActivity(),
                    "session retry settled at tick " + tick);
            assertEquals(
                    UiMessage.info("nclskins.status.checking_session"),
                    runtime.snapshot().status());
            ViewSpec view = runtime.view(854, 480, 427, 180);
            assertEquals(
                    UiMessage.info("nclskins.session.connecting"),
                    view.texts().stream()
                            .filter(text -> text.id().equals("gallery.offline"))
                            .findFirst()
                            .orElseThrow()
                            .message());
            assertFalse(view.widget("gallery.retry_session").orElseThrow().enabled());
        }
    }

    private static void advanceTicks(ClientRuntime runtime, int count) {
        for (int tick = 0; tick < count; tick++) {
            runtime.tick();
        }
    }

    private static void settleScroll(ClientRuntime runtime) {
        for (int index = 0; index < 80; index++) {
            runtime.tick();
        }
    }

    private static int panelX(ViewSpec view, String panelId) {
        return view.panels().stream()
                .filter(panel -> panel.id().equals(panelId))
                .findFirst()
                .orElseThrow()
                .bounds()
                .x();
    }

    private static void assertNoPublication(AtomicInteger publications, Runnable action) {
        int before = publications.get();
        action.run();
        assertEquals(before, publications.get());
    }

    private static void assertHorizontallyCentered(Bounds bounds, int viewportWidth) {
        assertTrue(
                Math.abs(bounds.x() + bounds.width() / 2.0 - viewportWidth / 2.0) <= 0.5,
                () -> bounds + " is not horizontally centered in " + viewportWidth);
    }

    private static void assertHorizontallyVisible(Bounds bounds, int viewportWidth) {
        assertTrue(
                bounds.x() >= 0 && bounds.right() <= viewportWidth,
                () -> bounds + " is not fully visible in " + viewportWidth);
    }

    private static void assertImportFailure(
            boolean player,
            PublicSkinImportException.Code code,
            String expectedKey) {
        FakeOperations operations = new FakeOperations();
        PublicSkinImportException failure = new PublicSkinImportException(
                code, "sensitive source detail must not reach the view");
        if (player) {
            operations.playerImportFailure = failure;
        } else {
            operations.urlImportFailure = failure;
        }
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.add");
        runtime.dispatchWidget("add.tab.file");
        runtime.dispatchText(player ? "add.player.input" : "add.url.input", player
                ? "Player"
                : "https://example.test/skin.png");
        runtime.dispatchWidget(player ? "add.player.load" : "add.url.load");

        assertEquals(UiMessage.error(expectedKey), runtime.snapshot().status());
        assertTrue(operations.uiPreferences.preferredSkinVariant().isEmpty());
        assertFalse(runtime.snapshot().status().literal());
        assertFalse(runtime.snapshot().status().key().contains("sensitive"));
    }

    @Test
    void capeImportErrorIsTypedTransientAndCancelKeepsSuccessfulImport() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.capeImportFailure = new com.naocraftlab.skins.core.png.PngValidationException(
                com.naocraftlab.skins.core.png.PngValidationException.Reason.UNSUPPORTED_DIMENSIONS, "fixture");
        ClientRuntime runtime = runtime(operations, () -> CompletableFuture.completedFuture(Optional.of(Path.of("cape.png"))));
        runtime.initialize(); runtime.view(320, 240, 0, 0);
        runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
        runtime.dispatchWidget("editor.tab.cape");
        runtime.dispatchWidget("editor.cape_item.OFFLINE.import");
        assertTrue(runtime.snapshot().editor().orElseThrow().capeCatalog().formatError());
        assertFalse(runtime.snapshot().editor().orElseThrow().busy());
        assertTrue(runtime.view(320, 240, 0, 0).texts().stream().anyMatch(text -> text.id().equals("editor.cape_error") && text.layout() == ViewSpec.Text.Layout.WRAP));
        runtime.view(320, 240, 20, 20);
        assertTrue(runtime.snapshot().editor().orElseThrow().capeCatalog().formatError());
        runtime.dispatchWidget("editor.cape_filter");
        assertFalse(runtime.snapshot().editor().orElseThrow().capeCatalog().formatError());
        operations.capeImportFailure = null;
        runtime.dispatchWidget("editor.cape_item.OFFLINE.import");
        assertEquals(TestFixtures.ACCOUNT_ID, operations.capeImportAccount);
        var imported = runtime.snapshot().editor().orElseThrow().capeCatalog();
        assertEquals(0, imported.filter());
        assertTrue(imported.inspected().local() != null);
        runtime.dispatchWidget("editor.cancel");
        assertEquals(1, operations.account.personalCapes().size());
    }

    @Test
    void cancelledCapePickerPublishesUnlockedEditorImmediately() {
        FakeOperations operations = new FakeOperations(); operations.account = TestFixtures.account(1);
        CompletableFuture<Optional<Path>> picker = new CompletableFuture<>();
        ClientRuntime runtime = runtime(operations, () -> picker);
        runtime.initialize(); runtime.view(320, 240, 0, 0);
        runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
        runtime.dispatchWidget("editor.tab.cape"); runtime.dispatchWidget("editor.cape_item.OFFLINE.import");
        assertTrue(runtime.snapshot().busy());
        picker.complete(Optional.empty());
        assertFalse(runtime.snapshot().busy());
        assertFalse(runtime.snapshot().editor().orElseThrow().busy());
        assertTrue(runtime.snapshot().editor().orElseThrow().status().isEmpty());
    }

    @Test
    void capeRenameWithMouseFocusesInputAndTabVisitsToolbarBeforeList() {
        FakeOperations operations = new FakeOperations(); operations.account = TestFixtures.account(1);
        ClientRuntime runtime = runtime(operations, () -> CompletableFuture.completedFuture(Optional.of(Path.of("cape.png"))));
        runtime.initialize(); runtime.view(854, 480, 0, 0);
        runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
        runtime.dispatchWidget("editor.tab.cape");
        String[] order = {"editor.tab.cape", "editor.cape_search", "editor.cape_filter", "editor.cape_disclosure", "editor.cape_header.OFFLINE"};
        for (int index = 1; index < order.length; index++) {
            assertTrue(runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, order[index - 1]));
            assertEquals(order[index], runtime.view(854, 480, 0, 0).focusRequest().orElseThrow().widgetId());
        }
        runtime.dispatchWidget("editor.cape_item.OFFLINE.import");
        String key = operations.account.personalCapes().get(0).texture().entryId().toString();
        assertTrue(runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, "editor.cape_item.OFFLINE." + key));
        assertEquals("editor.cape_action.rename." + key, runtime.view(854, 480, 0, 0).focusRequest().orElseThrow().widgetId());
        runtime.dispatchWidget("editor.cape_action.rename." + key);
        assertEquals("editor.cape_action.name", runtime.view(854, 480, 0, 0).focusRequest().orElseThrow().widgetId());
    }

    @Test
    void capeRenameSaveButtonAndFocusedFieldSubmitDispatchTheSameAction() throws Exception {
        FakeOperations pointerOperations = new FakeOperations();
        pointerOperations.account = TestFixtures.account(1);
        pointerOperations.importCape(TestFixtures.ACCOUNT_ID, Path.of("cape.png"), "Cape");
        ClientRuntime pointerRuntime = runtime(pointerOperations, CANCELLED_PICKER);
        pointerRuntime.initialize();
        pointerRuntime.view(854, 480, 0, 0);
        UUID presetId = pointerOperations.account.presets().get(0).id();
        UUID entryId = pointerOperations.account.personalCapes().get(0).texture().entryId();
        pointerRuntime.dispatchWidget("gallery.preset." + presetId + ".edit");
        pointerRuntime.dispatchWidget("editor.tab.cape");
        pointerRuntime.dispatchWidget("editor.cape_action.rename." + entryId);
        pointerRuntime.dispatchText("editor.cape_action.name", "Pointer name");
        ViewSpec pointerView = pointerRuntime.view(854, 480, 0, 0);
        ViewSpec.Widget save = pointerView.widget("editor.cape_action.save." + entryId).orElseThrow();
        assertEquals(Optional.of(save.id()), ViewHostPolicy.inlineCapePointerActionAt(
                pointerView, save.bounds().x() + 1, save.bounds().y() + 1));
        pointerRuntime.dispatchWidget(save.id(), false, InteractionOrigin.POINTER);
        assertEquals("Pointer name", pointerOperations.account.personalCapes().get(0).name());
        assertNull(pointerRuntime.snapshot().editor().orElseThrow().capeCatalog().editing());

        FakeOperations keyboardOperations = new FakeOperations();
        keyboardOperations.account = TestFixtures.account(1);
        keyboardOperations.importCape(TestFixtures.ACCOUNT_ID, Path.of("cape.png"), "Cape");
        ClientRuntime keyboardRuntime = runtime(keyboardOperations, CANCELLED_PICKER);
        keyboardRuntime.initialize();
        keyboardRuntime.view(854, 480, 0, 0);
        UUID keyboardPresetId = keyboardOperations.account.presets().get(0).id();
        UUID keyboardEntryId = keyboardOperations.account.personalCapes().get(0).texture().entryId();
        String originalKeyboardName = keyboardOperations.account.personalCapes().get(0).name();
        keyboardRuntime.dispatchWidget("gallery.preset." + keyboardPresetId + ".edit");
        keyboardRuntime.dispatchWidget("editor.tab.cape");
        keyboardRuntime.dispatchWidget("editor.cape_action.rename." + keyboardEntryId);
        keyboardRuntime.dispatchText("editor.cape_action.name", "Keyboard name");
        ViewSpec keyboardView = keyboardRuntime.view(854, 480, 0, 0);
        ViewSpec.Widget field = keyboardView.widget("editor.cape_action.name").orElseThrow();
        String submit = ViewHostPolicy.submitAction(
                keyboardView, field.id(), true, "Keyboard name").orElseThrow();
        assertEquals("editor.cape_action.save." + keyboardEntryId, submit);
        assertFalse(keyboardRuntime.dispatchNavigation(
                ViewSpec.NavigationCommand.ACTIVATE, field.id()));
        assertEquals(originalKeyboardName, keyboardOperations.account.personalCapes().get(0).name());
        assertEquals(keyboardEntryId,
                keyboardRuntime.snapshot().editor().orElseThrow().capeCatalog().editing());

        keyboardRuntime.dispatchText(field.id(), "Keyboard name with space");
        assertEquals(Optional.of("Keyboard name with space"),
                keyboardRuntime.view(854, 480, 0, 0).widget(field.id()).orElseThrow().value());
        keyboardRuntime.dispatchWidget(submit, false, InteractionOrigin.KEYBOARD);
        assertEquals("Keyboard name with space", keyboardOperations.account.personalCapes().get(0).name());
        assertNull(keyboardRuntime.snapshot().editor().orElseThrow().capeCatalog().editing());
    }

    @Test
    void capeRenamePreservesScrollWhileSearchKeepsItsResetSemantics() throws Exception {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.ownedCapes = capeInventory(5);
        operations.importCape(TestFixtures.ACCOUNT_ID, Path.of("cape.png"), "Cape");
        operations.session = new SessionValidation(
                SessionStatus.OFFLINE_OR_INVALID,
                TestFixtures.validSession().sessionIdentity(),
                null,
                (SessionFailureContext) null,
                "offline");
        ClientRuntime runtime = runtime(operations, CANCELLED_PICKER);
        runtime.initialize();
        runtime.view(320, 240, 0, 0);
        UUID presetId = operations.account.presets().get(0).id();
        UUID entryId = operations.account.personalCapes().get(0).texture().entryId();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        runtime.dispatchWidget("editor.tab.cape");
        runtime.dispatchWidget("editor.cape_action.rename." + entryId);
        runtime.nativeScrollPositionChanged("editor.capes", 16.0);

        String cardId = runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream()
                .filter(card -> card.local() != null && card.local().entryId().equals(entryId))
                .findFirst().orElseThrow().widgetId();
        ViewSpec before = runtime.view(320, 240, 0, 0);
        double offset = before.scrollSurface("editor.capes").orElseThrow().offsetPixels();
        assertTrue(offset > 0.0);
        Bounds cardBefore = before.widget(cardId).orElseThrow().bounds();
        Bounds fieldBefore = before.widget("editor.cape_action.name").orElseThrow().bounds();
        Bounds relativeBefore = new Bounds(
                fieldBefore.x() - cardBefore.x(),
                fieldBefore.y() - cardBefore.y(),
                fieldBefore.width(),
                fieldBefore.height());

        for (String value : List.of("Renamed", "Ren", "Renamed again")) {
            runtime.dispatchText("editor.cape_action.name", value);
            ViewSpec updated = runtime.view(320, 240, 0, 0);
            assertEquals(offset, updated.scrollSurface("editor.capes").orElseThrow().offsetPixels());
            Bounds card = updated.widget(cardId).orElseThrow().bounds();
            Bounds field = updated.widget("editor.cape_action.name").orElseThrow().bounds();
            assertEquals(cardBefore, card);
            assertEquals(relativeBefore, new Bounds(
                    field.x() - card.x(), field.y() - card.y(), field.width(), field.height()));
            assertEquals(Optional.of("editor.cape_action.name"),
                    updated.focusRequest().map(ViewSpec.FocusRequest::widgetId));
            assertEquals(Optional.of(value), updated.widget("editor.cape_action.name").orElseThrow().value());
        }

        runtime.nativeScrollPositionChanged("editor.capes", 16.0);
        runtime.view(320, 240, 0, 0);
        runtime.dispatchText("editor.cape_search", "no such cape");
        assertEquals(0.0, runtime.view(320, 240, 0, 0)
                .scrollSurface("editor.capes").orElseThrow().offsetPixels());
    }

    @Test
    void capeTabTraversesCollectionContentsAndReturnsFromFooter() throws Exception {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.importCape(TestFixtures.ACCOUNT_ID, Path.of("cape.png"), "Cape");
        ClientRuntime runtime = runtime(operations, CANCELLED_PICKER);
        runtime.initialize();
        runtime.view(854, 480, 0, 0);
        runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
        runtime.dispatchWidget("editor.tab.cape");
        ViewSpec view = runtime.view(854, 480, 0, 0);
        List<String> contents = view.widgets().stream()
                .filter(widget -> widget.id().startsWith("editor.cape_header.")
                        || widget.id().startsWith("editor.cape_item.")
                        || widget.id().startsWith("editor.cape_action."))
                .filter(ViewSpec.Widget::enabled)
                .map(ViewSpec.Widget::id).toList();
        assertTrue(contents.size() >= 6);
        CapeCatalogModel selectedBefore = runtime.snapshot().editor().orElseThrow().capeCatalog();
        for (int index = 1; index < contents.size(); index++) {
            assertEquals(contents.get(index), ViewNavigationPolicy.target(
                    view, contents.get(index - 1), ViewSpec.NavigationCommand.TAB_FORWARD).orElseThrow().id());
            assertEquals(contents.get(index - 1), ViewNavigationPolicy.target(
                    view, contents.get(index), ViewSpec.NavigationCommand.TAB_BACKWARD).orElseThrow().id());
        }
        String last = contents.get(contents.size() - 1);
        String outside = ViewNavigationPolicy.target(view, last,
                ViewSpec.NavigationCommand.TAB_FORWARD).orElseThrow().id();
        assertFalse(contents.contains(outside));
        assertEquals(last, ViewNavigationPolicy.target(view, outside,
                ViewSpec.NavigationCommand.TAB_BACKWARD).orElseThrow().id());
        assertEquals(selectedBefore, runtime.snapshot().editor().orElseThrow().capeCatalog());
    }

    @Test
    void capeTabMaterializesEveryOffscreenCardAndActionWithoutSelectingCape() {
        FakeOperations operations = new FakeOperations();
        List<PersonalCapeEntry> capes = java.util.stream.IntStream.range(1, 13)
                .mapToObj(index -> new PersonalCapeEntry(
                        new LocalCapeReference(new UUID(0, index), String.format("%064x", index), false),
                        String.format("%064x", index), "Cape " + index, Instant.EPOCH)).toList();
        operations.account = TestFixtures.account(1).withPersonalCapes(capes);
        ClientRuntime runtime = runtime(operations, CANCELLED_PICKER);
        runtime.initialize();
        runtime.view(320, 240, 0, 0);
        runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
        runtime.dispatchWidget("editor.tab.cape");
        ViewSpec initial = runtime.view(320, 240, 0, 0);
        CapeCatalogModel before = runtime.snapshot().editor().orElseThrow().capeCatalog();
        List<String> order = initial.navigationNodes().stream().filter(ViewSpec.NavigationNode::enabled)
                .filter(node -> node.surfaceId().filter("editor.capes"::equals).isPresent())
                .sorted(java.util.Comparator.comparingInt(ViewSpec.NavigationNode::tabOrder))
                .map(ViewSpec.NavigationNode::id).toList();
        assertTrue(order.stream().anyMatch(id -> initial.widget(id).isEmpty()));
        String focus = "editor.cape_disclosure";
        for (String expected : order) {
            assertTrue(runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, focus));
            ViewSpec view = runtime.view(320, 240, 0, 0);
            assertEquals(expected, view.focusRequest().orElseThrow().widgetId());
            assertTrue(view.widget(expected).isPresent(), expected);
            assertTrue(ViewNavigationPolicy.ensureVisibleOffset(view, view.navigationNode(expected).orElseThrow()).isEmpty());
            focus = expected;
        }
        ViewSpec scrolled = runtime.view(320, 240, 0, 0);
        Bounds viewport = scrolled.scrollSurface("editor.capes").orElseThrow().viewport();
        String visibleEntry = scrolled.navigationNodes().stream().filter(ViewSpec.NavigationNode::enabled)
                .filter(node -> node.surfaceId().filter("editor.capes"::equals).isPresent())
                .filter(node -> node.pattern() == ViewSpec.NavigationPattern.GRID)
                .filter(node -> node.bounds().bottom() > viewport.y() && node.bounds().y() < viewport.bottom())
                .min(java.util.Comparator.comparingInt(ViewSpec.NavigationNode::tabOrder)).orElseThrow().id();
        runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_FORWARD, "editor.cape_disclosure");
        assertEquals(visibleEntry, runtime.view(320, 240, 0, 0).focusRequest().orElseThrow().widgetId());
        for (int index = order.size() - 2; index >= 0; index--) {
            assertTrue(runtime.dispatchNavigation(ViewSpec.NavigationCommand.TAB_BACKWARD, focus));
            ViewSpec view = runtime.view(320, 240, 0, 0);
            assertEquals(order.get(index), view.focusRequest().orElseThrow().widgetId());
            assertTrue(view.widget(order.get(index)).isPresent());
            focus = order.get(index);
        }
        assertEquals(before, runtime.snapshot().editor().orElseThrow().capeCatalog());
    }

    @Test
    void reopeningEditorReloadsAccountCapeCatalog() throws Exception {
        FakeOperations operations = new FakeOperations(); operations.account = TestFixtures.account(1);
        ClientRuntime runtime = runtime(operations, () -> CompletableFuture.completedFuture(Optional.empty()));
        runtime.initialize(); runtime.view(854, 480, 0, 0);
        operations.importCape(TestFixtures.ACCOUNT_ID, Path.of("cape.png"), "Cape");
        runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
        assertEquals(1, runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream().filter(card -> card.local() != null).count());
    }

    @Test
    void lateCapePickerCompletionAfterScreenCloseDoesNotImport() {
        FakeOperations operations = new FakeOperations(); operations.account = TestFixtures.account(1);
        CompletableFuture<Optional<Path>> picker = new CompletableFuture<>();
        ClientRuntime runtime = runtime(operations, () -> picker);
        runtime.initialize(); runtime.view(320, 240, 0, 0);
        runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
        runtime.dispatchWidget("editor.tab.cape"); runtime.dispatchWidget("editor.cape_item.OFFLINE.import");
        runtime.closeScreen(); picker.complete(Optional.of(Path.of("cape.png")));
        assertTrue(operations.account.personalCapes().isEmpty());
        assertNull(operations.capeImportAccount);
    }

    @Test
    void resourceCapeSelectionIsOfflineOnlyAndCancelDoesNotMaterializeIt() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.resourceCapeCollections = List.of(resourceCapeCollection());
        operations.resourceCapeHashes = Map.of(
                new ClientOperations.ResourceCapeKey("event", "hero"), "c".repeat(64));
        ClientRuntime runtime = runtime(operations, CANCELLED_PICKER);
        runtime.initialize();
        runtime.view(854, 480, 0, 0);
        AppearancePreset original = operations.account.presets().get(0);

        runtime.dispatchWidget("gallery.preset." + original.id() + ".edit");
        runtime.dispatchWidget("editor.tab.cape");
        runtime.dispatchWidget("editor.cape_item.RESOURCE.event.hero");

        var draft = runtime.snapshot().editor().orElseThrow().capeCatalog();
        assertEquals("event", draft.selectedResource().orElseThrow().collectionId());
        assertTrue(operations.account.personalCapes().isEmpty());
        assertEquals(0, operations.resourceCapeMaterializations);
        assertEquals(original.capeId(), operations.account.presets().get(0).capeId());

        runtime.dispatchWidget("editor.cancel");
        assertEquals(0, operations.resourceCapeMaterializations);
        assertTrue(operations.account.personalCapes().isEmpty());
        assertEquals(original.capeId(), operations.account.presets().get(0).capeId());
    }

    @Test
    void matchingFileImportSelectsResourceOwnerAndHidesPersonalDuplicate() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.resourceCapeCollections = List.of(resourceCapeCollection());
        operations.resourceCapeHashes = Map.of(
                new ClientOperations.ResourceCapeKey("event", "hero"), "c".repeat(64));
        ClientRuntime runtime = runtime(operations,
                () -> CompletableFuture.completedFuture(Optional.of(Path.of("hero.png"))));
        runtime.initialize();
        runtime.view(854, 480, 0, 0);

        runtime.dispatchWidget(
                "gallery.preset." + operations.account.presets().get(0).id() + ".edit");
        runtime.dispatchWidget("editor.tab.cape");
        runtime.dispatchWidget("editor.cape_item.OFFLINE.import");

        CapeCatalogModel catalog = runtime.snapshot().editor().orElseThrow().capeCatalog();
        assertEquals("event", catalog.selectedResource().orElseThrow().collectionId());
        assertTrue(operations.account.personalCapes().isEmpty());
        assertTrue(catalog.cards().stream().noneMatch(card ->
                card.local() != null && card.local().entryId() != null));
        assertEquals(0, catalog.query().length());
        assertFalse(catalog.collapsed().contains("resource:event"));
    }

    @Test
    void deletingSelectedPersonalCapeImmediatelySelectsNoCapeAndUnlocksOtherCards() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        var selected = new com.naocraftlab.skins.core.model.PersonalCapeEntry(
                new com.naocraftlab.skins.core.model.LocalCapeReference(
                        new UUID(7, 1), "a".repeat(64), false),
                "b".repeat(64), "Selected", Instant.EPOCH);
        var other = new com.naocraftlab.skins.core.model.PersonalCapeEntry(
                new com.naocraftlab.skins.core.model.LocalCapeReference(
                        new UUID(7, 2), "c".repeat(64), false),
                "d".repeat(64), "Other", Instant.EPOCH);
        AppearancePreset preset = operations.account.presets().get(0)
                .withOfflineCape(selected.texture());
        operations.account = new AccountState(
                AccountState.CURRENT_SCHEMA_VERSION,
                operations.account.accountId(),
                operations.account.skinAssets(),
                operations.account.personalSkins(),
                List.of(preset),
                operations.account.updatedAt(),
                List.of(selected, other));

        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        runtime.dispatchWidget("gallery.preset." + preset.id() + ".edit");
        runtime.dispatchWidget("editor.tab.cape");
        runtime.dispatchWidget("editor.cape_action.delete." + selected.texture().entryId());
        runtime.dispatchWidget("editor.cape_action.confirm." + selected.texture().entryId());

        CapeCatalogModel catalog = runtime.snapshot().editor().orElseThrow().capeCatalog();
        assertNull(catalog.offline());
        assertNull(catalog.editing());
        assertFalse(catalog.deleting());
        assertTrue(catalog.selected(catalog.cards().stream()
                .filter(card -> card.collectionId().equals("OFFLINE"))
                .filter(card -> card.key().equals("none"))
                .findFirst().orElseThrow()));
        assertTrue(runtime.view(854, 480, 0, 0)
                .widget("editor.cape_action.delete." + other.texture().entryId())
                .orElseThrow().enabled());
    }

    @Test
    void resourceCapeSaveMaterializesBeforePresetAndRetryDeduplicatesAfterSaveFailure() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.resourceCapeCollections = List.of(resourceCapeCollection());
        operations.resourceCapeHashes = Map.of(
                new ClientOperations.ResourceCapeKey("event", "hero"), "c".repeat(64));
        operations.failEditorSave = true;
        ClientRuntime runtime = runtime(operations, CANCELLED_PICKER);
        runtime.initialize();
        runtime.view(854, 480, 0, 0);
        UUID presetId = operations.account.presets().get(0).id();

        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        runtime.dispatchWidget("editor.tab.cape");
        runtime.dispatchWidget("editor.cape_item.RESOURCE.event.hero");
        runtime.dispatchWidget("editor.save");

        assertEquals(1, operations.resourceCapeMaterializations);
        assertEquals(1, operations.account.personalCapes().size());
        assertTrue(runtime.snapshot().editor().isPresent());
        operations.failEditorSave = false;
        runtime.dispatchWidget("editor.save");

        assertEquals(2, operations.resourceCapeMaterializations);
        assertEquals(1, operations.account.personalCapes().size());
        assertEquals(operations.account.personalCapes().get(0).texture(),
                operations.lastEditorSaveRequest.offlineCape());
        assertTrue(runtime.snapshot().editor().isEmpty());
    }

    private record DuplicateFixture(
            AccountState account,
            AppearancePreset source,
            LocalCapeReference offline,
            List<PersonalCapeEntry> personalCapes,
            Optional<PersonalCapeEntry> alternate) {}

    private static DuplicateFixture duplicateFixture(boolean withCape, boolean withAlternate) {
        AccountState base = TestFixtures.account(2);
        AppearancePreset basePreset = base.presets().get(1);
        LocalCapeReference offline = withCape
                ? new LocalCapeReference(new UUID(7, 1), "a".repeat(64), false)
                : null;
        AppearancePreset source = new AppearancePreset(
                basePreset.id(),
                basePreset.name(),
                basePreset.skin(),
                "minecraft-cape",
                com.naocraftlab.skins.client.OuterLayerVisibility.allVisible()
                        .with(OuterLayerPart.HEAD, false),
                basePreset.createdAt(),
                basePreset.updatedAt(),
                offline);
        List<PersonalCapeEntry> personalCapes = withCape
                ? new ArrayList<>(List.of(new PersonalCapeEntry(
                        offline, "b".repeat(64), "Saved cape", Instant.EPOCH)))
                : new ArrayList<>();
        Optional<PersonalCapeEntry> alternate = Optional.empty();
        if (withAlternate) {
            PersonalCapeEntry other = new PersonalCapeEntry(
                    new LocalCapeReference(new UUID(7, 2), "c".repeat(64), true),
                    "d".repeat(64), "Other cape", Instant.EPOCH);
            personalCapes.add(other);
            alternate = Optional.of(other);
        }
        return new DuplicateFixture(
                new AccountState(
                        AccountState.CURRENT_SCHEMA_VERSION,
                        base.accountId(),
                        base.skinAssets(),
                        base.personalSkins(),
                        List.of(base.presets().get(0), source),
                        base.updatedAt(),
                        List.copyOf(personalCapes)),
                source,
                offline,
                List.copyOf(personalCapes),
                alternate);
    }

    private static CapeCatalogSource.CollectionDescriptor resourceCapeCollection() {
        return new CapeCatalogSource.CollectionDescriptor(
                "event", CatalogText.literal("Event"), Optional.empty(), Optional.empty(),
                List.of(new CapeCatalogSource.CapeDescriptor(
                        "hero", CatalogText.literal("Hero"), Optional.empty(), Optional.empty(),
                        "b".repeat(64), CapeCatalogSource.RenderSupport.CAPE_ONLY)),
                CatalogCollectionOrder.resourcePack("fixture", 0));
    }

    private static OwnedCapeInventory capeInventory(int count) {
        List<OwnedCapeEntry> capes = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new OwnedCapeEntry(
                        "cape-" + index,
                        "Cape " + index,
                        RemoteAssetState.ACTIVE,
                        null))
                .toList();
        return new OwnedCapeInventory(
                OwnedCapeInventory.CURRENT_SCHEMA_VERSION,
                TestFixtures.ACCOUNT_ID,
                capes,
                Instant.EPOCH);
    }

    private static ClientRuntime runtime(
            FakeOperations operations,
            Executor worker,
            Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh) {
        return runtime(operations, worker, appearanceRefresh, Optional.empty());
    }

    private static ClientRuntime runtime(FakeOperations operations, FilePicker picker) {
        return new ClientRuntime(new com.naocraftlab.skins.runtime.DefaultRuntimeServices(),
                operations,
                CLIENT,
                picker,
                Runnable::run,
                TEXT,
                Optional.empty(),
                Optional.empty(),
                DiagnosticSinks.discarding());
    }

    private static ClientRuntime runtime(
            FakeOperations operations,
            Executor worker,
            Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh,
            Optional<ServerAppearanceRefreshNotifier> serverAppearanceRefreshNotifier) {
        return new ClientRuntime(new com.naocraftlab.skins.runtime.DefaultRuntimeServices(),
                operations,
                CLIENT,
                CANCELLED_PICKER,
                worker,
                worker,
                Runnable::run,
                TEXT,
                appearanceRefresh,
                serverAppearanceRefreshNotifier,
                DiagnosticSinks.discarding());
    }

    private static byte[] skinPng() {
        return skinPng(0xFFFF00FF);
    }

    private static byte[] skinPng(int color) {
        try {
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(8, 8, color);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static byte[] opaqueSkinPng(boolean slim) {
        try {
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    image.setRGB(x, y, 0xff3186d8);
                }
            }
            if (slim) {
                image.setRGB(54, 20, 0x003186d8);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static SkinAsset findSkin(ClientSnapshot snapshot, UUID id) {
        SkinAsset skin = findSkinOrNull(snapshot, id);
        if (skin == null) {
            throw new AssertionError("skin not found: " + id);
        }
        return skin;
    }

    private static SkinAsset findSkinOrNull(ClientSnapshot snapshot, UUID id) {
        return snapshot.account().orElseThrow().skinAssets().stream()
                .filter(skin -> skin.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    private static AppearancePreset findPreset(ClientSnapshot snapshot, UUID id) {
        return snapshot.account().orElseThrow().presets().stream()
                .filter(preset -> preset.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static SessionValidation session(SessionStatus status) {
        SessionValidation valid = TestFixtures.validSession();
        return new SessionValidation(
                status,
                valid.sessionIdentity(),
                valid.profile(),
                (SessionFailureContext) null,
                status.name());
    }

    private static final class DirectClientExecutor implements ClientExecutor {
        @Override
        public boolean isClientThread() {
            return true;
        }

        @Override
        public void execute(Runnable action) {
            action.run();
        }
    }

    private static final class QueuedExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();
        private boolean reject;

        @Override
        public void execute(Runnable command) {
            if (reject) throw new java.util.concurrent.RejectedExecutionException();
            tasks.add(command);
        }

        private int size() {
            return tasks.size();
        }

        private void runFirst() {
            tasks.remove(0).run();
        }
    }

    private static final class TrackingClientExecutor implements ClientExecutor {
        private final List<Runnable> tasks = new ArrayList<>();
        private boolean reject;
        private boolean clientThread;

        @Override
        public boolean isClientThread() {
            return clientThread;
        }

        @Override
        public void execute(Runnable action) {
            if (reject) throw new java.util.concurrent.RejectedExecutionException();
            tasks.add(action);
        }

        private void runFirst() {
            Runnable action = tasks.remove(0);
            clientThread = true;
            try {
                action.run();
            } finally {
                clientThread = false;
            }
        }
    }

    private static final class FakeOperations implements TestCapeOperations {
        private DefaultClientOperations evidenceProducer;
        private int evidenceReads;

        @Override public boolean supportsAssetFeatureEvidence() { return evidenceProducer != null; }
        @Override public Map<UUID, com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence> assetFeatureEvidence() throws Exception {
            evidenceReads++;
            return evidenceProducer.assetFeatureEvidence();
        }

        private PublicSkinImports dependencyImports;

        @Override
        public AcquiredInput acquireFile(Path path,
                Optional<com.naocraftlab.skins.client.CpmModelFileConversion> converter,
                com.naocraftlab.skins.client.CpmModelFileConversion.Reason unavailable) throws Exception {
            return dependencyImports == null ? TestCapeOperations.super.acquireFile(path, converter, unavailable)
                    : dependencyImports.acquireFile(path, converter, unavailable);
        }

        private final List<String> providerPreviewLoads = new ArrayList<>();
        private Map<String, ProviderCapePoster> providerPosters = Map.of();
        private Map<String, byte[]> savedSkinPreviews = Map.of();

        @Override public Optional<byte[]> loadProviderTexture(String cacheKey, boolean skin) {
            providerPreviewLoads.add((skin ? "skin:" : "cape:") + cacheKey);
            return Optional.of(new byte[] {1, 2, 3});
        }
        @Override
        public com.naocraftlab.skins.core.png.NormalizedSkin loadLocalSkin(java.nio.file.Path path)
                throws java.io.IOException, com.naocraftlab.skins.core.png.PngValidationException {
            return new com.naocraftlab.skins.core.png.PngFileReader(
                    new com.naocraftlab.skins.core.png.PngValidator()).projectStandardImport(path);
        }

        private Exception capeImportFailure;
        private UUID capeImportAccount;
        private List<CapeCatalogSource.CollectionDescriptor> resourceCapeCollections = List.of();
        private Map<ResourceCapeKey, String> resourceCapeHashes = Map.of();
        private long resourceCapeGeneration = 1;
        private int resourceCapeMaterializations;
        private int capeCatalogGenerationCalls;
        private int resourceCapeCatalogWarmups;
        private int capeCatalogWarmups;
        private boolean capeEditorDataWarmed;
        private boolean publishWarmedCapeEditorOnWarmup;
        private boolean startupResourceCapeWarmupSupported;
        private CatalogRead preparedCatalog;
        private EditorSaveRequest lastEditorSaveRequest;

        @Override
        public CapeEditorData loadCapeEditorData(UUID accountId) throws Exception {
            if (preparedCatalog != null) return preparedCatalog.loadCapeEditorData(accountId);
            return new CapeEditorData(
                    account, resourceCapeCollections, resourceCapeHashes, resourceCapeGeneration);
        }

        @Override
        public Optional<CapeEditorData> warmedCapeEditorData(UUID accountId) {
            if (preparedCatalog != null) return preparedCatalog.warmedCapeEditorData(accountId);
            return capeEditorDataWarmed
                    ? Optional.of(new CapeEditorData(
                            account, resourceCapeCollections, resourceCapeHashes, resourceCapeGeneration))
                    : Optional.empty();
        }

        @Override
        public long capeCatalogGeneration() {
            capeCatalogGenerationCalls++;
            if (preparedCatalog != null) return preparedCatalog.capeCatalogGeneration();
            return resourceCapeGeneration;
        }

        @Override
        public boolean capeCatalogCapacityIncomplete() {
            return preparedCatalog != null && preparedCatalog.capeCatalogCapacityIncomplete();
        }

        @Override
        public CapacityWatch capeCatalogCapacityWatch(java.util.function.Consumer<CapacityWatch> ready) {
            if (preparedCatalog == null) throw new UnsupportedOperationException();
            return preparedCatalog.capeCatalogCapacityWatch(ready);
        }

        @Override
        public boolean supportsStartupResourceCapeWarmup() {
            return startupResourceCapeWarmupSupported;
        }

        @Override
        public void warmResourceCapeCatalog(long generation) throws Exception {
            resourceCapeCatalogWarmups++;
            if (preparedCatalog != null) preparedCatalog.warmResourceCapeCatalog(generation);
        }

        @Override
        public void warmCapeCatalog(UUID accountId, long generation) throws Exception {
            capeCatalogWarmups++;
            if (preparedCatalog != null) preparedCatalog.warmCapeCatalog(accountId, generation);
            if (publishWarmedCapeEditorOnWarmup) capeEditorDataWarmed = true;
        }

        @Override
        public Optional<byte[]> loadResourceCapePreview(
                UUID accountId, ResourceCapeSelection selection) {
            return Optional.of(skinPng());
        }

        @Override
        public com.naocraftlab.skins.core.model.PersonalCapeEntry materializeResourceCape(
                UUID accountId, ResourceCapeSelection selection) {
            resourceCapeMaterializations++;
            var existing = account.personalCapes().stream()
                    .filter(entry -> entry.renderSha256().equals(selection.contentIdentity()))
                    .findFirst();
            if (existing.isPresent()) {
                return existing.orElseThrow();
            }
            var entry = new com.naocraftlab.skins.core.model.PersonalCapeEntry(
                    new com.naocraftlab.skins.core.model.LocalCapeReference(
                            new UUID(9, 1), selection.sourceSha256(), selection.hasElytra()),
                    selection.contentIdentity(), selection.displayName(), Instant.EPOCH);
            account = account.withPersonalCapes(append(account.personalCapes(), entry));
            return entry;
        }

        @Override
        public com.naocraftlab.skins.core.model.PersonalCapeEntry importCape(UUID accountId, Path path, String fallback) throws Exception {
            capeImportAccount = accountId;
            if (capeImportFailure != null) throw capeImportFailure;
            var entry = new com.naocraftlab.skins.core.model.PersonalCapeEntry(
                    new com.naocraftlab.skins.core.model.LocalCapeReference(new UUID(3, 1), "a".repeat(64), false),
                    "b".repeat(64), "Personal cape", Instant.EPOCH);
            account = account.withPersonalCapes(List.of(entry));
            return entry;
        }

        @Override
        public AccountState renameCape(UUID accountId, UUID entryId, String name) {
            account = account.withPersonalCapes(account.personalCapes().stream()
                    .map(entry -> entry.texture().entryId().equals(entryId)
                            ? entry.renamed(name)
                            : entry)
                    .toList());
            return account;
        }

        @Override
        public Optional<AccountState> discardCapeIfUnreferenced(UUID accountId, UUID entryId) {
            boolean referenced = account.presets().stream()
                    .map(AppearancePreset::offlineCape)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(reference -> entryId.equals(reference.entryId()));
            if (!referenced) {
                account = account.withPersonalCapes(account.personalCapes().stream()
                        .filter(entry -> !entryId.equals(entry.texture().entryId()))
                        .toList());
            }
            return Optional.of(account);
        }

        @Override
        public CapeDeletion deleteCape(UUID accountId, UUID entryId) {
            account = new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION,
                    account.accountId(),
                    account.skinAssets(),
                    account.personalSkins(),
                    account.presets().stream()
                            .map(preset -> preset.offlineCape() != null
                                    && entryId.equals(preset.offlineCape().entryId())
                                            ? preset.withOfflineCape(null)
                                            : preset)
                            .toList(),
                    account.updatedAt().plusNanos(1),
                    account.personalCapes().stream()
                            .filter(entry -> !entryId.equals(entry.texture().entryId()))
                            .toList());
            return new CapeDeletion(account, providerState());
        }

        @Override
        public Optional<AccountState> reloadEditorAccount(UUID accountId) { return Optional.of(account); }

        private AccountState account = TestFixtures.account(0);
        private OwnedCapeInventory ownedCapes = OwnedCapeInventory.empty(
                TestFixtures.ACCOUNT_ID, Instant.EPOCH);
        private SessionValidation session = TestFixtures.validSession();
        private MutationResult result = MutationResult.APPLIED;
        private RemoteAppearanceImpact remoteAppearanceImpact =
                RemoteAppearanceImpact.CONFIRMED_CHANGED;
        private Set<RecoveryAction> recovery = Set.of();
        private Optional<DurableAppearance> durable = Optional.empty();
        private Optional<UUID> activePresetId = Optional.empty();
        private long appearanceRevision;
        private AppearanceSyncStatus appearanceSyncStatus = AppearanceSyncStatus.LOCAL_ONLY;
        private boolean localFirst;
        private boolean reconcileWithOutcome;
        private boolean rateLimited;
        private Duration rateLimitRemaining = Duration.ofSeconds(1);
        private int sequence = 100;
        private int applyCalls;
        private int editorSaveCalls;
        private int restoreCalls;
        private int retrySessionCalls;
        private int reconciliationCalls;
        private int retryCapeCalls;
        private int skinPreviewCalls;
        private byte[] skinPreviewPng = {7, 8, 9};
        private String lastCapeId;
        private final List<Trigger> reconciliationTriggers = new ArrayList<>();
        private final List<ReconciliationKey> reconciliationKeys = new ArrayList<>();
        private String deleteWarning;
        private boolean removeFinalDespiteFailedReset;
        private RemoteAppearanceImpact settlementFailureImpact;
        private AccountUiPreferences uiPreferences =
                AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID);
        private Set<SkinModel> failedCatalogModels = Set.of();
        private byte[] catalogPng = skinPng();
        private byte[] personalCatalogPng;
        private int catalogPreviewCalls;
        private int collapsedReplacementCalls;
        private Exception collapsedReplacementFailure;
        private UUID personalCatalogAssetId;
        private int removePersonalCalls;
        private boolean failPersonalRemoval;
        private java.util.function.BooleanSupplier reconciliationPrecondition = () -> true;
        private boolean reconciliationBeforePrecondition;
        private int storagePreflightCalls;
        private RuntimeException storagePreflightFailure;
        private Exception retrySessionFailure;
        private boolean failEditorSave;
        private Exception playerImportFailure;
        private Exception urlImportFailure;
        private SkinVariant playerImportVariant = SkinVariant.CLASSIC;
        private SkinVariant urlImportVariant = SkinVariant.CLASSIC;
        private int playerImportCalls;
        private Optional<InitialData> warmedInitialData = Optional.empty();
        private InitialData retryInitialDataOverride;
        private Exception externalImportFailure;
        private ExternalImportResult externalImportResult;
        private boolean externalSourceAvailable = true;
        private final EnumMap<ExternalImportSource, ExternalImportProbe> externalSourceProbes =
                new EnumMap<>(ExternalImportSource.class);
        private int externalCommitCalls;
        private ExternalImportSource lastExternalSource;
        private Optional<Path> lastExternalRoot = Optional.empty();

        private AppearanceSyncStatus selectionStatus = AppearanceSyncStatus.PENDING;
        private boolean settledActiveSave;
        private AppearanceProviders providers = AppearanceProviders.initial();
        private AppearanceProviders.Component providerRefresh;
        private boolean refreshSourceFailure;
        private boolean refreshSourceNull;
        private final List<ProviderRefreshRequest> providerRefreshRequests = new ArrayList<>();
        private final EnumMap<BuiltinProvider, Duration> capeCooldowns = new EnumMap<>(BuiltinProvider.class);
        private int optifineRefreshes;
        private int skinMcRefreshes;
        private CapeProviderCoordinator optifineCoordinator;
        private boolean deferCapeRefresh;
        private final List<Set<BuiltinProvider>> candidateCheckpoints = new java.util.ArrayList<>();
        private final java.util.Map<BuiltinProvider, java.util.function.Consumer<ProviderObservation<ProviderCape>>> capeRefreshCompletions
                = new java.util.EnumMap<>(BuiltinProvider.class);
        private NclSkinsStorage providerStorage;
        private boolean presetUseProviders;

        @Override public void onCapeObservation(java.util.function.Consumer<CapeObservationPort.Observation> listener) {
            if (optifineCoordinator != null) optifineCoordinator.onCapeObservation(listener);
        }
        @Override public void startOptiFineCapes() {
            if (optifineCoordinator != null) optifineCoordinator.start();
        }
        @Override public long capeObservationGeneration() {
            return optifineCoordinator == null ? 0 : optifineCoordinator.observationGeneration();
        }
        @Override public void refreshCapeProvider(ProviderRefreshUseCases.Scope scope, BuiltinProvider provider,
                java.util.function.Consumer<com.naocraftlab.skins.core.provider.ProviderObservation<ProviderCape>> completion) {
            if (provider == BuiltinProvider.OPTIFINE) optifineRefreshes++;
            if (provider == BuiltinProvider.SKINMC) skinMcRefreshes++;
            if (deferCapeRefresh) capeRefreshCompletions.put(provider, completion);
            else if (optifineCoordinator == null) completion.accept(null);
            else optifineCoordinator.refreshScoped(scope, provider, completion);
        }
        @Override public Optional<Duration> capeProviderCooldown(BuiltinProvider provider) {
            return Optional.ofNullable(capeCooldowns.get(provider));
        }
        @Override public void optiFineConfigurationChanged() {
            if (optifineCoordinator != null) optifineCoordinator.configurationChanged();
        }
        @Override public void adoptSharedCapeObservation(UUID accountId, String canonicalName,
                AppearanceProviders snapshot) {
            if (optifineCoordinator != null) optifineCoordinator.adoptSharedSnapshot(
                    accountId, canonicalName, snapshot);
        }
        @Override public void selfCapeCandidatesChanged(UUID accountId, String canonicalName,
                AppearanceProviders next) {
            if (optifineCoordinator != null) optifineCoordinator.selfCapeCandidatesChanged(
                    accountId, canonicalName, next);
        }

        @Override public void selfCapeCandidatesChanged(UUID accountId, String canonicalName,
                AppearanceProviders next, Set<BuiltinProvider> allowedSources) {
            candidateCheckpoints.add(Set.copyOf(allowedSources));
            if (optifineCoordinator != null) optifineCoordinator.selfCapeCandidatesChanged(
                    accountId, canonicalName, next, allowedSources);
        }

        @Override public DurableAppearance reloadProviders() { return providerState(); }
        @Override public AppearanceProviders loadProviders() { return providers; }

        private DurableAppearance providerState() {
            if (providerStorage != null) {
                try { providers = providerStorage.loadAppearance(account.accountId()).providers(); }
                catch (IOException failure) { throw new IllegalStateException(failure); }
            }
            return new DurableAppearance(account.accountId(), appearanceRevision, appearanceSyncStatus,
                    activePresetId, Optional.empty(), Optional.empty(), providers);
        }

        @Override public DurableAppearance enableProvider(AppearanceProviders.Component component, BuiltinProvider provider) {
            if (providerStorage == null) providers = providers.enable(component, provider);
            else {
                try { providers = providerStorage.updateAppearance(account.accountId(),
                        state -> state.withProviders(state.providers().enable(component, provider))).providers(); }
                catch (IOException failure) { throw new IllegalStateException(failure); }
            }
            return providerState();
        }

        @Override public DurableAppearance disableProvider(AppearanceProviders.Component component, BuiltinProvider provider) {
            if (providerStorage == null) providers = providers.disable(component, provider);
            else {
                try { providers = providerStorage.updateAppearance(account.accountId(),
                        state -> state.withProviders(state.providers().disable(component, provider))).providers(); }
                catch (IOException failure) { throw new IllegalStateException(failure); }
            }
            return providerState();
        }

        @Override public DurableAppearance moveProvider(AppearanceProviders.Component component, BuiltinProvider provider, int direction) {
            providers = providers.move(component, provider, direction);
            return providerState();
        }

        @Override public ProviderRefreshResult refreshProviderSources(ProviderRefreshUseCases.Scope scope) {
            providerRefresh = scope.request().component();
            providerRefreshRequests.add(scope.request());
            if (refreshSourceFailure) throw new IllegalStateException("Synthetic refresh failure");
            if (refreshSourceNull) return null;
            return new ProviderRefreshResult(scope, Optional.of(providerState()), Map.of(), false);
        }

        @Override
        public void verifyStorageAccess() {
            storagePreflightCalls++;
            if (storagePreflightFailure != null) {
                throw storagePreflightFailure;
            }
        }

        @Override
        public InitialData initialize() {
            return initial();
        }

        @Override
        public Optional<InitialData> warmedInitialData() {
            return warmedInitialData;
        }

        @Override
        public List<SkinCatalogSource.CollectionDescriptor> catalogCollections() {
            List<SkinCatalogSource.CollectionDescriptor> collections = new ArrayList<>();
            account.personalSkins().stream()
                    .filter(PersonalSkinEntry::visible)
                    .findFirst()
                    .ifPresent(entry -> {
                        personalCatalogAssetId = entry.optionalAssetId(SkinVariant.CLASSIC)
                                .orElseThrow();
                        boolean otherPlayer = entry.source() == PersonalSkinSource.PLAYER_NAME;
                        collections.add(new SkinCatalogSource.CollectionDescriptor(
                                otherPlayer
                                        ? PersonalSkinCatalog.OTHER_PLAYERS_COLLECTION_ID
                                        : PersonalSkinCatalog.COLLECTION_ID,
                                CatalogText.literal(otherPlayer ? "Other players' skins" : "Your skins"),
                                Optional.empty(),
                                Optional.empty(),
                                List.of(new SkinCatalogSource.SkinDescriptor(
                                        entry.sha256(),
                                        CatalogText.literal(entry.displayName()),
                                        Optional.empty(),
                                        Optional.empty(),
                                        List.of(SkinModel.CLASSIC))),
                                CatalogCollectionOrder.personal(otherPlayer
                                        ? PersonalSkinCatalog.OTHER_PLAYERS_SOURCE_ID
                                        : PersonalSkinCatalog.SOURCE_ID)));
                    });
            collections.addAll(MinecraftSkinCatalog.collections());
            return List.copyOf(collections);
        }

        @Override
        public Optional<FrozenCatalogSelection> freezeCatalogSelection(String collectionId, String skinId) throws Exception {
            String hash = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(catalogPng));
            Map<SkinModel, String> hashes = new EnumMap<>(SkinModel.class);
            Map<SkinModel, UUID> assets = new EnumMap<>(SkinModel.class);
            List<SkinModel> models = catalogCollections().stream()
                    .filter(collection -> collection.id().equals(collectionId))
                    .flatMap(collection -> collection.skins().stream())
                    .filter(skin -> skin.id().equals(skinId))
                    .findFirst().orElseThrow().models();
            for (SkinModel model : models) {
                if (!failedCatalogModels.contains(model)) {
                    Optional<UUID> reusable = reusableCatalogSkinAsset(collectionId, skinId, model);
                    if (reusable.isPresent()) assets.put(model, reusable.orElseThrow());
                    else hashes.put(model, hash);
                }
            }
            return Optional.of(new FrozenCatalogSelection(account.accountId(), collectionId, skinId,
                    0, hashes, assets));
        }

        @Override
        public byte[] loadCatalogSkin(String collectionId, String skinId, SkinModel model)
                throws IOException {
            catalogPreviewCalls++;
            if (failedCatalogModels.contains(model)) {
                throw new IOException("catalog variant unavailable");
            }
            return PersonalSkinCatalog.isCollection(collectionId) && personalCatalogPng != null
                    ? personalCatalogPng.clone() : catalogPng.clone();
        }

        @Override
        public Optional<UUID> reusableCatalogSkinAsset(
                String collectionId, String skinId, SkinModel model) throws IOException {
            if (!PersonalSkinCatalog.isCollection(collectionId)) {
                return Optional.empty();
            }
            if (personalCatalogAssetId == null || model != SkinModel.CLASSIC) {
                throw new IOException("personal catalog snapshot is unavailable");
            }
            return Optional.of(personalCatalogAssetId);
        }

        @Override
        public Optional<AccountUiPreferences> loadUiPreferences() {
            return Optional.of(uiPreferences);
        }

        @Override
        public void setSelectedProvidersTab(UUID accountId, AppearanceProviders.Component tab) {
            assertEquals(uiPreferences.accountId(), accountId);
            uiPreferences = uiPreferences.withSelectedProvidersTab(tab);
        }

        @Override
        public void setSelectedAddSourceTab(AddSourceTab tab) {
            uiPreferences = uiPreferences.withSelectedAddSourceTab(tab);
        }

        @Override
        public void setCollectionCollapsed(String collectionId, boolean collapsed) {
            uiPreferences = uiPreferences.withCollectionCollapsed(collectionId, collapsed);
        }

        @Override
        public void replaceCollapsedCollectionIds(Set<String> collectionIds) throws Exception {
            collapsedReplacementCalls++;
            if (collapsedReplacementFailure != null) {
                throw collapsedReplacementFailure;
            }
            uiPreferences = uiPreferences.withCollapsedCollectionIds(collectionIds);
        }

        @Override
        public void setPreferredSkinVariant(SkinVariant variant) {
            uiPreferences = uiPreferences.withPreferredSkinVariant(variant);
        }

        @Override
        public AccountState importSkin(String name, SkinVariant variant, byte[] normalizedPng) {
            Instant now = nextTime();
            SkinAsset asset = new SkinAsset(
                    nextId(),
                    name,
                    String.format("%064x", sequence),
                    variant,
                    SkinSource.IMPORTED,
                    now,
                    now);
            account = copy(append(account.skinAssets(), asset), account.presets());
            return account;
        }

        @Override
        public ImportDraft loadPlayerSkin(String playerNameOrUuid) throws Exception {
            playerImportCalls++;
            if (playerImportFailure != null) {
                throw playerImportFailure;
            }
            return new ImportDraft(
                    "Player", playerImportVariant, skinPng(), PersonalSkinSource.PLAYER_NAME);
        }

        private PublicSkinImports acquiredImports;

        @Override
        public AcquiredInput acquireUrl(String url,
                Optional<com.naocraftlab.skins.client.CpmModelFileConversion> converter,
                com.naocraftlab.skins.client.CpmModelFileConversion.Reason unavailable) throws Exception {
            return acquiredImports == null ? new AcquiredSkin(loadUrlSkin(url))
                    : acquiredImports.acquireUrl(url, converter, unavailable);
        }

        @Override
        public ImportDraft loadUrlSkin(String url) throws Exception {
            if (urlImportFailure != null) {
                throw urlImportFailure;
            }
            return new ImportDraft(
                    "Remote skin", urlImportVariant, skinPng(), PersonalSkinSource.URL);
        }

        @Override
        public ExternalImportProbe probeExternalSource(
                ExternalImportSource source, Optional<Path> selectedRoot) throws Exception {
            lastExternalSource = source;
            lastExternalRoot = selectedRoot;
            if (externalImportFailure != null) {
                throw externalImportFailure;
            }
            return externalSourceProbes.getOrDefault(source, externalSourceAvailable
                    ? ExternalImportProbe.AVAILABLE
                    : ExternalImportProbe.UNAVAILABLE);
        }

        @Override
        public ExternalImportReview prepareExternalAppearances(
                ExternalImportSource source, Optional<Path> selectedRoot) throws Exception {
            lastExternalSource = source;
            lastExternalRoot = selectedRoot;
            if (externalImportFailure != null) {
                throw externalImportFailure;
            }
            return new ExternalImportReview(source, List.of(new ExternalImportCandidate(
                    "candidate-0",
                    "Imported",
                    SkinVariant.CLASSIC,
                    PersonalSkinSource.FILE,
                    skinPng(),
                    "0".repeat(64),
                    null,
                    0,
                    false)), 0, 0);
        }

        @Override
        public ExternalImportResult commitExternalAppearances(
                List<ExternalImportCandidate> selected, int skipped, int warnings) throws Exception {
            externalCommitCalls++;
            if (externalImportFailure != null) {
                throw externalImportFailure;
            }
            return externalImportResult == null
                    ? new ExternalImportResult(account, 1, 0, 0, 0)
                    : externalImportResult;
        }

        @Override
        public AccountState renameSkin(UUID skinId, String newName) {
            Instant now = nextTime();
            List<SkinAsset> skins = account.skinAssets().stream()
                    .map(skin -> skin.id().equals(skinId) ? skin.renamed(newName, now) : skin)
                    .toList();
            account = copy(skins, account.presets());
            return account;
        }

        @Override
        public AccountState changeSkinVariant(UUID skinId, SkinVariant variant) {
            Instant now = nextTime();
            List<SkinAsset> skins = account.skinAssets().stream()
                    .map(skin -> skin.id().equals(skinId) ? skin.withVariant(variant, now) : skin)
                    .toList();
            account = copy(skins, account.presets());
            return account;
        }

        @Override
        public AccountState duplicateSkin(UUID skinId, String newName) {
            SkinAsset source = account.skinAssets().stream()
                    .filter(skin -> skin.id().equals(skinId))
                    .findFirst()
                    .orElseThrow();
            account = copy(
                    append(account.skinAssets(), source.duplicate(nextId(), newName, nextTime())),
                    account.presets());
            return account;
        }

        @Override
        public AccountState deleteSkin(UUID skinId) {
            account = copy(
                    account.skinAssets().stream().filter(skin -> !skin.id().equals(skinId)).toList(),
                    account.presets());
            return account;
        }

        @Override
        public AccountState removePersonalSkin(String sha256) throws IOException {
            removePersonalCalls++;
            if (failPersonalRemoval) {
                throw new IOException("personal catalog write failed");
            }
            Instant now = nextTime();
            List<PersonalSkinEntry> personalSkins = account.personalSkins().stream()
                    .map(entry -> entry.sha256().equals(sha256) ? entry.hidden(now) : entry)
                    .toList();
            account = new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION,
                    TestFixtures.ACCOUNT_ID,
                    account.skinAssets(),
                    personalSkins,
                    account.presets(),
                    now);
            return account;
        }

        @Override
        public AccountState renamePersonalSkin(String sha256, String newName) {
            Instant now = nextTime();
            List<PersonalSkinEntry> personalSkins = account.personalSkins().stream()
                    .map(entry -> entry.sha256().equals(sha256)
                            ? entry.renamed(newName, now)
                            : entry)
                    .toList();
            account = new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION,
                    TestFixtures.ACCOUNT_ID,
                    account.skinAssets(),
                    personalSkins,
                    account.presets(),
                    now);
            return account;
        }

        @Override
        public InitialData resetLibrary() {
            account = TestFixtures.account(0);
            return initial();
        }

        @Override
        public EditorSave saveEditor(EditorSaveRequest request) throws IOException {
            editorSaveCalls++;
            lastEditorSaveRequest = request;
            if (failEditorSave) {
                throw new IOException("editor save failed");
            }
            UUID presetId = request.originalPresetId().orElseGet(this::nextId);
            SkinReference skin = request.skin();
            if (request.pngBytes().isPresent()) {
                Instant skinTime = nextTime();
                SkinAsset imported = new SkinAsset(
                        nextId(),
                        request.name() + " skin",
                        String.format("%064x", sequence),
                        request.variant(),
                        SkinSource.IMPORTED,
                        skinTime,
                        skinTime,
                        request.catalogOrigin());
                account = copy(append(account.skinAssets(), imported), account.presets());
                skin = SkinReference.asset(imported.id());
            } else if (request.variant() != request.initialVariant()) {
                UUID selectedSkinId = skin.assetId();
                SkinAsset selected = account.skinAssets().stream()
                        .filter(asset -> asset.id().equals(selectedSkinId))
                        .findFirst()
                        .orElseThrow();
                Instant skinTime = nextTime();
                SkinAsset changed = selected.withVariant(request.variant(), skinTime);
                List<SkinAsset> skins = account.skinAssets().stream()
                        .map(asset -> asset.id().equals(changed.id()) ? changed : asset)
                        .toList();
                account = copy(skins, account.presets());
            }
            Instant now = nextTime();
            AppearancePreset existing = account.presets().stream()
                    .filter(preset -> preset.id().equals(presetId))
                    .findFirst()
                    .orElse(null);
            AppearancePreset saved = new AppearancePreset(
                    presetId,
                    request.name(),
                    skin,
                    request.capeId().orElse(null),
                    request.outerLayerVisibility(),
                    existing == null ? now : existing.createdAt(),
                    now,
                    request.offlineCape());
            List<AppearancePreset> presets = new ArrayList<>(account.presets());
            presets.removeIf(preset -> preset.id().equals(presetId));
            presets.add(saved);
            account = copy(account.skinAssets(), presets);
            if (activePresetId.filter(presetId::equals).isEmpty()) {
                return new EditorSave(account, presetId);
            }
            appearanceRevision++;
            appearanceSyncStatus = settledActiveSave
                    ? AppearanceSyncStatus.OFFICIAL : AppearanceSyncStatus.PENDING;
            AppliedAppearance local = AppliedAppearance.localSkin(
                    TestFixtures.ACCOUNT_ID,
                    "a".repeat(64),
                    request.variant(),
                    Optional.empty());
            durable = Optional.of(new DurableAppearance(
                    account.accountId(),
                    appearanceRevision,
                    appearanceSyncStatus,
                    activePresetId,
                    Optional.of(local),
                    Optional.of(request.outerLayerVisibility())));
            return new EditorSave(account, presetId, durable);
        }

        @Override
        public PresetDelete deletePreset(UUID presetId) {
            if (localFirst) {
                account = copy(
                        account.skinAssets(),
                        account.presets().stream()
                                .filter(preset -> !preset.id().equals(presetId))
                                .toList());
                activePresetId = Optional.empty();
                appearanceRevision++;
                appearanceSyncStatus = AppearanceSyncStatus.PENDING;
                AppliedAppearance accountDefault = AppliedAppearance.accountDefault(
                        TestFixtures.ACCOUNT_ID, Optional.empty());
                durable = Optional.of(new DurableAppearance(
                        account.accountId(),
                        appearanceRevision,
                        appearanceSyncStatus,
                        activePresetId,
                        Optional.of(accountDefault),
                        Optional.of(com.naocraftlab.skins.client.OuterLayerVisibility.allVisible())));
                return PresetDelete.local(account, durable.orElseThrow());
            }
            boolean finalPreset = account.presets().size() == 1;
            if (finalPreset) {
                if (result == MutationResult.APPLIED || removeFinalDespiteFailedReset) {
                    account = copy(account.skinAssets(), List.of());
                }
                return PresetDelete.withRemoteReset(
                        remote(),
                        deleteWarning == null ? List.of() : List.of(deleteWarning));
            }
            account = copy(
                    account.skinAssets(),
                    account.presets().stream().filter(preset -> !preset.id().equals(presetId)).toList());
            return PresetDelete.local(account);
        }

        @Override
        public RemoteResult applyPreset(UUID presetId) {
            applyCalls++;
            if (settlementFailureImpact != null) {
                throw new RemoteMutationSettlementException(settlementFailureImpact);
            }
            return remote();
        }

        @Override
        public PresetUse usePreset(UUID presetId) throws Exception {
            if (!localFirst) {
                RemoteResult remote = applyPreset(presetId);
                activePresetId = Optional.of(presetId);
                return new PresetUse(
                        remote.account(),
                        remote.session(),
                        presetId,
                        remote.outcome().optionalAppliedAppearance(),
                        Optional.of(remote),
                        false,
                        false);
            }
            AppearancePreset preset = account.presets().stream()
                    .filter(candidate -> candidate.id().equals(presetId))
                    .findFirst()
                    .orElseThrow();
            activePresetId = Optional.of(presetId);
            appearanceRevision++;
            appearanceSyncStatus = selectionStatus;
            AppliedAppearance local = AppliedAppearance.localSkin(
                    TestFixtures.ACCOUNT_ID,
                    "a".repeat(64),
                    SkinVariant.CLASSIC,
                    Optional.empty());
            durable = Optional.of(new DurableAppearance(
                    account.accountId(),
                    appearanceRevision,
                    appearanceSyncStatus,
                    activePresetId,
                    Optional.of(local),
                    Optional.of(preset.outerLayerVisibility())));
            return new PresetUse(
                    account,
                    session,
                    presetId,
                    Optional.of(local),
                    Optional.empty(),
                    true,
                    true,
                    Optional.of(preset.outerLayerVisibility()),
                    appearanceRevision,
                    appearanceSyncStatus,
                    presetUseProviders ? providers : AppearanceProviders.initial());
        }

        @Override
        public Optional<ReconciliationResult> reconcileAppearance(Trigger trigger) {
            if (!reconciliationPrecondition.getAsBoolean()) {
                reconciliationBeforePrecondition = true;
            }
            reconciliationCalls++;
            reconciliationTriggers.add(trigger);
            reconciliationKeys.add(new ReconciliationKey(
                    account.accountId(), appearanceRevision));
            if (settlementFailureImpact != null) {
                appearanceSyncStatus = settlementFailureImpact == RemoteAppearanceImpact.UNCERTAIN
                        ? AppearanceSyncStatus.UNKNOWN
                        : AppearanceSyncStatus.OFFICIAL;
                durable = Optional.of(new DurableAppearance(
                        account.accountId(),
                        appearanceRevision,
                        appearanceSyncStatus,
                        activePresetId,
                        durable.flatMap(DurableAppearance::localAppearance),
                        durable.flatMap(DurableAppearance::outerLayerVisibility)));
                throw new RemoteMutationSettlementException(settlementFailureImpact);
            }
            if (reconcileWithOutcome) {
                PresetApplicationOutcome outcome = remote().outcome();
                appearanceSyncStatus = switch (outcome.result()) {
                    case APPLIED -> AppearanceSyncStatus.OFFICIAL;
                    case PARTIAL -> AppearanceSyncStatus.PARTIAL;
                    case UNKNOWN -> AppearanceSyncStatus.UNKNOWN;
                    case FAILED, SESSION_EXPIRED -> AppearanceSyncStatus.PENDING;
                };
                durable = Optional.of(new DurableAppearance(
                        account.accountId(),
                        appearanceRevision,
                        appearanceSyncStatus,
                        activePresetId,
                        outcome.optionalAppliedAppearance(),
                        durable.flatMap(DurableAppearance::outerLayerVisibility)));
                return Optional.of(new ReconciliationResult(
                        account,
                        session,
                        Optional.empty(),
                        durable.orElseThrow(),
                        Optional.of(outcome)));
            }
            return Optional.empty();
        }

        @Override
        public RemoteResult retryCape(String capeId) {
            retryCapeCalls++;
            lastCapeId = capeId;
            return remote();
        }

        @Override
        public RemoteResult restorePreviousAppearance(PresetApplicationOutcome outcome) {
            restoreCalls++;
            return remote();
        }

        @Override
        public byte[] loadSkinPreview(UUID skinId) {
            skinPreviewCalls++;
            return skinPreviewPng.clone();
        }

        @Override
        public Optional<byte[]> loadCapePreview(String capeId) {
            return Optional.of(new byte[] {4, 5, 6});
        }

        @Override
        public InitialData retrySession() throws Exception {
            retrySessionCalls++;
            if (retrySessionFailure != null) {
                throw retrySessionFailure;
            }
            return retryInitialDataOverride == null ? initial() : retryInitialDataOverride;
        }

        @Override
        public boolean rateLimited() {
            return rateLimited;
        }

        @Override
        public Optional<Duration> rateLimitRemaining() {
            return rateLimited ? Optional.of(rateLimitRemaining) : Optional.empty();
        }

        private Runnable sessionIdentityHook;

        @Override
        public GameSessionTokenSource.SessionIdentity sessionIdentity() {
            if (sessionIdentityHook != null) sessionIdentityHook.run();
            return session.sessionIdentity();
        }

        @Override
        public Optional<DurableAppearance> durableAppearance() {
            return durable;
        }

        private InitialData initial() {
            return new InitialData(
                    account,
                    session,
                    Optional.empty(),
                    activePresetId,
                    durable.flatMap(DurableAppearance::localAppearance),
                    appearanceSyncStatus == AppearanceSyncStatus.PENDING,
                    List.of(),
                    uiPreferences,
                    durable.flatMap(DurableAppearance::outerLayerVisibility),
                    ownedCapes,
                    appearanceRevision,
                    appearanceSyncStatus, providers, providerPosters, savedSkinPreviews);
        }

        private RemoteResult remote() {
            AppliedAppearance appearance = result == MutationResult.APPLIED || result == MutationResult.PARTIAL
                    ? AppliedAppearance.accountDefault(TestFixtures.ACCOUNT_ID, Optional.empty())
                    : null;
            PresetApplicationOutcome outcome = new PresetApplicationOutcome(
                    result,
                    ApplicationPhase.COMPLETE,
                    session.profile(),
                    session.profile(),
                    appearance,
                    null,
                    recovery,
                    remoteAppearanceImpact,
                    result.name());
            return new RemoteResult(outcome, account, session, Optional.empty());
        }

        private AccountState copy(List<SkinAsset> skins, List<AppearancePreset> presets) {
            return new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION,
                    TestFixtures.ACCOUNT_ID,
                    skins,
                    account.personalSkins(),
                    presets,
                    nextTime(),
                    account.personalCapes());
        }

        private String seedPersonalSkin(String name) {
            return seedPersonalSkin(name, PersonalSkinSource.FILE);
        }

        private String seedPersonalSkin(String name, PersonalSkinSource source) {
            String hash = "a".repeat(64);
            Instant now = nextTime();
            SkinAsset asset = new SkinAsset(
                    nextId(),
                    name,
                    hash,
                    SkinVariant.CLASSIC,
                    SkinSource.IMPORTED,
                    now,
                    now);
            PersonalSkinEntry entry = new PersonalSkinEntry(
                    hash,
                    name,
                    source,
                    now,
                    now,
                    Map.of(SkinVariant.CLASSIC, asset.id()),
                    true);
            account = new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION,
                    TestFixtures.ACCOUNT_ID,
                    append(account.skinAssets(), asset),
                    List.of(entry),
                    account.presets(),
                    nextTime());
            return hash;
        }

        private Instant nextTime() {
            return Instant.parse("2026-01-01T00:00:00Z").plusSeconds(sequence++);
        }

        private UUID nextId() {
            return new UUID(9L, sequence++);
        }

        private static <T> List<T> append(List<T> values, T value) {
            List<T> result = new ArrayList<>(values);
            result.add(value);
            return List.copyOf(result);
        }
    }
}
