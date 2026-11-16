package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import java.util.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

public final class ReferenceEquipmentWeave {
    public static void main(String[] args)throws Exception{
        MixinBootstrap.init();var phase=MixinEnvironment.class.getDeclaredMethod("gotoPhase",MixinEnvironment.Phase.class);phase.setAccessible(true);phase.invoke(null,MixinEnvironment.Phase.DEFAULT);var env=MixinEnvironment.getDefaultEnvironment();env.setSide(MixinEnvironment.Side.CLIENT);var ctor=Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();ctor.setAccessible(true);ctor.newInstance();com.llamalad7.mixinextras.MixinExtrasBootstrap.init();Mixins.addConfiguration("cpm.mixins.shared.json");
        String name="net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer";byte[] raw;try(var in=ClassLoader.getSystemResourceAsStream(name.replace('.','/')+".class")){raw=in.readAllBytes();}byte[] woven=((IMixinTransformer)env.getActiveTransformer()).transformClass(env,name,raw);if(Arrays.equals(raw,woven))throw new AssertionError("real CPM equipment material hook required");var target=Path.of(args[0]).resolve(name.replace('.','/')+".class");Files.createDirectories(target.getParent());Files.write(target,woven);System.out.println("REFERENCE_EQUIPMENT_WEAVE normal pinned upstream EquipmentLayerRendererMixin including genuine WINGS texture/material binding "+raw.length+" -> "+woven.length);
    }
}
