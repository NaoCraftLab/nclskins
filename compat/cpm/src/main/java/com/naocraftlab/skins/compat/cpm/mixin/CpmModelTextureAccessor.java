package com.naocraftlab.skins.compat.cpm.mixin;

import com.tom.cpm.shared.model.TextureSheetType;
import com.tom.cpm.shared.skin.TextureProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.tom.cpm.shared.parts.ModelPartTexture", remap = false)
public interface CpmModelTextureAccessor {
    @Accessor("texType") TextureSheetType nclskins$modelSheet();
    @Accessor("image") TextureProvider nclskins$modelTexture();
}
