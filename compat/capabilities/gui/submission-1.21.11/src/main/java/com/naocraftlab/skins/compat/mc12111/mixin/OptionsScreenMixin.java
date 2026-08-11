package com.naocraftlab.skins.compat.mc12111.mixin;

import com.naocraftlab.skins.compat.mc12111.NclSkinsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;


@Mixin(OptionsScreen.class)
abstract class OptionsScreenMixin {
    @ModifyArg(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;",
                    ordinal = 0),
            index = 0)
    private LayoutElement nclskins$openGallery(LayoutElement vanillaButton) {
        Screen parent = (Screen) (Object) this;
        return Button.builder(
                        ((Button) vanillaButton).getMessage(),
                        ignored -> Minecraft.getInstance().setScreen(new NclSkinsScreen(parent)))
                .build();
    }
}
