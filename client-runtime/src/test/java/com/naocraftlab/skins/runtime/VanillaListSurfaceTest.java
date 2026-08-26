package com.naocraftlab.skins.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class VanillaListSurfaceTest {
    @Test
    void anchorsTextureToTheNativeBottomRightAndScrollsAlongTheSurfaceAxis() {
        Bounds panelBounds = new Bounds(10, 20, 30, 40);
        ViewSpec.Panel panel = new ViewSpec.Panel(
                "card", panelBounds, ViewSpec.Panel.Style.VANILLA_LIST);

        assertEquals(new VanillaListSurface.Sample(40, 60),
                VanillaListSurface.sample(view(panel, Optional.empty()), panel));
        assertEquals(new VanillaListSurface.Sample(47, 60),
                VanillaListSurface.sample(view(panel, Optional.of(surface(
                        ViewSpec.Scrollbar.Orientation.HORIZONTAL, 7.9))), panel));
        assertEquals(new VanillaListSurface.Sample(40, 67),
                VanillaListSurface.sample(view(panel, Optional.of(surface(
                        ViewSpec.Scrollbar.Orientation.VERTICAL, 7.9))), panel));
    }

    @Test
    void modernMiniatureWorkspaceKeepsBothBoundariesInsideTheCard() {
        assertEquals(
                new VanillaListSurface.Boundaries(20, 58),
                VanillaListSurface.boundaries(new Bounds(10, 20, 30, 40)));
        assertEquals(
                new VanillaListSurface.Boundaries(20, 20),
                VanillaListSurface.boundaries(new Bounds(10, 20, 30, 1)));
    }

    private static ViewSpec.ScrollSurface surface(
            ViewSpec.Scrollbar.Orientation orientation, double offset) {
        return new ViewSpec.ScrollSurface(
                "surface", new Bounds(0, 0, 100, 100), orientation, offset, 100.0);
    }

    private static ViewSpec view(
            ViewSpec.Panel panel, Optional<ViewSpec.ScrollSurface> surface) {
        return new ViewSpec(
                "surface-test",
                UiMessage.literal("Surface", UiMessage.Severity.INFO),
                100,
                100,
                List.of(panel),
                List.of(),
                List.of(),
                List.of(),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                surface.stream().toList());
    }
}
