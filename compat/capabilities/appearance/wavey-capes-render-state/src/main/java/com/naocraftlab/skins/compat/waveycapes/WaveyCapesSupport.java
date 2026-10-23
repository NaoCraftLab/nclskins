package com.naocraftlab.skins.compat.waveycapes;

import com.naocraftlab.skins.compat.client.identifier.CapeMaterialSnapshot;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.CapeInfos;
import dev.tr7zw.waveycapes.render.CapePass;
import dev.tr7zw.waveycapes.render.CapeRenderer;
import dev.tr7zw.waveycapes.support.ModSupport;
import dev.tr7zw.waveycapes.support.SupportManager;
import java.util.List;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

public final class WaveyCapesSupport implements ModSupport, CapeRenderer, AutoCloseable {
    private boolean active = true;
    private boolean closed;

    public static WaveyCapesSupport register() {
        WaveyCapesSupport support = new WaveyCapesSupport();
        try {
            CapePass pass = new CapePass(null, java.util.function.UnaryOperator.identity());
            ((CapeRenderer) support).getCapePasses(null);
            pass.renderType();
            pass.wrapper();
            new CapeInfos(support, null, false).isGlint();
            if (!SupportManager.getSupportedMods().add(support)) {
                support.close();
                return null;
            }
            return support;
        } catch (RuntimeException | LinkageError incompatible) {
            support.close();
            throw incompatible;
        }
    }

    private CapeMaterialSnapshot material(PlayerWrapper player) {
        if (!active || !CapeRenderThread.isClientThread() || player == null || player.isPlayerInvisible() || !player.isCapeVisible()
                || player.hasElytraEquipped() || player.getRenderState() == null) return null;
        Identifier installed = player.getCapeTexture();
        if (installed == null) return null;
        CapeMaterialSnapshot snapshot = ((CapeMaterialSnapshot.Carrier) player.getRenderState()).nclskins$capeMaterial();
        return snapshot != null && snapshot.capeGlint() && snapshot.validity().isValid()
                && installed.toString().equals(snapshot.textureLocation()) ? snapshot : null;
    }

    @Override public boolean shouldBeUsed(PlayerWrapper player) {
        try {
            return material(player) != null;
        } catch (LinkageError incompatible) {
            active = false;
            return false;
        }
    }

    @Override public List<CapePass> getCapePasses(PlayerWrapper player) {
        try {
            CapeMaterialSnapshot snapshot = material(player);
            if (snapshot == null) return List.of();
            return WaveyCapePasses.plan(Identifier.parse(snapshot.textureLocation()), snapshot.validity());
        } catch (LinkageError incompatible) {
            active = false;
            return List.of();
        }
    }

    @Override public CapeInfos getCapeInfo(PlayerWrapper player) {
        if (closed || !CapeRenderThread.isClientThread()) return null;
        try {
            Identifier texture = player == null ? null : player.getCapeTexture();
            return texture == null ? null : new CapeInfos(this, RenderTypes.entityTranslucent(texture), false);
        } catch (LinkageError incompatible) {
            active = false;
            return null;
        }
    }

    @Override public CapeRenderer getRenderer() { return this; }
    @Override public int priority() { return 1; }
    @Override public boolean blockFeatureRenderer(Object player) { return false; }
    @Override public boolean vanillaUvValues() { return true; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        active = false;
        var supports = SupportManager.getSupportedMods().iterator();
        while (supports.hasNext()) {
            if (supports.next() == this) { supports.remove(); break; }
        }
    }
}
