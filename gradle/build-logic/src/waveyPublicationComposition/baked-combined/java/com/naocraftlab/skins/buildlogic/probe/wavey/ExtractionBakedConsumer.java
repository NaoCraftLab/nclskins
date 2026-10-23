package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.naocraftlab.skins.compat.client.identifier.extraction.NclBakedPlayerRenderState;
import com.naocraftlab.skins.compat.client.identifier.foil.CapeTranslucentFoilMaterial;
import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.*;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;

public final class ExtractionBakedConsumer {
    public static void consume(NclBakedPlayerRenderState state, boolean foil) throws Exception {
        Class<?> type = Class.forName("com.naocraftlab.skins.compat.client.identifier.extraction.NclBakedPlayerRenderer$TargetRenderer");
        Object consumer = CapePublicationProbe.allocate(type);
        var render = type.getDeclaredMethod("renderToTexture", NclBakedPlayerRenderState.class, PoseStack.class, SubmitNodeCollector.class); render.setAccessible(true);
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        var rendererField = net.minecraft.client.Minecraft.class.getDeclaredField("gameRenderer"); rendererField.setAccessible(true);
        var lightingField = GameRenderer.class.getDeclaredField("lighting"); lightingField.setAccessible(true);
        Object previousRenderer = rendererField.get(minecraft);
        Object previousLighting = lightingField.get(previousRenderer);
        if (previousLighting == null) throw new AssertionError("EXTRACTION_BAKED_LIGHTING_INPUT_MISSING");
        try {
            CombinedCapeProbe.initializeRenderOptions();
            lightingField.set(rendererField.get(minecraft), previousLighting);
            SubmitNodeStorage storage = new SubmitNodeStorage(); render.invoke(consumer, state, new PoseStack(), storage);
            FeatureFrameContext context = new FeatureFrameContext(null, null, null, null, null, null, null, CombinedCapeStaged.open());
            RenderPass pass = (RenderPass) Proxy.newProxyInstance(ExtractionBakedConsumer.class.getClassLoader(), new Class<?>[]{RenderPass.class},
                    (proxy, method, args) -> { throw new AssertionError("GPU API reached"); });
            CombinedCapeHost.expectedPass = pass; CombinedCapeHost.expectedStage = null;
            int acquired = CombinedCapeHost.acquired.size(), draws = CombinedCapeHost.drawn.size(), infos = CombinedCapeHost.infos.size();
            int[] nonemptyGroups = {0};
            ModelFeatureRenderer models = new ModelFeatureRenderer();
            try {
                FeatureRenderPhase.Output output = new FeatureRenderPhase.Output() {
                    int groupIndex;
                    public void accept(SubmitNode node, boolean ordered) {
                        if (!(node instanceof ModelFeatureRenderer.Submit<?> submit)) throw new AssertionError("Unexpected baked feature");
                        acceptFeatureGroup(ModelFeatureRenderer.TYPE, List.of(submit), ordered);
                    }
                    public <T extends SubmitNode> void acceptFeatureGroup(FeatureRendererType<T> renderer, Collection<T> nodes, boolean ordered) {
                        if (renderer != ModelFeatureRenderer.TYPE) throw new AssertionError("Unexpected baked feature");
                        @SuppressWarnings("unchecked") List<ModelFeatureRenderer.Submit<?>> submits = (List<ModelFeatureRenderer.Submit<?>>) (List<?>) List.copyOf(nodes);
                        int prepared = CombinedCapeHost.prepared.size(), drawn = CombinedCapeHost.drawn.size();
                        int groupInfos = CombinedCapeHost.infos.size();
                        models.prepareGroup(context, submits, ordered);
                        models.executeGroup(context, null, pass, groupIndex++, submits, ordered);
                        var preparedGroup = CombinedCapeHost.prepared.subList(prepared, CombinedCapeHost.prepared.size());
                        var drawnGroup = CombinedCapeHost.drawn.subList(drawn, CombinedCapeHost.drawn.size());
                        int executedInfos = CombinedCapeHost.infos.size() - groupInfos;
                        Set<Object> identities = Collections.newSetFromMap(new IdentityHashMap<>());
                        identities.addAll(preparedGroup);
                        String groupContext = " group=" + groupIndex + " submits=" + submits.size() + " prepared=" + preparedGroup.size()
                                + " drawn=" + drawnGroup.size() + " infos=" + executedInfos + " identities=" + identities.size();
                        if (submits.isEmpty()) {
                            if (!preparedGroup.isEmpty() || !drawnGroup.isEmpty() || executedInfos != 0)
                                throw new AssertionError("EXTRACTION_BAKED_GROUP_IDENTITY_CARDINALITY" + groupContext);
                        } else {
                            if (preparedGroup.isEmpty() || identities.size() != preparedGroup.size() || drawnGroup.size() != preparedGroup.size())
                                throw new AssertionError("EXTRACTION_BAKED_GROUP_IDENTITY_CARDINALITY" + groupContext);
                            for (var value : drawnGroup) if (!identities.remove(value)) throw new AssertionError("EXTRACTION_BAKED_GROUP_IDENTITY_CARDINALITY" + groupContext);
                            if (!identities.isEmpty()) throw new AssertionError("EXTRACTION_BAKED_GROUP_IDENTITY_CARDINALITY" + groupContext);
                            nonemptyGroups[0]++;
                        }
                        System.out.println("EXTRACTION_BAKED_GROUP_IDENTITY_PASS" + groupContext);
                    }
                };
                storage.drainPhases(phase -> phase.sortInto(output));
            } finally { models.finishExecute(context); }
            RenderType expected = state.attachmentTexture() == null ? null : CapeTranslucentFoilMaterial.forTexture(state.attachmentTexture());
            if (CombinedCapeHost.acquired.subList(acquired, CombinedCapeHost.acquired.size()).stream().filter(t -> t == expected).count() != (foil ? 1 : 0))
                throw new AssertionError("EXTRACTION_BAKED_NATIVE_FOIL_MISSING_OR_LEAKED");
            if (foil && !state.standaloneEquipment() && minecraft.level != null && nonemptyGroups[0] < 2)
                throw new AssertionError("EXTRACTION_BAKED_MULTIGROUP_MISSING");
            int count = (state.simpleAttachmentModel() == null ? 0 : 1) + (state.standaloneEquipment() ? 0 : 1);
            if (CombinedCapeHost.drawn.size() == draws || CombinedCapeHost.infos.subList(infos, CombinedCapeHost.infos.size()).stream().mapToInt(StagedVertexBuffer.ExecuteInfo::indexCount).sum() != count)
                throw new AssertionError("EXTRACTION_BAKED_NATIVE_DRAIN_MISSING");
        } finally {
            try {
                rendererField.set(minecraft, previousRenderer);
                if (rendererField.get(minecraft) != previousRenderer || lightingField.get(previousRenderer) != previousLighting)
                    throw new AssertionError("EXTRACTION_BAKED_RENDERER_RESTORE");
                System.out.println("EXTRACTION_BAKED_RENDERER_RESTORED same renderer+lighting");
            } finally {
                CombinedCapeStaged.reset();
            }
        }
    }
}
