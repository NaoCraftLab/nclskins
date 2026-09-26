package com.naocraftlab.skins.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

final class RetainedSkinPixels {
    private long sequence;
    private final ReferenceQueue<Object> retired = new ReferenceQueue<>();
    private final Map<String, Pixels> textures = new HashMap<>();

    synchronized void retain(String location, int[] argb, Object texture) {
        drain();
        if (texture == null) return;
        Pixels previous = textures.get(location);
        if (previous != null && previous.get() == texture) return;
        textures.put(location, new Pixels(texture, location, argb.clone(), ++sequence, retired));
    }

    synchronized Snapshot capture(String location, int[] argb, Object texture) {
        retain(location, argb, texture);
        return texture == null ? new Snapshot(argb.clone(), 0) : snapshot(location);
    }

    synchronized int[] read(String location) {
        Snapshot snapshot = snapshot(location);
        return snapshot == null ? null : snapshot.argb();
    }

    synchronized Snapshot snapshot(String location) {
        drain();
        Pixels pixels = textures.get(location);
        if (pixels == null) return null;
        if (pixels.get() == null) {
            textures.remove(location);
            return null;
        }
        return new Snapshot(pixels.argb.clone(), pixels.revision);
    }

    record Snapshot(int[] argb, long revision) {}

    private void drain() {
        Pixels pixels;
        while ((pixels = (Pixels) retired.poll()) != null) textures.remove(pixels.location, pixels);
    }

    private static final class Pixels extends WeakReference<Object> {
        private final String location;
        private final int[] argb;
        private final long revision;

        private Pixels(Object texture, String location, int[] argb, long revision, ReferenceQueue<Object> queue) {
            super(texture, queue);
            this.location = location;
            this.argb = argb;
            this.revision = revision;
        }
    }
}
