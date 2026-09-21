package com.naocraftlab.skins.compat.keybindings.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.naocraftlab.skins.compat.keybindings.ScreenKeybindings;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardScreenKeybindingMixin {
    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"), require = 1, expect = 1, allow = 1)
    private void nclskins$screenKeybinding(long window, int action, net.minecraft.client.input.KeyEvent event, CallbackInfo ci) {
        ScreenKeybindings.press(window, InputConstants.getKey(event), action);
    }
}
