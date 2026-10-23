package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.OuterLayerVisibilityController;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinExtensionEnvironmentSource;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.MutationResult;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderSkin;
import com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger;
import com.naocraftlab.skins.core.service.ApplicationPhase;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.RecoveryAction;
import com.naocraftlab.skins.core.service.SessionValidation;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientRuntimeRegressionTest {
    private static final ClientExecutor CLIENT = new ClientExecutor() {
        @Override
        public boolean isClientThread() {
            return true;
        }

        @Override
        public void execute(Runnable action) {
            action.run();
        }
    };
    private static final FilePicker CANCELLED_PICKER =
            () -> CompletableFuture.completedFuture(Optional.empty());
    private static final TextResolver TEXT = message -> switch (message.key()) {
        case "nclskins.editor.default_name" -> "Preset " + message.arguments().get(0);
        case "nclskins.mutation.partial" -> "The skin was applied, but the cape needs attention.";
        case "nclskins.status.reconnect_refresh" ->
                "The local player preview will update after reconnecting.";
        default -> message.key();
    };

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cancelledCapePickerDoesNotSupersedePendingSneakyDraft(boolean decodePending) throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = new AppearanceProviders(operations.providers.skin(), operations.providers.cape().enable(BuiltinProvider.SNEAKY));
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0x7f123456);
        QueuedExecutor worker = new QueuedExecutor();
        try (ClientRuntime runtime = runtime(operations, worker)) {
            runtime.initialize();
            while (worker.size() > 0) worker.runFirst();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            if (decodePending) worker.runFirst();
            long ticket = runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyTicket();
            assertEquals(CapeCatalogModel.SneakyReadiness.PENDING, runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyReadiness());
            runtime.dispatchWidget("editor.tab.cape");
            runtime.dispatchWidget("editor.cape_item.OFFLINE.import");
            while (worker.size() > 0) worker.runFirst();
            assertEquals(ticket, runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyTicket());
            assertEquals(CapeCatalogModel.SneakyReadiness.VALUE, runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyReadiness());
            assertTrue(hasSneakyCard(runtime.view(854, 480, 0, 0)));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void closeReopenWhileDecodePendingRejectsOldDraftEvenAcrossAccountSwitch(boolean switchAccount) throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY);
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0x7f123456);
        QueuedExecutor worker = new QueuedExecutor();
        try (ClientRuntime runtime = runtime(operations, worker)) {
            runtime.initialize();
            while (worker.size() > 0) worker.runFirst();
            String edit = "gallery.preset." + operations.account.presets().get(0).id() + ".edit";
            runtime.dispatchWidget(edit);
            worker.runFirst();
            long retired = runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyTicket();
            runtime.closeScreen();
            if (switchAccount) {
                UUID next = new UUID(0, 99);
                operations.account = new AccountState(AccountState.CURRENT_SCHEMA_VERSION, next, operations.account.skinAssets(),
                        operations.account.personalSkins(), operations.account.presets(), operations.account.updatedAt());
                operations.session = new SessionValidation(operations.session.status(),
                        new GameSessionTokenSource.SessionIdentity(next, "SecondAccount"), null, null, "");
            }
            List<ClientSnapshot> published = new ArrayList<>();
            runtime.subscribe(published::add);
            runtime.reopen();
            while (worker.size() > 0) worker.runFirst();
            assertTrue(runtime.snapshot().editor().isEmpty());
            runtime.dispatchWidget(edit);
            long current = runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyTicket();
            assertTrue(current > retired);
            while (worker.size() > 0) worker.runFirst();
            assertEquals(operations.account.accountId(), runtime.snapshot().account().orElseThrow().accountId());
            assertEquals(CapeCatalogModel.SneakyReadiness.VALUE, runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyReadiness());
            assertTrue(published.stream().flatMap(snapshot -> snapshot.editor().stream()).filter(editor ->
                    editor.capeCatalog().sneakyReadiness() == CapeCatalogModel.SneakyReadiness.VALUE)
                    .allMatch(editor -> editor.capeCatalog().sneakyTicket() == current));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = SkinExtensionEnvironmentSource.State.class, names = {"UNKNOWN", "MISSING_PREREQUISITE"})
    void unknownConsumersAndUnrelatedWarningDoNotBlockSneaky(SkinExtensionEnvironmentSource.State consumer) throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY);
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0);
        try (ClientRuntime runtime = runtime(operations).useSkinExtensionEnvironmentSource(() ->
                new SkinExtensionEnvironmentSource.Snapshot(1, Map.of(SkinExtensionEnvironmentSource.Consumer.FRESH_MOVES, consumer)))) {
            runtime.initialize();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            runtime.dispatchWidget("editor.tab.cape");
            assertTrue(hasSneakyCard(runtime.view(854, 480, 0, 0)));
            if (consumer == SkinExtensionEnvironmentSource.State.MISSING_PREREQUISITE) {
                assertEquals(Optional.of(GuiIcon.STATUS_COMPATIBILITY_INCOMPATIBLE), runtime.view(854, 480, 0, 0)
                        .widget("editor.compatibility").orElseThrow().icon());
            }
            assertEquals(BuiltinProvider.SNEAKY, runtime.snapshot().editor().orElseThrow().capeCatalog().defaultBundle().source());
            assertEquals(1, operations.skinPreviewCalls.get());
        }
    }

    @Test
    void independentEmbeddedInspectionSurvivesPartialCallbacksDisableAndOverlapWarning() throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM);
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0);
        AtomicReference<SkinExtensionEnvironmentSource.State> consumer = new AtomicReference<>(SkinExtensionEnvironmentSource.State.UNKNOWN);
        AtomicReference<Runnable> changed = new AtomicReference<>();
        SkinExtensionEnvironmentSource environment = new SkinExtensionEnvironmentSource() {
            public Snapshot snapshot() { return new Snapshot(1, Map.of(Consumer.FRESH_MOVES, consumer.get()), true); }
            public Subscription subscribe(Runnable listener) { changed.set(listener); return () -> changed.compareAndSet(listener, null); }
        };
        try (ClientRuntime runtime = runtime(operations).useSkinExtensionEnvironmentSource(environment)) {
            runtime.initialize();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            runtime.dispatchWidget("editor.tab.cape");
            var capability = new AtomicReference<>(new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 903), 1,
                    com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED, true, true, PreviewRenderer.LiveReason.AVAILABLE));
            var callback = runtime.observePreviewAccessories(runtime.view(854, 480, 0, 0).previews().get(0), capability::get);
            var catalog = runtime.snapshot().editor().orElseThrow().capeCatalog();
            String cpm = catalog.cards().stream().filter(card -> card.provider() == BuiltinProvider.CPM).findFirst().orElseThrow().widgetId();
            var sneaky = catalog.cards().stream().filter(card -> card.provider() == BuiltinProvider.SNEAKY).findFirst().orElseThrow();
            runtime.dispatchWidget(cpm);
            consumer.set(SkinExtensionEnvironmentSource.State.ACTIVE);
            changed.get().run();
            assertEquals(BuiltinProvider.CPM, runtime.snapshot().editor().orElseThrow().capeCatalog().inspected().provider());
            assertTrue(hasSneakyCard(runtime.view(854, 480, 0, 0)));
            consumer.set(SkinExtensionEnvironmentSource.State.UNKNOWN);
            changed.get().run();
            assertEquals(BuiltinProvider.CPM, runtime.snapshot().editor().orElseThrow().capeCatalog().inspected().provider());
            var accept = ClientRuntime.class.getDeclaredMethod("acceptProviderChange", ClientOperations.DurableAppearance.class);
            accept.setAccessible(true);
            operations.providers = operations.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY);
            accept.invoke(runtime, operations.reloadProviders());
            assertEquals(BuiltinProvider.CPM, runtime.snapshot().editor().orElseThrow().capeCatalog().inspected().provider());
            operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY);
            accept.invoke(runtime, operations.reloadProviders());
            runtime.dispatchWidget(runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream()
                    .filter(card -> card.provider() == BuiltinProvider.SNEAKY).findFirst().orElseThrow().widgetId());
            capability.set(com.naocraftlab.skins.client.PreviewAccessories.unavailable(PreviewRenderer.LiveReason.WORLD_REQUIRED));
            callback.accept(capability.get());
            assertEquals(BuiltinProvider.SNEAKY, runtime.snapshot().editor().orElseThrow().capeCatalog().inspected().provider());
            assertTrue(hasSneakyCard(runtime.view(854, 480, 0, 0)));
            assertTrue(runtime.view(854, 480, 0, 0).widgets().stream().noneMatch(widget -> widget.id().startsWith("editor.cape_item.CPM.")));
            assertEquals(sneaky.texture().isPresent(), runtime.view(854, 480, 0, 0).previews().get(0).capeId().isPresent());
            assertEquals(1, operations.skinPreviewCalls.get());
        }
        assertNull(changed.get());
    }

    @Test
    void sneakyRuntimeInteractionsUseClippedTargetsAndNeverRepeatPreparationOrExternalEffects() throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY);
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0);
        operations.account = operations.account.withPersonalCapes(java.util.stream.IntStream.range(0, 24)
                .mapToObj(index -> new com.naocraftlab.skins.core.model.PersonalCapeEntry(
                        new com.naocraftlab.skins.core.model.LocalCapeReference(new UUID(0, index + 100), String.format("%064x", index + 1), false),
                        String.format("%064x", index + 1), "Personal " + index, java.time.Instant.EPOCH)).toList());
        AtomicInteger demands = new AtomicInteger(), previewJobs = new AtomicInteger();
        DefaultRuntimeServices realServices = new DefaultRuntimeServices();
        RuntimeServices services = (RuntimeServices) java.lang.reflect.Proxy.newProxyInstance(RuntimeServices.class.getClassLoader(),
                new Class<?>[]{RuntimeServices.class}, (proxy, method, arguments) -> {
                    if (!method.getName().equals("previewAssets")) return method.invoke(realServices, arguments);
                    PreviewAssets real = new PreviewAssetLoader(CLIENT, task -> { previewJobs.incrementAndGet(); task.run(); }, DiagnosticSinks.discarding());
                    return java.lang.reflect.Proxy.newProxyInstance(PreviewAssets.class.getClassLoader(), new Class<?>[]{PreviewAssets.class},
                            (assetProxy, assetMethod, assetArguments) -> {
                                if (assetMethod.getName().equals("prepareSneaky")) demands.incrementAndGet();
                                return assetMethod.invoke(real, assetArguments);
                            });
                });
        try (ClientRuntime runtime = new ClientRuntime(services, operations, CLIENT, CANCELLED_PICKER,
                Runnable::run, Runnable::run, Runnable::run, TEXT, Optional.empty(), Optional.empty(), DiagnosticSinks.discarding())) {
            runtime.initialize();
            int initializationAdoptions = operations.observationAdoptions.get();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            runtime.dispatchWidget("editor.tab.cape");
            var initial = runtime.view(320, 240, 0, 0);
            String id = runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream()
                    .filter(card -> card.provider() == BuiltinProvider.SNEAKY).findFirst().orElseThrow().widgetId();
            var widget = initial.widget(id).orElseThrow();
            int x = widget.bounds().x() + widget.bounds().width() / 2;
            int y = widget.bounds().y() + widget.bounds().height() / 2;
            assertEquals(id, ViewHostPolicy.pointerOwnerAt(initial, x, y).orElseThrow().id());
            int jobs = previewJobs.get();
            var account = runtime.snapshot().account();
            var providers = runtime.snapshot().providers();
            runtime.dispatchWidget(ViewHostPolicy.pointerOwnerAt(initial, x, y).orElseThrow().id(), false, InteractionOrigin.POINTER);
            assertEquals(BuiltinProvider.SNEAKY, runtime.snapshot().editor().orElseThrow().capeCatalog().inspected().provider());
            var previewBounds = initial.previews().get(0).bounds();
            runtime.pointerPressed(previewBounds.x() + 10, previewBounds.y() + 10, 0);
            runtime.pointerDragged(previewBounds.x() + 20, previewBounds.y() + 15, 0, 10, 5);
            runtime.pointerReleased(0);
            var transformed = runtime.snapshot().editor().orElseThrow().preview();
            runtime.nativeScrollPositionChanged("editor.capes", 1000);
            double scroll = runtime.view(320, 240, 0, 0).scrollSurface("editor.capes").orElseThrow().offsetPixels();
            assertTrue(scroll > 0);
            runtime.dispatchWidget("editor.tab.cape");
            assertEquals(transformed, runtime.snapshot().editor().orElseThrow().preview());
            assertEquals(scroll, runtime.view(320, 240, 0, 0).scrollSurface("editor.capes").orElseThrow().offsetPixels());
            runtime.nativeScrollPositionChanged("editor.capes", 0);
            runtime.dispatchWidget(id);
            var inspected = runtime.snapshot().editor().orElseThrow().capeCatalog().inspected();
            for (int width : new int[]{320, 854, 400}) {
                ViewSpec resized = runtime.view(width, 240, x, y);
                var clip = resized.clipRegions().stream().filter(region -> region.id().equals("editor.capes")).findFirst().orElseThrow().bounds();
                assertTrue(clip.x() >= 0 && clip.right() <= width && clip.y() >= 0 && clip.bottom() <= 240);
                assertFalse(ViewHostPolicy.pointerOwnerAt(resized, clip.x() + 1, clip.y() - 1).map(owner -> owner.id().equals(id)).orElse(false));
            }
            runtime.dispatchText("editor.cape_search", "no-matches");
            ViewSpec hidden = runtime.view(320, 240, x, y);
            assertTrue(hidden.widget(id).isEmpty());
            assertTrue(hidden.navigationNode(id).isEmpty());
            assertTrue(hidden.widget("editor.cape_header.EMBEDDED").isEmpty());
            assertFalse(runtime.dispatchNavigation(ViewSpec.NavigationCommand.ACTIVATE, id));
            assertTrue(ViewNavigationPolicy.target(hidden, id, ViewSpec.NavigationCommand.TAB_FORWARD)
                    .flatMap(node -> hidden.widget(node.id())).isPresent());
            assertEquals(inspected, runtime.snapshot().editor().orElseThrow().capeCatalog().inspected());
            runtime.dispatchText("editor.cape_search", "");
            runtime.dispatchWidget("editor.cape_filter");
            runtime.dispatchWidget("editor.cape_filter", true);
            runtime.dispatchWidget("editor.cape_header.EMBEDDED");
            assertTrue(runtime.view(320, 240, 0, 0).widget(id).isEmpty());
            runtime.dispatchWidget("editor.cape_header.EMBEDDED");
            runtime.dispatchWidget("editor.cape_disclosure");
            runtime.dispatchWidget("editor.cape_disclosure");
            runtime.dispatchWidget("editor.preview_mode");
            runtime.dispatchWidget("editor.tab.cape");
            assertEquals(1, demands.get());
            assertEquals(jobs, previewJobs.get());
            assertEquals(1, operations.skinPreviewCalls.get());
            assertEquals(account, runtime.snapshot().account());
            assertEquals(providers, runtime.snapshot().providers());
            assertEquals(0, operations.providerHttpCalls.get());
            assertEquals(0, operations.personalMaterializations.get());
            assertEquals(initializationAdoptions, operations.observationAdoptions.get());
            assertNull(operations.lastEditorRequest);
        }
    }

    @Test
    void failedSaveDoesNotSupersedePendingSneakyDraft() throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY);
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0);
        operations.failEditorSave = true;
        QueuedExecutor worker = new QueuedExecutor();
        try (ClientRuntime runtime = runtime(operations, worker)) {
            runtime.initialize();
            while (worker.size() > 0) worker.runFirst();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            long ticket = runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyTicket();
            var request = runtime.snapshot().editor().orElseThrow().saveRequest();
            runtime.dispatchWidget("editor.save");
            worker.tasks.remove(worker.size() - 1).run();
            assertFalse(runtime.snapshot().editor().orElseThrow().busy());
            while (worker.size() > 0) worker.runFirst();
            assertEquals(CapeCatalogModel.SneakyReadiness.VALUE, runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyReadiness());
            assertEquals(ticket, runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyTicket());
            assertEquals(request, runtime.snapshot().editor().orElseThrow().saveRequest());
        }
    }

    @Test
    void personalRenameAndDeleteWhileDecodePendingSurviveSneakyCancel() throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY);
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0);
        var keep = new com.naocraftlab.skins.core.model.LocalCapeReference(new UUID(0, 101), "a".repeat(64), false);
        var remove = new com.naocraftlab.skins.core.model.LocalCapeReference(new UUID(0, 102), "b".repeat(64), true);
        operations.account = operations.account.withPersonalCapes(List.of(
                new com.naocraftlab.skins.core.model.PersonalCapeEntry(keep, keep.sha256(), "Keep", java.time.Instant.EPOCH),
                new com.naocraftlab.skins.core.model.PersonalCapeEntry(remove, remove.sha256(), "Remove", java.time.Instant.EPOCH)));
        QueuedExecutor worker = new QueuedExecutor();
        try (ClientRuntime runtime = runtime(operations, worker)) {
            runtime.initialize();
            while (worker.size() > 0) worker.runFirst();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            worker.runFirst();
            runtime.dispatchWidget("editor.tab.cape");
            long ticket = runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyTicket();
            runtime.dispatchWidget("editor.cape_action.rename." + keep.entryId());
            runtime.dispatchText("editor.cape_action.name", "Renamed");
            runtime.dispatchWidget("editor.cape_action.save." + keep.entryId());
            worker.tasks.remove(worker.size() - 1).run();
            runtime.dispatchWidget("editor.cape_action.delete." + remove.entryId());
            runtime.dispatchWidget("editor.cape_action.confirm." + remove.entryId());
            worker.tasks.remove(worker.size() - 1).run();
            while (worker.size() > 0) worker.runFirst();
            assertEquals(ticket, runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyTicket());
            assertEquals(CapeCatalogModel.SneakyReadiness.VALUE, runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyReadiness());
            runtime.dispatchWidget("editor.tab.cape");
            var card = runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream()
                    .filter(value -> value.provider() == BuiltinProvider.SNEAKY).findFirst().orElseThrow();
            runtime.dispatchWidget(card.widgetId());
            runtime.dispatchWidget("editor.cancel");
            assertTrue(runtime.loadCapePreview(card.texture().orElseThrow()).join().isEmpty());
            assertEquals(List.of("Renamed"), runtime.snapshot().account().orElseThrow().personalCapes().stream()
                    .map(com.naocraftlab.skins.core.model.PersonalCapeEntry::name).toList());
            assertEquals(1, operations.capeRenames.get());
            assertEquals(1, operations.capeDeletes.get());
            assertNull(operations.lastEditorRequest);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"model", "roundtrip", "reopen", "reenable"})
    void cpmStringDispatchRejectsRetiredNativeScope(String transition) throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM);
        try (ClientRuntime runtime = runtime(operations).useSkinExtensionEnvironmentSource(() ->
                new SkinExtensionEnvironmentSource.Snapshot(1, Map.of(), true))) {
            runtime.initialize();
            String edit = "gallery.preset." + operations.account.presets().get(0).id() + ".edit";
            runtime.dispatchWidget(edit);
            runtime.dispatchWidget("editor.tab.cape");
            var first = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 901), 1,
                    com.naocraftlab.skins.client.PreviewAccessories.Availability.CONFIRMED, true, true,
                    PreviewRenderer.LiveReason.AVAILABLE);
            var oldCallback = runtime.observePreviewAccessories(runtime.view(854, 480, 0, 0).previews().get(0), () -> first);
            String stale = runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream()
                    .filter(card -> card.provider() == BuiltinProvider.CPM).findFirst().orElseThrow().widgetId();
            if (transition.equals("reopen")) {
                runtime.closeScreen();
                runtime.reopen();
                runtime.dispatchWidget(edit);
            } else if (transition.equals("reenable")) {
                var accept = ClientRuntime.class.getDeclaredMethod("acceptProviderChange", ClientOperations.DurableAppearance.class);
                accept.setAccessible(true);
                operations.providers = operations.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM);
                accept.invoke(runtime, operations.reloadProviders());
                operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM);
                accept.invoke(runtime, operations.reloadProviders());
            } else {
                runtime.dispatchWidget("editor.tab.appearance");
                runtime.dispatchWidget("editor.model_choice.slim");
                if (transition.equals("roundtrip")) runtime.dispatchWidget("editor.model_choice.classic");
            }
            runtime.dispatchWidget("editor.tab.cape");
            var next = new com.naocraftlab.skins.client.PreviewAccessories(new UUID(0, 902), 2,
                    first.availability(), true, true, PreviewRenderer.LiveReason.AVAILABLE);
            runtime.observePreviewAccessories(runtime.view(854, 480, 0, 0).previews().get(0), () -> next);
            oldCallback.accept(first);
            for (InteractionOrigin origin : InteractionOrigin.values()) {
                runtime.dispatchWidget(stale, false, origin);
                assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().inspected());
            }
            String current = runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream()
                    .filter(card -> card.provider() == BuiltinProvider.CPM).findFirst().orElseThrow().widgetId();
            runtime.dispatchWidget(current);
            assertEquals(next.scope() + ":" + next.generation(), runtime.snapshot().editor().orElseThrow().capeCatalog().inspected().embeddedIdentity());
        }
    }

    @Test
    void sneakyDraftPublishesWorldlessTextureWithoutBorrowingWorldSkin() throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = new AppearanceProviders(operations.providers.skin(),
                operations.providers.cape().enable(BuiltinProvider.SNEAKY));
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int[] marker = {0xfffff42f, 0xffffffff, 0xff9c59d1, 0xff292929};
        for (int index = 0; index < marker.length; index++) skin.setRGB(60, 48 + index, marker[index]);
        skin.setRGB(56, 16, 0x7f123456);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(skin, "png", encoded);
        operations.skinPreviewByAsset = Map.of(TestFixtures.CLASSIC_ID, encoded.toByteArray());
        try (ClientRuntime runtime = runtime(operations)) {
            runtime.initialize();
            AppearancePreset draft = operations.account.presets().get(0);
            runtime.dispatchWidget("gallery.preset." + draft.id() + ".edit");
            runtime.dispatchWidget("editor.tab.cape");
            ViewSpec view = runtime.view(854, 480, 0, 0);
            assertTrue(hasSneakyCard(view));
            assertTrue(view.previews().get(0).capeId().isPresent());
            byte[] atlas = runtime.loadCapePreview(view.previews().get(0).capeId().orElseThrow()).join().orElseThrow();
            assertArrayEquals(new com.naocraftlab.skins.core.png.SneakyCapeDecoder()
                    .decode(encoded.toByteArray()).orElseThrow().bytes(), atlas);
            assertFalse(view.previews().get(0).nativeAccessories());
            assertTrue(runtime.openPreparedCapePreview(view.previews().get(0).capeId().orElseThrow()).join().isEmpty());
            assertEquals(0, operations.preparedCapeReads.get());
            assertEquals(1, operations.skinPreviewCalls.get());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = SkinExtensionEnvironmentSource.Consumer.class, names = {"FRESH_MOVES", "JUST_EXPRESSIONS"})
    void sneakyAdmissionAllowsActiveOverlapAndRetainsWarning(SkinExtensionEnvironmentSource.Consumer source) throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = new AppearanceProviders(operations.providers.skin(),
                operations.providers.cape().enable(BuiltinProvider.SNEAKY));
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0x7f123456);
        AtomicReference<SkinExtensionEnvironmentSource.State> consumer = new AtomicReference<>(SkinExtensionEnvironmentSource.State.ACTIVE);
        try (ClientRuntime runtime = runtime(operations).useSkinExtensionEnvironmentSource(() ->
                new SkinExtensionEnvironmentSource.Snapshot(1, Map.of(
                        source, consumer.get())))) {
            runtime.initialize();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            runtime.dispatchWidget("editor.tab.cape");
            assertTrue(hasSneakyCard(runtime.view(854, 480, 0, 0)));
            assertEquals(BuiltinProvider.SNEAKY, runtime.snapshot().editor().orElseThrow().capeCatalog().defaultBundle().source());
            assertEquals(Optional.of(GuiIcon.STATUS_COMPATIBILITY_INCOMPATIBLE), runtime.view(854, 480, 0, 0)
                    .widget("editor.compatibility").orElseThrow().icon());
            consumer.set(SkinExtensionEnvironmentSource.State.INACTIVE);
            runtime.resourcesReloaded();
            assertTrue(hasSneakyCard(runtime.view(854, 480, 0, 0)));
            runtime.dispatchWidget(runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream()
                    .filter(card -> card.provider() == BuiltinProvider.SNEAKY).findFirst().orElseThrow().widgetId());
            consumer.set(SkinExtensionEnvironmentSource.State.ACTIVE);
            runtime.resourcesReloaded();
            assertTrue(hasSneakyCard(runtime.view(854, 480, 0, 0)));
            assertEquals(Optional.of(GuiIcon.STATUS_COMPATIBILITY_INCOMPATIBLE), runtime.view(854, 480, 0, 0)
                    .widget("editor.compatibility").orElseThrow().icon());
            assertEquals(1, operations.skinPreviewCalls.get());
        }
    }

    @Test
    void sneakyCompletionPublishesToOpenScreenAndCloseRejectsLateCompletion() throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = new AppearanceProviders(operations.providers.skin(), operations.providers.cape().enable(BuiltinProvider.SNEAKY));
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0);
        QueuedExecutor worker = new QueuedExecutor();
        try (ClientRuntime runtime = runtime(operations, worker)) {
            runtime.initialize();
            while (worker.size() > 0) worker.runFirst();
            List<ClientSnapshot> published = new ArrayList<>();
            runtime.subscribe(published::add);
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            runtime.dispatchWidget("editor.tab.cape");
            assertEquals(CapeCatalogModel.SneakyReadiness.PENDING, runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyReadiness());
            assertFalse(hasSneakyCard(runtime.view(854, 480, 0, 0)));
            while (worker.size() > 0) worker.runFirst();
            assertTrue(published.stream().anyMatch(snapshot -> snapshot.editor().map(editor ->
                    editor.capeCatalog().sneakyReadiness() == CapeCatalogModel.SneakyReadiness.VALUE).orElse(false)));
            assertTrue(hasSneakyCard(runtime.view(854, 480, 0, 0)));
            int reads = operations.skinPreviewCalls.get();
            for (int index = 0; index < 10; index++) {
                runtime.view(320, 240, 0, 0);
                runtime.dispatchWidget("editor.tab.cape");
                runtime.dispatchWidget("editor.preview_mode");
            }
            assertEquals(reads, operations.skinPreviewCalls.get());
            while (worker.size() > 0) worker.runFirst();
            assertEquals(reads, operations.skinPreviewCalls.get());
            runtime.resourcesReloaded();
            runtime.closeScreen();
            while (worker.size() > 0) worker.runFirst();
            assertTrue(runtime.snapshot().editor().isEmpty());
        }
    }

    @Test
    void sneakyExactDraftIgnoresWorldObservationsAndInspectionNeverLeaksIntoSave() throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = new AppearanceProviders(operations.providers.skin(), operations.providers.cape()
                .enable(BuiltinProvider.SNEAKY).observeSneaky(new com.naocraftlab.skins.core.provider.ProviderCape("world:A", "a".repeat(64))));
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0x7f123456);
        try (ClientRuntime runtime = runtime(operations)) {
            runtime.initialize();
            AppearancePreset draft = operations.account.presets().get(0);
            runtime.dispatchWidget("gallery.preset." + draft.id() + ".edit");
            runtime.dispatchWidget("editor.tab.cape");
            var card = runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream()
                    .filter(value -> value.provider() == BuiltinProvider.SNEAKY).findFirst().orElseThrow();
            runtime.dispatchWidget(card.widgetId());
            assertArrayEquals(new com.naocraftlab.skins.core.png.SneakyCapeDecoder().decode(operations.skinPreviewBytes).orElseThrow().bytes(),
                    runtime.loadCapePreview(card.texture().orElseThrow()).join().orElseThrow());
            var providers = runtime.snapshot().providers();
            runtime.dispatchWidget("editor.save");
            assertEquals(draft.skin(), operations.lastEditorRequest.skin());
            assertEquals(Optional.ofNullable(draft.capeId()), operations.lastEditorRequest.capeId());
            assertTrue(operations.lastEditorRequest.pngBytes().isEmpty());
            assertEquals(providers, runtime.snapshot().providers());
            assertTrue(runtime.loadCapePreview(card.texture().orElseThrow()).join().isEmpty());
        }
    }

    @Test
    void sneakyDisableReenableRetiresGenerationWithoutLosingDraft() throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = new AppearanceProviders(operations.providers.skin(), operations.providers.cape().enable(BuiltinProvider.SNEAKY));
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0);
        try (ClientRuntime runtime = runtime(operations)) {
            runtime.initialize();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            runtime.dispatchWidget("editor.tab.cape");
            var before = runtime.snapshot().editor().orElseThrow();
            var card = before.capeCatalog().cards().stream().filter(value -> value.provider() == BuiltinProvider.SNEAKY).findFirst().orElseThrow();
            runtime.dispatchWidget(card.widgetId());
            var accept = ClientRuntime.class.getDeclaredMethod("acceptProviderChange", ClientOperations.DurableAppearance.class);
            accept.setAccessible(true);
            operations.providers = operations.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY);
            accept.invoke(runtime, operations.reloadProviders());
            assertFalse(hasSneakyCard(runtime.view(854, 480, 0, 0)));
            assertTrue(runtime.loadCapePreview(card.texture().orElseThrow()).join().isEmpty());
            operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY);
            accept.invoke(runtime, operations.reloadProviders());
            assertTrue(hasSneakyCard(runtime.view(854, 480, 0, 0)));
            runtime.dispatchWidget(card.widgetId());
            var current = runtime.snapshot().editor().orElseThrow();
            assertNull(current.capeCatalog().inspected());
            assertEquals(before.skin(), current.skin());
            assertEquals(before.capeId(), current.capeId());
            org.junit.jupiter.api.Assertions.assertNotEquals(before.capeCatalog().sneakyTicket(), current.capeCatalog().sneakyTicket());
        }
    }

    @Test
    void sneakyImportedDraftUsesItsOwnBytesAndCancelRetiresAtlas(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = new AppearanceProviders(operations.providers.skin(), operations.providers.cape().enable(BuiltinProvider.SNEAKY));
        byte[] imported = SneakyPreviewPreparationTest.skin(0x7f123456);
        java.nio.file.Path path = directory.resolve("draft.png");
        java.nio.file.Files.write(path, imported);
        assertTrue(operations.loadLocalSkin(path).pngBytes().length > 0);
        FilePicker picker = () -> CompletableFuture.completedFuture(Optional.of(path));
        try (ClientRuntime runtime = new ClientRuntime(new DefaultRuntimeServices(), operations, CLIENT,
                picker, Runnable::run, Runnable::run, Runnable::run, TEXT, Optional.empty(), Optional.empty(), DiagnosticSinks.discarding())) {
            runtime.initialize();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            assertFalse(runtime.snapshot().editor().orElseThrow().busy());
            runtime.dispatchWidget("editor.png.choose");
            runtime.dispatchWidget("editor.tab.cape");
            var editor = runtime.snapshot().editor().orElseThrow();
            assertTrue(editor.png().isPresent());
            assertTrue(hasSneakyCard(runtime.view(854, 480, 0, 0)));
            String texture = runtime.view(854, 480, 0, 0).previews().get(0).capeId().orElseThrow();
            assertArrayEquals(new com.naocraftlab.skins.core.png.SneakyCapeDecoder().decode(editor.png().orElseThrow().bytes()).orElseThrow().bytes(),
                    runtime.loadCapePreview(texture).join().orElseThrow());
            runtime.dispatchWidget("editor.cancel");
            assertTrue(runtime.loadCapePreview(texture).join().isEmpty());
            assertNull(operations.lastEditorRequest);
        }
    }

    @Test
    void sneakyLateAToBToACompletionAdmitsOnlyLatestTicket() throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = new AppearanceProviders(operations.providers.skin(), operations.providers.cape().enable(BuiltinProvider.SNEAKY));
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0x7f123456);
        QueuedExecutor worker = new QueuedExecutor();
        try (ClientRuntime runtime = runtime(operations, worker)) {
            runtime.initialize();
            while (worker.size() > 0) worker.runFirst();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            worker.runFirst();
            long first = runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyTicket();
            runtime.dispatchWidget("editor.model_choice.slim");
            runtime.dispatchWidget("editor.model_choice.classic");
            long current = runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyTicket();
            assertTrue(current > first);
            List<ClientSnapshot> published = new ArrayList<>();
            runtime.subscribe(published::add);
            while (worker.size() > 0) worker.runFirst();
            assertEquals(CapeCatalogModel.SneakyReadiness.VALUE, runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyReadiness());
            assertTrue(published.stream().flatMap(snapshot -> snapshot.editor().stream()).filter(editor ->
                    editor.capeCatalog().sneakyReadiness() == CapeCatalogModel.SneakyReadiness.VALUE)
                    .allMatch(editor -> editor.capeCatalog().sneakyTicket() == current));
        }
    }

    @Test
    void sneakyAccountSwitchClearsPreparedCacheAndOldTexture() throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = new AppearanceProviders(operations.providers.skin(), operations.providers.cape().enable(BuiltinProvider.SNEAKY));
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0);
        QueuedExecutor worker = new QueuedExecutor();
        try (ClientRuntime runtime = runtime(operations, worker)) {
            runtime.initialize();
            while (worker.size() > 0) worker.runFirst();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            while (worker.size() > 0) worker.runFirst();
            String old = runtime.view(854, 480, 0, 0).previews().get(0).capeId().orElseThrow();
            var assetsField = ClientRuntime.class.getDeclaredField("previewAssets");
            assetsField.setAccessible(true);
            var assets = (PreviewAssets) assetsField.get(runtime);
            assertEquals(PreviewAssets.SneakyStatus.VALUE, assets.prepareSneaky(operations.skinPreviewBytes).result().join().status());
            assertEquals(0, worker.size());
            runtime.closeScreen();
            UUID next = new UUID(0, 99);
            operations.account = new AccountState(AccountState.CURRENT_SCHEMA_VERSION, next, operations.account.skinAssets(),
                    operations.account.personalSkins(), operations.account.presets(), operations.account.updatedAt());
            operations.session = new SessionValidation(operations.session.status(),
                    new GameSessionTokenSource.SessionIdentity(next, "SecondAccount"), null, null, "");
            runtime.reopen();
            while (worker.size() > 0) worker.runFirst();
            assertEquals(next, runtime.snapshot().account().orElseThrow().accountId());
            assertTrue(runtime.loadCapePreview(old).join().isEmpty());
            var prepared = assets.prepareSneaky(operations.skinPreviewBytes);
            assertEquals(1, worker.size());
            worker.runFirst();
            assertEquals(PreviewAssets.SneakyStatus.VALUE, prepared.result().join().status());
        }
    }

    @Test
    void sneakyEnabledLoserStaysVisibleAndInspectionResetPreservesWritableAssignment() throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = new AppearanceProviders(operations.providers.skin(), operations.providers.cape().enable(BuiltinProvider.SNEAKY));
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0);
        var reference = new com.naocraftlab.skins.core.model.LocalCapeReference(new UUID(0, 101), "a".repeat(64), false);
        operations.account = operations.account.withPersonalCapes(List.of(new com.naocraftlab.skins.core.model.PersonalCapeEntry(
                reference, reference.sha256(), "Ordinary cape", java.time.Instant.EPOCH)));
        try (ClientRuntime runtime = runtime(operations)) {
            runtime.initialize();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            runtime.dispatchWidget("editor.tab.cape");
            var catalog = runtime.snapshot().editor().orElseThrow().capeCatalog();
            var ordinary = catalog.cards().stream().filter(card -> reference.equals(card.local())).findFirst().orElseThrow();
            runtime.dispatchWidget(ordinary.widgetId());
            var assigned = runtime.snapshot().editor().orElseThrow().capeCatalog();
            String sneakyWidget = assigned.cards().stream().filter(card -> card.provider() == BuiltinProvider.SNEAKY).findFirst().orElseThrow().widgetId();
            assertEquals(BuiltinProvider.OFFLINE, assigned.defaultBundle().source());
            assertTrue(hasSneakyCard(runtime.view(854, 480, 0, 0)));
            runtime.dispatchWidget(sneakyWidget);
            assertTrue(runtime.view(854, 480, 0, 0).previews().get(0).capeId().orElseThrow().startsWith("sneaky:preview:"));
            assertEquals(reference, runtime.snapshot().editor().orElseThrow().capeCatalog().offline());
            runtime.dispatchWidget(sneakyWidget);
            assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().inspected());
            assertEquals(assigned.previewCape(), runtime.view(854, 480, 0, 0).previews().get(0).capeId());
            runtime.dispatchWidget(sneakyWidget);
            runtime.dispatchWidget("editor.tab.cape");
            assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().inspected());
            assertEquals(reference, runtime.snapshot().editor().orElseThrow().capeCatalog().offline());
            assertEquals(assigned.previewCape(), runtime.view(854, 480, 0, 0).previews().get(0).capeId());
        }
    }

    @Test
    void sneakyMissingNativeDefaultBytesRemainFailedWithoutLocalOrWorldLookup() throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = new AppearanceProviders(operations.providers.skin(), operations.providers.cape().enable(BuiltinProvider.SNEAKY));
        var preset = operations.account.presets().get(0);
        var nativeDefault = new AppearancePreset(preset.id(), preset.name(), com.naocraftlab.skins.core.model.SkinReference.accountDefault(),
                preset.capeId(), preset.outerLayerVisibility(), preset.createdAt(), preset.updatedAt());
        operations.account = new AccountState(AccountState.CURRENT_SCHEMA_VERSION, operations.account.accountId(), operations.account.skinAssets(),
                operations.account.personalSkins(), List.of(nativeDefault), operations.account.updatedAt());
        try (ClientRuntime runtime = runtime(operations)) {
            runtime.initialize();
            runtime.dispatchWidget("gallery.preset." + preset.id() + ".edit");
            runtime.dispatchWidget("editor.tab.cape");
            assertEquals(CapeCatalogModel.SneakyReadiness.FAILED, runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyReadiness());
            assertFalse(hasSneakyCard(runtime.view(854, 480, 0, 0)));
            assertEquals(0, operations.skinPreviewCalls.get());
            assertEquals(0, operations.capePreviewCalls.get());
        }
    }

    @Test
    void sneakyFailureRetriesOnlyAtSourceCheckpointAndAbsenceStaysDistinct() throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = new AppearanceProviders(operations.providers.skin(), operations.providers.cape().enable(BuiltinProvider.SNEAKY));
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0);
        operations.failNextSkinPreview = true;
        try (ClientRuntime runtime = runtime(operations)) {
            runtime.initialize();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            runtime.dispatchWidget("editor.tab.cape");
            assertEquals(CapeCatalogModel.SneakyReadiness.FAILED, runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyReadiness());
            int reads = operations.skinPreviewCalls.get();
            for (int index = 0; index < 10; index++) runtime.view(320, 240, 0, 0);
            assertEquals(reads, operations.skinPreviewCalls.get());
            runtime.resourcesReloaded();
            assertEquals(CapeCatalogModel.SneakyReadiness.VALUE, runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyReadiness());
        }
        BufferedImage ordinary = ImageIO.read(new java.io.ByteArrayInputStream(operations.skinPreviewBytes));
        ordinary.setRGB(60, 48, 0);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(ordinary, "png", encoded);
        operations.skinPreviewBytes = encoded.toByteArray();
        try (ClientRuntime runtime = runtime(operations)) {
            runtime.initialize();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            assertEquals(CapeCatalogModel.SneakyReadiness.ABSENCE, runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyReadiness());
        }
    }

    @Test
    void catalogReloadPreservesIndependentSneakyInspection() throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = new AppearanceProviders(operations.providers.skin(), operations.providers.cape().enable(BuiltinProvider.SNEAKY));
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0x7f123456);
        try (ClientRuntime runtime = runtime(operations)) {
            runtime.initialize();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            runtime.dispatchWidget("editor.tab.cape");
            var card = runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream()
                    .filter(value -> value.provider() == BuiltinProvider.SNEAKY).findFirst().orElseThrow();
            runtime.dispatchWidget(card.widgetId());
            String texture = runtime.view(854, 480, 0, 0).previews().get(0).capeId().orElseThrow();
            var reload = ClientRuntime.class.getDeclaredMethod("reloadEditorCatalog");
            reload.setAccessible(true);
            int reads = operations.editorCatalogReads.get();
            reload.invoke(runtime);
            assertEquals(reads + 1, operations.editorCatalogReads.get());
            assertTrue(hasSneakyCard(runtime.view(854, 480, 0, 0)));
            assertEquals(card, runtime.snapshot().editor().orElseThrow().capeCatalog().inspected());
            assertEquals(texture, runtime.view(854, 480, 0, 0).previews().get(0).capeId().orElseThrow());
            assertEquals(1, operations.skinPreviewCalls.get());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void worldChangeRetiresPendingSneakyDraftBeforePublishingCurrentCompletion(boolean decodePending) throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = operations.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY);
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0x7f123456);
        QueuedExecutor worker = new QueuedExecutor();
        try (ClientRuntime runtime = runtime(operations, worker)) {
            runtime.initialize();
            while (worker.size() > 0) worker.runFirst();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            if (decodePending) worker.runFirst();
            long retired = runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyTicket();
            List<ClientSnapshot> published = new ArrayList<>();
            runtime.subscribe(published::add);
            runtime.capeWorldChanged();
            long current = runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyTicket();
            assertTrue(current > retired);
            while (worker.size() > 0) worker.runFirst();
            assertEquals(CapeCatalogModel.SneakyReadiness.VALUE, runtime.snapshot().editor().orElseThrow().capeCatalog().sneakyReadiness());
            assertTrue(published.stream().flatMap(snapshot -> snapshot.editor().stream())
                    .filter(editor -> editor.capeCatalog().sneakyReadiness() == CapeCatalogModel.SneakyReadiness.VALUE)
                    .allMatch(editor -> editor.capeCatalog().sneakyTicket() == current));
            runtime.dispatchWidget("editor.tab.cape");
            var card = runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream()
                    .filter(value -> value.provider() == BuiltinProvider.SNEAKY).findFirst().orElseThrow();
            runtime.dispatchWidget(card.widgetId());
            String oldTexture = runtime.view(854, 480, 0, 0).previews().get(0).capeId().orElseThrow();
            runtime.capeWorldChanged();
            assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().inspected());
            assertTrue(runtime.loadCapePreview(oldTexture).join().isEmpty());
            runtime.dispatchWidget(card.widgetId());
            assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().inspected());
            assertEquals(1, operations.skinPreviewCalls.get());
        }
    }

    @Test
    void resourceInvalidationRetiresSneakyTextureKeyBeforeRepreparingExactDraft() throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = new AppearanceProviders(operations.providers.skin(), operations.providers.cape().enable(BuiltinProvider.SNEAKY));
        operations.skinPreviewBytes = SneakyPreviewPreparationTest.skin(0);
        try (ClientRuntime runtime = runtime(operations)) {
            runtime.initialize();
            runtime.dispatchWidget("gallery.preset." + operations.account.presets().get(0).id() + ".edit");
            runtime.dispatchWidget("editor.tab.cape");
            String previous = runtime.view(854, 480, 0, 0).previews().get(0).capeId().orElseThrow();
            String staleAction = runtime.snapshot().editor().orElseThrow().capeCatalog().cards().stream()
                    .filter(card -> card.provider() == BuiltinProvider.SNEAKY).findFirst().orElseThrow().widgetId();
            runtime.resourcesReloaded();
            String next = runtime.view(854, 480, 0, 0).previews().get(0).capeId().orElseThrow();
            org.junit.jupiter.api.Assertions.assertNotEquals(previous, next);
            assertTrue(runtime.loadCapePreview(previous).join().isEmpty());
            assertTrue(runtime.loadCapePreview(next).join().isPresent());
            runtime.dispatchWidget(staleAction);
            assertNull(runtime.snapshot().editor().orElseThrow().capeCatalog().inspected());
            runtime.closeScreen();
            assertTrue(runtime.loadCapePreview(next).join().isEmpty());
        }
    }

    @Test
    void reloadPublishesAChangedSkinExtensionEnvironmentWithoutTickPolling() {
        StubOperations operations = new StubOperations();
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<SkinExtensionEnvironmentSource.Snapshot> environment =
                new AtomicReference<>(new SkinExtensionEnvironmentSource.Snapshot(
                        1, java.util.Map.of(
                                SkinExtensionEnvironmentSource.Consumer.FRESH_MOVES,
                                SkinExtensionEnvironmentSource.State.INACTIVE)));
        ClientRuntime runtime = runtime(operations).useSkinExtensionEnvironmentSource(() -> {
            reads.incrementAndGet();
            return environment.get();
        });
        runtime.initialize();
        assertEquals(1, runtime.snapshot().skinExtensionEnvironment().generation());
        assertEquals(1, reads.get());

        environment.set(new SkinExtensionEnvironmentSource.Snapshot(
                2, java.util.Map.of(
                        SkinExtensionEnvironmentSource.Consumer.FRESH_MOVES,
                        SkinExtensionEnvironmentSource.State.ACTIVE)));
        runtime.tick();
        runtime.tick();
        runtime.closeScreen();
        runtime.reopen();

        assertEquals(1, runtime.snapshot().skinExtensionEnvironment().generation());
        assertEquals(1, reads.get());

        runtime.resourcesReloaded();

        assertEquals(2, runtime.snapshot().skinExtensionEnvironment().generation());
        assertEquals(2, reads.get());
        assertEquals(
                com.naocraftlab.skins.core.compatibility.SkinConsumerState.ACTIVE,
                runtime.snapshot().skinExtensionEnvironment().state(
                        com.naocraftlab.skins.core.compatibility.SkinConsumer.FRESH_MOVES));
    }

    @Test
    void cpmSettingsEventUpdatesFrozenSnapshotWithoutResourceReloadAndUnsubscribesOnClose() {
        StubOperations operations = new StubOperations();
        AtomicReference<Runnable> changed = new AtomicReference<>();
        AtomicReference<SkinExtensionEnvironmentSource.State> cpm = new AtomicReference<>(SkinExtensionEnvironmentSource.State.ACTIVE);
        AtomicInteger reads = new AtomicInteger();
        SkinExtensionEnvironmentSource source = new SkinExtensionEnvironmentSource() {
            public Snapshot snapshot() {
                return new Snapshot(reads.incrementAndGet(), Map.of(Consumer.CUSTOMIZABLE_PLAYER_MODELS, cpm.get()));
            }
            public Subscription subscribe(Runnable listener) {
                changed.set(listener);
                return () -> changed.compareAndSet(listener, null);
            }
        };
        ClientRuntime runtime = runtime(operations).useSkinExtensionEnvironmentSource(source);
        runtime.initialize();
        var frozen = runtime.snapshot().skinExtensionEnvironment();
        cpm.set(SkinExtensionEnvironmentSource.State.INACTIVE);
        changed.get().run();
        assertEquals(com.naocraftlab.skins.core.compatibility.SkinConsumerState.ACTIVE,
                frozen.state(com.naocraftlab.skins.core.compatibility.SkinConsumer.CUSTOMIZABLE_PLAYER_MODELS));
        assertEquals(com.naocraftlab.skins.core.compatibility.SkinConsumerState.INACTIVE,
                runtime.snapshot().skinExtensionEnvironment().state(com.naocraftlab.skins.core.compatibility.SkinConsumer.CUSTOMIZABLE_PLAYER_MODELS));
        assertEquals(2, reads.get());
        runtime.tick();
        assertEquals(2, reads.get());
        runtime.close();
        assertNull(changed.get());
    }

    @Test
    void cpmCarrierUsesTheSharedGalleryEditorAndProvidersIndicatorWithoutFiltering() throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = new AppearanceProviders(
                operations.providers.skin().select(1, new ProviderSkin("1".repeat(64), SkinVariant.CLASSIC)),
                operations.providers.cape());
        operations.skinPreviewBytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../core/src/test/resources/cpm/classic-a.png"));
        ClientRuntime runtime = runtime(operations).useSkinExtensionEnvironmentSource(() ->
                new SkinExtensionEnvironmentSource.Snapshot(1, Map.of(SkinExtensionEnvironmentSource.Consumer.CUSTOMIZABLE_PLAYER_MODELS, SkinExtensionEnvironmentSource.State.INACTIVE)));
        runtime.initialize();
        AppearancePreset preset = runtime.snapshot().account().orElseThrow().presets().get(0);
        ViewSpec.Preview preview = runtime.view(854, 480, 0, 0).previews().stream()
                .filter(candidate -> candidate.skin().equals(preset.skin())).findFirst().orElseThrow();
        runtime.loadSkinPreview(preview).join();
        String id = "gallery.preset." + preset.id();
        var marker = runtime.view(854, 480, 0, 0).widget(id + ".compatibility").orElseThrow();
        assertEquals(Optional.of(GuiIcon.STATUS_COMPATIBILITY_EXTENDED), marker.icon());
        assertTrue(marker.label().arguments().toString().contains("customizable_player_models"));
        runtime.dispatchWidget(id + ".edit");
        assertEquals(marker.label(), runtime.view(854, 480, 0, 0).widget("editor.compatibility").orElseThrow().label());
        runtime.dispatchWidget("editor.cancel");
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.row.OFFLINE");
        assertEquals(marker.label(), runtime.view(854, 480, 0, 0).widget("providers.compatibility").orElseThrow().label());
        assertEquals(preset.skin(), runtime.snapshot().account().orElseThrow().presets().get(0).skin());
    }

    @Test
    void unavailableEnvironmentRetriesOnlyOnReload() {
        StubOperations operations = new StubOperations();
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<SkinExtensionEnvironmentSource.Snapshot> environment =
                new AtomicReference<>();
        ClientRuntime runtime = runtime(operations).useSkinExtensionEnvironmentSource(() -> {
            reads.incrementAndGet();
            SkinExtensionEnvironmentSource.Snapshot current = environment.get();
            if (current == null) {
                throw new IllegalStateException("unavailable");
            }
            return current;
        });

        runtime.initialize();
        runtime.tick();
        assertEquals(0, runtime.snapshot().skinExtensionEnvironment().generation());
        assertEquals(1, reads.get());

        environment.set(new SkinExtensionEnvironmentSource.Snapshot(
                4, java.util.Map.of(
                        SkinExtensionEnvironmentSource.Consumer.FRESH_MOVES,
                        SkinExtensionEnvironmentSource.State.INACTIVE)));
        runtime.resourcesReloaded();

        assertEquals(4, runtime.snapshot().skinExtensionEnvironment().generation());
        assertEquals(2, reads.get());
    }

    @Test void cancellingOrClosingDraftRetiresOnlyItsSyntheticSkinSource() {
        ClientRuntime runtime = runtime(new StubOperations());
        runtime.initialize();
        AppearancePreset preset = runtime.snapshot().account().orElseThrow().presets().get(0);
        runtime.dispatchWidget("gallery.preset." + preset.id() + ".edit");
        UUID actor = UUID.randomUUID(), preview = UUID.randomUUID();
        try (var registration = CapeProjection.installEvents(TestCapeProjection.events())) {
            assertTrue(registration.active());
            var world = CapeProjection.effectiveSkin(actor, actor, "nclskins:world-a", com.naocraftlab.skins.client.SkinModel.CLASSIC, false);
            var draft = CapeProjection.effectiveSkin(preview, actor, "nclskins:draft-b", com.naocraftlab.skins.client.SkinModel.SLIM, true);
            runtime.dispatchWidget("editor.cancel");
            assertFalse(draft.active());
            assertTrue(world.active());
            runtime.dispatchWidget("gallery.preset." + preset.id() + ".edit");
            var next = CapeProjection.effectiveSkin(preview, actor, "nclskins:draft-c", com.naocraftlab.skins.client.SkinModel.CLASSIC, true);
            runtime.closeScreen();
            assertFalse(next.active());
            assertTrue(world.active());
        }
    }

    @Test
    void storedLegacyPaddingShowsWarningInGalleryAndEditorWhenPreviewIsReused() throws Exception {
        StubOperations operations = new StubOperations();
        BufferedImage skin = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        skin.setRGB(4, 0, 0xff123456);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(skin, "png", output);
        operations.skinPreviewBytes = output.toByteArray();
        ClientRuntime runtime = runtime(operations).useSkinExtensionEnvironmentSource(() ->
                new SkinExtensionEnvironmentSource.Snapshot(
                        3, java.util.Map.of(
                                SkinExtensionEnvironmentSource.Consumer.FRESH_MOVES,
                                SkinExtensionEnvironmentSource.State.ACTIVE)));
        runtime.initialize();
        AppearancePreset preset = runtime.snapshot().account().orElseThrow().presets().get(0);
        UUID presetId = preset.id();
        ViewSpec.Preview galleryPreview = runtime.view(854, 480, 0, 0).previews().stream()
                .filter(candidate -> candidate.skin().equals(preset.skin()))
                .findFirst().orElseThrow();
        runtime.loadSkinPreview(galleryPreview).join();
        assertEquals(1, operations.skinPreviewCalls.get());
        ViewSpec gallery = runtime.view(854, 480, 0, 0);
        assertEquals(Optional.of(GuiIcon.STATUS_COMPATIBILITY_INCOMPATIBLE), gallery.widget(
                        "gallery.preset." + presetId + ".compatibility")
                .orElseThrow().icon());

        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        TestFixtures.confirmOrdinaryDisplay(runtime);
        ViewSpec editor = runtime.view(854, 480, 0, 0);

        assertEquals(1, operations.skinPreviewCalls.get());
        ViewSpec.Widget indicator = editor.widget("editor.compatibility").orElseThrow();
        assertEquals(ViewSpec.WidgetKind.PASSIVE_INDICATOR, indicator.kind());
        assertTrue(indicator.enabled());
        assertEquals(Optional.of(indicator.label()), indicator.hint());
        assertEquals("nclskins.compatibility.tooltip.1", indicator.label().key());
        assertEquals(
                editor.widgets().indexOf(editor.widget("editor.outer_layer.legs").orElseThrow()) + 1,
                editor.widgets().indexOf(indicator));
        assertEquals(Optional.of(GuiIcon.STATUS_COMPATIBILITY_INCOMPATIBLE), indicator.icon());
        assertEquals(20, indicator.bounds().width());
        assertEquals(20, indicator.bounds().height());
        assertTrue(editor.iconDecorations().stream().noneMatch(icon ->
                icon.ownerWidgetId().equals(indicator.id())));
    }

    @Test
    void providersCapePreviewShowsSneakyConflictFromDisplayedSkin() throws Exception {
        StubOperations operations = new StubOperations();
        operations.providers = new AppearanceProviders(
                operations.providers.skin().select(1, new ProviderSkin("1".repeat(64), SkinVariant.CLASSIC))
                        .observeMinecraft(new ProviderSkin("2".repeat(64), SkinVariant.SLIM)),
                operations.providers.cape().enable(BuiltinProvider.SNEAKY));
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int[] marker = {0xfffff42f, 0xffffffff, 0xff9c59d1, 0xff292929};
        for (int index = 0; index < marker.length; index++) skin.setRGB(60, 48 + index, marker[index]);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(skin, "png", output);
        operations.skinPreviewBytes = output.toByteArray();
        ByteArrayOutputStream ordinary = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", ordinary);
        operations.skinPreviewByAsset = Map.of(TestFixtures.CLASSIC_ID, output.toByteArray(),
                TestFixtures.SLIM_ID, ordinary.toByteArray());
        ClientRuntime runtime = runtime(operations).useSkinExtensionEnvironmentSource(() ->
                new SkinExtensionEnvironmentSource.Snapshot(3, Map.of(
                        SkinExtensionEnvironmentSource.Consumer.FRESH_MOVES,
                        SkinExtensionEnvironmentSource.State.ACTIVE)));
        runtime.initialize();
        ViewSpec.Preview preview = runtime.view(854, 480, 0, 0).previews().stream()
                .filter(candidate -> candidate.skin().equals(operations.account.presets().get(0).skin()))
                .findFirst().orElseThrow();
        runtime.loadSkinPreview(preview).join();
        assertTrue(runtime.snapshot().assetEvidence().containsKey(TestFixtures.CLASSIC_ID));
        ViewSpec.Preview slimPreview = runtime.view(854, 480, 0, 0).previews().stream()
                .filter(candidate -> candidate.skin().equals(operations.account.presets().get(1).skin()))
                .findFirst().orElseThrow();
        runtime.loadSkinPreview(slimPreview).join();
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.tab.CAPE");
        ViewSpec providers = runtime.view(854, 480, 0, 0);
        assertEquals("provider:skin:" + "1".repeat(64), providers.previews().get(0).imageRevision());
        assertEquals(Optional.of(GuiIcon.STATUS_COMPATIBILITY_INCOMPATIBLE), providers.widget(
                "providers.compatibility").orElseThrow().icon());
        assertTrue(providers.widget("providers.compatibility").orElseThrow().label().key()
                .startsWith("nclskins.compatibility.tooltip."));
        runtime.dispatchWidget("providers.tab.SKIN");
        runtime.dispatchWidget("providers.row.MINECRAFT");
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.compatibility").isEmpty());
        runtime.dispatchWidget("providers.row.OFFLINE");
        assertEquals(Optional.of(GuiIcon.STATUS_COMPATIBILITY_INCOMPATIBLE),
                runtime.view(854, 480, 0, 0).widget("providers.compatibility").orElseThrow().icon());
        runtime.dispatchWidget("providers.tab.CAPE");
        runtime.dispatchWidget("providers.row.SNEAKY");
        assertEquals(Optional.of(GuiIcon.STATUS_COMPATIBILITY_INCOMPATIBLE),
                runtime.view(854, 480, 0, 0).widget("providers.compatibility").orElseThrow().icon());
    }

    @Test
    void providersSelectedSneakySkinWarnsWhileFinalSkinIsCompatible() throws Exception {
        StubOperations operations = new StubOperations();
        var ordinarySkin = new ProviderSkin("2".repeat(64), SkinVariant.SLIM);
        var sneakySkin = new ProviderSkin("1".repeat(64), SkinVariant.CLASSIC);
        operations.providers = new AppearanceProviders(
                operations.providers.skin().select(1, ordinarySkin).observeMinecraft(sneakySkin),
                operations.providers.cape().enable(BuiltinProvider.SNEAKY));
        BufferedImage marked = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int[] marker = {0xfffff42f, 0xffffffff, 0xff9c59d1, 0xff292929};
        for (int index = 0; index < marker.length; index++) marked.setRGB(60, 48 + index, marker[index]);
        ByteArrayOutputStream sneaky = new ByteArrayOutputStream();
        ByteArrayOutputStream ordinary = new ByteArrayOutputStream();
        ImageIO.write(marked, "png", sneaky);
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", ordinary);
        operations.skinPreviewByAsset = Map.of(TestFixtures.CLASSIC_ID, sneaky.toByteArray(),
                TestFixtures.SLIM_ID, ordinary.toByteArray());
        ClientRuntime runtime = runtime(operations).useSkinExtensionEnvironmentSource(() ->
                new SkinExtensionEnvironmentSource.Snapshot(4, Map.of(
                        SkinExtensionEnvironmentSource.Consumer.JUST_EXPRESSIONS,
                        SkinExtensionEnvironmentSource.State.ACTIVE)));
        runtime.initialize();
        for (ViewSpec.Preview preview : runtime.view(854, 480, 0, 0).previews()) {
            runtime.loadSkinPreview(preview).join();
        }
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.tab.SKIN");
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.compatibility").isEmpty());
        runtime.dispatchWidget("providers.row.MINECRAFT");
        assertEquals(Optional.of(GuiIcon.STATUS_COMPATIBILITY_INCOMPATIBLE),
                runtime.view(854, 480, 0, 0).widget("providers.compatibility").orElseThrow().icon());
        runtime.dispatchWidget("providers.row.OFFLINE");
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.compatibility").isEmpty());
    }

    @Test
    void editorSavePublishesAccountAndGallerySelectionTogetherAndCanReopenSavedDraft() {
        StubOperations operations = new StubOperations();
        ClientRuntime runtime = runtime(operations);
        runtime.initialize();
        UUID presetId = operations.account.presets().get(1).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        assertEquals(Optional.of(presetId), runtime.snapshot().selectedPresetId());
        runtime.dispatchText("editor.name", "Saved through flow boundary");

        runtime.dispatchWidget("editor.save");

        ClientSnapshot saved = runtime.snapshot();
        assertFalse(saved.busy());
        assertTrue(saved.editor().isEmpty());
        assertTrue(saved.addSource().isEmpty());
        assertEquals(Optional.of(presetId), saved.selectedPresetId());
        assertEquals("Saved through flow boundary", saved.account().orElseThrow().presets().stream()
                .filter(preset -> preset.id().equals(presetId)).findFirst().orElseThrow().name());
        assertEquals("gallery", runtime.view(854, 480, 0, 0).screenId());
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        assertEquals("Saved through flow boundary", runtime.snapshot().editor().orElseThrow().name());
    }

    @Test
    void failedEditorSaveReleasesBusyPreservesDraftAndCanStillBeCancelled() {
        StubOperations operations = new StubOperations();
        operations.failEditorSave = true;
        ClientRuntime runtime = runtime(operations);
        runtime.initialize();
        runtime.view(854, 480, 0, 0);

        UUID presetId = runtime.snapshot().account().orElseThrow().presets().get(0).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        runtime.dispatchText("editor.name", "Draft survives");
        runtime.dispatchWidget("editor.model_choice.slim");
        PresetEditorModel beforeSave = runtime.snapshot().editor().orElseThrow();

        runtime.dispatchWidget("editor.save");

        ClientSnapshot failed = runtime.snapshot();
        PresetEditorModel retained = failed.editor().orElseThrow();
        assertFalse(failed.busy());
        assertFalse(retained.busy());
        assertEquals("Draft survives", retained.name());
        assertEquals(beforeSave.skin(), retained.skin());
        assertEquals(beforeSave.variant(), retained.variant());
        assertEquals(beforeSave.capeId(), retained.capeId());
        assertEquals("nclskins.error.save", retained.status().orElseThrow().key());
        assertEquals("nclskins.error.save", failed.status().key());

        runtime.dispatchWidget("editor.cancel");

        assertTrue(runtime.snapshot().editor().isEmpty());
        assertEquals("gallery", runtime.view(854, 480, 0, 0).screenId());
    }

    @Test
    @SuppressWarnings("deprecation")
    void unsafePreviousRemoteRestoreIsNotPublishedOrDispatched() {
        StubOperations operations = new StubOperations();
        ClientRuntime runtime = runtime(operations);
        runtime.initialize();
        UUID presetId = operations.account.presets().get(1).id();

        operations.mutationResult = MutationResult.PARTIAL;
        operations.recoveryActions = Set.of(
                RecoveryAction.RETRY_CAPE,
                RecoveryAction.RESTORE_PREVIOUS_APPEARANCE);
        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        assertEquals(Optional.of(presetId), runtime.snapshot().selectedPresetId());
        assertTrue(runtime.snapshot().activePresetId().isEmpty());
        assertTrue(runtime.snapshot().recoveryActions().contains(RecoveryAction.RETRY_CAPE));
        assertFalse(runtime.snapshot().recoveryActions()
                .contains(RecoveryAction.RESTORE_PREVIOUS_APPEARANCE));
        assertTrue(runtime.view(854, 480, 0, 0).widget("gallery.restore").isEmpty());

        runtime.dispatchWidget("gallery.restore");
        runtime.activateCape("legacy-cape");
        runtime.hideCape();

        assertEquals(0, operations.restoreCalls);
        assertEquals(0, operations.retryCapeCalls);
    }

    @Test
    void deferredLocalRefreshKeepsPartialMutationErrorAsTheLeadingStatus() {
        StubOperations operations = new StubOperations();
        operations.mutationResult = MutationResult.PARTIAL;
        AppearanceRefreshCoordinator<Object> refresh = new AppearanceRefreshCoordinator<>(
                CLIENT,
                ignored -> CompletableFuture.completedFuture(Optional.empty()),
                ignored -> PlayerAppearanceSink.ApplyResult.DEFERRED,
                DiagnosticSinks.discarding());
        ClientRuntime runtime = new ClientRuntime(new com.naocraftlab.skins.runtime.DefaultRuntimeServices(),
                operations,
                CLIENT,
                CANCELLED_PICKER,
                Runnable::run,
                TEXT,
                Optional.of(refresh),
                DiagnosticSinks.discarding());
        runtime.initialize();
        UUID presetId = operations.account.presets().get(1).id();

        runtime.dispatchWidget("gallery.preset." + presetId + ".apply");

        UiMessage status = runtime.snapshot().status();
        assertEquals(
                "The skin was applied, but the cape needs attention. "
                        + "The local player preview will update after reconnecting.",
                status.key());
        assertEquals(UiMessage.Severity.ERROR, status.severity());
        assertTrue(status.literal());
        assertTrue(status.arguments().isEmpty());
    }

    @Test
    void editorPublishesCanonicalSkinAndCapePreviewErrors() {
        StubOperations operations = new StubOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker);
        runtime.initialize();
        worker.runFirst();
        UUID presetId = operations.account.presets().get(1).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        ViewSpec.Preview preview = runtime.view(854, 480, 0, 0).previews().get(0);

        operations.failNextSkinPreview = true;
        CompletableFuture<Optional<byte[]>> skin = runtime.loadSkinPreview(preview);
        worker.runFirst();
        worker.runFirst();

        assertTrue(skin.join().isEmpty());
        assertEquals(
                "nclskins.error.preview",
                runtime.snapshot().editor().orElseThrow().status().orElseThrow().key());

        operations.failNextCapePreview = true;
        CompletableFuture<Optional<byte[]>> cape = runtime.loadCapePreview(preview);
        worker.runFirst();

        assertTrue(cape.join().isEmpty());
        assertEquals(
                "nclskins.error.cape_preview",
                runtime.snapshot().editor().orElseThrow().status().orElseThrow().key());
    }

    @Test
    void successfulPreviewRetriesClearOnlyTheirMatchingEditorError() {
        StubOperations operations = new StubOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker);
        runtime.initialize();
        worker.runFirst();
        UUID presetId = operations.account.presets().get(1).id();
        runtime.dispatchWidget("gallery.preset." + presetId + ".edit");
        worker.runFirst();
        ViewSpec.Preview preview = runtime.view(854, 480, 0, 0).previews().get(0);
        worker.runFirst();

        operations.failNextCapePreview = true;
        CompletableFuture<Optional<byte[]>> failedCape = runtime.loadCapePreview(preview);
        worker.runFirst();
        assertTrue(failedCape.join().isEmpty());
        assertEquals(
                "nclskins.error.cape_preview",
                runtime.snapshot().editor().orElseThrow().status().orElseThrow().key());

        CompletableFuture<Optional<byte[]>> successfulSkin = runtime.loadSkinPreview(preview);
        assertTrue(successfulSkin.join().isPresent());
        assertEquals(
                "nclskins.error.cape_preview",
                runtime.snapshot().editor().orElseThrow().status().orElseThrow().key());

        CompletableFuture<Optional<byte[]>> successfulCape = runtime.loadCapePreview(preview);
        worker.runFirst();
        assertTrue(successfulCape.join().isPresent());
        assertTrue(runtime.snapshot().editor().orElseThrow().status().isEmpty());
    }

    @Test
    void identicalConcurrentSkinAndCapePreviewsCoalesceAndPublishIndependentArrays() {
        StubOperations operations = new StubOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker);
        SkinReference skin = SkinReference.asset(TestFixtures.CLASSIC_ID);

        CompletableFuture<Optional<byte[]>> firstSkin = runtime.loadSkinPreview(skin);
        CompletableFuture<Optional<byte[]>> secondSkin = runtime.loadSkinPreview(skin);
        assertEquals(1, worker.size());
        worker.runFirst();

        byte[] firstSkinBytes = firstSkin.join().orElseThrow();
        byte[] secondSkinBytes = secondSkin.join().orElseThrow();
        assertEquals(1, operations.skinPreviewCalls.get());
        assertArrayEquals(new byte[] {1, 2, 3}, firstSkinBytes);
        assertArrayEquals(firstSkinBytes, secondSkinBytes);
        assertNotSame(firstSkinBytes, secondSkinBytes);
        firstSkinBytes[0] = 99;
        assertArrayEquals(new byte[] {1, 2, 3}, secondSkinBytes);

        CompletableFuture<Optional<byte[]>> firstCape = runtime.loadCapePreview("cape-shared");
        CompletableFuture<Optional<byte[]>> secondCape = runtime.loadCapePreview("cape-shared");
        assertEquals(1, worker.size());
        worker.runFirst();

        byte[] firstCapeBytes = firstCape.join().orElseThrow();
        byte[] secondCapeBytes = secondCape.join().orElseThrow();
        assertEquals(1, operations.capePreviewCalls.get());
        assertArrayEquals(new byte[] {4, 5, 6}, firstCapeBytes);
        assertArrayEquals(firstCapeBytes, secondCapeBytes);
        assertNotSame(firstCapeBytes, secondCapeBytes);
        firstCapeBytes[0] = 99;
        assertArrayEquals(new byte[] {4, 5, 6}, secondCapeBytes);
    }

    @Test
    void failedPreviewClearsInflightSoTheSameKeyCanBeRetried() {
        StubOperations operations = new StubOperations();
        operations.failNextSkinPreview = true;
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker);
        SkinReference skin = SkinReference.asset(TestFixtures.CLASSIC_ID);

        CompletableFuture<Optional<byte[]>> first = runtime.loadSkinPreview(skin);
        CompletableFuture<Optional<byte[]>> joined = runtime.loadSkinPreview(skin);
        worker.runFirst();

        assertTrue(first.join().isEmpty());
        assertTrue(joined.join().isEmpty());
        assertEquals(1, operations.skinPreviewCalls.get());

        CompletableFuture<Optional<byte[]>> retry = runtime.loadSkinPreview(skin);
        assertEquals(1, worker.size());
        worker.runFirst();

        assertArrayEquals(new byte[] {1, 2, 3}, retry.join().orElseThrow());
        assertEquals(2, operations.skinPreviewCalls.get());
    }

    @Test
    void startupWarmupIsIdempotentAndDoesNotEnterTheGalleryLifecycle() {
        StubOperations operations = new StubOperations();
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker);
        ClientSnapshot beforeWarmup = runtime.snapshot();

        runtime.warmSession();
        runtime.warmSession();

        assertEquals(ClientSnapshot.Lifecycle.NEW, runtime.snapshot().lifecycle());
        assertEquals(beforeWarmup, runtime.snapshot());
        assertEquals(0, operations.initializeCalls.get());
        assertEquals(0, operations.warmSessionCalls.get());
        assertEquals(1, worker.size());

        worker.runFirst();
        runtime.warmSession();

        assertEquals(1, operations.warmSessionCalls.get());
        assertEquals(0, operations.initializeCalls.get());
        assertEquals(ClientSnapshot.Lifecycle.NEW, runtime.snapshot().lifecycle());
        assertEquals(0, worker.size());

        runtime.initialize();
        assertEquals(ClientSnapshot.Lifecycle.INITIALIZING, runtime.snapshot().lifecycle());
        assertEquals(0, operations.initializeCalls.get());
        assertEquals(1, worker.size());
        worker.runFirst();

        assertEquals(1, operations.initializeCalls.get());
        assertEquals(1, operations.warmSessionCalls.get());
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
    }

    @Test
    void startupWarmupSchedulesRevisionZeroProfileCheckpoint() {
        StubOperations operations = new StubOperations();
        operations.warmCheckpoint = true;
        QueuedExecutor worker = new QueuedExecutor();
        ClientRuntime runtime = runtime(operations, worker);

        runtime.warmSession();
        worker.runFirst();

        assertEquals(ClientSnapshot.Lifecycle.NEW, runtime.snapshot().lifecycle());
        assertEquals(1, worker.size());
        worker.runFirst();

        assertEquals(1, operations.reconciliationCalls.get());
        assertEquals(
                List.of(Trigger.PROCESS_START),
                operations.reconciliationTriggers);
        assertFalse(runtime.snapshot().syncInProgress());
    }

    @Test
    void menuPreviewReadsEveryEffectiveMaskWithoutApplyingItOrCachingTextures() {
        var visibility = new TestVisibility();
        var skin = new TextureRegistry.TextureHandle("nclskins:test", 64, 64);
        var cape = new TextureRegistry.TextureHandle("nclskins:cape", 64, 32);
        var current = new AtomicReference<CurrentPlayerAppearanceSource.PlayerAppearance>();
        try (var runtime = menuRuntime(CLIENT, Optional.of(current::get), Optional.of(visibility))) {
            for (SkinModel model : SkinModel.values()) {
                for (boolean withCape : new boolean[]{false, true}) {
                    current.set(new CurrentPlayerAppearanceSource.PlayerAppearance(
                            skin, model, withCape ? Optional.of(cape) : Optional.empty()));
                    for (int bits = 0; bits < 64; bits++) {
                        var parts = java.util.EnumSet.noneOf(OuterLayerPart.class);
                        for (OuterLayerPart part : OuterLayerPart.values()) {
                            if ((bits & (1 << part.ordinal())) != 0) parts.add(part);
                        }
                        visibility.value = OuterLayerVisibility.of(parts);
                        var preview = runtime.menuPreviewAppearance().orElseThrow();
                        assertEquals(visibility.value, preview.outerLayerVisibility());
                        assertEquals(skin, preview.skin());
                        assertEquals(model, preview.model());
                        assertEquals(current.get().cape(), preview.cape());
                        assertEquals(withCape ? PreviewRenderer.CapeMode.CAPE : PreviewRenderer.CapeMode.OFF,
                                preview.capeMode());
                    }
                }
            }
            visibility.value = OuterLayerVisibility.noneVisible();
            var first = runtime.menuPreviewAppearance().orElseThrow();
            visibility.value = OuterLayerVisibility.of(Set.of(OuterLayerPart.LEFT_ARM, OuterLayerPart.RIGHT_LEG));
            assertEquals(visibility.value, runtime.menuPreviewAppearance().orElseThrow().outerLayerVisibility());
            visibility.value = OuterLayerVisibility.allVisible();
            assertEquals(visibility.value, runtime.menuPreviewAppearance().orElseThrow().outerLayerVisibility());
            assertEquals(OuterLayerVisibility.noneVisible(), first.outerLayerVisibility());
            assertTrue(visibility.applied.isEmpty());
        }
    }

    @Test
    void menuPreviewReadsPublishedSelfCapeWithoutRefreshingOrLeakingAnotherIdentity() {
        StubOperations operations = new StubOperations();
        var identity = operations.session.sessionIdentity();
        var self = new CapeProjection.Identity(identity.profileId(), identity.profileName());
        var stale = new TextureRegistry.TextureHandle("nclskins:stale", 64, 32);
        var skin = new TextureRegistry.TextureHandle("nclskins:skin", 64, 64);
        var visibility = new TestVisibility();
        var sourceReads = new AtomicInteger();
        CurrentPlayerAppearanceSource source = () -> {
            sourceReads.incrementAndGet();
            return new CurrentPlayerAppearanceSource.PlayerAppearance(
                    skin, SkinModel.SLIM, Optional.of(stale));
        };
        try (var runtime = new ClientRuntime(new com.naocraftlab.skins.runtime.DefaultRuntimeServices(), operations, CLIENT, CANCELLED_PICKER, Runnable::run,
                TEXT, Optional.of(source), Optional.empty(), Optional.of(visibility), Optional.empty(),
                DiagnosticSinks.discarding())) {
            var optifine = new CapeProjection.Candidate("nclskins:optifine", null, false);
            var skinmc = new CapeProjection.Candidate("nclskins:skinmc", "nclskins:skinmc", true);
            TestCapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.OPTIFINE, BuiltinProvider.SKINMC, BuiltinProvider.MINECRAFT),
                    Map.of(self, optifine), Map.of(self, skinmc), self, null, null));
            var first = runtime.menuPreviewAppearance().orElseThrow();
            assertEquals("nclskins:optifine", first.cape().orElseThrow().location());
            assertFalse(first.capeHasElytra());
            assertEquals(skin, first.skin());
            assertEquals(SkinModel.SLIM, first.model());
            assertEquals(visibility.value, first.outerLayerVisibility());

            TestCapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.SKINMC, BuiltinProvider.OPTIFINE, BuiltinProvider.MINECRAFT),
                    Map.of(self, optifine), Map.of(self, skinmc), self, null, null));
            var second = runtime.menuPreviewAppearance().orElseThrow();
            assertEquals("nclskins:skinmc", second.cape().orElseThrow().location());
            assertTrue(second.capeHasElytra());

            TestCapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.SKINMC, BuiltinProvider.OPTIFINE, BuiltinProvider.MINECRAFT),
                    Map.of(self, optifine), Map.of(), self, null, null));
            assertEquals("nclskins:optifine",
                    runtime.menuPreviewAppearance().orElseThrow().cape().orElseThrow().location());

            TestCapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.SKINMC, BuiltinProvider.OPTIFINE, BuiltinProvider.MINECRAFT),
                    Map.of(), Map.of(), self, null, null));
            var absent = runtime.menuPreviewAppearance().orElseThrow();
            assertTrue(absent.cape().isEmpty());
            assertEquals(PreviewRenderer.CapeMode.OFF, absent.capeMode());

            var other = new CapeProjection.Identity(UUID.randomUUID(), identity.profileName());
            TestCapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.SKINMC), Map.of(), Map.of(other, skinmc), other, null, null));
            assertEquals(stale, runtime.menuPreviewAppearance().orElseThrow().cape().orElseThrow());
            var renamed = new CapeProjection.Identity(identity.profileId(), "OtherName");
            TestCapeProjection.publish(new CapeProjection.Snapshot(
                    List.of(BuiltinProvider.SKINMC), Map.of(), Map.of(renamed, skinmc), renamed, null, null));
            assertEquals(stale, runtime.menuPreviewAppearance().orElseThrow().cape().orElseThrow());
            assertEquals(6, sourceReads.get());
            assertEquals(0, operations.initializeCalls.get());
            assertEquals(0, operations.warmSessionCalls.get());
        } finally {
            TestCapeProjection.clear();
        }
    }

    @Test
    void menuPreviewHidesUnavailableInputsAndRecoversOnTheNextRead() {
        var visibility = new TestVisibility();
        var sourceFailure = new AtomicReference<RuntimeException>();
        CurrentPlayerAppearanceSource source = () -> {
            if (sourceFailure.get() != null) throw sourceFailure.get();
            return menuPlayerAppearance();
        };
        try (var runtime = menuRuntime(CLIENT, Optional.empty(), Optional.of(visibility))) {
            assertTrue(runtime.menuPreviewAppearance().isEmpty());
        }
        try (var runtime = menuRuntime(CLIENT, Optional.of(source), Optional.empty())) {
            assertTrue(runtime.menuPreviewAppearance().isEmpty());
        }
        try (var runtime = menuRuntime(CLIENT, Optional.of(source), Optional.of(visibility))) {
            sourceFailure.set(new IllegalStateException());
            assertTrue(runtime.menuPreviewAppearance().isEmpty());
            sourceFailure.set(null);
            visibility.failure = new IllegalStateException();
            assertTrue(runtime.menuPreviewAppearance().isEmpty());
            visibility.failure = null;
            visibility.value = OuterLayerVisibility.noneVisible();
            assertEquals(visibility.value, runtime.menuPreviewAppearance().orElseThrow().outerLayerVisibility());
            assertTrue(visibility.applied.isEmpty());
        }
        try (var runtime = menuRuntime(CLIENT, Optional.of(() -> { throw new AssertionError(); }),
                Optional.of(visibility))) {
            assertThrows(AssertionError.class, runtime::menuPreviewAppearance);
        }
    }

    @Test
    void menuPreviewRequiresAClientThreadAndAnOpenRuntime() {
        var onClient = new java.util.concurrent.atomic.AtomicBoolean(true);
        ClientExecutor client = new ClientExecutor() {
            @Override
            public boolean isClientThread() { return onClient.get(); }
            @Override
            public void execute(Runnable action) { action.run(); }
        };
        var runtime = menuRuntime(client, Optional.of(ClientRuntimeRegressionTest::menuPlayerAppearance),
                Optional.of(new TestVisibility()));
        onClient.set(false);
        assertThrows(IllegalStateException.class, runtime::menuPreviewAppearance);
        onClient.set(true);
        runtime.close();
        assertThrows(IllegalStateException.class, runtime::menuPreviewAppearance);
    }

    private static ClientRuntime menuRuntime(ClientExecutor client,
            Optional<CurrentPlayerAppearanceSource> source,
            Optional<OuterLayerVisibilityController> visibility) {
        return new ClientRuntime(new com.naocraftlab.skins.runtime.DefaultRuntimeServices(), new StubOperations(), client, CANCELLED_PICKER, Runnable::run,
                TEXT, source, Optional.empty(), visibility, Optional.empty(),
                DiagnosticSinks.discarding());
    }

    private static CurrentPlayerAppearanceSource.PlayerAppearance menuPlayerAppearance() {
        return new CurrentPlayerAppearanceSource.PlayerAppearance(
                new TextureRegistry.TextureHandle("nclskins:test", 64, 64), SkinModel.CLASSIC, Optional.empty());
    }

    private static final class TestVisibility implements OuterLayerVisibilityController {
        private OuterLayerVisibility value = OuterLayerVisibility.allVisible();
        private final List<OuterLayerVisibility> applied = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public OuterLayerVisibility current() {
            if (failure != null) throw failure;
            return value;
        }

        @Override
        public void applyDurable(OuterLayerVisibility visibility) {
            value = visibility;
            applied.add(visibility);
        }
    }

    @Test
    void uncheckedSessionWorkerCompletionPublishesWithoutClientTaskFailure() {
        StubOperations operations = new StubOperations();
        operations.session = new SessionValidation(
                com.naocraftlab.skins.core.service.SessionStatus.UNCHECKED,
                operations.session.sessionIdentity(), null, null, "Unchecked");
        operations.providers = operations.providers.disable(AppearanceProviders.Component.SKIN, BuiltinProvider.MINECRAFT)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        QueuedExecutor clientTasks = new QueuedExecutor();
        QueuedExecutor worker = new QueuedExecutor();
        ClientExecutor client = new ClientExecutor() {
            @Override
            public boolean isClientThread() { return false; }
            @Override
            public void execute(Runnable action) { clientTasks.execute(action); }
        };
        var runtime = new ClientRuntime(new DefaultRuntimeServices(), operations, client,
                CANCELLED_PICKER, worker, TEXT, Optional.empty(), Optional.empty(),
                DiagnosticSinks.discarding());
        runtime.initialize();
        clientTasks.runFirst();
        assertEquals(ClientSnapshot.Lifecycle.INITIALIZING, runtime.snapshot().lifecycle());
        worker.runFirst();
        assertEquals(ClientSnapshot.Lifecycle.INITIALIZING, runtime.snapshot().lifecycle());
        clientTasks.runFirst();
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        assertFalse(runtime.snapshot().busy());
        assertEquals(UiMessage.info("nclskins.status.library_loaded"), runtime.snapshot().status());
        runtime.close();
        clientTasks.runFirst();
    }

    @Test
    void uncheckedSessionPublishesReadyProvidersAndKeepsLayersThroughReopen() {
        StubOperations operations = new StubOperations();
        operations.session = new SessionValidation(
                com.naocraftlab.skins.core.service.SessionStatus.UNCHECKED,
                operations.session.sessionIdentity(), null, null, "Unchecked");
        operations.providers = operations.providers.disable(AppearanceProviders.Component.SKIN, BuiltinProvider.MINECRAFT)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE);
        TestVisibility visibility = new TestVisibility();
        var runtime = providerRuntime(operations, visibility);
        runtime.initialize();
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        assertFalse(runtime.snapshot().busy());
        assertEquals(UiMessage.info("nclskins.status.library_loaded"), runtime.snapshot().status());
        assertEquals(ClientSnapshot.GallerySessionPresentation.HIDDEN, runtime.snapshot().gallerySessionPresentation());
        assertTrue(runtime.snapshot().remoteControlsBlocked());
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.outer_layer.head").isEmpty());
        TestFixtures.confirmOrdinaryDisplay(runtime);
        assertTrue(runtime.view(854, 480, 0, 0).widget("providers.outer_layer.head").orElseThrow().enabled());
        runtime.dispatchWidget("providers.outer_layer.head");
        var options = visibility.value;
        assertFalse(options.visible(OuterLayerPart.HEAD));
        runtime.closeScreen();
        runtime.reopen(com.naocraftlab.skins.client.ScreenDestination.PROVIDERS);
        assertEquals(ClientSnapshot.Lifecycle.READY, runtime.snapshot().lifecycle());
        assertFalse(runtime.snapshot().busy());
        assertEquals(UiMessage.info("nclskins.status.library_loaded"), runtime.snapshot().status());
        assertEquals(options, runtime.view(854, 480, 0, 0).previews().get(0).outerLayerVisibility());
        runtime.close();
    }

    @Test
    void reopeningRestrictedProvidersKeepsOptionsWithAnUnchangedActivePresetMask() {
        StubOperations operations = new StubOperations();
        operations.visibilityInResults = true;
        TestVisibility visibility = new TestVisibility();
        var runtime = providerRuntime(operations, visibility);
        runtime.initialize();
        runtime.dispatchWidget("gallery.providers");
        operations.providers = operations.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
        runtime.dispatchWidget("gallery.providers");
        var account = operations.account;
        TestFixtures.confirmOrdinaryDisplay(runtime);
        runtime.dispatchWidget("providers.outer_layer.head");
        var options = visibility.value;
        assertFalse(options.visible(OuterLayerPart.HEAD));
        int applies = visibility.applied.size();
        for (boolean escape : List.of(false, true)) {
            if (escape) runtime.escapePressed(); else runtime.dispatchWidget("providers.back");
            assertEquals(options, runtime.menuPreviewAppearance().orElseThrow().outerLayerVisibility());
            runtime.reopen(com.naocraftlab.skins.client.ScreenDestination.PROVIDERS);
            assertEquals(options, runtime.view(854, 480, 0, 0).previews().get(0).outerLayerVisibility());
            assertEquals(options, visibility.value);
            assertEquals(applies, visibility.applied.size());
            assertEquals(account, operations.account);
        }
        runtime.closeScreen();
        operations.appearanceRevision++;
        runtime.reopen(com.naocraftlab.skins.client.ScreenDestination.PROVIDERS);
        assertEquals(account.presets().get(0).outerLayerVisibility(), visibility.value);
        assertEquals(applies + 1, visibility.applied.size());
        runtime.close();
    }

    @Test
    void providersOptionsSurviveStartupWithoutActivePresetAndYieldToApplyOrActiveStartup() {
        for (boolean active : List.of(false, true)) {
            StubOperations operations = new StubOperations();
            operations.visibilityInResults = active;
            operations.providers = operations.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                    .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT);
            TestVisibility visibility = new TestVisibility();
            var runtime = providerRuntime(operations, visibility);
            runtime.initialize();
            var account = operations.account;
            TestFixtures.confirmOrdinaryDisplay(runtime);
            runtime.dispatchWidget("providers.outer_layer.head");
            assertFalse(visibility.value.visible(OuterLayerPart.HEAD));
            assertEquals(account, operations.account);
            runtime.close();
            var restarted = providerRuntime(operations, visibility);
            restarted.initialize();
            assertEquals(active, visibility.value.visible(OuterLayerPart.HEAD));
            TestFixtures.confirmOrdinaryDisplay(restarted);
            restarted.dispatchWidget("providers.outer_layer.body");
            var options = visibility.value;
            operations.providers = AppearanceProviders.initial();
            restarted.dispatchWidget("gallery.providers");
            restarted.dispatchWidget("providers.back");
            UUID id = operations.account.presets().get(0).id();
            restarted.dispatchWidget("gallery.preset." + id + ".edit");
            TestFixtures.confirmOrdinaryDisplay(restarted);
            restarted.dispatchWidget("editor.outer_layer.head");
            restarted.dispatchWidget("editor.cancel");
            assertEquals(options, visibility.value);
            assertEquals(account, operations.account);
            if (active) {
                restarted.dispatchWidget("gallery.preset." + id + ".apply");
                assertEquals(account.presets().get(0).outerLayerVisibility(), visibility.value);
                restarted.dispatchWidget("gallery.preset." + id + ".edit");
                TestFixtures.confirmOrdinaryDisplay(restarted);
                restarted.dispatchWidget("editor.outer_layer.legs");
                restarted.dispatchWidget("editor.save");
                assertEquals(operations.account.presets().get(0).outerLayerVisibility(), visibility.value);
            }
            restarted.close();
        }
    }

    private static ClientRuntime providerRuntime(StubOperations operations, TestVisibility visibility) {
        return new ClientRuntime(new DefaultRuntimeServices(), operations, CLIENT, CANCELLED_PICKER,
                Runnable::run, TEXT, Optional.of(ClientRuntimeRegressionTest::menuPlayerAppearance),
                Optional.empty(), Optional.of(visibility), Optional.empty(), DiagnosticSinks.discarding());
    }

    @Test
    void editorDraftIsSideEffectFreeAndOnlyActiveSaveAppliesItsMask() {
        StubOperations operations = new StubOperations();
        operations.visibilityInResults = true;
        TestVisibility visibility = new TestVisibility();
        List<OuterLayerVisibility> applied = visibility.applied;
        ClientRuntime runtime = new ClientRuntime(new com.naocraftlab.skins.runtime.DefaultRuntimeServices(),
                operations,
                CLIENT,
                CANCELLED_PICKER,
                Runnable::run,
                TEXT,
                Optional.of(ClientRuntimeRegressionTest::menuPlayerAppearance),
                Optional.empty(),
                Optional.of(visibility),
                Optional.empty(),
                DiagnosticSinks.discarding());

        runtime.initialize();
        assertEquals(List.of(OuterLayerVisibility.allVisible()), applied);
        var initialPreview = runtime.menuPreviewAppearance().orElseThrow();
        UUID active = operations.account.presets().get(0).id();
        UUID inactive = operations.account.presets().get(1).id();

        runtime.dispatchWidget("gallery.preset." + inactive + ".edit");
        TestFixtures.confirmOrdinaryDisplay(runtime);
        runtime.dispatchWidget("editor.outer_layer.body");
        assertEquals(1, applied.size());
        runtime.dispatchWidget("editor.save");
        assertEquals(1, applied.size());

        assertEquals(initialPreview, runtime.menuPreviewAppearance().orElseThrow());
        runtime.dispatchWidget("gallery.preset." + active + ".edit");
        TestFixtures.confirmOrdinaryDisplay(runtime);
        runtime.dispatchWidget("editor.outer_layer.head");
        runtime.dispatchWidget("editor.cancel");
        assertEquals(initialPreview, runtime.menuPreviewAppearance().orElseThrow());
        runtime.dispatchWidget("gallery.preset." + active + ".edit");
        TestFixtures.confirmOrdinaryDisplay(runtime);
        runtime.dispatchWidget("editor.outer_layer.head");
        assertEquals(1, applied.size());
        runtime.dispatchWidget("editor.save");

        assertEquals(2, applied.size());
        assertFalse(applied.get(1).visible(OuterLayerPart.HEAD));
        assertTrue(applied.get(1).visible(OuterLayerPart.BODY));
        assertEquals(applied.get(1), runtime.menuPreviewAppearance().orElseThrow().outerLayerVisibility());
        runtime.dispatchWidget("gallery.preset." + inactive + ".apply");
        assertEquals(3, applied.size());
        assertEquals(applied.get(2), runtime.menuPreviewAppearance().orElseThrow().outerLayerVisibility());
        assertFalse(applied.get(2).visible(OuterLayerPart.BODY));
        runtime.close();
    }

    private static boolean hasSneakyCard(ViewSpec view) {
        return view.widgets().stream().anyMatch(widget -> widget.id().startsWith("editor.cape_item.SNEAKY.bundle."));
    }

    private static ClientRuntime runtime(StubOperations operations) {
        return runtime(operations, Runnable::run);
    }

    private static ClientRuntime runtime(StubOperations operations, Executor worker) {
        return new ClientRuntime(new com.naocraftlab.skins.runtime.DefaultRuntimeServices(),
                operations,
                CLIENT,
                CANCELLED_PICKER,
                worker,
                worker,
                Runnable::run,
                TEXT,
                Optional.empty(),
                Optional.empty(),
                DiagnosticSinks.discarding());
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

    private static final class StubOperations implements TestCapeOperations {
        @Override
        public com.naocraftlab.skins.core.png.NormalizedSkin loadLocalSkin(java.nio.file.Path path)
                throws java.io.IOException, com.naocraftlab.skins.core.png.PngValidationException {
            return new com.naocraftlab.skins.core.png.PngFileReader(
                    new com.naocraftlab.skins.core.png.PngValidator()).projectStandardImport(path);
        }

        private SessionValidation session = TestFixtures.validSession();
        private AccountState account = TestFixtures.account(2);
        private AppearanceProviders providers = AppearanceProviders.initial();
        private boolean failEditorSave;
        private EditorSaveRequest lastEditorRequest;
        private MutationResult mutationResult = MutationResult.APPLIED;
        private Set<RecoveryAction> recoveryActions = Set.of();
        private String lastCapeId;
        private Optional<UUID> rememberedActivePreset = Optional.empty();
        private int restoreCalls;
        private int retryCapeCalls;
        private final AtomicInteger warmSessionCalls = new AtomicInteger();
        private final AtomicInteger initializeCalls = new AtomicInteger();
        private final AtomicInteger skinPreviewCalls = new AtomicInteger();
        private final AtomicInteger capePreviewCalls = new AtomicInteger();
        private final AtomicInteger preparedCapeReads = new AtomicInteger();
        private final AtomicInteger editorCatalogReads = new AtomicInteger();
        private final AtomicInteger capeRenames = new AtomicInteger();
        private final AtomicInteger capeDeletes = new AtomicInteger();
        private final AtomicInteger providerHttpCalls = new AtomicInteger();
        private final AtomicInteger personalMaterializations = new AtomicInteger();
        private final AtomicInteger observationAdoptions = new AtomicInteger();

        @Override
        public ProviderRefreshResult refreshProviderSources(ProviderRefreshUseCases.Scope scope) {
            providerHttpCalls.incrementAndGet();
            return new ProviderRefreshResult(scope, Optional.of(reloadProviders()), Map.of(), false);
        }

        @Override
        public void refreshCapeProvider(ProviderRefreshUseCases.Scope scope, BuiltinProvider provider,
                java.util.function.Consumer<com.naocraftlab.skins.core.provider.ProviderObservation<com.naocraftlab.skins.core.provider.ProviderCape>> completion) {
            providerHttpCalls.incrementAndGet();
            completion.accept(null);
        }

        @Override
        public void adoptSharedCapeObservation(UUID accountId, String canonicalName, AppearanceProviders providers) {
            observationAdoptions.incrementAndGet();
        }

        @Override
        public com.naocraftlab.skins.core.model.PersonalCapeEntry materializeResourceCape(UUID accountId, ResourceCapeSelection selection) {
            personalMaterializations.incrementAndGet();
            throw new UnsupportedOperationException();
        }

        @Override
        public com.naocraftlab.skins.core.model.PersonalCapeEntry importCape(UUID accountId, java.nio.file.Path path, String fallbackName) {
            personalMaterializations.incrementAndGet();
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountState renameCape(UUID accountId, UUID entryId, String name) {
            capeRenames.incrementAndGet();
            account = account.withPersonalCapes(account.personalCapes().stream()
                    .map(entry -> entryId.equals(entry.texture().entryId()) ? entry.renamed(name) : entry).toList());
            return account;
        }

        @Override
        public CapeDeletion deleteCape(UUID accountId, UUID entryId) {
            capeDeletes.incrementAndGet();
            account = account.withPersonalCapes(account.personalCapes().stream()
                    .filter(entry -> !entryId.equals(entry.texture().entryId())).toList());
            return new CapeDeletion(account, reloadProviders());
        }

        @Override
        public CatalogRead.CapeEditorData loadCapeEditorData(UUID accountId) {
            editorCatalogReads.incrementAndGet();
            return new CatalogRead.CapeEditorData(account, List.of(), Map.of(), 1);
        }

        @Override
        public Optional<CapePreviewSource> openPreparedCapePreview(UUID account,
                Optional<CatalogRead.ResourceCapeSelection> resource, String capeId,
                Executor worker, ClientExecutor clientExecutor, boolean thumbnail) {
            preparedCapeReads.incrementAndGet();
            return Optional.empty();
        }
        private final AtomicInteger reconciliationCalls = new AtomicInteger();
        private final List<Trigger> reconciliationTriggers = new ArrayList<>();
        private boolean failNextSkinPreview;
        private boolean failNextCapePreview;
        private boolean visibilityInResults;
        private byte[] skinPreviewBytes = {1, 2, 3};
        private Map<UUID, byte[]> skinPreviewByAsset = Map.of();
        private boolean warmCheckpoint;
        private long appearanceRevision;

        @Override
        public DurableAppearance reloadProviders() {
            return new DurableAppearance(account.accountId(), appearanceRevision, AppearanceSyncStatus.LOCAL_ONLY,
                    visibilityInResults ? Optional.of(account.presets().get(0).id()) : Optional.empty(),
                    Optional.empty(), Optional.empty(), providers);
        }

        @Override
        public void warmSession() {
            warmSessionCalls.incrementAndGet();
        }

        @Override
        public boolean warmedReconciliationRecommended() {
            return warmCheckpoint;
        }

        @Override
        public Optional<DurableAppearance> warmedDurableAppearance() {
            return warmCheckpoint
                    ? Optional.of(new DurableAppearance(
                            account.accountId(),
                            0,
                            AppearanceSyncStatus.LOCAL_ONLY,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()))
                    : Optional.empty();
        }

        @Override
        public Optional<ReconciliationResult> reconcileAppearance(
                Trigger trigger) {
            reconciliationCalls.incrementAndGet();
            reconciliationTriggers.add(trigger);
            return Optional.empty();
        }

        @Override
        public InitialData initialize() {
            initializeCalls.incrementAndGet();
            return new InitialData(
                    account,
                    session,
                    Optional.empty(),
                    visibilityInResults ? Optional.of(account.presets().get(0).id()) : Optional.empty(),
                    Optional.empty(),
                    false,
                    List.of(),
                    com.naocraftlab.skins.core.model.AccountUiPreferences.defaults(account.accountId()),
                    visibilityInResults
                            ? Optional.of(account.presets().get(0).outerLayerVisibility())
                            : Optional.empty(),
                    com.naocraftlab.skins.core.model.OwnedCapeInventory.empty(account.accountId(),
                            java.time.Instant.EPOCH),
                    appearanceRevision, AppearanceSyncStatus.LOCAL_ONLY, providers);
        }

        @Override
        public EditorSave saveEditor(EditorSaveRequest request) throws IOException {
            lastEditorRequest = request;
            if (failEditorSave) {
                throw new IOException("characterized save failure");
            }
            UUID presetId = request.originalPresetId().orElseThrow();
            List<AppearancePreset> presets = new ArrayList<>(account.presets());
            for (int index = 0; index < presets.size(); index++) {
                AppearancePreset existing = presets.get(index);
                if (existing.id().equals(presetId)) {
                    presets.set(index, new AppearancePreset(
                            existing.id(),
                            request.name(),
                            request.skin(),
                            request.capeId().orElse(null),
                            request.outerLayerVisibility(),
                            existing.createdAt(),
                            existing.updatedAt().plusNanos(1)));
                    break;
                }
            }
            account = new AccountState(
                    AccountState.CURRENT_SCHEMA_VERSION,
                    account.accountId(),
                    account.skinAssets(),
                    account.personalSkins(),
                    presets,
                    account.updatedAt().plusNanos(1));
            if (!visibilityInResults || !account.presets().get(0).id().equals(presetId)) {
                return new EditorSave(account, presetId);
            }
            appearanceRevision++;
            AppearancePreset saved = account.presets().stream()
                    .filter(preset -> preset.id().equals(presetId))
                    .findFirst()
                    .orElseThrow();
            DurableAppearance appearance = new DurableAppearance(
                    account.accountId(),
                    appearanceRevision,
                    AppearanceSyncStatus.PENDING,
                    Optional.of(presetId),
                    Optional.of(AppliedAppearance.accountDefault(
                            account.accountId(), Optional.empty())),
                    Optional.of(saved.outerLayerVisibility()));
            return new EditorSave(account, presetId, Optional.of(appearance));
        }

        @Override
        public RemoteResult applyPreset(UUID presetId) {
            return remoteResult();
        }

        @Override
        public PresetUse usePreset(UUID presetId) {
            RemoteResult remote = remoteResult();
            AppearancePreset preset = account.presets().stream()
                    .filter(candidate -> candidate.id().equals(presetId))
                    .findFirst()
                    .orElseThrow();
            return new PresetUse(
                    account,
                    session,
                    presetId,
                    remote.outcome().optionalAppliedAppearance(),
                    Optional.of(remote),
                    false,
                    false,
                    visibilityInResults ? Optional.of(preset.outerLayerVisibility()) : Optional.empty());
        }

        @Override
        public RemoteResult retryCape(String capeId) {
            retryCapeCalls++;
            lastCapeId = capeId;
            return remoteResult();
        }

        @Override
        public void rememberActivePreset(UUID accountId, Optional<UUID> presetId) {
            rememberedActivePreset = presetId;
        }

        private RemoteResult remoteResult() {
            AppliedAppearance appearance = mutationResult == MutationResult.APPLIED
                            || mutationResult == MutationResult.PARTIAL
                    ? AppliedAppearance.accountDefault(TestFixtures.ACCOUNT_ID, Optional.empty())
                    : null;
            PresetApplicationOutcome outcome = new PresetApplicationOutcome(
                    mutationResult,
                    ApplicationPhase.COMPLETE,
                    session.profile(),
                    session.profile(),
                    appearance,
                    null,
                    recoveryActions,
                    mutationResult.name());
            return new RemoteResult(outcome, account, session, Optional.empty());
        }

        @Override
        public AccountState importSkin(String name, SkinVariant variant, byte[] normalizedPng) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountState renameSkin(UUID skinId, String newName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountState changeSkinVariant(UUID skinId, SkinVariant variant) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountState duplicateSkin(UUID skinId, String newName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountState deleteSkin(UUID skinId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InitialData resetLibrary() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PresetDelete deletePreset(UUID presetId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RemoteResult restorePreviousAppearance(PresetApplicationOutcome outcome) {
            restoreCalls++;
            return remoteResult();
        }

        @Override
        public byte[] loadSkinPreview(UUID skinId) {
            skinPreviewCalls.incrementAndGet();
            if (failNextSkinPreview) {
                failNextSkinPreview = false;
                throw new IllegalStateException("characterized preview failure");
            }
            return skinPreviewByAsset.getOrDefault(skinId, skinPreviewBytes).clone();
        }

        @Override
        public Optional<byte[]> loadCapePreview(String capeId) {
            capePreviewCalls.incrementAndGet();
            if (failNextCapePreview) {
                failNextCapePreview = false;
                throw new IllegalStateException("characterized cape preview failure");
            }
            return Optional.of(new byte[] {4, 5, 6});
        }

        @Override
        public InitialData retrySession() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean rateLimited() {
            return false;
        }

        @Override
        public GameSessionTokenSource.SessionIdentity sessionIdentity() {
            return session.sessionIdentity();
        }
    }
}
