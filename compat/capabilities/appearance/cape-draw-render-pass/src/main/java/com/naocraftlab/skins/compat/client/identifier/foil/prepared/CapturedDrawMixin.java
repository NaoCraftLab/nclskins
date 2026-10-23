package com.naocraftlab.skins.compat.client.identifier.foil.prepared;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderTypeFeatureRenderer.class)
public abstract class CapturedDrawMixin {
    @WrapOperation(method = "executeGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Lnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/commands/RenderPass;ILjava/util/List;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/PreparedRenderType;drawFromBuffer(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"),
            require = 1, expect = 1, allow = 1)
    private void nclskins$drawLiveCape(PreparedRenderType prepared, StagedVertexBuffer.ExecuteInfo info,
            RenderPass pass, Operation<Void> original) {
        CapturedCapePass capture = ((CapturedCapePass.PreparedCarrier) (Object) prepared).nclskins$capture();
        if (capture == null || capture.isValid()) original.call(prepared, info, pass);
    }

    @WrapOperation(method = "executeGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Lnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/commands/RenderPass;ILjava/util/List;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/PreparedRenderType;drawFromBufferOit(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;Lnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"),
            require = 1, expect = 1, allow = 1)
    private void nclskins$drawLiveCapeOit(PreparedRenderType prepared, StagedVertexBuffer.ExecuteInfo info,
            OitStage stage, RenderPass pass, Operation<Void> original) {
        CapturedCapePass capture = ((CapturedCapePass.PreparedCarrier) (Object) prepared).nclskins$capture();
        if (capture == null || capture.isValid()) original.call(prepared, info, stage, pass);
    }
}
