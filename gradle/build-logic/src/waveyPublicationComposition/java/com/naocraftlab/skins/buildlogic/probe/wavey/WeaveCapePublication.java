package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;

public final class WeaveCapePublication {
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
        boolean baked = args.length > 2 && args[2].equals("baked");
        if (baked) Mixins.addConfiguration("nclskins.identifier-extraction.mixins.json");
        var transformer = (IMixinTransformer) env.getActiveTransformer();
        var owners = new java.util.ArrayList<>(java.util.List.of("net.minecraft.client.renderer.entity.state.AvatarRenderState",
                "net.minecraft.client.renderer.entity.player.AvatarRenderer"));
        if (baked) owners.add("net.minecraft.client.gui.GuiGraphicsExtractor");
        for (String name : owners) {
            String entry = name.replace('.', '/') + ".class";
            byte[] before;
            try (var in = ClassLoader.getSystemResourceAsStream(entry)) { before = in.readAllBytes(); }
            byte[] after = transformer.transformClass(env, name, before);
            ClassNode node = new ClassNode();
            new ClassReader(after).accept(node, 0);
            boolean publication = node.methods.stream().anyMatch(m -> m.name.contains("captureCapeMaterial"));
            boolean carrier = node.interfaces.contains("com/naocraftlab/skins/compat/client/identifier/CapeMaterialSnapshot$Carrier");
            if (name.endsWith("AvatarRenderer") && !publication && !args[1].contains("missing"))
                throw new AssertionError("Production publication handler not woven");
            if (name.endsWith("AvatarRenderState") && !carrier) throw new AssertionError("Production carrier not woven");
            if (name.endsWith("GuiGraphicsExtractor") && node.methods.stream().filter(m -> m.name.contains("nclskins$submitCompositePlayer")
                    && !m.name.contains("$mixinextras$bridge$")).count() != 1) throw new AssertionError("Production baked GUI handler not woven exactly once");
            Path dest = Path.of(args[0]).resolve(entry);
            Files.createDirectories(dest.getParent());
            Files.write(dest, after);
            System.out.println("NORMAL_PUBLICATION_MIXIN " + name + " publication=" + publication + " carrier=" + carrier);
        }
    }
}
