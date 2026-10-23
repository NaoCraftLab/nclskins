package com.naocraftlab.skins.compat.client.identifier.foil.prepared;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public abstract class CapturedDrawGroupMixin {
    @WrapOperation(method = "getOrAddDraw(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lnet/minecraft/client/renderer/StagedVertexBuffer$Draw;",
            at = @At(value = "INVOKE", target = "Ljava/util/List;indexOf(Ljava/lang/Object;)I"),
            require = 1, expect = 1, allow = 1)
    private int nclskins$isolateCapturedDraw(List<?> values, Object prepared, Operation<Integer> original) {
        if (((CapturedCapePass.PreparedCarrier) prepared).nclskins$capture() != null) return -1;
        int found = original.call(values, prepared);
        if (found < 0 || ((CapturedCapePass.PreparedCarrier) values.get(found)).nclskins$capture() == null)
            return found;
        for (int index = 0; index < values.size(); index++) {
            Object candidate = values.get(index);
            if (((CapturedCapePass.PreparedCarrier) candidate).nclskins$capture() == null
                    && prepared.equals(candidate)) return index;
        }
        return -1;
    }
}
