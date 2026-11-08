package com.naocraftlab.skins.compat.cpm.mixin;


import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.tom.cpm.shared.parts.anim.AnimatorChannel$CubeVisDriver", remap = false)
public interface CpmVisibilityTargetAccessor {
    @Accessor("cubeId") int nclskins$visibilityTarget();
}
