package com.naocraftlab.skins.compat.mc12111;

import java.util.Objects;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;


public final class Minecraft12111BakedPreviewSubmission implements AutoCloseable {
    private static final ThreadLocal<Minecraft12111BakedPreviewSubmission> ACTIVE =
            new ThreadLocal<>();

    private final Object graphics;
    private final Minecraft12111BakedPreviewRenderState state;
    private boolean consumed;

    private Minecraft12111BakedPreviewSubmission(
            Object graphics, Minecraft12111BakedPreviewRenderState state) {
        this.graphics = Objects.requireNonNull(graphics, "graphics");
        this.state = Objects.requireNonNull(state, "state");
    }

    public static Minecraft12111BakedPreviewSubmission open(
            Object graphics, Minecraft12111BakedPreviewRenderState state) {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("Baked preview submission is already active");
        }
        Minecraft12111BakedPreviewSubmission submission =
                new Minecraft12111BakedPreviewSubmission(graphics, state);
        ACTIVE.set(submission);
        return submission;
    }

    public static PictureInPictureRenderState replace(
            Object graphics, PictureInPictureRenderState vanillaState) {
        Minecraft12111BakedPreviewSubmission submission = ACTIVE.get();
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
