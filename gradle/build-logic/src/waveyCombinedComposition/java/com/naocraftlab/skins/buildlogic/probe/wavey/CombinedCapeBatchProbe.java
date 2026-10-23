package com.naocraftlab.skins.buildlogic.probe.wavey;

import dev.tr7zw.waveycapes.support.ModSupport;
import dev.tr7zw.waveycapes.support.SupportManager;
import dev.tr7zw.waveycapes.versionless.ModBase;
import dev.tr7zw.waveycapes.versionless.config.Config;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

public final class CombinedCapeBatchProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("COMBINED_BATCH_MANIFEST_REQUIRED");
        var manifest = CombinedBatchCases.readManifest(Path.of(args[0]));
        if (manifest.cases().size() != 216) throw new IllegalArgumentException("COMBINED_BATCH_CASE_COUNT");
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        CombinedBatchCases.execute(manifest, row -> CombinedCapeProbe.runCase(row.arguments()),
                CombinedCapeBatchProbe::cleanupSteps, System.out::println);
    }

    static List<CaseLifecycle.Action> cleanupSteps(CombinedBatchCases.Case row) throws Exception {
        var guard = new NativeGuard();
        return List.of(guard::verifyClosed, CombinedCapeHost::reset, CombinedCapeStaged::reset, guard::restoreConfig, guard::verifyReset);
    }

    static final class NativeGuard {
        private final Field singleton;
        private final Object previous;
        private final List<ModSupport> supports;
        private final Config config;

        NativeGuard() throws Exception {
            singleton = Minecraft.class.getDeclaredField("instance");
            singleton.setAccessible(true);
            previous = singleton.get(null);
            supports = new ArrayList<>(SupportManager.getSupportedMods());
            config = ModBase.config;
        }

        private void verifyReferences() throws Exception {
            CombinedBatchCases.cleanupAll(List.of(
                    () -> check(singleton.get(null) == previous, "SYNTHETIC_SINGLETON_LEAK"),
                    () -> {
                        var current = new ArrayList<>(SupportManager.getSupportedMods());
                        check(current.size() == supports.size(), "SUPPORT_REGISTRATION_LEAK");
                        for (int i = 0; i < supports.size(); i++) check(current.get(i) == supports.get(i), "SUPPORT_REGISTRATION_LEAK");
                    }));
        }

        void verifyClosed() throws Exception {
            CombinedBatchCases.cleanupAll(List.of(this::verifyReferences,
                    () -> check(CombinedCapeHost.retireAtDraw == null, "PENDING_RETIREMENT_REFERENCE")));
        }

        void restoreConfig() { ModBase.config = config; }

        void verifyReset() throws Exception {
            CombinedBatchCases.cleanupAll(List.of(this::verifyReferences,
                    () -> check(CombinedCapeHost.acquired.isEmpty() && CombinedCapeHost.prepared.isEmpty()
                            && CombinedCapeHost.drawn.isEmpty() && CombinedCapeHost.infos.isEmpty()
                            && CombinedCapeHost.renderers.isEmpty() && CombinedCapeHost.actors.isEmpty()
                            && CombinedCapeHost.geometry == 0 && CombinedCapeHost.expectedActor == null
                            && CombinedCapeHost.expectedRenderer == null && CombinedCapeHost.currentConsumer == null
                            && CombinedCapeHost.expectedStage == null && CombinedCapeHost.expectedPass == null
                            && CombinedCapeHost.geometryFailure == null && CombinedCapeHost.drawFailure == null
                            && CombinedCapeHost.retireAtDraw == null && CombinedCapeHost.expectedLight == 31
                            && CombinedCapeHost.expectedDelta == 0.25F && CombinedCapeStaged.empty(), "CASE_REFERENCE_COUNTER_RESET"),
                    () -> check(ModBase.config == config, "CONFIG_REFERENCE_NOT_RESTORED")));
        }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
