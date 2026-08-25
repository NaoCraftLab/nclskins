package com.naocraftlab.skins.compat.client.identifier.submission;

import java.util.Objects;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;


public final class LivePreviewSubmission implements AutoCloseable {
    private static final ThreadLocal<LivePreviewSubmission> ACTIVE =
            new ThreadLocal<>();

    private final Object graphics;
    private final LivePreviewRenderState state;
    private boolean consumed;

    private LivePreviewSubmission(
            Object graphics, LivePreviewRenderState state) {
        this.graphics = Objects.requireNonNull(graphics, "graphics");
        this.state = Objects.requireNonNull(state, "state");
    }

    public static LivePreviewSubmission open(
            Object graphics, LivePreviewRenderState state) {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("Live preview submission is already active");
        }
        LivePreviewSubmission submission =
                new LivePreviewSubmission(graphics, state);
        ACTIVE.set(submission);
        return submission;
    }

    public static PictureInPictureRenderState replace(
            Object graphics, PictureInPictureRenderState vanillaState) {
        LivePreviewSubmission submission = ACTIVE.get();
        if (submission == null || submission.graphics != graphics) {
            return vanillaState;
        }
        if (submission.consumed) {
            throw new IllegalStateException("Live preview submission was consumed twice");
        }
        submission.consumed = true;
        return submission.state.withScissor(vanillaState.scissorArea());
    }

    public void requireConsumed() {
        if (!consumed) {
            throw new IllegalStateException("Vanilla entity submission did not reach the GUI state");
        }
    }

    @Override
    public void close() {
        if (ACTIVE.get() != this) {
            throw new IllegalStateException("Live preview submission closed out of order");
        }
        ACTIVE.remove();
    }
}
