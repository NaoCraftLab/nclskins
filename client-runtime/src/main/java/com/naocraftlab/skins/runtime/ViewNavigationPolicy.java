package com.naocraftlab.skins.runtime;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ViewNavigationPolicy {
    public static final int FOCUS_FRAME_INSET = 2;

    public static Optional<ViewSpec.NavigationNode> target(
            ViewSpec view, String focusedId, ViewSpec.NavigationCommand command) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(command, "command");
        return switch (command) {
            case TAB_FORWARD -> tabTarget(view, focusedId, false);
            case TAB_BACKWARD -> tabTarget(view, focusedId, true);
            case LEFT, RIGHT, UP, DOWN -> directionalTarget(view, focusedId, command);
            case ACTIVATE -> Optional.empty();
        };
    }

    public static Optional<String> activationAction(ViewSpec view, String focusedId) {
        if (focusedId == null) {
            return Optional.empty();
        }
        return view.navigationNode(focusedId)
                .filter(ViewSpec.NavigationNode::enabled)
                .flatMap(ViewSpec.NavigationNode::activationActionId);
    }

    public static Optional<Double> ensureVisibleOffset(
            ViewSpec view, ViewSpec.NavigationNode node) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(node, "node");
        if (node.surfaceId().isEmpty()) {
            return Optional.empty();
        }
        ViewSpec.ScrollSurface surface = view.scrollSurface(node.surfaceId().orElseThrow())
                .orElse(null);
        if (surface == null) {
            return Optional.empty();
        }
        Bounds viewport = surface.viewport();
        Bounds bounds = node.bounds();
        double desired = surface.offsetPixels();
        if (surface.orientation() == ViewSpec.Scrollbar.Orientation.HORIZONTAL) {
            int leading = bounds.x() - FOCUS_FRAME_INSET;
            int trailing = bounds.right() + FOCUS_FRAME_INSET;
            if (leading < viewport.x()) {
                desired -= viewport.x() - leading;
            } else if (trailing > viewport.right()) {
                desired += trailing - viewport.right();
            }
        } else {
            int leading = bounds.y() - FOCUS_FRAME_INSET;
            int trailing = bounds.bottom() + FOCUS_FRAME_INSET;
            if (leading < viewport.y()) {
                desired -= viewport.y() - leading;
            } else if (trailing > viewport.bottom()) {
                desired += trailing - viewport.bottom();
            }
        }
        desired = Math.max(0.0, Math.min(surface.maximumPixels(), desired));
        return Math.abs(desired - surface.offsetPixels()) <= 0.001
                ? Optional.empty()
                : Optional.of(desired);
    }

    private static Optional<ViewSpec.NavigationNode> tabTarget(
            ViewSpec view, String focusedId, boolean reverse) {
        List<ViewSpec.NavigationNode> nodes = view.navigationNodes().stream()
                .filter(ViewSpec.NavigationNode::enabled)
                .filter(node -> node.tabOrder() >= 0)
                .sorted(Comparator.comparingInt(ViewSpec.NavigationNode::tabOrder)
                        .thenComparingInt(ViewSpec.NavigationNode::documentOrder))
                .toList();
        if (nodes.isEmpty()) {
            return Optional.empty();
        }
        int current = -1;
        for (int index = 0; index < nodes.size(); index++) {
            if (nodes.get(index).id().equals(focusedId)) {
                current = index;
                break;
            }
        }
        if (current < 0) {
            return Optional.of(reverse ? nodes.get(nodes.size() - 1) : nodes.get(0));
        }
        int next = Math.floorMod(current + (reverse ? -1 : 1), nodes.size());
        return Optional.of(nodes.get(next));
    }

    private static Optional<ViewSpec.NavigationNode> directionalTarget(
            ViewSpec view, String focusedId, ViewSpec.NavigationCommand command) {
        if (focusedId == null) {
            return Optional.empty();
        }
        ViewSpec.NavigationNode current = view.navigationNode(focusedId)
                .filter(ViewSpec.NavigationNode::enabled)
                .orElse(null);
        if (current == null || current.pattern() == ViewSpec.NavigationPattern.NONE) {
            return Optional.empty();
        }
        List<ViewSpec.NavigationNode> peers = view.navigationNodes().stream()
                .filter(ViewSpec.NavigationNode::enabled)
                .filter(node -> node.pattern() == current.pattern())
                .filter(node -> node.surfaceId().equals(current.surfaceId()))
                .filter(node -> !node.id().equals(current.id()))
                .toList();
        if (current.pattern() == ViewSpec.NavigationPattern.HORIZONTAL_LIST) {
            if (command != ViewSpec.NavigationCommand.LEFT
                    && command != ViewSpec.NavigationCommand.RIGHT) {
                return Optional.empty();
            }
            Comparator<ViewSpec.NavigationNode> order = Comparator
                    .comparingInt(ViewSpec.NavigationNode::documentOrder);
            return command == ViewSpec.NavigationCommand.LEFT
                    ? peers.stream()
                            .filter(node -> node.documentOrder() < current.documentOrder())
                            .max(order)
                    : peers.stream()
                            .filter(node -> node.documentOrder() > current.documentOrder())
                            .min(order);
        }
        int currentX = centerX(current.bounds());
        int currentY = centerY(current.bounds());
        return peers.stream()
                .filter(node -> inHalfPlane(current, node, command, currentX, currentY))
                .min(Comparator
                        .comparingInt((ViewSpec.NavigationNode node) ->
                                primaryDistance(node.bounds(), command, currentX, currentY))
                        .thenComparingInt(node ->
                                crossDistance(node.bounds(), command, currentX, currentY))
                        .thenComparingInt(ViewSpec.NavigationNode::documentOrder));
    }

    private static boolean inHalfPlane(
            ViewSpec.NavigationNode current,
            ViewSpec.NavigationNode candidate,
            ViewSpec.NavigationCommand command,
            int currentX,
            int currentY) {
        int x = centerX(candidate.bounds());
        int y = centerY(candidate.bounds());
        return switch (command) {
            case LEFT -> y == currentY && x < currentX;
            case RIGHT -> y == currentY && x > currentX;
            case UP -> y < currentY;
            case DOWN -> y > currentY;
            default -> false;
        };
    }

    private static int primaryDistance(
            Bounds bounds, ViewSpec.NavigationCommand command, int currentX, int currentY) {
        return switch (command) {
            case LEFT, RIGHT -> Math.abs(centerX(bounds) - currentX);
            case UP, DOWN -> Math.abs(centerY(bounds) - currentY);
            default -> Integer.MAX_VALUE;
        };
    }

    private static int crossDistance(
            Bounds bounds, ViewSpec.NavigationCommand command, int currentX, int currentY) {
        return switch (command) {
            case LEFT, RIGHT -> Math.abs(centerY(bounds) - currentY);
            case UP, DOWN -> Math.abs(centerX(bounds) - currentX);
            default -> Integer.MAX_VALUE;
        };
    }

    private static int centerX(Bounds bounds) {
        return bounds.x() + bounds.width() / 2;
    }

    private static int centerY(Bounds bounds) {
        return bounds.y() + bounds.height() / 2;
    }

    private ViewNavigationPolicy() {
    }
}
