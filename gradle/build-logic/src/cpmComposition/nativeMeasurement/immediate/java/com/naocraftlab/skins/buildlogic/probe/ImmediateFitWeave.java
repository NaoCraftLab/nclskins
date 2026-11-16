package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Applies the selected loader's real production fit hooks to first-effective 26.1 inputs. */
public final class ImmediateFitWeave {
    public static void main(String[] args) throws Exception {
        org.spongepowered.asm.launch.MixinBootstrap.init();
        org.spongepowered.asm.mixin.Mixins.addConfiguration("nclskins.player-model-fit.mixins.json");
        org.spongepowered.asm.mixin.Mixins.addConfiguration("nclskins.cpm-neutral.probe.mixins.json");
        org.spongepowered.asm.mixin.Mixins.addConfiguration("nclskins.ears-measurement.mixins.json");
        org.spongepowered.asm.mixin.Mixins.addConfiguration("ears-model-binding.json");
        var phase=org.spongepowered.asm.mixin.MixinEnvironment.class.getDeclaredMethod("gotoPhase",org.spongepowered.asm.mixin.MixinEnvironment.Phase.class);
        phase.setAccessible(true);phase.invoke(null,org.spongepowered.asm.mixin.MixinEnvironment.Phase.DEFAULT);
        var env=org.spongepowered.asm.mixin.MixinEnvironment.getDefaultEnvironment();env.setSide(org.spongepowered.asm.mixin.MixinEnvironment.Side.CLIENT);
        var ctor=Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();ctor.setAccessible(true);ctor.newInstance();com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
        var transformer=(org.spongepowered.asm.mixin.transformer.IMixinTransformer)env.getActiveTransformer();
        for(String owner:List.of("net.minecraft.client.renderer.MultiBufferSource$BufferSource","net.minecraft.client.renderer.entity.LivingEntityRenderer","net.minecraft.client.renderer.entity.layers.CapeLayer","net.minecraft.client.renderer.entity.layers.WingsLayer","diy.y2k.five.thermite.EarsLayerRenderer$1","net.minecraft.client.model.geom.ModelPart")) {
            String entry=owner.replace('.','/')+".class";var url=ClassLoader.getSystemResource(entry);
            if(url==null)throw new AssertionError("first-effective class missing "+owner);
            byte[] before;try(var in=url.openStream()){before=in.readAllBytes();}
            byte[] after=transformer.transformClass(env,owner,before);
            if(Arrays.equals(before,after))throw new AssertionError("production hook not woven "+owner);
            var target=Path.of(args[0],entry);Files.createDirectories(target.getParent());Files.write(target,after);
            var sha=MessageDigest.getInstance("SHA-256");
            System.out.println("FIT_BINDING "+owner+" source="+url+" before="+HexFormat.of().formatHex(sha.digest(before))+" after="+HexFormat.of().formatHex(sha.digest(after)));
        }
    }
}
