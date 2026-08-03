package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.ExpectedAppearance;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.SignedProfileResolver;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

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
                new AppearanceRefreshCoordinator<>(client, resolver, sink);
        List<AppearanceRefreshCoordinator.Result> published = new ArrayList<>();

        AppliedAppearance first = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID, "1".repeat(64), SkinVariant.CLASSIC, Optional.empty());
        AppliedAppearance second = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID, "2".repeat(64), SkinVariant.SLIM, Optional.empty());
        CompletableFuture<AppearanceRefreshCoordinator.Result> old =
                coordinator.afterReconnect(first, published::add);
        CompletableFuture<AppearanceRefreshCoordinator.Result> current =
                coordinator.afterReconnect(second, published::add);

        resolutions.get(0).complete(Optional.of(resolved(first, "old")));
        resolutions.get(1).complete(Optional.of(resolved(second, "new")));

        assertEquals(AppearanceRefreshCoordinator.Result.SUPERSEDED, old.join());
        assertEquals(AppearanceRefreshCoordinator.Result.UPDATED, current.join());
        assertEquals(List.of(
                AppearanceRefreshCoordinator.Result.SUPERSEDED,
                AppearanceRefreshCoordinator.Result.UPDATED), published);
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
                sink);
        AppliedAppearance appearance = AppliedAppearance.accountDefault(
                TestFixtures.ACCOUNT_ID, Optional.empty());

        assertEquals(
                AppearanceRefreshCoordinator.Result.UPDATED,
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
                sink);

        assertEquals(
                AppearanceRefreshCoordinator.Result.DEFERRED,
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
                sink);
        AppliedAppearance appearance = AppliedAppearance.localSkin(
                TestFixtures.ACCOUNT_ID, "4".repeat(64), SkinVariant.SLIM, Optional.empty());

        assertEquals(
                AppearanceRefreshCoordinator.Result.UPDATED,
                coordinator.afterReconnect(appearance, ignored -> {}).join());
        assertEquals(1, sink.reattachments.get());
        assertEquals(0, resolutions.get());
        assertEquals(0, sink.installs.get());
        assertEquals(0, sink.invalidations.get());
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
