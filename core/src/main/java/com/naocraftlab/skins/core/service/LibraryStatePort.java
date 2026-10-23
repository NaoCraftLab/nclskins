package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AccountAppearanceState;
import com.naocraftlab.skins.core.model.PersonalCapeEntry;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

public interface LibraryStatePort {
    AccountState loadOrCreateAccount(UUID accountId) throws IOException;
    AccountState updateAccount(UUID accountId, UnaryOperator<AccountState> update,
            AccountWriteGuard guard) throws IOException;
    default AccountState updateAccount(UUID accountId, UnaryOperator<AccountState> update,
            AccountWriteGuard guard, java.util.function.BooleanSupplier current) throws IOException {
        throw new UnsupportedOperationException("Guarded account publication unavailable");
    }
    default AccountState updateAccount(UUID accountId, UnaryOperator<AccountState> update)
            throws IOException {
        return updateAccount(accountId, update, current -> {});
    }
    AccountAppearanceMutationResult mutateAccountAndAppearance(UUID accountId,
            AccountAppearanceMutation mutation) throws IOException;
    PersonalCapeEntry importCape(UUID accountId, String name, byte[] bytes, AccountWriteGuard guard)
            throws IOException, PngValidationException;
    default PersonalCapeEntry importCape(UUID accountId, String name, Path source,
            AccountWriteGuard guard) throws IOException, PngValidationException {
        throw new UnsupportedOperationException("Path cape import unavailable");
    }
    default PersonalCapeEntry importPreparedCape(UUID accountId, String name,
            PreparedCapeAsset prepared, AccountWriteGuard guard) throws IOException {
        throw new UnsupportedOperationException("Prepared cape import unavailable");
    }
    default Optional<PreparedCapeAsset> readPreparedCapeAsset(UUID accountId, String key)
            throws IOException {
        return Optional.empty();
    }

    AccountState renameCape(UUID accountId, UUID entryId, String name) throws IOException;
    AccountAppearanceMutationResult deleteCape(UUID accountId, UUID entryId) throws IOException;
    AccountState discardCapeIfUnreferenced(UUID accountId, UUID entryId) throws IOException;

    @FunctionalInterface
    interface AppearanceIntentFromAccount {
        AccountAppearanceState apply(AccountState account, AccountAppearanceState currentAppearance,
                long nextRevision);
    }

    @FunctionalInterface
    interface AccountAppearanceMutation {
        AccountAppearanceMutationPlan apply(
                AccountState account,
                AccountAppearanceState appearance,
                long nextIntentRevision);
    }


    record AccountAppearanceMutationPlan(
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


    record AccountAppearanceMutationResult(
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


}
