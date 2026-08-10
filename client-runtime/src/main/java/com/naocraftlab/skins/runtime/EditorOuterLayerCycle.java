package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import java.util.List;
import java.util.Objects;
import java.util.Set;


final class EditorOuterLayerCycle {
    private static final List<OuterLayerPart> HEAD = List.of(OuterLayerPart.HEAD);
    private static final List<OuterLayerPart> BODY = List.of(
            OuterLayerPart.BODY, OuterLayerPart.LEFT_ARM, OuterLayerPart.RIGHT_ARM);
    private static final List<OuterLayerPart> LEGS = List.of(
            OuterLayerPart.LEFT_LEG, OuterLayerPart.RIGHT_LEG);

    private EditorOuterLayerCycle() {}

    static State state(String control, OuterLayerVisibility visibility) {
        Objects.requireNonNull(visibility, "visibility");
        Cycle cycle = cycle(control);
        return cycle.steps().get(cycle.indexOf(visibility)).state();
    }

    static OuterLayerVisibility cycle(
            String control,
            OuterLayerVisibility visibility,
            int direction) {
        Objects.requireNonNull(visibility, "visibility");
        if (direction == 0) {
            return visibility;
        }
        Cycle cycle = cycle(control);
        int current = cycle.indexOf(visibility);
        int next = current < 0
                ? direction < 0 ? cycle.steps().size() - 1 : 0
                : Math.floorMod(current + Integer.signum(direction), cycle.steps().size());
        Step step = cycle.steps().get(next);
        return visibility
                .withAll(cycle.parts(), false)
                .withAll(step.visibleParts(), true);
    }

    private static Cycle cycle(String control) {
        Objects.requireNonNull(control, "control");
        return switch (control) {
            case "head" -> new Cycle(
                    HEAD,
                    List.of(
                            step(Set.of(OuterLayerPart.HEAD), "head_on", "nclskins.editor.outer_head_on"),
                            step(Set.of(), "head_off", "nclskins.editor.outer_head_off")));
            case "body" -> bodyCycle();
            case "legs" -> legCycle();
            default -> throw new IllegalArgumentException("unknown outer-layer cycle: " + control);
        };
    }

    private static Cycle bodyCycle() {
        return new Cycle(
                BODY,
                List.of(
                        step(Set.of(
                                        OuterLayerPart.BODY,
                                        OuterLayerPart.LEFT_ARM,
                                        OuterLayerPart.RIGHT_ARM),
                                "body_all_on",
                                "nclskins.editor.outer_body_all_on"),
                        step(Set.of(), "body_all_off", "nclskins.editor.outer_body_all_off"),
                        step(Set.of(OuterLayerPart.BODY),
                                "body_both_arms_off",
                                "nclskins.editor.outer_body_no_arms"),
                        step(Set.of(OuterLayerPart.LEFT_ARM, OuterLayerPart.RIGHT_ARM),
                                "body_only_arms_on",
                                "nclskins.editor.outer_body_arms_without_body"),
                        step(Set.of(OuterLayerPart.LEFT_ARM),
                                "body_only_left_arm",
                                "nclskins.editor.outer_body_only_left_arm"),
                        step(Set.of(OuterLayerPart.RIGHT_ARM),
                                "body_only_right_arm",
                                "nclskins.editor.outer_body_only_right_arm"),
                        step(Set.of(OuterLayerPart.BODY, OuterLayerPart.LEFT_ARM),
                                "body_right_arm_off",
                                "nclskins.editor.outer_body_and_left_arm"),
                        step(Set.of(OuterLayerPart.BODY, OuterLayerPart.RIGHT_ARM),
                                "body_left_arm_off",
                                "nclskins.editor.outer_body_and_right_arm")));
    }

    private static Cycle legCycle() {
        return new Cycle(
                LEGS,
                List.of(
                        step(Set.of(OuterLayerPart.LEFT_LEG, OuterLayerPart.RIGHT_LEG),
                                "legs_all_on",
                                "nclskins.editor.outer_legs_all_on"),
                        step(Set.of(), "legs_all_off", "nclskins.editor.outer_legs_all_off"),
                        step(Set.of(OuterLayerPart.RIGHT_LEG),
                                "legs_left_off",
                                "nclskins.editor.outer_legs_no_left_leg"),
                        step(Set.of(OuterLayerPart.LEFT_LEG),
                                "legs_right_off",
                                "nclskins.editor.outer_legs_no_right_leg")));
    }

    private static Step step(Set<OuterLayerPart> visibleParts, String icon, String stateKey) {
        return new Step(visibleParts, new State(icon, stateKey));
    }

    record State(String icon, String stateKey) {
        State {
            Objects.requireNonNull(icon, "icon");
            Objects.requireNonNull(stateKey, "stateKey");
        }
    }

    private record Step(Set<OuterLayerPart> visibleParts, State state) {
        Step {
            visibleParts = Set.copyOf(Objects.requireNonNull(visibleParts, "visibleParts"));
            Objects.requireNonNull(state, "state");
        }
    }

    private record Cycle(List<OuterLayerPart> parts, List<Step> steps) {
        Cycle {
            parts = List.copyOf(Objects.requireNonNull(parts, "parts"));
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("cycle must not be empty");
            }
        }

        int indexOf(OuterLayerVisibility visibility) {
            Set<OuterLayerPart> visible = visibility.visibleParts();
            for (int index = 0; index < steps.size(); index++) {
                Step step = steps.get(index);
                boolean matches = parts.stream()
                        .allMatch(part -> visible.contains(part) == step.visibleParts().contains(part));
                if (matches) {
                    return index;
                }
            }
            throw new IllegalStateException("complete cycle has no matching state");
        }
    }
}
