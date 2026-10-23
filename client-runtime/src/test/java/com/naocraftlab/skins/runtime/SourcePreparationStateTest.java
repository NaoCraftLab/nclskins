package com.naocraftlab.skins.runtime;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourcePreparationStateTest {
    private enum Source { LOCAL, SESSION, EXTERNAL, EXTENSION }

    @Test
    void additionalBindingsUseTheSameScopeCooldownCoalescingAndIndependentCompletionAlgorithms() {
        Map<Source, ProviderRefreshBehavior> behaviors = Map.of(
                Source.LOCAL, new ProviderRefreshBehavior(ProviderRefreshBehavior.Observation.LOCAL,
                        ProviderRefreshBehavior.Preparation.LOCAL_ASSET, ProviderRefreshBehavior.Cooldown.NONE, false),
                Source.SESSION, new ProviderRefreshBehavior(ProviderRefreshBehavior.Observation.SESSION,
                        ProviderRefreshBehavior.Preparation.REMOTE_REFERENCE, ProviderRefreshBehavior.Cooldown.SESSION, true),
                Source.EXTERNAL, new ProviderRefreshBehavior(ProviderRefreshBehavior.Observation.EXTERNAL,
                        ProviderRefreshBehavior.Preparation.OWNED, ProviderRefreshBehavior.Cooldown.SOURCE, true),
                Source.EXTENSION, new ProviderRefreshBehavior(ProviderRefreshBehavior.Observation.SESSION,
                        ProviderRefreshBehavior.Preparation.REMOTE_REFERENCE, ProviderRefreshBehavior.Cooldown.SESSION, true));
        var enabled = List.of(Source.LOCAL, Source.SESSION, Source.EXTERNAL, Source.EXTENSION);
        assertEquals(enabled, ProviderRefreshBehavior.resolve(enabled, Optional.empty(), behaviors::get));
        for (var source : enabled)
            assertEquals(List.of(source), ProviderRefreshBehavior.resolve(enabled, Optional.of(source), behaviors::get));
        assertTrue(ProviderRefreshBehavior.resolve(List.of(Source.LOCAL), Optional.of(Source.EXTENSION), behaviors::get).isEmpty());
        var facts = ProviderRefreshBehavior.cooldowns(enabled, behaviors::get, Optional.of(Duration.ofSeconds(10)),
                Map.of(Source.LOCAL, Duration.ofDays(1), Source.EXTERNAL, Duration.ofSeconds(20)));
        assertFalse(facts.containsKey(Source.LOCAL));
        assertEquals(facts.get(Source.SESSION), facts.get(Source.EXTENSION));
        assertFalse(ProviderRefreshBehavior.blocked(enabled, facts));
        assertTrue(ProviderRefreshBehavior.blocked(List.of(Source.SESSION, Source.EXTERNAL, Source.EXTENSION), facts));
        assertFalse(ProviderRefreshBehavior.blocked(List.of(), facts));
        assertTrue(behaviors.get(Source.EXTENSION).confirmedChangeSignal());
        assertFalse(behaviors.get(Source.LOCAL).confirmedChangeSignal());

        var candidates = Set.of(Source.LOCAL, Source.SESSION, Source.EXTENSION);
        Map<Source, Integer> reads = new java.util.EnumMap<>(Source.class);
        Map<Source, ProviderEffectBinding<java.util.function.Function<SourcePreparationState.Ticket<Source, String, String>, String>>> bindings =
                new java.util.EnumMap<>(Source.class);
        for (var source : candidates) bindings.put(source, new ProviderEffectBinding<>(behaviors.get(source), ticket -> {
            if (behaviors.get(source).preparation() == ProviderRefreshBehavior.Preparation.REMOTE_REFERENCE) {
                if (!ticket.remoteRead()) return null;
                reads.merge(source, 1, Integer::sum);
            }
            return ticket.observation();
        }));
        var state = new SourcePreparationState<Source, String, String>(bindings.keySet());
        for (var source : candidates) state.offer(source, "account/generation", "A", true, true);
        var first = state.issue();
        assertEquals(3, first.size());
        for (int update = 0; update < 1000; update++) {
            state.offer(Source.LOCAL, "account/generation", "B" + update, true, false);
            state.offer(Source.SESSION, "account/generation", "A", true, false);
            state.offer(Source.EXTENSION, "account/generation", "A", true, false);
        }
        assertEquals(1, state.pending().size());
        List<Source> adopted = new ArrayList<>();
        for (var ticket : first) {
            if (state.current(ticket)) {
                assertNotNull(bindings.get(ticket.source()).effect().apply(ticket));
                adopted.add(ticket.source()); assertTrue(ticket.remoteRead());
            }
            state.complete(ticket, state.current(ticket));
        }
        assertEquals(Set.of(Source.SESSION, Source.EXTENSION), Set.copyOf(adopted));
        var latest = state.issue();
        assertEquals(1, latest.size());
        assertEquals("B999", latest.get(0).observation());
        assertFalse(latest.get(0).remoteRead());
        assertNotNull(bindings.get(latest.get(0).source()).effect().apply(latest.get(0)));
        state.complete(latest.get(0), true);
        assertEquals(Map.of(Source.SESSION, 1, Source.EXTENSION, 1), reads, "only admitted independent reads survive the local scope");
        for (var source : candidates) state.offer(source, "account/generation", source == Source.LOCAL ? "B999" : "A", true, false);
        assertTrue(state.issue().isEmpty(), "completed unchanged inputs never create extra reads");
    }

    @Test
    void roundTripsDisableAndStaleCleanupNeverReviveOrClearNewTickets() {
        var state = new SourcePreparationState<String, Long, String>(Set.of("one", "two", "three"));
        state.offer("one", 1L, "A", true, true);
        var old = state.issue().get(0);
        state.offer("one", 1L, "B", true, false);
        state.offer("one", 1L, "A", true, false);
        assertFalse(state.current(old));
        state.complete(old, true);
        var latest = state.issue().get(0);
        assertFalse(latest.remoteRead());
        state.offer("one", 1L, "A", false, false);
        assertFalse(state.current(latest));
        state.offer("one", 2L, "A", true, true);
        state.complete(latest, false);
        assertEquals(1, state.pending().size());
        state.invalidate();
        assertTrue(state.issue().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> state.offer("unknown", 1L, "A", true, true));
    }

    @Test
    void queueRetainsPendingSourcesAndReportsInitialLatestAndClosedDispatchRejection() {
        var state = new SourcePreparationState<String, Long, String>(Set.of("one", "two", "three"));
        ArrayDeque<Runnable> workers = new ArrayDeque<>();
        AtomicBoolean reject = new AtomicBoolean();
        var queue = new CapePreparationQueue(action -> {
            if (reject.get()) throw new RejectedExecutionException();
            workers.add(action);
        });
        try {
            List<String> completed = new ArrayList<>();
            Runnable prepare = () -> {
                var batch = state.issue();
                batch.forEach(ticket -> { completed.add(ticket.source()); state.complete(ticket, true); });
            };
            for (String source : List.of("one", "two", "three")) {
                state.offer(source, 1L, "A", true, true);
                var pending = state.pending();
                queue.submit(CapePreparationQueue.Slot.SELF_CANDIDATES, prepare,
                        () -> pending.forEach(state::rejectPending));
            }
            assertEquals(1, workers.size());
            workers.remove().run();
            assertEquals(Set.of("one", "two", "three"), Set.copyOf(completed));
            assertEquals(1, workers.size());
            state.offer("one", 1L, "B", true, true);
            var pending = state.pending();
            queue.submit(CapePreparationQueue.Slot.SELF_CANDIDATES, prepare,
                    () -> pending.forEach(state::rejectPending));
            reject.set(true);
            workers.remove().run();
            assertTrue(workers.isEmpty());
            state.offer("two", 1L, "B", true, true);
            var initial = state.pending();
            queue.submit(CapePreparationQueue.Slot.SELF_CANDIDATES, prepare,
                    () -> initial.forEach(state::rejectPending));
            assertTrue(state.pending().isEmpty());
            reject.set(false);
            state.offer("two", 1L, "B", true, true);
            queue.submit(CapePreparationQueue.Slot.SELF_CANDIDATES, prepare);
            workers.remove().run();
            assertEquals(2, completed.stream().filter("two"::equals).count());
            queue.close();
            AtomicBoolean closed = new AtomicBoolean();
            queue.submit(CapePreparationQueue.Slot.SELF_CANDIDATES, () -> fail("closed queue"), () -> closed.set(true));
            assertTrue(closed.get());
        } finally { queue.close(); }
    }

    @Test
    void rejectedLatestContinuationClearsOnlyUnissuedWorkAndDoesNotStealRunningPermission() {
        var state = new SourcePreparationState<String, Long, String>(Set.of("running", "latest", "other"));
        ArrayDeque<Runnable> workers = new ArrayDeque<>();
        AtomicBoolean reject = new AtomicBoolean();
        List<SourcePreparationState.Ticket<String, Long, String>> running = new ArrayList<>();
        try (var queue = new CapePreparationQueue(action -> {
            if (reject.get()) throw new RejectedExecutionException();
            workers.add(action);
        })) {
            state.offer("running", 1L, "A", true, true);
            queue.submit(CapePreparationQueue.Slot.SELF_CANDIDATES, () -> {
                running.addAll(state.issue());
                state.offer("latest", 1L, "B", true, true);
                var pending = state.pending();
                queue.submit(CapePreparationQueue.Slot.SELF_CANDIDATES, () -> fail("dispatch rejected"),
                        () -> pending.forEach(state::rejectPending));
                reject.set(true);
            });
            workers.remove().run();
            assertTrue(workers.isEmpty());
            assertTrue(state.pending().isEmpty());
            state.offer("running", 1L, "A", true, false);
            assertTrue(state.pending().isEmpty(), "issued read remains coalesced after independent rejection");
            assertTrue(state.current(running.get(0)));
            state.complete(running.get(0), true);
            state.offer("latest", 1L, "B", true, true);
            assertEquals(1, state.pending().size(), "a new allowed checkpoint can recover the failed latest");
        }
    }

    @Test
    void visibleWriteAndDistributionCapabilitiesDoNotChooseIoPolicy() {
        var cape = com.naocraftlab.skins.core.provider.AppearanceProviders.Component.CAPE;
        var skin = com.naocraftlab.skins.core.provider.AppearanceProviders.Component.SKIN;
        var offline = com.naocraftlab.skins.core.provider.BuiltinProvider.OFFLINE;
        var minecraft = com.naocraftlab.skins.core.provider.BuiltinProvider.MINECRAFT;
        var derived = com.naocraftlab.skins.core.provider.BuiltinProvider.SNEAKY;
        var external = com.naocraftlab.skins.core.provider.BuiltinProvider.OPTIFINE;
        assertTrue(offline.canWrite(skin));
        assertTrue(minecraft.canWrite(skin));
        assertEquals(ProviderRefreshBehavior.Cooldown.NONE, ProviderRefreshBehavior.of(offline, skin).cooldown());
        assertEquals(ProviderRefreshBehavior.Cooldown.SESSION, ProviderRefreshBehavior.of(minecraft, skin).cooldown());
        assertEquals(derived.capabilities(cape), external.capabilities(cape));
        assertEquals(ProviderRefreshBehavior.Observation.DERIVED, ProviderRefreshBehavior.of(derived, cape).observation());
        assertEquals(ProviderRefreshBehavior.Observation.EXTERNAL, ProviderRefreshBehavior.of(external, cape).observation());
        assertEquals(ProviderRefreshBehavior.Cooldown.NONE, ProviderRefreshBehavior.of(derived, cape).cooldown());
        assertEquals(ProviderRefreshBehavior.Cooldown.SOURCE, ProviderRefreshBehavior.of(external, cape).cooldown());
    }

    @Test
    void missingAndIncompatibleBindingsFailBeforeAnAction() {
        var behavior = new ProviderRefreshBehavior(ProviderRefreshBehavior.Observation.SESSION,
                ProviderRefreshBehavior.Preparation.REMOTE_REFERENCE, ProviderRefreshBehavior.Cooldown.SESSION, true);
        assertThrows(NullPointerException.class, () -> new ProviderEffectBinding<Runnable>(behavior, null));
        assertThrows(IllegalArgumentException.class, () -> new ProviderEffectBinding<Runnable>(behavior, () -> {})
                .require(ProviderRefreshBehavior.Observation.EXTERNAL));
        assertThrows(IllegalArgumentException.class, () -> new ProviderRefreshBehavior(ProviderRefreshBehavior.Observation.DERIVED,
                ProviderRefreshBehavior.Preparation.EXISTING_DERIVED, ProviderRefreshBehavior.Cooldown.SOURCE, false));
        assertThrows(IllegalArgumentException.class, () -> new ProviderRefreshBehavior(ProviderRefreshBehavior.Observation.LOCAL,
                ProviderRefreshBehavior.Preparation.LOCAL_ASSET, ProviderRefreshBehavior.Cooldown.NONE, true));
        assertThrows(NullPointerException.class, () -> ProviderRefreshBehavior.resolve(List.of("missing"), Optional.empty(), ignored -> null));
    }
}
