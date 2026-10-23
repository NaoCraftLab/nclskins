package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import com.naocraftlab.skins.compat.waveycapes.WaveyCapesSupport;
import java.util.*;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;

public final class PublicationCapeCollector {
    public static void collect(AvatarRenderState state, WaveyCapesSupport support) {
        var actor = CapePublicationProbe.wrapper(state);
        var passes = support.getCapePasses(actor);
        SubmitNodeStorage storage = new SubmitNodeStorage();
        new dev.tr7zw.waveycapes.render.CapeNodeCollector().submitCape(storage, actor,
                new com.mojang.blaze3d.vertex.PoseStack(), 0, 0);
        int count = 0;
        try {
            for (var collection : storage.getSubmitsPerOrder().values()) {
                for (String name : new String[]{"solidCustomGeometrySubmits", "translucentCustomGeometrySubmits"}) {
                    var field = CustomFeatureRenderer.Storage.class.getDeclaredField(name);
                    field.setAccessible(true);
                    Map<RenderType, List<SubmitNodeStorage.CustomGeometrySubmit>> submits =
                            (Map<RenderType, List<SubmitNodeStorage.CustomGeometrySubmit>>) field.get(collection.getCustomGeometrySubmits());
                    for (var entry : submits.entrySet()) {
                        if (CapturedCapePass.of(entry.getKey()).validity() != CapePublicationProbe.material(state).validity())
                            throw new AssertionError("Buffered collector lost actual publication capture");
                        count += entry.getValue().size();
                    }
                }
            }
        } catch (ReflectiveOperationException failure) { throw new AssertionError("Exact buffered storage ABI", failure); }
        if (count != passes.size()) throw new AssertionError("Buffered genuine collector did not submit exact public plan");
        if (Boolean.getBoolean("nclskins.wavey.loaderBatch")) {
            int geometry = BufferedCapeHost.geometry, draws = BufferedCapeHost.draws;
            try { BufferedCapeProbe.drain(storage); }
            catch (ReflectiveOperationException failure) { throw new AssertionError("CONNECTED_BUFFERED_SPLIT_DRAIN", failure); }
            if (BufferedCapeHost.geometry - geometry != passes.size() || BufferedCapeHost.draws - draws != passes.size())
                throw new AssertionError("CONNECTED_PUBLICATION_NATIVE_CONSUMER");
            BufferedCapeHost.verifyClosed();
            System.out.println("CONNECTED_PUBLICATION_NATIVE_PASS buffered-split passes=" + passes.size());
        }
    }
}
