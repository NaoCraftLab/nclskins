package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.mixin;

import com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.PreviewScope;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerPreviewMixin {
    @ModifyReturnValue(method = "getSkin", at = @At("RETURN"))
    private PlayerSkin nclskins$skin(PlayerSkin original) {
        AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
        return PreviewScope.skin(player, original);
    }
}
