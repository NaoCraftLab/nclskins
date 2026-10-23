package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.CapeRenderer;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.oit.*;
import net.minecraft.client.renderer.rendertype.*;

public final class CombinedCapeHost {
    public static final List<RenderType> acquired = new ArrayList<>();
    public static final List<PreparedRenderType> prepared = new ArrayList<>();
    public static final List<PreparedRenderType> drawn = new ArrayList<>();
    public static final List<StagedVertexBuffer.ExecuteInfo> infos = new ArrayList<>();
    public static final Set<CapeRenderer> renderers = Collections.newSetFromMap(new IdentityHashMap<>());
    public static final Set<PlayerWrapper> actors = Collections.newSetFromMap(new IdentityHashMap<>());
    public static PlayerWrapper expectedActor;
    public static CapeRenderer expectedRenderer;
    public static VertexConsumer currentConsumer;
    public static OitStage expectedStage;
    public static RenderPass expectedPass;
    public static RuntimeException geometryFailure, drawFailure;
    public static Runnable retireAtDraw;
    public static int geometry, expectedLight = 31;
    public static float expectedDelta = 0.25F;

    public static PreparedRenderType prepare(RenderType type) throws Exception {
        acquired.add(type);
        var setup = ((CapturedCapePass.Carrier) type).nclskins$setup();
        Field name = RenderType.class.getDeclaredField("name"); name.setAccessible(true);
        Field oit = RenderSetup.class.getDeclaredField("oitPipelineSet"); oit.setAccessible(true);
        PreparedRenderType value = new PreparedRenderType((String) name.get(type), type.pipeline(), (OitPipelineSet) oit.get(setup), null, null, List.of());
        prepared.add(value);
        return value;
    }
    public static void draw(PreparedRenderType value, StagedVertexBuffer.ExecuteInfo info, OitStage stage, RenderPass pass) {
        check(stage == expectedStage && pass == expectedPass, "Draw stage/pass identity changed");
        check(infos.stream().anyMatch(candidate -> candidate == info), "ExecuteInfo identity changed");
        check(prepared.stream().anyMatch(candidate -> candidate == value), "Prepared identity changed");
        var capture = ((CapturedCapePass.PreparedCarrier) (Object) value).nclskins$capture();
        check(capture == null || capture.isValid(), "Stale draw reached terminal");
        drawn.add(value);
        if (retireAtDraw != null && capture == null) { Runnable retire = retireAtDraw; retireAtDraw = null; retire.run(); }
        if (drawFailure != null) throw drawFailure;
    }
    public static void hostGeometry(PlayerWrapper actor, CapeRenderer renderer, VertexConsumer supplied, PoseStack pose, int light, float delta) {
        check((expectedActor == null || actor == expectedActor) && (expectedRenderer == null || renderer == expectedRenderer), "Genuine geometry actor/renderer identity changed");
        actors.add(actor); renderers.add(renderer);
        check(supplied == currentConsumer, "Public wrapper did not preserve host consumer identity");
        check(light == expectedLight && delta == expectedDelta, "Collector callback arguments changed");
        geometry++;
        if (geometryFailure != null) throw geometryFailure;
        supplied.addVertex(0, 0, 0);
    }
    public static void reset() {
        acquired.clear(); prepared.clear(); drawn.clear(); infos.clear(); renderers.clear(); actors.clear(); geometry = 0;
        geometryFailure = null; drawFailure = null; retireAtDraw = null;
        expectedActor = null; expectedRenderer = null; currentConsumer = null; expectedStage = null; expectedPass = null;
        expectedLight = 31; expectedDelta = 0.25F;
    }
    public static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
