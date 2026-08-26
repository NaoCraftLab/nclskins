package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ViewNavigationPolicyTest {
    @Test
    void viewRejectsDuplicateIdsTabOrdersAndMissingDirectionalSurfaces() {
        ViewSpec.NavigationNode first = card(
                "card", new Bounds(0, 0, 20, 20), "surface", 0,
                ViewSpec.NavigationPattern.GRID);
        assertThrows(IllegalArgumentException.class,
                () -> view(List.of(first, first), List.of(surface(
                        "surface", new Bounds(0, 0, 40, 40),
                        ViewSpec.Scrollbar.Orientation.VERTICAL, 0, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> view(List.of(
                        control("one", 0, true),
                        new ViewSpec.NavigationNode(
                                "two", new Bounds(20, 0, 18, 18), Optional.empty(),
                                1, 0, true, ViewSpec.NavigationPattern.NONE, Optional.empty())),
                        List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> view(List.of(first), List.of()));
    }

    @Test
    void tabTraversalUsesExplicitOrderSkipsDisabledAndReversesSymmetrically() {
        ViewSpec view = view(List.of(
                control("recovery", 0, true),
                control("search", 1, true),
                control("card", 2, true),
                control("disabled", 3, false),
                control("done", 4, true)), List.of());

        assertEquals("recovery", target(view, null, ViewSpec.NavigationCommand.TAB_FORWARD));
        assertEquals("search", target(view, "recovery", ViewSpec.NavigationCommand.TAB_FORWARD));
        assertEquals("done", target(view, "card", ViewSpec.NavigationCommand.TAB_FORWARD));
        assertEquals("card", target(view, "done", ViewSpec.NavigationCommand.TAB_BACKWARD));
        assertEquals("done", target(view, "recovery", ViewSpec.NavigationCommand.TAB_BACKWARD));
    }

    @Test
    void horizontalCardsDoNotWrapAndGridUsesStableGeometricTieBreaks() {
        ViewSpec view = view(List.of(
                card("gallery-0", new Bounds(0, 0, 20, 20), "gallery", 0,
                        ViewSpec.NavigationPattern.HORIZONTAL_LIST),
                card("gallery-1", new Bounds(30, 0, 20, 20), "gallery", 1,
                        ViewSpec.NavigationPattern.HORIZONTAL_LIST),
                card("grid-0", new Bounds(0, 40, 20, 20), "grid", 0,
                        ViewSpec.NavigationPattern.GRID),
                card("grid-1", new Bounds(30, 40, 20, 20), "grid", 1,
                        ViewSpec.NavigationPattern.GRID),
                card("grid-2", new Bounds(2, 70, 20, 20), "grid", 2,
                        ViewSpec.NavigationPattern.GRID),
                card("grid-3", new Bounds(32, 70, 20, 20), "grid", 3,
                        ViewSpec.NavigationPattern.GRID)), List.of(
                surface("gallery", new Bounds(0, 0, 50, 20),
                        ViewSpec.Scrollbar.Orientation.HORIZONTAL, 0, 30),
                surface("grid", new Bounds(0, 40, 50, 40),
                        ViewSpec.Scrollbar.Orientation.VERTICAL, 0, 30)));

        assertTrue(ViewNavigationPolicy.target(
                view, "gallery-0", ViewSpec.NavigationCommand.LEFT).isEmpty());
        assertEquals("gallery-1", target(
                view, "gallery-0", ViewSpec.NavigationCommand.RIGHT));
        assertEquals("grid-3", target(view, "grid-1", ViewSpec.NavigationCommand.DOWN));
        assertEquals("grid-0", target(view, "grid-1", ViewSpec.NavigationCommand.LEFT));
        assertTrue(ViewNavigationPolicy.target(
                view, "grid-1", ViewSpec.NavigationCommand.RIGHT).isEmpty());
    }

    @Test
    void directionalTraversalSkipsDisabledNodesAndReturnsEmptyWithoutCandidate() {
        ViewSpec.NavigationNode first = card(
                "first", new Bounds(0, 0, 20, 20), "gallery", 0,
                ViewSpec.NavigationPattern.HORIZONTAL_LIST);
        ViewSpec.NavigationNode disabled = new ViewSpec.NavigationNode(
                "disabled", new Bounds(30, 0, 20, 20), Optional.of("gallery"),
                1, -1, false, ViewSpec.NavigationPattern.HORIZONTAL_LIST, Optional.empty());
        ViewSpec.NavigationNode third = card(
                "third", new Bounds(60, 0, 20, 20), "gallery", 2,
                ViewSpec.NavigationPattern.HORIZONTAL_LIST);
        ViewSpec view = view(List.of(first, disabled, third), List.of(surface(
                "gallery", new Bounds(0, 0, 50, 20),
                ViewSpec.Scrollbar.Orientation.HORIZONTAL, 0, 30)));

        assertEquals("third", target(view, "first", ViewSpec.NavigationCommand.RIGHT));
        assertEquals("first", target(view, "third", ViewSpec.NavigationCommand.LEFT));
        assertTrue(ViewNavigationPolicy.target(
                view, "third", ViewSpec.NavigationCommand.RIGHT).isEmpty());
        assertTrue(ViewNavigationPolicy.activationAction(view, "disabled").isEmpty());
    }

    @Test
    void raggedGridUsesPrimaryThenCrossAxisThenDocumentOrder() {
        ViewSpec.NavigationNode current = card(
                "current", new Bounds(40, 0, 20, 20), "grid", 0,
                ViewSpec.NavigationPattern.GRID);
        ViewSpec.NavigationNode fartherCross = card(
                "farther-cross", new Bounds(0, 30, 20, 20), "grid", 1,
                ViewSpec.NavigationPattern.GRID);
        ViewSpec.NavigationNode closerCrossLater = card(
                "closer-cross-later", new Bounds(32, 30, 20, 20), "grid", 3,
                ViewSpec.NavigationPattern.GRID);
        ViewSpec.NavigationNode closerCrossEarlier = card(
                "closer-cross-earlier", new Bounds(48, 30, 20, 20), "grid", 2,
                ViewSpec.NavigationPattern.GRID);
        ViewSpec.NavigationNode fartherRow = card(
                "farther-row", new Bounds(40, 70, 20, 20), "grid", 4,
                ViewSpec.NavigationPattern.GRID);
        ViewSpec view = view(
                List.of(current, fartherCross, closerCrossLater, closerCrossEarlier, fartherRow),
                List.of(surface("grid", new Bounds(0, 0, 100, 60),
                        ViewSpec.Scrollbar.Orientation.VERTICAL, 0, 40)));

        assertEquals("closer-cross-earlier", target(
                view, "current", ViewSpec.NavigationCommand.DOWN));
        assertTrue(ViewNavigationPolicy.target(
                view, "current", ViewSpec.NavigationCommand.UP).isEmpty());
        assertTrue(ViewNavigationPolicy.target(
                view, "current", ViewSpec.NavigationCommand.LEFT).isEmpty());
    }

    @Test
    void partiallyHeaderCoveredCardRemainsDirectionalSource() {
        ViewSpec.NavigationNode partiallyCovered = card(
                "first-row", new Bounds(10, 50, 20, 20), "catalog", 0,
                ViewSpec.NavigationPattern.GRID);
        ViewSpec.NavigationNode secondRow = card(
                "second-row", new Bounds(10, 86, 20, 20), "catalog", 1,
                ViewSpec.NavigationPattern.GRID);
        ViewSpec view = view(
                List.of(partiallyCovered, secondRow),
                List.of(surface(
                        "catalog", new Bounds(0, 58, 80, 40),
                        ViewSpec.Scrollbar.Orientation.VERTICAL, 8, 80)));

        assertEquals("second-row", target(
                view, "first-row", ViewSpec.NavigationCommand.DOWN));
        assertEquals(18.0, ViewNavigationPolicy.ensureVisibleOffset(
                view, secondRow).orElseThrow());
    }

    @Test
    void ensureVisibleUsesMinimalClampedCanonicalOffsetIncludingFrame() {
        ViewSpec.NavigationNode node = card(
                "card", new Bounds(52, 10, 20, 20), "surface", 0,
                ViewSpec.NavigationPattern.GRID);
        ViewSpec view = view(List.of(node), List.of(surface(
                "surface",
                new Bounds(0, 0, 60, 40),
                ViewSpec.Scrollbar.Orientation.HORIZONTAL,
                10,
                40)));

        assertEquals(24.0, ViewNavigationPolicy.ensureVisibleOffset(view, node).orElseThrow());
        ViewSpec.NavigationNode alreadyVisible = card(
                "visible", new Bounds(4, 4, 20, 20), "surface", 1,
                ViewSpec.NavigationPattern.GRID);
        assertTrue(ViewNavigationPolicy.ensureVisibleOffset(
                view.withNavigationNodes(List.of(alreadyVisible)), alreadyVisible).isEmpty());
    }

    @Test
    void ensureVisibleHandlesEveryEdgeClampAndMissingSurfaceIdempotently() {
        ViewSpec.ScrollSurface horizontal = surface(
                "horizontal", new Bounds(10, 10, 50, 40),
                ViewSpec.Scrollbar.Orientation.HORIZONTAL, 20, 100);
        ViewSpec.ScrollSurface vertical = surface(
                "vertical", new Bounds(10, 10, 50, 40),
                ViewSpec.Scrollbar.Orientation.VERTICAL, 20, 100);
        ViewSpec.NavigationNode left = card(
                "left", new Bounds(5, 15, 10, 10), "horizontal", 0,
                ViewSpec.NavigationPattern.GRID);
        ViewSpec.NavigationNode right = card(
                "right", new Bounds(55, 15, 10, 10), "horizontal", 1,
                ViewSpec.NavigationPattern.GRID);
        ViewSpec.NavigationNode top = card(
                "top", new Bounds(15, 5, 10, 10), "vertical", 2,
                ViewSpec.NavigationPattern.GRID);
        ViewSpec.NavigationNode bottom = card(
                "bottom", new Bounds(15, 45, 10, 10), "vertical", 3,
                ViewSpec.NavigationPattern.GRID);
        ViewSpec view = view(
                List.of(left, right, top, bottom), List.of(horizontal, vertical));

        assertEquals(13.0, ViewNavigationPolicy.ensureVisibleOffset(view, left).orElseThrow());
        assertEquals(27.0, ViewNavigationPolicy.ensureVisibleOffset(view, right).orElseThrow());
        assertEquals(13.0, ViewNavigationPolicy.ensureVisibleOffset(view, top).orElseThrow());
        assertEquals(27.0, ViewNavigationPolicy.ensureVisibleOffset(view, bottom).orElseThrow());

        ViewSpec.NavigationNode stale = new ViewSpec.NavigationNode(
                "stale", new Bounds(-500, -500, 10, 10), Optional.of("vertical"),
                4, -1, true, ViewSpec.NavigationPattern.GRID, Optional.empty());
        assertEquals(0.0, ViewNavigationPolicy.ensureVisibleOffset(view, stale).orElseThrow());
        assertFalse(ViewNavigationPolicy.ensureVisibleOffset(
                view, card("missing", new Bounds(0, 0, 10, 10), "missing", 5,
                        ViewSpec.NavigationPattern.GRID)).isPresent());
    }

    private static String target(
            ViewSpec view, String current, ViewSpec.NavigationCommand command) {
        return ViewNavigationPolicy.target(view, current, command).orElseThrow().id();
    }

    private static ViewSpec.NavigationNode control(String id, int order, boolean enabled) {
        return new ViewSpec.NavigationNode(
                id,
                new Bounds(order * 20, 0, 18, 18),
                Optional.empty(),
                order,
                order,
                enabled,
                ViewSpec.NavigationPattern.NONE,
                Optional.empty());
    }

    private static ViewSpec.NavigationNode card(
            String id,
            Bounds bounds,
            String surface,
            int order,
            ViewSpec.NavigationPattern pattern) {
        return ViewSpec.NavigationNode.card(
                id, bounds, surface, order, -1, true, pattern, Optional.empty());
    }

    private static ViewSpec.ScrollSurface surface(
            String id,
            Bounds viewport,
            ViewSpec.Scrollbar.Orientation orientation,
            double offset,
            double maximum) {
        return new ViewSpec.ScrollSurface(id, viewport, orientation, offset, maximum);
    }

    private static ViewSpec view(
            List<ViewSpec.NavigationNode> nodes, List<ViewSpec.ScrollSurface> surfaces) {
        return new ViewSpec(
                "navigation",
                UiMessage.literal("Navigation", UiMessage.Severity.INFO),
                100,
                100,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                surfaces,
                List.of(),
                List.of(),
                nodes);
    }
}
