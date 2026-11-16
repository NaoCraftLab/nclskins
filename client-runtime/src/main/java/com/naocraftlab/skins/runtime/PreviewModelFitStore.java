package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewModelNormalization;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public interface PreviewModelFitStore {
    Result load(Key key);
    Result create(Key key, Value value, BooleanSupplier current);

    record Key(UUID accountId, String contentSha256, SkinVariant variant) {
        public Key {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(variant, "variant");
            if (contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid content identity");
            }
        }
    }

    record Value(double referenceHeight, double referenceCenterX, double referenceCenterY,
            double referenceCenterZ, PreviewModelNormalization.Fit fit) {
        public Value {
            Objects.requireNonNull(fit, "fit");
            if (!Double.isFinite(referenceHeight) || referenceHeight <= 0
                    || !Double.isFinite(referenceCenterX) || !Double.isFinite(referenceCenterY) || !Double.isFinite(referenceCenterZ)
                    || fit.representation() != PreviewModelNormalization.Representation.FULL
                    || fit.normalization() != Math.min(1.0, referenceHeight / fit.bodyHeight())) {
                throw new IllegalArgumentException("Invalid full numeric fit");
            }
        }
    }

    enum Status { PRESENT, ABSENT, UNAVAILABLE }

    record Result(Status status, Value value) {
        public Result {
            if (status == null || (status == Status.PRESENT) != (value != null)) {
                throw new IllegalArgumentException("Invalid numeric result");
            }
        }
        public static Result unavailable() { return new Result(Status.UNAVAILABLE, null); }
    }
}
