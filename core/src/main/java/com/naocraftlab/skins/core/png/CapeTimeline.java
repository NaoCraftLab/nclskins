package com.naocraftlab.skins.core.png;

import java.util.Arrays;

public final class CapeTimeline {
    private final int[] delaysMillis;
    private final long[] endsMillis;
    private final long cycleMillis;
    private final long plays;

    public CapeTimeline(int[] delaysMillis, long plays) {
        if (delaysMillis == null || delaysMillis.length < 1 || delaysMillis.length > 64 || plays < 0) {
            throw new IllegalArgumentException("Invalid cape timeline");
        }
        this.delaysMillis = delaysMillis.clone();
        this.endsMillis = new long[delaysMillis.length];
        long elapsed = 0;
        for (int index = 0; index < delaysMillis.length; index++) {
            if (delaysMillis[index] < 1) {
                throw new IllegalArgumentException("Cape frame delay must be positive");
            }
            elapsed = Math.addExact(elapsed, delaysMillis[index]);
            endsMillis[index] = elapsed;
        }
        cycleMillis = elapsed;
        if (plays > 0) Math.multiplyExact(cycleMillis, plays);
        this.plays = delaysMillis.length == 1 ? 1 : plays;
    }

    public static CapeTimeline pngStrip(int frameCount) {
        if (frameCount < 1 || frameCount > 64) throw new IllegalArgumentException("Invalid frame count");
        int[] delays = new int[frameCount];
        Arrays.fill(delays, 100);
        return new CapeTimeline(delays, 0);
    }

    public static CapeTimeline gif(int[] centiseconds, int repeats) {
        if (centiseconds == null || repeats < 0 || repeats > 65_535) {
            throw new IllegalArgumentException("Invalid GIF timeline");
        }
        int[] delays = new int[centiseconds.length];
        for (int index = 0; index < delays.length; index++) {
            if (centiseconds[index] < 0 || centiseconds[index] > 65_535) {
                throw new IllegalArgumentException("Invalid GIF delay");
            }
            delays[index] = centiseconds[index] == 0 ? 100 : Math.multiplyExact(centiseconds[index], 10);
        }
        return new CapeTimeline(delays, repeats == 0 ? 0 : (long) repeats + 1);
    }

    public int frameAt(long elapsedMillis) {
        if (elapsedMillis <= 0 || delaysMillis.length == 1) return 0;
        if (plays > 0 && elapsedMillis >= cycleMillis * plays) return delaysMillis.length - 1;
        long position = elapsedMillis % cycleMillis;
        int index = Arrays.binarySearch(endsMillis, position + 1);
        return index >= 0 ? index : -index - 1;
    }

    public int frameCount() { return delaysMillis.length; }
    public int delayMillis(int frame) { return delaysMillis[frame]; }
    public long cycleMillis() { return cycleMillis; }
    public long plays() { return plays; }
    public int[] delaysMillis() { return delaysMillis.clone(); }
}
