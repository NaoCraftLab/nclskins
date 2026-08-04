package com.naocraftlab.skins.compat.v1_20_1.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.naocraftlab.skins.compat.gui.immediate.NclSkinsImmediateScreen;
import com.naocraftlab.skins.runtime.ViewSpec;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;


final class NclSkins1201ImmediateScreen extends NclSkinsImmediateScreen {
    NclSkins1201ImmediateScreen(Screen parent, Minecraft1201Client client) {
        super(parent, client);
    }

    @Override
    protected void renderEpochBackground(
            GuiGraphics graphics,
            ViewSpec view,
            int mouseX,
            int mouseY,
            float partialTick) {
        if ("add_source".equals(view.screenId())) {
            renderCreateWorldBackground(graphics);
            return;
        }
        renderDirtBackground(graphics);
    }

    private void renderCreateWorldBackground(GuiGraphics graphics) {
        graphics.blit(
                CreateWorldScreen.LIGHT_DIRT_BACKGROUND,
                0,
                0,
                0.0F,
                0.0F,
                width,
                height,
                32,
                32);
        RenderSystem.enableBlend();
        graphics.blit(
                CreateWorldScreen.FOOTER_SEPERATOR,
                0,
                height - 38,
                0.0F,
                0.0F,
                width,
                2,
                32,
                2);
        RenderSystem.disableBlend();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return forwardScroll(mouseX, mouseY, 0.0, amount)
                || super.mouseScrolled(mouseX, mouseY, amount);
    }
}
