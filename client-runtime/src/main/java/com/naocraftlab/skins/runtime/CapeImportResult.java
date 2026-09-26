package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.model.AccountState;

import java.util.Objects;
import java.util.Optional;

record CapeImportResult(
        com.naocraftlab.skins.core.model.PersonalCapeEntry entry,
        Optional<AccountState> account) {
    CapeImportResult {
        Objects.requireNonNull(entry, "entry");
        account = Objects.requireNonNull(account, "account");
    }
}
