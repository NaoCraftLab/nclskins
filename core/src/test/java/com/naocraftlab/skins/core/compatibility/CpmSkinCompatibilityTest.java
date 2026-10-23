package com.naocraftlab.skins.core.compatibility;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpmSkinCompatibilityTest {
    private final SkinFeatureAnalyzer analyzer = new SkinFeatureAnalyzer();
    private final SkinCompatibilityEvaluator evaluator = new SkinCompatibilityEvaluator();

    @Test
    void carrierSupportDoesNotRequireRuntimeAndMalformedRequiresActiveCpm() throws Exception {
        var valid = analyzer.analyze(image("classic-a"));
        assertEquals(List.of(SkinFeature.CUSTOMIZABLE_PLAYER_MODELS), valid.supportedFeatures());
        assertEquals(SkinCompatibilityStatus.EXTENDED, evaluator.evaluate(valid, SkinExtensionEnvironment.unknown(1)).status());
        var malformed = analyzer.analyze(image("classic-bad-checksum"));
        assertEquals(List.of(SkinConflictReason.MALFORMED_CPM_DATA), malformed.potentialConflicts());
        for (var state : SkinConsumerState.values()) {
            var result = evaluator.evaluate(malformed, new SkinExtensionEnvironment(1,
                    Map.of(SkinConsumer.CUSTOMIZABLE_PLAYER_MODELS, state)));
            assertEquals(state == SkinConsumerState.ACTIVE, result.status() == SkinCompatibilityStatus.INCOMPATIBLE);
        }
    }

    @Test
    void specificExpressiveReasonsNameOnlyActivePacksWithoutGenericDuplicate() throws Exception {
        for (boolean multiple : new boolean[]{false, true}) {
            var skin = image("classic-a");
            if (multiple) {
                fill(skin, 24, 2, 4, 3);
                fill(skin, 24, 5, 4, 3);
            } else skin.setRGB(24, 2, 0xff000000);
            var evidence = analyzer.analyze(skin);
            assertEquals(List.of(SkinFeature.CUSTOMIZABLE_PLAYER_MODELS), evidence.supportedFeatures());
            assertEquals(List.of(SkinConflictReason.CPM_FRESH_MOVES_OVERLAP,
                    SkinConflictReason.CPM_JUST_EXPRESSIONS_OVERLAP), evidence.potentialConflicts());
            for (var state : SkinConsumerState.values()) {
                var result = evaluator.evaluate(evidence, new SkinExtensionEnvironment(1,
                        Map.of(SkinConsumer.JUST_EXPRESSIONS, state)));
                assertEquals(state == SkinConsumerState.ACTIVE
                        ? List.of(SkinConflictReason.CPM_JUST_EXPRESSIONS_OVERLAP)
                        : state == SkinConsumerState.MISSING_PREREQUISITE
                                ? List.of(SkinConflictReason.MISSING_EXPRESSIVE_RUNTIME) : List.of(), result.activeConflicts());
            }
        }
    }

    @Test
    void sneakyMarkerPreservesIndependentCpmExpressiveConflicts() throws Exception {
        for (String fixture : List.of("classic-a", "slim-a")) {
            for (boolean multiple : new boolean[]{false, true}) {
                var skin = image(fixture);
                if (multiple) {
                    fill(skin, 24, 2, 4, 3);
                    fill(skin, 24, 5, 4, 3);
                } else skin.setRGB(24, 2, 0xff000000);
                markSneaky(skin);
                var evidence = analyzer.analyze(skin);
                assertEquals(List.of(SkinFeature.CUSTOMIZABLE_PLAYER_MODELS,
                        SkinFeature.SNEAKY_CAPES), evidence.supportedFeatures());
                assertEquals(List.of(SkinConflictReason.SNEAKY_FRESH_MOVES_OVERLAP,
                        SkinConflictReason.SNEAKY_JUST_EXPRESSIONS_OVERLAP,
                        SkinConflictReason.CPM_FRESH_MOVES_OVERLAP,
                        SkinConflictReason.CPM_JUST_EXPRESSIONS_OVERLAP), evidence.potentialConflicts());
                assertEquals(evidence.potentialConflicts(), evaluator.evaluate(evidence,
                        new SkinExtensionEnvironment(1, Map.of(
                                SkinConsumer.FRESH_MOVES, SkinConsumerState.ACTIVE,
                                SkinConsumer.JUST_EXPRESSIONS, SkinConsumerState.ACTIVE))).activeConflicts());
                for (var consumer : List.of(SkinConsumer.FRESH_MOVES, SkinConsumer.JUST_EXPRESSIONS)) {
                    for (var state : SkinConsumerState.values()) {
                        var result = evaluator.evaluate(evidence, new SkinExtensionEnvironment(1,
                                Map.of(consumer, state, SkinConsumer.CUSTOMIZABLE_PLAYER_MODELS,
                                        SkinConsumerState.INACTIVE)));
                        var expected = state == SkinConsumerState.ACTIVE
                                ? consumer == SkinConsumer.FRESH_MOVES
                                        ? List.of(SkinConflictReason.SNEAKY_FRESH_MOVES_OVERLAP,
                                                SkinConflictReason.CPM_FRESH_MOVES_OVERLAP)
                                        : List.of(SkinConflictReason.SNEAKY_JUST_EXPRESSIONS_OVERLAP,
                                                SkinConflictReason.CPM_JUST_EXPRESSIONS_OVERLAP)
                                : state == SkinConsumerState.MISSING_PREREQUISITE
                                        ? List.of(SkinConflictReason.MISSING_EXPRESSIVE_RUNTIME) : List.of();
                        assertEquals(expected, result.activeConflicts());
                        assertEquals(!expected.isEmpty(), result.status() == SkinCompatibilityStatus.INCOMPATIBLE);
                    }
                }
            }
        }
    }

    @Test
    void sneakyPriorityDoesNotInventExpressiveSupportOrCpmConflictForEmptyOrSingleLayout() throws Exception {
        for (int[] layout : new int[][]{{0, 0, 0, 0}, {4, 0, 4, 2}, {24, 2, 4, 3}}) {
            var skin = image("classic-a");
            fill(skin, layout[0], layout[1], layout[2], layout[3]);
            markSneaky(skin);
            var evidence = analyzer.analyze(skin);
            assertEquals(List.of(SkinFeature.CUSTOMIZABLE_PLAYER_MODELS,
                    SkinFeature.SNEAKY_CAPES), evidence.supportedFeatures());
            assertEquals(List.of(SkinConflictReason.SNEAKY_FRESH_MOVES_OVERLAP,
                    SkinConflictReason.SNEAKY_JUST_EXPRESSIONS_OVERLAP), evidence.potentialConflicts());
        }
    }

    @Test
    void oneLayoutOverConsumedBytesIsAmbiguousAndOneAfterEndIsIndependent() throws Exception {
        var overlap = image("classic-a");
        fill(overlap, 4, 0, 4, 2);
        var independent = image("classic-a");
        fill(independent, 24, 2, 4, 3);
        assertEquals(List.of(SkinFeature.CUSTOMIZABLE_PLAYER_MODELS), analyzer.analyze(overlap).supportedFeatures());
        assertTrue(analyzer.analyze(overlap).potentialConflicts().isEmpty());
        assertEquals(List.of(SkinFeature.CUSTOMIZABLE_PLAYER_MODELS, SkinFeature.FRESH_MOVES,
                SkinFeature.JUST_EXPRESSIONS), analyzer.analyze(independent).supportedFeatures());
    }

    @Test
    void earsAndSneakyRetainIndependentMarkersAndReasons() throws Exception {
        var skin = image("classic-a");
        skin.setRGB(0, 32, 0xffea2501);
        skin.setRGB(1, 32, 0xff000000);
        markSneaky(skin);
        var evidence = analyzer.analyze(skin);
        assertEquals(List.of(SkinFeature.EARS, SkinFeature.CUSTOMIZABLE_PLAYER_MODELS,
                SkinFeature.SNEAKY_CAPES), evidence.supportedFeatures());
        assertEquals(List.of(SkinConflictReason.SNEAKY_FRESH_MOVES_OVERLAP,
                SkinConflictReason.SNEAKY_JUST_EXPRESSIONS_OVERLAP), evidence.potentialConflicts());
        skin.setRGB(1, 32, 0xff020000);
        assertTrue(analyzer.analyze(skin).potentialConflicts().contains(SkinConflictReason.MALFORMED_EARS_DATA));
    }

    private static void markSneaky(BufferedImage skin) {
        int[] marker = {0xfffff42f, 0xffffffff, 0xff9c59d1, 0xff292929};
        for (int i = 0; i < marker.length; i++) skin.setRGB(60, 48 + i, marker[i]);
    }

    private static void fill(BufferedImage image, int x, int y, int width, int height) {
        for (int dy = y; dy < y + height; dy++) for (int dx = x; dx < x + width; dx++) {
            image.setRGB(dx, dy, image.getRGB(dx, dy) | 0xff000000);
        }
    }

    private static BufferedImage image(String name) throws Exception {
        try (var input = CpmSkinCompatibilityTest.class.getResourceAsStream("/cpm/" + name + ".png")) {
            return ImageIO.read(java.util.Objects.requireNonNull(input));
        }
    }
}
