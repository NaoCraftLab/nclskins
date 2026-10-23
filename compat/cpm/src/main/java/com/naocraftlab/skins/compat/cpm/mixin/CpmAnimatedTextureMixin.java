package com.naocraftlab.skins.compat.cpm.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.cpm.OwnedCpmDefinition;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.ModelDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.tom.cpm.shared.parts.ModelPartAnimatedTexture", remap = false)
abstract class CpmAnimatedTextureMixin {
    @SuppressWarnings("deprecation")
    @WrapOperation(method = "apply(Lcom/tom/cpm/shared/definition/ModelDefinition;)V",
            at = @At(value = "INVOKE", target = "Lcom/tom/cpm/shared/config/Player;isClientPlayer()Z"),
            require = 1, expect = 1, allow = 1)
    private boolean nclskins$selfResources(Player<?> player, Operation<Boolean> original, ModelDefinition definition) {
        return definition instanceof OwnedCpmDefinition owned && owned.getPlayerObj() == player
                && owned.selfResources() || original.call(player);
    }
}
