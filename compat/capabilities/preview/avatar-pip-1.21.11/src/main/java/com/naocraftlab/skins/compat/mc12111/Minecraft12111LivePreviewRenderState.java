package com.naocraftlab.skins.compat.mc12111;

import com.naocraftlab.skins.client.PreviewRenderer;
import java.util.Objects;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import org.joml.Quaternionf;
import org.joml.Vector3f;


public final class Minecraft12111LivePreviewRenderState
        implements PictureInPictureRenderState {
    private final Minecraft12111PreviewRenderer.PreviewPlayer previewPlayer;
    private final Minecraft12111PreviewContext previewContext;
    private final PreviewRenderer.PreviewRequest request;
    private final float previewAge;
    private final Vector3f translation;
    private final Quaternionf rotation;
    private final Quaternionf overrideCameraAngle;
    private final float scale;
    private final Minecraft12111PreviewFailureSink failureSink;
    private final Minecraft12111PreviewFailureSink layerFailureSink;
    private final ScreenRectangle scissorArea;
    private final ScreenRectangle bounds;

    Minecraft12111LivePreviewRenderState(
            Minecraft12111PreviewRenderer.PreviewPlayer previewPlayer,
            Minecraft12111PreviewContext previewContext,
            PreviewRenderer.PreviewRequest request,
            float previewAge,
            Vector3f translation,
            Quaternionf rotation,
            Quaternionf overrideCameraAngle,
            float scale,
            Minecraft12111PreviewFailureSink failureSink,
            Minecraft12111PreviewFailureSink layerFailureSink,
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
                request.left(),
                request.top(),
                request.left() + request.width(),
                request.top() + request.height(),
                scissorArea);
    }

    Minecraft12111PreviewRenderer.PreviewPlayer previewPlayer() {
        return previewPlayer;
    }

    Minecraft12111PreviewContext previewContext() {
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

    Minecraft12111PreviewFailureSink failureSink() {
        return failureSink;
    }

    Minecraft12111PreviewFailureSink layerFailureSink() {
        return layerFailureSink;
    }

    @Override
    public int x0() {
        return request.left();
    }

    @Override
    public int y0() {
        return request.top();
    }

    @Override
    public int x1() {
        return request.left() + request.width();
    }

    @Override
    public int y1() {
        return request.top() + request.height();
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

    Minecraft12111LivePreviewRenderState withScissor(ScreenRectangle scissor) {
        return new Minecraft12111LivePreviewRenderState(
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
