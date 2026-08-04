package com.naocraftlab.skins.compat.gui.immediate;

import com.naocraftlab.skins.runtime.ViewSpec;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;

public interface NativeScrollController {
    void synchronize(Optional<ViewSpec.ScrollSurface> surface);

    void renderFrame(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

    boolean mouseScrolled(
            double mouseX,
            double mouseY,
            double horizontalAmount,
            double verticalAmount);

    Optional<String> surfaceId();

    double offsetPixels();

    void acceptedRuntimeOffset(double offsetPixels);
}
