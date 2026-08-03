package com.naocraftlab.skins.server;

import java.util.Objects;
import java.util.concurrent.CompletionStage;


public record RefreshSubmission(Admission admission, CompletionStage<RefreshResult> completion) {
    public RefreshSubmission {
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(completion, "completion");
    }

    @Override
    public String toString() {
        return "RefreshSubmission[admission=" + admission + ']';
    }
}
