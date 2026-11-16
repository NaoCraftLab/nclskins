package com.naocraftlab.skins.core.model;

public record FullPreviewModelFit(double referenceHeight, double normalization, double bodyHeight,
        double centerX, double centerY, double centerZ, double halfWidth, double halfDepth,
        double referenceCenterX, double referenceCenterY, double referenceCenterZ) {
    public FullPreviewModelFit {
        if (!Double.isFinite(referenceHeight) || referenceHeight <= 0
                || !Double.isFinite(bodyHeight) || bodyHeight <= 0
                || !Double.isFinite(normalization) || normalization <= 0 || normalization > 1
                || normalization != Math.min(1.0, referenceHeight / bodyHeight)
                || !Double.isFinite(centerX) || !Double.isFinite(centerY) || !Double.isFinite(centerZ)
                || !Double.isFinite(referenceCenterX) || !Double.isFinite(referenceCenterY) || !Double.isFinite(referenceCenterZ)
                || !Double.isFinite(halfWidth) || halfWidth < 0
                || !Double.isFinite(halfDepth) || halfDepth < 0) {
            throw new IllegalArgumentException("Invalid full preview model fit");
        }
    }
}
