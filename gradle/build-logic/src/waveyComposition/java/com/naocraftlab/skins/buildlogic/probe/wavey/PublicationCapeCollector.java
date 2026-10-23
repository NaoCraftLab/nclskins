package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import com.naocraftlab.skins.compat.waveycapes.WaveyCapesSupport;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import java.util.Collection;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

public final class PublicationCapeCollector {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void collect(AvatarRenderState state, WaveyCapesSupport support) {
        PlayerWrapper actor = CapePublicationProbe.wrapper(state);
        var passes = support.getCapePasses(actor);
        var storage = new net.minecraft.client.renderer.SubmitNodeStorage();
        int before = CapeHost.geometry;
        new dev.tr7zw.waveycapes.render.CapeNodeCollector().submitCape(storage, actor, new com.mojang.blaze3d.vertex.PoseStack(), 0, 0);
        if (Boolean.getBoolean("nclskins.wavey.loaderBatch")) {
            int draws = CapeHost.draws, vertices = CapeHost.ownVertices;
            try (CapeNativeDriver driver = new CapeNativeDriver(new net.minecraft.client.renderer.feature.CustomFeatureRenderer())) { driver.drain(storage); }
            catch (Exception failure) { throw new AssertionError("CONNECTED_PREPARED_NATIVE_DRAIN", failure); }
            check(CapeHost.geometry - before == passes.size() && CapeHost.draws - draws == passes.size()
                    && CapeHost.ownVertices - vertices == passes.size(), "CONNECTED_PUBLICATION_NATIVE_CONSUMER");
            System.out.println("CONNECTED_PUBLICATION_NATIVE_PASS prepared passes=" + passes.size());
            return;
        }
        final int[] count = {0};
        storage.drainPhases(phase -> phase.sortInto(new net.minecraft.client.renderer.feature.phase.FeatureRenderPhase.Output() {
            public void accept(net.minecraft.client.renderer.feature.submit.SubmitNode node, boolean ordered) { throw new AssertionError("Unexpected singleton"); }
            public <T extends net.minecraft.client.renderer.feature.submit.SubmitNode> void acceptFeatureGroup(
                    net.minecraft.client.renderer.feature.FeatureRendererType<T> type, Collection<T> nodes, boolean ordered) {
                for (T node : nodes) {
                    var submit = (net.minecraft.client.renderer.feature.CustomFeatureRenderer.Submit) node;
                    check(CapturedCapePass.of(submit.renderType()).validity() == CapePublicationProbe.material(state).validity(), "Collector lost publication capture");
                    count[0]++;
                }
            }
        }));
        check(count[0] == passes.size() && CapeHost.geometry == before, "Genuine collector did not submit exactly the public plan");
    }}
