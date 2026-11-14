package com.naocraftlab.skins.client;

public final class PreviewLoading {
    private PreviewLoading() {}
    public record Frame(String resource, int left, int top, int size, int u, int v,
            int width, int height, int textureWidth, int textureHeight) {}
    public static boolean delayed(long started, long now) { return now - started >= 300_000_000L; }
    public static int phase(long now) { return (int) Math.floorMod(Math.floorDiv(now, 100_000_000L), 8L); }
    public static Frame frame(long now, int left, int top, int width, int height, int size) {
        if (size != 16 && size != 32) throw new IllegalArgumentException("Invalid preview loading size");
        return new Frame("nclskins:textures/gui/icons/status/preview_loading.png",
                left + (width - size) / 2, top + (height - size) / 2, size,
                0, phase(now) * 32, 32, 32, 32, 256);
    }
}
