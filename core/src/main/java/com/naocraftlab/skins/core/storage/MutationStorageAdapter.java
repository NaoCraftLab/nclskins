package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.core.service.MutationGuard;
import com.naocraftlab.skins.core.service.MutationTextureIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class MutationStorageAdapter implements MutationGuard, MutationTextureIdentity {
    private final NclSkinsStorage storage;
    private final TextureCache textures;
    public MutationStorageAdapter(NclSkinsStorage storage) {
        this.storage = java.util.Objects.requireNonNull(storage);
        this.textures = new TextureCache(storage);
    }
    public Lease acquireRemoteMutationLock(UUID accountId) throws IOException {
        ProcessFileLock lock = storage.acquireRemoteMutationLock(accountId);
        return lock::close;
    }
    public boolean cachedContentMatches(URI texture, String expected) throws IOException {
        Path cached = textures.cachePath(texture);
        return Files.isRegularFile(cached) && expected.equals(sha256(cached));
    }
    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }

}
