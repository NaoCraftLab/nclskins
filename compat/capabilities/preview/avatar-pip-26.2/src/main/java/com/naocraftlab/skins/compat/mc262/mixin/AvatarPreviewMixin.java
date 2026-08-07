package com.naocraftlab.skins.compat.mc262.mixin;

import com.naocraftlab.skins.compat.mc262.Minecraft262PreviewScope;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Avatar.class)
abstract class AvatarPreviewMixin {
    @Inject(method = "isModelPartShown", at = @At("RETURN"), cancellable = true)
    private void nclskins$modelPart(
            PlayerModelPart part,
            CallbackInfoReturnable<Boolean> callback) {
        Avatar avatar = (Avatar) (Object) this;
        Boolean value = Minecraft262PreviewScope.modelPart(avatar, part);
        if (value != null) {
            callback.setReturnValue(value);
        }
    }
}
