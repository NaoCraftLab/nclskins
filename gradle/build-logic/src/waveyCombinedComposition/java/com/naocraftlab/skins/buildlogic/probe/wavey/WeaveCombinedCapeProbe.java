package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;

public final class WeaveCombinedCapeProbe {
    public static void main(String[] args) throws Exception {
        MixinBootstrap.init();
        var phase = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        phase.setAccessible(true);
        phase.invoke(null, MixinEnvironment.Phase.DEFAULT);
        var env = MixinEnvironment.getDefaultEnvironment();
        env.setSide(MixinEnvironment.Side.CLIENT);
        var ctor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();
        ctor.setAccessible(true);
        ctor.newInstance();
        MixinExtrasBootstrap.init();
        for (String config : args[1].split(",")) Mixins.addConfiguration(config);
        var transformer = (IMixinTransformer) env.getActiveTransformer();
        for (String name : java.util.List.of("net.minecraft.client.renderer.rendertype.RenderType", "net.minecraft.client.renderer.rendertype.PreparedRenderType", "net.minecraft.client.renderer.feature.CustomFeatureRenderer", "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group", "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer")) {
            String entry = name.replace('.', '/') + ".class";
            byte[] before;
            try (var in = ClassLoader.getSystemResourceAsStream(entry)) { before = in.readAllBytes(); }
            byte[] after = transformer.transformClass(env, name, before);
            Path dest = Path.of(args[0]).resolve(entry);
            Files.createDirectories(dest.getParent());
            Files.write(dest, after);
            var node = new ClassNode();
            new ClassReader(after).accept(node, 0);
            boolean own = node.methods.stream().anyMatch(m -> m.name.contains("nclskins$")) || node.fields.stream().anyMatch(f -> f.name.contains("nclskins$"));
            if (!own && args.length == 2) throw new AssertionError("lifetime hook missing: " + name);
            System.out.println("NORMAL_MIXIN_TRANSFORM " + name + " " + before.length + " -> " + after.length + "; productionHandler=" + own);
        }
    }
}
