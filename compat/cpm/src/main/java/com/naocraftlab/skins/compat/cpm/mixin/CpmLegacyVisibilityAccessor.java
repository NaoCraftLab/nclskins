package com.naocraftlab.skins.compat.cpm.mixin;


import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.tom.cpm.shared.parts.ModelPartAnimation$ResolvedData", remap = false)
public interface CpmLegacyVisibilityAccessor {
    @Accessor("components") int[] nclskins$legacyVisibilityTargets();
}
