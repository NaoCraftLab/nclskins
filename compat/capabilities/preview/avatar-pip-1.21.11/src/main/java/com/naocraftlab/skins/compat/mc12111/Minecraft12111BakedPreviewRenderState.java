package com.naocraftlab.skins.compat.mc12111;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import java.util.Objects;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.object.equipment.ElytraModel;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.resources.Identifier;


public record Minecraft12111BakedPreviewRenderState(
        Minecraft12111BakedPreviewTarget target,
        PlayerModel playerModel,
        Model<?> attachmentModel,
        PlayerCapeModel capePoseModel,
        ElytraModel elytraPoseModel,
        Identifier skinTexture,
        Identifier attachmentTexture,
        PreviewRenderer.CapeMode capeMode,
        OuterLayerVisibility outerLayerVisibility,
        float pitchDegrees,
        float yawDegrees,
        int x0,
        int y0,
        int x1,
        int y1,
        float scale,
        boolean standaloneEquipment,
        ScreenRectangle scissorArea,
        ScreenRectangle bounds) implements PictureInPictureRenderState {

    public Minecraft12111BakedPreviewRenderState(
            Minecraft12111BakedPreviewTarget target,
            PlayerModel playerModel,
            Model<?> attachmentModel,
            PlayerCapeModel capePoseModel,
            ElytraModel elytraPoseModel,
            Identifier skinTexture,
            Identifier attachmentTexture,
            PreviewRenderer.CapeMode capeMode,
            OuterLayerVisibility outerLayerVisibility,
            float pitchDegrees,
            float yawDegrees,
            int x0,
            int y0,
            int x1,
            int y1,
            float scale,
            boolean standaloneEquipment,
            ScreenRectangle scissorArea) {
        this(
                target,
                playerModel,
                attachmentModel,
                capePoseModel,
                elytraPoseModel,
                skinTexture,
                attachmentTexture,
                capeMode,
                outerLayerVisibility,
                pitchDegrees,
                yawDegrees,
                x0,
                y0,
                x1,
                y1,
                scale,
                standaloneEquipment,
                scissorArea,
                PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
    }

    public Minecraft12111BakedPreviewRenderState {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(capeMode, "capeMode");
        Objects.requireNonNull(outerLayerVisibility, "outerLayerVisibility");
        if (standaloneEquipment) {
            if (playerModel != null || attachmentModel == null || attachmentTexture == null) {
                throw new IllegalArgumentException("Standalone equipment state is inconsistent");
            }
        } else if (playerModel == null || skinTexture == null) {
            throw new IllegalArgumentException("Player preview is missing its model or texture");
        }
        if (attachmentModel == null) {
            if (attachmentTexture != null
                    || capePoseModel != null
                    || elytraPoseModel != null
                    || capeMode != PreviewRenderer.CapeMode.OFF) {
                throw new IllegalArgumentException("Back equipment state is inconsistent");
            }
        } else if (attachmentTexture == null
                || (capePoseModel == null) == (elytraPoseModel == null)
                || capeMode == PreviewRenderer.CapeMode.OFF
                || (capeMode == PreviewRenderer.CapeMode.CAPE) != (capePoseModel != null)) {
            throw new IllegalArgumentException("Back equipment mode and pose model disagree");
        }
        if (x1 <= x0
                || y1 <= y0
                || !Float.isFinite(scale)
                || scale <= 0.0F
                || !Float.isFinite(pitchDegrees)
                || !Float.isFinite(yawDegrees)) {
            throw new IllegalArgumentException("Preview bounds and transform must be finite and positive");
        }
    }

    Minecraft12111BakedPreviewRenderState withScissor(ScreenRectangle scissor) {
        return new Minecraft12111BakedPreviewRenderState(
                target,
                playerModel,
                attachmentModel,
                capePoseModel,
                elytraPoseModel,
                skinTexture,
                attachmentTexture,
                capeMode,
                outerLayerVisibility,
                pitchDegrees,
                yawDegrees,
                x0,
                y0,
                x1,
                y1,
                scale,
                standaloneEquipment,
                scissor);
    }
}
