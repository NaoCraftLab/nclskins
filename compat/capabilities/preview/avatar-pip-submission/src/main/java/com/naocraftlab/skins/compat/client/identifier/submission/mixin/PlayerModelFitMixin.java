package com.naocraftlab.skins.compat.client.identifier.submission.mixin;

import com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerModel.class)
abstract class PlayerModelFitMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V",
            at = @At("RETURN"), require = 1, expect = 1, allow = 1)
    private void nclskins$neutralPose(AvatarRenderState state, CallbackInfo callback) {
        if (AvatarBodyCapture.excludes(state)) ((PlayerModel) (Object) this).resetPose();
    }
}
