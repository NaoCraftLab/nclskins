package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.PreviewRenderer;
import java.util.Objects;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.object.equipment.ElytraModel;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.resources.Identifier;

public record NclBakedPlayerRenderState(
        NclBakedPlayerTarget target,
        PlayerModel playerModel,
        Model.Simple simplePlayerModel,
        PlayerModel attachmentPlayerModel,
        Model.Simple simpleAttachmentModel,
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

    public NclBakedPlayerRenderState(
            NclBakedPlayerTarget target,
            PlayerModel playerModel,
            Model.Simple simplePlayerModel,
            PlayerModel attachmentPlayerModel,
            Model.Simple simpleAttachmentModel,
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
                simplePlayerModel,
                attachmentPlayerModel,
                simpleAttachmentModel,
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

    public NclBakedPlayerRenderState {
        Objects.requireNonNull(target, "target");
        if (!standaloneEquipment && (playerModel == null) == (simplePlayerModel == null)) {
            throw new IllegalArgumentException("Exactly one baked player model is required");
        }
        if (standaloneEquipment && (playerModel != null || simplePlayerModel != null)) {
            throw new IllegalArgumentException("Standalone equipment must not carry a player model");
        }
        if (attachmentTexture == null) {
            if (attachmentPlayerModel != null
                    || simpleAttachmentModel != null
                    || capePoseModel != null
                    || elytraPoseModel != null) {
                throw new IllegalArgumentException("Back equipment texture is missing");
            }
        } else if ((attachmentPlayerModel == null) == (simpleAttachmentModel == null)) {
            throw new IllegalArgumentException("Exactly one back equipment model is required");
        } else if (capeMode == PreviewRenderer.CapeMode.CAPE
                ? capePoseModel == null || elytraPoseModel != null
                : capeMode == PreviewRenderer.CapeMode.ELYTRA
                        ? elytraPoseModel == null || capePoseModel != null
                        : true) {
            throw new IllegalArgumentException("Back equipment mode and neutral pose model disagree");
        }
        if (standaloneEquipment && attachmentTexture == null) {
            throw new IllegalArgumentException("Standalone equipment is missing");
        }
        if (!standaloneEquipment) {
            Objects.requireNonNull(skinTexture, "skinTexture");
        }
        Objects.requireNonNull(capeMode, "capeMode");
        Objects.requireNonNull(outerLayerVisibility, "outerLayerVisibility");
        if (x1 <= x0 || y1 <= y0 || !Float.isFinite(scale) || scale <= 0.0F) {
            throw new IllegalArgumentException("Baked preview bounds and scale must be positive");
        }
    }

    NclBakedPlayerRenderState withScissor(ScreenRectangle scissor) {
        return new NclBakedPlayerRenderState(
                target,
                playerModel,
                simplePlayerModel,
                attachmentPlayerModel,
                simpleAttachmentModel,
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
