package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderCape;
import com.naocraftlab.skins.core.provider.ProviderObservation;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ProviderRefreshServiceTest {
    @Test void activeScopeReflectsInitialStageCallbacksAndComparisonWithoutBlockingOtherScopes() {
        Fixture fixture = new Fixture();
        ArrayDeque<Runnable> worker = new ArrayDeque<>();
        fixture.worker = worker::add;
        fixture.defer = true;
        fixture.rebuildService();
        var request = ProviderRefreshRequest.one(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC);
        var initial = fixture.service.refresh(request, fixture.providers);
        assertTrue(fixture.service.refreshing(request, fixture.providers));
        assertFalse(fixture.service.refreshing(ProviderRefreshRequest.one(
                AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE), fixture.providers));
        worker.remove().run();
        fixture.service.initialPublished(initial.getNow(null));
        assertTrue(fixture.service.refreshing(request, fixture.providers));
        fixture.pending.remove(BuiltinProvider.SKINMC).accept(ProviderObservation.observed(
                new ProviderCape("changed", "b".repeat(64))));
        assertTrue(fixture.service.refreshing(request, fixture.providers), "Signal comparison is still coalesced");
        worker.remove().run();
        assertFalse(fixture.service.refreshing(request, fixture.providers));
        assertEquals(1, fixture.signals);
        var next = fixture.service.refresh(request, fixture.providers);
        assertTrue(fixture.service.refreshing(request, fixture.providers));
        fixture.service.close();
        assertFalse(fixture.service.refreshing(request, fixture.providers));
        assertTrue(next.isDone());
    }

    @Test void activeScopeRejectsChangedConfigurationAccountAndGeneration() {
        for (String change : List.of("configuration", "account", "generation")) {
            Fixture fixture = new Fixture();
            fixture.defer = true;
            var before = fixture.providers;
            var request = ProviderRefreshRequest.one(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC);
            fixture.service.initialPublished(fixture.service.refresh(request, before).join());
            assertTrue(fixture.service.refreshing(request, before));
            if (change.equals("configuration")) fixture.providers = before.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE);
            if (change.equals("account")) fixture.identity = new GameSessionTokenSource.SessionIdentity(java.util.UUID.randomUUID(), "Other");
            if (change.equals("generation")) fixture.generation++;
            assertFalse(fixture.service.refreshing(request, fixture.providers), change);
            assertFalse(fixture.service.refreshing(request, before), change);
            fixture.service.close();
        }
    }

    @Test void everySingleSourceAndAllUseOnlyThePinnedComponentAndProviders() {
        for (var component : AppearanceProviders.Component.values()) {
            for (var source : BuiltinProvider.values()) {
                Fixture fixture = new Fixture();
                var request = ProviderRefreshRequest.one(component, source);
                var result = fixture.service.refresh(request, fixture.providers).join();
                fixture.service.initialPublished(result);
                boolean supported = component == AppearanceProviders.Component.SKIN ? source.supportsSkin() : source.supportsCape();
                assertEquals(supported ? List.of(source) : List.of(), result.scope().providers());
                assertEquals(supported ? 1 : 0, fixture.effects);
                assertEquals(supported && remote(source) ? List.of(source) : List.of(), fixture.remoteReads);
                assertEquals(0, fixture.signals);
            }
            Fixture fixture = new Fixture();
            var result = fixture.service.refresh(ProviderRefreshRequest.all(component), fixture.providers).join();
            fixture.service.initialPublished(result);
            assertEquals(component == AppearanceProviders.Component.SKIN
                    ? fixture.providers.skin().order() : fixture.providers.cape().order(), result.scope().providers());
            assertEquals(component == AppearanceProviders.Component.CAPE
                    ? List.of(BuiltinProvider.OPTIFINE, BuiltinProvider.SKINMC, BuiltinProvider.MINECRAFTCAPES) : List.of(), fixture.remoteReads);
        }
    }

    @Test void emptyDisabledAndUnsupportedScopesNeverBecomeAll() {
        Fixture fixture = new Fixture();
        fixture.providers = fixture.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE);
        var disabled = fixture.service.refresh(ProviderRefreshRequest.one(AppearanceProviders.Component.CAPE,
                BuiltinProvider.OPTIFINE), fixture.providers).join();
        fixture.service.initialPublished(disabled);
        assertTrue(disabled.scope().providers().isEmpty());
        for (var source : BuiltinProvider.values()) fixture.providers = fixture.providers.disable(AppearanceProviders.Component.CAPE, source);
        var empty = fixture.service.refresh(ProviderRefreshRequest.all(AppearanceProviders.Component.CAPE), fixture.providers).join();
        fixture.service.initialPublished(empty);
        assertTrue(empty.scope().providers().isEmpty());
        assertEquals(0, fixture.effects);
        assertTrue(fixture.remoteReads.isEmpty());
    }

    @Test void cooldownPreventsSingletonDemandAndWholeBlockedAllButKeepsLocalAllAvailable() {
        Fixture fixture = new Fixture();
        fixture.cooldowns.put(BuiltinProvider.SKINMC, Duration.ofSeconds(2));
        var limited = fixture.service.refresh(ProviderRefreshRequest.one(AppearanceProviders.Component.CAPE,
                BuiltinProvider.SKINMC), fixture.providers).join();
        fixture.service.initialPublished(limited);
        assertEquals(ProviderRefreshResult.Status.RATE_LIMITED, limited.outcomes().get(BuiltinProvider.SKINMC).status());
        assertEquals(0, fixture.effects);
        fixture.providers = AppearanceProviders.initial().disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE)
                .disable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFT)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC);
        fixture.service.initialPublished(fixture.service.refresh(ProviderRefreshRequest.all(AppearanceProviders.Component.CAPE), fixture.providers).join());
        assertEquals(0, fixture.effects);
        assertTrue(fixture.remoteReads.isEmpty());
        fixture.providers = fixture.providers.enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE);
        fixture.service.initialPublished(fixture.service.refresh(ProviderRefreshRequest.all(AppearanceProviders.Component.CAPE), fixture.providers).join());
        assertEquals(1, fixture.effects);
    }

    @Test void cooldownArisingAfterDispatchPreventsExternalAdmissionWithoutRetainingDemand() {
        Fixture fixture = new Fixture();
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        fixture.worker = queue::add;
        fixture.rebuildService();
        var future = fixture.service.refresh(ProviderRefreshRequest.one(AppearanceProviders.Component.CAPE,
                BuiltinProvider.SKINMC), fixture.providers);
        fixture.cooldowns.put(BuiltinProvider.SKINMC, Duration.ofNanos(1));
        queue.remove().run();
        fixture.service.initialPublished(future.getNow(null));
        assertTrue(fixture.remoteReads.isEmpty());
        fixture.cooldowns.clear();
        assertTrue(queue.isEmpty());
        assertTrue(fixture.remoteReads.isEmpty(), "Expiry alone must not replay the blocked operation");
        assertEquals(0, fixture.signals);
    }

    @Test void initialFailureDoesNotSkipIndependentExternalBranches() {
        Fixture fixture = new Fixture();
        fixture.failEffect = true;
        var result = fixture.service.refresh(ProviderRefreshRequest.all(AppearanceProviders.Component.CAPE), fixture.providers).join();
        assertEquals(ProviderRefreshResult.Status.FAILED, result.outcomes().get(BuiltinProvider.MINECRAFT).status());
        fixture.service.initialPublished(result);
        assertEquals(List.of(BuiltinProvider.OPTIFINE, BuiltinProvider.SKINMC, BuiltinProvider.MINECRAFTCAPES), fixture.remoteReads);
        assertEquals(0, fixture.signals);
    }

    @Test void sameScopeCoalescesAcrossInitialCompletionAndRosterWhileOtherScopeStaysIndependent() {
        Fixture fixture = new Fixture();
        fixture.defer = true;
        var request = ProviderRefreshRequest.one(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC);
        var initial = fixture.service.refresh(request, fixture.providers);
        fixture.service.initialPublished(initial.join());
        assertSame(initial, fixture.service.refresh(request, fixture.providers));
        fixture.service.initialPublished(initial.join());
        assertEquals(1, fixture.effects);
        assertEquals(List.of(BuiltinProvider.SKINMC), fixture.remoteReads);
        var other = fixture.service.refresh(ProviderRefreshRequest.one(AppearanceProviders.Component.CAPE,
                BuiltinProvider.OPTIFINE), fixture.providers).join();
        fixture.service.initialPublished(other);
        assertEquals(List.of(BuiltinProvider.SKINMC, BuiltinProvider.OPTIFINE), fixture.remoteReads);
        fixture.pending.get(BuiltinProvider.SKINMC).accept(null);
        fixture.service.refresh(request, fixture.providers);
        assertEquals(3, fixture.effects);
    }

    @Test void generationIdentityConfigurationInvalidateComparisonsWithoutExpandingScope() {
        for (int fence = 0; fence < 3; fence++) {
            Fixture fixture = new Fixture();
            fixture.defer = true;
            var initial = fixture.service.refresh(ProviderRefreshRequest.one(AppearanceProviders.Component.CAPE,
                    BuiltinProvider.SKINMC), fixture.providers).join();
            fixture.service.initialPublished(initial);
            var callback = fixture.pending.get(BuiltinProvider.SKINMC);
            switch (fence) {
                case 0 -> fixture.generation++;
                case 1 -> fixture.identity = new GameSessionTokenSource.SessionIdentity(java.util.UUID.randomUUID(), "Changed");
                case 2 -> fixture.providers = fixture.providers.disable(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE);
            }
            callback.accept(ProviderObservation.observed(new ProviderCape("changed", "b".repeat(64))));
            assertEquals(0, fixture.signals);
            assertEquals(List.of(BuiltinProvider.SKINMC), fixture.remoteReads);
        }
    }

    @Test void workerRejectionCloseAndInvalidationAlwaysCompleteInitialStage() {
        Fixture fixture = new Fixture();
        fixture.worker = ignored -> { throw new RejectedExecutionException(); };
        fixture.rebuildService();
        var failed = fixture.service.refresh(ProviderRefreshRequest.all(AppearanceProviders.Component.SKIN), fixture.providers);
        assertTrue(failed.isDone());
        assertEquals(ProviderRefreshResult.Status.FAILED, failed.join().outcomes().get(BuiltinProvider.MINECRAFT).status());
        ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        fixture.worker = jobs::add;
        fixture.rebuildService();
        var abandoned = fixture.service.refresh(ProviderRefreshRequest.all(AppearanceProviders.Component.SKIN), fixture.providers);
        assertFalse(abandoned.isDone());
        fixture.service.invalidate();
        assertTrue(abandoned.join().superseded());
        jobs.remove().run();
        assertEquals(0, fixture.effects);
        var closed = fixture.service.refresh(ProviderRefreshRequest.all(AppearanceProviders.Component.SKIN), fixture.providers);
        fixture.service.close();
        assertTrue(closed.join().superseded());
    }

    @Test void clientCallbackRejectionTerminatesComparisonAndAllowsAFreshRequest() {
        Fixture fixture = new Fixture();
        fixture.rejectClient = true;
        var request = ProviderRefreshRequest.one(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC);
        var initial = fixture.service.refresh(request, fixture.providers);
        fixture.service.initialPublished(initial.join());
        assertEquals(0, fixture.signals);
        assertNotSame(initial, fixture.service.refresh(request, fixture.providers));
        assertEquals(2, fixture.effects);
    }

    @Test void scopeUsesKnownConfirmedChangesIncludingLosingCapeButNeverSharedSnapshotOrFailure() {
        Fixture fixture = new Fixture();
        var changed = new ProviderCape("new", "b".repeat(64), true, true);
        fixture.next = ProviderObservation.observed(changed);
        var request = ProviderRefreshRequest.one(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC);
        fixture.service.initialPublished(fixture.service.refresh(request, fixture.providers).join());
        assertEquals(1, fixture.signals);
        fixture.providers = new AppearanceProviders(fixture.providers.skin(), fixture.providers.cape().observeSkinmc(changed));
        fixture.service.initialPublished(fixture.service.refresh(request, fixture.providers).join());
        assertEquals(1, fixture.signals);
        fixture.next = null;
        fixture.service.initialPublished(fixture.service.refresh(request, fixture.providers).join());
        assertEquals(1, fixture.signals);
        fixture.next = ProviderObservation.observed(null);
        fixture.service.initialPublished(fixture.service.refresh(request, fixture.providers).join());
        assertEquals(2, fixture.signals);
    }

    @Test void mergePreservesFreshOtherSourcesAndIgnoresUnconfirmedMinecraftSnapshot() {
        Fixture fixture = new Fixture();
        var request = ProviderRefreshRequest.one(AppearanceProviders.Component.CAPE, BuiltinProvider.OFFLINE);
        var result = fixture.service.refresh(request, fixture.providers).join();
        var fresh = new ProviderCape("fresh", "f".repeat(64));
        var current = new AppearanceProviders(fixture.providers.skin(), fixture.providers.cape().observeMinecraft(fresh).observeSkinmc(fresh));
        var merged = result.merge(current, Optional.empty()).orElseThrow().providers();
        assertEquals(current, merged);
        fixture.service.initialPublished(result);
        assertEquals(0, fixture.signals);
    }

    private static boolean remote(BuiltinProvider provider) {
        return provider == BuiltinProvider.OPTIFINE || provider == BuiltinProvider.SKINMC || provider == BuiltinProvider.MINECRAFTCAPES;
    }

    private static final class Fixture {
        AppearanceProviders providers = AppearanceProviders.initial()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.OPTIFINE)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SKINMC)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFTCAPES)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.SNEAKY)
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.CPM);
        GameSessionTokenSource.SessionIdentity identity = new GameSessionTokenSource.SessionIdentity(TestFixtures.ACCOUNT_ID, "Player");
        long generation;
        int effects;
        int signals;
        boolean failEffect;
        boolean rejectClient;
        boolean defer;
        Executor worker = Runnable::run;
        ProviderObservation<ProviderCape> next;
        final Map<BuiltinProvider, Duration> cooldowns = new EnumMap<>(BuiltinProvider.class);
        final Map<BuiltinProvider, Consumer<ProviderObservation<ProviderCape>>> pending = new EnumMap<>(BuiltinProvider.class);
        final java.util.ArrayList<BuiltinProvider> remoteReads = new java.util.ArrayList<>();
        ProviderRefreshService service;

        Fixture() {
            var cape = new ProviderCape("old", "a".repeat(64));
            providers = new AppearanceProviders(providers.skin(), providers.cape().observeOptifine(cape).observeSkinmc(cape).observeMinecraftcapes(cape));
            rebuildService();
        }

        @SuppressWarnings("unchecked")
        void rebuildService() {
            if (service != null) service.close();
            CapeObservationPort capes = (CapeObservationPort) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{CapeObservationPort.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "capeObservationGeneration" -> generation;
                        case "capeProviderCooldown" -> Optional.ofNullable(cooldowns.get((BuiltinProvider) args[0]));
                        case "refreshCapeProvider" -> {
                            var source = (BuiltinProvider) args[1];
                            remoteReads.add(source);
                            var completion = (Consumer<ProviderObservation<ProviderCape>>) args[2];
                            if (defer) pending.put(source, completion);
                            else completion.accept(next);
                            yield null;
                        }
                        default -> throw new AssertionError(method.getName());
                    });
            ClientSessionView session = new ClientSessionView() {
                public boolean rateLimited() { return false; }
                public Optional<Duration> rateLimitRemaining() { return Optional.empty(); }
                public GameSessionTokenSource.SessionIdentity sessionIdentity() { return identity; }
            };
            service = new ProviderRefreshService(new ProviderRefreshEffects() {
                public boolean providerRefreshCurrent(ProviderRefreshUseCases.Scope scope) {
                    return scope.identity().equals(identity) && scope.configurationMatches(providers);
                }
                public ProviderRefreshResult refreshProviderSources(ProviderRefreshUseCases.Scope scope) {
                effects++;
                if (failEffect) throw new IllegalStateException("unavailable");
                return new ProviderRefreshResult(scope, Optional.of(new AccountReconciliationPort.DurableAppearance(
                        identity.profileId(), 0, AppearanceSyncStatus.OFFICIAL, Optional.empty(), Optional.empty(), Optional.empty(), providers)), Map.of(), false);
                }
            }, capes, session, worker, action -> {
                if (rejectClient) throw new RejectedExecutionException();
                action.run();
            }, () -> providers, Optional.of(new ServerAppearanceReadiness() {
                public StartResult start() { signals++; return StartResult.STARTED; }
                public void close() { fail("Borrowed readiness must stay open"); }
            }));
        }
    }
}
