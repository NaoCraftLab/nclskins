package com.naocraftlab.skins.compat.v1_20_1.client.mixin;

import com.naocraftlab.skins.compat.v1_20_1.client.Minecraft1201Client;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
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
                    ordinal = 2),
            index = 0)
    private LayoutElement nclskins$openGallery(LayoutElement vanillaButton) {
        Screen parent = (Screen) (Object) this;
        return Button.builder(
                        ((Button) vanillaButton).getMessage(),
                        ignored -> Minecraft.getInstance().setScreen(
                                Minecraft1201Client.instance().createScreen(parent)))
                .build();
    }
}
