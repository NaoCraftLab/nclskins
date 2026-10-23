package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.*;
import com.naocraftlab.skins.client.TextureUseValidity;
import com.naocraftlab.skins.compat.client.identifier.foil.*;
import com.naocraftlab.skins.runtime.*;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.*;
import dev.tr7zw.waveycapes.support.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.*;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;

public final class CapeDriverAbortProbe extends CustomFeatureRenderer {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    public static void main(String[] args) throws Exception {
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        runCase(args);
    }
    public static void runCase(String[] args) throws Exception {
        boolean captured = args[1].equals("captured"), aborted = args[2].equals("abort");
        try (HeadlessCapeClient client = HeadlessCapeClient.open(false); WaveyProducerFixture producer = new WaveyProducerFixture(Path.of(args[0]))) {
            String installed = CapeProjection.material(producer.self, "Self", true).capeLocation();
            TextureUseValidity token = CapeProjection.captureCapeTexture(producer.self, "Self", true, installed);
            RenderType nativeBase = RenderTypes.entityTranslucent(Identifier.parse(installed));
            RenderType base = captured ? CapturedCapePass.material(nativeBase, token, false) : nativeBase;
            RenderType foil = captured ? OrderedCapeFoil.pass(token) : RenderTypes.entityGlint();
            RenderType independent = captured ? CapturedCapePass.material(nativeBase, () -> true, false) : nativeBase;
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
            try (CapeNativeDriver driver = new CapeNativeDriver(new CapeDriverAbortProbe())) {
                SubmitNodeStorage storage = new SubmitNodeStorage();
                storage.submitCustomGeometry(new PoseStack(), nativeBase, (p, c) -> c.addVertex(0, 0, 0));
                new CapeNodeCollector().submitCape(storage, null, new PoseStack(), 0, 0);
                storage.submitCustomGeometry(new PoseStack(), nativeBase, (p, c) -> c.addVertex(0, 0, 0));
                storage.submitCustomGeometry(new PoseStack(), independent, (p, c) -> c.addVertex(0, 0, 0));
                var context = driver.context;
                var dispatcher = driver.dispatcher;
                var frame = driver.frame;
                var prepare = driver.prepare;
                Field renderersField = FeatureRenderDispatcher.class.getDeclaredField("featureRenderers"); renderersField.setAccessible(true);
                var consumer = ((FeatureRendererMap) renderersField.get(dispatcher)).get(CustomFeatureRenderer.TYPE);
                CapeHost.abort = aborted;
                Throwable failure = null;
                try {
                    check(prepare.invoke(dispatcher, context, storage) == frame, "Driver frame identity");
                    frame.close();
                } catch (InvocationTargetException thrown) {
                    failure = thrown.getCause();
                    check(aborted && failure instanceof IllegalStateException
                            && "CAPE_FIXTURE_ABORT".equals(failure.getMessage()), "Original native exception changed");
                }
                Field current = RenderTypeFeatureRenderer.class.getDeclaredField("currentGroup"); current.setAccessible(true);
                boolean groupRetained = current.get(consumer) != null;
                boolean contextRetained = field(frame, "context") != null;
                int retained = ((List<?>) field(frame, "allSubmits")).size();
                Throwable next = null;
                try { prepare.invoke(dispatcher, context, new SubmitNodeStorage()); frame.close(); }
                catch (InvocationTargetException thrown) { next = thrown.getCause(); }
                producer.close();

                check(!token.isValid() && producer.sink.registry.size() == 0, "New own resource retention");
                check(producer.profileReads.get() == 1 && producer.imageReads.get() == 1, "Unexpected producer work");
                if (aborted) {
                    check(failure != null && groupRetained && contextRetained && retained == 5
                            && next instanceof IllegalStateException && "PreparedFrame already in use".equals(next.getMessage()),
                            "Production captured/unmarked abort differs from confirmed baseline");
                } else check(!groupRetained && !contextRetained && retained == 0 && next == null, "Normal frame cleanup changed");
                System.out.println("ACTUAL_DRIVER_DIFFERENTIAL captured=" + captured + " aborted=" + aborted
                        + " currentGroupRetained=" + groupRetained + " contextRetained=" + contextRetained
                        + " retainedSubmits=" + retained + " nextPrepareFailure=" + (next == null ? "none" : next.getMessage())
                        + " ownResourcesAfterClose=0 tokenValidAfterClose=false; baseline recovery NOT_ACCEPTED; no GPU");
            } finally { SupportManager.getSupportedMods().remove(support); }
        }
    }
}
