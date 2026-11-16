package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class NativeFitPersistenceFixture {
    public record Probe(CompletableFuture<PreviewAssets.NumericFitResult> result, AtomicInteger loads, AtomicInteger creates) {}

    public static Probe begin(PreviewRenderer.ModelFitPreparation preparation, byte[] content) {
        var creates = new AtomicInteger();
        var loads = new AtomicInteger();
        var store = new PreviewModelFitStore() {
            @Override public Result load(Key key) {
                loads.incrementAndGet();
                return new Result(Status.ABSENT, null);
            }
            @Override public Result create(Key key, Value value, BooleanSupplier current) {
                creates.incrementAndGet();
                return new Result(Status.PRESENT, value);
            }
        };
        var client = new ClientExecutor() {
            @Override public boolean isClientThread() { return true; }
            @Override public void execute(Runnable action) { action.run(); }
        };
        var loader = new PreviewAssetLoader(client, Runnable::run, DiagnosticSinks.discarding(), store);
        var result = loader.prepareModelFit(new UUID(0, 1), SkinVariant.CLASSIC, content, preparation::current,
                () -> preparation.measure().thenApply(value -> value == null ? null
                        : new PreviewModelFitStore.Value(value.referenceHeight(), value.referenceCenterX(), value.referenceCenterY(), value.referenceCenterZ(), value.fit())));
        return new Probe(result, loads, creates);
    }
}
