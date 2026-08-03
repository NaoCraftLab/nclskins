package com.naocraftlab.skins.compat.v1_20_1.client;

import com.naocraftlab.skins.compat.gui.immediate.NclSkinsImmediateScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;


final class NclSkins1201ImmediateScreen extends NclSkinsImmediateScreen {
    NclSkins1201ImmediateScreen(Screen parent, Minecraft1201Client client) {
        super(parent, client);
    }

    @Override
    protected void renderEpochBackground(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return forwardScroll(mouseX, mouseY, 0.0, amount)
                || super.mouseScrolled(mouseX, mouseY, amount);
    }
}
