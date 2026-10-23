package com.naocraftlab.skins.compat.cpm.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.cpm.CpmCapePart;
import com.naocraftlab.skins.compat.cpm.CpmIntegration;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.render.ModelRenderManager;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = {"com.tom.cpm.client.PlayerRenderManager$RedirectModelRendererBase",
        "com.tom.cpm.client.PlayerRenderManager$RedirectModelRenderer"}, remap = false)
abstract class RedirectRendererMixin {
    @WrapOperation(method = "getPart()Lcom/tom/cpm/shared/model/render/VanillaModelPart;", at = {
            @At(value = "FIELD", target = "Lcom/tom/cpm/client/PlayerRenderManager$RedirectModelRendererBase;part:Lcom/tom/cpm/shared/model/render/VanillaModelPart;"),
            @At(value = "FIELD", target = "Lcom/tom/cpm/client/PlayerRenderManager$RedirectModelRenderer;part:Lcom/tom/cpm/shared/model/render/VanillaModelPart;")},
            require = 1, expect = 1, allow = 1)
    private static VanillaModelPart nclskins$accessoryPart(@Coerce Object renderer, Operation<VanillaModelPart> original) {
        VanillaModelPart part = original.call(renderer);
        var holder = ((ModelRenderManager.RedirectRenderer<?>) renderer).getHolder();
        if (part == RootModelType.CAPE && holder.playerObj != null
                && !CpmIntegration.accessories(holder.playerObj, holder.def)) return CpmCapePart.ORDINARY;
        return part;
    }
}
