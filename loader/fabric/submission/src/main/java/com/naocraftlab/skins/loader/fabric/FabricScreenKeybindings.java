package com.naocraftlab.skins.loader.fabric;

import com.naocraftlab.skins.compat.keybindings.ScreenKeybindings;

final class FabricScreenKeybindings {
    private FabricScreenKeybindings() {}

    static void register() {
        net.minecraft.client.KeyMapping.Category.register(
                com.naocraftlab.skins.compat.keybindings.ScreenKeyMapping.category().id());
        ScreenKeybindings.register(net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper::registerKeyBinding);
    }
}
