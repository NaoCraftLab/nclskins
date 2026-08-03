package com.naocraftlab.skins.compat.server.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;


@Mixin(value = GameProfile.class, remap = false)
public interface GameProfilePropertiesAccessor {
    @Accessor(value = "properties", remap = false)
    @Mutable
    void nclskins$setProperties(PropertyMap properties);
}
