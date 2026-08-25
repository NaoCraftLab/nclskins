package com.naocraftlab.skins.compat.client.identifier.submission.mixin;

import com.naocraftlab.skins.compat.client.identifier.submission.PreviewScope;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Avatar.class)
abstract class AvatarPreviewMixin {
    @ModifyReturnValue(method = "isModelPartShown", at = @At("RETURN"))
    private boolean nclskins$modelPart(boolean original, PlayerModelPart part) {
        Avatar avatar = (Avatar) (Object) this;
        Boolean value = PreviewScope.modelPart(avatar, part);
        return value == null ? original : value;
    }
}
