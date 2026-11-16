package com.naocraftlab.skins.compat.client.identifier.submission.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MultiBufferSource.BufferSource.class)
abstract class BufferSourceFitMixin {
    @WrapOperation(method = "endBatch(Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/BufferBuilder;)V", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;build()Lcom/mojang/blaze3d/vertex/MeshData;"),
            require = 1, expect = 1, allow = 1)
    private MeshData nclskins$observeBody(BufferBuilder builder, Operation<MeshData> original) {
        MeshData mesh = original.call(builder);
        if (mesh != null && AvatarBodyCapture.collecting()) {
            var draw = mesh.drawState();
            var format = draw.format();
            var bytes = mesh.vertexBuffer().duplicate().order(java.nio.ByteOrder.nativeOrder());
            int stride = format.getVertexSize();
            int position = format.getOffset(com.mojang.blaze3d.vertex.VertexFormatElement.POSITION);
            for (int i = 0; i < draw.vertexCount(); i++) {
                int offset = i * stride + position;
                AvatarBodyCapture.vertex(bytes.getFloat(offset), bytes.getFloat(offset + 4), bytes.getFloat(offset + 8));
            }
        }
        return mesh;
    }
}
