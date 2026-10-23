package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.core.model.AccountAppearanceState;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountUiPreferences;
import com.naocraftlab.skins.core.model.AddSourceTab;
import com.naocraftlab.skins.core.model.AppearanceSyncStatus;
import com.naocraftlab.skins.core.model.EditorTab;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngInfo;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.png.CapeAssetDecoder;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;

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
        return updateAccount(accountId, update, current -> {});
    }

    @SuppressWarnings("try")
    public AccountState updateAccount(UUID accountId, UnaryOperator<AccountState> update,
            com.naocraftlab.skins.core.service.AccountWriteGuard guard) throws IOException {
        return updateAccount(accountId, update, guard, () -> true);
    }

    @SuppressWarnings("try")
    public AccountState updateAccount(UUID accountId, UnaryOperator<AccountState> update,
            com.naocraftlab.skins.core.service.AccountWriteGuard guard,
            java.util.function.BooleanSupplier stillCurrent) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(update, "update");
        requireCurrent(stillCurrent);
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            Path statePath = layout.accountState(accountId);
            Path backupPath = layout.accountBackup(accountId);
            AccountState current = !Files.exists(statePath) && !Files.exists(backupPath)
                    ? AccountState.empty(accountId, clock.instant())
                    : loadAccountLocked(accountId);
            Objects.requireNonNull(guard, "guard").verify(current);
            AccountState replacement = Objects.requireNonNull(update.apply(current), "update result");
            if (!replacement.accountId().equals(accountId)) {
                throw new IllegalArgumentException("Account update changed the Minecraft UUID");
            }
            guard.verify(current);
            requireCurrent(stillCurrent);
            saveAccountLocked(replacement, stillCurrent);
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


    public AccountAppearanceState updateAppearance(
            UUID accountId, UnaryOperator<AccountAppearanceState> update) throws IOException {
        return updateAppearance(accountId, update, () -> true);
    }

    @SuppressWarnings("try")
    public AccountAppearanceState updateAppearance(
            UUID accountId, UnaryOperator<AccountAppearanceState> update,
            java.util.function.BooleanSupplier stillCurrent) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(stillCurrent, "stillCurrent");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            if (!stillCurrent.getAsBoolean()) throw new IOException("Appearance update is no longer current");
            AccountAppearanceState current = loadAppearanceLocked(accountId);
            AccountAppearanceState replacement = Objects.requireNonNull(update.apply(current), "update result");
            if (!accountId.equals(replacement.accountId())) {
                throw new IllegalArgumentException("Appearance update changed the account UUID");
            }
            if (replacement != current || !Files.exists(layout.accountAppearance(accountId))) {
                AtomicFileWriter.replace(layout.accountAppearance(accountId), encodeAppearanceLocked(accountId, replacement), stillCurrent);
            }
            else if (!stillCurrent.getAsBoolean()) throw new IOException("Appearance update is no longer current");
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
            AtomicFileWriter.replace(layout.accountAppearance(accountId), encodeAppearanceLocked(accountId, replacement));
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
            AtomicFileWriter.replace(layout.accountAppearance(accountId), encodeAppearanceLocked(accountId, replacement));
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
                    layout.accountAppearance(accountId), encodeAppearanceLocked(accountId, replacement));
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
    public AccountAppearanceMutationResult mutateAccountAndAppearance(
            UUID accountId,
            AccountAppearanceMutation mutation) throws IOException {
        return mutateAccountAndAppearance(accountId, mutation, false);
    }

    @SuppressWarnings("try")
    private AccountAppearanceMutationResult mutateAccountAndAppearance(
            UUID accountId, AccountAppearanceMutation mutation, boolean recoverable) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(mutation, "mutation");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            Path statePath = layout.accountState(accountId);
            Path backupPath = layout.accountBackup(accountId);
            AccountState currentAccount = !Files.exists(statePath) && !Files.exists(backupPath)
                    ? AccountState.empty(accountId, clock.instant())
                    : loadAccountLocked(accountId);
            AccountAppearanceState currentAppearance = loadAppearanceLocked(accountId);
            long nextRevision = currentAppearance.intentRevision() == Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : currentAppearance.intentRevision() + 1;
            AccountAppearanceMutationPlan plan = Objects.requireNonNull(
                    mutation.apply(currentAccount, currentAppearance, nextRevision),
                    "mutation result");
            validateAccountAppearanceMutation(
                    accountId, currentAccount, currentAppearance, nextRevision, plan);

            if (recoverable && plan.accountUpdated()) {
                com.google.gson.JsonObject transaction = new com.google.gson.JsonObject();
                transaction.add("account", com.google.gson.JsonParser.parseString(new String(stateJson.encode(plan.account()), java.nio.charset.StandardCharsets.UTF_8)));
                transaction.add("appearance", com.google.gson.JsonParser.parseString(new String(encodeAppearanceLocked(accountId, plan.appearance()), java.nio.charset.StandardCharsets.UTF_8)));
                AtomicFileWriter.replace(transactionPath(accountId), transaction.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                recoverAccountTransaction(accountId);
                return new AccountAppearanceMutationResult(plan.account(), plan.appearance(), true, true);
            }
            if (plan.appearanceUpdated()) {
                AtomicFileWriter.replace(
                        layout.accountAppearance(accountId),
                        encodeAppearanceLocked(accountId, plan.appearance()));
            }
            if (plan.accountUpdated()) {
                saveAccountLocked(plan.account());
            }
            return new AccountAppearanceMutationResult(
                    plan.account(),
                    plan.appearance(),
                    plan.accountUpdated(),
                    plan.appearanceUpdated());
        }
    }


    private static void validateAccountAppearanceMutation(
            UUID accountId,
            AccountState currentAccount,
            AccountAppearanceState currentAppearance,
            long nextRevision,
            AccountAppearanceMutationPlan plan) throws StorageException {
        if (!accountId.equals(plan.account().accountId())
                || !accountId.equals(plan.appearance().accountId())) {
            throw new IllegalArgumentException("Account/appearance mutation changed the account UUID");
        }
        if (!plan.accountUpdated() && !plan.account().equals(currentAccount)) {
            throw new IllegalArgumentException("Account mutation changed state without requesting persistence");
        }
        if (!plan.appearanceUpdated() && !plan.appearance().equals(currentAppearance)) {
            throw new IllegalArgumentException("Appearance mutation changed state without requesting persistence");
        }
        if (plan.appearanceUpdated()) {
            if (currentAppearance.intentRevision() == Long.MAX_VALUE) {
                throw new StorageException(
                        StorageException.Code.INVALID_STATE,
                        "Appearance intent revision is exhausted");
            }
            if (plan.appearance().intentRevision() != nextRevision) {
                throw new IllegalArgumentException(
                        "Appearance mutation did not use the allocated revision");
            }
        }
    }


    @FunctionalInterface
    public interface AccountAppearanceMutation {
        AccountAppearanceMutationPlan apply(
                AccountState account,
                AccountAppearanceState appearance,
                long nextIntentRevision);
    }


    public record AccountAppearanceMutationPlan(
            AccountState account,
            AccountAppearanceState appearance,
            boolean accountUpdated,
            boolean appearanceUpdated) {
        public AccountAppearanceMutationPlan {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(appearance, "appearance");
        }

        public static AccountAppearanceMutationPlan accountOnly(
                AccountState account,
                AccountAppearanceState appearance) {
            return new AccountAppearanceMutationPlan(account, appearance, true, false);
        }

        public static AccountAppearanceMutationPlan appearanceOnly(
                AccountState account,
                AccountAppearanceState appearance) {
            return new AccountAppearanceMutationPlan(account, appearance, false, true);
        }

        public static AccountAppearanceMutationPlan both(
                AccountState account,
                AccountAppearanceState appearance) {
            return new AccountAppearanceMutationPlan(account, appearance, true, true);
        }
    }


    public record AccountAppearanceMutationResult(
            AccountState account,
            AccountAppearanceState appearance,
            boolean accountUpdated,
            boolean appearanceUpdated) {
        public AccountAppearanceMutationResult {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(appearance, "appearance");
            if (!account.accountId().equals(appearance.accountId())) {
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
                    layout.accountAppearance(accountId), encodeAppearanceLocked(accountId, replacement));
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
    public AccountUiPreferencesResult setSelectedProvidersTab(
            UUID accountId, com.naocraftlab.skins.core.provider.AppearanceProviders.Component selectedTab) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(selectedTab, "selectedTab");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            AccountUiPreferencesResult loaded = loadUiPreferencesLocked(accountId);
            AccountUiPreferences replacement = loaded.preferences().withSelectedProvidersTab(selectedTab);
            saveUiPreferencesLocked(replacement);
            return new AccountUiPreferencesResult(replacement, loaded.warnings());
        }
    }


    @SuppressWarnings("try")
    public AccountUiPreferencesResult setSelectedEditorTab(
            UUID accountId, EditorTab selectedTab) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(selectedTab, "selectedTab");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            AccountUiPreferencesResult loaded = loadUiPreferencesLocked(accountId);
            AccountUiPreferences replacement = loaded.preferences().withSelectedEditorTab(selectedTab);
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
    public AccountUiPreferencesResult replaceCollapsedCollectionIds(
            UUID accountId, Set<String> collectionIds) throws IOException {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(collectionIds, "collectionIds");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            AccountUiPreferencesResult loaded = loadUiPreferencesLocked(accountId);
            AccountUiPreferences replacement = loaded.preferences()
                    .withCollapsedCollectionIds(collectionIds);
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


    @SuppressWarnings("try")
    public void setCollapsedCapeCollections(UUID accountId, java.util.Set<String> values) throws IOException {
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.accountLock(accountId))) {
            saveUiPreferencesLocked(loadUiPreferencesLocked(accountId).preferences().withCollapsedCapeCollections(values));
        }
    }

    public void completeVerifiedIdentity(VerifiedIdentityPlan plan, String displayName) throws IOException {
        Objects.requireNonNull(plan, "plan");
    }

    @SuppressWarnings("try")
    public StoredAsset storeAsset(byte[] pngBytes) throws IOException, PngValidationException {
        return storeAsset(pngBytes, () -> true);
    }

    @SuppressWarnings("try")
    public StoredAsset storeAsset(byte[] pngBytes, java.util.function.BooleanSupplier stillCurrent)
            throws IOException, PngValidationException {
        Objects.requireNonNull(pngBytes, "pngBytes");
        requireCurrent(stillCurrent);
        ensureInitialized();
        byte[] normalizedPng = pngValidator.normalizeSkin(pngBytes);
        PngInfo info = pngValidator.validate(normalizedPng);
        String sha256 = sha256(normalizedPng);
        Path path = assetPath(sha256);
        boolean created;
        try (ProcessFileLock ignored = lockManager.acquire(layout.assetLock(sha256))) {
            requireCurrent(stillCurrent);
            created = AtomicFileWriter.createImmutable(path, normalizedPng, stillCurrent);
            if (!created && !sha256.equals(sha256(path))) {
                throw new StorageException(
                        StorageException.Code.ASSET_INTEGRITY_FAILURE,
                        "Existing content-addressed skin asset failed integrity verification");
            }
        }
        return new StoredAsset(sha256, path, info, !created);
    }

    public com.naocraftlab.skins.core.model.PersonalCapeEntry importCape(
            UUID accountId, String name, byte[] bytes) throws IOException, PngValidationException {
        return importCape(accountId, name, bytes, current -> {});
    }

    public com.naocraftlab.skins.core.model.PersonalCapeEntry importCape(
            UUID accountId, String name, byte[] bytes,
            com.naocraftlab.skins.core.service.AccountWriteGuard guard) throws IOException, PngValidationException {
        PngValidator.CapePng png = pngValidator.projectImportedCape(bytes);
        String hash = sha256(png.bytes());
        Path asset = capeAssetPath(accountId, hash);
        ensureInitialized();
        com.naocraftlab.skins.core.model.PersonalCapeEntry[] selected = new com.naocraftlab.skins.core.model.PersonalCapeEntry[1];
        updateAccount(accountId, current -> {
            selected[0] = current.personalCapes().stream().filter(entry -> entry.renderSha256().equals(png.renderSha256()))
                    .findFirst().orElse(null);
            if (selected[0] != null) return current;
            selected[0] = new com.naocraftlab.skins.core.model.PersonalCapeEntry(
                    new com.naocraftlab.skins.core.model.LocalCapeReference(UUID.randomUUID(), hash, png.hasElytra()),
                    png.renderSha256(), name, clock.instant());
            try {
                AtomicFileWriter.createImmutable(asset, png.bytes());
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
            java.util.List<com.naocraftlab.skins.core.model.PersonalCapeEntry> entries = new java.util.ArrayList<>(current.personalCapes());
            entries.add(selected[0]);
            return current.withPersonalCapes(entries);
        }, guard);
        AtomicFileWriter.createImmutable(layout.textureCache().resolve(selected[0].texture().sha256() + ".png"),
                readCapeAsset(accountId, selected[0].texture().sha256()));
        return selected[0];
    }

    public com.naocraftlab.skins.core.model.PersonalCapeEntry importCape(
            UUID accountId, String name, Path encoded) throws IOException, PngValidationException {
        return importCape(accountId, name, encoded, current -> {});
    }

    public com.naocraftlab.skins.core.model.PersonalCapeEntry importCape(
            UUID accountId, String name, Path encoded,
            com.naocraftlab.skins.core.service.AccountWriteGuard guard) throws IOException, PngValidationException {
        Objects.requireNonNull(encoded, "encoded");
        ensureInitialized();
        Path assetDirectory = layout.accountState(accountId).getParent().resolve("cape-assets");
        createDirectories(assetDirectory);
        Path staged = assetDirectory.resolve(".prepared-" + UUID.randomUUID() + ".frames");
        try {
            PreparedCapeAsset prepared = new CapeAssetDecoder().prepare(encoded, staged);
            if (prepared.frameCount() == 1 && prepared.width() <= 128) {
                return importCape(accountId, name, prepared.framePng(0), guard);
            }
            return importPreparedCape(accountId, name, prepared, guard);
        } finally { Files.deleteIfExists(staged); }
    }

    public com.naocraftlab.skins.core.model.PersonalCapeEntry importPreparedCape(
            UUID accountId, String name, PreparedCapeAsset prepared,
            com.naocraftlab.skins.core.service.AccountWriteGuard guard) throws IOException {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(guard, "guard");
        ensureInitialized();
        byte[] descriptor = prepared.descriptorBytes();
        String descriptorHash = sha256(descriptor);
        Path directory = layout.accountState(accountId).getParent().resolve("cape-assets");
        Path backing = directory.resolve(descriptorHash + ".frames");
        Path descriptorPath = directory.resolve(descriptorHash + ".cape");
        com.naocraftlab.skins.core.png.CapeAssetDecoder.restore(descriptor, prepared.backing());
        com.naocraftlab.skins.core.model.PersonalCapeEntry[] selected = new com.naocraftlab.skins.core.model.PersonalCapeEntry[1];
        try {
            updateAccount(accountId, current -> {
                selected[0] = current.personalCapes().stream()
                        .filter(entry -> entry.renderSha256().equals(prepared.visualSha256()))
                        .findFirst().orElse(null);
                if (selected[0] != null) return current;
                try {
                    AtomicFileWriter.createImmutable(backing, prepared.backing(), prepared.backingBytes());
                    com.naocraftlab.skins.core.png.CapeAssetDecoder.restore(descriptor, backing);
                    AtomicFileWriter.createImmutable(descriptorPath, descriptor);
                    readPreparedCapeAsset(accountId, descriptorHash).orElseThrow(
                            () -> new IOException("Cape descriptor was not published"));
                } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                selected[0] = new com.naocraftlab.skins.core.model.PersonalCapeEntry(
                        new com.naocraftlab.skins.core.model.LocalCapeReference(
                                UUID.randomUUID(), descriptorHash, prepared.hasElytra()),
                        prepared.visualSha256(), name, clock.instant());
                java.util.List<com.naocraftlab.skins.core.model.PersonalCapeEntry> entries =
                        new java.util.ArrayList<>(current.personalCapes());
                entries.add(selected[0]);
                return current.withPersonalCapes(entries);
            }, guard);
        } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
        return selected[0];
    }

    public java.util.Optional<PreparedCapeAsset> readPreparedCapeAsset(UUID accountId, String hash)
            throws IOException {
        if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid cape hash");
        Path directory = layout.accountState(accountId).getParent().resolve("cape-assets");
        Path descriptorPath = directory.resolve(hash + ".cape");
        if (!Files.exists(descriptorPath)) return java.util.Optional.empty();
        byte[] bytes = readCapeDescriptor(descriptorPath);
        if (!hash.equals(sha256(bytes))) {
            throw new StorageException(StorageException.Code.ASSET_INTEGRITY_FAILURE,
                    "Cape descriptor integrity failure", new com.naocraftlab.skins.core.png.CapeIntegrityException("Cape descriptor integrity failure"));
        }
        try {
            return java.util.Optional.of(com.naocraftlab.skins.core.png.CapeAssetDecoder.restore(bytes,
                    directory.resolve(hash + ".frames")));
        } catch (com.naocraftlab.skins.core.png.CapeIntegrityException failure) {
            throw new StorageException(StorageException.Code.ASSET_INTEGRITY_FAILURE,
                    "Cape backing integrity failure", failure);
        }
    }

    public java.util.Optional<PreparedCapeAsset> readPreparedCapeThumbnail(UUID accountId, String hash)
            throws IOException {
        if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid cape hash");
        Path directory = layout.accountState(accountId).getParent().resolve("cape-assets");
        Path descriptorPath = directory.resolve(hash + ".cape");
        if (!Files.exists(descriptorPath)) return java.util.Optional.empty();
        byte[] descriptor = readCapeDescriptor(descriptorPath);
        if (!hash.equals(sha256(descriptor))) throw new StorageException(
                StorageException.Code.ASSET_INTEGRITY_FAILURE, "Cape descriptor integrity failure", new com.naocraftlab.skins.core.png.CapeIntegrityException("Cape descriptor integrity failure"));
        try {
            return java.util.Optional.of(com.naocraftlab.skins.core.png.CapeAssetDecoder.restoreThumbnail(
                    descriptor, directory.resolve(hash + ".frames")));
        } catch (com.naocraftlab.skins.core.png.CapeIntegrityException failure) {
            throw new StorageException(StorageException.Code.ASSET_INTEGRITY_FAILURE,
                    "Cape backing integrity failure", failure);
        }
    }

    @SuppressWarnings("try")
    public String storeObservedPreparedCape(PreparedCapeAsset prepared) throws IOException {
        Objects.requireNonNull(prepared, "prepared");
        ensureInitialized();
        byte[] descriptor = prepared.descriptorBytes();
        com.naocraftlab.skins.core.png.CapeAssetDecoder.restore(descriptor, prepared.backing());
        String key = prepared.visualSha256();
        Path backing = layout.textureCache().resolve(key + ".frames");
        Path description = layout.textureCache().resolve(key + ".cape");
        try (ProcessFileLock ignored = lockManager.acquire(layout.textureLock(key))) {
            AtomicFileWriter.createImmutable(backing, prepared.backing(), prepared.backingBytes());
            com.naocraftlab.skins.core.png.CapeAssetDecoder.restore(descriptor, backing);
            AtomicFileWriter.createImmutable(description, descriptor);
            readObservedPreparedCape(key).orElseThrow(() -> new IOException("Cape cache was not published"));
        }
        return key;
    }

    public java.util.Optional<PreparedCapeAsset> readObservedPreparedCape(String visualSha256) throws IOException {
        if (!visualSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid cape hash");
        Path description = layout.textureCache().resolve(visualSha256 + ".cape");
        if (!Files.exists(description)) return java.util.Optional.empty();
        byte[] descriptor = readCapeDescriptor(description);
        try {
            PreparedCapeAsset asset = com.naocraftlab.skins.core.png.CapeAssetDecoder.restore(descriptor,
                    layout.textureCache().resolve(visualSha256 + ".frames"));
            if (!visualSha256.equals(asset.visualSha256())) throw new com.naocraftlab.skins.core.png.CapeIntegrityException("Cape cache identity mismatch");
            return java.util.Optional.of(asset);
        } catch (com.naocraftlab.skins.core.png.CapeIntegrityException failure) {
            throw new StorageException(StorageException.Code.ASSET_INTEGRITY_FAILURE,
                    "Cape cache integrity failure", failure);
        }
    }

    public java.util.Optional<PreparedCapeAsset> readObservedPreparedCapeThumbnail(String visualSha256) throws IOException {
        if (!visualSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid cape hash");
        Path descriptorPath = layout.textureCache().resolve(visualSha256 + ".cape");
        if (!Files.exists(descriptorPath)) return java.util.Optional.empty();
        byte[] descriptor = readCapeDescriptor(descriptorPath);
        try {
            PreparedCapeAsset asset = com.naocraftlab.skins.core.png.CapeAssetDecoder.restoreThumbnail(
                    descriptor, layout.textureCache().resolve(visualSha256 + ".frames"));
            if (!visualSha256.equals(asset.visualSha256())) throw new com.naocraftlab.skins.core.png.CapeIntegrityException("Cape cache identity mismatch");
            return java.util.Optional.of(asset);
        } catch (com.naocraftlab.skins.core.png.CapeIntegrityException failure) {
            throw new StorageException(StorageException.Code.ASSET_INTEGRITY_FAILURE,
                    "Cape cache integrity failure", failure);
        }
    }

    public java.util.List<com.naocraftlab.skins.core.service.ProviderTextureStore.ObservedPreparedCape>
            listObservedPreparedCapes() throws IOException {
        ensureInitialized();
        Path physicalCache = layout.textureCache().toRealPath();
        java.util.Set<String> keys = new java.util.HashSet<>();
        try (java.nio.file.DirectoryStream<Path> paths = Files.newDirectoryStream(layout.textureCache())) {
            for (Path path : paths) {
                String name = path.getFileName().toString();
                if (Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        && name.matches("[0-9a-f]{64}\\.(cape|frames)")) {
                    keys.add(name.substring(0, 64));
                }
            }
        }
        java.util.List<com.naocraftlab.skins.core.service.ProviderTextureStore.ObservedPreparedCape> entries =
                new java.util.ArrayList<>(keys.size());
        for (String key : keys) {
            Path backing = layout.textureCache().resolve(key + ".frames");
            Path descriptor = layout.textureCache().resolve(key + ".cape");
            if (Files.isSymbolicLink(backing) || Files.isSymbolicLink(descriptor)) continue;
            Path dated = Files.isRegularFile(backing, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    ? backing : descriptor;
            try {
                long size = (Files.isRegularFile(backing, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        ? Files.size(backing) : 0)
                        + (Files.isRegularFile(descriptor, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        ? Files.size(descriptor) : 0);
                entries.add(new com.naocraftlab.skins.core.service.ProviderTextureStore.ObservedPreparedCape(
                        key, physicalCache.resolve(key + ".frames"), size,
                        Files.getLastModifiedTime(dated).toMillis()));
            } catch (java.nio.file.NoSuchFileException concurrentlyRemoved) {
            }
        }
        return java.util.List.copyOf(entries);
    }

    @SuppressWarnings("try")
    public boolean removeObservedPreparedCape(String visualSha256) throws IOException {
        if (!visualSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid cape hash");
        ensureInitialized();
        try (ProcessFileLock ignored = lockManager.acquire(layout.textureLock(visualSha256))) {
            Path descriptor = layout.textureCache().resolve(visualSha256 + ".cape");
            Path backing = layout.textureCache().resolve(visualSha256 + ".frames");
            if (Files.isSymbolicLink(descriptor) || Files.isSymbolicLink(backing)) {
                throw new IOException("Refusing to remove a symlinked cape cache entry");
            }
            if (!Files.exists(backing)) {
                Files.deleteIfExists(descriptor);
                return true;
            }
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(backing,
                    java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)) {
                java.nio.channels.FileLock lease;
                try { lease = channel.tryLock(0, Long.MAX_VALUE, false); }
                catch (java.nio.channels.OverlappingFileLockException pinned) { return false; }
                if (lease == null) return false;
                try (lease) {
                    Files.deleteIfExists(descriptor);
                    Files.deleteIfExists(backing);
                    return true;
                }
            }
        }
    }

    public Path capeAssetPath(UUID accountId, String hash) {
        if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid cape hash");
        return layout.accountState(accountId).getParent().resolve("cape-assets").resolve(hash + ".png");
    }

    public byte[] readCapeAsset(UUID accountId, String hash) throws IOException {
        if (readPreparedCapeAsset(accountId, hash).isPresent()) {
            throw new StorageException(StorageException.Code.ASSET_INTEGRITY_FAILURE,
                    "Animated cape requires a bounded frame reader");
        }
        Path path = capeAssetPath(accountId, hash);
        if (!hash.equals(sha256(path))) throw new StorageException(
                StorageException.Code.ASSET_INTEGRITY_FAILURE, "Cape asset integrity failure");
        byte[] bytes = readBoundedAsset(path);
        if (!hash.equals(sha256(bytes))) throw new StorageException(
                StorageException.Code.ASSET_INTEGRITY_FAILURE, "Cape asset integrity failure");
        return bytes;
    }

    public AccountState renameCape(UUID accountId, UUID entryId, String name) throws IOException {
        return updateAccount(accountId, current -> current.withPersonalCapes(current.personalCapes().stream()
                .map(entry -> entry.texture().entryId().equals(entryId) ? entry.renamed(name) : entry).toList()));
    }

    public byte[] readAsset(String sha256) throws IOException, PngValidationException {
        Path path = verifiedAssetPath(sha256);
        byte[] bytes = readBoundedAsset(path);
        if (!sha256.equals(sha256(bytes))) throw new StorageException(
                StorageException.Code.ASSET_INTEGRITY_FAILURE,
                "Content-addressed skin asset failed integrity verification");
        pngValidator.validate(bytes);
        return bytes;
    }

    public Path readAssetPath(String hash) throws IOException, PngValidationException {
        Path path = verifiedAssetPath(hash);
        new com.naocraftlab.skins.core.png.PngFileReader(pngValidator).validate(path);
        return path;
    }

    private Path verifiedAssetPath(String hash) throws IOException {
        Path path = assetPath(hash);
        if (!hash.equals(sha256(path))) throw new StorageException(
                StorageException.Code.ASSET_INTEGRITY_FAILURE,
                "Content-addressed skin asset failed integrity verification");
        return path;
    }

    public StoredAsset storeAsset(Path encoded) throws IOException, PngValidationException {
        return storeAsset(new com.naocraftlab.skins.core.png.PngFileReader(pngValidator).projectImport(encoded).pngBytes());
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

    private Path transactionPath(UUID accountId) {
        return layout.accountState(accountId).getParent().resolve("cape-transaction.json");
    }

    private void recoverAccountTransaction(UUID accountId) throws IOException {
        Path path = transactionPath(accountId);
        if (!Files.exists(path)) return;
        try {
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(
                    new String(readBoundedState(path, (int) MAX_STATE_BYTES * 2 + 1024), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            byte[] accountBytes = json.getAsJsonObject("account").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] appearanceBytes = json.getAsJsonObject("appearance").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            AccountState account = stateJson.decode(accountBytes).state();
            AccountAppearanceState appearance = appearanceStateJson.decode(appearanceBytes).state();
            validateAccountId(accountId, account);
            if (!accountId.equals(appearance.accountId())) throw new IllegalArgumentException("Transaction account mismatch");
            AtomicFileWriter.replace(layout.accountAppearance(accountId),
                    boundedAppearanceBytes(appearanceStateJson.encode(appearance, appearanceBytes)));
            AtomicFileWriter.replace(layout.accountState(accountId), stateJson.encode(account));
            AtomicFileWriter.replace(layout.accountBackup(accountId), stateJson.encode(account));
            Files.delete(path);
        } catch (com.google.gson.JsonParseException | IllegalArgumentException | IllegalStateException | NullPointerException exception) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "Invalid account transaction", exception);
        }
    }

    public AccountAppearanceMutationResult deleteCape(UUID accountId, UUID entryId) throws IOException {
        return mutateAccountAndAppearance(accountId, (account, appearance, revision) -> {
            var entry = account.personalCapes().stream().filter(value -> value.texture().entryId().equals(entryId)).findFirst();
            if (entry.isEmpty()) return AccountAppearanceMutationPlan.accountOnly(account, appearance);
            var reference = entry.orElseThrow().texture();
            AccountState updated = new AccountState(account.schemaVersion(), account.accountId(), account.skinAssets(), account.personalSkins(),
                    account.presets().stream().map(preset -> reference.equals(preset.offlineCape()) ? preset.withOfflineCape(null) : preset).toList(),
                    account.updatedAt().plusNanos(1), account.personalCapes().stream().filter(value -> !value.texture().equals(reference)).toList());
            var channel = appearance.providers().cape();
            java.util.function.Predicate<com.naocraftlab.skins.core.provider.ProviderCape> matches = cape ->
                    cape != null && cape.id().equals(entryId.toString());
            if (!matches.test(channel.offline().value()) && !matches.test(channel.offlineDesired())) {
                return AccountAppearanceMutationPlan.accountOnly(updated, appearance);
            }
            var replacement = new com.naocraftlab.skins.core.provider.ProviderChannel<>(channel.order(),
                    matches.test(channel.offline().value()) ? com.naocraftlab.skins.core.provider.ProviderObservation.<com.naocraftlab.skins.core.provider.ProviderCape>observed(null) : channel.offline(),
                    channel.minecraft(), channel.configurationRevision(), channel.intentRevision(), channel.desired(), channel.minecraftDelivery(),
                    matches.test(channel.offlineDesired()) ? null : channel.offlineDesired(),
                    channel.optifine(), channel.skinmc());
            var providers = new com.naocraftlab.skins.core.provider.AppearanceProviders(appearance.providers().skin(), replacement);
            var changed = new AccountAppearanceState(appearance.schemaVersion(), accountId, revision, appearance.activePresetId(),
                    appearance.skinSha256(), appearance.skinVariant(), appearance.capeId(), appearance.outerLayerVisibility(),
                    appearance.syncStatus(), appearance.syncStatus() == com.naocraftlab.skins.core.model.AppearanceSyncStatus.OFFICIAL ? revision : appearance.settledRevision(),
                    clock.instant(), providers);
            return new AccountAppearanceMutationPlan(updated, changed, true, true);
        }, true);
    }

    public AccountState discardCapeIfUnreferenced(UUID accountId, UUID entryId) throws IOException {
        return mutateAccountAndAppearance(accountId, (account, appearance, revision) -> {
            var entry = account.personalCapes().stream()
                    .filter(value -> value.texture().entryId().equals(entryId))
                    .findFirst();
            if (entry.isEmpty()) {
                return new AccountAppearanceMutationPlan(account, appearance, false, false);
            }
            var reference = entry.orElseThrow().texture();
            var channel = appearance.providers().cape();
            java.util.function.Predicate<com.naocraftlab.skins.core.provider.ProviderCape> matches = cape ->
                    cape != null && entryId.toString().equals(cape.id());
            boolean referenced = account.presets().stream()
                    .anyMatch(preset -> reference.equals(preset.offlineCape()))
                    || matches.test(channel.offline().value())
                    || matches.test(channel.offlineDesired());
            if (referenced) {
                return new AccountAppearanceMutationPlan(account, appearance, false, false);
            }
            return AccountAppearanceMutationPlan.accountOnly(
                    account.withPersonalCapes(account.personalCapes().stream()
                            .filter(value -> !value.texture().equals(reference)).toList()),
                    appearance);
        }).account();
    }

    private AccountState materializePublishedCapes(AccountState account) throws IOException {
        Path inventoryPath = layout.accountOwnedCapes(account.accountId());
        if (!Files.isRegularFile(inventoryPath)) return account;
        OwnedCapeInventory inventory = ownedCapeInventoryJson.decode(readBoundedState(inventoryPath));
        if (!account.accountId().equals(inventory.accountId())) throw new StorageException(StorageException.Code.INVALID_STATE, "Cape account mismatch");
        java.util.List<com.naocraftlab.skins.core.model.AppearancePreset> presets = new java.util.ArrayList<>();
        for (var preset : account.presets()) {
            String key = inventory.find(preset.capeId() == null ? "" : preset.capeId())
                    .flatMap(com.naocraftlab.skins.core.model.OwnedCapeEntry::optionalTextureCacheKey).orElse(null);
            if (key != null && preset.offlineCape() == null) {
                Path source = layout.textureCache().resolve(key + ".png");
                if (Files.isRegularFile(source)) {
                    try {
                        var png = new com.naocraftlab.skins.core.png.PngFileReader(pngValidator).projectCanonicalCape(source);
                        String hash = sha256(png.bytes());
                        AtomicFileWriter.createImmutable(capeAssetPath(account.accountId(), hash), png.bytes());
                        preset = preset.withOfflineCape(new com.naocraftlab.skins.core.model.LocalCapeReference(null, hash, png.hasElytra()));
                    } catch (PngValidationException unavailable) { }
                }
            }
            presets.add(preset);
        }
        return new AccountState(account.schemaVersion(), account.accountId(), account.skinAssets(), account.personalSkins(),
                presets, account.updatedAt(), account.personalCapes());
    }

    private void restoreCapeCache(AccountState account) throws IOException {
        java.util.Set<String> hashes = new java.util.HashSet<>();
        account.personalCapes().forEach(entry -> hashes.add(entry.texture().sha256()));
        account.presets().stream().map(com.naocraftlab.skins.core.model.AppearancePreset::offlineCape)
                .filter(Objects::nonNull).forEach(cape -> hashes.add(cape.sha256()));
        for (String hash : hashes) {
            Path target = layout.textureCache().resolve(hash + ".png");
            Path descriptor = capeAssetPath(account.accountId(), hash).resolveSibling(hash + ".cape");
            if (!Files.exists(descriptor) && !Files.exists(target)
                    && Files.isRegularFile(capeAssetPath(account.accountId(), hash))) {
                AtomicFileWriter.createImmutable(target, readCapeAsset(account.accountId(), hash));
            }
        }
    }

    private AccountState loadAccountLocked(UUID accountId) throws IOException {
        recoverAccountTransaction(accountId);
        Path statePath = layout.accountState(accountId);
        Path backupPath = layout.accountBackup(accountId);
        StorageException primaryFailure = null;
        if (Files.exists(statePath)) {
            try {
                AccountStateJson.Decoded decoded = stateJson.decode(readBoundedState(statePath));
                validateAccountId(accountId, decoded.state());
                AccountState loaded = decoded.legacyCapes() ? materializePublishedCapes(decoded.state()) : decoded.state();
                if (decoded.migrated() || decoded.legacyCapes()) AtomicFileWriter.replace(statePath, stateJson.encode(loaded));
                restoreCapeCache(loaded);
                return loaded;
            } catch (StorageException exception) {
                primaryFailure = exception;
            }
        }
        if (Files.exists(backupPath)) {
            AccountStateJson.Decoded decoded = stateJson.decode(readBoundedState(backupPath));
            validateAccountId(accountId, decoded.state());
            AccountState loaded = decoded.legacyCapes() ? materializePublishedCapes(decoded.state()) : decoded.state();
            AtomicFileWriter.replace(statePath, stateJson.encode(loaded));
            restoreCapeCache(loaded);
            return loaded;
        }
        if (primaryFailure != null) {
            throw primaryFailure;
        }
        throw new StorageException(StorageException.Code.INVALID_STATE, "Account state does not exist");
    }

    private AccountAppearanceState loadAppearanceLocked(UUID accountId) throws IOException {
        recoverAccountTransaction(accountId);
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

    private byte[] encodeAppearanceLocked(UUID accountId, AccountAppearanceState state) throws IOException {
        Path path = layout.accountAppearance(accountId);
        byte[] previous = Files.exists(path) ? readBoundedState(path) : null;
        byte[] encoded = appearanceStateJson.encode(state, previous);
        return boundedAppearanceBytes(encoded);
    }

    private static byte[] boundedAppearanceBytes(byte[] encoded) throws StorageException {
        if (encoded.length > MAX_STATE_BYTES) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "Appearance state exceeds size limit");
        }
        return encoded;
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
        saveAccountLocked(state, () -> true);
    }

    private static void requireCurrent(java.util.function.BooleanSupplier current) throws IOException {
        if (!current.getAsBoolean()) throw new IOException("Editor account retired");
    }

    private void saveAccountLocked(AccountState state, java.util.function.BooleanSupplier stillCurrent) throws IOException {
        requireCurrent(stillCurrent);
        UUID accountId = state.accountId();
        Path statePath = layout.accountState(accountId);
        if (Files.exists(statePath)) {
            byte[] existing = readBoundedState(statePath);
            try {
                AccountStateJson.Decoded decoded = stateJson.decode(existing);
                if (decoded.state().accountId().equals(accountId)) {
                    AtomicFileWriter.replace(layout.accountBackup(accountId), existing, stillCurrent);
                }
            } catch (StorageException invalidExistingState) {

            }
        }
        AtomicFileWriter.replace(statePath, stateJson.encode(state), stillCurrent);
    }

    private static void validateAccountId(UUID expected, AccountState state) throws StorageException {
        if (!expected.equals(state.accountId())) {
            throw new StorageException(
                    StorageException.Code.INVALID_STATE,
                    "Account state UUID does not match its directory");
        }
    }

    private byte[] readBoundedAsset(Path path) throws IOException {
        int maximum = Math.min(pngValidator.maxBytes(), 65 * 1024 * 1024);
        try (java.io.InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length <= maximum) return bytes;
        }
        try {
            return new com.naocraftlab.skins.core.png.PngFileReader(pngValidator).projectStoredRender(path).pngBytes();
        } catch (PngValidationException invalid) {
            throw new StorageException(StorageException.Code.ASSET_INTEGRITY_FAILURE,
                    "Stored skin asset failed validation");
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long total = 0;
            try (java.io.InputStream input = Files.newInputStream(path)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) continue;
                    if (count > pngValidator.maxBytes() - total) throw new StorageException(
                            StorageException.Code.ASSET_INTEGRITY_FAILURE,
                            "Stored skin asset exceeds the configured size limit");
                    digest.update(buffer, 0, count);
                    total += count;
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static byte[] readCapeDescriptor(Path path) throws IOException {
        try (java.io.InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(64 * 1024 + 1);
            if (bytes.length <= 64 * 1024) return bytes;
            throw new StorageException(StorageException.Code.ASSET_INTEGRITY_FAILURE,
                    "Cape descriptor exceeds size limit",
                    new com.naocraftlab.skins.core.png.CapeIntegrityException("Cape descriptor exceeds size limit"));
        }
    }

    private static byte[] readBoundedState(Path path) throws IOException {
        return readBoundedState(path, (int) MAX_STATE_BYTES);
    }

    private static byte[] readBoundedState(Path path, int maximum) throws IOException {
        try (java.io.InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length <= maximum) {
                return bytes;
            }
            throw new StorageException(StorageException.Code.INVALID_STATE, "Account state exceeds the size limit");
        }
    }

    private void ensureInitialized() throws IOException {
        initialize();
    }

    public static Path defaultRoot() {
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
