package com.naocraftlab.skins.compat.client.identifier.ears.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.naocraftlab.skins.compat.client.identifier.ears.EarsNeutralBody;
import com.unascribed.ears.api.features.EarsFeatures;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "diy.y2k.five.thermite.EarsLayerRenderer$1", remap = false)
abstract class EarsMeasurementMixin {
    @ModifyReturnValue(method = "getEarsFeatures()Lcom/unascribed/ears/api/features/EarsFeatures;",
            at = @At("RETURN"), require = 1, expect = 1, allow = 1)
    private EarsFeatures nclskins$neutralFeatures(EarsFeatures original) {
        return EarsNeutralBody.select(original, (AvatarRenderState)
                ((com.unascribed.ears.common.render.EarsRenderDelegate) (Object) this).getPeer());
    }
}
