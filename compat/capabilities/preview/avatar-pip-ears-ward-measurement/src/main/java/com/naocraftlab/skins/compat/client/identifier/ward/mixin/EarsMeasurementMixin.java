package com.naocraftlab.skins.compat.client.identifier.ward.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.naocraftlab.skins.compat.client.identifier.ward.EarsNeutralBody;
import com.unascribed.ears.api.features.EarsFeatures;
import diy.y2k.five.ward.Ward;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Desc;

@Mixin(targets = "diy.y2k.five.ward.Ward", remap = false)
abstract class EarsMeasurementMixin {
    @Desc(id = "features", value = "getEarsFeatures", args = AvatarRenderState.class, ret = EarsFeatures.class)
    @ModifyExpressionValue(method = "@Desc(features)",
            at = @At(value = "INVOKE", desc = @Desc(owner = Ward.class, value = "getEarsFeatures",
                    args = {String.class, UUID.class, Identifier.class}, ret = EarsFeatures.class)),
            require = 1, expect = 1, allow = 1)
    private static EarsFeatures nclskins$neutralFeatures(EarsFeatures original, AvatarRenderState state) {
        return EarsNeutralBody.select(original, state);
    }
}
