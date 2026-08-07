package com.naocraftlab.skins.mc1211.mixin;

import com.naocraftlab.skins.mc1211.Minecraft1211PreviewScope;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerPreviewMixin {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void nclskins$skin(CallbackInfoReturnable<PlayerSkin> callback) {
        AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
        callback.setReturnValue(Minecraft1211PreviewScope.skin(player, callback.getReturnValue()));
    }
}
