package com.naocraftlab.skins.compat.mc12111.mixin;

import com.naocraftlab.skins.compat.mc12111.Minecraft12111PreviewScope;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerPreviewMixin {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void nclskins$skin(CallbackInfoReturnable<PlayerSkin> callback) {
        AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
        callback.setReturnValue(
                Minecraft12111PreviewScope.skin(player, callback.getReturnValue()));
    }
}
