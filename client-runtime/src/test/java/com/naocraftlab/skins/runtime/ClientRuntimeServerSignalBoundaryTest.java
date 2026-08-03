package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.FilePicker;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.ServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.core.api.ApiFailureKind;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.MutationResult;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.ApplicationPhase;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.core.service.RecoveryAction;
import com.naocraftlab.skins.core.service.RemoteAppearanceImpact;
import com.naocraftlab.skins.core.service.SessionValidation;
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
import org.junit.jupiter.api.Test;

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

    private static ClientRuntime runtime(SignalScenario scenario, TestNotifier notifier) {
        return new ClientRuntime(
                scenario.operations,
                CLIENT,
                CANCELLED_PICKER,
                Runnable::run,
                TEXT,
                Optional.empty(),
                Optional.of(notifier));
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
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "initialize" -> initialData();
                case "reconciliationRecommended", "rateLimited" -> false;
                case "usePreset" -> selectPreset((UUID) arguments[0]);
                case "reconcileAppearance" -> reconcile();
                case "reconciliationKey" -> Optional.of(durable.reconciliationKey());
                case "durableAppearance" -> Optional.of(durable);
                case "sessionIdentity" -> currentIdentity;
                case "close" -> null;
                case "toString" -> "SignalScenarioOperations";
                default -> throw new AssertionError(
                        "Unexpected ClientOperations call in signal test: " + method.getName());
            };
        }

        private ClientOperations.InitialData initialData() {
            return new ClientOperations.InitialData(
                    account,
                    session,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    false,
                    List.of(),
                    AccountUiPreferences.defaults(account.accountId()),
                    Optional.empty(),
                    OwnedCapeInventory.empty(account.accountId(), Instant.EPOCH),
                    durable.intentRevision(),
                    durable.syncStatus());
        }

        private ClientOperations.PresetUse selectPreset(UUID selectedPresetId) {
            assertEquals(presetId, selectedPresetId);
            durable = new ClientOperations.DurableAppearance(
                    account.accountId(),
                    1,
                    AppearanceSyncStatus.PENDING,
                    Optional.of(presetId),
                    Optional.empty(),
                    Optional.empty());
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
                    durable.syncStatus());
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
                    Optional.empty());
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
