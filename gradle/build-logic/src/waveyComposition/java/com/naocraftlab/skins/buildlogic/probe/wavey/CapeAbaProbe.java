package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.*;
import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.*;
import net.minecraft.client.renderer.feature.phase.*;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;

public final class CapeAbaProbe extends CustomFeatureRenderer {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        runCase(args);
    }
    public static void runCase(String[] args) throws Exception {
        try (HeadlessCapeClient client = HeadlessCapeClient.open(false)) {
        CapeRegistry registry = new CapeRegistry();
        try {
            String hash = "a".repeat(64);
            var old = registry.registerRgba(TextureRegistry.TextureKind.IMAGE, hash, 1, 1, new byte[4], () -> {});
            var retired = registry.capture(old);
            registry.release(old);
            var replacement = registry.registerRgba(TextureRegistry.TextureKind.IMAGE, hash, 1, 1, new byte[4], () -> {});
            var current = registry.capture(replacement);
            check(old.equals(replacement) && old != replacement && retired != current
                    && !retired.isValid() && !registry.capture(old).isValid() && current.isValid(), "ABA admission");
            RenderType nativeBase = RenderTypes.entityTranslucent(Identifier.parse(replacement.location()));
            RenderType stale = CapturedCapePass.material(nativeBase, retired, false);
            RenderType live = CapturedCapePass.material(nativeBase, current, false);
            SubmitNodeStorage storage = new SubmitNodeStorage();
            storage.submitCustomGeometry(new PoseStack(), stale, (p, c) -> { throw new AssertionError("Stale ABA callback"); });
            storage.submitCustomGeometry(new PoseStack(), live, (p, c) -> c.addVertex(0, 0, 0));
            storage.submitCustomGeometry(new PoseStack(), nativeBase, (p, c) -> c.addVertex(0, 0, 0));
            List<CustomFeatureRenderer.Submit> pending = new ArrayList<>();
            storage.drainPhases(phase -> phase.sortInto(new FeatureRenderPhase.Output() {
                public void accept(SubmitNode node, boolean ordered) { throw new AssertionError("Singleton"); }
                public <T extends SubmitNode> void acceptFeatureGroup(FeatureRendererType<T> type, Collection<T> nodes, boolean ordered) {
                    for (T node : nodes) pending.add((CustomFeatureRenderer.Submit) node);
                }
            }));
            boolean foreignFirst = args[0].equals("foreign-first");
            pending.sort(Comparator.comparingInt(submit -> {
                boolean own = CapturedCapePass.of(submit.renderType()) != null;
                return foreignFirst ? own ? 1 : 0 : own ? 0 : 1;
            }));
            var context = new FeatureFrameContext(null, null, null, null, null, null, null, CapeStaged.headless());
            var consumer = new CapeAbaProbe();
            try {
                consumer.prepareGroup(context, pending, false);
                consumer.executeGroup(context, 0, pending, false);
            } finally { consumer.finishExecute(context); }
            check(CapeHost.foreignVertices == 1 && CapeHost.ownVertices == 1 && CapeHost.prepares == 2,
                    "ABA or equal-prepared foreign/live draw lost");
            registry.close();
            check(!current.isValid() && registry.size() == 0, "Replacement incarnation did not retire");
            System.out.println("PRODUCTION_ABA_PASS order=" + args[0] + "; real registry/native consumer, old value-equal handle rejected; no reader claim, no GPU");
        } finally { registry.close(); }
        }
    }
}
