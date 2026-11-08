package com.naocraftlab.skins.compat.cpm.mixin;

import com.tom.cpm.shared.parts.anim.AnimLoaderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.tom.cpm.shared.parts.anim.ModelPartAnimationNew", remap = false)
public interface CpmAnimationStateAccessor {
    @Accessor("state") AnimLoaderState nclskins$animationState();
}
