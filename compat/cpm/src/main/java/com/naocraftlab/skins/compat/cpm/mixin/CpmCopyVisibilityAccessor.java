package com.naocraftlab.skins.compat.cpm.mixin;

import com.tom.cpm.shared.model.RenderedCube;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.tom.cpm.shared.model.CopyTransform", remap = false)
public interface CpmCopyVisibilityAccessor {
    @Accessor("to") RenderedCube nclskins$copyTarget();
    @Accessor("copyVis") boolean nclskins$copyVisibility();
}
