package com.naocraftlab.skins.compat.v1_20_1.client.mixin;

import net.minecraft.client.model.PlayerModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerModel.class)
public interface PlayerModelPreviewAccessor {
    @Accessor("slim")
    boolean nclskins$isSlim();
}
