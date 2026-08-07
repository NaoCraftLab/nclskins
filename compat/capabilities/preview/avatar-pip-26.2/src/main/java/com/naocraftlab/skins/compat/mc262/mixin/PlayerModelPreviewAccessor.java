package com.naocraftlab.skins.compat.mc262.mixin;

import net.minecraft.client.model.player.PlayerModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerModel.class)
public interface PlayerModelPreviewAccessor {
    @Accessor("slim")
    boolean nclskins$isSlim();
}
