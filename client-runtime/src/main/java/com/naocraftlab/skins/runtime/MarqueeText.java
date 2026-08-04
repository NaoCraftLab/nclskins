package com.naocraftlab.skins.runtime;


public final class MarqueeText {
    private MarqueeText() {
    }

    public static int offset(int textWidth, int availableWidth, long elapsedMillis) {
        int overflow = Math.max(0, textWidth - availableWidth);
        if (overflow == 0) {
            return 0;
        }
        double seconds = Math.max(0L, elapsedMillis) / 1_000.0;
        double period = Math.max(overflow * 0.5, 3.0);
        double eased = Math.sin((Math.PI / 2.0)
                * Math.cos((Math.PI * 2.0) * seconds / period)) / 2.0 + 0.5;
        return Math.max(0, Math.min(overflow, (int) Math.floor(eased * overflow)));
    }
}
