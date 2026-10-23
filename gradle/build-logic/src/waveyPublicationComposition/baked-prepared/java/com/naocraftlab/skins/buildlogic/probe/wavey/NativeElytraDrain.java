package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.*;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;

public final class NativeElytraDrain {
    public static AutoCloseable open() { return () -> {}; }
    public static net.minecraft.client.resources.model.EquipmentClientInfo equipment(net.minecraft.client.resources.model.EquipmentClientInfo.Layer layer) {
        return new net.minecraft.client.resources.model.EquipmentClientInfo(java.util.Map.of(net.minecraft.client.resources.model.EquipmentClientInfo.LayerType.WINGS, java.util.List.of(layer)));
    }
    public static void consume(SubmitNodeStorage storage, boolean enchanted, Identifier texture) throws Exception {
        int prepared = CapeHost.prepared.size(), draws = CapeHost.draws, vertices = CapeHost.foreignVertices;
        var renderers = new FeatureRendererMap(); renderers.put(ModelFeatureRenderer.TYPE, new ModelFeatureRenderer());
        try {
            try (CapeNativeDriver driver = new CapeNativeDriver(renderers)) { driver.drain(storage); }
            var group = CapeHost.prepared.subList(prepared, CapeHost.prepared.size());
            int count = enchanted ? 2 : 1;
            if (group.stream().filter(t -> t.pipeline() == RenderTypes.armorEntityGlint().pipeline()).count() != (enchanted ? 1 : 0)
                    || group.stream().filter(t -> t.pipeline() == RenderTypes.armorCutoutNoCull(texture).pipeline()).count() != 1
                    || group.stream().anyMatch(t -> ((CapturedCapePass.PreparedCarrier) (Object) t).nclskins$capture() != null)
                    || group.size() != count || CapeHost.draws - draws != count || CapeHost.foreignVertices - vertices != count)
                throw new AssertionError("ELYTRA_NATIVE_FOIL_OR_DRAIN_CHANGED");
            System.out.println("NATIVE_ELYTRA_DRAIN_PASS prepared enchanted=" + enchanted + " nativeFoil=" + (enchanted ? 1 : 0)
                    + " prepared=" + count + " draws=" + count + " native frame closed");
        } finally { CapeStaged.reset(); }
    }
}
