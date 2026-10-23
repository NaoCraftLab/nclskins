package com.naocraftlab.skins.compat.client.cape.mixin;

import com.naocraftlab.skins.compat.client.cape.CapturedCapePass;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderType.class)
public interface RenderTypeFlagsMixin extends CapturedCapePass.RenderTypeFlags {
    @Accessor("sortOnUpload") boolean nclskins$sortOnUpload();
}
