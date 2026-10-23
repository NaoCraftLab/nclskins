package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.TextureRegistry;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface CapePreviewSource extends AutoCloseable {
    CompletableFuture<Optional<Frame>> nextFrame(int installedIndex);
    default boolean thumbnail() { return false; }
    default void playing(boolean playing) {}
    void installed(int index);
    @Override void close() throws IOException;

    final class Frame implements AutoCloseable {
        private final int index;
        private final String sha256;
        private final int width;
        private final int height;
        private byte[] rgba8;
        private final TextureRegistry.CpuLease cpuLease;

        public Frame(int index, String sha256, int width, int height,
                byte[] rgba8, TextureRegistry.CpuLease cpuLease) {
            this.index = index;
            this.sha256 = sha256;
            this.width = width;
            this.height = height;
            this.rgba8 = rgba8;
            this.cpuLease = cpuLease;
        }

        public int index() { return index; }
        public String sha256() { return sha256; }
        public int width() { return width; }
        public int height() { return height; }
        public byte[] rgba8() { return rgba8; }
        public TextureRegistry.CpuLease cpuLease() { return cpuLease; }

        public void sourcePixelsReleased() {
            rgba8 = null;
            cpuLease.sourcePixelsReleased(Math.multiplyExact(Math.multiplyExact(width, height), 4L));
        }

        @Override public void close() { rgba8 = null; cpuLease.close(); }
    }
}
