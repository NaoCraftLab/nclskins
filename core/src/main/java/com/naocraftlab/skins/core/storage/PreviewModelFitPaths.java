package com.naocraftlab.skins.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

final class PreviewModelFitPaths {
    private PreviewModelFitPaths() {}

    static void beforeInitialization(StorageLayout layout, UUID account, Path target) throws IOException {
        directory(layout.root());
        directory(layout.accounts());
        directory(layout.assetsSha256());
        directory(layout.textureCache());
        directory(layout.backups());
        regular(layout.locks().resolve("storage-preflight.lock"));
        forWrite(layout, account, target);
    }

    static void forWrite(StorageLayout layout, UUID account, Path target) throws IOException {
        if (!target.startsWith(layout.accounts().resolve(account.toString()))) {
            throw new IOException("Foreign derived path");
        }
        regular(layout.accountLock(account));
        regular(target);
    }

    static boolean regular(Path path) throws IOException {
        directory(path.getParent());
        BasicFileAttributes attributes = attributes(path);
        if (attributes == null) return false;
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("Derived storage requires a regular file");
        }
        return true;
    }

    static boolean safeParent(Path target) {
        try {
            directory(target.getParent());
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    private static void directory(Path path) throws IOException {
        Path component = path.getRoot();
        for (Path part : path) {
            component = component.resolve(part);
            BasicFileAttributes attributes = attributes(component);
            if (attributes != null && (!attributes.isDirectory() || attributes.isSymbolicLink())) {
                throw new IOException("Derived storage requires real directories");
            }
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return null;
        }
    }
}
