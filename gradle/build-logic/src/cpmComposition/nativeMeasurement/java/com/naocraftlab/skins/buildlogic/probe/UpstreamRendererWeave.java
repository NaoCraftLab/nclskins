package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

public final class UpstreamRendererWeave {
    public static void decorateFabric(Path output) throws Exception {
        var metadataType=Class.forName("net.fabricmc.loader.impl.metadata.LoaderModMetadata");
        var versionsType=Class.forName("net.fabricmc.loader.impl.metadata.VersionOverrides");
        var dependenciesType=Class.forName("net.fabricmc.loader.impl.metadata.DependencyOverrides");
        Object versions=versionsType.getConstructor().newInstance();
        Path metadataRoot=output.resolve("empty-owned-config");Files.createDirectories(metadataRoot);
        Object dependencies=dependenciesType.getConstructor(Path.class).newInstance(metadataRoot);
        Object metadata;
        try(var input=ClassLoader.getSystemResourceAsStream("fabric.mod.json")) {
            metadata=Class.forName("net.fabricmc.loader.impl.metadata.ModMetadataParser").getMethod("parseMetadata",java.io.InputStream.class,String.class,List.class,versionsType,dependenciesType,boolean.class).invoke(null,input,"public-ears-verification-jar",List.of(),versions,dependencies,false);
        }
        if(!metadataType.getMethod("getId").invoke(metadata).equals("ears"))throw new AssertionError("exact Ears metadata");
        var candidateType=Class.forName("net.fabricmc.loader.impl.discovery.ModCandidateImpl");
        var create=candidateType.getDeclaredMethod("createPlain",List.class,metadataType,boolean.class,Collection.class);create.setAccessible(true);
        var jar=(java.net.JarURLConnection)ClassLoader.getSystemResource("ears.mixins.json").openConnection();
        Object candidate=create.invoke(null,List.of(Path.of(jar.getJarFileURL().toURI())),metadata,false,List.of());
        var containerType=Class.forName("net.fabricmc.loader.impl.ModContainerImpl");Object container=containerType.getConstructor(candidateType).newInstance(candidate);
        var decorator=Class.forName("net.fabricmc.loader.impl.launch.FabricMixinBootstrap$MixinConfigDecorator").getDeclaredMethod("apply",Map.class);decorator.setAccessible(true);decorator.invoke(null,Map.of("ears.mixins.json",container));
        var config=Mixins.getConfigs().stream().filter(value->value.getName().equals("ears.mixins.json")).findFirst().orElseThrow().getConfig();
        System.out.println("GENUINE_FABRIC_METADATA_DECORATOR mod="+config.getDecoration("fabric-modId")+" compatibility="+config.getDecoration("fabric-compat"));
    }
    public static void main(String[] args) throws Exception {
        System.out.println("PREFLIGHT config="+ClassLoader.getSystemResource("ears.mixins.json")+" mixin="+ClassLoader.getSystemResource("diy/y2k/five/thermite/mixin/MixinLocatorBarRenderer.class")+" target="+ClassLoader.getSystemResource("net/minecraft/client/gui/contextualbar/LocatorBarRenderer.class")+" service="+ClassLoader.getSystemResource("com/naocraftlab/skins/buildlogic/probe/HeadlessMixinService.class"));
        MixinBootstrap.init();
        var phase=MixinEnvironment.class.getDeclaredMethod("gotoPhase",MixinEnvironment.Phase.class);phase.setAccessible(true);phase.invoke(null,MixinEnvironment.Phase.DEFAULT);
        var environment=MixinEnvironment.getDefaultEnvironment();environment.setSide(MixinEnvironment.Side.CLIENT);
        var ctor=Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();ctor.setAccessible(true);ctor.newInstance();
        MixinExtrasBootstrap.init();
        Mixins.addConfiguration("ears.mixins.json");
        Mixins.addConfiguration("cpm.mixins.json");
        Mixins.addConfiguration("cpm.mixins.shared.json");
        if(args.length>1&&args[1].equals("fabric")) decorateFabric(Path.of(args[0]));
        var transformer=(IMixinTransformer)environment.getActiveTransformer();
        Path output=Path.of(args[0]);
        String materialOwner="net/minecraft/client/renderer/rendertype/RenderTypes";
        var materialNode=new ClassNode();try(var in=ClassLoader.getSystemResourceAsStream(materialOwner+".class")){new ClassReader(in).accept(materialNode,0);}
        var materialMethod=materialNode.methods.stream().filter(m->m.name.equals("entityTranslucent")&&m.desc.equals("(Lnet/minecraft/resources/Identifier;Z)Lnet/minecraft/client/renderer/rendertype/RenderType;")).findFirst().orElseThrow();
        materialMethod.instructions.clear();materialMethod.tryCatchBlocks.clear();materialMethod.localVariables=null;
        materialMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"com/naocraftlab/skins/buildlogic/probe/UpstreamABProbe","material","()Lnet/minecraft/client/renderer/rendertype/RenderType;",false));materialMethod.instructions.add(new InsnNode(Opcodes.ARETURN));
        var materialWriter=new ClassWriter(ClassWriter.COMPUTE_MAXS);materialNode.accept(materialWriter);Path materialPath=output.resolve(materialOwner+".class");Files.createDirectories(materialPath.getParent());Files.write(materialPath,materialWriter.toByteArray());
        System.out.println("DEVICE_MATERIAL_TERMINAL RenderTypes.entityTranslucent(Identifier,boolean) exact one factory; no geometry/lifecycle substitution");
        for(String name:List.of("net.minecraft.client.renderer.entity.player.AvatarRenderer","net.minecraft.client.renderer.entity.state.AvatarRenderState","net.minecraft.client.renderer.entity.layers.CapeLayer","net.minecraft.client.renderer.entity.layers.WingsLayer","net.minecraft.client.renderer.entity.LivingEntityRenderer","net.minecraft.client.model.player.PlayerModel","diy.y2k.five.thermite.EarsLayerRenderer")) {
            byte[] original;try(var in=ClassLoader.getSystemResourceAsStream(name.replace('.','/')+".class")){original=in.readAllBytes();}
            byte[] result=transformer.transformClass(environment,name,original);
            var node=new ClassNode();new ClassReader(result).accept(node,0);
            int redirects=0;
            for(var m:node.methods)for(var instruction:m.instructions.toArray())if(instruction instanceof MethodInsnNode call && call.owner.equals("diy/y2k/five/thermite/Thermite") && call.name.equals("getEarsFeatures") && call.desc.equals("(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lcom/unascribed/ears/api/features/EarsFeatures;")){
                call.owner="com/naocraftlab/skins/probe/EarsJoinBridge";call.name="features";redirects++;
            }
            if(name.endsWith("CapeLayer")||name.endsWith("WingsLayer"))if(redirects!=1)throw new AssertionError("exact processed features terminal count "+name+" "+redirects);
            if(name.endsWith("AvatarRenderer")){
                var constructor=node.methods.stream().filter(m->m.name.equals("<init>")).findFirst().orElseThrow();
                long install=Arrays.stream(constructor.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).filter(m->m.owner.equals(node.name)&&m.name.contains("init")&&m.desc.endsWith("CallbackInfo;)V")).count();
                if(install!=1)throw new AssertionError("genuine Ears constructor injection "+install);
                System.out.println("CONSTRUCTOR_INJECTION exact Ears init callback count="+install);
            }
            if(name.equals("diy.y2k.five.thermite.EarsLayerRenderer")){
                var submit=node.methods.stream().filter(m->m.name.equals("submit")&&m.desc.contains("Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;")).findFirst().orElseThrow();
                submit.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,"com/naocraftlab/skins/buildlogic/probe/UpstreamABProbe","earsEntry","()V",false));
            }
            if(name.endsWith("LivingEntityRenderer")){
                int calls=0;
                for(var method:node.methods)if(method.name.equals("submit"))for(var instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/client/renderer/entity/layers/RenderLayer")&&call.name.equals("submit")){
                    method.instructions.insertBefore(call,new MethodInsnNode(Opcodes.INVOKESTATIC,"com/naocraftlab/skins/buildlogic/probe/UpstreamABProbe","enterLayer","()V",false));
                    method.instructions.insert(call,new MethodInsnNode(Opcodes.INVOKESTATIC,"com/naocraftlab/skins/buildlogic/probe/UpstreamABProbe","leaveLayer","()V",false));calls++;
                }
                if(calls!=1)throw new AssertionError("exact native layer traversal observer count "+calls);
            }
            var writer=new ClassWriter(0);node.accept(writer);Path target=output.resolve(name.replace('.','/')+".class");Files.createDirectories(target.getParent());Files.write(target,writer.toByteArray());
            System.out.println("UPSTREAM_CPM_EARS_WEAVE "+name+" "+original.length+" -> "+result.length+" processedFeatureTerminals="+redirects);
        }
    }
}
