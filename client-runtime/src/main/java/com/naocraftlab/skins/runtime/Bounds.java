package com.naocraftlab.skins.runtime;


public record Bounds(int x, int y, int width, int height) {
    public Bounds {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bounds must have positive dimensions");
        }
    }

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < right() && mouseY >= y && mouseY < bottom();
    }
}
