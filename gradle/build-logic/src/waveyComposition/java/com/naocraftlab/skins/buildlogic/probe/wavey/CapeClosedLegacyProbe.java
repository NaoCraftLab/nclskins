package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.naocraftlab.skins.compat.waveycapes.WaveyCapesSupport;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;

public final class CapeClosedLegacyProbe {
    public static void main(String[] args) throws Exception {
        try (HeadlessCapeClient client = HeadlessCapeClient.open(false)) {
            WaveyCapesSupport support = WaveyCapesSupport.register();
            if (support == null) throw new AssertionError("Registration");
            support.close();
            PlayerWrapper late = new PlayerWrapper((AvatarRenderState) null) {
                @Override public Identifier getCapeTexture() { throw new AssertionError("CLOSED_LEGACY_FOREIGN_LOOKUP"); }
            };
            if (support.getCapeInfo(late) != null || support.shouldBeUsed(late)
                    || !support.getCapePasses(late).isEmpty()) throw new AssertionError("Closed renderer was revived");
        }
        System.out.println("CLOSED_LEGACY_PASS terminal rejection precedes foreign getter; no registration/resource revival");
    }
}
