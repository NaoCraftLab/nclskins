package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.ServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.SignedProfileResolver;
import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.MutationResult;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.service.ApplicationPhase;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.RecoveryAction;
import com.naocraftlab.skins.core.service.RemoteAppearanceImpact;
import com.naocraftlab.skins.core.service.SessionValidation;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ClientRuntimeServerSignalBoundaryTest {
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
    private static final FilePicker CANCELLED_PICKER = () ->
            CompletableFuture.completedFuture(Optional.<Path>empty());
    private static final TextResolver TEXT = message -> message.key();

    @Test
    void offlineOnlyObserverDoesNotSendEvenALateConfirmedMinecraftOutcome() {
        SignalScenario scenario = SignalScenario.confirmedPartial();
        scenario.providers = scenario.providers
                .disable(com.naocraftlab.skins.core.provider.AppearanceProviders.Component.SKIN,
                        com.naocraftlab.skins.core.provider.BuiltinProvider.MINECRAFT)
                .disable(com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE,
                        com.naocraftlab.skins.core.provider.BuiltinProvider.MINECRAFT);
        TestNotifier notifier = new TestNotifier(OptionalLong.of(1L));
        ClientRuntime runtime = runtime(scenario, notifier);
        runtime.initialize();
        runtime.dispatchWidget(applyWidget(scenario.presetId));
        assertEquals(1, scenario.reconciliationCalls);
        assertEquals(0, notifier.notifications);
    }

    @Test
    void confirmedPartialReconciliationSignalsExactlyOnce() {
        SignalScenario scenario = SignalScenario.confirmedPartial();
        TestNotifier notifier = new TestNotifier(OptionalLong.of(1L));
        ClientRuntime runtime = runtime(scenario, notifier);

        runtime.initialize();
        runtime.dispatchWidget(applyWidget(scenario.presetId));

        assertEquals(AppearanceSyncStatus.PARTIAL, runtime.snapshot().syncStatus());
        assertEquals(1, scenario.reconciliationCalls);
        assertEquals(1, notifier.notifications);
    }

    @Test
    void disconnectedConfirmedSignalIsDroppedAndNeverReplayedAfterReconnect() {
        SignalScenario scenario = SignalScenario.confirmedPartial();
        TestNotifier notifier = new TestNotifier(OptionalLong.empty());
        ClientRuntime runtime = runtime(scenario, notifier);

        runtime.initialize();
        runtime.dispatchWidget(applyWidget(scenario.presetId));

        assertEquals(1, scenario.reconciliationCalls);
        assertEquals(0, notifier.notifications);

        notifier.connection = OptionalLong.of(2L);
        runtime.afterReconnect().join();

        assertEquals(1, scenario.reconciliationCalls);
        assertEquals(0, notifier.notifications);
    }

    @Test
    void readerOrConcurrentLoserWithoutOwnedOutcomeNeverSignals() {
        SignalScenario scenario = SignalScenario.readerOrLoser();
        TestNotifier notifier = new TestNotifier(OptionalLong.of(1L));
        ClientRuntime runtime = runtime(scenario, notifier);

        runtime.initialize();
        runtime.dispatchWidget(applyWidget(scenario.presetId));


        assertEquals(AppearanceSyncStatus.OFFICIAL, runtime.snapshot().syncStatus());
        assertEquals(1, scenario.reconciliationCalls);
        assertEquals(0, notifier.notifications);
    }

    @Test
    void confirmedOldAccountResultIsDroppedAfterMinecraftUserSwitch() {
        SignalScenario scenario = SignalScenario.confirmedPartial();
        scenario.switchUserBeforePublication = true;
        TestNotifier notifier = new TestNotifier(OptionalLong.of(1L));
        ClientRuntime runtime = runtime(scenario, notifier);

        runtime.initialize();
        runtime.dispatchWidget(applyWidget(scenario.presetId));

        assertEquals(AppearanceSyncStatus.PENDING, runtime.snapshot().syncStatus());
        assertEquals(1, scenario.reconciliationCalls);
        assertEquals(0, notifier.notifications);
    }

    @Test
    void optifineObservationNeverSignalsOfficialProfileRefresh() {
        SignalScenario scenario = SignalScenario.readerOrLoser();
        scenario.providers = scenario.providers.enable(
                com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE,
                com.naocraftlab.skins.core.provider.BuiltinProvider.OPTIFINE);
        TestNotifier notifier = new TestNotifier(OptionalLong.of(1L));
        ClientRuntime runtime = runtime(scenario, notifier);
        runtime.initialize();
        var cape = new com.naocraftlab.skins.core.provider.ProviderCape("optifine", "a".repeat(64), false);

        scenario.optifineObservation.accept(new ClientOperations.OptiFineObservation(
                scenario.currentIdentity.profileId(), scenario.currentIdentity.profileName(),
                runtime.snapshot().providers().cape().configurationRevision(), cape));

        assertEquals(cape, runtime.snapshot().providers().cape().optifine().value());
        assertEquals(0, notifier.notifications);
    }

    @Test
    void explicitCapeRefreshSignalsOneConfirmedOptifineChangeOnly() {
        SignalScenario scenario = SignalScenario.readerOrLoser();
        scenario.providers = scenario.providers
                .disable(com.naocraftlab.skins.core.provider.AppearanceProviders.Component.SKIN,
                        com.naocraftlab.skins.core.provider.BuiltinProvider.MINECRAFT)
                .disable(com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE,
                        com.naocraftlab.skins.core.provider.BuiltinProvider.MINECRAFT)
                .enable(com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE,
                        com.naocraftlab.skins.core.provider.BuiltinProvider.OPTIFINE);
        scenario.providers = new com.naocraftlab.skins.core.provider.AppearanceProviders(
                scenario.providers.skin(), scenario.providers.cape().observeOptifine(null));
        TestNotifier notifier = new TestNotifier(OptionalLong.of(1L));
        ClientRuntime runtime = runtime(scenario, notifier);
        runtime.initialize();
        runtime.dispatchWidget("providers.tab.CAPE");
        var cape = new com.naocraftlab.skins.core.provider.ProviderCape("optifine", "a".repeat(64), false);
        scenario.refreshCape = cape;
        runtime.dispatchWidget("providers.refresh");
        assertEquals(1, notifier.notifications);
        runtime.dispatchWidget("providers.refresh");
        assertEquals(1, notifier.notifications);
        scenario.refreshCape = null;
        runtime.dispatchWidget("providers.refresh");
        assertEquals(2, notifier.notifications);
    }

    @Test
    void explicitRefreshUsesCurrentConnectionAfterBackendSwitch() {
        SignalScenario scenario = SignalScenario.readerOrLoser();
        scenario.providers = scenario.providers.enable(
                com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE,
                com.naocraftlab.skins.core.provider.BuiltinProvider.OPTIFINE);
        scenario.providers = new com.naocraftlab.skins.core.provider.AppearanceProviders(
                scenario.providers.skin(), scenario.providers.cape().observeOptifine(null));
        TestNotifier notifier = new TestNotifier(OptionalLong.of(1L));
        scenario.afterRefresh = () -> notifier.connection = OptionalLong.of(2L);
        ClientRuntime runtime = runtime(scenario, notifier);
        runtime.initialize();
        runtime.dispatchWidget("providers.tab.CAPE");
        scenario.refreshCape = new com.naocraftlab.skins.core.provider.ProviderCape(
                "optifine", "a".repeat(64), false);
        runtime.dispatchWidget("providers.refresh");
        assertEquals(1, notifier.notifications);
        notifier.connection = OptionalLong.empty();
        scenario.afterRefresh = () -> {};
        scenario.refreshCape = null;
        runtime.dispatchWidget("providers.refresh");
        assertEquals(1, notifier.notifications);
    }

    @Test
    void unrelatedObservationDuringFailedRefreshDoesNotSignal() {
        SignalScenario scenario = SignalScenario.readerOrLoser();
        scenario.providers = scenario.providers.enable(
                com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE,
                com.naocraftlab.skins.core.provider.BuiltinProvider.OPTIFINE);
        scenario.providers = new com.naocraftlab.skins.core.provider.AppearanceProviders(
                scenario.providers.skin(), scenario.providers.cape().observeOptifine(null));
        scenario.refreshConfirmed = false;
        TestNotifier notifier = new TestNotifier(OptionalLong.of(1L));
        ClientRuntime runtime = runtime(scenario, notifier);
        runtime.initialize();
        runtime.dispatchWidget("providers.tab.CAPE");
        var unrelated = new com.naocraftlab.skins.core.provider.ProviderCape(
                "optifine", "b".repeat(64), false);
        scenario.afterRefresh = () -> scenario.optifineObservation.accept(
                new ClientOperations.OptiFineObservation(scenario.currentIdentity.profileId(),
                        scenario.currentIdentity.profileName(),
                        scenario.providers.cape().configurationRevision(), unrelated));
        runtime.dispatchWidget("providers.refresh");
        assertEquals(unrelated, runtime.snapshot().providers().cape().optifine().value());
        assertEquals(0, notifier.notifications);
    }

    @Test
    void unchangedProvidersOpenAndRefreshDoNotReattachLocalAppearance() {
        SignalScenario scenario = SignalScenario.readerOrLoser();
        var local = AppliedAppearance.localSkin(TestFixtures.ACCOUNT_ID,
                "a".repeat(64), com.naocraftlab.skins.core.model.SkinVariant.CLASSIC,
                Optional.empty());
        scenario.localAppearance = Optional.of(local);
        AtomicInteger attachments = new AtomicInteger();
        AppearanceRefreshCoordinator<String> refresh = new AppearanceRefreshCoordinator<>(
                CLIENT,
                expected -> CompletableFuture.completedFuture(Optional.of(
                        new SignedProfileResolver.ResolvedProfile<>(
                                expected.profileId(), expected, "profile"))),
                ignored -> {
                    attachments.incrementAndGet();
                    return PlayerAppearanceSink.ApplyResult.UPDATED;
                }, DiagnosticSinks.discarding());
        ClientRuntime runtime = new ClientRuntime(scenario.operations, CLIENT, CANCELLED_PICKER,
                Runnable::run, TEXT, Optional.of(refresh), Optional.empty(),
                DiagnosticSinks.discarding());
        runtime.initialize();
        int initial = attachments.get();
        runtime.dispatchWidget("gallery.providers");
        runtime.dispatchWidget("providers.refresh");
        assertEquals(initial, attachments.get());
        runtime.closeScreen();
        runtime.reopen();
        assertEquals(initial, attachments.get());
        scenario.providers = scenario.providers.move(
                com.naocraftlab.skins.core.provider.AppearanceProviders.Component.SKIN,
                com.naocraftlab.skins.core.provider.BuiltinProvider.MINECRAFT, -1);
        runtime.dispatchWidget("gallery.providers");
        assertEquals(initial + 1, attachments.get());
    }

    @Test
    void durableMinecraftChangeWithoutRefreshConfirmationDoesNotSignal() {
        SignalScenario scenario = SignalScenario.readerOrLoser();
        var first = new com.naocraftlab.skins.core.provider.ProviderCape("first", null, false);
        var shared = new com.naocraftlab.skins.core.provider.ProviderCape("shared", null, false);
        var confirmed = new com.naocraftlab.skins.core.provider.ProviderCape("confirmed", null, false);
        scenario.providers = new com.naocraftlab.skins.core.provider.AppearanceProviders(
                scenario.providers.skin(), scenario.providers.cape().observeMinecraft(first));
        TestNotifier notifier = new TestNotifier(OptionalLong.of(1L));
        ClientRuntime runtime = runtime(scenario, notifier);
        runtime.initialize();
        runtime.dispatchWidget("providers.tab.CAPE");
        scenario.providers = new com.naocraftlab.skins.core.provider.AppearanceProviders(
                scenario.providers.skin(), scenario.providers.cape().observeMinecraft(shared));
        runtime.dispatchWidget("providers.refresh");
        assertEquals(0, notifier.notifications);
        scenario.providers = new com.naocraftlab.skins.core.provider.AppearanceProviders(
                scenario.providers.skin(), scenario.providers.cape().observeMinecraft(confirmed));
        scenario.confirmedMinecraft = com.naocraftlab.skins.core.provider.ProviderObservation.observed(confirmed);
        runtime.dispatchWidget("providers.refresh");
        assertEquals(1, notifier.notifications);
    }

    private static ClientRuntime runtime(SignalScenario scenario, TestNotifier notifier) {
        return new ClientRuntime(
                scenario.operations,
                CLIENT,
                CANCELLED_PICKER,
                Runnable::run,
                TEXT,
                Optional.empty(),
                Optional.of(notifier),
                DiagnosticSinks.discarding());
    }

    private static String applyWidget(UUID presetId) {
        return "gallery.preset." + presetId + ".apply";
    }

    private static final class TestNotifier implements ServerAppearanceRefreshNotifier {
        private OptionalLong connection;
        private int notifications;

        private TestNotifier(OptionalLong connection) {
            this.connection = connection;
        }

        @Override
        public OptionalLong activeConnectionGeneration() {
            return connection;
        }

        @Override
        public void requestOfficialProfileRefresh() {
            notifications++;
        }
    }

    private static final class SignalScenario implements InvocationHandler {
        private final AccountState account = TestFixtures.account(1);
        private final SessionValidation session = TestFixtures.validSession();
        private final UUID presetId = account.presets().get(0).id();
        private final Optional<PresetApplicationOutcome> settlement;
        private final ClientOperations operations;
        private GameSessionTokenSource.SessionIdentity currentIdentity = session.sessionIdentity();
        private boolean switchUserBeforePublication;
        private ClientOperations.DurableAppearance durable = new ClientOperations.DurableAppearance(
                TestFixtures.ACCOUNT_ID,
                0,
                AppearanceSyncStatus.LOCAL_ONLY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        private int reconciliationCalls;
        private Consumer<ClientOperations.OptiFineObservation> optifineObservation;
        private com.naocraftlab.skins.core.provider.AppearanceProviders providers = com.naocraftlab.skins.core.provider.AppearanceProviders.initial();
        private com.naocraftlab.skins.core.provider.ProviderCape refreshCape;
        private Runnable afterRefresh = () -> {};
        private boolean refreshConfirmed = true;
        private Optional<AppliedAppearance> localAppearance = Optional.empty();
        private com.naocraftlab.skins.core.provider.ProviderObservation<?> confirmedMinecraft;

        private SignalScenario(Optional<PresetApplicationOutcome> settlement) {
            this.settlement = settlement;
            operations = (ClientOperations) Proxy.newProxyInstance(
                    ClientOperations.class.getClassLoader(),
                    new Class<?>[] {ClientOperations.class},
                    this);
        }

        private static SignalScenario confirmedPartial() {
            SessionValidation session = TestFixtures.validSession();
            PresetApplicationOutcome outcome = new PresetApplicationOutcome(
                    MutationResult.PARTIAL,
                    ApplicationPhase.CAPE_MUTATION,
                    session.profile(),
                    session.profile(),
                    AppliedAppearance.accountDefault(TestFixtures.ACCOUNT_ID, Optional.empty()),
                    ApiFailureKind.FORBIDDEN,
                    Set.of(RecoveryAction.RETRY_CAPE),
                    RemoteAppearanceImpact.CONFIRMED_CHANGED,
                    "Skin changed, but cape recovery is still required.");
            return new SignalScenario(Optional.of(outcome));
        }

        private static SignalScenario readerOrLoser() {
            return new SignalScenario(Optional.empty());
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            return switch (method.getName()) {
                case "initialize" -> initialData();
                case "warmedInitialData", "rateLimitRemaining" -> Optional.empty();
                case "reconciliationRecommended", "rateLimited" -> false;
                case "usePreset" -> selectPreset((UUID) arguments[0]);
                case "reconcileAppearance" -> reconcile();
                case "reconciliationKey" -> Optional.of(durable.reconciliationKey());
                case "durableAppearance" -> Optional.of(durable);
                case "sessionIdentity" -> currentIdentity;
                case "reloadProviders", "refreshProviders" -> new ClientOperations.DurableAppearance(
                        account.accountId(), durable.intentRevision(), durable.syncStatus(),
                        durable.activePresetId(), localAppearance,
                        durable.outerLayerVisibility(), providers);
                case "refreshProvidersWithObservation" -> new ClientOperations.ProviderRefresh(
                        new ClientOperations.DurableAppearance(account.accountId(), durable.intentRevision(),
                                durable.syncStatus(), durable.activePresetId(), localAppearance,
                                durable.outerLayerVisibility(), providers), confirmedMinecraft);
                case "refreshOptiFineCapes" -> {
                    if (arguments != null && arguments.length == 1) {
                        if (refreshConfirmed) optifineObservation.accept(new ClientOperations.OptiFineObservation(
                                currentIdentity.profileId(), currentIdentity.profileName(),
                                providers.cape().configurationRevision(), refreshCape));
                        afterRefresh.run();
                        ((Consumer<com.naocraftlab.skins.core.provider.ProviderObservation<com.naocraftlab.skins.core.provider.ProviderCape>>) arguments[0])
                                .accept(refreshConfirmed
                                        ? com.naocraftlab.skins.core.provider.ProviderObservation.observed(refreshCape)
                                        : null);
                    }
                    yield null;
                }
                case "onOptiFineObservation" -> {
                    optifineObservation = (Consumer<ClientOperations.OptiFineObservation>) arguments[0];
                    yield null;
                }
                case "close", "startOptiFineCapes", "selfCapeCandidatesChanged", "closeOptiFineCapes" -> null;
                case "toString" -> "SignalScenarioOperations";
                default -> {
                    if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, arguments);
                    throw new AssertionError("Unexpected ClientOperations call in signal test: " + method.getName());
                }
            };
        }

        private ClientOperations.InitialData initialData() {
            return new ClientOperations.InitialData(
                    account,
                    session,
                    Optional.empty(),
                    Optional.empty(),
                    localAppearance,
                    false,
                    List.of(),
                    AccountUiPreferences.defaults(account.accountId()),
                    Optional.empty(),
                    OwnedCapeInventory.empty(account.accountId(), Instant.EPOCH),
                    durable.intentRevision(),
                    durable.syncStatus(), providers);
        }

        private ClientOperations.PresetUse selectPreset(UUID selectedPresetId) {
            assertEquals(presetId, selectedPresetId);
            durable = new ClientOperations.DurableAppearance(
                    account.accountId(),
                    1,
                    AppearanceSyncStatus.PENDING,
                    Optional.of(presetId),
                    Optional.empty(),
                    Optional.empty(), providers);
            return new ClientOperations.PresetUse(
                    account,
                    session,
                    presetId,
                    Optional.empty(),
                    Optional.empty(),
                    true,
                    true,
                    Optional.empty(),
                    durable.intentRevision(),
                    durable.syncStatus(), providers);
        }

        private Optional<ClientOperations.ReconciliationResult> reconcile() {
            reconciliationCalls++;
            AppearanceSyncStatus settledStatus = settlement.isPresent()
                    ? AppearanceSyncStatus.PARTIAL
                    : AppearanceSyncStatus.OFFICIAL;
            durable = new ClientOperations.DurableAppearance(
                    account.accountId(),
                    durable.intentRevision(),
                    settledStatus,
                    durable.activePresetId(),
                    settlement.flatMap(PresetApplicationOutcome::optionalAppliedAppearance),
                    Optional.empty(), providers);
            ClientOperations.ReconciliationResult result = new ClientOperations.ReconciliationResult(
                    account,
                    session,
                    Optional.empty(),
                    durable,
                    settlement);
            if (switchUserBeforePublication) {
                currentIdentity = new GameSessionTokenSource.SessionIdentity(
                        UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                        "Switched");
            }
            return Optional.of(result);
        }
    }
}
