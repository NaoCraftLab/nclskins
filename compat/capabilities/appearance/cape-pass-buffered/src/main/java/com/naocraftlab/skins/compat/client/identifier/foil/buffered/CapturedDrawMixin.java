package com.naocraftlab.skins.compat.client.identifier.foil.buffered;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.MeshData;
import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MultiBufferSource.BufferSource.class)
public abstract class CapturedDrawMixin {
    @WrapOperation(method = "endBatch(Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/BufferBuilder;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderType;draw(Lcom/mojang/blaze3d/vertex/MeshData;)V"),
            require = 1, expect = 1, allow = 1)
    private void nclskins$drawLiveCape(RenderType type, MeshData mesh, Operation<Void> original) {
        CapturedCapePass capture = CapturedCapePass.of(type);
        if (capture == null || capture.isValid()) original.call(type, mesh);
        else mesh.close();
    }
}
