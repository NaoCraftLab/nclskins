package com.naocraftlab.skins.compat.cpm.mixin;

import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.tom.cpm.shared.parts.ModelPartAnimation", remap = false)
public interface CpmLegacyAnimationsAccessor {
    @Accessor("parsedData") Map<Integer, ?> nclskins$legacyAnimations();
}
