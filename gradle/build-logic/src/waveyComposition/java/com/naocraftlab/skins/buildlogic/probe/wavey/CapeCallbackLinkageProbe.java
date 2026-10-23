package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.compat.client.identifier.CapeMaterialSnapshot;
import com.naocraftlab.skins.compat.waveycapes.WaveyCapesSupport;
import com.naocraftlab.skins.runtime.*;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.*;
import dev.tr7zw.waveycapes.support.*;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

public final class CapeCallbackLinkageProbe {
    public static void main(String[] args) throws Exception {
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        runCase(args);
    }
    public static void runCase(String[] args) throws Exception {
        try (HeadlessCapeClient client = HeadlessCapeClient.open(false);
                WaveyProducerFixture producer = new WaveyProducerFixture(Path.of(args[0]))) {
            String installed = CapeProjection.material(producer.self, "Self", true).capeLocation();
            Class<?> unsafe = Class.forName("sun.misc.Unsafe");
            Field singleton = unsafe.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
            CapeGuardProbe.State state = (CapeGuardProbe.State) unsafe.getMethod("allocateInstance", Class.class)
                    .invoke(singleton.get(null), CapeGuardProbe.State.class);
            state.nclskins$setCapeMaterial(CapeMaterialSnapshot.world(producer.self, "Self", true, installed));
            Actor actor = new Actor(state, Identifier.parse(installed));
            CapeRenderer fallbackRenderer = new CapeRenderer() {
                public CapeInfos getCapeInfo(PlayerWrapper player) {
                    return new CapeInfos(this, RenderTypes.entityTranslucent(Identifier.parse(installed)), false);
                }
            };
            ModSupport foreign = new ModSupport() {
                public boolean shouldBeUsed(PlayerWrapper player) { return true; }
                public CapeRenderer getRenderer() { return fallbackRenderer; }
                public int priority() { return 0; }
                public boolean blockFeatureRenderer(Object player) { return false; }
            };
            Set<ModSupport> registry = SupportManager.getSupportedMods();
            Set<ModSupport> before = new HashSet<>(registry);
            registry.add(foreign);
            try (WaveyCapesSupport support = WaveyCapesSupport.register()) {
                if (support == null || !support.shouldBeUsed(actor)) throw new AssertionError("Valid producer admission missing");
                actor.broken = true;
                Set<ModSupport> during = new HashSet<>(registry);
                SubmitNodeStorage storage = new SubmitNodeStorage();
                new CapeNodeCollector().submitCape(storage, actor, new PoseStack(), 0, 0);
                if (!registry.equals(during) || !registry.contains(foreign) || storage.getSubmitsPerOrder().size() != 1
                        || support.shouldBeUsed(actor)) throw new AssertionError("Local ABI failure changed foreign dispatch/registration");
            } finally { registry.remove(foreign); }
            if (!registry.equals(before)) throw new AssertionError("Inactive own registration survived terminal close");
            if (producer.profileReads.get() != 1 || producer.imageReads.get() != 1) throw new AssertionError("Callback did remote work");
        }
        System.out.println("CALLBACK_LINKAGE_PASS genuine registry/collector preserves foreign continuation; local disable does not mutate an active registry iteration; terminal removes inactive own instance");
    }
    private static final class Actor extends PlayerWrapper {
        private final Identifier installed;
        private boolean broken;
        Actor(CapeGuardProbe.State state, Identifier installed) { super(state); this.installed = installed; }
        @Override public Identifier getCapeTexture() {
            if (broken) throw new NoSuchMethodError("SYNTHETIC_GETTER_ABI_FAILURE");
            return installed;
        }
        @Override public boolean isPlayerInvisible() { return false; }
        @Override public boolean isCapeVisible() { return true; }
        @Override public boolean hasElytraEquipped() { return false; }
    }
}
