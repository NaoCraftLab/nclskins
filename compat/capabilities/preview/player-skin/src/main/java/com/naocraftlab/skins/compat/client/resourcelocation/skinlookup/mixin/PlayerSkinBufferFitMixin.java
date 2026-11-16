package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.PlayerSkinBodyCapture;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MultiBufferSource.BufferSource.class)
abstract class PlayerSkinBufferFitMixin {
    @WrapOperation(method = "endBatch(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/BufferBuilder;)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;build()Lcom/mojang/blaze3d/vertex/MeshData;"),
            require = 1, expect = 1, allow = 1)
    private MeshData nclskins$measureBody(BufferBuilder builder, Operation<MeshData> original) {
        return PlayerSkinBodyCapture.drain(original.call(builder));
    }
}
