package com.naocraftlab.skins.compat.client.identifier.submission;

import com.naocraftlab.skins.client.PreviewRenderer;
import java.util.Objects;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import org.joml.Quaternionf;
import org.joml.Vector3f;


public final class LivePreviewRenderState
        implements PictureInPictureRenderState {
    private final SubmissionPreviewRenderer.PreviewPlayer previewPlayer;
    private final PreviewContext previewContext;
    private final PreviewRenderer.PreviewRequest request;
    private final float previewAge;
    private final Vector3f translation;
    private final Quaternionf rotation;
    private final Quaternionf overrideCameraAngle;
    private final float scale;
    private final PreviewFailureSink failureSink;
    private final PreviewFailureSink layerFailureSink;
    private final ScreenRectangle scissorArea;
    private final ScreenRectangle bounds;

    LivePreviewRenderState(
            SubmissionPreviewRenderer.PreviewPlayer previewPlayer,
            PreviewContext previewContext,
            PreviewRenderer.PreviewRequest request,
            float previewAge,
            Vector3f translation,
            Quaternionf rotation,
            Quaternionf overrideCameraAngle,
            float scale,
            PreviewFailureSink failureSink,
            PreviewFailureSink layerFailureSink,
            ScreenRectangle scissorArea) {
        this.previewPlayer = Objects.requireNonNull(previewPlayer, "previewPlayer");
        this.previewContext = Objects.requireNonNull(previewContext, "previewContext");
        this.request = Objects.requireNonNull(request, "request");
        if (!Float.isFinite(previewAge) || previewAge < 0.0F) {
            throw new IllegalArgumentException("Preview age must be finite and non-negative");
        }
        this.previewAge = previewAge;
        this.translation = new Vector3f(Objects.requireNonNull(translation, "translation"));
        this.rotation = new Quaternionf(Objects.requireNonNull(rotation, "rotation"));
        this.overrideCameraAngle =
                new Quaternionf(Objects.requireNonNull(overrideCameraAngle, "overrideCameraAngle"));
        if (!Float.isFinite(scale) || scale <= 0.0F) {
            throw new IllegalArgumentException("Preview scale must be finite and positive");
        }
        this.scale = scale;
        this.failureSink = Objects.requireNonNull(failureSink, "failureSink");
        this.layerFailureSink = Objects.requireNonNull(layerFailureSink, "layerFailureSink");
        this.scissorArea = scissorArea;
        this.bounds = PictureInPictureRenderState.getBounds(
                request.stageLeft(),
                request.stageTop(),
                request.stageLeft() + request.stageWidth(),
                request.stageTop() + request.stageHeight(),
                scissorArea);
    }

    SubmissionPreviewRenderer.PreviewPlayer previewPlayer() {
        return previewPlayer;
    }

    PreviewContext previewContext() {
        return previewContext;
    }

    PreviewRenderer.PreviewRequest request() {
        return request;
    }

    float previewAge() {
        return previewAge;
    }

    Vector3f translation() {
        return translation;
    }

    Quaternionf rotation() {
        return rotation;
    }

    Quaternionf overrideCameraAngle() {
        return overrideCameraAngle;
    }

    PreviewFailureSink failureSink() {
        return failureSink;
    }

    PreviewFailureSink layerFailureSink() {
        return layerFailureSink;
    }

    @Override
    public int x0() {
        return request.stageLeft();
    }

    @Override
    public int y0() {
        return request.stageTop();
    }

    @Override
    public int x1() {
        return request.stageLeft() + request.stageWidth();
    }

    @Override
    public int y1() {
        return request.stageTop() + request.stageHeight();
    }

    @Override
    public float scale() {
        return scale;
    }

    @Override
    public ScreenRectangle scissorArea() {
        return scissorArea;
    }

    @Override
    public ScreenRectangle bounds() {
        return bounds;
    }

    LivePreviewRenderState withScissor(ScreenRectangle scissor) {
        return new LivePreviewRenderState(
                previewPlayer,
                previewContext,
                request,
                previewAge,
                translation,
                rotation,
                overrideCameraAngle,
                scale,
                failureSink,
                layerFailureSink,
                scissor);
    }
}
