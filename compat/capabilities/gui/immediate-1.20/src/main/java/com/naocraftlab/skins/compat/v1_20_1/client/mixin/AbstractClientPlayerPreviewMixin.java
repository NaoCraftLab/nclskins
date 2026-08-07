package com.naocraftlab.skins.compat.v1_20_1.client.mixin;

import com.naocraftlab.skins.compat.v1_20_1.client.Minecraft1201PreviewScope;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerPreviewMixin {
    @Inject(method = "getSkinTextureLocation", at = @At("RETURN"), cancellable = true)
    private void nclskins$skin(CallbackInfoReturnable<ResourceLocation> callback) {
        callback.setReturnValue(Minecraft1201PreviewScope.skin(self(), callback.getReturnValue()));
    }

    @Inject(method = "getCloakTextureLocation", at = @At("RETURN"), cancellable = true)
    private void nclskins$cape(CallbackInfoReturnable<ResourceLocation> callback) {
        callback.setReturnValue(Minecraft1201PreviewScope.cape(self(), callback.getReturnValue()));
    }

    @Inject(method = "getElytraTextureLocation", at = @At("RETURN"), cancellable = true)
    private void nclskins$elytra(CallbackInfoReturnable<ResourceLocation> callback) {
        callback.setReturnValue(Minecraft1201PreviewScope.elytra(self(), callback.getReturnValue()));
    }

    @Inject(method = "getModelName", at = @At("RETURN"), cancellable = true)
    private void nclskins$model(CallbackInfoReturnable<String> callback) {
        callback.setReturnValue(Minecraft1201PreviewScope.model(self(), callback.getReturnValue()));
    }

    @Inject(method = "isSkinLoaded", at = @At("RETURN"), cancellable = true)
    private void nclskins$skinLoaded(CallbackInfoReturnable<Boolean> callback) {
        overrideLoaded(Minecraft1201PreviewScope.Texture.SKIN, callback);
    }

    @Inject(method = "isCapeLoaded", at = @At("RETURN"), cancellable = true)
    private void nclskins$capeLoaded(CallbackInfoReturnable<Boolean> callback) {
        overrideLoaded(Minecraft1201PreviewScope.Texture.CAPE, callback);
    }

    @Inject(method = "isElytraLoaded", at = @At("RETURN"), cancellable = true)
    private void nclskins$elytraLoaded(CallbackInfoReturnable<Boolean> callback) {
        overrideLoaded(Minecraft1201PreviewScope.Texture.ELYTRA, callback);
    }

    private void overrideLoaded(
            Minecraft1201PreviewScope.Texture texture,
            CallbackInfoReturnable<Boolean> callback) {
        Boolean value = Minecraft1201PreviewScope.textureLoaded(self(), texture);
        if (value != null) {
            callback.setReturnValue(value);
        }
    }

    private AbstractClientPlayer self() {
        return (AbstractClientPlayer) (Object) this;
    }
}
