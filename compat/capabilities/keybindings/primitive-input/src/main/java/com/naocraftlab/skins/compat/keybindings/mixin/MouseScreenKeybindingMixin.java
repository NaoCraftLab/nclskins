package com.naocraftlab.skins.compat.keybindings.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.naocraftlab.skins.compat.keybindings.ScreenKeybindings;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseScreenKeybindingMixin {
    @Inject(method = "onPress(JIII)V", at = @At("HEAD"), require = 1, expect = 1, allow = 1)
    private void nclskins$screenKeybinding(long window, int button, int action, int modifiers, CallbackInfo ci) {
        ScreenKeybindings.press(window, InputConstants.Type.MOUSE.getOrCreate(button), action);
    }
}
