package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

public final class CpmPreviewResourceTransformProbe {
    public static void main(String[] args) throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader absent = new ClassLoader(original) {
            @Override public java.net.URL getResource(String name) { return name.startsWith("com/tom/") ? null : super.getResource(name); }
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.tom.")) throw new AssertionError("Absent policy eagerly loaded native class");
                return super.loadClass(name, resolve);
            }
        };
        Thread.currentThread().setContextClassLoader(absent);
        try {
            var plugin = new com.naocraftlab.skins.compat.cpm.CpmMixinPlugin();
            for (String mixin : List.of("CpmAnimatedTextureMixin", "CpmDefinitionResourceMixin"))
                if (plugin.shouldApplyMixin("unused", mixin)) throw new AssertionError("Absent native policy activated");
        } finally { Thread.currentThread().setContextClassLoader(original); }
        boolean server = args.length > 1 && args[1].equals("server");
        MixinBootstrap.init();
        var phase = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        phase.setAccessible(true);
        phase.invoke(null, MixinEnvironment.Phase.DEFAULT);
        var environment = MixinEnvironment.getDefaultEnvironment();
        environment.setSide(server ? MixinEnvironment.Side.SERVER : MixinEnvironment.Side.CLIENT);
        var constructor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
        com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
        Mixins.addConfiguration("preview-resource-binding.json");
        var transformer = (IMixinTransformer) environment.getActiveTransformer();
        for (String owner : List.of("parts.ModelPartAnimatedTexture", "definition.ModelDefinition")) {
            String name = "com.tom.cpm.shared." + owner;
            String entry = name.replace('.', '/') + ".class";
            byte[] before;
            try (var input = ClassLoader.getSystemResourceAsStream(entry)) { before = input.readAllBytes(); }
            byte[] after = transformer.transformClass(environment, name, before);
            if (server) {
                if (!Arrays.equals(before, after)) throw new AssertionError("Client resource policy transformed server class");
            } else {
                var node = new ClassNode();
                new ClassReader(after).accept(node, 0);
                var method = node.methods.stream().filter(candidate -> candidate.name.equals(owner.startsWith("parts.") ? "apply" : "resolveAll")
                        && candidate.desc.equals(owner.startsWith("parts.") ? "(Lcom/tom/cpm/shared/definition/ModelDefinition;)V" : "()V")).findFirst().orElseThrow();
                long calls = java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                        .filter(instruction -> instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                                && call.name.contains("nclskins$selfResources")).count();
                if (calls != 1) throw new AssertionError("Resource call-site cardinality: " + name + " " + calls);
                Path output = Path.of(args[0]).resolve(entry);
                Files.createDirectories(output.getParent());
                Files.write(output, after);
            }
            System.out.println("PASS exact resource transform " + (server ? "server " : "client ") + name);
        }
    }
}
