package com.naocraftlab.skins.mc1211;

import com.naocraftlab.skins.compat.gui.immediate.NclSkinsImmediateScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;


final class NclSkins1211Screen extends NclSkinsImmediateScreen {
    NclSkins1211Screen(Screen parent, Minecraft1211Client client) {
        super(parent, client);
    }

    @Override
    protected void renderEpochBackground(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return forwardScroll(mouseX, mouseY, horizontalAmount, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
}
