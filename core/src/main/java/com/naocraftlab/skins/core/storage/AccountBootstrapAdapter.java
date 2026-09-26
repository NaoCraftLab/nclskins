package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.core.service.AccountBootstrapPort;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

public final class AccountBootstrapAdapter implements AccountBootstrapPort {
    private final NclSkinsStorage storage;
    public AccountBootstrapAdapter(NclSkinsStorage storage) { this.storage = java.util.Objects.requireNonNull(storage); }
    public List<String> initialize() throws IOException {
        return storage.initialize().warnings().stream().map(StorageWarning::message).toList();
    }
    public Preferences loadUiPreferences(UUID accountId) throws IOException {
        var result = storage.loadUiPreferences(accountId);
        return new Preferences(result.preferences(), result.warnings().stream().map(StorageWarning::message).toList());
    }
}
