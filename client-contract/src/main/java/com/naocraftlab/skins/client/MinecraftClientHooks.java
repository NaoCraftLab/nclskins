package com.naocraftlab.skins.client;

import java.nio.file.Path;
import java.util.function.Consumer;


public interface MinecraftClientHooks<C, S, W, F> extends AutoCloseable {
    default void initialize(Path configurationDirectory) {
    }

    default void tick(C client) {
    }

    default void afterScreenInit(
            C client,
            S screen,
            int scaledWidth,
            int scaledHeight,
            Consumer<W> widgets) {
    }

    default void afterScreenFrame(S screen, F frame) {
    }

    default void screenRemoved(S screen) {
    }

    @Override
    default void close() {
    }
}
