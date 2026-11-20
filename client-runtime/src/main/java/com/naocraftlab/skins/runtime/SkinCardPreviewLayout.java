package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CenteredPlayerPreviewGeometry;

final class SkinCardPreviewLayout {
    private SkinCardPreviewLayout() {
    }

    static Bounds modelIcon(Bounds card) {
        Bounds slot = CatalogCardGeometry.preview(card);
        int width = Math.max(1, Math.min(slot.width(),
                Math.round(CenteredPlayerPreviewGeometry.fittedScale(slot.height(), 1.0F))));
        int height = width * 2;
        return new Bounds(slot.x() + (slot.width() - width) / 2,
                slot.y() + (slot.height() - height) / 2, width, height);
    }
}
