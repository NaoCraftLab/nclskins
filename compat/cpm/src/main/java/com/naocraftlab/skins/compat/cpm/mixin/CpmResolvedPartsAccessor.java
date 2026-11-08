package com.naocraftlab.skins.compat.cpm.mixin;

import java.util.List;
import com.tom.cpm.shared.parts.IResolvedModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.tom.cpm.shared.definition.ModelDefinition", remap = false)
public interface CpmResolvedPartsAccessor {
    @Accessor("resolved") List<IResolvedModelPart> nclskins$resolvedParts();
}
