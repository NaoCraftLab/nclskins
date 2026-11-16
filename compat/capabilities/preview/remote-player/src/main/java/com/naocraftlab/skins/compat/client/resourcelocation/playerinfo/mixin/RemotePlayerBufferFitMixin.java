package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture;
import com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.RemotePlayerBodyCapture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MultiBufferSource.BufferSource.class)
abstract class RemotePlayerBufferFitMixin {
    @WrapOperation(method = "endBatch(Lnet/minecraft/client/renderer/RenderType;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderType;end(Lcom/mojang/blaze3d/vertex/BufferBuilder;Lcom/mojang/blaze3d/vertex/VertexSorting;)V"),
            require = 1, expect = 1, allow = 1)
    private void nclskins$measureBody(RenderType type, BufferBuilder builder, VertexSorting sorting, Operation<Void> original) {
        if (AvatarBodyCapture.hiding()) {
            if (builder.building()) RemotePlayerBodyCapture.drain(builder.end());
        } else original.call(type, builder, sorting);
    }
}
