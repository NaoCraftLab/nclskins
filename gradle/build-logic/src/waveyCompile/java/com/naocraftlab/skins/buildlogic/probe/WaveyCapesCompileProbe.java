package com.naocraftlab.skins.buildlogic.probe;

import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.CapeInfos;
import dev.tr7zw.waveycapes.render.CapePass;
import dev.tr7zw.waveycapes.render.CapeRenderer;
import dev.tr7zw.waveycapes.support.ModSupport;
import java.util.List;
import java.util.function.UnaryOperator;

public final class WaveyCapesCompileProbe implements ModSupport, CapeRenderer {
    @Override public boolean shouldBeUsed(PlayerWrapper player) {
        Object nativeTexture = player.getCapeTexture();
        return nativeTexture != null && player.isCapeVisible() && !player.isPlayerInvisible()
                && !player.hasElytraEquipped();
    }

    @Override public CapeRenderer getRenderer() { return this; }
    @Override public boolean blockFeatureRenderer(Object player) { return false; }
    @Override public int priority() { return 1; }
    @Override public boolean vanillaUvValues() { return true; }
    @Override public CapeInfos getCapeInfo(PlayerWrapper player) { return new CapeInfos(this, null, false); }
    @Override public List<CapePass> getCapePasses(PlayerWrapper player) {
        CapePass pass = new CapePass(null, UnaryOperator.identity());
        Object nativeMaterial = pass.renderType();
        if (nativeMaterial != null) throw new AssertionError();
        return List.of(new CapePass(null, pass.wrapper()));
    }
}
