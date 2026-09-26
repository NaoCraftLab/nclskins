package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo;

import com.naocraftlab.skins.compat.gui.immediate.NclSkinsImmediateScreen;
import com.naocraftlab.skins.runtime.Bounds;
import com.naocraftlab.skins.runtime.ViewSpec;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

final class PlayerInfoScreen extends NclSkinsImmediateScreen {
    PlayerInfoScreen(Screen parent, ImmediateClientRuntime client) {
        super(parent, client);
    }

    PlayerInfoScreen(Screen parent, ImmediateClientRuntime client,
            com.naocraftlab.skins.client.ScreenDestination destination) {
        super(parent, client, destination);
    }

    @Override
    protected void renderEpochBackground(
            GuiGraphics graphics, ViewSpec view, int mouseX, int mouseY, float partialTick) {
        NativeSurfaceAdapter.renderBackground(
                graphics, view, width, height, () -> renderDirtBackground(graphics));
    }

    @Override
    protected boolean shouldRenderFramePanel(ViewSpec view, ViewSpec.Panel panel) {
        return NativeSurfaceAdapter.shouldRenderFramePanel(view, panel);
    }

    @Override
    protected Bounds resolveTextBounds(ViewSpec view, ViewSpec.Text text) {
        return NativeSurfaceAdapter.resolveTextBounds(view, text);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return forwardScroll(mouseX, mouseY, 0.0, amount)
                || super.mouseScrolled(mouseX, mouseY, amount);
    }
}
