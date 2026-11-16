package com.naocraftlab.skins.runtime.infrastructure;

import com.naocraftlab.skins.client.PreviewModelNormalization;
import com.naocraftlab.skins.core.model.FullPreviewModelFit;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.storage.PreviewModelFitResult;
import com.naocraftlab.skins.runtime.PreviewModelFitStore;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class PreviewModelFitStorageAdapter implements PreviewModelFitStore {
    private final NclSkinsStorage storage;

    public PreviewModelFitStorageAdapter(NclSkinsStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override public Result load(Key key) {
        return map(storage.loadPreviewModelFit(key.accountId(), key.contentSha256(), key.variant()));
    }

    @Override public Result create(Key key, Value value, BooleanSupplier current) {
        var fit = value.fit();
        var stored = new FullPreviewModelFit(value.referenceHeight(), fit.normalization(), fit.bodyHeight(),
                fit.centerX(), fit.centerY(), fit.centerZ(), fit.halfWidth(), fit.halfDepth(),
                value.referenceCenterX(), value.referenceCenterY(), value.referenceCenterZ());
        return map(storage.createPreviewModelFit(key.accountId(), key.contentSha256(), key.variant(), stored, current));
    }

    private static Result map(PreviewModelFitResult result) {
        return switch (result.status()) {
            case ABSENT -> new Result(Status.ABSENT, null);
            case UNAVAILABLE -> Result.unavailable();
            case PRESENT -> {
                var fit = result.fit();
                yield new Result(Status.PRESENT, new Value(fit.referenceHeight(), fit.referenceCenterX(), fit.referenceCenterY(), fit.referenceCenterZ(),
                        PreviewModelNormalization.full(fit.referenceHeight(), fit.bodyHeight(),
                                fit.centerX(), fit.centerY(), fit.centerZ(), fit.halfWidth(), fit.halfDepth())));
            }
        };
    }
}
