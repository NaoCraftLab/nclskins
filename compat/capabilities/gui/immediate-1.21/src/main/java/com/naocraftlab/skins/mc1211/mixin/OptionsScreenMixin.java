package com.naocraftlab.skins.mc1211.mixin;

import com.naocraftlab.skins.mc1211.Minecraft1211Client;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;


@Mixin(OptionsScreen.class)
abstract class OptionsScreenMixin {
    @ModifyArg(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/options/OptionsScreen;openScreenButton(Lnet/minecraft/network/chat/Component;Ljava/util/function/Supplier;)Lnet/minecraft/client/gui/components/Button;",
                    ordinal = 0),
            index = 1)
    private Supplier<Screen> nclskins$openGallery(Supplier<Screen> vanillaScreen) {
        Screen parent = (Screen) (Object) this;
        return () -> Minecraft1211Client.instance().createScreen(parent);
    }
}
