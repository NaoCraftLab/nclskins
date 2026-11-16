package com.naocraftlab.skins.buildlogic.probe;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=CapeLayer.class,remap=false)
public abstract class ReferenceCapeSliceMixin {
    @Inject(method="submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void reference$bodySlice(PoseStack pose,SubmitNodeCollector collector,int light,AvatarRenderState state,float yaw,float pitch,CallbackInfo ci){if(ReferenceNeutral.excludes(state)){ReferenceNeutral.capeExclusions++;ci.cancel();}}
}
