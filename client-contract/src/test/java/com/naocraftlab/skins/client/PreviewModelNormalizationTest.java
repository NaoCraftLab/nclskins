package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreviewModelNormalizationTest {
    @Test
    void selectedBakedRepresentationIgnoresMatchingFullFit() {
        var baked = PreviewModelNormalization.baked(0.0, 0.5, 0.0);
        var full = PreviewModelNormalization.full(2.0, 4.0, 3.0, 5.0, -2.0, 1.0, 1.0);
        var selected = PreviewModelNormalization.select(PreviewModelNormalization.Representation.BAKED, baked, full);
        assertEquals(baked, selected);
    }

    @Test
    void rejectsInvalidMeasurementsAndCenters() {
        for (double invalid : new double[]{0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> PreviewModelNormalization.full(invalid, 2.0, 0.0, 0.0, 0.0, 1.0, 1.0));
            assertThrows(IllegalArgumentException.class, () -> PreviewModelNormalization.full(2.0, invalid, 0.0, 0.0, 0.0, 1.0, 1.0));
            assertThrows(IllegalArgumentException.class, () -> PreviewModelNormalization.full(2.0, 2.0, invalid == 0.0 || invalid == -1.0 ? Double.NaN : invalid, 0.0, 0.0, 1.0, 1.0));
        }
    }

    @Test
    void fullSelectionRequiresAvailableFullFit() {
        var baked = PreviewModelNormalization.baked(0.0, 0.5, 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> PreviewModelNormalization.select(PreviewModelNormalization.Representation.FULL, baked, null));
    }

    @Test
    void fullRetainsNeutralDimensionsWithoutWidthLimitingFit() {
        var fit = PreviewModelNormalization.full(2.0, 4.0, 3.0, 5.0, -2.0, 50.0, 3.0);
        assertEquals(0.5, fit.normalization(), 1e-12);
        assertEquals(4.0, fit.bodyHeight(), 1e-12);
        assertEquals(50.0, fit.halfWidth(), 1e-12);
        assertEquals(3.0, fit.halfDepth(), 1e-12);
    }

    @Test
    void ordinaryAndSmallBodiesAreNeverUpscaled() {
        assertEquals(1.0, PreviewModelNormalization.full(2.0, 2.0, 0, 0.5, 0, 1, 1).normalization());
        assertEquals(1.0, PreviewModelNormalization.full(2.0, 1.0, 0, 0.5, 0, 1, 1).normalization());
        assertEquals(0.5, PreviewModelNormalization.full(1.9189453125, 3.837890625, 0, 0, 0, 1, 1).normalization());
    }

    @Test
    void authoredScaleAlreadyInMeasurementsIsNotAppliedTwice() {
        var fit = PreviewModelNormalization.full(2.0, 3.0 * 2.0, 0, 0.5 * 2.0, 0, 1.0 * 2.0, 1.0 * 2.0);
        assertEquals(1.0 / 3.0, fit.normalization(), 1e-12);
        assertEquals(1.0, fit.centerY(), 1e-12);
        assertEquals(2.0, fit.halfWidth(), 1e-12);
    }

    @Test
    void layoutAndZoomReuseSameFitWithoutMeasuring() {
        var fit = PreviewModelNormalization.full(2, 4, 3, 5, -2, 50, 3);
        for (int height : new int[]{80, 200}) {
            var narrow = CenteredPlayerPreviewGeometry.fit(10, 20, 20, height, 1);
            var wide = CenteredPlayerPreviewGeometry.fit(10, 20, 400, height, 1);
            assertEquals(narrow.scale(), wide.scale());
            for (float zoom : new float[]{0.68f, 1.0f, 2.0f}) {
                double renderedScale = CenteredPlayerPreviewGeometry.fittedScale(height, zoom) * fit.normalization();
                assertEquals(narrow.scale() * zoom * 0.5, renderedScale, 1e-5);
            }
        }
        assertEquals(0.5, fit.normalization());
    }

    @Test
    void fullAndBakedAreSelectedOnlyForTheirRepresentation() {
        var baked = PreviewModelNormalization.baked(0, 0.5, 0);
        var full = PreviewModelNormalization.full(2, 4, 3, 5, -2, 50, 3);
        assertEquals(full, PreviewModelNormalization.select(PreviewModelNormalization.Representation.FULL, baked, full));
        assertEquals(baked, PreviewModelNormalization.select(PreviewModelNormalization.Representation.BAKED, baked, null));
        assertThrows(IllegalArgumentException.class, () -> PreviewModelNormalization.select(PreviewModelNormalization.Representation.BAKED, full, full));
        assertThrows(IllegalArgumentException.class, () -> PreviewModelNormalization.select(PreviewModelNormalization.Representation.FULL, baked, baked));
    }

    @Test
    void rejectsInvalidDimensionsAndUnrepresentableScale() {
        for (double invalid : new double[]{-1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> PreviewModelNormalization.full(2, 4, 0, 0, 0, invalid, 1));
            assertThrows(IllegalArgumentException.class, () -> PreviewModelNormalization.full(2, 4, 0, 0, 0, 1, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> PreviewModelNormalization.full(Double.MIN_VALUE, Double.MAX_VALUE, 0, 0, 0, 1, 1));
    }

    @Test
    void neutralCenterOffsetPlacesTranslatedBodyAtRotationOrigin() {
        var fit = PreviewModelNormalization.full(2, 4, 3, 5, -2, 1, 1);
        assertEquals(-3.0, fit.offsetX());
        assertEquals(-5.0, fit.offsetY());
        assertEquals(2.0, fit.offsetZ());
    }

    @Test
    void tallTranslatedBodyUsesReferenceHeightAndNeutralCenter() {
        var fit = PreviewModelNormalization.full(2.0, 4.0, 3.0, 5.0, -2.0, 1.0, 1.0);
        assertEquals(0.5, fit.normalization(), 1e-12);
        assertEquals(3.0, fit.centerX(), 1e-12);
        assertEquals(5.0, fit.centerY(), 1e-12);
        assertEquals(-2.0, fit.centerZ(), 1e-12);
    }
}
