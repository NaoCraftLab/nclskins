package com.naocraftlab.skins.compat.cpm.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.cpm.CpmIntegration;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CapeLayer.class, priority = 1100, remap = false)
abstract class CapeBranchRenderStateMixin {
    @WrapOperation(method = "@nclskins:cpmaccessories(com.tom.cpm.mixin.render.CapeLayerMixin;submitCape;(Lnet/minecraft/class_4587;Lnet/minecraft/class_11659;ILnet/minecraft/class_10055;FFLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V|(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FFLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V|(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/PlayerRenderState;FFLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V)", at = @At(value = "INVOKE", target = "Lcom/tom/cpm/shared/definition/ModelDefinition;hasRoot(Lcom/tom/cpm/shared/model/render/VanillaModelPart;)Z"), require = 1, expect = 1, allow = 1, remap = false)
    private boolean nclskins$accessoryCape(ModelDefinition definition, VanillaModelPart part, Operation<Boolean> original,
            @com.llamalad7.mixinextras.sugar.Local com.tom.cpm.shared.config.Player<?> bound) {
        return CpmIntegration.accessories(bound, definition) && original.call(definition, part);
    }
}
