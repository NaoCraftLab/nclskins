package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup;

import com.naocraftlab.skins.compat.gui.immediate.NclSkinsImmediateScreen;
import com.naocraftlab.skins.runtime.ViewSpec;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

final class SkinLookupScreen extends NclSkinsImmediateScreen {
    private boolean createWorldChrome;

    SkinLookupScreen(Screen parent, ImmediateClientRuntime client) {
        super(parent, client);
    }

    SkinLookupScreen(Screen parent, ImmediateClientRuntime client,
            com.naocraftlab.skins.client.ScreenDestination destination) {
        super(parent, client, destination);
    }

    @Override
    protected void renderEpochBackground(
            GuiGraphics graphics,
            ViewSpec view,
            int mouseX,
            int mouseY,
            float partialTick) {
        createWorldChrome = view.surfaceRole() == ViewSpec.SurfaceRole.TABBED_MENU;
        try {
            renderBackground(graphics, mouseX, mouseY, partialTick);
        } finally {
            createWorldChrome = false;
        }
        if (view.surfaceRole() == ViewSpec.SurfaceRole.TABBED_MENU) {
            NativeSurfaceAdapter.renderCreateWorldFooterSeparator(graphics, width, height);
        }
    }

    @Override
    protected void renderMenuBackground(GuiGraphics graphics) {
        if (!createWorldChrome) {
            super.renderMenuBackground(graphics);
            return;
        }
        NativeSurfaceAdapter.renderTabHeader(graphics, width);
        renderMenuBackground(graphics, 0, 24, width, height);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return forwardScroll(mouseX, mouseY, horizontalAmount, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
}
