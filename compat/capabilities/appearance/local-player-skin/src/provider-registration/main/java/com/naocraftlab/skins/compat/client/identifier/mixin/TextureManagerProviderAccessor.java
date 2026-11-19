package com.naocraftlab.skins.compat.client.identifier.mixin;

import java.util.Map;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TextureManager.class)
public interface TextureManagerProviderAccessor {
    @Accessor("byPath")
    Map<?, ?> ncl$getEntries();
}
