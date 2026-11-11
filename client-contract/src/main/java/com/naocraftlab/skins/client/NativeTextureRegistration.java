package com.naocraftlab.skins.client;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class NativeTextureRegistration {
    private static final Map<Object, NativeTextureRegistration> IMAGES = new IdentityHashMap<>();
    private final BooleanSupplier current;
    private Object image;
    private Runnable discardImage;
    private Runnable release;
    private boolean retired;
    private boolean acquiring;
    private boolean attempted;

    public NativeTextureRegistration(BooleanSupplier current) {
        this.current = Objects.requireNonNull(current, "current");
    }

    public synchronized boolean isCurrent() {
        return !retired && current.getAsBoolean();
    }

    public synchronized void bind(Object image, Runnable discardImage) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(discardImage, "discardImage");
        if (this.image != null) throw new IllegalStateException("Texture image is already bound");
        synchronized (IMAGES) {
            if (IMAGES.putIfAbsent(image, this) != null) {
                throw new IllegalStateException("Texture image has another owner");
            }
        }
        this.image = image;
        this.discardImage = discardImage;
    }

    public static NativeTextureRegistration forImage(Object image) {
        synchronized (IMAGES) {
            return IMAGES.get(image);
        }
    }

    public synchronized void acquire(Runnable register, Runnable release, Runnable discard) {
        Objects.requireNonNull(register, "register");
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(discard, "discard");
        if (attempted) throw new IllegalStateException("Texture registration was already attempted");
        attempted = true;
        if (!isCurrent()) {
            discard.run();
            return;
        }
        acquiring = true;
        try {
            register.run();
            this.release = release;
        } catch (RuntimeException | Error failure) {
            try {
                discard.run();
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        } finally {
            acquiring = false;
        }
        if (!isCurrent()) release();
    }

    public synchronized void complete() {
        if (image != null) {
            synchronized (IMAGES) {
                IMAGES.remove(image, this);
            }
            image = null;
            Runnable discard = discardImage;
            discardImage = null;
            if (!attempted) discard.run();
        }
    }

    public synchronized void retire() {
        retired = true;
        if (!acquiring) release();
    }

    private void release() {
        Runnable owned = release;
        release = null;
        if (owned != null) owned.run();
    }
}
