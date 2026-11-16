package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class FullRendererPrepare implements Opcodes {
    public static net.minecraft.core.component.DataComponentType<?> component() throws Exception {
        var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        return (net.minecraft.core.component.DataComponentType<?>)((sun.misc.Unsafe)field.get(null)).allocateInstance(Class.forName("net.minecraft.core.component.DataComponentType$Builder$SimpleType"));
    }
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);
        String owner="net/minecraft/core/component/DataComponents";
        ClassNode node=new ClassNode();
        try(var in=ClassLoader.getSystemResourceAsStream(owner+".class")){new ClassReader(in).accept(node,0);}
        var initializer=node.methods.stream().filter(m->m.name.equals("<clinit>") && m.desc.equals("()V")).findFirst().orElseThrow();
        Set<String> reads=new TreeSet<>();
        for(String type:List.of("HumanoidArmorLayer","CapeLayer","WingsLayer","EquipmentLayerRenderer")) {
            var layer=new ClassNode();
            try(var in=ClassLoader.getSystemResourceAsStream("net/minecraft/client/renderer/entity/layers/"+type+".class")){new ClassReader(in).accept(layer,0);}
            for(var method:layer.methods)for(var instruction:method.instructions.toArray())if(instruction instanceof FieldInsnNode field && field.owner.equals(owner)){
                if(!field.desc.equals("Lnet/minecraft/core/component/DataComponentType;"))throw new AssertionError("nonmetadata component terminal");
                reads.add(field.name);
            }
        }
        if(!reads.equals(Set.of("EQUIPPABLE","TRIM")))throw new AssertionError("raw data closure "+reads);
        initializer.instructions.clear();initializer.tryCatchBlocks.clear();if(initializer.localVariables!=null)initializer.localVariables.clear();
        initializer.visibleLocalVariableAnnotations=null;initializer.invisibleLocalVariableAnnotations=null;
        for(String field:reads){
            if(node.fields.stream().filter(f->f.name.equals(field) && f.desc.equals("Lnet/minecraft/core/component/DataComponentType;")).count()!=1)throw new AssertionError(field);
            initializer.instructions.add(new MethodInsnNode(INVOKESTATIC,"com/naocraftlab/skins/buildlogic/probe/FullRendererPrepare","component","()Lnet/minecraft/core/component/DataComponentType;",false));
            initializer.instructions.add(new FieldInsnNode(PUTSTATIC,owner,field,"Lnet/minecraft/core/component/DataComponentType;"));
        }
        initializer.instructions.add(new InsnNode(RETURN));
        var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);
        Path target=out.resolve(owner+".class");Files.createDirectories(target.getParent());Files.write(target,writer.toByteArray());
        System.out.println("CPU_METADATA_TERMINAL one clinit, fields="+reads+", original instance methods retained, no registry/world/game bootstrap");
        String contextOwner="net/minecraft/client/renderer/entity/EntityRendererProvider$Context";
        var context=new ClassNode();try(var in=ClassLoader.getSystemResourceAsStream(contextOwner+".class")){new ClassReader(in).accept(context,0);}
        int atlasCount=0;
        for(var method:context.methods)if(method.name.equals("<init>"))for(var instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/client/resources/model/sprite/AtlasManager")&&call.name.equals("getAtlasOrThrow")&&call.desc.equals("(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/texture/TextureAtlas;")){
            call.setOpcode(INVOKESTATIC);call.owner="com/naocraftlab/skins/buildlogic/probe/CpmCurrentNativeGeometryProbe";call.name="armorAtlas";call.desc="(Lnet/minecraft/client/resources/model/sprite/AtlasManager;Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/texture/TextureAtlas;";atlasCount++;
        }
        int paletteConstructors=0;
        for(var method:context.methods)if(method.name.equals("<init>"))for(var instruction:method.instructions.toArray())
            if(instruction instanceof MethodInsnNode call&&call.getOpcode()==INVOKESPECIAL&&call.owner.equals("net/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer")&&call.name.equals("<init>")&&call.desc.equals("(Lnet/minecraft/client/resources/model/EquipmentAssetManager;Lnet/minecraft/client/resources/palette/PalettedTextureManager;)V"))paletteConstructors++;
        if(!((atlasCount==1&&paletteConstructors==0)||(atlasCount==0&&paletteConstructors==1)))throw new AssertionError("exact Context constructor ABI atlas="+atlasCount+" palette="+paletteConstructors);
        var contextWriter=new ClassWriter(0);context.accept(contextWriter);Path contextPath=out.resolve(contextOwner+".class");Files.createDirectories(contextPath.getParent());Files.write(contextPath,contextWriter.toByteArray());
        System.out.println("CONTEXT_INPUT exact atlas="+atlasCount+" paletteConstructor="+paletteConstructors+"; other constructor instructions preserved");
        if(args.length>1){
            String delegate="diy/y2k/five/thermite/EarsLayerRenderer$1";
            var ears=new ClassNode();try(var in=ClassLoader.getSystemResourceAsStream(delegate+".class")){new ClassReader(in).accept(ears,0);}
            int count=0;
            for(var method:ears.methods)for(var instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode call && call.owner.equals("com/naocraftlab/skins/probe/EarsProbe")&&call.name.equals("selectedFeatures")){
                call.owner="com/naocraftlab/skins/probe/EarsJoinBridge";call.name="features";count++;
            }
            if(count!=1)throw new AssertionError("exact selected processed features call "+count);
            var earsWriter=new ClassWriter(0);ears.accept(earsWriter);Path earsPath=out.resolve(delegate+".class");Files.createDirectories(earsPath.getParent());Files.write(earsPath,earsWriter.toByteArray());
            System.out.println("SOURCE_FEATURE_BINDING one exact processed-source callback; other delegate instructions preserved");
        }
    }
}
