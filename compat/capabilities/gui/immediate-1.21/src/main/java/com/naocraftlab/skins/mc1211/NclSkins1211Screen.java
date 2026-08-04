package com.naocraftlab.skins.mc1211;

import com.mojang.blaze3d.systems.RenderSystem;
import com.naocraftlab.skins.compat.gui.immediate.NclSkinsImmediateScreen;
import com.naocraftlab.skins.runtime.ViewSpec;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.resources.ResourceLocation;


final class NclSkins1211Screen extends NclSkinsImmediateScreen {
    private boolean createWorldChrome;

    NclSkins1211Screen(Screen parent, Minecraft1211Client client) {
        super(parent, client);
    }

    @Override
    protected void renderEpochBackground(
            GuiGraphics graphics,
            ViewSpec view,
            int mouseX,
            int mouseY,
            float partialTick) {
        createWorldChrome = "add_source".equals(view.screenId());
        try {
            renderBackground(graphics, mouseX, mouseY, partialTick);
        } finally {
            createWorldChrome = false;
        }
        if ("add_source".equals(view.screenId())) {
            renderCreateWorldFooterSeparator(graphics);
        }
    }

    @Override
    protected void renderMenuBackground(GuiGraphics graphics) {
        if (!createWorldChrome) {
            super.renderMenuBackground(graphics);
            return;
        }
        graphics.blit(
                CreateWorldScreen.TAB_HEADER_BACKGROUND,
                0,
                0,
                0.0F,
                0.0F,
                width,
                24,
                16,
                16);
        renderMenuBackground(graphics, 0, 24, width, height);
    }

    private void renderCreateWorldFooterSeparator(GuiGraphics graphics) {
        ResourceLocation footer = minecraft.level == null
                ? Screen.FOOTER_SEPARATOR
                : Screen.INWORLD_FOOTER_SEPARATOR;
        RenderSystem.enableBlend();
        graphics.blit(footer, 0, height - 38, 0.0F, 0.0F, width, 2, 32, 2);
        RenderSystem.disableBlend();
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return forwardScroll(mouseX, mouseY, horizontalAmount, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
}
