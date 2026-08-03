package com.naocraftlab.skins.core.storage;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;


public record StorageLayout(
        Path root,
        Path accounts,
        Path assetsSha256,
        Path textureCache,
        Path locks,
        Path backups) {

    public StorageLayout {
        root = normalized(root, "root");
        accounts = normalized(accounts, "accounts");
        assetsSha256 = normalized(assetsSha256, "assetsSha256");
        textureCache = normalized(textureCache, "textureCache");
        locks = normalized(locks, "locks");
        backups = normalized(backups, "backups");
    }

    public static StorageLayout at(Path root) {
        Path normalized = normalized(root, "root");
        return new StorageLayout(
                normalized,
                normalized.resolve("accounts"),
                normalized.resolve("assets").resolve("sha256"),
                normalized.resolve("cache").resolve("textures"),
                normalized.resolve("locks"),
                normalized.resolve("backups"));
    }

    public Path accountState(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return accounts.resolve(accountId.toString()).resolve("state-v1.json");
    }

    public Path accountAppearance(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return accounts.resolve(accountId.toString()).resolve("appearance-v1.json");
    }

    public Path accountUiPreferences(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return accounts.resolve(accountId.toString()).resolve("ui-v1.json");
    }

    public Path accountOwnedCapes(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return accounts.resolve(accountId.toString()).resolve("capes-v1.json");
    }

    public Path accountBackup(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return backups.resolve(accountId + "-state-v1.json");
    }

    public Path accountLock(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return locks.resolve("state-" + accountId + ".lock");
    }

    public Path mutationLock(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return locks.resolve("mutation-" + accountId + ".lock");
    }

    public Path intentSequence() {
        return root.resolve("intent-sequence-v1");
    }

    public Path intentSequenceLock() {
        return locks.resolve("intent-sequence.lock");
    }

    public Path identityRegistry() {
        return root.resolve("identities-v1.json");
    }

    public Path identityRegistryLock() {
        return locks.resolve("identities.lock");
    }

    public Path assetLock(String sha256) {
        Objects.requireNonNull(sha256, "sha256");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 is invalid");
        }
        return locks.resolve("asset-" + sha256 + ".lock");
    }

    public Path textureLock(String cacheKey) {
        Objects.requireNonNull(cacheKey, "cacheKey");
        if (!cacheKey.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("texture cache key is invalid");
        }
        return locks.resolve("texture-" + cacheKey + ".lock");
    }

    private static Path normalized(Path value, String name) {
        return Objects.requireNonNull(value, name).toAbsolutePath().normalize();
    }
}
