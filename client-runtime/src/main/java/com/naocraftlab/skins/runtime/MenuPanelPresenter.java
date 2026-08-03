package com.naocraftlab.skins.runtime;

import java.util.Objects;
import java.util.Optional;


public final class MenuPanelPresenter {
    public static final int PANEL_WIDTH = 104;
    public static final int MIN_PANEL_WIDTH = 44;
    public static final int BUTTON_HEIGHT = 20;
    public static final int GAP = 4;
    public static final int MAX_PANEL_HEIGHT = 5 * BUTTON_HEIGHT + 4 * GAP;
    private static final int MIN_PANEL_HEIGHT = 82;
    private static final int RIGHT_MARGIN = 8;
    private static final int BOTTOM_RESERVED = 36;


    public Optional<Layout> present(
            int screenWidth, int screenHeight, int mouseX, int mouseY, Bounds anchor) {
        if (screenWidth <= 0 || screenHeight <= 0) {
            throw new IllegalArgumentException("screen dimensions must be positive");
        }
        Objects.requireNonNull(anchor, "anchor");
        if (anchor.x() < 0
                || anchor.y() < 0
                || anchor.right() > screenWidth
                || anchor.bottom() > screenHeight) {
            return Optional.empty();
        }

        int x = anchor.right() + GAP;
        int availableWidth = screenWidth - x - RIGHT_MARGIN;
        int availableHeight = screenHeight - anchor.y() - BOTTOM_RESERVED;
        if (availableWidth < MIN_PANEL_WIDTH || availableHeight < MIN_PANEL_HEIGHT) {
            return Optional.empty();
        }

        int panelWidth = Math.min(PANEL_WIDTH, availableWidth);
        int modelHeight = Math.min(MAX_PANEL_HEIGHT, availableHeight);
        int y = anchor.y();
        Bounds panel = new Bounds(x, y, panelWidth, modelHeight);
        Bounds preview = new Bounds(x + 4, y + 3, panelWidth - 8, modelHeight - 6);
        Bounds button = panel;
        float centerX = x + panelWidth / 2.0F;
        float centerY = y + modelHeight * 0.38F;
        float yaw = Math.max(-38.0F, Math.min(38.0F, (mouseX - centerX) * 0.55F));
        float pitch = Math.max(-24.0F, Math.min(24.0F, (centerY - mouseY) * 0.35F));
        return Optional.of(new Layout(panel, preview, button, yaw, pitch, 0.92F));
    }

    public record Layout(
            Bounds panelBounds,
            Bounds previewBounds,
            Bounds buttonBounds,
            float yawDegrees,
            float pitchDegrees,
            float scale) {
        public Layout {
            Objects.requireNonNull(panelBounds, "panelBounds");
            Objects.requireNonNull(previewBounds, "previewBounds");
            Objects.requireNonNull(buttonBounds, "buttonBounds");
            if (!Float.isFinite(yawDegrees)
                    || !Float.isFinite(pitchDegrees)
                    || !Float.isFinite(scale)
                    || scale <= 0.0F) {
                throw new IllegalArgumentException("invalid preview transform");
            }
        }

        public UiMessage narration() {
            return UiMessage.info("nclskins.menu.preview");
        }
    }
}
