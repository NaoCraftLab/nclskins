package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.naocraftlab.skins.client.CenteredPlayerPreviewGeometry;
import com.naocraftlab.skins.client.PreviewRenderer;
import org.joml.Matrix4f;

public final class PlayerSkinProjection implements AutoCloseable {
    private final Matrix4f previous;
    private final VertexSorting sorting;
    private PlayerSkinProjection(Matrix4f previous, VertexSorting sorting) {
        this.previous = previous; this.sorting = sorting;
    }
    public static PlayerSkinProjection open(PreviewRenderer.ModelFitMeasurement measured,
            PreviewRenderer.PreviewRequest request, float scale, float entityHeight) {
        var original = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var scope = new PlayerSkinProjection(original, sorting);
        var fit = measured.fit();
        double yaw = Math.toRadians(request.yawDegrees()), pitch = Math.toRadians(request.pitchDegrees());
        double horizontal = fit.halfWidth() * Math.abs(Math.sin(yaw)) + fit.halfDepth() * Math.abs(Math.cos(yaw));
        double centerY = fit.centerY() - CenteredPlayerPreviewGeometry.modernEntityTranslationY(entityHeight);
        double centerZ = -fit.centerX() * Math.sin(yaw) + fit.centerZ() * Math.cos(yaw);
        double radius = 50 + com.naocraftlab.skins.client.LegacyPreviewDepth.additional(scale, 50) + 16 + scale * ((fit.bodyHeight() * 0.5 + Math.abs(centerY)) * Math.abs(Math.sin(pitch))
                + (horizontal + Math.abs(centerZ)) * Math.abs(Math.cos(pitch)));
        if (original.m22() < 0 && original.m23() == 0 && original.m33() == 1) {
            double center = -original.m32() / original.m22();
            double half = Math.max(-1.0 / original.m22(), radius);
            var expanded = new Matrix4f(original).m22((float) (-1.0 / half)).m32((float) (center / half));
            RenderSystem.setProjectionMatrix(expanded, sorting);
        }
        return scope;
    }
    @Override public void close() { RenderSystem.setProjectionMatrix(previous, sorting); }
}
