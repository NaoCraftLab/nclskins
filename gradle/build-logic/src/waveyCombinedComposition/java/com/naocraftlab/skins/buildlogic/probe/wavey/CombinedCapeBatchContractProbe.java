package com.naocraftlab.skins.buildlogic.probe.wavey;

import dev.tr7zw.waveycapes.support.ModSupport;
import dev.tr7zw.waveycapes.support.SupportManager;
import dev.tr7zw.waveycapes.versionless.ModBase;
import dev.tr7zw.waveycapes.versionless.config.Config;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import static com.naocraftlab.skins.buildlogic.probe.wavey.CombinedBatchCases.*;

public final class CombinedCapeBatchContractProbe {
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("CONTRACT_STORAGE_ROOT_REQUIRED");
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        Path root = Path.of(args[0]).toAbsolutePath();
        Field singleton = Minecraft.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        Object originalSingleton = singleton.get(null);
        var originalSupports = SupportManager.mods;
        Config originalConfig = ModBase.config;
        try {
            Object sentinel = CombinedCapeProbe.allocate(Minecraft.class);
            singleton.set(null, sentinel);
            ModSupport first = support(), second = support();
            SupportManager.mods = new LinkedHashSet<>(List.of(first, second));
            Config config = new Config();
            ModBase.config = config;
            reset();
            actualContinuation(root, sentinel, first, second, config);
            for (String fault : List.of("singleton", "support-add", "support-remove", "support-reorder", "support-replace", "pending-retire")) {
                leak(root, fault, singleton, sentinel, first, second, config);
            }
            for (Field field : CombinedCapeHost.class.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                dirtyHost(root, field);
            }
            dirtyStaged(root);
            configRestore(root);
            cleanupFailures(root);
            snapshotFailure(root);
            System.out.println("COMBINED_BATCH_NATIVE_CONTRACT_PASS checks=" + checks
                    + "; synthetic host/CPU terminals; no game/GPU acceptance");
        } finally {
            singleton.set(null, originalSingleton);
            SupportManager.mods = originalSupports;
            ModBase.config = originalConfig;
            reset();
        }
    }

    private static ModSupport support() { return new ForeignSupport(new Object()); }

    private static final class ForeignSupport implements ModSupport {
        private final Object key;
        private ForeignSupport(Object key) { this.key = key; }
        public boolean shouldBeUsed(dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper actor) { return false; }
        public dev.tr7zw.waveycapes.render.CapeRenderer getRenderer() { return null; }
        public boolean blockFeatureRenderer(Object actor) { return false; }
        @Override public boolean equals(Object other) { return other instanceof ForeignSupport support && key == support.key; }
        @Override public int hashCode() { return System.identityHashCode(key); }
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static Throwable failure(CaseLifecycle.Action action) {
        try { action.run(); }
        catch (Exception | Error failure) { return failure; }
        throw new AssertionError("FAULT_NOT_DETECTED");
    }

    private static Case row(Path root, String name, String scenario) {
        return new Case(name, Set.of("no-support", "wrong-material").contains(scenario) ? 1 : 0,
                root.resolve(name), "world", scenario, "foreign-first", "normal", "SMOOTH");
    }

    private static Manifest roster(Path root, String name) {
        return new Manifest("a".repeat(64), List.of(row(root, name, "live"), row(root, name + "-next", "live")));
    }

    private static void rejected(List<String> events, String name) {
        check(events.stream().noneMatch(s -> s.startsWith("COMBINED_BATCH_RESET") || s.startsWith("COMBINED_BATCH_COMPLETE")), "RESET_OR_COMPLETE_AFTER_FAULT " + name);
        check(events.stream().filter(s -> s.startsWith("COMBINED_BATCH_BEGIN")).count() == 1, "NEXT_CASE_AFTER_FAULT " + name);
    }

    private static void reset() {
        CombinedCapeHost.reset();
        CombinedCapeStaged.reset();
    }

    private static void actualContinuation(Path root, Object singleton, ModSupport first, ModSupport second, Config config) throws Exception {
        List<Case> rows = new ArrayList<>();
        for (String scenario : List.of("live", "own-exception", "foreign-exception", "draw-exception", "no-support", "wrong-material")) {
            rows.add(row(root, "actual-" + scenario, scenario));
            rows.add(row(root, "actual-" + scenario + "-fresh", "live"));
        }
        List<String> events = new ArrayList<>();
        execute(new Manifest("a".repeat(64), rows), c -> CombinedCapeProbe.runCase(c.arguments()),
                CombinedCapeBatchProbe::cleanupSteps, events::add);
        check(events.get(events.size() - 1).equals("COMBINED_BATCH_COMPLETE\t12\t10\t2"), "ACTUAL_CONTINUATION");
        check(Minecraft.getInstance() == singleton && ModBase.config == config, "FOREIGN_REFERENCE_NOT_RESTORED");
        List<ModSupport> supports = new ArrayList<>(SupportManager.getSupportedMods());
        check(supports.size() == 2 && supports.get(0) == first && supports.get(1) == second, "FOREIGN_SUPPORT_SENTINELS_LOST");
    }

    private static void leak(Path root, String fault, Field singleton, Object sentinel, ModSupport first, ModSupport second, Config config) throws Exception {
        List<String> events = new ArrayList<>();
        try {
            Throwable error = failure(() -> execute(roster(root, fault), c -> {
                switch (fault) {
                    case "singleton" -> singleton.set(null, CombinedCapeProbe.allocate(Minecraft.class));
                    case "support-add" -> SupportManager.mods.add(support());
                    case "support-remove" -> SupportManager.mods.remove(first);
                    case "support-reorder" -> { SupportManager.mods.remove(first); SupportManager.mods.add(first); }
                    case "support-replace" -> SupportManager.mods = new LinkedHashSet<>(List.of(new ForeignSupport(((ForeignSupport)first).key), second));
                    case "pending-retire" -> CombinedCapeHost.retireAtDraw = () -> { throw new AssertionError("RETIREMENT_MUST_NOT_RUN"); };
                    default -> throw new AssertionError(fault);
                }
                ModBase.config = new Config();
            }, CombinedCapeBatchProbe::cleanupSteps, events::add));
            String expected = fault.equals("singleton") ? "SYNTHETIC_SINGLETON_LEAK"
                    : fault.equals("pending-retire") ? "PENDING_RETIREMENT_REFERENCE" : "SUPPORT_REGISTRATION_LEAK";
            check(error instanceof AssertionError && expected.equals(error.getMessage()), "EXACT_NATIVE_GUARD " + fault);
            rejected(events, fault);
            check(ModBase.config == config && CombinedCapeHost.retireAtDraw == null, "LATER_CLEANUP_NOT_RUN " + fault);
            if (fault.equals("support-remove")) check(!SupportManager.mods.contains(first), "REMOVED_SUPPORT_RESURRECTED");
            if (fault.equals("support-add")) check(SupportManager.mods.size() == 3, "ADDED_SUPPORT_LEAK_ERASED");
            if (fault.equals("support-reorder")) check(new ArrayList<>(SupportManager.mods).get(0) == second, "SUPPORT_ORDER_LEAK_ERASED");
            if (fault.equals("singleton")) check(singleton.get(null) != sentinel, "SINGLETON_LEAK_ERASED");
        } finally {
            singleton.set(null, sentinel);
            SupportManager.mods = new LinkedHashSet<>(List.of(first, second));
            ModBase.config = config;
            reset();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void dirtyHost(Path root, Field field) throws Exception {
        String name = "host-" + field.getName();
        List<String> events = new ArrayList<>();
        try {
            Throwable error = failure(() -> execute(roster(root, name), c -> {}, c -> {
                List<CaseLifecycle.Action> steps = new ArrayList<>(CombinedCapeBatchProbe.cleanupSteps(c));
                CaseLifecycle.Action original = steps.get(1);
                steps.set(1, () -> {
                    original.run();
                    if (List.class.isAssignableFrom(field.getType())) ((List)field.get(null)).add(null);
                    else if (Set.class.isAssignableFrom(field.getType())) ((Set)field.get(null)).add(null);
                    else if (field.getType() == int.class) field.setInt(null, field.getName().equals("expectedLight") ? 32 : 1);
                    else if (field.getType() == float.class) field.setFloat(null, 0.5F);
                    else if (field.getType() == RuntimeException.class) field.set(null, new IllegalStateException("synthetic"));
                    else if (field.getType() == Runnable.class) field.set(null, (Runnable)() -> {});
                    else if (field.getType().isEnum()) field.set(null, field.getType().getEnumConstants()[0]);
                    else if (field.getType().isInterface()) field.set(null, java.lang.reflect.Proxy.newProxyInstance(field.getType().getClassLoader(), new Class<?>[]{field.getType()}, (p,m,a) -> null));
                    else field.set(null, CombinedCapeProbe.allocate(field.getType()));
                });
                return steps;
            }, events::add));
            check(error instanceof AssertionError && "CASE_REFERENCE_COUNTER_RESET".equals(error.getMessage()), "DIRTY_HOST_FIELD_NOT_DETECTED " + field.getName());
            rejected(events, name);
        } finally { reset(); }
    }

    private static void dirtyStaged(Path root) throws Exception {
        List<String> events = new ArrayList<>();
        try {
            Throwable error = failure(() -> execute(roster(root, "staged"), c -> {}, c -> {
                List<CaseLifecycle.Action> steps = new ArrayList<>(CombinedCapeBatchProbe.cleanupSteps(c));
                CaseLifecycle.Action original = steps.get(2);
                steps.set(2, () -> {
                    original.run();
                    var field = CombinedCapeStaged.class.getDeclaredField("counts"); field.setAccessible(true);
                    @SuppressWarnings("unchecked") Map<Object,Integer> counts = (Map<Object,Integer>) field.get(null);
                    counts.put(null, 1);
                });
                return steps;
            }, events::add));
            check(error instanceof AssertionError && "CASE_REFERENCE_COUNTER_RESET".equals(error.getMessage()), "DIRTY_STAGED_NOT_DETECTED");
            rejected(events, "staged");
        } finally { reset(); }
    }

    private static void configRestore(Path root) throws Exception {
        Config original = ModBase.config;
        List<String> events = new ArrayList<>();
        try {
            Throwable error = failure(() -> execute(roster(root, "config"), c -> ModBase.config = new Config(), c -> {
                List<CaseLifecycle.Action> steps = new ArrayList<>(CombinedCapeBatchProbe.cleanupSteps(c));
                steps.set(3, () -> {});
                return steps;
            }, events::add));
            check(error instanceof AssertionError && "CONFIG_REFERENCE_NOT_RESTORED".equals(error.getMessage()), "CONFIG_IDENTITY_NOT_CHECKED");
            rejected(events, "config");
        } finally { ModBase.config = original; reset(); }
    }

    private static void cleanupFailures(Path root) throws Exception {
        for (int fault = 0; fault < 5; fault++) {
            int failed = fault;
            Error marker = new LinkageError("cleanup-stage-" + failed);
            List<Integer> visited = new ArrayList<>();
            List<String> events = new ArrayList<>();
            Config original = ModBase.config;
            Throwable got = failure(() -> execute(roster(root, "step-" + failed), c -> {
                CombinedCapeHost.geometry = 19; ModBase.config = new Config();
            }, c -> {
                List<CaseLifecycle.Action> actual = CombinedCapeBatchProbe.cleanupSteps(c);
                List<CaseLifecycle.Action> injected = new ArrayList<>();
                for (int i = 0; i < actual.size(); i++) {
                    int index = i;
                    injected.add(() -> { visited.add(index); actual.get(index).run(); if (index == failed) throw marker; });
                }
                return injected;
            }, events::add));
            check(got == marker, "CLEANUP_PRIMARY_REPLACED " + failed);
            check(visited.equals(List.of(0,1,2,3,4)), "NATIVE_LATER_CLEANUP_SKIPPED " + failed);
            check(ModBase.config == original && CombinedCapeHost.geometry == 0, "NATIVE_RESET_SKIPPED " + failed);
            rejected(events, "step-" + failed);
        }
    }

    private static void snapshotFailure(Path root) throws Exception {
        Error marker = new LinkageError("snapshot");
        List<String> events = new ArrayList<>();
        Throwable got = failure(() -> execute(roster(root, "snapshot"), c -> { throw new AssertionError("BODY_AFTER_SNAPSHOT_FAILURE"); }, c -> {
            CombinedCapeBatchProbe.cleanupSteps(c);
            throw marker;
        }, events::add));
        check(got == marker, "SNAPSHOT_REPLACED");
        rejected(events, "snapshot");
    }
}
