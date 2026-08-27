package com.naocraftlab.skins.compat.gui.immediate;

import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.runtime.ClientRuntime;
import com.naocraftlab.skins.runtime.ViewSpec;
import com.naocraftlab.skins.runtime.ViewChromeMetrics;
import net.minecraft.client.gui.GuiGraphics;


public interface ImmediateScreenCapabilities {
    ClientRuntime runtime();

    default ViewChromeMetrics viewChromeMetrics() {
        return ViewChromeMetrics.STANDARD;
    }

    TextureRegistry createTextureRegistry();


    PreviewRenderer<GuiGraphics> createSimplePreviewRenderer();


    PreviewRenderer<GuiGraphics> createEditorPreviewRenderer();

    BackEquipmentPreviewRenderer<GuiGraphics> createBackEquipmentPreviewRenderer();

    void finishPreviewPass(GuiGraphics graphics);

    NativeScrollController createScrollController();

    void renderPanel(
            GuiGraphics graphics, ViewSpec.Panel panel, int textureU, int textureV);

    void renderScrollbar(GuiGraphics graphics, ViewSpec.Scrollbar scrollbar);
}
