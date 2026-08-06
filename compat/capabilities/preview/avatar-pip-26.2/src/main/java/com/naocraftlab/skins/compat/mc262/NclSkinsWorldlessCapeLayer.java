package com.naocraftlab.skins.compat.mc262;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;


public final class NclSkinsWorldlessCapeLayer
        extends RenderLayer<AvatarRenderState, PlayerModel> {
    private final PlayerCapeModel model = new PlayerCapeModel(
            PlayerCapeModel.createCapeLayer().bakeRoot());

    public NclSkinsWorldlessCapeLayer(
            RenderLayerParent<AvatarRenderState, PlayerModel> renderer) {
        super(renderer);
    }

    @Override
    public void submit(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int packedLight,
            AvatarRenderState state,
            float bodyYaw,
            float bodyPitch) {
        Identifier texture = ((NclSkinsWideDepthState) state).nclskins$worldlessCapeTexture();
        if (texture == null || state.isInvisible) {
            return;
        }
        collector.submitModel(
                model,
                state,
                poseStack,
                RenderTypes.entitySolid(texture),
                packedLight,
                OverlayTexture.NO_OVERLAY,
                state.outlineColor,
                null);
    }
}
