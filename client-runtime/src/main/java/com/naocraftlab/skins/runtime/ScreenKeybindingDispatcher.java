package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ScreenDestination;
import com.naocraftlab.skins.client.ScreenKeybinding;

import java.util.Optional;

public final class ScreenKeybindingDispatcher {
    private ScreenKeybinding pending;

    public void press(ScreenKeybinding binding, boolean eligible) {
        if (eligible && (pending == null || binding.ordinal() < pending.ordinal())) pending = binding;
    }

    public Optional<ScreenDestination> drain(boolean eligible) {
        ScreenKeybinding selected = pending;
        pending = null;
        return eligible && selected != null ? Optional.of(selected.destination()) : Optional.empty();
    }
}
