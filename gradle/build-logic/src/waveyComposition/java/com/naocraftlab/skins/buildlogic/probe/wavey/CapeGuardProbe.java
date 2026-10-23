package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.compat.client.identifier.CapeMaterialSnapshot;
import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import com.naocraftlab.skins.compat.waveycapes.*;
import com.naocraftlab.skins.runtime.*;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.*;
import dev.tr7zw.waveycapes.support.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;

public final class CapeGuardProbe {
    @FunctionalInterface public interface Drain {
        boolean consume(SubmitNodeStorage storage, CapeRenderer renderer) throws Exception;
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }

    public static void run(Path root, boolean noSupport, Drain drain) throws Exception {
        run(root, noSupport, drain, 2);
    }
    public static void run(Path root, boolean noSupport, Drain drain, int expectedPasses) throws Exception {
        if (expectedPasses != 1 && expectedPasses != 2) throw new IllegalArgumentException("UNIMPLEMENTED_GUARD_PLAN");
        Set<ModSupport> registry = SupportManager.getSupportedMods();
        Set<ModSupport> original = new HashSet<>(registry);
        ModSupport foreign = new ModSupport() {
            public boolean shouldBeUsed(PlayerWrapper player) { return false; }
            public CapeRenderer getRenderer() { throw new AssertionError("REJECTED_FOREIGN_RENDERER"); }
            public int priority() { return 1; }
            public boolean blockFeatureRenderer(Object player) { return false; }
        };
        check(registry.add(foreign), "PRIORITY_COLLISION_FIXTURE");
        Set<ModSupport> before = new HashSet<>(registry);
        Throwable primary = null;
        try (HeadlessCapeClient client = HeadlessCapeClient.open(true);
                WaveyProducerFixture producer = new WaveyProducerFixture(root)) {
            String installed = CapeProjection.material(producer.self, "Self", true).capeLocation();
            var token = CapeProjection.captureCapeTexture(producer.self, "Self", true, installed);
            State state = allocate(State.class);
            state.material = CapeMaterialSnapshot.world(producer.self, "Self", true, installed);
            Actor actor = new Actor(state, Identifier.parse(installed));
            if (!noSupport) { WaveyCapesIntegration.install("1.11.3"); WaveyCapesIntegration.install("1.11.3"); }
            var own = registry.stream().filter(value -> value instanceof WaveyCapesSupport).toList();
            check(own.size() == (noSupport ? 0 : 1), "REPEATED_INSTALL_OWN_COUNT");
            WaveyCapesSupport support = noSupport ? new WaveyCapesSupport() : (WaveyCapesSupport) own.getFirst();
            try {
                check(support.priority() == 1 && !support.blockFeatureRenderer(actor), "PRIORITY_FEATURE_POLICY");
                check(support.shouldBeUsed(actor), "GUARD_PRODUCER_ADMISSION");
                List<CapePass> passes = support.getCapePasses(actor);
                check(passes.size() == expectedPasses && !support.getCapeInfo(actor).isGlint(), "WORLD_PLAN_NO_SECOND_LEGACY_FOIL");
                check(CapturedCapePass.of(passes.getFirst().renderType()).validity() == token, "PUBLIC_PLAN_CAPTURE");
                assertWrappers(passes);
                try { passes.add(passes.getFirst()); throw new AssertionError("MUTABLE_PUBLIC_PLAN"); }
                catch (UnsupportedOperationException expected) { }
                check(support.vanillaUvValues() && support.uvSheetWidth() == 64 && support.uvSheetHeight() == 32, "LOGICAL_UV_DELEGATION");
                actor.invisible = true; check(!support.shouldBeUsed(actor), "INVISIBLE_ACTOR_ADMITTED");
                actor.invisible = false; actor.visible = false; check(!support.shouldBeUsed(actor), "HIDDEN_CAPE_ADMITTED");
                actor.visible = true;
                SubmitNodeStorage storage = new SubmitNodeStorage();
                new CapeNodeCollector().submitCape(storage, actor, new PoseStack(), 0, 0);
                check(drain.consume(storage, support), "NATIVE_BRIDGE_FOIL_MISSING");
            } finally {
                if (!noSupport) WaveyCapesIntegration.close();
                support.close(); support.close();
            }
            check(!support.shouldBeUsed(actor), "CLOSED_SUPPORT_ACTIVATED");
            if (!noSupport) WaveyCapesIntegration.install("1.11.3");
            check(registry.equals(before) && registry.contains(foreign), "FOREIGN_CHANGED_OR_LATE_INSTALL_RECREATED");
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                registry.remove(foreign);
                check(registry.equals(original), "GUARD_REGISTRY_NOT_RESTORED");
            } catch (Throwable cleanup) {
                if (primary == null) throw cleanup;
                primary.addSuppressed(cleanup);
            }
        }
    }

    static void assertWrappers(List<CapePass> passes) {
        var hostConsumer = (com.mojang.blaze3d.vertex.VertexConsumer) java.lang.reflect.Proxy.newProxyInstance(
                CapeGuardProbe.class.getClassLoader(), new Class<?>[]{com.mojang.blaze3d.vertex.VertexConsumer.class},
                (proxy, method, args) -> { throw new AssertionError("WRAPPER_TOUCHED_HOST_CONSUMER"); });
        if (passes.size() == 1) {
            check(passes.getFirst().wrapper().apply(hostConsumer) == hostConsumer, "PUBLIC_WRAPPER_IDENTITY");
        } else {
            List<List<String>> streams = new ArrayList<>();
            for (CapePass pass : passes) {
                List<String> stream = new ArrayList<>(); streams.add(stream);
                var supplied = (com.mojang.blaze3d.vertex.VertexConsumer) java.lang.reflect.Proxy.newProxyInstance(
                        CapeGuardProbe.class.getClassLoader(), new Class<?>[]{com.mojang.blaze3d.vertex.VertexConsumer.class},
                        (proxy, method, args) -> {
                            stream.add(method.getName() + Arrays.toString(args));
                            return method.getReturnType() == void.class ? null : proxy;
                        });
                var wrapped = pass.wrapper().apply(supplied);
                if (streams.size() == 1) {
                    check(stream.isEmpty(), "PUBLIC_WRAPPER_EAGER_HOST_USE");
                    wrapped.addVertex(1, 2, 3).setUv(.25f, .75f).setColor(0x81234567);
                } else wrapped.addVertex(99, 99, 99);
            }
            check(streams.getFirst().equals(List.of("addVertex[1.0, 2.0, 3.0]", "setUv[0.25, 0.75]", "setColor[-2128394905]"))
                    && streams.getFirst().equals(streams.get(1)), "PUBLIC_WRAPPER_STREAM_OR_HOST_HANDOFF");
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }
    public static final class State extends AvatarRenderState implements CapeMaterialSnapshot.Carrier {
        private CapeMaterialSnapshot material;
        public CapeMaterialSnapshot nclskins$capeMaterial() { return material; }
        public void nclskins$setCapeMaterial(CapeMaterialSnapshot value) { material = value; }
    }
    private static final class Actor extends PlayerWrapper {
        private final Identifier installed;
        private boolean visible = true, invisible;
        Actor(State state, Identifier installed) { super(state); this.installed = installed; }
        @Override public Identifier getCapeTexture() { return installed; }
        @Override public boolean isPlayerInvisible() { return invisible; }
        @Override public boolean isCapeVisible() { return visible; }
        @Override public boolean hasElytraEquipped() { return false; }
    }
}
