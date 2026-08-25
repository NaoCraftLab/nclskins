package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Unit;

public final class NclBakedPlayerRenderer
        extends PictureInPictureRenderer<NclBakedPlayerRenderState> {
    private final Set<NclBakedPlayerTarget> targets =
            Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public Class<NclBakedPlayerRenderState> getRenderStateClass() {
        return NclBakedPlayerRenderState.class;
    }

    @Override
    public void prepare(
            NclBakedPlayerRenderState state,
            GuiRenderState guiRenderState,
            FeatureRenderDispatcher featureRenderDispatcher,
            int guiScale) {
        NclBakedPlayerTarget target = state.target();
        TargetRenderer renderer = target.acquire(
                this,
                () -> {
                    targets.add(target);
                    return new TargetRenderer();
                },
                TargetRenderer.class);
        renderer.prepare(state, guiRenderState, featureRenderDispatcher, guiScale);
    }

    @Override
    protected float getTranslateY(int textureHeight, int guiScale) {
        return textureHeight / 2.0F;
    }

    @Override
    protected void renderToTexture(
            NclBakedPlayerRenderState state,
            PoseStack pose,
            SubmitNodeCollector collector) {
        throw new IllegalStateException("Dispatcher render target must not be used");
    }

    @Override
    public void close() {
        for (NclBakedPlayerTarget target : new ArrayList<>(targets)) {
            target.release(this);
        }
        targets.clear();
        super.close();
    }

    @Override
    protected String getTextureLabel() {
        return "NCL Skins baked player dispatcher";
    }

    private static final class TargetRenderer
            extends PictureInPictureRenderer<NclBakedPlayerRenderState> {
        @Override
        public Class<NclBakedPlayerRenderState> getRenderStateClass() {
            return NclBakedPlayerRenderState.class;
        }

        @Override
        protected float getTranslateY(int textureHeight, int guiScale) {
            return textureHeight / 2.0F;
        }

        @Override
        protected void renderToTexture(
                NclBakedPlayerRenderState state,
                PoseStack pose,
                SubmitNodeCollector collector) {
            Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.PLAYER_SKIN);
            BakedPlayerPose.configure(state);
            pose.pushPose();
            try {
                BakedPlayerPose.applyPitch(pose, state.pitchDegrees());
                if (state.standaloneEquipment()) {
                    BakedPlayerPose.applyStandaloneEquipment(pose, state);
                } else {
                    BakedPlayerPose.applyPlayer(pose, state);
                }
                Model.Simple attachment = state.simpleAttachmentModel();
                if (attachment != null) {
                    pose.pushPose();
                    try {
                        BakedPlayerPose.applyAttachment(pose, state.capeMode());
                        collector.submitModel(
                                attachment,
                                Unit.INSTANCE,
                                pose,
                                state.attachmentTexture(),
                                0x00F000F0,
                                OverlayTexture.NO_OVERLAY,
                                0,
                                null);
                    } finally {
                        pose.popPose();
                    }
                }
                if (!state.standaloneEquipment()) {
                    collector.submitModel(
                            state.simplePlayerModel(),
                            Unit.INSTANCE,
                            pose,
                            state.skinTexture(),
                            0x00F000F0,
                            OverlayTexture.NO_OVERLAY,
                            0,
                            null);
                }
            } finally {
                pose.popPose();
            }
        }

        @Override
        protected String getTextureLabel() {
            return "NCL Skins baked player target";
        }
    }
}
