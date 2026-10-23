package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.*;
import com.naocraftlab.skins.compat.client.identifier.foil.*;
import com.naocraftlab.skins.runtime.*;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.*;
import dev.tr7zw.waveycapes.support.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;

public final class BufferedCapeProbe {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class HostBuffers extends MultiBufferSource.BufferSource {
        HostBuffers() { super(null, new LinkedHashMap<>()); }
    }
    public static void main(String[] args) throws Exception {
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        runCase(args);
    }
    public static void runCase(String[] args) throws Exception {
        try (HeadlessCapeClient client = HeadlessCapeClient.open(false); WaveyProducerFixture producer = new WaveyProducerFixture(Path.of(args[0]))) {
            String installed = CapeProjection.material(producer.self, "Self", true).capeLocation();
            var token = CapeProjection.captureCapeTexture(producer.self, "Self", true, installed);
            check(token.isValid(), "Producer did not install current capture");
            RenderType base = CapturedCapePass.material(RenderTypes.entityTranslucent(Identifier.parse(installed)), token, false);
            RenderType foil = OrderedCapeFoil.pass(token);
            CapeRenderer renderer = new CapeRenderer() {
                public CapeInfos getCapeInfo(PlayerWrapper player) { throw new AssertionError("Legacy path"); }
                public List<CapePass> getCapePasses(PlayerWrapper player) {
                    return List.of(new CapePass(base, java.util.function.UnaryOperator.identity()),
                            new CapePass(foil, java.util.function.UnaryOperator.identity()));
                }
            };
            ModSupport support = new ModSupport() {
                public boolean shouldBeUsed(PlayerWrapper player) { return true; }
                public CapeRenderer getRenderer() { return renderer; }
                public int priority() { return 1; }
                public boolean blockFeatureRenderer(Object player) { return false; }
            };
            check(SupportManager.getSupportedMods().add(support), "Registration");
            try {
                SubmitNodeStorage storage = new SubmitNodeStorage();
                new CapeNodeCollector().submitCape(storage, null, new PoseStack(), 0, 0);
                check(storage.getSubmitsPerOrder().size() == 2, "Foil ordering absent");
                boolean before = args[1].equals("before"), after = args[1].equals("after");
                if (before) producer.coordinator.close();
                CustomFeatureRenderer consumer = new CustomFeatureRenderer();
                for (var collection : storage.getSubmitsPerOrder().values()) {
                    HostBuffers buffers = new HostBuffers();
                    for (java.lang.reflect.Method render : geometryMethods()) render.invoke(consumer, collection, buffers);
                    if (after) producer.coordinator.close();
                    buffers.endBatch();
                }
                check(BufferedCapeHost.geometry == (before ? 0 : after ? 1 : 2), "Skipped/stale callback contract");
                check(BufferedCapeHost.draws == (before || after ? 0 : 2), "Stale draw was not cancelled");
                BufferedCapeHost.verifyClosed();
                check(producer.profileReads.get() == 1 && producer.imageReads.get() == 1, "Render I/O");
                System.out.println("BUFFERED_PRODUCTION_PASS scenario=" + args[1] + " geometry=" + BufferedCapeHost.geometry
                        + " draws=" + BufferedCapeHost.draws + " meshesClosed=" + BufferedCapeHost.closes
                        + "; actual getBuffer/endBatch; real reader/coordinator; no GPU");
            } finally { SupportManager.getSupportedMods().remove(support); }
        }
    }
    public static void drain(SubmitNodeStorage storage) throws ReflectiveOperationException {
        CustomFeatureRenderer consumer = new CustomFeatureRenderer();
        for (var collection : storage.getSubmitsPerOrder().values()) {
            HostBuffers buffers = new HostBuffers();
            for (var method : geometryMethods()) method.invoke(consumer, collection, buffers);
            buffers.endBatch();
        }
    }
    private static List<java.lang.reflect.Method> geometryMethods() throws NoSuchMethodException {
        Class<?> owner = CustomFeatureRenderer.class;
        Class<?> collection = SubmitNodeCollection.class;
        Class<?> buffers = MultiBufferSource.BufferSource.class;
        try { return List.of(owner.getMethod("render", collection, buffers)); }
        catch (NoSuchMethodException split) {
            return List.of(owner.getMethod("renderSolid", collection, buffers),
                    owner.getMethod("renderTranslucent", collection, buffers));
        }
    }
}
