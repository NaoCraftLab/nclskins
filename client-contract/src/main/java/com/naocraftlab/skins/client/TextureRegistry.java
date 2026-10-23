package com.naocraftlab.skins.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;


public interface TextureRegistry extends AutoCloseable {

    TextureHandle register(TextureKind kind, String sha256, Path pngFile) throws IOException;


    TextureHandle register(TextureKind kind, String sha256, byte[] pngBytes) throws IOException;

    default TextureHandle registerRgba(TextureKind kind, String sha256, int width, int height,
            byte[] rgba8, CpuLease cpuLease) throws IOException {
        cpuLease.close();
        throw new IOException("Raw frame upload is unavailable");
    }

    interface CpuLease extends AutoCloseable {
        default void sourcePixelsReleased(long retainedNativeBytes) {}
        @Override void close();
    }


    default TextureUseValidity capture(TextureHandle handle) {
        return TextureUseValidity.INVALID;
    }

    void release(TextureHandle handle);


    @Override
    void close();

    enum TextureKind {

        PLAYER_SKIN,

        IMAGE
    }

    record TextureHandle(String location, int width, int height) {
        public TextureHandle {
            Objects.requireNonNull(location, "location");
            if (location.isBlank()) {
                throw new IllegalArgumentException("Texture location must not be blank");
            }
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Texture dimensions must be positive");
            }
        }
    }
}
