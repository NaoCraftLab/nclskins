package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

import com.mojang.blaze3d.systems.RenderSystem;
import com.naocraftlab.skins.compat.gui.immediate.NclSkinsImmediateScreen;
import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.ViewSpec;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;


final class PlayerInfoScreen extends NclSkinsImmediateScreen {
    PlayerInfoScreen(Screen parent, ImmediateClientRuntime client) {
        super(parent, client);
    }

    @Override
    protected void renderEpochBackground(
            GuiGraphics graphics,
            ViewSpec view,
            int mouseX,
            int mouseY,
            float partialTick) {
        if (isExternalImportView(view)) {
            renderChatOptionsBackground(graphics);
            return;
        }
        if ("add_source".equals(view.screenId())) {
            renderCreateWorldBackground(graphics);
            return;
        }
        renderDirtBackground(graphics);
    }

    @Override
    protected boolean shouldRenderFramePanel(ViewSpec view, ViewSpec.Panel panel) {
        return !isExternalImportView(view)
                || (panel.style() != ViewSpec.Panel.Style.VANILLA_HEADER
                        && panel.style() != ViewSpec.Panel.Style.VANILLA_FOOTER);
    }

    @Override
    protected Bounds resolveTextBounds(ViewSpec view, ViewSpec.Text text) {
        Bounds bounds = text.bounds();
        if (isExternalImportView(view)
                && ("external.title".equals(text.id())
                        || "external.review.title".equals(text.id()))) {
            return new Bounds(bounds.x(), 20, bounds.width(), bounds.height());
        }
        return bounds;
    }

    private void renderChatOptionsBackground(GuiGraphics graphics) {
        renderDirtBackground(graphics);
        int top = 32;
        int bottom = Math.max(top, height - 32);
        graphics.setColor(0.125F, 0.125F, 0.125F, 1.0F);
        graphics.blit(
                Screen.BACKGROUND_LOCATION,
                0,
                top,
                (float) width,
                (float) bottom,
                width,
                bottom - top,
                32,
                32);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.fillGradient(0, top, width, top + 4, 0xFF000000, 0x00000000);
        graphics.fillGradient(0, bottom - 4, width, bottom, 0x00000000, 0xFF000000);
    }

    private static boolean isExternalImportView(ViewSpec view) {
        return "external_chooser".equals(view.screenId())
                || "external_review".equals(view.screenId());
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
