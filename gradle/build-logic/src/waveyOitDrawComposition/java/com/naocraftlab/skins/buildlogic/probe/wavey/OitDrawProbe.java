package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.naocraftlab.skins.client.TextureUseValidity;
import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;

public final class OitDrawProbe extends RenderTypeFeatureRenderer<SubmitNode> {
    private static final Map<PreparedRenderType, StagedVertexBuffer.ExecuteInfo> expected = new IdentityHashMap<>();
    private static final List<PreparedRenderType> calls = new ArrayList<>();
    private static RenderPass expectedPass;
    private static OitStage expectedStage;
    private static PreparedRenderType failOn;
    private static PreparedRenderType retireOn;
    private static Runnable retire;
    private static RuntimeException failure;
    private static int controls;

    @Override protected void buildGroup(FeatureFrameContext context, List<SubmitNode> submits) {
        throw new AssertionError("Draw-only fixture must not replace native preparation");
    }

    public static void drawFromBuffer(PreparedRenderType prepared, StagedVertexBuffer.ExecuteInfo info, RenderPass pass) {
        terminal(prepared, info, null, pass);
    }

    public static void drawFromBufferOit(PreparedRenderType prepared, StagedVertexBuffer.ExecuteInfo info,
            OitStage stage, RenderPass pass) {
        terminal(prepared, info, stage, pass);
    }

    private static void terminal(PreparedRenderType prepared, StagedVertexBuffer.ExecuteInfo info,
            OitStage stage, RenderPass pass) {
        check(expected.containsKey(prepared), "Prepared identity changed");
        check(expected.get(prepared) == info, "ExecuteInfo identity changed");
        check(stage == expectedStage, "OIT stage identity/branch changed");
        check(pass == expectedPass, "RenderPass identity changed");
        calls.add(prepared);
        if (retire != null && prepared == retireOn) { Runnable action = retire; retire = null; action.run(); }
        if (prepared == failOn) throw failure;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("no-mod")) {
            try { Class.forName("dev.tr7zw.waveycapes.support.ModSupport"); throw new AssertionError("Wavey present in no-mod classpath"); }
            catch (ClassNotFoundException absent) { }
        }
        try (HeadlessCapeClient client = HeadlessCapeClient.open(false)) {
            for (OitStage stage : Arrays.asList(null, OitStage.DEPTH_BOUNDS, OitStage.TRANSMITTANCE, OitStage.ACCUMULATE)) {
                AtomicBoolean valid = new AtomicBoolean(true);
                PreparedRenderType own = prepared(valid::get);
                PreparedRenderType foreign = prepared(null), unmarked = prepared(null);
                PreparedRenderType independent = prepared(() -> true);
                run(stage, List.of(own), List.of(own), null, null, false, "live-own");
                valid.set(false);
                run(stage, List.of(own), List.of(), null, null, false, "stale-own");
                run(stage, List.of(foreign), List.of(foreign), null, null, false, "foreign");
                run(stage, List.of(unmarked), List.of(unmarked), null, null, false, "unmarked");
                run(stage, List.of(own, foreign, independent), List.of(foreign, independent), null, null, false, "stale-first");
                run(stage, List.of(foreign, independent, own), List.of(foreign, independent), null, null, false, "stale-last");
                valid.set(true);
                run(stage, List.of(foreign, own, independent), List.of(foreign, independent), foreign, () -> valid.set(false), false, "intra-loop-retirement");
                valid.set(true);
                run(stage, List.of(own, foreign, independent), List.of(own, foreign, independent), foreign, () -> valid.set(false), false, "retirement-after-own");
                run(stage, List.of(independent, foreign), List.of(independent), independent, null, true, "own-exception");
                run(stage, List.of(foreign, independent), List.of(foreign), foreign, null, true, "foreign-exception");
                run(stage, List.of(unmarked), List.of(unmarked), unmarked, null, true, "unmarked-exception");
                valid.set(true);
                run(stage, List.of(own, foreign), List.of(own), null, null, false, "null-execute-info", foreign);
            }
        }
        check(controls == 48, "Incomplete native branch coverage: " + controls);
        System.out.println("ACTUAL_NORMAL_WOVEN_DRAW_CONTROLS_PASS count=" + controls + "; normal=12; oit=36; mode=" + (args.length == 0 ? "optional-present" : args[0]));
    }

    private static PreparedRenderType prepared(TextureUseValidity validity) throws Exception {
        PreparedRenderType prepared = allocate(PreparedRenderType.class);
        if (validity != null) ((CapturedCapePass.PreparedCarrier) (Object) prepared)
                .nclskins$setCapture(new CapturedCapePass(validity, false));
        return prepared;
    }

    private static void run(OitStage stage, List<PreparedRenderType> prepared, List<PreparedRenderType> wanted,
            PreparedRenderType trigger, Runnable action, boolean throwsExpected, String label,
            PreparedRenderType... missingInfo) throws Exception {
        expected.clear(); calls.clear(); expectedStage = stage;
        expectedPass = (RenderPass) Proxy.newProxyInstance(RenderPass.class.getClassLoader(), new Class<?>[]{RenderPass.class},
                (proxy, method, values) -> { throw new AssertionError("GPU RenderPass operation reached"); });
        retire = action;
        retireOn = trigger;
        failOn = throwsExpected ? trigger : null;
        failure = new IllegalStateException("draw-terminal-marker");
        StageBuffer buffer = allocate(StageBuffer.class);
        buffer.infos = new IdentityHashMap<>();
        List<StagedVertexBuffer.Draw> draws = new ArrayList<>();
        for (PreparedRenderType type : prepared) {
            StagedVertexBuffer.Draw draw = allocate(StagedVertexBuffer.Draw.class);
            StagedVertexBuffer.ExecuteInfo info = new StagedVertexBuffer.ExecuteInfo(null, null, null, 0, 0, 1, null);
            expected.put(type, info);
            buffer.infos.put(draw, Arrays.stream(missingInfo).anyMatch(candidate -> candidate == type) ? null : info);
            draws.add(draw);
        }
        Class<?> groupType = Class.forName("net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group");
        Constructor<?> constructor = groupType.getDeclaredConstructor(StagedVertexBuffer.class, boolean.class);
        constructor.setAccessible(true);
        Object group = constructor.newInstance(buffer, false);
        field(group, "draws", draws);
        field(group, "drawRenderTypes", prepared);
        OitDrawProbe renderer = new OitDrawProbe();
        field(renderer, RenderTypeFeatureRenderer.class, "groups", List.of(group));
        FeatureFrameContext context = new FeatureFrameContext(null, null, null, null, null, null, null, buffer);
        try {
            renderer.executeGroup(context, stage, expectedPass, 0, List.of(), false);
            check(!throwsExpected, "Native exception swallowed: " + label);
        } catch (RuntimeException actual) {
            check(throwsExpected && actual == failure, "Native exception identity changed: " + label);
        }
        check(calls.size() == wanted.size(), "Continuation count: " + label + " actual=" + calls.size() + " expected=" + wanted.size());
        for (int i = 0; i < wanted.size(); i++) check(calls.get(i) == wanted.get(i), "Draw order/identity: " + label);
        controls++;
        System.out.println("DRAW_CONTROL " + (stage == null ? "NORMAL" : stage.name()) + " " + label + " PASS");
    }

    public static final class StageBuffer extends StagedVertexBuffer {
        private Map<Draw, ExecuteInfo> infos;
        private StageBuffer() { super(() -> "not-executed", 1); }
        @Override public ExecuteInfo getExecuteInfo(Draw draw) {
            check(infos.containsKey(draw), "Native draw identity changed");
            return infos.get(draw);
        }
    }

    private static void field(Object target, String name, Object value) throws Exception {
        field(target, target.getClass(), name, value);
    }
    private static void field(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
