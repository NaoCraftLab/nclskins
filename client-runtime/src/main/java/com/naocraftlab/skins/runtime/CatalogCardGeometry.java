package com.naocraftlab.skins.runtime;

final class CatalogCardGeometry {
    private static final int SERVICE_ICON_SIZE = 32;
    private static final int ACTION_AREA_HEIGHT = 23;

    private CatalogCardGeometry() {
    }

    static int readOnlyHeight(int personalHeight) {
        return Math.max(1, personalHeight - ACTION_AREA_HEIGHT);
    }

    static Bounds name(Bounds card) {
        return new Bounds(
                card.x() + 4,
                card.y() + 7,
                Math.max(1, card.width() - 8),
                10);
    }

    static Bounds preview(Bounds card) {
        return new Bounds(
                card.x() + 5,
                card.y() + 20,
                Math.max(1, card.width() - 10),
                Math.max(1, card.height() - 25));
    }

    static Bounds previewWithActions(Bounds card) {
        return new Bounds(
                card.x() + 5,
                card.y() + 20,
                Math.max(1, card.width() - 10),
                Math.max(1, card.height() - 25 - ACTION_AREA_HEIGHT));
    }

    static Bounds renameField(Bounds card) {
        return new Bounds(card.x() + 3, card.y() + 3, card.width() - 6, 20);
    }

    static ActionPair renameActions(Bounds card) {
        int half = Math.max(1, (card.width() - 8) / 2);
        return new ActionPair(
                new Bounds(card.x() + 3, card.bottom() - 23, half, 20),
                new Bounds(card.x() + 5 + half, card.bottom() - 23, card.width() - half - 8, 20));
    }

    static ActionPair personalActions(Bounds card) {
        int half = Math.max(1, (card.width() - 6) / 2);
        return new ActionPair(
                new Bounds(card.x() + 2, card.bottom() - 22, half, 20),
                new Bounds(card.x() + 4 + half, card.bottom() - 22, Math.max(1, card.width() - half - 6), 20));
    }

    static Bounds serviceIcon(Bounds preview) {
        int size = Math.max(1, Math.min(SERVICE_ICON_SIZE,
                Math.min(preview.width(), preview.height())));
        return new Bounds(
                preview.x() + (preview.width() - size) / 2,
                preview.y() + (preview.height() - size) / 2,
                size,
                size);
    }

    record ActionPair(Bounds left, Bounds right) {
    }
}
