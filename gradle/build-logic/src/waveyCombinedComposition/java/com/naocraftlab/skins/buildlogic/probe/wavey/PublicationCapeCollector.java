package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import com.naocraftlab.skins.compat.waveycapes.WaveyCapesSupport;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

public final class PublicationCapeCollector {
    public static void collect(AvatarRenderState state, WaveyCapesSupport support) {
        var actor = CapePublicationProbe.wrapper(state);
        var passes = support.getCapePasses(actor);
        var storage = new SubmitNodeStorage();
        new dev.tr7zw.waveycapes.render.CapeNodeCollector().submitCape(storage, actor, new PoseStack(), 31, 0.25F);
        var pending = CombinedCapeProbe.drain(storage);
        CombinedCapeHost.check(passes.size() == 1 && pending.size() == 1, "Combined publication did not produce one native pass");
        var capture = CapturedCapePass.of(pending.getFirst().renderType());
        CombinedCapeHost.check(capture != null && capture.validity() == CapePublicationProbe.material(state).validity()
                && !capture.orderedFoil(), "Combined collector lost published material capture");
        CombinedCapeHost.check(((CapturedCapePass.Carrier) pending.getFirst().renderType()).nclskins$setup()
                == ((CapturedCapePass.Carrier) passes.getFirst().renderType()).nclskins$setup(), "Publication native setup changed");
        consume(pending, actor, support, 31, 0.25F);
        CombinedCapeHost.check(CombinedCapeHost.geometry == 1 && CombinedCapeHost.drawn.size() == 1,
                "Published combined material did not execute exactly one native geometry/draw");
    }
    public static void consume(java.util.List<net.minecraft.client.renderer.feature.CustomFeatureRenderer.Submit> pending, dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper actor,
            dev.tr7zw.waveycapes.render.CapeRenderer support, int light, float delta) {
        net.minecraft.client.renderer.feature.FeatureFrameContext context;
        try {
            CombinedCapeProbe.initializeRenderOptions();
            context = new net.minecraft.client.renderer.feature.FeatureFrameContext(null, null, null, null, null, null, null, CombinedCapeStaged.open());
        } catch (Exception failure) { throw new AssertionError("Headless context setup", failure); }
        CombinedCapeHost.reset();
        CombinedCapeHost.expectedActor = actor; CombinedCapeHost.expectedRenderer = support;
        CombinedCapeHost.expectedStage = null;
        CombinedCapeHost.expectedLight = light; CombinedCapeHost.expectedDelta = delta;
        var pass = (com.mojang.renderpearl.api.commands.RenderPass) java.lang.reflect.Proxy.newProxyInstance(
                PublicationCapeCollector.class.getClassLoader(), new Class<?>[]{com.mojang.renderpearl.api.commands.RenderPass.class},
                (proxy, method, args) -> { throw new AssertionError("GPU API reached"); });
        CombinedCapeHost.expectedPass = pass;
        var consumer = new net.minecraft.client.renderer.feature.CustomFeatureRenderer();
        try {
            consumer.prepareGroup(context, pending, false);
            consumer.executeGroup(context, null, pass, 0, pending, false);
        } finally { consumer.finishExecute(context); }
    }
}
