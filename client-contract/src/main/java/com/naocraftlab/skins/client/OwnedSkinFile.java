package com.naocraftlab.skins.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.regex.Pattern;


public final class OwnedSkinFile implements AutoCloseable {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final FileAttribute<?> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private static final FileAttribute<?> OWNER_ONLY_FILE =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private final Path directory;
    private final Path path;
    private boolean closed;

    private OwnedSkinFile(Path directory, Path path) {
        this.directory = directory;
        this.path = path;
    }


    public static OwnedSkinFile stage(String sha256, byte[] pngBytes) throws IOException {
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(pngBytes, "pngBytes");
        if (!SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Expected a lowercase SHA-256 value");
        }

        Path directory = createDirectory();
        Path path = directory.resolve(sha256 + ".png");
        try {
            createFile(path);
            Files.write(path, pngBytes);
            return new OwnedSkinFile(directory, path);
        } catch (IOException | RuntimeException failure) {
            delete(path);
            delete(directory);
            throw failure;
        }
    }


    public synchronized Path path() {
        if (closed) {
            throw new IllegalStateException("Owned skin file is closed");
        }
        return path;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        delete(path);
        delete(directory);
    }

    private static Path createDirectory() throws IOException {
        try {
            return Files.createTempDirectory("nclskins-feature-skin-", OWNER_ONLY_DIRECTORY);
        } catch (UnsupportedOperationException unsupportedPermissions) {
            return Files.createTempDirectory("nclskins-feature-skin-");
        }
    }

    private static void createFile(Path path) throws IOException {
        try {
            Files.createFile(path, OWNER_ONLY_FILE);
        } catch (UnsupportedOperationException unsupportedPermissions) {
            Files.createFile(path);
        }
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            path.toFile().deleteOnExit();
        }
    }
}
