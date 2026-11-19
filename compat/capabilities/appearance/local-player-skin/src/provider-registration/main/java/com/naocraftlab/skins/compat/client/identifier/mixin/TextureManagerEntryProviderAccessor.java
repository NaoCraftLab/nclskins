package com.naocraftlab.skins.compat.client.identifier.mixin;

import net.minecraft.client.renderer.texture.TextureProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.texture.TextureManager$Entry")
public interface TextureManagerEntryProviderAccessor {
    @Accessor("provider")
    TextureProvider<?, ?> ncl$getProvider();
}
