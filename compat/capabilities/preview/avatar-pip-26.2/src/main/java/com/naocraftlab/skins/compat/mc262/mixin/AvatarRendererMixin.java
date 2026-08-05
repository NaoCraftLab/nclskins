package com.naocraftlab.skins.compat.mc262.mixin;

import com.naocraftlab.skins.compat.mc262.NclSkinsPreviewCapeLayer;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(AvatarRenderer.class)
abstract class AvatarRendererMixin
        extends LivingEntityRenderer<AbstractClientPlayer, AvatarRenderState, PlayerModel> {
    protected AvatarRendererMixin(
            EntityRendererProvider.Context context,
            PlayerModel model,
            float shadowRadius) {
        super(context, model, shadowRadius);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void nclskins$addPreviewCapeLayer(
            EntityRendererProvider.Context context,
            boolean slim,
            CallbackInfo callbackInfo) {
        addLayer(new NclSkinsPreviewCapeLayer(this));
    }
}
