package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.*;
import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.CapeRenderer;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.rendertype.*;

public final class CapeHost {
    public static boolean abort;
    public static int prepares, wrappers, geometry, draws, foreignVertices, ownVertices;
    public static final List<PreparedRenderType> prepared = new ArrayList<>();
    public static final Set<CapeRenderer> renderers = new HashSet<>();
    public static void reset() {
        abort = false;
        prepares = wrappers = geometry = draws = foreignVertices = ownVertices = 0;
        prepared.clear(); renderers.clear();
    }

    public static PreparedRenderType prepare(RenderType type) {
        prepares++;
        CapturedCapePass capture = CapturedCapePass.of(type);
        if (capture != null && !capture.validity().isValid())
            throw new AssertionError("RETIRED_AT_PREPARE wrappers=" + wrappers + " geometry=" + geometry);
        PreparedRenderType result = new PreparedRenderType(type.pipeline(), type.outputTarget(), null, null, List.of());
        prepared.add(result);
        return result;
    }
    public static void draw(PreparedRenderType type, StagedVertexBuffer.ExecuteInfo info) {
        CapturedCapePass capture = ((CapturedCapePass.PreparedCarrier) (Object) type).nclskins$capture();
        if (capture != null && !capture.validity().isValid())
            throw new AssertionError("RETIRED_AT_EXECUTE prepares=" + prepares + " geometry=" + geometry);
        draws++;
        if (capture == null) foreignVertices += info.indexCount(); else ownVertices += info.indexCount();
    }
    public static void hostGeometry(PlayerWrapper actor, CapeRenderer renderer, VertexConsumer supplied,
            PoseStack pose, int light, float delta) {
        geometry++;
        renderers.add(renderer);
        if (abort) throw new IllegalStateException("CAPE_FIXTURE_ABORT");
        supplied.addVertex(0, 0, 0);
    }
}
