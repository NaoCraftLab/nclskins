package com.naocraftlab.skins.compat.client.resourcelocation.optifine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.optifine.player.CapeUtils", remap = false)
abstract class OptifineCapeUtilsMixin {
    @WrapMethod(method = "downloadCape(Lnet/minecraft/client/player/AbstractClientPlayer;)V", remap = false, require = 1, expect = 1, allow = 1)
    private static void nclskins$suppressDownloadCape(AbstractClientPlayer player, Operation<Void> original) { }

    @WrapMethod(method = "reloadCape(Lnet/minecraft/client/player/AbstractClientPlayer;)V", remap = false, require = 1, expect = 1, allow = 1)
    private static void nclskins$suppressReloadCape(AbstractClientPlayer player, Operation<Void> original) { }
}
