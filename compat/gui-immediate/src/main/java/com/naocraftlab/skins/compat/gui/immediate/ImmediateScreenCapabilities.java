package com.naocraftlab.skins.compat.gui.immediate;

import java.util.Optional;
import com.naocraftlab.skins.client.BackEquipmentPreviewRenderer;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.runtime.ClientRuntime;
import com.naocraftlab.skins.runtime.ViewSpec;
import com.naocraftlab.skins.runtime.ViewChromeMetrics;
import net.minecraft.client.gui.GuiGraphics;


public interface ImmediateScreenCapabilities {
    ClientRuntime runtime();

    void renderProviderArrow(GuiGraphics graphics, String action, int x, int y, boolean highlighted);

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
            GuiGraphics graphics, ViewSpec.Panel panel, int textureU, int textureV, Optional<com.naocraftlab.skins.runtime.Bounds> selectedVerticalTabBounds,
            Optional<com.naocraftlab.skins.runtime.Bounds> verticalTabGroupBounds);

    void renderVerticalTab(GuiGraphics graphics, int x, int y, int width, int height,
            boolean selected, boolean highlighted, boolean active);

    void renderScrollbar(GuiGraphics graphics, ViewSpec.Scrollbar scrollbar);
}
