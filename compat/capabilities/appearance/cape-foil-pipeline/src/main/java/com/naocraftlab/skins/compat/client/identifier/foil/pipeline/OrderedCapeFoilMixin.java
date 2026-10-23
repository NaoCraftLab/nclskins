package com.naocraftlab.skins.compat.client.identifier.foil.pipeline;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.compat.client.identifier.foil.OrderedCapeFoil;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SubmitNodeStorage.class)
public abstract class OrderedCapeFoilMixin {
    @Shadow public abstract SubmitNodeCollection order(int order);

    @WrapOperation(
            method = "submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V"),
            require = 1, expect = 1, allow = 1)
    private void nclskins$orderedCapeFoil(SubmitNodeCollection receiver, PoseStack pose, RenderType type,
            SubmitNodeCollector.CustomGeometryRenderer callback, Operation<Void> original) {
        if (OrderedCapeFoil.owns(type)) {
            original.call(order(1), pose, type, callback);
        } else {
            original.call(receiver, pose, type, callback);
        }
    }
}
