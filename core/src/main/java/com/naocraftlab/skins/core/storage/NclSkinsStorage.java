package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.core.model.AccountAppearanceState;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.png.PngInfo;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;


public final class NclSkinsStorage {
    private static final long MAX_STATE_BYTES = 4L * 1024L * 1024L;
    private static final byte[] PREFLIGHT_BYTES = new byte[] {0x4e, 0x43, 0x4c, 0x53};
    private static final FileAttribute<?> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

    private final StorageLayout layout;
    private final PngValidator pngValidator;
    private final Clock clock;
    private final InterProcessLockManager lockManager = new InterProcessLockManager();
    private final StoragePreflight storagePreflight;
    private final AccountStateJson stateJson = new AccountStateJson();
    private final AccountAppearanceStateJson appearanceStateJson = new AccountAppearanceStateJson();
    private final AccountUiPreferencesJson uiPreferencesJson = new AccountUiPreferencesJson();
    private final OwnedCapeInventoryJson ownedCapeInventoryJson = new OwnedCapeInventoryJson();
    private volatile StorageInitialization initialization;

    public NclSkinsStorage() {
        this(
                defaultRoot(),
                new PngValidator(),
                Clock.systemUTC());
    }

    public NclSkinsStorage(
            Path root,
            PngValidator pngValidator,
            Clock clock) {
        this(root, pngValidator, clock, NclSkinsStorage::verifyStorageSemantics);
    }

    NclSkinsStorage(
            Path root,
            PngValidator pngValidator,
            Clock clock,
            StoragePreflight storagePreflight) {
        this.layout = StorageLayout.at(root);
        this.pngValidator = Objects.requireNonNull(pngValidator, "pngValidator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.storagePreflight = Objects.requireNonNull(storagePreflight, "storagePreflight");
    }

    public StorageLayout layout() {
        return layout;
    }


    public StorageInitialization initialize() throws IOException {
        StorageInitialization current = initialization;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = initialization;
            if (current == null) {
                try {
                    createDirectories(layout.root());
                    createDirectories(layout.accounts());
                    createDirectories(layout.assetsSha256());
                    createDirectories(layout.textureCache());
                    createDirectories(layout.locks());
                    createDirectories(layout.backups());
                    storagePreflight.verify(layout, lockManager);
                    current = new StorageInitialization(layout.root(), List.of());
                    initialization = current;
                } catch (StorageAccessException failure) {
                    throw failure;
                } catch (IOException | RuntimeException failure) {
                    throw new StorageAccessException(layout.root(), failure);
                }
            }
        }
        return current;
    }

    @SuppressWarnings("try")
    private static void verifyStorageSemantics(
            StorageLayout layout,
            InterProcessLockManager lockManager) throws IOException {
        String nonce = UUID.randomUUID().toString();
        Path writeProbe = layout.root().resolve(".storage-preflight-" + nonce + ".tmp");
        Path moveProbe = layout.root().resolve(".storage-preflight-" + nonce + ".moved");
        Throwable primaryFailure = null;
        IOException cleanupFailure = null;
        try {
            try (FileChannel channel = FileChannel.open(
                    writeProbe,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(PREFLIGHT_BYTES);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            if (!java.util.Arrays.equals(PREFLIGHT_BYTES, Files.readAllBytes(writeProbe))) {
                throw new IOException("Storage preflight could not read back the bytes it wrote");
            }
            Files.move(writeProbe, moveProbe, StandardCopyOption.ATOMIC_MOVE);
            if (!java.util.Arrays.equals(PREFLIGHT_BYTES, Files.readAllBytes(moveProbe))) {
                throw new IOException("Storage preflight atomic move changed the probe contents");
            }
            try (ProcessFileLock ignored =
                    lockManager.acquire(layout.locks().resolve("storage-preflight.lock"))) {

            }
        } catch (IOException | RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                Files.deleteIfExists(writeProbe);
            } catch (IOException failure) {
                cleanupFailure = failure;
            }
            try {
                Files.deleteIfExists(moveProbe);
            } catch (IOException failure) {
                if (cleanupFailure == null) {
                    cleanupFailure = failure;
                } else {
                    cleanupFailure.addSuppressed(failure);
                }
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static void createDirectories(Path path) throws IOException {
        try {
            Files.createDirectories(path, OWNER_ONLY_DIRECTORY);
        } catch (UnsupportedOperationException unsupportedAttributes) {
            Files.createDirectories(path);
        }
    }

    @FunctionalInterface
    interface StoragePreflight {
        void verify(StorageLayout layout, InterProcessLockManager lockManager) throws IOException;
    }

    @SuppressWarnings("try")
    public AccountState loadOrCreateAccount(UUID accountId) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            Path statePath = layout.accountState(accountId);
            Path backupPath = layout.accountBackup(accountId);
            if (!Files.exists(statePath) && !Files.exists(backupPath)) {
                if (!Files.isDirectory(layout.accounts())) {
                    throw new StorageException(
                            StorageException.Code.INVALID_STATE,
                            "Account storage directory is unavailable");
                }
                return AccountState.empty(accountId, clock.instant());
            }
            return loadAccountLocked(accountId);
        }
    }


    @SuppressWarnings("try")
    public AccountState updateAccount(UUID accountId, UnaryOperator<AccountState> update) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(update, "update");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            Path statePath = layout.accountState(accountId);
            Path backupPath = layout.accountBackup(accountId);
            AccountState current = !Files.exists(statePath) && !Files.exists(backupPath)
                    ? AccountState.empty(accountId, clock.instant())
                    : loadAccountLocked(accountId);
            AccountState replacement = Objects.requireNonNull(update.apply(current), "update result");
            if (!replacement.accountId().equals(accountId)) {
                throw new IllegalArgumentException("Account update changed the Minecraft UUID");
            }
            saveAccountLocked(replacement);
            return replacement;
        }
    }


    @SuppressWarnings("try")
    public AccountAppearanceState loadAppearance(UUID accountId) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            return loadAppearanceLocked(accountId);
        }
    }


    @SuppressWarnings("try")
    public AccountAppearanceState updateAppearance(
            UUID accountId, UnaryOperator<AccountAppearanceState> update) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(update, "update");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            AccountAppearanceState current = loadAppearanceLocked(accountId);
            AccountAppearanceState replacement = Objects.requireNonNull(update.apply(current), "update result");
            if (!accountId.equals(replacement.accountId())) {
                throw new IllegalArgumentException("Appearance update changed the account UUID");
            }
            AtomicFileWriter.replace(layout.accountAppearance(accountId), appearanceStateJson.encode(replacement));
            return replacement;
        }
    }


    @SuppressWarnings("try")
    public AccountAppearanceState updateAppearanceIntent(
            UUID accountId,
            BiFunction<AccountAppearanceState, Long, AccountAppearanceState> update) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(update, "update");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            AccountAppearanceState current = loadAppearanceLocked(accountId);
            if (current.intentRevision() == Long.MAX_VALUE) {
                throw new StorageException(
                        StorageException.Code.INVALID_STATE,
                        "Appearance intent revision is exhausted");
            }
            long nextRevision = current.intentRevision() + 1;
            AccountAppearanceState replacement = Objects.requireNonNull(
                    update.apply(current, nextRevision),
                    "update result");
            if (!accountId.equals(replacement.accountId())) {
                throw new IllegalArgumentException("Appearance update changed the account UUID");
            }
            if (replacement.intentRevision() != nextRevision) {
                throw new IllegalArgumentException("Appearance update did not use the allocated revision");
            }
            AtomicFileWriter.replace(layout.accountAppearance(accountId), appearanceStateJson.encode(replacement));
            return replacement;
        }
    }


    @SuppressWarnings("try")
    public AppearanceIntentUpdate updateAppearanceIntentIfCurrent(
            UUID accountId,
            long expectedRevision,
            AppearanceSyncStatus expectedStatus,
            BiFunction<AccountAppearanceState, Long, AccountAppearanceState> update) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(expectedStatus, "expectedStatus");
        Objects.requireNonNull(update, "update");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            AccountAppearanceState current = loadAppearanceLocked(accountId);
            if (current.intentRevision() != expectedRevision || current.syncStatus() != expectedStatus) {
                return new AppearanceIntentUpdate(current, false);
            }
            if (current.intentRevision() == Long.MAX_VALUE) {
                throw new StorageException(
                        StorageException.Code.INVALID_STATE,
                        "Appearance intent revision is exhausted");
            }
            long nextRevision = current.intentRevision() + 1;
            AccountAppearanceState replacement = Objects.requireNonNull(
                    update.apply(current, nextRevision),
                    "update result");
            if (!accountId.equals(replacement.accountId())) {
                throw new IllegalArgumentException("Appearance update changed the account UUID");
            }
            if (replacement.intentRevision() != nextRevision) {
                throw new IllegalArgumentException("Appearance update did not use the allocated revision");
            }
            AtomicFileWriter.replace(layout.accountAppearance(accountId), appearanceStateJson.encode(replacement));
            return new AppearanceIntentUpdate(replacement, true);
        }
    }


    public record AppearanceIntentUpdate(AccountAppearanceState state, boolean updated) {
        public AppearanceIntentUpdate {
            Objects.requireNonNull(state, "state");
        }
    }


    @SuppressWarnings("try")
    public ActivePresetAppearanceIntentUpdate updateAppearanceIntentIfPresetActive(
            UUID accountId,
            UUID editedPresetId,
            AppearanceIntentFromAccount update) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(editedPresetId, "editedPresetId");
        Objects.requireNonNull(update, "update");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            Path statePath = layout.accountState(accountId);
            Path backupPath = layout.accountBackup(accountId);
            AccountState account = !Files.exists(statePath) && !Files.exists(backupPath)
                    ? AccountState.empty(accountId, clock.instant())
                    : loadAccountLocked(accountId);
            AccountAppearanceState current = loadAppearanceLocked(accountId);
            boolean presetExists = account.presets().stream()
                    .anyMatch(preset -> preset.id().equals(editedPresetId));
            if (!presetExists || !editedPresetId.equals(current.activePresetId())) {
                return new ActivePresetAppearanceIntentUpdate(account, current, false);
            }
            if (current.intentRevision() == Long.MAX_VALUE) {
                throw new StorageException(
                        StorageException.Code.INVALID_STATE,
                        "Appearance intent revision is exhausted");
            }
            long nextRevision = current.intentRevision() + 1;
            AccountAppearanceState replacement = Objects.requireNonNull(
                    update.apply(account, current, nextRevision), "update result");
            if (!accountId.equals(replacement.accountId())) {
                throw new IllegalArgumentException("Appearance update changed the account UUID");
            }
            if (replacement.intentRevision() != nextRevision) {
                throw new IllegalArgumentException("Appearance update did not use the allocated revision");
            }
            AtomicFileWriter.replace(
                    layout.accountAppearance(accountId), appearanceStateJson.encode(replacement));
            return new ActivePresetAppearanceIntentUpdate(account, replacement, true);
        }
    }

    @FunctionalInterface
    public interface AppearanceIntentFromAccount {
        AccountAppearanceState apply(
                AccountState account,
                AccountAppearanceState currentAppearance,
                long nextRevision);
    }


    public record ActivePresetAppearanceIntentUpdate(
            AccountState account, AccountAppearanceState state, boolean updated) {
        public ActivePresetAppearanceIntentUpdate {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(state, "state");
            if (!account.accountId().equals(state.accountId())) {
                throw new IllegalArgumentException("Account and appearance UUIDs differ");
            }
        }
    }


    @SuppressWarnings("try")
    public AccountAppearanceState updateAppearanceIntentIfRevisionAndPreset(
            UUID accountId,
            long expectedRevision,
            UUID expectedActivePresetId,
            BiFunction<AccountAppearanceState, Long, AccountAppearanceState> update) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(expectedActivePresetId, "expectedActivePresetId");
        Objects.requireNonNull(update, "update");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            AccountAppearanceState current = loadAppearanceLocked(accountId);
            if (current.intentRevision() != expectedRevision
                    || !expectedActivePresetId.equals(current.activePresetId())) {
                return current;
            }
            if (current.intentRevision() == Long.MAX_VALUE) {
                throw new StorageException(
                        StorageException.Code.INVALID_STATE,
                        "Appearance intent revision is exhausted");
            }
            long nextRevision = current.intentRevision() + 1;
            AccountAppearanceState replacement = Objects.requireNonNull(
                    update.apply(current, nextRevision), "update result");
            if (!accountId.equals(replacement.accountId())) {
                throw new IllegalArgumentException("Appearance update changed the account UUID");
            }
            if (replacement.intentRevision() != nextRevision) {
                throw new IllegalArgumentException("Appearance update did not use the allocated revision");
            }
            AtomicFileWriter.replace(
                    layout.accountAppearance(accountId), appearanceStateJson.encode(replacement));
            return replacement;
        }
    }


    @SuppressWarnings("try")
    public AccountUiPreferencesResult loadUiPreferences(UUID accountId) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            return loadUiPreferencesLocked(accountId);
        }
    }


    @SuppressWarnings("try")
    public OwnedCapeInventory loadOwnedCapes(UUID accountId) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            Path path = layout.accountOwnedCapes(accountId);
            if (!Files.exists(path)) {
                return OwnedCapeInventory.empty(accountId, clock.instant());
            }
            OwnedCapeInventory inventory = ownedCapeInventoryJson.decode(readBoundedState(path));
            if (!accountId.equals(inventory.accountId())) {
                throw new StorageException(
                        StorageException.Code.INVALID_STATE,
                        "Owned cape inventory UUID does not match its directory");
            }
            return inventory;
        }
    }


    @SuppressWarnings("try")
    public OwnedCapeInventory saveOwnedCapes(OwnedCapeInventory inventory) throws IOException {
        Objects.requireNonNull(inventory, "inventory");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(inventory.accountId()))) {
            AtomicFileWriter.replace(
                    layout.accountOwnedCapes(inventory.accountId()),
                    ownedCapeInventoryJson.encode(inventory));
            return inventory;
        }
    }


    @SuppressWarnings("try")
    public OwnedCapeInventory updateOwnedCapes(
            UUID accountId, UnaryOperator<OwnedCapeInventory> update) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(update, "update");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            Path path = layout.accountOwnedCapes(accountId);
            OwnedCapeInventory current = Files.exists(path)
                    ? ownedCapeInventoryJson.decode(readBoundedState(path))
                    : OwnedCapeInventory.empty(accountId, clock.instant());
            OwnedCapeInventory replacement = Objects.requireNonNull(update.apply(current), "update result");
            if (!accountId.equals(replacement.accountId())) {
                throw new IllegalArgumentException("Owned cape update changed the account UUID");
            }
            AtomicFileWriter.replace(path, ownedCapeInventoryJson.encode(replacement));
            return replacement;
        }
    }


    @SuppressWarnings("try")
    public AccountUiPreferencesResult setSelectedAddSourceTab(
            UUID accountId, AddSourceTab selectedTab) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(selectedTab, "selectedTab");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            AccountUiPreferencesResult loaded = loadUiPreferencesLocked(accountId);
            AccountUiPreferences replacement = loaded.preferences().withSelectedAddSourceTab(selectedTab);
            saveUiPreferencesLocked(replacement);
            return new AccountUiPreferencesResult(replacement, loaded.warnings());
        }
    }


    @SuppressWarnings("try")
    public AccountUiPreferencesResult setCollectionCollapsed(
            UUID accountId, String collectionId, boolean collapsed) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(collectionId, "collectionId");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            AccountUiPreferencesResult loaded = loadUiPreferencesLocked(accountId);
            AccountUiPreferences replacement = loaded.preferences()
                    .withCollectionCollapsed(collectionId, collapsed);
            saveUiPreferencesLocked(replacement);
            return new AccountUiPreferencesResult(replacement, loaded.warnings());
        }
    }


    @SuppressWarnings("try")
    public AccountUiPreferencesResult setPreferredSkinVariant(
            UUID accountId, SkinVariant preferredVariant) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(preferredVariant, "preferredVariant");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            AccountUiPreferencesResult loaded = loadUiPreferencesLocked(accountId);
            AccountUiPreferences replacement = loaded.preferences()
                    .withPreferredSkinVariant(preferredVariant);
            saveUiPreferencesLocked(replacement);
            return new AccountUiPreferencesResult(replacement, loaded.warnings());
        }
    }


    public IdentityResolution observeIdentity(UUID profileId, String profileName) throws IOException {
        Objects.requireNonNull(profileId, "profileId");
        return new IdentityResolution(profileId, profileId, normalizeProfileName(profileName));
    }


    public VerifiedIdentityPlan planVerifiedIdentity(UUID profileId, String profileName) throws IOException {
        Objects.requireNonNull(profileId, "profileId");
        return new VerifiedIdentityPlan(profileId, normalizeProfileName(profileName), Set.of(), false);
    }


    public void completeVerifiedIdentity(VerifiedIdentityPlan plan, String displayName) throws IOException {
        Objects.requireNonNull(plan, "plan");
    }

    @SuppressWarnings("try")
    public StoredAsset storeAsset(byte[] pngBytes) throws IOException, PngValidationException {
        Objects.requireNonNull(pngBytes, "pngBytes");
        ensureInitialized();
        byte[] normalizedPng = pngValidator.normalizeSkin(pngBytes);
        PngInfo info = pngValidator.validate(normalizedPng);
        String sha256 = sha256(normalizedPng);
        Path path = assetPath(sha256);
        boolean created;
        try (ProcessFileLock ignored = lockManager.acquire(layout.assetLock(sha256))) {
            created = AtomicFileWriter.createImmutable(path, normalizedPng);
            if (!created && !sha256.equals(sha256(readBoundedAsset(path)))) {
                throw new StorageException(
                        StorageException.Code.ASSET_INTEGRITY_FAILURE,
                        "Existing content-addressed skin asset failed integrity verification");
            }
        }
        return new StoredAsset(sha256, path, info, !created);
    }

    public byte[] readAsset(String sha256) throws IOException, PngValidationException {
        Path path = assetPath(sha256);
        byte[] bytes = readBoundedAsset(path);
        if (!sha256.equals(sha256(bytes))) {
            throw new StorageException(
                    StorageException.Code.ASSET_INTEGRITY_FAILURE,
                    "Content-addressed skin asset failed integrity verification");
        }
        pngValidator.validate(bytes);
        return bytes;
    }


    public ProcessFileLock acquireRemoteMutationLock(UUID accountId) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        ensureInitialized();
        return lockManager.acquire(layout.mutationLock(accountId));
    }


    public ProcessFileLock acquireTextureCacheLock(String cacheKey) throws IOException {
        Objects.requireNonNull(cacheKey, "cacheKey");
        ensureInitialized();
        return lockManager.acquire(layout.textureLock(cacheKey));
    }

    public Path assetPath(String sha256) {
        Objects.requireNonNull(sha256, "sha256");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must contain 64 lowercase hexadecimal characters");
        }
        return layout.assetsSha256().resolve(sha256 + ".png");
    }

    private AccountState loadAccountLocked(UUID accountId) throws IOException {
        Path statePath = layout.accountState(accountId);
        Path backupPath = layout.accountBackup(accountId);
        StorageException primaryFailure = null;
        if (Files.exists(statePath)) {
            try {
                AccountStateJson.Decoded decoded = stateJson.decode(readBoundedState(statePath));
                validateAccountId(accountId, decoded.state());
                if (decoded.migrated()) {
                    AtomicFileWriter.replace(statePath, stateJson.encode(decoded.state()));
                }
                return decoded.state();
            } catch (StorageException exception) {
                primaryFailure = exception;
            }
        }
        if (Files.exists(backupPath)) {
            AccountStateJson.Decoded decoded = stateJson.decode(readBoundedState(backupPath));
            validateAccountId(accountId, decoded.state());
            AtomicFileWriter.replace(statePath, stateJson.encode(decoded.state()));
            return decoded.state();
        }
        if (primaryFailure != null) {
            throw primaryFailure;
        }
        throw new StorageException(StorageException.Code.INVALID_STATE, "Account state does not exist");
    }

    private AccountAppearanceState loadAppearanceLocked(UUID accountId) throws IOException {
        Path path = layout.accountAppearance(accountId);
        if (!Files.exists(path)) {
            return AccountAppearanceState.empty(accountId, clock.instant());
        }
        byte[] bytes = readBoundedState(path);
        AccountAppearanceStateJson.Decoded decoded = appearanceStateJson.decode(bytes);
        AccountAppearanceState state = decoded.state();
        if (!accountId.equals(state.accountId())) {
            throw new StorageException(
                    StorageException.Code.INVALID_STATE,
                    "Appearance state UUID does not match its directory");
        }
        if (decoded.migrated()) {
            AtomicFileWriter.replace(path, appearanceStateJson.encode(state));
        }
        return state;
    }

    private AccountUiPreferencesResult loadUiPreferencesLocked(UUID accountId) throws IOException {
        Path path = layout.accountUiPreferences(accountId);
        if (!Files.exists(path)) {
            return new AccountUiPreferencesResult(AccountUiPreferences.defaults(accountId), List.of());
        }
        try {
            AccountUiPreferences preferences = uiPreferencesJson.decode(readBoundedState(path));
            if (!accountId.equals(preferences.accountId())) {
                throw new StorageException(
                        StorageException.Code.INVALID_STATE,
                        "UI preferences UUID does not match their directory");
            }
            return new AccountUiPreferencesResult(preferences, List.of());
        } catch (StorageException malformed) {
            return new AccountUiPreferencesResult(
                    AccountUiPreferences.defaults(accountId),
                    List.of(new StorageWarning(
                            "UI preferences could not be read; default choices are being used.")));
        }
    }

    private void saveUiPreferencesLocked(AccountUiPreferences preferences) throws IOException {
        AtomicFileWriter.replace(
                layout.accountUiPreferences(preferences.accountId()),
                uiPreferencesJson.encode(preferences));
    }

    private static String normalizeProfileName(String profileName) {
        if (profileName == null) {
            return null;
        }
        String trimmed = profileName.trim();
        if (!trimmed.matches("[A-Za-z0-9_]{1,16}")) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private void saveAccountLocked(AccountState state) throws IOException {
        UUID accountId = state.accountId();
        Path statePath = layout.accountState(accountId);
        if (Files.exists(statePath)) {
            byte[] existing = readBoundedState(statePath);
            try {
                AccountStateJson.Decoded decoded = stateJson.decode(existing);
                if (decoded.state().accountId().equals(accountId)) {
                    AtomicFileWriter.replace(layout.accountBackup(accountId), existing);
                }
            } catch (StorageException invalidExistingState) {

            }
        }
        AtomicFileWriter.replace(statePath, stateJson.encode(state));
    }

    private static void validateAccountId(UUID expected, AccountState state) throws StorageException {
        if (!expected.equals(state.accountId())) {
            throw new StorageException(
                    StorageException.Code.INVALID_STATE,
                    "Account state UUID does not match its directory");
        }
    }

    private byte[] readBoundedAsset(Path path) throws IOException {
        try (java.io.InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(pngValidator.maxBytes() + 1);
            if (bytes.length <= pngValidator.maxBytes()) {
                return bytes;
            }
            throw new StorageException(
                    StorageException.Code.ASSET_INTEGRITY_FAILURE,
                    "Stored skin asset exceeds the configured size limit");
        }
    }

    private static byte[] readBoundedState(Path path) throws IOException {
        try (java.io.InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes((int) MAX_STATE_BYTES + 1);
            if (bytes.length <= MAX_STATE_BYTES) {
                return bytes;
            }
            throw new StorageException(StorageException.Code.INVALID_STATE, "Account state exceeds the size limit");
        }
    }

    private void ensureInitialized() throws IOException {
        initialize();
    }

    private static Path defaultRoot() {
        return defaultRoot(
                System.getProperty("os.name", ""),
                System.getProperty("user.home"),
                System.getenv("APPDATA"),
                System.getenv("XDG_DATA_HOME"));
    }

    static Path defaultRoot(
            String osName,
            String userHome,
            String appData,
            String xdgDataHome) {
        return PlatformDataRoot.resolve(osName, userHome, appData, xdgDataHome);
    }

    public record IdentityResolution(
            UUID observedProfileId,
            UUID canonicalAccountId,
            String normalizedName) {
        public IdentityResolution {
            Objects.requireNonNull(observedProfileId, "observedProfileId");
            Objects.requireNonNull(canonicalAccountId, "canonicalAccountId");
        }
    }

    public record VerifiedIdentityPlan(
            UUID verifiedAccountId,
            String normalizedName,
            Set<UUID> sourceAccountIds,
            boolean conflict) {
        public VerifiedIdentityPlan {
            Objects.requireNonNull(verifiedAccountId, "verifiedAccountId");
            sourceAccountIds = Set.copyOf(Objects.requireNonNull(sourceAccountIds, "sourceAccountIds"));
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }
}
