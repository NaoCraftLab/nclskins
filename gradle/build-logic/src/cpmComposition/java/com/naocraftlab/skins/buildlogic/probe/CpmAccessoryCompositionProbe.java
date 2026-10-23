package com.naocraftlab.skins.buildlogic.probe;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

public final class CpmAccessoryCompositionProbe {
    public static void main(String[] args) throws Exception {
        MixinBootstrap.init();
        var phase = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        phase.setAccessible(true);
        phase.invoke(null, MixinEnvironment.Phase.DEFAULT);
        var environment = MixinEnvironment.getDefaultEnvironment();
        environment.setSide(MixinEnvironment.Side.CLIENT);
        var constructor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
        MixinExtrasBootstrap.init();
        System.out.println("Mixin: " + MixinBootstrap.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("MixinExtras: " + MixinExtrasBootstrap.class.getProtectionDomain().getCodeSource().getLocation());
        var production = com.naocraftlab.skins.compat.cpm.CpmMixinPlugin.class.getProtectionDomain().getCodeSource().getLocation();
        System.out.println("Production plugin: " + production);
        String artifact = System.getProperty("nclskins.cpm.artifact");
        if (artifact != null && !Path.of(production.toURI()).equals(Path.of(artifact)))
            throw new AssertionError("Packaged artifact did not win classpath");
        System.out.println("Production config: " + ClassLoader.getSystemResource("nclskins.cpm.mixins.json"));
        Mixins.addConfiguration("accessory-upstream.json");
        Mixins.addConfiguration("nclskins.cpm.mixins.json");
        if (args[1].equals("loader-compat")) for (var config : Mixins.getConfigs()) {
            boolean upstream = config.getName().equals("accessory-upstream.json");
            config.getConfig().decorate(FabricUtil.KEY_MOD_ID, upstream ? "cpm" : "nclskins");
            config.getConfig().decorate("fabric-compat", upstream ? 9002 : 17004);
        }
        var transformer = (IMixinTransformer) environment.getActiveTransformer();
        List<String> hosts = new ArrayList<>(List.of("net.minecraft.client.renderer.entity.layers.CapeLayer"));
        for (String name : hosts) {
            byte[] original;
            try (var input = ClassLoader.getSystemResourceAsStream(name.replace('.', '/') + ".class")) { original = input.readAllBytes(); }
            byte[] transformed = transformer.transformClass(environment, name, original);
            Path output = Path.of(args[0], name.replace('.', '/') + ".class");
            Files.createDirectories(output.getParent());
            Files.write(output, transformed);
            ClassNode node = new ClassNode();
            new ClassReader(transformed).accept(node, 0);
            if (name.endsWith("CapeLayer")) {
                long handlers = node.methods.stream().filter(m -> m.name.endsWith("$nclskins$accessoryCape")).count();
                long calls = node.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
                        .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                        .filter(c -> c.name.endsWith("$nclskins$accessoryCape")).count();
                if (handlers != 1 || calls != 1) throw new AssertionError("Exact cape transformation cardinality " + handlers + "/" + calls);
            }
            for (var method : node.methods) for (var instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode type && type.desc.startsWith("com/llamalad7/mixinextras/sugar/impl/ref/generated/")) {
                    byte[] bytes = com.llamalad7.mixinextras.utils.ClassGenUtils.getDefinitions().get(type.desc.replace('/', '.'));
                    if (bytes == null) bytes = com.llamalad7.mixinextras.utils.ClassGenUtils.getDefinitions().get(type.desc);
                    if (bytes == null) throw new AssertionError("Missing generated sugar class");
                    Path path = Path.of(args[0], type.desc + ".class");
                    Files.createDirectories(path.getParent()); Files.write(path, bytes);
                }
            }
            System.out.println("Actual upstream + production accessory host transformed: " + name);
        }
    }
}
