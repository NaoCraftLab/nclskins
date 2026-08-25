package com.naocraftlab.skins.compat.client.identifier.extraction.mixin;

import com.naocraftlab.skins.client.SettingsOptionOrder;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;


@Mixin(AccessibilityOptionsScreen.class)
abstract class AccessibilityOptionsScreenMixin {
    @ModifyReturnValue(method = "options", at = @At("RETURN"))
    private static OptionInstance<?>[] nclskins$addMainHand(
            OptionInstance<?>[] original, Options options) {
        List<OptionInstance<?>> ordered = SettingsOptionOrder.insertAfterFirstPresent(
                Arrays.asList(original),
                options.mainHand(),
                List.of(options.bobView(), options.autoJump()));
        return ordered.toArray(new OptionInstance<?>[0]);
    }
}
