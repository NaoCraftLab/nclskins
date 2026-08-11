package com.naocraftlab.skins.compat.mc12111.mixin;

import com.naocraftlab.skins.client.SettingsOptionOrder;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(AccessibilityOptionsScreen.class)
abstract class AccessibilityOptionsScreenMixin {
    @Inject(method = "options", at = @At("RETURN"), cancellable = true)
    private static void nclskins$addMainHand(
            Options options, CallbackInfoReturnable<OptionInstance<?>[]> callback) {
        List<OptionInstance<?>> ordered = SettingsOptionOrder.insertAfterFirstPresent(
                Arrays.asList(callback.getReturnValue()),
                options.mainHand(),
                List.of(options.bobView(), options.autoJump()));
        callback.setReturnValue(ordered.toArray(new OptionInstance<?>[0]));
    }
}
