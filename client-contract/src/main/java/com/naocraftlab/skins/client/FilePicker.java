package com.naocraftlab.skins.client;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;


public interface FilePicker {
    CompletableFuture<Optional<Path>> chooseSkinPng();
}
