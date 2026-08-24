package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.ExpectedAppearance;
import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.SignedProfileResolver;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.model.MutationResult;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.service.AppliedAppearance;
import com.naocraftlab.skins.core.service.PresetApplicationOutcome;
import com.naocraftlab.skins.diagnostics.DiagnosticDetails;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;


public final class AppearanceRefreshCoordinator<P> implements AutoCloseable {
    private final ClientExecutor clientExecutor;
    private final SignedProfileResolver<P> resolver;
    private final PlayerAppearanceSink<P> sink;
    private final DiagnosticSink diagnostics;
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean closed;

    public AppearanceRefreshCoordinator(
            ClientExecutor clientExecutor,
            SignedProfileResolver<P> resolver,
            PlayerAppearanceSink<P> sink,
            DiagnosticSink diagnostics) {
        this.clientExecutor = Objects.requireNonNull(clientExecutor, "clientExecutor");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public CompletableFuture<Result> afterMutation(
            PresetApplicationOutcome outcome, Consumer<Result> publisher) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(publisher, "publisher");
        if ((outcome.result() != MutationResult.APPLIED && outcome.result() != MutationResult.PARTIAL)
                || outcome.optionalAppliedAppearance().isEmpty()) {
            return publishImmediately(Result.NOT_APPLICABLE, publisher);
        }
        return refresh(outcome.optionalAppliedAppearance().orElseThrow(), publisher);
    }

    public CompletableFuture<Result> afterReconnect(
            AppliedAppearance acknowledgedAppearance, Consumer<Result> publisher) {
        return refresh(
                Objects.requireNonNull(acknowledgedAppearance, "acknowledgedAppearance"),
                Objects.requireNonNull(publisher, "publisher"));
    }

    public long generation() {
        return generation.get();
    }

    @Override
    public void close() {
        closed = true;
        generation.incrementAndGet();
    }

    private CompletableFuture<Result> refresh(
            AppliedAppearance appearance, Consumer<Result> publisher) {
        if (closed) {
            return CompletableFuture.completedFuture(Result.SUPERSEDED);
        }
        long ticket = generation.incrementAndGet();
        CompletableFuture<Result> publication = new CompletableFuture<>();
        ExpectedAppearance expected = expectedAppearance(appearance);
        clientExecutor.execute(() -> startRefresh(ticket, expected, publisher, publication));
        return publication;
    }

    private void startRefresh(
            long ticket,
            ExpectedAppearance expected,
            Consumer<Result> publisher,
            CompletableFuture<Result> publication) {
        if (closed || ticket != generation.get()) {
            publish(Result.SUPERSEDED, publisher, publication);
            return;
        }
        if (expected.vanillaReset()) {
            try {
                PlayerAppearanceSink.ApplyResult reset = sink.reset(expected);
                publish(
                        reset == PlayerAppearanceSink.ApplyResult.UPDATED
                                ? Result.UPDATED
                                : Result.DEFERRED,
                        publisher,
                        publication);
            } catch (RuntimeException unavailablePlayerState) {
                diagnose(unavailablePlayerState);
                publish(Result.DEFERRED, publisher, publication);
            }
            return;
        }
        try {
            if (sink.reattach(expected) == PlayerAppearanceSink.ApplyResult.UPDATED) {
                publish(Result.UPDATED, publisher, publication);
                return;
            }
        } catch (RuntimeException unavailableOverride) {
            diagnose(unavailableOverride);
        }

        final CompletableFuture<java.util.Optional<SignedProfileResolver.ResolvedProfile<P>>> resolution;
        try {
            resolution = Objects.requireNonNull(resolver.resolve(expected), "resolver future");
        } catch (RuntimeException failure) {
            diagnose(failure);
            finishRefresh(ticket, expected, publisher, publication, null, failure);
            return;
        }
        resolution.whenComplete((resolved, failure) -> clientExecutor.execute(() ->
                finishRefresh(ticket, expected, publisher, publication, resolved, failure)));
    }

    private void finishRefresh(
            long ticket,
            ExpectedAppearance expected,
            Consumer<Result> publisher,
            CompletableFuture<Result> publication,
            java.util.Optional<SignedProfileResolver.ResolvedProfile<P>> resolved,
            Throwable failure) {
        Result result;
        if (closed || ticket != generation.get()) {
            result = Result.SUPERSEDED;
        } else if (failure != null || resolved == null || resolved.isEmpty()) {
            if (failure != null) {
                diagnose(failure);
            }
            result = invalidate(expected);
        } else {
            try {
                PlayerAppearanceSink.ApplyResult applied = sink.apply(resolved.orElseThrow());
                result = applied == PlayerAppearanceSink.ApplyResult.UPDATED
                        ? Result.UPDATED
                        : invalidate(expected);
            } catch (RuntimeException sinkFailure) {
                diagnose(sinkFailure);
                result = invalidate(expected);
            }
        }
        publish(result, publisher, publication);
    }

    private Result invalidate(ExpectedAppearance expected) {
        try {
            sink.invalidate(expected);
        } catch (RuntimeException unavailableClientState) {
            diagnose(unavailableClientState);
        }
        return Result.DEFERRED;
    }

    private CompletableFuture<Result> publishImmediately(Result result, Consumer<Result> publisher) {
        CompletableFuture<Result> publication = new CompletableFuture<>();
        clientExecutor.execute(() -> publish(result, publisher, publication));
        return publication;
    }

    private static void publish(
            Result result, Consumer<Result> publisher, CompletableFuture<Result> publication) {
        try {
            publisher.accept(result);
            publication.complete(result);
        } catch (RuntimeException publishFailure) {
            publication.completeExceptionally(publishFailure);
        }
    }

    private static ExpectedAppearance expectedAppearance(AppliedAppearance appearance) {
        return new ExpectedAppearance(
                appearance.profileId(),
                appearance.skinTexture(),
                appearance.localSkinSha256(),
                appearance.skinVariant().map(AppearanceRefreshCoordinator::skinModel),
                appearance.capeTexture(),
                appearance.localCapeCacheKey());
    }

    private static SkinModel skinModel(SkinVariant variant) {
        return switch (variant) {
            case CLASSIC -> SkinModel.CLASSIC;
            case SLIM -> SkinModel.SLIM;
        };
    }

    private void diagnose(Throwable failure) {
        diagnostics.report(
                DiagnosticEvent.CLIENT_APPEARANCE_REFRESH_FAILED,
                () -> DiagnosticDetails.failure(failure));
    }

    public enum Result {
        UPDATED,
        DEFERRED,
        SUPERSEDED,
        NOT_APPLICABLE
    }
}
