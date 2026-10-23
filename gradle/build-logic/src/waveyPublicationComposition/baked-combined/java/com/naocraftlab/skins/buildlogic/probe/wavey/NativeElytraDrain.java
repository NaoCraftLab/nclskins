package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.*;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;

public final class NativeElytraDrain {
    public static AutoCloseable open() throws Exception {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        var field = net.minecraft.client.Minecraft.class.getDeclaredField("gameRenderer"); field.setAccessible(true);
        Object previous = field.get(minecraft);
        try { CombinedCapeProbe.initializeRenderOptions(); }
        catch (Exception | Error failure) { field.set(minecraft, previous); throw failure; }
        return () -> {
            field.set(minecraft, previous);
            if (field.get(minecraft) != previous) throw new AssertionError("ELYTRA_NATIVE_RENDERER_RESTORE");
        };
    }
    public static net.minecraft.client.resources.model.EquipmentClientInfo equipment(net.minecraft.client.resources.model.EquipmentClientInfo.Layer layer) {
        return new net.minecraft.client.resources.model.EquipmentClientInfo(Map.of(net.minecraft.client.resources.model.EquipmentClientInfo.LayerType.WINGS, List.of(layer)), List.of());
    }
    public static void consume(SubmitNodeStorage storage, boolean enchanted, Identifier texture) throws Exception {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        var field = net.minecraft.client.Minecraft.class.getDeclaredField("gameRenderer"); field.setAccessible(true);
        Object previous = field.get(minecraft);
        var previousPass = CombinedCapeHost.expectedPass; var previousStage = CombinedCapeHost.expectedStage;
        int acquired = CombinedCapeHost.acquired.size(), prepared = CombinedCapeHost.prepared.size();
        int drawn = CombinedCapeHost.drawn.size(), infos = CombinedCapeHost.infos.size();
        try {
            FeatureFrameContext context = new FeatureFrameContext(null, null, null, null, null, null, null, CombinedCapeStaged.open());
            RenderPass pass = (RenderPass) Proxy.newProxyInstance(NativeElytraDrain.class.getClassLoader(), new Class<?>[]{RenderPass.class},
                    (proxy, method, args) -> { throw new AssertionError("GPU API reached"); });
            CombinedCapeHost.expectedPass = pass; CombinedCapeHost.expectedStage = null;
            ModelFeatureRenderer models = new ModelFeatureRenderer();
            try {
                FeatureRenderPhase.Output output = new FeatureRenderPhase.Output() {
                    int groupIndex;
                    public void accept(SubmitNode node, boolean ordered) {
                        if (!(node instanceof ModelFeatureRenderer.Submit<?> submit)) throw new AssertionError("Unexpected elytra feature");
                        acceptFeatureGroup(ModelFeatureRenderer.TYPE, List.of(submit), ordered);
                    }
                    public <T extends SubmitNode> void acceptFeatureGroup(FeatureRendererType<T> renderer, Collection<T> nodes, boolean ordered) {
                        if (renderer != ModelFeatureRenderer.TYPE) throw new AssertionError("Unexpected elytra feature");
                        @SuppressWarnings("unchecked") List<ModelFeatureRenderer.Submit<?>> submits = (List<ModelFeatureRenderer.Submit<?>>) (List<?>) List.copyOf(nodes);
                        models.prepareGroup(context, submits, ordered);
                        models.executeGroup(context, null, pass, groupIndex++, submits, ordered);
                    }
                };
                storage.drainPhases(phase -> phase.sortInto(output));
            } finally { models.finishExecute(context); }
            RenderType expected = enchanted ? RenderTypes.armorCutoutNoCullGlint(texture) : RenderTypes.armorCutoutNoCull(texture);
            var types = CombinedCapeHost.acquired.subList(acquired, CombinedCapeHost.acquired.size());
            var preparedGroup = CombinedCapeHost.prepared.subList(prepared, CombinedCapeHost.prepared.size());
            var drawnGroup = CombinedCapeHost.drawn.subList(drawn, CombinedCapeHost.drawn.size());
            if (types.size() != 1 || types.getFirst() != expected || CapturedCapePass.of(types.getFirst()) != null
                    || preparedGroup.size() != 1 || drawnGroup.size() != 1 || preparedGroup.getFirst() != drawnGroup.getFirst()
                    || ((CapturedCapePass.PreparedCarrier) (Object) preparedGroup.getFirst()).nclskins$capture() != null
                    || CombinedCapeHost.infos.subList(infos, CombinedCapeHost.infos.size()).stream().mapToInt(StagedVertexBuffer.ExecuteInfo::indexCount).sum() != 1)
                throw new AssertionError("ELYTRA_NATIVE_FOIL_OR_DRAIN_CHANGED");
            System.out.println("NATIVE_ELYTRA_DRAIN_PASS combined enchanted=" + enchanted + " nativeFoil=" + (enchanted ? 1 : 0)
                    + " actual armorCutoutNoCull/armorCutoutNoCullGlint; prepared=1 draws=1 same prepared/info/pass identities; native finishExecute");
        } finally {
            field.set(minecraft, previous);
            CombinedCapeHost.expectedPass = previousPass; CombinedCapeHost.expectedStage = previousStage;
            CombinedCapeStaged.reset();
            if (field.get(minecraft) != previous || !CombinedCapeStaged.empty()) throw new AssertionError("ELYTRA_NATIVE_CLEANUP_CHANGED");
        }
    }
}
