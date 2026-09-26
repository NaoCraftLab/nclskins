package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.model.AccountUiPreferences;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

public interface AccountBootstrapPort {
    List<String> initialize() throws IOException;
    Preferences loadUiPreferences(UUID accountId) throws IOException;
    record Preferences(AccountUiPreferences preferences, List<String> warnings) {
        public Preferences { warnings = List.copyOf(warnings); }
    }
}
