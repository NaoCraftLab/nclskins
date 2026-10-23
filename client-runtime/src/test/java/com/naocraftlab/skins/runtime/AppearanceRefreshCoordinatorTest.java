package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.ExpectedAppearance;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.SignedProfileResolver;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AppearanceRefreshCoordinatorTest {
    @Test
    void latestExpectedAppearanceWinsAndSinkRunsThroughClientExecutor() {
        DirectClientExecutor client = new DirectClientExecutor();
        List<CompletableFuture<Optional<SignedProfileResolver.ResolvedProfile<String>>>> resolutions =
                new ArrayList<>();
        SignedProfileResolver<String> resolver = expected -> {
            CompletableFuture<Optional<SignedProfileResolver.ResolvedProfile<String>>> result =
                    new CompletableFuture<>();
            resolutions.add(result);
            return result;
        };
        CountingSink sink = new CountingSink(PlayerAppearanceSink.ApplyResult.UPDATED);
        AppearanceRefreshCoordinator<String> coordinator =
                new AppearanceRefreshCoordinator<>(
                        client, resolver, sink, DiagnosticSinks.discarding());
        List<AppearanceRefresh.Result> published = new ArrayList<>();

        AppliedAppearance first = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID, "1".repeat(64), SkinVariant.CLASSIC, Optional.empty());
        AppliedAppearance second = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID, "2".repeat(64), SkinVariant.SLIM, Optional.empty());
        CompletableFuture<AppearanceRefresh.Result> old =
                coordinator.afterReconnect(first, published::add);
        CompletableFuture<AppearanceRefresh.Result> current =
                coordinator.afterReconnect(second, published::add);

        resolutions.get(0).complete(Optional.of(resolved(first, "old")));
        resolutions.get(1).complete(Optional.of(resolved(second, "new")));

        assertEquals(AppearanceRefresh.Result.SUPERSEDED, old.join());
        assertEquals(AppearanceRefresh.Result.UPDATED, current.join());
        assertEquals(List.of(
                AppearanceRefresh.Result.SUPERSEDED,
                AppearanceRefresh.Result.UPDATED), published);
        assertEquals(1, sink.installs.get());
        assertEquals(0, sink.invalidations.get());
        assertTrue(client.executions.get() >= 4);
    }

    @Test
    void accountDefaultIntentResetsOverrideWithoutResolvingAssets() {
        DirectClientExecutor client = new DirectClientExecutor();
        CountingSink sink = new CountingSink(PlayerAppearanceSink.ApplyResult.UPDATED);
        AppearanceRefreshCoordinator<String> coordinator = new AppearanceRefreshCoordinator<>(
                client,
                expected -> CompletableFuture.completedFuture(Optional.empty()),
                sink,
                DiagnosticSinks.discarding());
        AppliedAppearance appearance = AppliedAppearance.accountDefault(
                TestFixtures.ACCOUNT_ID, Optional.empty());

        assertEquals(
                AppearanceRefresh.Result.UPDATED,
                coordinator.afterReconnect(appearance, ignored -> {}).join());
        assertEquals(0, sink.installs.get());
        assertEquals(1, sink.resets.get());
        assertEquals(0, sink.invalidations.get());
    }

    @Test
    void deferredSinkInvalidatesOnlyTheCurrentExpectedAppearance() {
        DirectClientExecutor client = new DirectClientExecutor();
        CountingSink sink = new CountingSink(PlayerAppearanceSink.ApplyResult.DEFERRED);
        AppliedAppearance appearance = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID, "3".repeat(64), SkinVariant.CLASSIC, Optional.empty());
        AppearanceRefreshCoordinator<String> coordinator = new AppearanceRefreshCoordinator<>(
                client,
                expected -> CompletableFuture.completedFuture(
                        Optional.of(resolved(appearance, "resolved"))),
                sink,
                DiagnosticSinks.discarding());

        assertEquals(
                AppearanceRefresh.Result.DEFERRED,
                coordinator.afterReconnect(appearance, ignored -> {}).join());
        assertEquals(1, sink.installs.get());
        assertEquals(1, sink.invalidations.get());
        assertEquals(
                Optional.of("3".repeat(64)),
                sink.lastInvalidated.localSkinSha256());
    }

    @Test
    void liveOverrideReattachesBeforeResolverTouchesAssets() {
        DirectClientExecutor client = new DirectClientExecutor();
        AtomicInteger resolutions = new AtomicInteger();
        CountingSink sink = new CountingSink(
                PlayerAppearanceSink.ApplyResult.UPDATED,
                PlayerAppearanceSink.ApplyResult.UPDATED);
        AppearanceRefreshCoordinator<String> coordinator = new AppearanceRefreshCoordinator<>(
                client,
                expected -> {
                    resolutions.incrementAndGet();
                    return CompletableFuture.completedFuture(Optional.empty());
                },
                sink,
                DiagnosticSinks.discarding());
        AppliedAppearance appearance = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID, "4".repeat(64), SkinVariant.SLIM, Optional.empty());

        assertEquals(
                AppearanceRefresh.Result.UPDATED,
                coordinator.afterReconnect(appearance, ignored -> {}).join());
        assertEquals(1, sink.reattachments.get());
        assertEquals(0, resolutions.get());
        assertEquals(0, sink.installs.get());
        assertEquals(0, sink.invalidations.get());
    }

    @Test
    void capeOmissionAndDeferredAttachEmitOnlyFixedPayloadFreeCheckpoints() {
        List<com.naocraftlab.skins.diagnostics.DiagnosticEvent> events = new ArrayList<>();
        com.naocraftlab.skins.diagnostics.DiagnosticSink diagnostics = (event, lazyDetails) -> {
            events.add(event);
            var details = lazyDetails.get();
            assertTrue(details.status().isEmpty());
            assertTrue(details.failure().isEmpty());
            assertTrue(details.count().isEmpty());
            assertTrue(details.attempt().isEmpty());
            assertTrue(details.duration().isEmpty());
        };
        AppliedAppearance appearance = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID, "5".repeat(64), SkinVariant.SLIM,
                Optional.of(java.net.URI.create("https://textures.minecraft.net/texture/synthetic-cape")));
        AppliedAppearance skinOnly = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID, "5".repeat(64), SkinVariant.SLIM, Optional.empty());
        CountingSink sink = new CountingSink(PlayerAppearanceSink.ApplyResult.DEFERRED);
        var coordinator = new AppearanceRefreshCoordinator<>(new DirectClientExecutor(),
                expected -> CompletableFuture.completedFuture(Optional.of(resolved(skinOnly, "synthetic"))),
                sink, diagnostics);

        assertEquals(AppearanceRefresh.Result.DEFERRED,
                coordinator.afterReconnect(appearance, ignored -> {}).join());
        assertEquals(List.of(
                com.naocraftlab.skins.diagnostics.DiagnosticEvent.CLIENT_APPEARANCE_CAPE_UNAVAILABLE,
                com.naocraftlab.skins.diagnostics.DiagnosticEvent.CLIENT_APPEARANCE_ATTACHMENT_DEFERRED), events);
        assertEquals(resolved(skinOnly, "synthetic").expectedAppearance(), sink.lastInvalidated);
    }

    @Test
    void emptyResolutionIsDiagnosedAndLateResolutionCannotInstallAfterClose() {
        List<com.naocraftlab.skins.diagnostics.DiagnosticEvent> events = new ArrayList<>();
        var resolution = new CompletableFuture<Optional<SignedProfileResolver.ResolvedProfile<String>>>();
        CountingSink sink = new CountingSink(PlayerAppearanceSink.ApplyResult.UPDATED);
        AppliedAppearance appearance = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID, "6".repeat(64), SkinVariant.SLIM, Optional.empty());
        var coordinator = new AppearanceRefreshCoordinator<>(new DirectClientExecutor(),
                expected -> resolution, sink, (event, details) -> events.add(event));
        var pending = coordinator.afterReconnect(appearance, ignored -> {});
        coordinator.close();
        resolution.complete(Optional.of(resolved(appearance, "late")));
        assertEquals(AppearanceRefresh.Result.SUPERSEDED, pending.join());
        assertEquals(0, sink.installs.get());
        assertEquals(0, sink.invalidations.get());
        assertTrue(events.isEmpty());

        var empty = new AppearanceRefreshCoordinator<String>(new DirectClientExecutor(),
                expected -> CompletableFuture.completedFuture(Optional.empty()), sink,
                (event, details) -> events.add(event));
        assertEquals(AppearanceRefresh.Result.DEFERRED,
                empty.afterReconnect(appearance, ignored -> {}).join());
        assertEquals(List.of(com.naocraftlab.skins.diagnostics.DiagnosticEvent.CLIENT_APPEARANCE_ASSETS_UNAVAILABLE),
                events);
        assertEquals(resolved(appearance, "synthetic").expectedAppearance(), sink.lastInvalidated);
    }

    @Test
    void failedRegistrationBeforeCommitRetainsExistingOriginalOverride() {
        AppliedAppearance appearance = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID, "7".repeat(64), SkinVariant.SLIM,
                Optional.of(java.net.URI.create("https://textures.minecraft.net/texture/synthetic-cape")));
        AppliedAppearance skinOnly = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID, "7".repeat(64), SkinVariant.SLIM, Optional.empty());
        ExpectedAppearance original = resolved(appearance, "original").expectedAppearance();
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        try (var controller = new AppearanceOverrideController<ExpectedAppearance, String>(
                new AppearanceOverrideController.Strategy<>() {
                    @Override public ExpectedAppearance expected(ExpectedAppearance value) { return value; }
                    @Override public List<String> handles(ExpectedAppearance value) { return List.of("synthetic"); }
                    @Override public PlayerAppearanceSink.ApplyResult attach(ExpectedAppearance value) {
                        return PlayerAppearanceSink.ApplyResult.DEFERRED;
                    }
                    @Override public void restore() { restores.incrementAndGet(); }
                    @Override public void release(String handle) { releases.incrementAndGet(); }
                })) {
            controller.install(original);
            PlayerAppearanceSink<String> sink = new PlayerAppearanceSink<>() {
                @Override public ApplyResult apply(SignedProfileResolver.ResolvedProfile<String> value) {
                    return ApplyResult.NOT_INSTALLED;
                }
                @Override public ApplyResult reattach(ExpectedAppearance expected) { return controller.reattach(expected); }
                @Override public void invalidate(ExpectedAppearance expected) { controller.invalidate(expected); }
            };
            var coordinator = new AppearanceRefreshCoordinator<>(new DirectClientExecutor(),
                    expected -> CompletableFuture.completedFuture(Optional.of(resolved(skinOnly, "failed"))),
                    sink, DiagnosticSinks.discarding());
            assertEquals(AppearanceRefresh.Result.DEFERRED,
                    coordinator.afterReconnect(appearance, ignored -> {}).join());
            assertEquals(Optional.of(original), controller.active());
            assertEquals(0, releases.get());
            assertEquals(0, restores.get());
        }
        assertEquals(1, releases.get());
        assertEquals(1, restores.get());
    }

    @Test
    void controllerCannotCommitRejectedAttachmentOrOwnItsHandles() {
        AtomicInteger releases = new AtomicInteger();
        var expected = resolved(AppliedAppearance.localSkin(TestFixtures.ACCOUNT_ID,
                "8".repeat(64), SkinVariant.CLASSIC, Optional.empty()), "synthetic").expectedAppearance();
        try (var controller = new AppearanceOverrideController<ExpectedAppearance, String>(
                new AppearanceOverrideController.Strategy<>() {
                    @Override public ExpectedAppearance expected(ExpectedAppearance value) { return value; }
                    @Override public List<String> handles(ExpectedAppearance value) { return List.of("synthetic"); }
                    @Override public PlayerAppearanceSink.ApplyResult attach(ExpectedAppearance value) {
                        return PlayerAppearanceSink.ApplyResult.NOT_INSTALLED;
                    }
                    @Override public void restore() { throw new AssertionError("no committed override"); }
                    @Override public void release(String handle) { releases.incrementAndGet(); }
                })) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> controller.install(expected));
            assertTrue(controller.active().isEmpty());
        }
        assertEquals(0, releases.get(), "candidate handles still belong to the caller");
    }

    private static SignedProfileResolver.ResolvedProfile<String> resolved(
            AppliedAppearance appearance, String payload) {
        return new SignedProfileResolver.ResolvedProfile<>(
                appearance.profileId(),
                new ExpectedAppearance(
                        appearance.profileId(),
                        appearance.skinTexture(),
                        appearance.localSkinSha256(),
                        appearance.skinVariant().map(variant -> variant == SkinVariant.SLIM
                                ? SkinModel.SLIM
                                : SkinModel.CLASSIC),
                        appearance.capeTexture()),
                payload);
    }

    private static final class DirectClientExecutor implements ClientExecutor {
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public boolean isClientThread() {
            return true;
        }

        @Override
        public void execute(Runnable action) {
            executions.incrementAndGet();
            action.run();
        }
    }

    private static final class CountingSink implements PlayerAppearanceSink<String> {
        private final PlayerAppearanceSink.ApplyResult result;
        private final PlayerAppearanceSink.ApplyResult reattachResult;
        private final AtomicInteger installs = new AtomicInteger();
        private final AtomicInteger reattachments = new AtomicInteger();
        private final AtomicInteger resets = new AtomicInteger();
        private final AtomicInteger invalidations = new AtomicInteger();
        private ExpectedAppearance lastInvalidated;

        private CountingSink(PlayerAppearanceSink.ApplyResult result) {
            this(result, PlayerAppearanceSink.ApplyResult.DEFERRED);
        }

        private CountingSink(
                PlayerAppearanceSink.ApplyResult result,
                PlayerAppearanceSink.ApplyResult reattachResult) {
            this.result = result;
            this.reattachResult = reattachResult;
        }

        @Override
        public ApplyResult apply(SignedProfileResolver.ResolvedProfile<String> resolvedProfile) {
            installs.incrementAndGet();
            return result;
        }

        @Override
        public ApplyResult reattach(ExpectedAppearance expectedAppearance) {
            reattachments.incrementAndGet();
            return reattachResult;
        }

        @Override
        public ApplyResult reset(ExpectedAppearance expectedAppearance) {
            resets.incrementAndGet();
            return ApplyResult.UPDATED;
        }

        @Override
        public void invalidate(ExpectedAppearance expectedAppearance) {
            invalidations.incrementAndGet();
            lastInvalidated = expectedAppearance;
        }
    }
}
