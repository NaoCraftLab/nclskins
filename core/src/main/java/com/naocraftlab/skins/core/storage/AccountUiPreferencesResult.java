package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.core.model.AccountUiPreferences;
import java.util.List;
import java.util.Objects;


public record AccountUiPreferencesResult(
        AccountUiPreferences preferences,
        List<StorageWarning> warnings) {
    public AccountUiPreferencesResult {
        Objects.requireNonNull(preferences, "preferences");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }
}
