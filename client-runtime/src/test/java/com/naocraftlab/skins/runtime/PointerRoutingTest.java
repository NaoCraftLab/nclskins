package com.naocraftlab.skins.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.naocraftlab.skins.client.PreviewRenderer.CapeMode;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PointerRoutingTest {
    @Test
    void classifiesPreviewAndScrollbarWithoutNativeInputTypes() {
        ViewSpec view = view("preset_editor");

        PointerRouting.Hit preview = PointerRouting.hit(view, 15, 25);
        assertTrue(preview.preview("editor.preview"));
        assertTrue(preview.anyInteractiveSurface());

        PointerRouting.Hit scrollbar = PointerRouting.hit(view, 55, 82);
        assertTrue(scrollbar.scrollbar());
        assertFalse(scrollbar.anyPreview());

        assertFalse(PointerRouting.hit(view, 0, 0).anyInteractiveSurface());
    }

    @Test
    void galleryScrollBandPreservesCanonicalVerticalBounds() {
        ViewSpec gallery = view("gallery");

        assertTrue(PointerRouting.galleryScrollRegion(gallery, 240, 38));
        assertTrue(PointerRouting.galleryScrollRegion(gallery, 240, 176));
        assertFalse(PointerRouting.galleryScrollRegion(gallery, 240, 177));
        assertFalse(PointerRouting.galleryScrollRegion(view("preset_editor"), 240, 100));
    }

    @Test
    void namedClipRegionOwnsOnlyItsExactBounds() {
        ViewSpec clipped = new ViewSpec(
                "add_source",
                UiMessage.info("title"),
                200,
                240,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                List.of(new ViewSpec.ClipRegion(
                        "add.catalog.viewport",
                        new Bounds(0, 58, 200, 149),
                        List.of("add.catalog."))),
                List.of());

        assertFalse(PointerRouting.clipRegion(clipped, "add.catalog.viewport", 100, 57));
        assertTrue(PointerRouting.clipRegion(clipped, "add.catalog.viewport", 100, 58));
        assertTrue(PointerRouting.clipRegion(clipped, "add.catalog.viewport", 199, 206));
        assertFalse(PointerRouting.clipRegion(clipped, "add.catalog.viewport", 100, 207));
        assertFalse(PointerRouting.clipRegion(clipped, "missing", 100, 100));
    }

    private static ViewSpec view(String screenId) {
        ViewSpec.Preview preview = new ViewSpec.Preview(
                "editor.preview",
                new Bounds(10, 20, 30, 40),
                SkinReference.accountDefault(),
                "revision",
                SkinVariant.CLASSIC,
                Optional.empty(),
                CapeMode.OFF,
                true,
                0.0F,
                0.0F,
                1.0F,
                Optional.empty());
        ViewSpec.Scrollbar scrollbar = new ViewSpec.Scrollbar(
                new Bounds(50, 80, 100, 5),
                new Bounds(60, 80, 20, 5),
                0,
                100);
        return new ViewSpec(
                screenId,
                UiMessage.info("title"),
                200,
                240,
                List.of(),
                List.of(),
                List.of(),
                List.of(preview),
                Optional.of(scrollbar));
    }
}
