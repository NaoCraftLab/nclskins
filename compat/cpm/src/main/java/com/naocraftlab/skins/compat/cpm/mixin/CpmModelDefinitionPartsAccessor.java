package com.naocraftlab.skins.compat.cpm.mixin;

import com.tom.cpm.shared.parts.IModelPart;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.tom.cpm.shared.parts.ModelPartDefinition", remap = false)
public interface CpmModelDefinitionPartsAccessor {
    @Accessor("otherParts") List<IModelPart> nclskins$modelParts();
}
