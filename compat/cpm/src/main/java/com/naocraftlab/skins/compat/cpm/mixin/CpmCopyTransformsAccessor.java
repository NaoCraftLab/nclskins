package com.naocraftlab.skins.compat.cpm.mixin;

import java.util.List;
import com.tom.cpm.shared.model.CopyTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.tom.cpm.shared.animation.AnimationRegistry", remap = false)
public interface CpmCopyTransformsAccessor {
    @Accessor("copyTransforms") List<CopyTransform> nclskins$copyTransforms();
}
