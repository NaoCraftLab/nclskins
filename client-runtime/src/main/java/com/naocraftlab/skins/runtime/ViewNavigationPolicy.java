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
                .filter(node -> !viewportTab(node) || view.scrollSurface(node.surfaceId().orElseThrow())
                        .filter(surface -> AppearanceCollections.intersects(node.bounds(), surface.viewport())).isPresent())
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
            var precedingOrder = focusedId == null ? Optional.<ViewSpec.NavigationNode>empty() : view.navigationNode(focusedId);
            if (precedingOrder.filter(ViewNavigationPolicy::viewportTab).isPresent()) {
                int order = precedingOrder.orElseThrow().tabOrder();
                var adjacent = reverse ? nodes.stream().filter(node -> node.tabOrder() < order).reduce((a, b) -> b)
                        : nodes.stream().filter(node -> node.tabOrder() > order).findFirst();
                if (adjacent.isPresent()) return adjacent;
            }
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
        if (current == null) {
            return Optional.empty();
        }
        if ("add_source".equals(view.screenId())) {
            return catalogDirection(view, current, command);
        }
        if (current.pattern() == ViewSpec.NavigationPattern.NONE) {
            return Optional.empty();
        }
        List<ViewSpec.NavigationNode> peers = view.navigationNodes().stream()
                .filter(ViewSpec.NavigationNode::enabled)
                .filter(node -> node.pattern() == current.pattern())
                .filter(node -> node.surfaceId().equals(current.surfaceId()))
                .filter(node -> !node.id().equals(current.id()))
                .toList();
        if (current.pattern() == ViewSpec.NavigationPattern.COMPOSITE_LIST) {
            Comparator<ViewSpec.NavigationNode> order = Comparator.comparingInt(ViewSpec.NavigationNode::documentOrder);
            if (command == ViewSpec.NavigationCommand.LEFT || command == ViewSpec.NavigationCommand.RIGHT) {
                var siblings = peers.stream().filter(node -> node.bounds().y() == current.bounds().y());
                return command == ViewSpec.NavigationCommand.LEFT
                        ? siblings.filter(node -> node.documentOrder() < current.documentOrder()).max(order)
                        : siblings.filter(node -> node.documentOrder() > current.documentOrder()).min(order);
            }
            var rows = peers.stream().filter(node -> command == ViewSpec.NavigationCommand.UP
                    ? node.bounds().y() < current.bounds().y() : node.bounds().y() > current.bounds().y());
            return rows.min(Comparator.comparingInt((ViewSpec.NavigationNode node) ->
                    Math.abs(node.bounds().y() - current.bounds().y())).thenComparing(order));
        }
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
        if (current.pattern() == ViewSpec.NavigationPattern.VERTICAL_LIST) {
            if (command != ViewSpec.NavigationCommand.UP
                    && command != ViewSpec.NavigationCommand.DOWN) {
                return Optional.empty();
            }
            Comparator<ViewSpec.NavigationNode> order = Comparator
                    .comparingInt(ViewSpec.NavigationNode::documentOrder);
            return command == ViewSpec.NavigationCommand.UP
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

    static boolean viewportTab(ViewSpec.NavigationNode node) {
        return node.surfaceId().filter(id -> id.equals("gallery.cards") || id.equals("external.review") || id.equals("add.catalog")
                && (node.id().startsWith("add.catalog.skin:") || node.id().startsWith("add.catalog.collection:")
                || node.id().startsWith("add.catalog.more:") || node.id().startsWith("add.catalog.rename")
                || node.id().startsWith("add.catalog.delete"))).isPresent();
    }

    private static Optional<ViewSpec.NavigationNode> catalogDirection(ViewSpec view,
            ViewSpec.NavigationNode current, ViewSpec.NavigationCommand command) {
        if (view.scrollSurface("add.catalog").isEmpty()) return Optional.empty();
        var anchors = view.navigationNodes().stream().filter(ViewSpec.NavigationNode::enabled)
                .filter(node -> node.pattern() == ViewSpec.NavigationPattern.GRID)
                .filter(node -> node.surfaceId().filter("add.catalog"::equals).isPresent()).toList();
        var order = Comparator.comparingInt(ViewSpec.NavigationNode::documentOrder);
        if (current.id().equals("add.catalog.disclosure") && command == ViewSpec.NavigationCommand.DOWN)
            return anchors.stream().min(order);
        if (current.id().equals("add.cancel") && command == ViewSpec.NavigationCommand.UP)
            return anchors.stream().max(order);
        if (!viewportTab(current)) return Optional.empty();
        var anchor = current.pattern() == ViewSpec.NavigationPattern.GRID ? current : anchors.stream()
                .filter(node -> node.documentOrder() < current.documentOrder()).max(order).orElse(null);
        if (anchor == null) return Optional.empty();
        if (command == ViewSpec.NavigationCommand.LEFT || command == ViewSpec.NavigationCommand.RIGHT) {
            var row = anchors.stream().filter(node -> node.bounds().y() == anchor.bounds().y());
            return command == ViewSpec.NavigationCommand.LEFT
                    ? row.filter(node -> node.documentOrder() < anchor.documentOrder()).max(order).or(() -> Optional.of(anchor))
                    : row.filter(node -> node.documentOrder() > anchor.documentOrder()).min(order).or(() -> Optional.of(anchor));
        }
        int x = centerX(anchor.bounds());
        int y = anchor.bounds().y();
        boolean up = command == ViewSpec.NavigationCommand.UP;
        return anchors.stream().filter(node -> up ? node.bounds().y() < y : node.bounds().y() > y)
                .min(Comparator.comparingInt((ViewSpec.NavigationNode node) -> Math.abs(node.bounds().y() - y))
                        .thenComparingInt(node -> Math.abs(centerX(node.bounds()) - x)).thenComparing(order))
                .or(() -> view.navigationNode(up ? "add.catalog.disclosure" : "add.cancel")
                        .filter(ViewSpec.NavigationNode::enabled));
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
