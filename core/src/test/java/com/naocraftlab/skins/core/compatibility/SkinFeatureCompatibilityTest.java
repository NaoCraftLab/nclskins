package com.naocraftlab.skins.core.compatibility;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.math.BigInteger;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinFeatureCompatibilityTest {
    private final SkinFeatureAnalyzer analyzer = new SkinFeatureAnalyzer();
    private final SkinCompatibilityEvaluator evaluator = new SkinCompatibilityEvaluator();

    @Test
    void recognizesExactEarsV0AndV1Markers() {
        BufferedImage v0 = blank();
        int[] colors = {
                0x3f23d8, 0xd82350, 0x3f23d8, 0x3f23d8,
                0xd82350, 0x3f23d8, 0x3f23d8, 0x3f23d8,
                0xd82350, 0xd82350, 0xd82350
        };
        for (int index = 0; index < colors.length; index++) {
            v0.setRGB(index % 4, 32 + index / 4, 0xff000000 | colors[index]);
        }
        BufferedImage v1 = blank();
        v1.setRGB(0, 32, 0xffea2501);
        v1.setRGB(1, 32, 0xff000000);

        assertEquals(List.of(SkinFeature.EARS), analyzer.analyze(v0).supportedFeatures());
        assertEquals(List.of(SkinFeature.EARS), analyzer.analyze(v1).supportedFeatures());
    }

    @Test
    void validatesOptionalBoundedAlfalfaSequenceOnlyAfterAnEarsMarker() {
        BufferedImage valid = earsV1();
        encodeAlfalfa(valid, new byte[] {
                (byte) 0xea, 0x1f, (byte) 0xa1, (byte) 0xfa, 1, 0
        });
        BufferedImage malformed = earsV1();
        encodeAlfalfa(malformed, new byte[] {
                (byte) 0xea, 0x1f, (byte) 0xa1, (byte) 0xfa, 2, 0
        });

        assertEquals(List.of(SkinFeature.EARS), analyzer.analyze(valid).supportedFeatures());
        assertEquals(
                List.of(SkinConflictReason.MALFORMED_EARS_DATA),
                analyzer.analyze(malformed).potentialConflicts());
    }

    @Test
    void recognizesCompleteExpressiveLayoutAndFlagsIncompleteSampledPixels() {
        BufferedImage complete = blank();
        for (int y = 2; y < 5; y++) {
            for (int x = 24; x < 28; x++) {
                complete.setRGB(x, y, 0xff123456);
            }
        }
        BufferedImage partial = blank();
        partial.setRGB(24, 2, 0xff123456);

        assertEquals(
                List.of(SkinFeature.FRESH_MOVES, SkinFeature.JUST_EXPRESSIONS),
                analyzer.analyze(complete).supportedFeatures());
        assertEquals(
                List.of(SkinConflictReason.MALFORMED_EXPRESSIVE_DATA),
                analyzer.analyze(partial).potentialConflicts());
    }

    @Test
    void sampledAuthorPixelsArePotentialExpressiveConflict() {
        BufferedImage skin = blank();
        skin.setRGB(4, 6, 0xff123456);
        skin.setRGB(5, 6, 0xff123456);

        assertEquals(
                List.of(SkinConflictReason.MALFORMED_EXPRESSIVE_DATA),
                analyzer.analyze(skin).potentialConflicts());
    }

    @Test
    void transparentRgbAndPixelsOutsideSampledUnionRemainOrdinary() {
        BufferedImage skin = blank();
        skin.setRGB(4, 0, 0x00123456);
        skin.setRGB(3, 0, 0xff123456);
        skin.setRGB(8, 0, 0xff123456);

        assertEquals(SkinFeatureEvidence.ORDINARY, analyzer.analyze(skin));
    }

    @Test
    void supportsFreshMovesOnePixelExampleLayoutAndCurrentUpperRightLayout() {
        BufferedImage example = blank();
        fill(example, 4, 4, 4, 2);
        BufferedImage current = blank();
        fill(current, 60, 6, 4, 2);

        List<SkinFeature> expected =
                List.of(SkinFeature.FRESH_MOVES, SkinFeature.JUST_EXPRESSIONS);
        assertEquals(expected, analyzer.analyze(example).supportedFeatures());
        assertEquals(expected, analyzer.analyze(current).supportedFeatures());
    }

    @Test
    void recognizesEverySupportedVersionedExpressiveLayoutBoundary() {
        int[][] layouts = {
                {4, 0, 4, 2}, {4, 2, 4, 2}, {4, 4, 4, 2}, {4, 6, 4, 2},
                {24, 2, 4, 3}, {24, 5, 4, 3}, {28, 2, 4, 3}, {28, 5, 4, 3},
                {60, 0, 4, 2}, {60, 2, 4, 2}, {60, 4, 4, 2}, {60, 6, 4, 2}
        };
        List<SkinFeature> expected =
                List.of(SkinFeature.FRESH_MOVES, SkinFeature.JUST_EXPRESSIONS);

        for (int[] layout : layouts) {
            BufferedImage skin = blank();
            fill(skin, layout[0], layout[1], layout[2], layout[3]);
            assertEquals(expected, analyzer.analyze(skin).supportedFeatures(),
                    "layout at " + layout[0] + "," + layout[1]);

            skin.setRGB(
                    layout[0] + layout[2] - 1,
                    layout[1] + layout[3] - 1,
                    0);
            assertEquals(
                    List.of(SkinConflictReason.MALFORMED_EXPRESSIVE_DATA),
                    analyzer.analyze(skin).potentialConflicts(),
                    "partial layout at " + layout[0] + "," + layout[1]);
        }
    }

    @Test
    void evaluatorIsFailOpenForInactiveAndUnknownConsumers() {
        SkinFeatureEvidence evidence = new SkinFeatureEvidence(
                List.of(SkinFeature.EARS),
                List.of(SkinConflictReason.MALFORMED_EXPRESSIVE_DATA));

        assertEquals(
                SkinCompatibilityStatus.EXTENDED,
                evaluator.evaluate(evidence, SkinExtensionEnvironment.unknown(1)).status());
        assertEquals(
                SkinCompatibilityStatus.EXTENDED,
                evaluator.evaluate(evidence, environment(1, SkinConsumerState.INACTIVE)).status());
        assertEquals(
                SkinCompatibilityStatus.INCOMPATIBLE,
                evaluator.evaluate(evidence, environment(2, SkinConsumerState.ACTIVE)).status());
    }

    @Test
    void activeConsumerDoesNotEraseIntrinsicFeatureEvidence() {
        SkinFeatureEvidence evidence = new SkinFeatureEvidence(
                List.of(SkinFeature.EARS),
                List.of(SkinConflictReason.MALFORMED_EXPRESSIVE_DATA));

        SkinCompatibility compatibility = evaluator.evaluate(
                evidence, environment(1, SkinConsumerState.ACTIVE));

        assertEquals(List.of(SkinFeature.EARS), compatibility.supportedFeatures());
        assertEquals(
                List.of(SkinConflictReason.MALFORMED_EXPRESSIVE_DATA),
                compatibility.activeConflicts());
    }

    @Test
    void activeExpressivePackAcceptsSkinWithoutEyeLayout() {
        SkinCompatibility compatibility = evaluator.evaluate(
                SkinFeatureEvidence.ORDINARY,
                environment(1, SkinConsumerState.ACTIVE));

        assertEquals(SkinCompatibilityStatus.ORDINARY, compatibility.status());
        assertTrue(compatibility.activeConflicts().isEmpty());
    }

    @Test
    void earsPixelsAcrossSeveralExclusiveLayoutsAreNotExpressiveSupport() {
        BufferedImage ears = earsV1();
        fill(ears, 4, 0, 4, 8);

        SkinFeatureEvidence evidence = analyzer.analyze(ears);

        assertEquals(List.of(SkinFeature.EARS), evidence.supportedFeatures());
        assertEquals(
                List.of(SkinConflictReason.MALFORMED_EXPRESSIVE_DATA),
                evidence.potentialConflicts());
        assertEquals(
                SkinCompatibilityStatus.INCOMPATIBLE,
                evaluator.evaluate(evidence, environment(1, SkinConsumerState.ACTIVE)).status());
        assertEquals(
                SkinCompatibilityStatus.EXTENDED,
                evaluator.evaluate(evidence, environment(2, SkinConsumerState.INACTIVE)).status());
    }

    @Test
    void inactiveAndUnknownExpressivePackRemainFailOpenForOrdinarySkin() {
        assertEquals(
                SkinCompatibilityStatus.ORDINARY,
                evaluator.evaluate(
                        SkinFeatureEvidence.ORDINARY,
                        environment(1, SkinConsumerState.INACTIVE)).status());
        assertEquals(
                SkinCompatibilityStatus.ORDINARY,
                evaluator.evaluate(
                        SkinFeatureEvidence.ORDINARY,
                        environment(2, SkinConsumerState.UNKNOWN)).status());
    }

    @Test
    void effectiveExpressivePackWithoutModelRuntimeHasDistinctConflict() {
        BufferedImage expressive = blank();
        fill(expressive, 4, 2, 4, 2);

        SkinCompatibility compatibility = evaluator.evaluate(
                analyzer.analyze(expressive),
                environment(1, SkinConsumerState.MISSING_PREREQUISITE));

        assertEquals(SkinCompatibilityStatus.INCOMPATIBLE, compatibility.status());
        assertEquals(
                List.of(SkinConflictReason.MISSING_EXPRESSIVE_RUNTIME),
                compatibility.activeConflicts());
        assertEquals(
                List.of(SkinFeature.FRESH_MOVES, SkinFeature.JUST_EXPRESSIONS),
                compatibility.supportedFeatures());
    }

    private static SkinExtensionEnvironment environment(long generation, SkinConsumerState expressive) {
        Map<SkinConsumer, SkinConsumerState> states = new EnumMap<>(SkinConsumer.class);
        states.put(SkinConsumer.FRESH_MOVES, expressive);
        states.put(SkinConsumer.JUST_EXPRESSIONS, expressive);
        return new SkinExtensionEnvironment(generation, states);
    }

    private static BufferedImage blank() {
        return new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    }

    private static BufferedImage earsV1() {
        BufferedImage image = blank();
        image.setRGB(0, 32, 0xffea2501);
        image.setRGB(1, 32, 0xff000000);
        for (int[] region : alfalfaRegions()) {
            for (int x = region[0]; x < region[2]; x++) {
                for (int y = region[1]; y < region[3]; y++) {
                    image.setRGB(x, y, 0xff000000);
                }
            }
        }
        return image;
    }

    private static void fill(
            BufferedImage image, int x, int y, int width, int height) {
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                image.setRGB(column, row, 0xff123456);
            }
        }
    }

    private static void encodeAlfalfa(BufferedImage image, byte[] bytes) {
        BigInteger encoded = new BigInteger(1, bytes);
        int written = 0;
        for (int[] region : alfalfaRegions()) {
            for (int x = region[0]; x < region[2]; x++) {
                for (int y = region[1]; y < region[3]; y++) {
                    int value = encoded.shiftRight(written * 7)
                            .and(BigInteger.valueOf(0x7f)).intValue();
                    int alpha = (0x7f - value) | 0x80;
                    image.setRGB(x, y, alpha << 24);
                    written++;
                }
            }
        }
    }

    private static int[][] alfalfaRegions() {
        return new int[][] {
                {8, 0, 24, 8}, {0, 8, 8, 16}, {16, 8, 32, 16},
                {4, 16, 12, 20}, {20, 16, 36, 20}, {44, 16, 52, 20},
                {0, 20, 56, 32}, {20, 48, 28, 52}, {36, 48, 44, 52},
                {16, 52, 48, 64}
        };
    }
}
