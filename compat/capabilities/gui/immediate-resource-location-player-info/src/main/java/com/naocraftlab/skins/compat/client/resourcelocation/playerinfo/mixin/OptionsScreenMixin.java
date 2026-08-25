package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.mixin;

import com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.ImmediateClientRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;


@Mixin(OptionsScreen.class)
abstract class OptionsScreenMixin {
    @WrapOperation(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/OptionsScreen;openScreenButton(Lnet/minecraft/network/chat/Component;Ljava/util/function/Supplier;)Lnet/minecraft/client/gui/components/Button;"),
            require = 1)
    private Button nclskins$openGallery(
            OptionsScreen instance,
            Component label,
            Supplier<Screen> vanillaScreen,
            Operation<Button> original) {
        Screen parent = (Screen) (Object) this;
        Supplier<Screen> target = isSkinCustomization(label)
                ? () -> ImmediateClientRuntime.instance().createScreen(parent)
                : vanillaScreen;
        return original.call(instance, label, target);
    }

    private static boolean isSkinCustomization(Component label) {
        return label.getContents() instanceof TranslatableContents contents
                && "options.skinCustomisation".equals(contents.getKey());
    }
}
