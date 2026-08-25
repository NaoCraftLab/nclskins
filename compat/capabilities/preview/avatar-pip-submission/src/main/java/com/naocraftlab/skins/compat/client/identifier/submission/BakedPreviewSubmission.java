package com.naocraftlab.skins.compat.client.identifier.submission;

import java.util.Objects;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;


public final class BakedPreviewSubmission implements AutoCloseable {
    private static final ThreadLocal<BakedPreviewSubmission> ACTIVE =
            new ThreadLocal<>();

    private final Object graphics;
    private final BakedPreviewRenderState state;
    private boolean consumed;

    private BakedPreviewSubmission(
            Object graphics, BakedPreviewRenderState state) {
        this.graphics = Objects.requireNonNull(graphics, "graphics");
        this.state = Objects.requireNonNull(state, "state");
    }

    public static BakedPreviewSubmission open(
            Object graphics, BakedPreviewRenderState state) {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("Baked preview submission is already active");
        }
        BakedPreviewSubmission submission =
                new BakedPreviewSubmission(graphics, state);
        ACTIVE.set(submission);
        return submission;
    }

    public static PictureInPictureRenderState replace(
            Object graphics, PictureInPictureRenderState vanillaState) {
        BakedPreviewSubmission submission = ACTIVE.get();
        if (submission == null || submission.graphics != graphics) {
            return vanillaState;
        }
        if (submission.consumed) {
            throw new IllegalStateException("Baked preview submission was consumed twice");
        }
        submission.consumed = true;
        return submission.state.withScissor(vanillaState.scissorArea());
    }

    public void requireConsumed() {
        if (!consumed) {
            throw new IllegalStateException("Vanilla skin submission did not reach the GUI state");
        }
    }

    @Override
    public void close() {
        if (ACTIVE.get() != this) {
            throw new IllegalStateException("Baked preview submission closed out of order");
        }
        ACTIVE.remove();
    }
}
