package com.naocraftlab.skins.runtime;

final class CatalogCardGeometry {
    private static final int SERVICE_ICON_SIZE = 32;

    private CatalogCardGeometry() {
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

    static Bounds renameField(Bounds card) {
        int inset = Math.min(3, (card.width() - 1) / 2);
        return new Bounds(card.x() + inset, card.y() + 3, card.width() - 2 * inset, 20);
    }

    record MenuRow(Bounds left, Bounds right) {}

    static MenuRow menuRow(Bounds card, int y) {
        int inset = Math.min(2, Math.max(0, (card.width() - 2) / 3));
        int usable = card.width() - 3 * inset;
        int leftWidth = Math.max(1, usable / 2);
        int rightWidth = Math.max(1, usable - leftWidth);
        return new MenuRow(new Bounds(card.x() + inset, y, leftWidth, 20),
                new Bounds(card.right() - inset - rightWidth, y, rightWidth, 20));
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
}
