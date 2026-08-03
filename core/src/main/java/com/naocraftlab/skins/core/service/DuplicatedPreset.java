package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import java.util.Objects;


public record DuplicatedPreset(AccountState state, AppearancePreset preset) {
    public DuplicatedPreset {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(preset, "preset");
    }
}
