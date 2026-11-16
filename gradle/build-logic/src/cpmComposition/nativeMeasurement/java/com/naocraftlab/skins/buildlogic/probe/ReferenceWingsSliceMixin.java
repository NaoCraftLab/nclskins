package com.naocraftlab.skins.buildlogic.probe;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=WingsLayer.class,remap=false)
public abstract class ReferenceWingsSliceMixin {
    @Inject(method="submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void reference$bodySlice(PoseStack pose,SubmitNodeCollector collector,int light,HumanoidRenderState state,float yaw,float pitch,CallbackInfo ci){if(ReferenceNeutral.excludes(state)){ReferenceNeutral.wingExclusions++;ci.cancel();}}
}
