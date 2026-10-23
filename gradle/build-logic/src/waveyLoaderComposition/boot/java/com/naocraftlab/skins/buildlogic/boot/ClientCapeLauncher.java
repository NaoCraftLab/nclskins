package com.naocraftlab.skins.buildlogic.boot;

import java.nio.file.*;
import java.util.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.startup.StartupArgs;

public final class ClientCapeLauncher {
    public static void main(String[] args) throws Exception {
        var previous = Thread.currentThread().getContextClassLoader();
        if (CompositionAgent.instrumentation == null) throw new AssertionError("NO_INSTRUMENTATION");
        for (var type : CompositionAgent.instrumentation.getAllLoadedClasses()) {
            if (type.getName().startsWith("net.minecraft.") || type.getName().startsWith("net.neoforged.neoforge.")
                    || type.getName().startsWith("com.naocraftlab.skins.runtime.")) throw new AssertionError("GAME_PRELOADED");
        }
        var lines = Files.readAllLines(Path.of(args[0])).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        if (lines.isEmpty() || !lines.getFirst().equals(ClientCapeLauncher.class.getName())) throw new AssertionError("PROVIDER_MAIN_CLASS");
        var providerArgs = lines.subList(1, lines.size()).toArray(String[]::new);
        var startup = new StartupArgs(Path.of(args[1]), true, Dist.CLIENT, false, providerArgs, Set.of(), List.of(), previous);
        try (var loader = FMLLoader.create(CompositionAgent.instrumentation, startup)) {
            if (loader.getDist() != Dist.CLIENT || loader.isProduction() || loader.getLoadingModList().hasErrors())
                throw new AssertionError("CLIENT_DISCOVERY");
            if (loader.getCurrentClassLoader() != Thread.currentThread().getContextClassLoader()) throw new AssertionError("TCCL");
            System.out.println("CANONICAL_CLIENT_CONTEXT_PASS");
            var game = Class.forName("com.naocraftlab.skins.buildlogic.probe.wavey.ClientCapeBatch", true, loader.getCurrentClassLoader());
            if (game.getClassLoader() != loader.getCurrentClassLoader()) throw new AssertionError("GAME_ORIGIN");
            try { game.getMethod("run", Path.class).invoke(null, Path.of(args[1]).resolve("cases")); }
            catch (java.lang.reflect.InvocationTargetException failure) {
                if (failure.getCause() instanceof Error error) throw error;
                if (failure.getCause() instanceof Exception exception) throw exception;
                throw failure;
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            System.out.println("CANONICAL_CLIENT_CONTEXT_RESTORED");
        }
        if (FMLLoader.getCurrentOrNull() != null) throw new AssertionError("LOADER_STILL_CURRENT");
        System.out.println("CANONICAL_CLIENT_CLOSED_PASS");
    }
}
