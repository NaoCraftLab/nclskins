package com.naocraftlab.skins.client;

public final class PreviewModelNormalization {
    private PreviewModelNormalization() {
    }

    public static Fit full(double referenceHeight, double bodyHeight,
            double centerX, double centerY, double centerZ, double halfWidth, double halfDepth) {
        if (!Double.isFinite(referenceHeight) || referenceHeight <= 0.0
                || !Double.isFinite(bodyHeight) || bodyHeight <= 0.0) {
            throw new IllegalArgumentException("Body and reference heights must be finite and positive");
        }
        return new Fit(Representation.FULL, Math.min(1.0, referenceHeight / bodyHeight),
                bodyHeight, centerX, centerY, centerZ, halfWidth, halfDepth);
    }

    public enum Representation { BAKED, FULL }

    public static Fit select(Representation representation, Fit baked, Fit full) {
        if (representation == null) {
            throw new IllegalArgumentException("Representation is required");
        }
        Fit selected = representation == Representation.BAKED ? baked : full;
        if (selected == null || selected.representation() != representation) {
            throw new IllegalArgumentException("Selected representation requires its own fit");
        }
        return selected;
    }

    public static Fit baked(double centerX, double centerY, double centerZ) {
        return new Fit(Representation.BAKED, 1.0, 0.0, centerX, centerY, centerZ, 0.0, 0.0);
    }

    public record Fit(Representation representation, double normalization, double bodyHeight,
            double centerX, double centerY, double centerZ, double halfWidth, double halfDepth) {
        public double offsetX() { return -centerX; }
        public double offsetY() { return -centerY; }
        public double offsetZ() { return -centerZ; }

        public Fit {
            if (representation == null || !Double.isFinite(normalization)
                    || normalization <= 0.0 || normalization > 1.0
                    || !Double.isFinite(bodyHeight) || bodyHeight < 0.0
                    || !Double.isFinite(centerX) || !Double.isFinite(centerY) || !Double.isFinite(centerZ)
                    || !Double.isFinite(halfWidth) || halfWidth < 0.0
                    || !Double.isFinite(halfDepth) || halfDepth < 0.0
                    || (representation == Representation.FULL && bodyHeight == 0.0)
                    || (representation == Representation.BAKED
                            && (normalization != 1.0 || bodyHeight != 0.0 || halfWidth != 0.0 || halfDepth != 0.0))) {
                throw new IllegalArgumentException("Invalid numeric preview fit");
            }
        }
    }
}
