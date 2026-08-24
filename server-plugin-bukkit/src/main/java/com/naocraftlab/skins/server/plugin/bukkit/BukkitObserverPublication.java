package com.naocraftlab.skins.server.plugin.bukkit;

import java.util.Objects;
import java.util.function.BooleanSupplier;


final class BukkitObserverPublication {
    private BukkitObserverPublication() {
    }

    static void refresh(
            Runnable untrack,
            Runnable playerInfo,
            Runnable retrack,
            BooleanSupplier trackingRestored) {
        Objects.requireNonNull(untrack, "untrack");
        Objects.requireNonNull(playerInfo, "playerInfo");
        Objects.requireNonNull(retrack, "retrack");
        Objects.requireNonNull(trackingRestored, "trackingRestored");
        untrack.run();
        RuntimeException publicationFailure = null;
        try {
            playerInfo.run();
        } catch (RuntimeException failure) {
            publicationFailure = failure;
        }
        try {
            retrack.run();
            if (!trackingRestored.getAsBoolean()) {
                throw new IllegalStateException("Observer tracking was not restored");
            }
        } catch (RuntimeException retrackFailure) {
            if (publicationFailure != null) {
                retrackFailure.addSuppressed(publicationFailure);
            }
            throw retrackFailure;
        }
        if (publicationFailure != null) {
            throw publicationFailure;
        }
    }
}
