package com.naocraftlab.skins.compat.keybindings;

import com.mojang.blaze3d.platform.InputConstants;
import com.naocraftlab.skins.client.ScreenKeybinding;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

public final class ScreenKeyMapping extends KeyMapping {
    private static final Category CATEGORY = new Category(net.minecraft.resources.Identifier.fromNamespaceAndPath("nclskins", "main"));
    private final ScreenKeybinding binding;

    ScreenKeyMapping(ScreenKeybinding binding) {
        super(binding.translationKey(), InputConstants.UNKNOWN.getValue(), CATEGORY);
        this.binding = binding;
    }

    public static Category category() { return CATEGORY; }

    ScreenKeybinding binding() { return binding; }

    static boolean isCurrentWindow(Minecraft client, long window) {
        return client.getWindow().handle() == window;
    }

    @Override
    public int compareTo(KeyMapping other) {
        return other instanceof ScreenKeyMapping mapping
                ? Integer.compare(binding.ordinal(), mapping.binding.ordinal()) : super.compareTo(other);
    }
}
