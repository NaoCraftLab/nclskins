package com.naocraftlab.skins.runtime;

import java.util.Objects;
import java.util.Optional;


public final class PointerRouting {
    private PointerRouting() {}

    public static Hit hit(ViewSpec view, double x, double y) {
        Objects.requireNonNull(view, "view");
        Optional<String> previewId = view.previews().stream()
                .filter(preview -> preview.bounds().contains(x, y))
                .map(ViewSpec.Preview::id)
                .findFirst();
        boolean scrollbar = view.scrollbar()
                .map(candidate -> candidate.track().contains(x, y))
                .orElse(false);
        return new Hit(previewId, scrollbar);
    }


    public static boolean galleryScrollRegion(ViewSpec view, int viewportHeight, double y) {
        Objects.requireNonNull(view, "view");
        return "gallery".equals(view.screenId())
                && y >= 38
                && y <= Math.max(150, viewportHeight - 64);
    }


    public static boolean clipRegion(ViewSpec view, String clipId, double x, double y) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(clipId, "clipId");
        return view.clipRegions().stream()
                .filter(region -> region.id().equals(clipId))
                .map(ViewSpec.ClipRegion::bounds)
                .anyMatch(bounds -> bounds.contains(x, y));
    }

    public record Hit(Optional<String> previewId, boolean scrollbar) {
        public Hit {
            previewId = Objects.requireNonNull(previewId, "previewId");
        }

        public boolean anyPreview() {
            return previewId.isPresent();
        }

        public boolean preview(String id) {
            Objects.requireNonNull(id, "id");
            return previewId.filter(id::equals).isPresent();
        }

        public boolean anyInteractiveSurface() {
            return anyPreview() || scrollbar;
        }
    }
}
