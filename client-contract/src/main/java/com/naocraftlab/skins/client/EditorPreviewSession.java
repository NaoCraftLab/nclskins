package com.naocraftlab.skins.client;

import java.util.Objects;

public final class EditorPreviewSession {
    public static final int SETTLING_TICKS = 6;
    private static final float SETTLING_HEIGHT = 0.35F;

    private boolean liveDisabled;
    private float settlingStartAge = Float.NaN;
    private float greatestAge;
    private final java.util.function.LongSupplier nanoTime;
    private String bodySource;
    private long bodyStarted;
    private long bodyGeneration;
    private PreviewRenderer.BodyStatus bodyStatus = PreviewRenderer.BodyStatus.ORDINARY;
    private PreviewModelFrames.Body readyBody;
    private PreviewModelFrames.Body displayedBody;
    private Object displayedWitness;
    private long displayGeneration;
    private boolean bodyExpected;
    private boolean bodyWorld;
    private Object displayAuthority;
    private Object displayWorld;
    private Object displayPlayer;
    private PreviewModelFrames.Completion ordinaryCompletion;
    private java.util.function.Consumer<PreviewRenderer.BodyStatus> bodyObserver = value -> {};

    public EditorPreviewSession() { this(System::nanoTime); }
    public EditorPreviewSession(java.util.function.LongSupplier nanoTime) { this.nanoTime = Objects.requireNonNull(nanoTime); }

    public void observeBody(java.util.function.Consumer<PreviewRenderer.BodyStatus> observer) {
        bodyObserver = Objects.requireNonNull(observer);
    }

    public void beginBody(String source, boolean expected, boolean world) {
        boolean sourceChanged = !Objects.equals(bodySource, source);
        boolean scopeChanged = sourceChanged || bodyWorld != world || bodyExpected != (expected && world);
        var previous = bodyStatus;
        bodyWorld = world;
        if (sourceChanged) {
            bodySource = Objects.requireNonNull(source);
            bodyStarted = nanoTime.getAsLong();
            bodyGeneration++;
            readyBody = null;
            bodyStatus = PreviewRenderer.BodyStatus.ORDINARY;
        }
        bodyExpected = expected && world;
        if (!bodyExpected) {
            readyBody = null;
            bodyStatus = PreviewRenderer.BodyStatus.ORDINARY;
        } else if (liveDisabled) bodyStatus = PreviewRenderer.BodyStatus.FAILED;
        else if (bodyStatus == PreviewRenderer.BodyStatus.ORDINARY) bodyStatus = PreviewRenderer.BodyStatus.PENDING;
        if (scopeChanged) invalidateDisplayedBody();
        expireBody();
        if (scopeChanged || previous != bodyStatus) bodyObserver.accept(bodyStatus);
    }

    public PreviewRenderer.BodyStatus bodyStatus() { expireBody(); return bodyStatus; }

    public boolean waitsForBody() { return bodyExpected; }

    public PreviewModelFrames.Body body() {
        expireBody();
        var selected = displayedBody;
        long generation = displayGeneration;
        boolean valid = selected != null && selected.current().getAsBoolean();
        if (!valid && displayedBody == selected && generation == displayGeneration && selected != null) invalidateDisplayedBody();
        return valid && generation == displayGeneration && displayedBody == selected ? selected
                : new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.ORDINARY, () -> false);
    }

    public void displayAuthority(Object authority, Object world, Object player) {
        if (Objects.equals(displayAuthority, authority) && displayWorld == world && displayPlayer == player) return;
        displayAuthority = Objects.requireNonNull(authority);
        displayWorld = world;
        displayPlayer = player;
        invalidateDisplayedBody();
    }

    public PreviewModelFrames.Completion ordinaryDisplay(
            java.util.function.BooleanSupplier current) {
        Objects.requireNonNull(current);
        if (ordinaryCompletion == null) {
            long generation = displayGeneration;
            var completed = completion(current, () -> true, true);
            ordinaryCompletion = new PreviewModelFrames.Completion() {
                public boolean current() { return generation == displayGeneration && current.getAsBoolean()
                        && generation == displayGeneration; }
                public void accept(PreviewModelFrames.Body body) { completed.accept(body); }
            };
        }
        return ordinaryCompletion;
    }

    public java.util.function.Consumer<PreviewModelFrames.Body> displayBody(java.util.function.BooleanSupplier current) {
        return completion(current, frame(), false);
    }

    private java.util.function.Consumer<PreviewModelFrames.Body> completion(
            java.util.function.BooleanSupplier current, java.util.function.BooleanSupplier frameCurrent,
            boolean ordinaryOnly) {
        Objects.requireNonNull(current);
        long generation = displayGeneration;
        long preparedGeneration = bodyGeneration;
        var preparedBody = readyBody;
        return result -> {
            Objects.requireNonNull(result);
            Object previousWitness = displayedWitness;
            if (generation != displayGeneration || !frameCurrent.getAsBoolean() || !current.getAsBoolean()
                    || !displayCurrent(generation, preparedGeneration, preparedBody, ordinaryOnly)
                    || displayedWitness != previousWitness) return;
            boolean success = result.status() == PreviewRenderer.BodyStatus.ORDINARY
                    || !ordinaryOnly && result.status() == PreviewRenderer.BodyStatus.READY;
            boolean valid = success && result.current().getAsBoolean();
            if (generation != displayGeneration || !frameCurrent.getAsBoolean() || !current.getAsBoolean()
                    || !displayCurrent(generation, preparedGeneration, preparedBody, ordinaryOnly)
                    || displayedWitness != previousWitness) return;
            Object witness = valid ? new Object() : null;
            setDisplayedBody(valid
                    ? new PreviewModelFrames.Body(result.status(),
                            () -> generation == displayGeneration && displayedWitness == witness && frameCurrent.getAsBoolean()
                                    && current.getAsBoolean() && result.current().getAsBoolean()
                                    && displayCurrent(generation, preparedGeneration, preparedBody, ordinaryOnly)
                                    && displayedWitness == witness) : null, witness);
        };
    }

    private boolean displayCurrent(long display, long prepared, PreviewModelFrames.Body selected, boolean ordinaryOnly) {
        return display == displayGeneration && (ordinaryOnly || frameCurrent(prepared, display, selected));
    }

    public void invalidateDisplayedBody() {
        displayGeneration++;
        ordinaryCompletion = null;
        setDisplayedBody(null, null);
    }

    private void setDisplayedBody(PreviewModelFrames.Body result, Object witness) {
        boolean changed = displayedBody == null ? result != null
                : result == null || displayedBody.status() != result.status();
        displayedBody = result;
        displayedWitness = witness;
        if (changed) bodyObserver.accept(bodyStatus);
    }

    public void body(PreviewModelFrames.Body result) {
        expireBody();
        if (!bodyExpected || terminalBody() || !result.current().getAsBoolean()) return;
        bodyStatus = result.status() == PreviewRenderer.BodyStatus.ORDINARY
                ? PreviewRenderer.BodyStatus.FAILED : result.status();
        readyBody = bodyStatus == PreviewRenderer.BodyStatus.READY
                ? readyBody != null && readyBody.current().getAsBoolean() ? readyBody : result : null;
        if (terminalBody()) invalidateDisplayedBody();
        bodyObserver.accept(bodyStatus);
    }

    public java.util.function.BooleanSupplier frame() {
        long generation = bodyGeneration;
        long display = displayGeneration;
        PreviewModelFrames.Body selected = readyBody;
        return () -> frameCurrent(generation, display, selected)
                && (!bodyExpected || selected.current().getAsBoolean())
                && frameCurrent(generation, display, selected);
    }

    private boolean frameCurrent(long generation, long display, PreviewModelFrames.Body selected) {
        return generation == bodyGeneration && display == displayGeneration && !liveDisabled && (!bodyExpected
                || bodyStatus == PreviewRenderer.BodyStatus.READY && readyBody == selected && selected != null);
    }

    private boolean terminalBody() {
        return bodyStatus == PreviewRenderer.BodyStatus.FAILED || bodyStatus == PreviewRenderer.BodyStatus.BLOCKED
                || bodyStatus == PreviewRenderer.BodyStatus.TIMED_OUT;
    }

    private void expireBody() {
        if (bodyExpected && bodyStatus == PreviewRenderer.BodyStatus.PENDING
                && nanoTime.getAsLong() - bodyStarted >= java.time.Duration.ofSeconds(60).toNanos()) {
            bodyStatus = PreviewRenderer.BodyStatus.TIMED_OUT;
            invalidateDisplayedBody();
            readyBody = null;
            bodyObserver.accept(bodyStatus);
        }
    }

    public PreviewRenderer.LiveReason liveReason(PreviewRenderer.PreviewIntent intent,
            boolean world, boolean textureReady, boolean proxyReady) {
        if (intent != PreviewRenderer.PreviewIntent.EDITOR_DRAFT || liveDisabled)
            return PreviewRenderer.LiveReason.LIVE_UNAVAILABLE;
        if (!world) return PreviewRenderer.LiveReason.WORLD_REQUIRED;
        if (!textureReady) return PreviewRenderer.LiveReason.LOADING;
        return proxyReady ? PreviewRenderer.LiveReason.AVAILABLE : PreviewRenderer.LiveReason.LIVE_UNAVAILABLE;
    }

    public Path path(
            PreviewRenderer.PreviewIntent intent,
            boolean hasWorldAndLocalPlayer) {
        Objects.requireNonNull(intent, "intent");
        return intent == PreviewRenderer.PreviewIntent.EDITOR_DRAFT
                && hasWorldAndLocalPlayer
                && !liveDisabled
                ? Path.LIVE
                : Path.BAKED;
    }

    public boolean disableLive(RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        if (liveDisabled) {
            return false;
        }
        liveDisabled = true;
        if (bodyExpected) {
            bodyStatus = PreviewRenderer.BodyStatus.FAILED;
            readyBody = null;
        }
        invalidateDisplayedBody();
        if (bodyExpected) bodyObserver.accept(bodyStatus);
        return true;
    }

    public SettlingMotion capeSettling(
            PreviewRenderer.PreviewIntent intent,
            boolean hasWorldAndLocalPlayer,
            PreviewRenderer.CapeMode capeMode,
            boolean capeRenderable,
            float ageTicks) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(capeMode, "capeMode");
        if (!Float.isFinite(ageTicks) || ageTicks < 0.0F) {
            throw new IllegalArgumentException("Preview age must be finite and non-negative");
        }
        if (path(intent, hasWorldAndLocalPlayer) != Path.LIVE
                || capeMode == PreviewRenderer.CapeMode.OFF
                || !capeRenderable) {
            return SettlingMotion.NONE;
        }
        greatestAge = Math.max(greatestAge, ageTicks);
        if (Float.isNaN(settlingStartAge)) {
            settlingStartAge = greatestAge;
        }
        float elapsed = Math.min(SETTLING_TICKS, greatestAge - settlingStartAge);
        if (elapsed >= SETTLING_TICKS) {
            return SettlingMotion.NONE;
        }
        float current = offset(elapsed);
        float previous = offset(Math.max(0.0F, elapsed - 1.0F));
        return new SettlingMotion(current, previous, true);
    }

    private static float offset(float elapsed) {
        float remaining = 1.0F - elapsed / SETTLING_TICKS;
        return SETTLING_HEIGHT * remaining * remaining;
    }

    public enum Path {
        BAKED,
        LIVE
    }

    public record SettlingMotion(float currentYOffset, float previousYOffset, boolean active) {
        public static final SettlingMotion NONE = new SettlingMotion(0.0F, 0.0F, false);

        public SettlingMotion {
            if (!Float.isFinite(currentYOffset) || !Float.isFinite(previousYOffset)) {
                throw new IllegalArgumentException("Settling offsets must be finite");
            }
        }
    }
}
