package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.core.model.FullPreviewModelFit;

public record PreviewModelFitResult(Status status, FullPreviewModelFit fit) {
    public enum Status { PRESENT, ABSENT, UNAVAILABLE }

    public PreviewModelFitResult {
        if (status == null || (status == Status.PRESENT) != (fit != null)) {
            throw new IllegalArgumentException("Invalid fit result");
        }
    }

    static PreviewModelFitResult absent() { return new PreviewModelFitResult(Status.ABSENT, null); }
    static PreviewModelFitResult unavailable() { return new PreviewModelFitResult(Status.UNAVAILABLE, null); }
    static PreviewModelFitResult present(FullPreviewModelFit fit) {
        return new PreviewModelFitResult(Status.PRESENT, fit);
    }
}
