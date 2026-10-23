package com.naocraftlab.skins.compat.client.identifier.foil.pipeline;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.compat.client.identifier.CapeMaterialSnapshot;
import com.naocraftlab.skins.compat.client.identifier.foil.CapeTranslucentFoilMaterial;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CapeLayer.class)
public abstract class CapeLayerFoilMixin {
    @WrapOperation(
            method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;entitySolid(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"),
            require = 1, expect = 1, allow = 1)
    private RenderType nclskins$capeFoil(Identifier texture, Operation<RenderType> original,
            PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state,
            float limbSwing, float limbSwingAmount,
            @Share("nativeFoil") LocalBooleanRef nativeFoil) {
        CapeMaterialSnapshot snapshot = ((CapeMaterialSnapshot.Carrier) state).nclskins$capeMaterial();
        if (snapshot != null && snapshot.capeGlint()
                && snapshot.textureLocation().equals(texture.toString())) {
            nativeFoil.set(CapeTranslucentFoilMaterial.inWorld());
            return CapeTranslucentFoilMaterial.forTexture(texture);
        }
        return original.call(texture);
    }

    @WrapOperation(
            method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"),
            require = 1, expect = 1, allow = 1)
    private <S> void nclskins$capeFoilSubmit(SubmitNodeCollector collector, Model<? super S> model,
            S state, PoseStack pose, RenderType type, int light, int overlay, int outline,
            ModelFeatureRenderer.CrumblingOverlay crumbling, Operation<Void> original,
            @Share("nativeFoil") LocalBooleanRef nativeFoil) {
        original.call(collector, model, state, pose, type, light, overlay, outline, crumbling);
        if (nativeFoil.get()) {
            collector.order(1).submitModel(model, state, pose, RenderTypes.entityGlint(),
                    light, overlay, 0, null);
        }
    }
}
