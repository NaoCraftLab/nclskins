package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.ears.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.ears.EarsNeutralBody;
import com.unascribed.ears.api.features.EarsFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "diy.y2k.five.buzz.Buzz", remap = false)
abstract class EarsMeasurementMixin {
    @WrapMethod(method = "getEarsFeatures(Ljava/lang/Object;)Lcom/unascribed/ears/api/features/EarsFeatures;",
            require = 1, expect = 1, allow = 1)
    private static EarsFeatures nclskins$neutralFeatures(Object player, Operation<EarsFeatures> original) {
        return EarsNeutralBody.select(original.call(player), player);
    }
}
