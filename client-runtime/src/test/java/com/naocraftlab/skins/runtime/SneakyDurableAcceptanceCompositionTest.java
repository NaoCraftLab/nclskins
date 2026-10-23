package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.api.ProfileApi;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.png.*;
import com.naocraftlab.skins.core.provider.*;
import com.naocraftlab.skins.core.service.AssetStorePort;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import com.naocraftlab.skins.runtime.composition.ClientCompositionRoot;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

final class SneakyDurableAcceptanceCompositionTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"reconnect,false", "reconnect,true", "warm,false", "warm,true", "reload,false", "reload,true", "reconcile,false", "reconcile,true"})
    void acceptedDurableCheckpointReplaysCurrentOwnerWithoutPreparingAgain(String checkpoint, boolean before) throws Exception {
        try (Graph graph = new Graph(directory)) {
            graph.openPending();
            assertFalse(graph.material());
            if (!before) {
                graph.settle();
                graph.assertExactMaterial();
            }
            var previous = graph.runtime.snapshot().providers();
            int reads = graph.assetReads.get(), jobs = graph.capes.submitted, registrations = graph.registrations.get();
            graph.client.call(() -> {
                switch (checkpoint) {
                    case "reconnect" -> graph.runtime.afterReconnect();
                    case "warm" -> graph.runtime.warmSession();
                    case "reload" -> graph.runtime.dispatchWidget("gallery.providers");
                    case "reconcile" -> {
                        try {
                            var request = ClientRuntime.class.getDeclaredMethod("requestAppearanceReconciliation",
                                    com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger.class);
                            request.setAccessible(true);
                            request.invoke(graph.runtime, com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger.LOCAL_INTENT);
                            assertTrue(graph.runtime.snapshot().syncInProgress());
                        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                    }
                    default -> fail(checkpoint);
                }
            });
            graph.worker.drain();
            if (before) graph.client.drain();
            graph.capes.drain();
            graph.client.drain();
            graph.settle();
            assertEquals(previous.skin().resolve(), graph.runtime.snapshot().providers().skin().resolve());
            assertEquals(previous.cape().configurationRevision(), graph.runtime.snapshot().providers().cape().configurationRevision());
            if (!before) assertEquals(jobs, graph.capes.submitted, "prepared checkpoint cannot submit another decode");
            assertEquals(before ? reads + 1 : reads, graph.assetReads.get());
            assertEquals(before ? registrations + 1 : registrations, graph.registrations.get());
            graph.assertExactMaterial();
            reads = graph.assetReads.get();
            jobs = graph.capes.submitted;
            registrations = graph.registrations.get();
            graph.client.call(graph.runtime::afterReconnect);
            graph.settle();
            graph.assertExactMaterial();
            assertEquals(reads, graph.assetReads.get(), "owner replay cannot read the skin");
            assertEquals(jobs, graph.capes.submitted, "owner replay cannot decode");
            assertEquals(registrations, graph.registrations.get(), "owner replay cannot register");
            assertFalse(graph.storage.loadAppearance(graph.account).providers().cape().sneaky().known(), "transient observation is not persisted");
        }
    }

    @ParameterizedTest
    @CsvSource({"account", "name", "disable", "remove", "skin", "close", "generation"})
    void pendingPreparationCannotPublishOutsideItsCurrentScope(String change) throws Exception {
        try (Graph graph = new Graph(directory)) {
            graph.openPending();
            graph.capes.drain();
            assertFalse(graph.material(), "native completion remains queued");
            graph.client.call(() -> {
                switch (change) {
                    case "account" -> graph.identity.set(new GameSessionTokenSource.SessionIdentity(UUID.fromString("00000000-0000-0000-0000-000000000012"), "SyntheticOther"));
                    case "name" -> graph.identity.set(new GameSessionTokenSource.SessionIdentity(graph.account, "SyntheticOther"));
                    case "disable", "remove" -> {
                        graph.runtime.dispatchWidget("providers.remove.SNEAKY");
                        graph.worker.drain();
                        graph.client.call(graph.client.queue.removeLast());
                        graph.operations.optiFineConfigurationChanged();
                    }
                    case "skin" -> {
                        try {
                            String sha = graph.storage.storeAsset(SneakyPreviewPreparationTest.skin(0xff654321)).sha256();
                            graph.storage.updateAppearance(graph.account, state -> state.withProviders(new AppearanceProviders(
                                    state.providers().skin().observeMinecraft(new ProviderSkin(sha, SkinVariant.CLASSIC)), state.providers().cape())));
                            graph.operations.optiFineConfigurationChanged();
                        } catch (Exception failure) { throw new AssertionError(failure); }
                    }
                    case "close" -> graph.runtime.close();
                    case "generation" -> graph.runtime.capeWorldChanged();
                    default -> fail(change);
                }
            });
            graph.client.drain();
            assertEquals(0, graph.registrations.get(), "stale native callback cannot register");
            if (change.equals("close")) assertTrue(graph.runtime.closed());
            else assertFalse(graph.material(), "stale callback cannot publish old atlas");
            graph.settle();
            if (change.equals("close")) assertTrue(graph.runtime.closed());
        }
    }

    @ParameterizedTest
    @CsvSource({"reconnect,account", "reconnect,configuration", "reconnect,intent", "reconnect,close",
            "reconcile,account", "reconcile,configuration", "reconcile,intent", "reconcile,close"})
    void heldActualDurableCompletionCannotReplaceNewerScope(String checkpoint, String change) throws Exception {
        try (Graph graph = new Graph(directory)) {
            graph.openPending();
            graph.settle();
            graph.assertExactMaterial();
            AtomicReference<CompletableFuture<AppearanceRefresh.Result>> result = new AtomicReference<>();
            if (checkpoint.equals("reconnect")) graph.client.call(() -> result.set(graph.runtime.afterReconnect()));
            else {
                var request = ClientRuntime.class.getDeclaredMethod("requestAppearanceReconciliation",
                        com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger.class);
                request.setAccessible(true);
                graph.client.call(() -> {
                    try { request.invoke(graph.runtime, com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger.LOCAL_INTENT); }
                    catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                });
            }
            graph.worker.drain();
            assertFalse(graph.client.queue.isEmpty());
            switch (change) {
                case "account" -> graph.identity.set(new GameSessionTokenSource.SessionIdentity(UUID.fromString("00000000-0000-0000-0000-000000000012"), "SyntheticOther"));
                case "configuration" -> {
                    graph.client.call(() -> graph.runtime.dispatchWidget("providers.remove.SNEAKY"));
                    graph.worker.drain();
                    graph.client.call(graph.client.queue.removeLast());
                    assertFalse(graph.runtime.snapshot().providers().cape().enabled(BuiltinProvider.SNEAKY));
                }
                case "intent" -> {
                    graph.storage.updateAppearance(graph.account, current -> new AccountAppearanceState(current.schemaVersion(),
                            current.accountId(), current.intentRevision() + 1, current.activePresetId(), current.skinSha256(),
                            current.skinVariant(), current.capeId(), current.outerLayerVisibility(), current.syncStatus(),
                            current.settledRevision(), current.updatedAt(), current.providers().disable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)));
                    var newer = graph.operations.durableAppearance().orElseThrow();
                    var accept = ClientRuntime.class.getDeclaredMethod("acceptProviderChange", ClientOperations.DurableAppearance.class);
                    accept.setAccessible(true);
                    graph.client.call(() -> {
                        try { accept.invoke(graph.runtime, newer); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                    });
                }
                case "close" -> graph.client.call(graph.runtime::close);
                default -> fail(change);
            }
            var before = graph.runtime.snapshot().providers();
            graph.client.drain();
            graph.settle();
            assertEquals(before, graph.runtime.snapshot().providers());
            if (checkpoint.equals("reconnect") && change.equals("account")) assertEquals(AppearanceRefresh.Result.DEFERRED, result.get().join());
            if (change.equals("configuration") || change.equals("intent")) assertFalse(graph.material());
        }
    }

    @org.junit.jupiter.api.Test
    void actualSettlementFailureReplaysPreparedOwnerBeforeLocalRefresh() throws Exception {
        try (Graph graph = new Graph(directory, true)) {
            graph.prepareSettlementFailure();
            long refreshGeneration = graph.refresh.generation();
            graph.queueSettlementFailure();
            int reads = graph.assetReads.get(), jobs = graph.capes.submitted, registrations = graph.registrations.get();
            graph.client.drain();
            graph.settle();
            graph.assertExactMaterial();
            assertTrue(graph.refresh.generation() > refreshGeneration, "actual local refresh was requested");
            assertTrue(graph.refreshSawSneaky.get(), "actual refresh sees owner observation after durable acceptance");
            assertEquals(reads, graph.assetReads.get());
            assertEquals(jobs, graph.capes.submitted);
            assertEquals(registrations, graph.registrations.get());
            assertFalse(graph.storage.loadAppearance(graph.account).providers().cape().sneaky().known());
        }
    }

    @ParameterizedTest
    @CsvSource({"account", "name", "skinConfiguration", "capeConfiguration", "intent", "close"})
    void heldActualSettlementFailureCannotRestoreRetiredScope(String change) throws Exception {
        try (Graph graph = new Graph(directory, true)) {
            graph.prepareSettlementFailure();
            graph.queueSettlementFailure();
            long intent = graph.runtime.snapshot().intentRevision();
            switch (change) {
                case "account" -> graph.identity.set(new GameSessionTokenSource.SessionIdentity(
                        UUID.fromString("00000000-0000-0000-0000-000000000012"), "SyntheticOther"));
                case "name" -> graph.identity.set(new GameSessionTokenSource.SessionIdentity(graph.account, "SyntheticOther"));
                case "skinConfiguration", "capeConfiguration" -> {
                    boolean skin = change.equals("skinConfiguration");
                    graph.operations.disableProvider(skin ? AppearanceProviders.Component.SKIN : AppearanceProviders.Component.CAPE,
                            skin ? BuiltinProvider.OFFLINE : BuiltinProvider.SNEAKY);
                    graph.acceptActualProviderChange();
                    assertEquals(intent, graph.runtime.snapshot().intentRevision(), "configuration change does not advance intent");
                }
                case "intent" -> {
                    graph.storage.updateAppearance(graph.account, current -> new AccountAppearanceState(current.schemaVersion(),
                            current.accountId(), current.intentRevision() + 1, current.activePresetId(), current.skinSha256(),
                            current.skinVariant(), current.capeId(), current.outerLayerVisibility(), current.syncStatus(),
                            current.settledRevision(), current.updatedAt(), current.providers().disable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)));
                    graph.acceptActualProviderChange();
                }
                case "close" -> graph.client.call(graph.runtime::close);
                default -> fail(change);
            }
            var before = graph.runtime.snapshot().providers();
            int reads = graph.assetReads.get(), jobs = graph.capes.submitted, registrations = graph.registrations.get();
            graph.client.drain();
            graph.settle();
            if (change.equals("name")) assertFalse(graph.material(), "renamed identity cannot replay old owner atlas");
            else assertEquals(before, graph.runtime.snapshot().providers(), "held failure cannot replace newer scope");
            assertEquals(reads, graph.assetReads.get());
            assertEquals(jobs, graph.capes.submitted);
            assertEquals(registrations, graph.registrations.get());
            assertFalse(graph.storage.loadAppearance(graph.account).providers().cape().sneaky().known());
        }
    }

    private static final class Graph implements AutoCloseable {
        final UUID account = UUID.fromString("00000000-0000-0000-0000-000000000011");
        final AtomicReference<GameSessionTokenSource.SessionIdentity> identity = new AtomicReference<>(new GameSessionTokenSource.SessionIdentity(account, "SyntheticPlayer"));
        final ClientQueue client = new ClientQueue();
        final WorkerQueue worker = new WorkerQueue(), capes = new WorkerQueue();
        final AtomicInteger http = new AtomicInteger(), assetReads = new AtomicInteger(), registrations = new AtomicInteger();
        final AtomicReference<byte[]> nativeAtlas = new AtomicReference<>();
        final byte[] skin = SneakyPreviewPreparationTest.skin(0xff123456);
        final byte[] atlas = new SneakyCapeDecoder().decode(skin).orElseThrow().bytes();
        final NclSkinsStorage storage;
        final DefaultClientOperations operations;
        final CapeProviderCoordinator owner;
        final ClientRuntime runtime;

        final AtomicBoolean settlementArmed = new AtomicBoolean(), refreshSawSneaky = new AtomicBoolean();
        final AtomicInteger settlementFaults = new AtomicInteger(), stubUploads = new AtomicInteger();
        final AppearanceRefreshCoordinator<Object> refresh;

        Graph(Path root) throws Exception { this(root, false); }

        Graph(Path root, boolean settlementFixture) throws Exception {
            Clock clock = Clock.systemUTC();
            storage = new NclSkinsStorage(root, new PngValidator(), clock);
            String sha = storage.storeAsset(skin).sha256();
            storage.updateAppearance(account, state -> {
                var intent = new AccountAppearanceState(AccountAppearanceState.CURRENT_SCHEMA_VERSION, account, 1, null,
                        sha, SkinVariant.CLASSIC, null, OuterLayerVisibility.allVisible(), AppearanceSyncStatus.LOCAL_ONLY, 0, Instant.now());
                return intent.withProviders(new AppearanceProviders(intent.providers().skin().disable(BuiltinProvider.OFFLINE)
                        .observeMinecraft(new ProviderSkin(sha, SkinVariant.CLASSIC)), intent.providers().cape().disable(BuiltinProvider.OPTIFINE)
                        .enable(BuiltinProvider.SNEAKY).move(BuiltinProvider.SNEAKY, -1)));
            });
            GameSessionTokenSource tokens = new GameSessionTokenSource() {
                public SessionIdentity currentSession() { return identity.get(); }
                public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
                    if (settlementFixture) return request.execute("synthetic-fixture-token");
                    throw new GameSessionTokenUnavailableException();
                }
            };
            ProfileApi api = (ProfileApi) java.lang.reflect.Proxy.newProxyInstance(ProfileApi.class.getClassLoader(), new Class<?>[]{ProfileApi.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("rateLimitRemaining")) return Optional.empty();
                        if (settlementFixture) {
                            switch (method.getName()) {
                                case "getProfile": return new RemoteProfile(account, "SyntheticPlayer", List.of(), List.of(), Set.of());
                                case "uploadSkin":
                                    assertArrayEquals(skin, (byte[]) arguments[2]);
                                    stubUploads.incrementAndGet();
                                    settlementArmed.set(true);
                                    return null;
                                case "deactivateCape": return null;
                                default: throw new AssertionError("unexpected API stub call: " + method.getName());
                            }
                        }
                        http.incrementAndGet();
                        throw new AssertionError("profile HTTP forbidden");
                    });
            var graph = ClientCompositionRoot.operations(tokens, api, storage, (collection, item, model) -> skin.clone(), clock,
                    remote -> { http.incrementAndGet(); throw new AssertionError("official HTTP forbidden"); });
            operations = graph.operations();
            if (settlementFixture) {
                var deliveryField = DefaultClientOperations.class.getDeclaredField("delivery");
                deliveryField.setAccessible(true);
                var delivery = (AccountDeliveryService) deliveryField.get(operations);
                var storeField = AccountDeliveryService.class.getDeclaredField("storage");
                storeField.setAccessible(true);
                var actualStore = (com.naocraftlab.skins.core.service.AccountAppearanceStore) storeField.get(delivery);
                var faultStore = java.lang.reflect.Proxy.newProxyInstance(ProfileApi.class.getClassLoader(),
                        new Class<?>[]{com.naocraftlab.skins.core.service.AccountAppearanceStore.class}, (proxy, method, arguments) -> {
                            if (method.getName().equals("updateAppearance") && settlementArmed.compareAndSet(true, false)) {
                                boolean actualSettlement = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                                        frame.getClassName().equals(AccountDeliveryService.class.getName())
                                                && frame.getMethodName().equals("settleAfterMutation")));
                                assertTrue(actualSettlement, "fault reaches actual settlement producer");
                                settlementFaults.incrementAndGet();
                                throw new java.io.IOException("synthetic settlement write failure");
                            }
                            try { return method.invoke(actualStore, arguments); }
                            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                        });
                storeField.set(delivery, faultStore);
            }
            AssetStorePort countedAssets = new AssetStorePort() {
                public Asset storeAsset(byte[] bytes) throws java.io.IOException, PngValidationException { return graph.assets().storeAsset(bytes); }
                public byte[] readAsset(String hash) throws java.io.IOException, PngValidationException {
                    assetReads.incrementAndGet();
                    return graph.assets().readAsset(hash);
                }
            };
            PlayerAppearanceSink<Object> sink = new PlayerAppearanceSink<>() {
                public ApplyResult apply(SignedProfileResolver.ResolvedProfile<Object> profile) { return ApplyResult.UPDATED; }
                public ApplyResult reattach(ExpectedAppearance expected) {
                    assertTrue(client.isClientThread());
                    if (settlementFaults.get() > 0) refreshSawSneaky.set(runtime.snapshot().providers().cape().sneaky().known());
                    return ApplyResult.UPDATED;
                }
                public Optional<String> registerCapeTexture(UUID id, CapeSource source, String hash, byte[] bytes) {
                    assertTrue(client.isClientThread());
                    assertEquals(CapeSource.SNEAKY, source);
                    registrations.incrementAndGet();
                    nativeAtlas.set(bytes.clone());
                    return Optional.of("fixture:atlas");
                }
            };
            CapeTestComposition.ResponseFixture forbidden = (uri, timeout, maximum) -> { http.incrementAndGet(); throw new AssertionError("cape HTTP forbidden"); };
            owner = new CapeProviderCoordinator(tokens, graph.appearances(), countedAssets, graph.textures(), sink, client, capes,
                    CapeTestComposition.optifine(storage, forbidden, clock), CapeTestComposition.skinMc(storage, forbidden, clock), operations::verifiedOfficialCapeUri);
            operations.attachCapeProviders(owner, null);
            refresh = new AppearanceRefreshCoordinator<>(client,
                    expected -> { throw new AssertionError("reattach must avoid resolver I/O"); }, sink, DiagnosticSinks.discarding());
            runtime = new ClientRuntime(new DefaultRuntimeServices(), operations, client,
                    () -> CompletableFuture.completedFuture(Optional.empty()), worker, UiMessage::key,
                    settlementFixture ? Optional.of(refresh) : Optional.empty(), DiagnosticSinks.discarding());
        }

        void prepareSettlementFailure() throws Exception {
            openPending();
            settle();
            var accountState = runtime.snapshot().account().orElseThrow();
            var saved = operations.saveEditor(new LibraryEditorPort.EditorSaveRequest(Optional.empty(), "Synthetic settlement",
                    SkinReference.asset(accountState.skinAssets().get(0).id()), SkinVariant.CLASSIC, SkinVariant.CLASSIC,
                    Optional.empty(), Optional.empty()));
            operations.usePreset(saved.presetId());
            storage.updateAppearance(account, current -> current.withProviders(new AppearanceProviders(
                    current.providers().skin().enable(BuiltinProvider.OFFLINE).move(BuiltinProvider.OFFLINE, -1),
                    current.providers().cape().observeMinecraft(null))));
            acceptActualProviderChange();
            operations.optiFineConfigurationChanged();
            settle();
            assertExactMaterial();
            assertEquals(0, stubUploads.get());
        }

        void acceptActualProviderChange() throws Exception {
            var durable = operations.durableAppearance().orElseThrow();
            var accept = ClientRuntime.class.getDeclaredMethod("acceptProviderChange", ClientOperations.DurableAppearance.class);
            accept.setAccessible(true);
            client.call(() -> {
                try { accept.invoke(runtime, durable); }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
        }

        void queueSettlementFailure() throws Exception {
            var request = ClientRuntime.class.getDeclaredMethod("requestAppearanceReconciliation",
                    com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger.class);
            request.setAccessible(true);
            client.call(() -> {
                try { request.invoke(runtime, com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger.LOCAL_INTENT); }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            worker.drain();
            assertEquals(1, stubUploads.get(), "real mutation reached synthetic API upload");
            assertEquals(1, settlementFaults.get(), "actual delivery settlement write failed once");
            assertEquals(runtime.snapshot().providers().skin().resolve(), storage.loadAppearance(account).providers().skin().resolve(),
                    "settlement recovery retains the same selected skin");
            assertFalse(storage.loadAppearance(account).providers().cape().sneaky().known());
            assertTrue(runtime.snapshot().syncInProgress(), "real coordinator completion remains held");
            assertFalse(client.queue.isEmpty());
        }

        void openPending() {
            client.call(runtime::initialize);
            client.drain();
            worker.drain();
            client.drain();
            client.call(() -> runtime.reopen(ScreenDestination.PROVIDERS));
            client.call(() -> runtime.dispatchWidget("providers.tab.CAPE"));
            worker.drain();
            client.drain();
            assertFalse(capes.queue.isEmpty(), "real owner decode is pending");
        }

        void settle() {
            for (int budget = 0; budget < 20 && (!worker.queue.isEmpty() || !capes.queue.isEmpty() || !client.queue.isEmpty()); budget++) {
                worker.drain(); capes.drain(); client.drain();
            }
            assertTrue(worker.queue.isEmpty() && capes.queue.isEmpty() && client.queue.isEmpty(), "queues quiesce");
        }

        ViewSpec view() {
            AtomicReference<ViewSpec> result = new AtomicReference<>();
            client.call(() -> result.set(runtime.view(854, 480, 0, 0)));
            return result.get();
        }

        boolean material() {
            return view().iconDecorations().stream().filter(icon -> icon.ownerWidgetId().equals("providers.row.SNEAKY"))
                    .anyMatch(icon -> icon.providerTexture().isPresent());
        }

        void assertExactMaterial() {
            assertArrayEquals(atlas, nativeAtlas.get(), "native sink receives decoded atlas");
            ViewSpec view = view();
            var row = view.iconDecorations().stream().filter(icon -> icon.ownerWidgetId().equals("providers.row.SNEAKY")).findFirst().orElseThrow();
            assertTrue(row.providerTexture().isPresent(), "accepted durable checkpoint must retain current Sneaky row material");
            var image = runtime.loadProviderTexture(row.providerTexture().orElseThrow());
            settle();
            assertArrayEquals(atlas, image.join().orElseThrow());
            var preview = view.previews().stream().filter(value -> value.capeId().isPresent()).findFirst().orElseThrow();
            var actual = runtime.loadCapePreview(preview.capeId().orElseThrow());
            settle();
            assertArrayEquals(atlas, actual.join().orElseThrow(), "default preview uses actual decoded atlas");
        }

        public void close() {
            client.call(runtime::close);
            settle();
            assertTrue(runtime.closed());
            assertEquals(0, http.get());
        }
    }

    private static final class ClientQueue implements ClientExecutor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        boolean running;
        public boolean isClientThread() { return running; }
        public void execute(Runnable action) { queue.add(action); }
        void call(Runnable action) {
            boolean previous = running;
            running = true;
            try { action.run(); } finally { running = previous; }
        }
        void drain() {
            for (int budget = 0; !queue.isEmpty(); budget++) {
                assertTrue(budget < 1000);
                call(queue.remove());
            }
        }
    }

    private static final class WorkerQueue implements Executor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        int submitted;
        public void execute(Runnable action) { submitted++; queue.add(action); }
        void drain() {
            for (int budget = 0; !queue.isEmpty(); budget++) {
                assertTrue(budget < 1000);
                queue.remove().run();
            }
        }
    }
}
