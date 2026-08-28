package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class EditorOuterLayerCycleTest {
    @Test
    void headCyclesForwardAndBackwardWithWrap() {
        OuterLayerVisibility all = OuterLayerVisibility.allVisible();
        assertState("head", all, "head_on", "nclskins.editor.outer_head_on");

        OuterLayerVisibility off = EditorOuterLayerCycle.cycle("head", all, 1);
        assertFalse(off.visible(OuterLayerPart.HEAD));
        assertState("head", off, "head_off", "nclskins.editor.outer_head_off");
        assertEquals(all, EditorOuterLayerCycle.cycle("head", off, 1));
        assertEquals(off, EditorOuterLayerCycle.cycle("head", all, -1));
    }

    @Test
    void bodyCycleCoversAllEightMasksInExactForwardAndReverseOrder() {
        List<ExpectedStep> expected = List.of(
                step(true, true, true, "body_all_on", "nclskins.editor.outer_body_all_on"),
                step(false, false, false, "body_all_off", "nclskins.editor.outer_body_all_off"),
                step(true, false, false, "body_both_arms_off", "nclskins.editor.outer_body_no_arms"),
                step(false, true, true, "body_only_arms_on",
                        "nclskins.editor.outer_body_arms_without_body"),
                step(false, true, false, "body_only_left_arm",
                        "nclskins.editor.outer_body_only_left_arm"),
                step(false, false, true, "body_only_right_arm",
                        "nclskins.editor.outer_body_only_right_arm"),
                step(true, true, false, "body_right_arm_off",
                        "nclskins.editor.outer_body_and_left_arm"),
                step(true, false, true, "body_left_arm_off",
                        "nclskins.editor.outer_body_and_right_arm"));
        OuterLayerVisibility visibility = OuterLayerVisibility.allVisible()
                .with(OuterLayerPart.HEAD, false)
                .with(OuterLayerPart.LEFT_LEG, false);
        OuterLayerVisibility first = visibility;

        for (ExpectedStep step : expected) {
            assertBody(visibility, step.first(), step.second(), step.third());
            assertState("body", visibility, step.icon(), step.stateKey());
            assertFalse(visibility.visible(OuterLayerPart.HEAD));
            assertFalse(visibility.visible(OuterLayerPart.LEFT_LEG));
            visibility = EditorOuterLayerCycle.cycle("body", visibility, 1);
        }

        assertEquals(first, visibility);
        visibility = first;
        for (int index = expected.size() - 1; index >= 0; index--) {
            visibility = EditorOuterLayerCycle.cycle("body", visibility, -1);
            ExpectedStep step = expected.get(index);
            assertEquals(maskBody(first, step), visibility);
            assertState("body", visibility, step.icon(), step.stateKey());
        }
        assertEquals(first, visibility);
    }

    @Test
    void legCycleUsesLiteralLeftThenRightOrderAndPreservesOtherParts() {
        List<ExpectedStep> expected = List.of(
                legStep(true, true, "legs_all_on", "nclskins.editor.outer_legs_all_on"),
                legStep(false, false, "legs_all_off", "nclskins.editor.outer_legs_all_off"),
                legStep(false, true, "legs_left_off", "nclskins.editor.outer_legs_no_left_leg"),
                legStep(true, false, "legs_right_off", "nclskins.editor.outer_legs_no_right_leg"));
        OuterLayerVisibility visibility = OuterLayerVisibility.allVisible()
                .with(OuterLayerPart.HEAD, false)
                .with(OuterLayerPart.BODY, false);
        OuterLayerVisibility first = visibility;

        for (ExpectedStep step : expected) {
            assertEquals(step.first(), visibility.visible(OuterLayerPart.LEFT_LEG));
            assertEquals(step.second(), visibility.visible(OuterLayerPart.RIGHT_LEG));
            assertState("legs", visibility, step.icon(), step.stateKey());
            assertFalse(visibility.visible(OuterLayerPart.HEAD));
            assertFalse(visibility.visible(OuterLayerPart.BODY));
            visibility = EditorOuterLayerCycle.cycle("legs", visibility, 1);
        }

        assertEquals(first, visibility);
        visibility = first;
        for (int index = expected.size() - 1; index >= 0; index--) {
            visibility = EditorOuterLayerCycle.cycle("legs", visibility, -1);
            ExpectedStep step = expected.get(index);
            assertEquals(maskLegs(first, step), visibility);
            assertState("legs", visibility, step.icon(), step.stateKey());
        }
        assertEquals(first, visibility);
    }

    private static OuterLayerVisibility maskBody(
            OuterLayerVisibility base, ExpectedStep step) {
        return base.with(OuterLayerPart.BODY, step.first())
                .with(OuterLayerPart.LEFT_ARM, step.second())
                .with(OuterLayerPart.RIGHT_ARM, step.third());
    }

    private static OuterLayerVisibility maskLegs(
            OuterLayerVisibility base, ExpectedStep step) {
        return base.with(OuterLayerPart.LEFT_LEG, step.first())
                .with(OuterLayerPart.RIGHT_LEG, step.second());
    }

    private static void assertBody(
            OuterLayerVisibility visibility, boolean body, boolean leftArm, boolean rightArm) {
        assertEquals(body, visibility.visible(OuterLayerPart.BODY));
        assertEquals(leftArm, visibility.visible(OuterLayerPart.LEFT_ARM));
        assertEquals(rightArm, visibility.visible(OuterLayerPart.RIGHT_ARM));
    }

    private static void assertState(
            String control,
            OuterLayerVisibility visibility,
            String icon,
            String stateKey) {
        EditorOuterLayerCycle.State state = EditorOuterLayerCycle.state(control, visibility);
        assertEquals(icon, state.icon());
        assertEquals(stateKey, state.label().key());
        List<String> expectedPartKeys = switch (control) {
            case "head" -> List.of("options.modelPart.hat");
            case "body" -> List.of(
                    "options.modelPart.jacket",
                    "options.modelPart.left_sleeve",
                    "options.modelPart.right_sleeve");
            case "legs" -> List.of(
                    "options.modelPart.left_pants_leg",
                    "options.modelPart.right_pants_leg");
            default -> throw new IllegalArgumentException(control);
        };
        assertEquals(expectedPartKeys, state.label().arguments().stream()
                .map(UiMessage.class::cast)
                .map(UiMessage::key)
                .toList());
    }

    private static ExpectedStep step(
            boolean body,
            boolean leftArm,
            boolean rightArm,
            String icon,
            String stateKey) {
        return new ExpectedStep(body, leftArm, rightArm, icon, stateKey);
    }

    private static ExpectedStep legStep(
            boolean leftLeg, boolean rightLeg, String icon, String stateKey) {
        return new ExpectedStep(leftLeg, rightLeg, false, icon, stateKey);
    }

    private record ExpectedStep(
            boolean first, boolean second, boolean third, String icon, String stateKey) {}
}
