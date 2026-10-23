package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public interface LocalCapeImportSource {
    Source read(Path path) throws IOException, PngValidationException;
    default PreparedCapeAsset prepare(Path encoded, Path backing) throws IOException, PngValidationException {
        throw new UnsupportedOperationException("Prepared cape import unavailable");
    }

    default Path createBacking() throws IOException {
        throw new UnsupportedOperationException("Prepared cape import unavailable");
    }

    default void discardBacking(Path path) throws IOException {
        throw new UnsupportedOperationException("Prepared cape import unavailable");
    }

    @FunctionalInterface
    interface Cleanup { void discard(Path path) throws IOException; }

    final class Source implements AutoCloseable {
        private final String fileName;
        private final byte[] bytes;
        private final Path stagedPath;
        private final CapePreparationQueue.Reservation scratch;
        private final Cleanup cleanup;

        public Source(String fileName, byte[] bytes) {
            this.fileName = Objects.requireNonNull(fileName);
            this.bytes = Objects.requireNonNull(bytes).clone();
            this.stagedPath = null;
            this.scratch = null;
            this.cleanup = null;
        }

        Source(String fileName, Path stagedPath, CapePreparationQueue.Reservation scratch,
                Cleanup cleanup) {
            this.fileName = Objects.requireNonNull(fileName);
            this.bytes = null;
            this.stagedPath = Objects.requireNonNull(stagedPath);
            this.scratch = scratch;
            this.cleanup = Objects.requireNonNull(cleanup);
        }

        public String fileName() { return fileName; }
        public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
        public Path stagedPath() { return stagedPath; }

        void discardEncoded() throws IOException {
            if (stagedPath != null) cleanup.discard(stagedPath);
        }

        void releaseStage() throws IOException {
            try {
                discardEncoded();
            } finally {
                if (scratch != null) scratch.close();
            }
        }

        @Override public void close() throws IOException { releaseStage(); }
    }
}
