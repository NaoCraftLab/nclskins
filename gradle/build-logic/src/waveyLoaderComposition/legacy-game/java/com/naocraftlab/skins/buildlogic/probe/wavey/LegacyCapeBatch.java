package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import dev.tr7zw.waveycapes.support.SupportManager;
import dev.tr7zw.waveycapes.versionless.ModBase;
import dev.tr7zw.waveycapes.versionless.config.Config;
import dev.tr7zw.waveycapes.versionless.CapeStyle;
import org.spongepowered.asm.mixin.MixinEnvironment;
import static com.naocraftlab.skins.buildlogic.probe.wavey.ImmediateCapeHost.*;

public final class LegacyCapeBatch {
    private static int cases;
    private static void runCase(String name, CaseLifecycle.Action action) throws Exception {
        var config = ModBase.config;
        var supports = new HashSet<>(SupportManager.getSupportedMods());
        Object singleton = field(null, Minecraft.class, "instance");
        String noSupport = System.getProperty("nclskins.wavey.noSupport");
        Object shader = field(null, com.naocraftlab.skins.compat.client.cape.CapeTranslucentFoilMaterial.class, "shader");
        @SuppressWarnings("unchecked")
        Map<Object, Object> types = (Map<Object, Object>) field(null, com.naocraftlab.skins.compat.client.cape.CapeTranslucentFoilMaterial.class, "TYPES");
        Map<Object, Object> previousTypes = new LinkedHashMap<>(types);
        ModBase.config = new Config();
        reset();
        CaseLifecycle.execute(() -> { action.run(); cases++; System.out.println("CANONICAL_CASE_PASS " + name); }, () -> {
            try {
                check(field(null, Minecraft.class, "instance") == singleton, "SINGLETON_LEAK");
                check(SupportManager.getSupportedMods().equals(supports), "REGISTRATION_LEAK");
                check(ImmediateBuffers.pending() == 0, "BUFFER_LEAK");
            } finally {
                field(null, Minecraft.class, "instance", singleton);
                SupportManager.getSupportedMods().removeIf(s -> !supports.contains(s));
                supports.forEach(s -> SupportManager.getSupportedMods().add(s));
                ModBase.config = config;
                field(null, com.naocraftlab.skins.compat.client.cape.CapeTranslucentFoilMaterial.class, "shader", shader);
                types.clear(); types.putAll(previousTypes);
                if (noSupport == null) System.clearProperty("nclskins.wavey.noSupport"); else System.setProperty("nclskins.wavey.noSupport", noSupport);
                reset();
            }
            check(expectedActor == null && expectedPose == null && lastReturned == null && drawing == null
                    && acquired.isEmpty() && drawn.isEmpty() && consumers.isEmpty() && renderers.isEmpty(), "CASE_REFERENCE_RESET");
            System.out.println("CANONICAL_CASE_RESET_PASS " + name);
        });
    }
    public static void run(Path root) throws Exception {
        cases = 0;
        var environment = MixinEnvironment.getCurrentEnvironment();
        check(environment.getSide() == MixinEnvironment.Side.CLIENT && environment.getPhase() == MixinEnvironment.Phase.DEFAULT, "CLIENT_MIXIN_PHASE");
        check(new HashSet<>(environment.getMixinConfigs()).containsAll(List.of(System.getProperty("nclskins.wavey.configs").split(","))), "NORMAL_CONFIGS");
        check(com.naocraftlab.skins.runtime.WaveyProducerFixture.class.getModule() == com.naocraftlab.skins.runtime.CapeProviderCoordinator.class.getModule(), "RUNTIME_FIXTURE_MODULE");
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        var originalConfig = ModBase.config;
        try {
        ModBase.config = new Config();
        if (System.getProperty("nclskins.wavey.definitionProfile").equals("guards")) {
            runCase("guard-partial-registration", () -> CapeRegistrationFailureProbe.main(new String[0]));
            runCase("guard-unsupported-public-abi", () -> CapeUnsupportedAbiProbe.main(new String[0]));
            runCase("guard-callback-linkage", () -> ImmediateGuardProbe.callback(root.resolve("callback")));
            runCase("guard-closed-legacy", ImmediateGuardProbe::closed);
            return;
        }
        for (CapeStyle style : CapeStyle.values()) {
            if (!List.of("SMOOTH", "BLOCKY").contains(style.name())) continue;
            for (String context : List.of("world", "inspection", "menu")) {
                for (String scenario : List.of("live", "stale-before", "stale-during", "stale-after", "foreign-first", "foreign-last", "geometry-abort")) {
                    String name = style + "-" + context + "-" + scenario;
                    runCase(name, () -> { ModBase.config.capeStyle = style; ImmediateCapeProbe.runCase(new String[]{root.resolve(name).toString(), context, scenario}); });
                }
                runCase(style + "-" + context + "-normal-after-abort", () -> { ModBase.config.capeStyle = style; check(ModBase.config.capeStyle == style, "RECOVERY_STYLE"); ImmediateCapeProbe.runCase(new String[]{root.resolve(style + "-" + context + "-after").toString(), context, "live"}); });
            }
        }
        runCase("unmarked-geometry-abort", () -> {
            System.setProperty("nclskins.wavey.noSupport", "true");
            ImmediateCapeProbe.runCase(new String[]{root.resolve("unmarked-abort").toString(), "world", "geometry-abort"});
        });
        runCase("fresh-normal-after-unmarked-abort", () -> ImmediateCapeProbe.runCase(new String[]{root.resolve("after-unmarked-abort").toString(), "world", "live"}));
        runCase("guard-no-support-sensitivity", () -> {
            System.setProperty("nclskins.wavey.noSupport", "true");
            try {
                ImmediateCapeProbe.runCase(new String[]{root.resolve("no-support").toString(), "world", "live"});
                throw new IllegalStateException("NO_SUPPORT_ACCEPTED");
            } catch (AssertionError expected) {
                check(expected.getMessage().startsWith("IMMEDIATE_OWN_FOIL_MISSING"), "NO_SUPPORT_NOT_BEHAVIORAL");
            }
        });
        runCase("public-legacy-preview", () -> ImmediatePublicPreviewProbe.runCase(new String[]{root.resolve("public-preview").toString()}));
        runCase("guard-bridge-contract", () -> ImmediateGuardProbe.bridge(root.resolve("bridge")));
        System.out.println("CANONICAL_CLIENT_BATCH_PASS cases=" + cases + "; immediate native lookup/world source; inspection/menu manual material controls; GPU/advanced preview NOT_RUN");
        } finally { ModBase.config = originalConfig; reset(); }
    }
}
