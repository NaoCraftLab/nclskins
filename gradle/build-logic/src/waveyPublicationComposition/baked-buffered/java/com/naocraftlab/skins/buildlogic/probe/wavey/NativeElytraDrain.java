package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;

public final class NativeElytraDrain {
    public static AutoCloseable open() { return () -> {}; }
    public static net.minecraft.client.resources.model.EquipmentClientInfo equipment(net.minecraft.client.resources.model.EquipmentClientInfo.Layer layer) {
        return new net.minecraft.client.resources.model.EquipmentClientInfo(Map.of(net.minecraft.client.resources.model.EquipmentClientInfo.LayerType.WINGS, List.of(layer)));
    }
    private static final class Buffers extends MultiBufferSource.BufferSource {
        final List<RenderType> acquired = new ArrayList<>();
        Buffers() { super(null, new LinkedHashMap<>()); }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer getBuffer(RenderType type) {
            acquired.add(type); return super.getBuffer(type);
        }
    }
    public static void consume(SubmitNodeStorage storage, boolean enchanted, Identifier texture) {
        Buffers buffers = new Buffers(); ModelFeatureRenderer consumer = new ModelFeatureRenderer();
        int draws = BufferedCapeHost.draws, closes = BufferedCapeHost.closes;
        for (var collection : storage.getSubmitsPerOrder().values()) {
            consumer.renderSolid(collection, buffers, null, buffers);
            consumer.renderTranslucent(collection, buffers, null, buffers);
        }
        buffers.endBatch();
        int count = enchanted ? 2 : 1;
        if (buffers.acquired.stream().filter(t -> t == RenderTypes.armorEntityGlint()).count() != (enchanted ? 1 : 0)
                || buffers.acquired.stream().filter(t -> t == RenderTypes.armorCutoutNoCull(texture)).count() != 1
                || buffers.acquired.stream().anyMatch(t -> CapturedCapePass.of(t) != null)
                || buffers.acquired.size() != count || BufferedCapeHost.draws - draws != count
                || BufferedCapeHost.closes - closes != count)
            throw new AssertionError("ELYTRA_NATIVE_FOIL_OR_DRAIN_CHANGED");
        BufferedCapeHost.verifyClosed();
        System.out.println("NATIVE_ELYTRA_DRAIN_PASS buffered enchanted=" + enchanted + " nativeFoil=" + (enchanted ? 1 : 0) + " draws=" + count + " closes=" + count);
    }
}
