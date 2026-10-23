package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.naocraftlab.skins.compat.waveycapes.WaveyCapesSupport;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.CapeRenderer;
import dev.tr7zw.waveycapes.support.*;
import java.util.*;

public final class CapeRegistrationFailureProbe {
    public static void main(String[] args) {
        Set<ModSupport> genuine = SupportManager.getSupportedMods();
        Set<ModSupport> before = new HashSet<>(genuine);
        ModSupport foreign = new ModSupport() {
            public boolean shouldBeUsed(PlayerWrapper player) { return false; }
            public CapeRenderer getRenderer() { return null; }
            public int priority() { return 1; }
            public boolean blockFeatureRenderer(Object player) { return false; }
        };
        if (!genuine.add(foreign)) throw new AssertionError("Foreign fixture registration");
        try {
            for (boolean after : new boolean[]{false, true}) {
                SupportManager.mods = new AbstractSet<>() {
                    public Iterator<ModSupport> iterator() { return genuine.iterator(); }
                    public int size() { return genuine.size(); }
                    public boolean add(ModSupport own) {
                        if (after) genuine.add(own);
                        throw new IllegalStateException("SYNTHETIC_REGISTRATION_FAILURE");
                    }
                };
                try { WaveyCapesSupport.register(); throw new AssertionError("Registration failure swallowed"); }
                catch (IllegalStateException expected) {
                    if (!"SYNTHETIC_REGISTRATION_FAILURE".equals(expected.getMessage())) throw expected;
                }
                if (!genuine.contains(foreign) || genuine.stream().anyMatch(value -> value instanceof WaveyCapesSupport))
                    throw new AssertionError("Partial registration cleanup changed foreign or retained own support");
            }
        } finally {
            SupportManager.mods = genuine;
            genuine.remove(foreign);
        }
        if (!genuine.equals(before)) throw new AssertionError("Genuine registry not restored");
        System.out.println("REGISTRATION_FAILURE_PASS before/after native-set insertion; only own instance removed; synthetic API failure facade over genuine registry");
    }
}
