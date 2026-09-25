package com.naocraftlab.skins.runtime;

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
    private static final DirectClientExecutor CLIENT = new DirectClientExecutor();
    private static final FilePicker CANCELLED_PICKER =
            () -> CompletableFuture.completedFuture(Optional.empty());
    private static final TextResolver TEXT = message -> switch (message.key()) {
        case "nclskins.editor.default_name" -> "Preset " + message.arguments().get(0);
        case "nclskins.gallery.copy_name" -> "Copy of " + message.arguments().get(0);
        default -> message.key();
    };

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
    void resourceCapeWarmupRunsOnReloadSignalAndNeverPollsFromClientTicks() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();

        for (int tick = 0; tick < 20; tick++) {
            runtime.tick();
        }
        assertEquals(0, operations.capeCatalogGenerationCalls);
        assertEquals(0, operations.capeCatalogWarmups);

        runtime.resourcesReloaded();
        assertEquals(1, operations.capeCatalogGenerationCalls);
        assertEquals(1, operations.resourceCapeCatalogWarmups);
        assertEquals(1, operations.capeCatalogWarmups);
    }

    @Test
    void initialResourceReloadWarmsDiscoveryBeforeAccountInitialization() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());

        runtime.resourcesReloaded();

        assertEquals(1, operations.capeCatalogGenerationCalls);
        assertEquals(1, operations.resourceCapeCatalogWarmups);
        assertEquals(0, operations.capeCatalogWarmups);
    }

    @Test
    void skinProviderGalleryDoneAndEscapeReturnToSkinProviders() {
        for (BuiltinProvider provider : BuiltinProvider.values()) {
            if (!provider.supportsSkin() || !provider.writable()) continue;
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
            if (!provider.writable()) continue;
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
            if (!provider.writable()) continue;
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
        var reader = new OptifineCapeReader((uri, timeout, maximum) ->
                new OptifineCapeReader.Response(200, capeBytes), new PngValidator());
        operations.optifineCoordinator = new OptifineCapeCoordinator(tokens, storage, cache,
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
        assertTrue(operations.reconciliationTriggers.contains(ClientOperations.ReconciliationTrigger.EXPLICIT_RETRY));
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
    void oversizedSkinImportDoesNotReachOperations() {
        FakeOperations operations = new FakeOperations();
        ClientRuntime runtime = runtime(operations, Runnable::run, Optional.empty());
        runtime.initialize();
        int before = runtime.snapshot().account().orElseThrow().skinAssets().size();

        runtime.importSkin("Oversized", SkinVariant.CLASSIC,
                new byte[EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES + 1]);

        assertEquals(before, runtime.snapshot().account().orElseThrow().skinAssets().size());
        assertEquals(UiMessage.Severity.ERROR, runtime.snapshot().status().severity());
        assertFalse(runtime.snapshot().busy());
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
        ClientRuntime runtime = new ClientRuntime(operations, CLIENT, CANCELLED_PICKER,
                Runnable::run, Runnable::run, sessionWorker, TEXT, Optional.empty(), Optional.empty(),
                IMMEDIATE_READINESS_SCHEDULER, DiagnosticSinks.discarding());
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
        ClientRuntime runtime = new ClientRuntime(
                operations,
                CLIENT,
                CANCELLED_PICKER,
                Runnable::run,
                Runnable::run,
                sessionWorker,
                TEXT,
                Optional.empty(),
                Optional.empty(),
                IMMEDIATE_READINESS_SCHEDULER,
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
        assertEquals(ClientOperations.ReconciliationTrigger.SESSION_REFRESHED,
                operations.reconciliationTriggers.get(operations.reconciliationTriggers.size() - 1));
    }

    @Test
    void lateExplicitSessionRefreshCannotOverwriteAConcurrentLocalLibraryMutation() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(1);
        operations.retryInitialDataOverride = operations.initial();
        QueuedExecutor sessionWorker = new QueuedExecutor();
        ClientRuntime runtime = new ClientRuntime(
                operations,
                CLIENT,
                CANCELLED_PICKER,
                Runnable::run,
                Runnable::run,
                sessionWorker,
                TEXT,
                Optional.empty(),
                Optional.empty(),
                IMMEDIATE_READINESS_SCHEDULER,
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
                List.of(ClientOperations.ReconciliationTrigger.SESSION_REFRESHED),
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
        ClientRuntime runtime = new ClientRuntime(
                operations,
                CLIENT,
                CANCELLED_PICKER,
                Runnable::run,
                reconciliation,
                Runnable::run,
                TEXT,
                Optional.empty(),
                Optional.of(notifications::incrementAndGet),
                IMMEDIATE_READINESS_SCHEDULER,
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
                List.of(ClientOperations.ReconciliationTrigger.RATE_LIMIT_EXPIRED),
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
                        ClientOperations.ReconciliationTrigger.LOCAL_INTENT,
                        ClientOperations.ReconciliationTrigger.EXPLICIT_RETRY),
                operations.reconciliationTriggers);
        assertEquals(AppearanceSyncStatus.OFFICIAL, runtime.snapshot().syncStatus());
    }

    @Test
    void queuedExplicitTriggerForOldRevisionCannotRecoverNewerRevision() {
        FakeOperations operations = new FakeOperations();
        operations.account = TestFixtures.account(2);
        operations.localFirst = true;
        QueuedExecutor reconciliation = new QueuedExecutor();
        ClientRuntime runtime = new ClientRuntime(
                operations,
                CLIENT,
                CANCELLED_PICKER,
                Runnable::run,
                reconciliation,
                Runnable::run,
                TEXT,
                Optional.empty(),
                Optional.empty(),
                IMMEDIATE_READINESS_SCHEDULER,
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
                List.of(ClientOperations.ReconciliationTrigger.LOCAL_INTENT),
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
        ClientRuntime runtime = new ClientRuntime(
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
                IMMEDIATE_READINESS_SCHEDULER,
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
                AppearanceRefreshCoordinator.Result.UPDATED,
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

        assertEquals(AppearanceRefreshCoordinator.Result.UPDATED, runtime.afterReconnect().join());

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
        ClientRuntime runtime = new ClientRuntime(
                operations,
                client,
                CANCELLED_PICKER,
                worker,
                worker,
                Runnable::run,
                TEXT,
                Optional.of(refresh),
                Optional.empty(),
                IMMEDIATE_READINESS_SCHEDULER,
                DiagnosticSinks.discarding());

        CompletableFuture<AppearanceRefreshCoordinator.Result> reconnect =
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

        assertEquals(AppearanceRefreshCoordinator.Result.DEFERRED, reconnect.join());
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
        ClientProcessHost<Object> host = new ClientProcessHost<>(runtime, () -> {});
        Object connection = new Object();

        for (int tick = 0; tick < 200; tick++) {
            host.tick(connection, true);
        }

        assertEquals(1, operations.reconciliationCalls);

        host.tick(new Object(), true);

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
        return new ClientRuntime(
                operations,
                CLIENT,
                picker,
                Runnable::run,
                TEXT,
                Optional.empty(),
                Optional.empty(),
                IMMEDIATE_READINESS_SCHEDULER,
                DiagnosticSinks.discarding());
    }

    private static ClientRuntime runtime(
            FakeOperations operations,
            Executor worker,
            Optional<AppearanceRefreshCoordinator<?>> appearanceRefresh,
            Optional<ServerAppearanceRefreshNotifier> serverAppearanceRefreshNotifier) {
        return new ClientRuntime(
                operations,
                CLIENT,
                CANCELLED_PICKER,
                worker,
                worker,
                Runnable::run,
                TEXT,
                appearanceRefresh,
                serverAppearanceRefreshNotifier,
                IMMEDIATE_READINESS_SCHEDULER,
                DiagnosticSinks.discarding());
    }

    private static final ServerAppearanceReadinessCoordinator.DelayScheduler
            IMMEDIATE_READINESS_SCHEDULER = (delay, action) -> {
                action.run();
                return () -> {};
            };

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

        @Override
        public void execute(Runnable command) {
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
        private boolean clientThread;

        @Override
        public boolean isClientThread() {
            return clientThread;
        }

        @Override
        public void execute(Runnable action) {
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

    private static final class FakeOperations implements ClientOperations {
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
        private EditorSaveRequest lastEditorSaveRequest;

        @Override
        public CapeEditorData loadCapeEditorData(UUID accountId) {
            return new CapeEditorData(
                    account, resourceCapeCollections, resourceCapeHashes, resourceCapeGeneration);
        }

        @Override
        public Optional<CapeEditorData> warmedCapeEditorData(UUID accountId) {
            return capeEditorDataWarmed ? Optional.of(loadCapeEditorData(accountId)) : Optional.empty();
        }

        @Override
        public long capeCatalogGeneration() {
            capeCatalogGenerationCalls++;
            return resourceCapeGeneration;
        }

        @Override
        public void warmResourceCapeCatalog(long generation) {
            resourceCapeCatalogWarmups++;
        }

        @Override
        public void warmCapeCatalog(UUID accountId, long generation) {
            capeCatalogWarmups++;
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
        private int restoreCalls;
        private int retrySessionCalls;
        private int reconciliationCalls;
        private int retryCapeCalls;
        private int skinPreviewCalls;
        private String lastCapeId;
        private final List<ReconciliationTrigger> reconciliationTriggers = new ArrayList<>();
        private final List<ReconciliationKey> reconciliationKeys = new ArrayList<>();
        private String deleteWarning;
        private boolean removeFinalDespiteFailedReset;
        private RemoteAppearanceImpact settlementFailureImpact;
        private AccountUiPreferences uiPreferences =
                AccountUiPreferences.defaults(TestFixtures.ACCOUNT_ID);
        private Set<SkinModel> failedCatalogModels = Set.of();
        private byte[] catalogPng = skinPng();
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
        private final EnumMap<BuiltinProvider, Duration> capeCooldowns = new EnumMap<>(BuiltinProvider.class);
        private int optifineRefreshes;
        private int skinMcRefreshes;
        private OptifineCapeCoordinator optifineCoordinator;
        private boolean presetUseProviders;

        @Override public void onOptiFineObservation(java.util.function.Consumer<OptiFineObservation> listener) {
            if (optifineCoordinator != null) optifineCoordinator.onSelfObservation(listener);
        }
        @Override public void startOptiFineCapes() {
            if (optifineCoordinator != null) optifineCoordinator.start();
        }
        @Override public void refreshOptiFineCapes() {
            if (optifineCoordinator != null) optifineCoordinator.refresh();
        }
        @Override public void refreshOptiFineCapes(
                java.util.function.Consumer<com.naocraftlab.skins.core.provider.ProviderObservation<ProviderCape>> completion) {
            optifineRefreshes++;
            completion.accept(null);
        }
        @Override public void refreshSkinMcCapes(
                java.util.function.Consumer<com.naocraftlab.skins.core.provider.ProviderObservation<ProviderCape>> completion) {
            skinMcRefreshes++;
            completion.accept(null);
        }
        @Override public Optional<Duration> capeProviderCooldown(BuiltinProvider provider) {
            return Optional.ofNullable(capeCooldowns.get(provider));
        }
        @Override public void selfCapeCandidatesChanged(UUID accountId, String canonicalName,
                AppearanceProviders next) {
            if (optifineCoordinator != null) optifineCoordinator.selfCapeCandidatesChanged(
                    accountId, canonicalName, next);
        }

        @Override public DurableAppearance reloadProviders() { return providerState(); }
        @Override public AppearanceProviders loadProviders() { return providers; }

        private DurableAppearance providerState() {
            return new DurableAppearance(account.accountId(), appearanceRevision, appearanceSyncStatus,
                    activePresetId, Optional.empty(), Optional.empty(), providers);
        }

        @Override public DurableAppearance enableProvider(AppearanceProviders.Component component, BuiltinProvider provider) {
            providers = providers.enable(component, provider);
            return providerState();
        }

        @Override public DurableAppearance disableProvider(AppearanceProviders.Component component, BuiltinProvider provider) {
            providers = providers.disable(component, provider);
            return providerState();
        }

        @Override public DurableAppearance moveProvider(AppearanceProviders.Component component, BuiltinProvider provider, int direction) {
            providers = providers.move(component, provider, direction);
            return providerState();
        }

        @Override public DurableAppearance refreshProviders(AppearanceProviders.Component component) {
            providerRefresh = component;
            return providerState();
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
        public byte[] loadCatalogSkin(String collectionId, String skinId, SkinModel model)
                throws IOException {
            catalogPreviewCalls++;
            if (failedCatalogModels.contains(model)) {
                throw new IOException("catalog variant unavailable");
            }
            return catalogPng.clone();
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
        public Optional<ReconciliationResult> reconcileAppearance(ReconciliationTrigger trigger) {
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
            return new byte[] {7, 8, 9};
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

        @Override
        public GameSessionTokenSource.SessionIdentity sessionIdentity() {
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
                    appearanceSyncStatus, providers);
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
