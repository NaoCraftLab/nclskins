package com.naocraftlab.skins.compat.client.identifier.extraction;

import java.util.Objects;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

public final class NclBakedPlayerSubmission implements AutoCloseable {
    private static final ThreadLocal<NclBakedPlayerSubmission> ACTIVE = new ThreadLocal<>();

    private final Object graphics;
    private final NclBakedPlayerRenderState state;
    private boolean consumed;

    private NclBakedPlayerSubmission(Object graphics, NclBakedPlayerRenderState state) {
        this.graphics = Objects.requireNonNull(graphics, "graphics");
        this.state = Objects.requireNonNull(state, "state");
    }

    public static NclBakedPlayerSubmission open(
            Object graphics, NclBakedPlayerRenderState state) {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("Baked player submission is already active");
        }
        NclBakedPlayerSubmission submission = new NclBakedPlayerSubmission(graphics, state);
        ACTIVE.set(submission);
        return submission;
    }

    public static PictureInPictureRenderState replace(
            Object graphics, PictureInPictureRenderState vanillaState) {
        NclBakedPlayerSubmission submission = ACTIVE.get();
        if (submission == null || submission.graphics != graphics) {
            return vanillaState;
        }
        if (submission.consumed) {
            throw new IllegalStateException("Baked player submission was consumed twice");
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
            throw new IllegalStateException("Baked player submission closed out of order");
        }
        ACTIVE.remove();
    }
}
