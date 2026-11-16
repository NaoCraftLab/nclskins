package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class PipNativeSetup implements Opcodes {
    static void save(Path out,ClassNode node)throws Exception{var w=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(w);Path f=out.resolve(node.name+".class");Files.createDirectories(f.getParent());Files.write(f,w.toByteArray());}
    static ClassNode load(String owner)throws Exception{var n=new ClassNode();try(var in=ClassLoader.getSystemResourceAsStream(owner+".class")){new ClassReader(in).accept(n,0);}return n;}
    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]);
        String dynamicOwner="net/minecraft/client/renderer/texture/DynamicTexture";
        var rawDynamic=load(dynamicOwner);var currentDynamic=new ClassNode();
        var resources=ClassLoader.getSystemResources(dynamicOwner+".class");java.net.URL selected=null;
        while(resources.hasMoreElements()){var url=resources.nextElement();if(url.toString().contains("/texture-inputs/")){selected=url;break;}}
        if(selected==null)throw new AssertionError("frozen prerequisite DynamicTexture first binding");
        try(var in=selected.openStream()){new ClassReader(in).accept(currentDynamic,0);}
        int restored=0;
        for(var rawMethod:rawDynamic.methods)if(rawMethod.name.equals("createTexture")||rawMethod.name.equals("upload")){
            if(rawMethod.name.equals("createTexture"))rawMethod.instructions.insert(new MethodInsnNode(INVOKESTATIC,"com/naocraftlab/skins/probe/EarsProbe","createTexture","()V",false));
            currentDynamic.methods.removeIf(m->m.name.equals(rawMethod.name)&&m.desc.equals(rawMethod.desc));currentDynamic.methods.add(rawMethod);restored++;
        }
        if(restored!=3)throw new AssertionError("three GPU-terminal methods restored "+restored);save(out,currentDynamic);
        System.out.println("GENUINE_TEXTURE_GPU_API restore raw createTexture(Supplier), createTexture(String), upload() through CPU hardware backend; woven lifetime and all ownership retained");
        var node=load("net/minecraft/client/renderer/feature/FeatureRenderDispatcher");
        var method=node.methods.stream().filter(m->m.name.equals("prepareFrame")&&m.desc.equals("(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;")).findFirst().orElseThrow();
        long calls=Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).filter(m->m.owner.equals("net/minecraft/client/renderer/feature/FeatureRenderDispatcher")&&m.name.equals("prepareFrameWithContext")&&m.desc.equals("(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;")).count();
        if(calls!=1)throw new AssertionError("one readonly frame-context assembly to original native prepareFrameWithContext "+calls);
        method.instructions.clear();method.tryCatchBlocks.clear();method.localVariables=null;
        method.visitVarInsn(ALOAD,0);method.visitVarInsn(ALOAD,0);
        method.visitMethodInsn(INVOKESTATIC,"com/naocraftlab/skins/buildlogic/probe/PipMaterialProbe","context","(Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;)Lnet/minecraft/client/renderer/feature/FeatureFrameContext;",false);
        method.visitVarInsn(ALOAD,1);method.visitMethodInsn(INVOKEVIRTUAL,node.name,"prepareFrameWithContext","(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;",false);method.visitInsn(ARETURN);
        save(out,node);
        System.out.println("READONLY_CONTEXT one prepareFrame metadata input assembly; real prepareFrameWithContext/PreparedFrame/phase drain unchanged");
        var guiCollector=load("net/minecraft/client/renderer/state/gui/GuiRenderState");var debugReads=new TreeMap<String,Integer>();
        for(var m:guiCollector.methods)for(var ins:m.instructions.toArray())if(ins instanceof FieldInsnNode field&&field.owner.equals("net/minecraft/SharedConstants")){
            if(field.getOpcode()!=GETSTATIC||!field.desc.equals("Z")||!Set.of("DEBUG_RENDER_UI_LAYERING_RECTANGLES","DEBUG_SHUFFLE_UI_RENDERING_ORDER").contains(field.name))throw new AssertionError("unadmitted native collector debug binding "+field.name);
            debugReads.merge(field.name,1,Integer::sum);field.owner="com/naocraftlab/skins/buildlogic/probe/CpmExtractionBodyProbe$SdkDebugFlags";
        }
        if(!debugReads.equals(Map.of("DEBUG_RENDER_UI_LAYERING_RECTANGLES",1,"DEBUG_SHUFFLE_UI_RENDERING_ORDER",1)))throw new AssertionError("exact previously audited native data flags "+debugReads);save(out,guiCollector);System.out.println("READONLY_GUI_FLAGS genuine GuiRenderState algorithms retained; exact two non-debug scalars, no SharedConstants/FML initialization");
        String renderTypeOwner="net/minecraft/client/renderer/rendertype/RenderType";var renderType=load(renderTypeOwner);int materialReturns=0;
        for(var m:renderType.methods)if(m.name.equals("prepare")&&m.desc.equals("()Lnet/minecraft/client/renderer/rendertype/PreparedRenderType;"))for(var ins:m.instructions.toArray())if(ins.getOpcode()==ARETURN){var observe=new InsnList();observe.add(new InsnNode(DUP));observe.add(new MethodInsnNode(INVOKESTATIC,"com/naocraftlab/skins/buildlogic/probe/PipCurrentHost","prepared","(Lnet/minecraft/client/renderer/rendertype/PreparedRenderType;)V",false));m.instructions.insertBefore(ins,observe);materialReturns++;}
        if(materialReturns!=1)throw new AssertionError("exact material acquisition return "+materialReturns);save(out,renderType);
        var staging=load("net/minecraft/client/renderer/StagedVertexBuffer");int meshBuilds=0;
        for(var m:staging.methods)if(m.name.equals("finishLastVertexBuilder")&&m.desc.equals("()V"))for(var ins:m.instructions.toArray())if(ins instanceof MethodInsnNode call&&call.owner.equals("com/mojang/blaze3d/vertex/BufferBuilder")&&call.name.equals("build")&&call.desc.equals("()Lcom/mojang/blaze3d/vertex/MeshData;")){var observe=new InsnList();observe.add(new InsnNode(DUP));observe.add(new MethodInsnNode(INVOKESTATIC,"com/naocraftlab/skins/buildlogic/probe/PipCurrentHost","mesh","(Lcom/mojang/blaze3d/vertex/MeshData;)V",false));m.instructions.insert(ins,observe);meshBuilds++;}
        if(meshBuilds!=1)throw new AssertionError("exact native CPU mesh completion "+meshBuilds);save(out,staging);System.out.println("OBSERVERS exact RenderType.prepare return=1 and StagedVertexBuffer.finishLastVertexBuilder BufferBuilder.build=1; original algorithms retained");
        Path config=out.getParent().resolve("classes/nclskins.f0-pip.mixins.json");
        Files.writeString(config,"{\"required\":true,\"package\":\"com.naocraftlab.skins.compat.client.identifier.extraction.mixin\",\"client\":[\"PictureInPictureRendererMixin\",\"GuiEntityRendererMixin\"],\"injectors\":{\"defaultRequire\":1}}");
        org.spongepowered.asm.launch.MixinBootstrap.init();
        var phase=org.spongepowered.asm.mixin.MixinEnvironment.class.getDeclaredMethod("gotoPhase",org.spongepowered.asm.mixin.MixinEnvironment.Phase.class);phase.setAccessible(true);phase.invoke(null,org.spongepowered.asm.mixin.MixinEnvironment.Phase.DEFAULT);
        var environment=org.spongepowered.asm.mixin.MixinEnvironment.getDefaultEnvironment();environment.setSide(org.spongepowered.asm.mixin.MixinEnvironment.Side.CLIENT);
        var ctor=Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();ctor.setAccessible(true);ctor.newInstance();com.llamalad7.mixinextras.MixinExtrasBootstrap.init();org.spongepowered.asm.mixin.Mixins.addConfiguration("nclskins.f0-pip.mixins.json");
        var transformer=(org.spongepowered.asm.mixin.transformer.IMixinTransformer)environment.getActiveTransformer();
        for(String name:List.of("net.minecraft.client.gui.render.pip.PictureInPictureRenderer","net.minecraft.client.gui.render.pip.GuiEntityRenderer")){
            byte[] original;try(var in=ClassLoader.getSystemResourceAsStream(name.replace('.','/')+".class")){original=in.readAllBytes();}
            byte[] woven=transformer.transformClass(environment,name,original);if(Arrays.equals(original,woven))throw new AssertionError("normal production PIP weaving missing "+name);Path target=out.resolve(name.replace('.','/')+".class");Files.createDirectories(target.getParent());Files.write(target,woven);System.out.println("NORMAL_CURRENT_PIP_WEAVE "+name+" "+original.length+" -> "+woven.length);
        }
        for(String owner:List.of("net/minecraft/client/renderer/entity/LivingEntityRenderer","diy/y2k/five/thermite/EarsLayerRenderer")){
            node=load(owner);int observations=0;
            for(var m:node.methods)for(var ins:m.instructions.toArray())if(ins instanceof MethodInsnNode call&&call.owner.equals("com/naocraftlab/skins/buildlogic/probe/UpstreamABProbe")&&Set.of("enterLayer","leaveLayer","earsEntry").contains(call.name)){
                call.owner="com/naocraftlab/skins/buildlogic/probe/PipMaterialProbe";observations++;
            }
            if(observations==0)throw new AssertionError("existing baseline observer not found "+owner);
            save(out,node);System.out.println("OBSERVER_REBIND "+owner+" count="+observations);
        }
    }
}
