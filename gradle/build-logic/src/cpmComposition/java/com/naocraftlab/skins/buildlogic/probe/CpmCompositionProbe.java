package com.naocraftlab.skins.buildlogic.probe;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import java.io.InputStream;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

public final class CpmCompositionProbe {
    public static void main(String[] args) throws Exception {
        MixinBootstrap.init();
        var phase = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        phase.setAccessible(true);
        phase.invoke(null, MixinEnvironment.Phase.DEFAULT);
        MixinEnvironment environment = MixinEnvironment.getDefaultEnvironment();
        environment.setSide(MixinEnvironment.Side.CLIENT);
        var constructor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
        MixinExtrasBootstrap.init();
        Mixins.addConfiguration("nclskins.cpm.mixins.json");
        IMixinTransformer transformer = (IMixinTransformer) environment.getActiveTransformer();
        if (args[0].startsWith("material")) {
            Mixins.addConfiguration("material-capture.json");
            transform(transformer, environment, "net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer", List.of());
            return;
        }
        if (args[0].startsWith("raw-")) {
            String family = args[0].substring(4);
            Mixins.addConfiguration("raw-capture.json");
            transform(transformer, environment, "net.minecraft.client.renderer.texture.HttpTexture", List.of("nclskins$raw"));
            return;
        }
        if (args[0].equals("absent")) {
            if (ClassLoader.getSystemResource("com/tom/cpm/client/PlayerProfile.class") != null) throw new IllegalStateException("CPM present in absence probe");
            transform(transformer, environment, CpmCompositionProbe.class.getName(), List.of());
            System.out.println("Optional CPM absence: configuration and plugin loaded without CPM classes");
            return;
        }
        transform(transformer, environment, "com.tom.cpm.shared.config.Player", List.of("nclskins$privilege", "nclskins$publication", "nclskins$cleanup", "nclskins$events", "nclskins$configurationChanged", "nclskins$accessories"));
        transform(transformer, environment, "com.tom.cpm.client.PlayerProfile", List.of("nclskins$textures", "nclskins$variant", "nclskins$subject"));
        transform(transformer, environment, "com.tom.cpm.shared.definition.ModelDefinitionLoader", List.of("nclskins$tag", "nclskins$owned", "nclskins$state", "nclskins$decode", "nclskins$configurationChanged"));
        transform(transformer, environment, "com.tom.cpm.shared.model.render.ModelRenderManager", List.of("nclskins$bind", "nclskins$unbind", "nclskins$accessoryMaterial"));
        transform(transformer, environment, "com.tom.cpm.shared.animation.AnimationEngine", List.of("nclskins$prepareTime", "nclskins$animationTime"));
        transform(transformer, environment, "com.tom.cpm.shared.model.RenderManager", List.of("nclskins$accessoryWings", "nclskins$previewStatus"));
        String renderer = ClassLoader.getSystemResource("com/tom/cpm/client/PlayerRenderManager$RedirectModelRendererBase.class") != null
                ? "com.tom.cpm.client.PlayerRenderManager$RedirectModelRendererBase"
                : "com.tom.cpm.client.PlayerRenderManager$RedirectModelRenderer";
        transform(transformer, environment, renderer, List.of("nclskins$accessoryPart"));
        System.out.println("Exact CPM Mixin application passed");
    }

    private static void transform(IMixinTransformer transformer, MixinEnvironment environment, String name, List<String> handlers) throws Exception {
        try (InputStream in = ClassLoader.getSystemResourceAsStream(name.replace('.', '/') + ".class")) {
            if (in == null) throw new IllegalStateException("Missing pinned input " + name);
            byte[] bytes = in.readAllBytes();
            if (name.equals("com.tom.cpm.shared.model.RenderManager")) {
                ClassNode input = new ClassNode();
                new ClassReader(bytes).accept(input, 0);
                var status = input.methods.stream().filter(method -> method.name.equals("getStatus")
                        && method.desc.equals("(Ljava/lang/Object;Ljava/lang/String;)Lcom/tom/cpl/text/FormatText;")).findFirst().orElseThrow();
                long calls = java.util.Arrays.stream(status.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
                        .map(MethodInsnNode.class::cast).filter(call -> call.getOpcode() == org.objectweb.asm.Opcodes.INVOKEVIRTUAL
                                && call.owner.equals("com/tom/cpm/shared/definition/ModelDefinition") && call.name.equals("getStatus")
                                && call.desc.equals("()Lcom/tom/cpl/text/FormatText;")).count();
                if (calls != 1) throw new IllegalStateException("Expected exactly one upstream preview status operation");
            }
            if (name.equals("net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer")) {
                ClassNode input = new ClassNode();
                new ClassReader(bytes).accept(input, 0);
                ClassNode upstream = new HeadlessMixinService().getClassNode("com.tom.cpm.mixin.render.EquipmentLayerRendererMixin");
                boolean glint = upstream.methods.stream().anyMatch(method -> method.name.equals("grabTextureGlint"));
                for (String selector : glint ? List.of("armorCutoutNoCull", "armorCutoutNoCullGlint") : List.of("armorCutoutNoCull")) {
                    long count = input.methods.stream().flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                            .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                            .filter(call -> call.owner.equals("net/minecraft/client/renderer/rendertype/RenderTypes") && call.name.equals(selector)
                                    && call.desc.equals("(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;")).count();
                    if (count != 1) throw new IllegalStateException("Expected one native material anchor: " + selector + "; found " + count);
                }
            }
            byte[] result = transformer.transformClass(environment, name, bytes);
            java.nio.file.Path output = java.nio.file.Path.of(System.getProperty("nclskins.cpm.transformed"), name.replace('.', '/') + ".class");
            java.nio.file.Files.createDirectories(output.getParent());
            java.nio.file.Files.write(output, result);
            ClassNode node = new ClassNode();
            new ClassReader(result).accept(node, 0);
            for (var method : node.methods) for (var instruction : method.instructions.toArray()) {
                if (instruction instanceof org.objectweb.asm.tree.TypeInsnNode type
                        && type.desc.startsWith("com/llamalad7/mixinextras/sugar/impl/ref/generated/")) {
                    byte[] generated = com.llamalad7.mixinextras.utils.ClassGenUtils.getDefinitions().get(type.desc.replace('/', '.'));
                    if (generated == null) generated = com.llamalad7.mixinextras.utils.ClassGenUtils.getDefinitions().get(type.desc);
                    if (generated == null) throw new IllegalStateException("Missing generated sugar class " + type.desc);
                    var path = java.nio.file.Path.of(System.getProperty("nclskins.cpm.transformed"), type.desc + ".class");
                    java.nio.file.Files.createDirectories(path.getParent());
                    java.nio.file.Files.write(path, generated);
                }
            }
            if (name.equals("com.tom.cpm.shared.definition.ModelDefinitionLoader")
                    && (!node.interfaces.contains("com/naocraftlab/skins/compat/cpm/CpmModelCacheAccess")
                    || node.fields.stream().filter(field -> field.name.equals("cache")
                            && field.desc.equals("Lcom/google/common/cache/LoadingCache;")).count() != 1
                    || node.methods.stream().filter(method -> method.name.equals("nclskins$refresh")).count() != 1)) {
                throw new IllegalStateException("Missing exact generation-scoped cache binding");
            }
            for (String handler : handlers) {
                long declared = node.methods.stream().filter(m -> m.name.contains(handler) && !m.name.contains("lambda$")
                        && !m.name.contains("$mixinextras$bridge$")).count();
                long invoked = node.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                        .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                        .filter(call -> call.owner.equals(node.name) && call.name.contains(handler)
                                && !call.name.contains("$mixinextras$bridge$")).count();
                if (handler.equals("nclskins$previewStatus")) {
                    long bridges = node.methods.stream().filter(m -> m.name.contains(handler) && m.name.contains("$mixinextras$bridge$")).count();
                    var status = node.methods.stream().filter(m -> m.name.equals("getStatus")
                            && m.desc.equals("(Ljava/lang/Object;Ljava/lang/String;)Lcom/tom/cpl/text/FormatText;")).findFirst().orElseThrow();
                    long entries = java.util.Arrays.stream(status.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
                            .map(MethodInsnNode.class::cast).filter(call -> call.owner.equals(node.name)
                                    && call.name.contains(handler) && call.name.contains("$mixinextras$bridge$")).count();
                    if (bridges != 1 || entries != 1) throw new IllegalStateException("Expected one exact status entry-to-local-capture bridge");
                }
                if (declared != 1 || invoked != 1) throw new IllegalStateException(name + ": " + handler + " expected exactly one declared/invoked handler; got " + declared + "/" + invoked + " " + node.methods.stream().filter(m -> m.name.contains(handler)).map(m -> m.name).toList());
            }
            System.out.println("Applied " + name + " " + bytes.length + " -> " + result.length);
        }
    }
}
