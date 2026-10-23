package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.naocraftlab.skins.compat.waveycapes.WaveyCapesSupport;
import com.naocraftlab.skins.compat.waveycapes.WaveyCapesIntegration;
import com.naocraftlab.skins.runtime.*;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.support.*;
import dev.tr7zw.waveycapes.render.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import static com.naocraftlab.skins.buildlogic.probe.wavey.ImmediateCapeHost.*;

public final class ImmediateGuardProbe {
    public static void bridge(Path root) throws Exception {
        Set<ModSupport> registry = SupportManager.getSupportedMods(), original = new HashSet<>(registry);
        ModSupport foreign = new ModSupport() {
            public boolean shouldBeUsed(PlayerWrapper player) { return false; }
            public CapeRenderer getRenderer() { throw new AssertionError("REJECTED_FOREIGN_RENDERER"); }
            public int priority() { return 1; }
            public boolean blockFeatureRenderer(Object player) { return false; }
        };
        check(registry.add(foreign), "PRIORITY_COLLISION_FIXTURE");
        Set<ModSupport> before = new HashSet<>(registry);
        try (AutoCloseable sentinel = () -> {
                    registry.remove(foreign);
                    check(registry.equals(original), "GUARD_REGISTRY_NOT_RESTORED");
                };
                AutoCloseable registration = () -> {
                    WaveyCapesIntegration.close();
                    WaveyCapesIntegration.close();
                    WaveyCapesIntegration.install("1.11.3");
                    check(registry.equals(before) && registry.contains(foreign), "FOREIGN_CHANGED_OR_LATE_INSTALL_RECREATED");
                }) {
            WaveyCapesIntegration.install("1.11.3");
            WaveyCapesIntegration.install("1.11.3");
            List<ModSupport> own = registry.stream().filter(value -> value instanceof WaveyCapesSupport).toList();
            check(own.size() == 1, "REPEATED_INSTALL_OWN_COUNT");
            ImmediateCapeProbe.runCase(new String[]{root.toString(), "world", "live"}, (WaveyCapesSupport) own.get(0));
        }
    }
    static void policy(WaveyCapesSupport support, PlayerWrapper actor, List<CapePass> passes) {
        check(support.priority() == 1 && !support.blockFeatureRenderer(actor), "PRIORITY_FEATURE_POLICY");
        check(support.vanillaUvValues() && support.uvSheetWidth() == 64 && support.uvSheetHeight() == 32, "LOGICAL_UV_DELEGATION");
        var hostConsumer = (com.mojang.blaze3d.vertex.VertexConsumer) java.lang.reflect.Proxy.newProxyInstance(
                ImmediateGuardProbe.class.getClassLoader(), new Class<?>[]{com.mojang.blaze3d.vertex.VertexConsumer.class},
                (proxy, method, args) -> { throw new AssertionError("WRAPPER_TOUCHED_HOST_CONSUMER"); });
        for (CapePass pass : passes) check(pass.wrapper().apply(hostConsumer) == hostConsumer, "PUBLIC_WRAPPER_IDENTITY");
    }
    public static void closed() throws Exception {
        try (HeadlessCapeClient client = HeadlessCapeClient.open(false)) {
            var support = WaveyCapesSupport.register();
            check(support != null, "REGISTRATION"); support.close(); support.close();
            PlayerWrapper late = new PlayerWrapper((net.minecraft.client.player.AbstractClientPlayer) null) {
                @Override public ResourceLocation getCapeTexture() { throw new AssertionError("CLOSED_FOREIGN_LOOKUP"); }
            };
            check(support.getCapeInfo(late) == null && !support.shouldBeUsed(late) && support.getCapePasses(late).isEmpty(), "CLOSED_REVIVED");
        }
    }
    public static void callback(Path root) throws Exception {
        Minecraft previous = Minecraft.getInstance();
        var previousUser = previous == null ? null : previous.getUser();
        try (HeadlessCapeClient client = HeadlessCapeClient.open(true); WaveyProducerFixture producer = new WaveyProducerFixture(root)) {
            Minecraft minecraft = Minecraft.getInstance();
            field(minecraft, Minecraft.class, "user", ImmediateAppearanceInputs.currentUser(producer.self));
            check(minecraft != previous && producer.self.equals(minecraft.getUser().getProfileId()), "CURRENT_SUBJECT_IDENTITY");
            var actor = allocate(ImmediateCapeProbe.Actor.class);
            field(actor, ImmediateCapeProbe.Actor.class, "identity", producer.self);
            check(actor.getUUID().equals(minecraft.getUser().getProfileId()), "ACTOR_SUBJECT_IDENTITY");
            var installed = ResourceLocation.tryParse(CapeProjection.material(producer.self, "Self", true).capeLocation());
            class Wrapper extends PlayerWrapper {
                boolean broken;
                Wrapper(ImmediateCapeProbe.Actor subject) { super(subject); }
                @Override public ResourceLocation getCapeTexture() { if (broken) throw new NoSuchMethodError("SYNTHETIC_GETTER_ABI"); return installed; }
                @Override public boolean isPlayerInvisible() { return false; }
                @Override public boolean isCapeVisible() { return true; }
                @Override public boolean hasElytraEquipped() { return false; }
            }
            var wrapper = new Wrapper(actor);
            CapeRenderer fallback = new CapeRenderer() {
                public CapeInfos getCapeInfo(PlayerWrapper player) { return new CapeInfos(this, net.minecraft.client.renderer.RenderType.entityTranslucent(installed), false); }
            };
            ModSupport foreign = new ModSupport() {
                public boolean shouldBeUsed(PlayerWrapper player) { return true; }
                public CapeRenderer getRenderer() { return fallback; }
                public int priority() { return 0; }
                public boolean blockFeatureRenderer(Object player) { return false; }
            };
            Set<ModSupport> registry = SupportManager.getSupportedMods(), before = new HashSet<>(registry);
            registry.add(foreign);
            try (var support = WaveyCapesSupport.register()) {
                check(support != null && support.shouldBeUsed(wrapper), "CALLBACK_PREREQUISITE");
                int profiles = producer.profileReads.get(), images = producer.imageReads.get();
                var foreignActor = allocate(ImmediateCapeProbe.Actor.class);
                field(foreignActor, ImmediateCapeProbe.Actor.class, "identity", UUID.randomUUID());
                check(!foreignActor.getUUID().equals(actor.getUUID()), "FOREIGN_SUBJECT_PREREQUISITE");
                var foreignSubject = new Wrapper(foreignActor);
                check(foreignSubject.getCapeTexture().equals(wrapper.getCapeTexture())
                        && !support.shouldBeUsed(foreignSubject) && support.getCapePasses(foreignSubject).isEmpty()
                        && !CapeProjection.captureCapeTexture(foreignActor.getUUID(), "Self", false, installed.toString()).isValid(),
                        "FOREIGN_SUBJECT_BORROWED_SELF_CAPTURE");
                check(support.shouldBeUsed(wrapper) && profiles == producer.profileReads.get() && images == producer.imageReads.get(),
                        "SUBJECT_CONTROL_CHANGED_SELF_OR_PERFORMED_IO");
                wrapper.broken = true;
                expectedActor = wrapper; expectedPose = new com.mojang.blaze3d.vertex.PoseStack();
                var buffers = ImmediateBuffers.create();
                new CapeNodeCollector().submitCape(buffers, wrapper, expectedPose, 157, 0.25f);
                buffers.endLastBatch();
                check(!support.shouldBeUsed(wrapper) && geometries == 1 && renderers.equals(List.of(fallback))
                        && registry.contains(foreign) && ImmediateBuffers.pending() == 0, "CALLBACK_FOREIGN_DISPATCH");
            } finally { registry.remove(foreign); }
            check(registry.equals(before), "CALLBACK_REGISTRY_RESTORE");
        } finally {
            check(Minecraft.getInstance() == previous && (previous == null || previous.getUser() == previousUser), "PREVIOUS_SUBJECT_RESTORED");
        }
    }
}
