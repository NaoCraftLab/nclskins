package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.provider.AppearanceProviders;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.core.provider.ProviderCape;
import com.naocraftlab.skins.core.provider.ProviderObservation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ProviderRefreshService implements ProviderRefreshUseCases {
    private final ProviderRefreshEffects effects;
    private final CapeObservationPort capes;
    private final ClientSessionView session;
    private final Executor worker;
    private final Consumer<Runnable> client;
    private final Supplier<AppearanceProviders> current;
    private final Optional<ServerAppearanceReadiness> readiness;
    private final Map<BuiltinProvider, ProviderEffectBinding<ExternalRefresh>> externalBindings;
    private final Map<Key, Comparison> comparisons = new HashMap<>();
    private boolean closed;

    public ProviderRefreshService(ProviderRefreshEffects effects, CapeObservationPort capes,
            ClientSessionView session, Executor worker, Consumer<Runnable> client,
            Supplier<AppearanceProviders> current, Optional<ServerAppearanceReadiness> readiness) {
        this.effects = Objects.requireNonNull(effects, "effects");
        this.capes = Objects.requireNonNull(capes, "capes");
        this.session = Objects.requireNonNull(session, "session");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.client = Objects.requireNonNull(client, "client");
        this.current = Objects.requireNonNull(current, "current");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        Map<BuiltinProvider, ProviderEffectBinding<ExternalRefresh>> bindings = new java.util.EnumMap<>(BuiltinProvider.class);
        for (BuiltinProvider provider : BuiltinProvider.values()) {
            for (AppearanceProviders.Component component : AppearanceProviders.Component.values()) {
                if (!(component == AppearanceProviders.Component.SKIN ? provider.supportsSkin() : provider.supportsCape())) continue;
                var behavior = ProviderRefreshBehavior.of(provider, component);
                if (behavior.observation() == ProviderRefreshBehavior.Observation.EXTERNAL) {
                    if (component != AppearanceProviders.Component.CAPE) throw new IllegalArgumentException("Missing external effect binding");
                    bindings.put(provider, new ProviderEffectBinding<>(behavior,
                            (scope, completion) -> capes.refreshCapeProvider(scope, provider, completion)));
                }
            }
        }
        this.externalBindings = Map.copyOf(bindings);
    }

    public synchronized CompletableFuture<ProviderRefreshResult> refresh(ProviderRefreshRequest request,
            AppearanceProviders before) {
        var identity = session.sessionIdentity();
        Scope scope = new Scope(request, identity, before, request.resolve(before), capes.capeObservationGeneration());
        if (closed) return CompletableFuture.completedFuture(ProviderRefreshResult.terminal(scope, ProviderRefreshResult.Status.SUPERSEDED));
        comparisons.values().stream().filter(value -> !sameContext(value.scope, scope)).toList().forEach(this::terminate);
        Key key = new Key(identity, scope.capeGeneration(), before.skin().configurationRevision(),
                before.cape().configurationRevision(), request.component(), scope.providers());
        Comparison existing = comparisons.get(key);
        if (existing != null) return existing.initial;
        if (request.blockedByRateLimit(before, session.rateLimitRemaining(), cooldowns(scope)))
            return CompletableFuture.completedFuture(ProviderRefreshResult.terminal(scope, ProviderRefreshResult.Status.RATE_LIMITED));
        Comparison comparison = new Comparison(scope, key);
        comparisons.put(key, comparison);
        try {
            worker.execute(() -> {
                ProviderRefreshResult result;
                try {
                    synchronized (ProviderRefreshService.this) {
                        if (closed || comparisons.get(key) != comparison) return;
                    }
                    result = scope.providers().isEmpty()
                            ? new ProviderRefreshResult(scope, Optional.empty(), Map.of(), false)
                            : effects.refreshProviderSources(scope);
                } catch (Exception unavailable) {
                    result = ProviderRefreshResult.terminal(scope, ProviderRefreshResult.Status.FAILED);
                }
                comparison.initial.complete(result == null
                        ? ProviderRefreshResult.terminal(scope, ProviderRefreshResult.Status.FAILED) : result);
            });
        } catch (RuntimeException rejected) {
            comparisons.remove(key);
            comparison.initial.complete(ProviderRefreshResult.terminal(scope, ProviderRefreshResult.Status.FAILED));
        }
        return comparison.initial;
    }

    private Map<BuiltinProvider, java.time.Duration> cooldowns(Scope scope) {
        var result = new java.util.EnumMap<BuiltinProvider, java.time.Duration>(BuiltinProvider.class);
        if (scope.request().component() == AppearanceProviders.Component.CAPE) scope.providers().forEach(source ->
                capes.capeProviderCooldown(source).ifPresent(value -> result.put(source, value)));
        return result;
    }

    public synchronized boolean refreshing(ProviderRefreshRequest request, AppearanceProviders before) {
        if (closed) return false;
        try {
            Key key = new Key(session.sessionIdentity(), capes.capeObservationGeneration(),
                    before.skin().configurationRevision(), before.cape().configurationRevision(),
                    request.component(), request.resolve(before));
            Comparison comparison = comparisons.get(key);
            return comparison != null && current(comparison.scope);
        } catch (RuntimeException unavailable) { return false; }
    }

    public synchronized void initialPublished(ProviderRefreshResult result) {
        Comparison comparison = comparisons.values().stream().filter(value -> value.scope.equals(result.scope()))
                .findFirst().orElse(null);
        if (comparison == null || comparison.published) return;
        comparison.published = true;
        if (result.superseded() || !current(comparison.scope)
                || comparison.scope.request().blockedByRateLimit(current.get(), session.rateLimitRemaining(),
                        cooldowns(comparison.scope))) { terminate(comparison); return; }
        comparison.outcomes.putAll(result.outcomes());
        var sources = comparison.scope.request().component() == AppearanceProviders.Component.CAPE
                ? comparison.scope.providers().stream().filter(externalBindings::containsKey).toList()
                : List.<BuiltinProvider>of();
        comparison.pending = sources.size();
        if (sources.isEmpty()) { finish(comparison); return; }
        for (BuiltinProvider source : sources) {
            if (comparisons.get(comparison.key) != comparison) break;
            try {
                externalBindings.get(source).require(ProviderRefreshBehavior.Observation.EXTERNAL).refresh(comparison.scope, observation -> {
                    try { client.accept(() -> completed(comparison, source, observation)); }
                    catch (RuntimeException rejected) { synchronized (ProviderRefreshService.this) { terminate(comparison); } }
                });
            } catch (RuntimeException unavailable) {
                completed(comparison, source, null);
            }
        }
    }

    private synchronized void completed(Comparison comparison, BuiltinProvider source,
            ProviderObservation<ProviderCape> observation) {
        if (comparisons.get(comparison.key) != comparison || comparison.completed.contains(source)) return;
        comparison.completed.add(source);
        comparison.outcomes.put(source, observation != null && observation.known()
                ? new ProviderRefreshResult.Outcome(ProviderRefreshResult.Status.CONFIRMED, observation)
                : ProviderRefreshResult.Outcome.unconfirmed(ProviderRefreshResult.Status.UNKNOWN));
        if (--comparison.pending == 0) finish(comparison);
    }

    private void finish(Comparison comparison) {
        if (!current(comparison.scope)) { terminate(comparison); return; }
        var scope = comparison.scope;
        var before = scope.request().component() == AppearanceProviders.Component.SKIN ? scope.before().skin() : scope.before().cape();
        boolean changed = comparison.outcomes.entrySet().stream().anyMatch(entry -> {
            if (!ProviderRefreshBehavior.of(entry.getKey(), scope.request().component()).confirmedChangeSignal()
                    || entry.getValue().status() != ProviderRefreshResult.Status.CONFIRMED) return false;
            var old = before.observation(entry.getKey());
            var next = entry.getValue().observation();
            return old.known() && (scope.request().component() == AppearanceProviders.Component.CAPE
                    ? !sameCapeContent((ProviderCape) old.value(), (ProviderCape) next.value()) : !old.equals(next));
        });
        if (!changed) { comparisons.remove(comparison.key, comparison); return; }
        try {
            worker.execute(() -> {
                boolean accepted;
                try { accepted = effects.providerRefreshCurrent(scope); }
                catch (RuntimeException unavailable) { accepted = false; }
                boolean confirmedScope = accepted;
                try { client.accept(() -> {
                    synchronized (ProviderRefreshService.this) {
                        if (comparisons.remove(comparison.key, comparison) && confirmedScope && current(scope))
                            readiness.ifPresent(ServerAppearanceReadiness::start);
                    }
                }); } catch (RuntimeException rejected) {
                    synchronized (ProviderRefreshService.this) { terminate(comparison); }
                }
            });
        } catch (RuntimeException rejected) { terminate(comparison); }
    }

    private boolean current(Scope scope) {
        try {
            return !closed && scope.identity().equals(session.sessionIdentity())
                    && scope.configurationMatches(current.get())
                    && (scope.request().component() != AppearanceProviders.Component.CAPE
                        || scope.capeGeneration() == capes.capeObservationGeneration());
        } catch (RuntimeException unavailable) { return false; }
    }

    private static boolean sameContext(Scope left, Scope right) {
        return left.identity().equals(right.identity()) && left.capeGeneration() == right.capeGeneration()
                && left.configurationMatches(right.before());
    }

    private static boolean sameCapeContent(ProviderCape left, ProviderCape right) {
        if (left == null || right == null) return left == right;
        if (left.textureCacheKey() != null && right.textureCacheKey() != null)
            return left.textureCacheKey().equals(right.textureCacheKey())
                    && Objects.equals(left.hasElytra(), right.hasElytra()) && left.capeGlint() == right.capeGlint();
        return left.equals(right);
    }

    public synchronized void publicationRejected(ProviderRefreshResult result) {
        comparisons.values().stream().filter(value -> value.scope.equals(result.scope())).toList().forEach(this::terminate);
    }

    private void terminate(Comparison comparison) {
        comparisons.remove(comparison.key, comparison);
        comparison.initial.complete(ProviderRefreshResult.terminal(comparison.scope, ProviderRefreshResult.Status.SUPERSEDED));
    }

    public synchronized void invalidate() { List.copyOf(comparisons.values()).forEach(this::terminate); }
    public synchronized void close() { closed = true; invalidate(); }

    @FunctionalInterface
    private interface ExternalRefresh {
        void refresh(Scope scope, Consumer<ProviderObservation<ProviderCape>> completion);
    }

    private record Key(com.naocraftlab.skins.client.GameSessionTokenSource.SessionIdentity identity,
            long generation, long skinRevision, long capeRevision, AppearanceProviders.Component component,
            List<BuiltinProvider> providers) {}

    private static final class Comparison {
        final Scope scope;
        final Key key;
        final CompletableFuture<ProviderRefreshResult> initial = new CompletableFuture<>();
        final Map<BuiltinProvider, ProviderRefreshResult.Outcome> outcomes = new java.util.EnumMap<>(BuiltinProvider.class);
        final java.util.Set<BuiltinProvider> completed = java.util.EnumSet.noneOf(BuiltinProvider.class);
        boolean published;
        int pending;
        Comparison(Scope scope, Key key) { this.scope = scope; this.key = key; }
    }
}
