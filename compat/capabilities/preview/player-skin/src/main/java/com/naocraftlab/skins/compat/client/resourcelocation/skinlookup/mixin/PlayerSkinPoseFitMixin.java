package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.mixin;

import com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture;
import java.util.List;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerModel.class)
abstract class PlayerSkinPoseFitMixin {
    @Shadow @Final private List<ModelPart> parts;

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at = @At("RETURN"), require = 1, expect = 1, allow = 1)
    private void nclskins$authoredPose(LivingEntity player, float limb, float amount, float age, float yaw,
            float pitch, CallbackInfo callback) {
        if (AvatarBodyCapture.excludes(player)) parts.forEach(ModelPart::resetPose);
    }
}
