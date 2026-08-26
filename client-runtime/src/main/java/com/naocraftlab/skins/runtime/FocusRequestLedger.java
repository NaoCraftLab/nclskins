package com.naocraftlab.skins.runtime;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class FocusRequestLedger {
    private String screenId;
    private final Set<ViewSpec.FocusRequest> applied = new LinkedHashSet<>();

    public Optional<ViewSpec.FocusRequest> pending(ViewSpec view) {
        Objects.requireNonNull(view, "view");
        selectScreen(view.screenId());
        return view.focusRequest().filter(request -> !applied.contains(request));
    }

    public void acknowledge(String currentScreenId, ViewSpec.FocusRequest request) {
        selectScreen(Objects.requireNonNull(currentScreenId, "currentScreenId"));
        applied.add(Objects.requireNonNull(request, "request"));
    }

    public void reset() {
        screenId = null;
        applied.clear();
    }

    int appliedCount() {
        return applied.size();
    }

    private void selectScreen(String currentScreenId) {
        Objects.requireNonNull(currentScreenId, "currentScreenId");
        if (!currentScreenId.equals(screenId)) {
            screenId = currentScreenId;
            applied.clear();
        }
    }
}
