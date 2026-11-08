package com.naocraftlab.skins.compat.client.identifier.extraction;

import com.naocraftlab.skins.client.ScreenOwnedRenderTarget;
import com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;

public final class PreviewTargetLease {
    private static final ThreadLocal<Boolean> DELEGATING = new ThreadLocal<>();
    private PreviewTargetLease() {}
    public static boolean delegating() { return Boolean.TRUE.equals(DELEGATING.get()); }

    public static void prepare(ScreenOwnedRenderTarget target, Object owner, GuiEntityRenderState state,
            GuiRenderState gui, int scale) {
        Minecraft client = Minecraft.getInstance();
        GuiEntityRenderer renderer = target.acquire(owner,
                () -> new GuiEntityRenderer(client.renderBuffers().bufferSource(), client.getEntityRenderDispatcher()),
                GuiEntityRenderer.class);
        PreviewConsumerFrame.retain(target);
        Boolean previous = DELEGATING.get();
        DELEGATING.set(true);
        try { renderer.prepare(state, gui, scale); }
        finally {
            if (previous == null) DELEGATING.remove(); else DELEGATING.set(previous);
        }
    }
}
