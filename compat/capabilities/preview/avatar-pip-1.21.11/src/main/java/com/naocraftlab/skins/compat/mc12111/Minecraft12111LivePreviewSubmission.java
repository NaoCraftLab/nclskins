package com.naocraftlab.skins.compat.mc12111;

import java.util.Objects;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;


public final class Minecraft12111LivePreviewSubmission implements AutoCloseable {
    private static final ThreadLocal<Minecraft12111LivePreviewSubmission> ACTIVE =
            new ThreadLocal<>();

    private final Object graphics;
    private final Minecraft12111LivePreviewRenderState state;
    private boolean consumed;

    private Minecraft12111LivePreviewSubmission(
            Object graphics, Minecraft12111LivePreviewRenderState state) {
        this.graphics = Objects.requireNonNull(graphics, "graphics");
        this.state = Objects.requireNonNull(state, "state");
    }

    public static Minecraft12111LivePreviewSubmission open(
            Object graphics, Minecraft12111LivePreviewRenderState state) {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("Live preview submission is already active");
        }
        Minecraft12111LivePreviewSubmission submission =
                new Minecraft12111LivePreviewSubmission(graphics, state);
        ACTIVE.set(submission);
        return submission;
    }

    public static PictureInPictureRenderState replace(
            Object graphics, PictureInPictureRenderState vanillaState) {
        Minecraft12111LivePreviewSubmission submission = ACTIVE.get();
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
