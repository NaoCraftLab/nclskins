package com.naocraftlab.skins.client;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

public final class NativeTextureUploadTracker {
    private static final Map<Object, NativePlayerSkinLifecycle.Registration> TRACKED =
            new IdentityHashMap<>();

    private NativeTextureUploadTracker() {
    }

    public static synchronized void track(
            Object texture, NativePlayerSkinLifecycle.Registration registration) {
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(registration, "registration");
        if (TRACKED.putIfAbsent(texture, registration) != null) {
            throw new IllegalStateException("Native texture upload is already tracked");
        }
    }

    public static synchronized void uploaded(Object texture) {
        NativePlayerSkinLifecycle.Registration registration = TRACKED.get(texture);
        if (registration != null) {
            registration.ready();
        }
    }

    public static synchronized void forget(Object texture) {
        TRACKED.remove(Objects.requireNonNull(texture, "texture"));
    }
}
