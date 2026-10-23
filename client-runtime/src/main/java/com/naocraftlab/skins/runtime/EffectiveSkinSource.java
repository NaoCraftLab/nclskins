package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.SkinModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class EffectiveSkinSource {
    private final UUID cacheId;
    private final UUID inspectionScope = UUID.randomUUID();
    private final UUID subject;
    private final String location;
    private final SkinModel model;
    private final boolean preview;
    private final long revision;
    private final int[] pixels;
    private final List<Runnable> retirement = new ArrayList<>();
    private volatile boolean retired;
    private volatile CpmAccessoryObservation accessories = CpmAccessoryObservation.unknown();
    private long observer;

    public CpmAccessoryObservation accessories() {
        return active() ? accessories : CpmAccessoryObservation.unknown();
    }

    synchronized long nextObserver() { return ++observer; }
    synchronized boolean currentObserver(long expected) { return observer == expected && active(); }

    synchronized boolean accessories(CpmAccessoryObservation next) {
        if (!active() || next.generation() < accessories.generation() || next.equals(accessories)) return false;
        accessories = next;
        return true;
    }


    EffectiveSkinSource(UUID cacheId, UUID subject, String location, SkinModel model,
            boolean preview, long revision, int[] pixels) {
        this.cacheId = Objects.requireNonNull(cacheId);
        this.subject = Objects.requireNonNull(subject);
        this.location = Objects.requireNonNull(location);
        this.model = Objects.requireNonNull(model);
        this.preview = preview;
        this.revision = revision;
        this.pixels = pixels == null ? null : pixels.clone();
        if (pixels != null && pixels.length != 4096) throw new IllegalArgumentException("Skin pixels must be canonical");
    }

    UUID inspectionScope() { return inspectionScope; }

    public UUID cacheId() { return cacheId; }
    public UUID subject() { return subject; }
    public String location() { return location; }
    public SkinModel model() { return model; }
    public boolean preview() { return preview; }
    public boolean active() { return !retired; }
    public int[] pixels() { return pixels == null ? null : pixels.clone(); }
    long revision() { return revision; }

    public AutoCloseable onRetirement(Runnable action) {
        boolean run;
        synchronized (this) {
            run = retired;
            if (!run) {
                if (retirement.size() >= 64) throw new IllegalStateException("Skin source consumer budget exceeded");
                retirement.add(Objects.requireNonNull(action));
            }
        }
        if (run) action.run();
        return () -> { synchronized (this) { retirement.remove(action); } };
    }

    void retire() {
        List<Runnable> actions;
        synchronized (this) {
            if (retired) return;
            retired = true;
            actions = List.copyOf(retirement);
            retirement.clear();
        }
        Throwable failure = null;
        for (Runnable action : actions) {
            try { action.run(); }
            catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem; else failure.addSuppressed(problem);
            }
        }
        if (failure instanceof RuntimeException problem) throw problem;
        if (failure instanceof Error problem) throw problem;
    }
}
