package com.naocraftlab.skins.compat.cpm.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.naocraftlab.skins.compat.cpm.CpmIntegration;
import com.tom.cpm.shared.model.RenderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "com.tom.cpm.shared.model.RenderManager", remap = false)
abstract class RenderManagerMixin {
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "getStatus(Ljava/lang/Object;Ljava/lang/String;)Lcom/tom/cpl/text/FormatText;",
            at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
                    target = "Lcom/tom/cpm/shared/definition/ModelDefinition;getStatus()Lcom/tom/cpl/text/FormatText;"),
            require = 1, expect = 1, allow = 1)
    private com.tom.cpl.text.FormatText nclskins$previewStatus(com.tom.cpm.shared.definition.ModelDefinition definition,
            Operation<com.tom.cpl.text.FormatText> original,
            @com.llamalad7.mixinextras.sugar.Local com.tom.cpm.shared.config.Player<?> player) {
        var source = CpmIntegration.source(player);
        return source != null && source.preview() && source.active() ? null : original.call(definition);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapMethod(method = "bindElytra(Ljava/lang/Object;Ljava/lang/Object;)V")
    private void nclskins$accessoryWings(Object parent, Object wings, Operation<Void> original) {
        RenderManager manager = (RenderManager) (Object) this;
        var bound = manager.getPlayerFromModel(parent);
        if (bound == null || CpmIntegration.accessories(bound.player, bound.definition)) original.call(parent, wings);
        else manager.unbind(wings);
    }
}
