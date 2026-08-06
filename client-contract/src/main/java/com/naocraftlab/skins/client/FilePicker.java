package com.naocraftlab.skins.client;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;


public interface FilePicker {
    CompletableFuture<Optional<Path>> chooseSkinPng();

    default CompletableFuture<Optional<Path>> chooseDirectory() {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Directory picker is unavailable"));
    }
}
