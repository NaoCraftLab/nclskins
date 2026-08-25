package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.mixin;

import com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.PreviewScope;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerPreviewMixin {
    @ModifyReturnValue(method = "getSkinTextureLocation", at = @At("RETURN"))
    private ResourceLocation nclskins$skin(ResourceLocation original) {
        return PreviewScope.skin(self(), original);
    }

    @ModifyReturnValue(method = "getCloakTextureLocation", at = @At("RETURN"))
    private ResourceLocation nclskins$cape(ResourceLocation original) {
        return PreviewScope.cape(self(), original);
    }

    @ModifyReturnValue(method = "getElytraTextureLocation", at = @At("RETURN"))
    private ResourceLocation nclskins$elytra(ResourceLocation original) {
        return PreviewScope.elytra(self(), original);
    }

    @ModifyReturnValue(method = "getModelName", at = @At("RETURN"))
    private String nclskins$model(String original) {
        return PreviewScope.model(self(), original);
    }

    @ModifyReturnValue(method = "isSkinLoaded", at = @At("RETURN"))
    private boolean nclskins$skinLoaded(boolean original) {
        return overrideLoaded(PreviewScope.Texture.SKIN, original);
    }

    @ModifyReturnValue(method = "isCapeLoaded", at = @At("RETURN"))
    private boolean nclskins$capeLoaded(boolean original) {
        return overrideLoaded(PreviewScope.Texture.CAPE, original);
    }

    @ModifyReturnValue(method = "isElytraLoaded", at = @At("RETURN"))
    private boolean nclskins$elytraLoaded(boolean original) {
        return overrideLoaded(PreviewScope.Texture.ELYTRA, original);
    }

    private boolean overrideLoaded(PreviewScope.Texture texture, boolean original) {
        Boolean value = PreviewScope.textureLoaded(self(), texture);
        return value == null ? original : value;
    }

    private AbstractClientPlayer self() {
        return (AbstractClientPlayer) (Object) this;
    }
}
