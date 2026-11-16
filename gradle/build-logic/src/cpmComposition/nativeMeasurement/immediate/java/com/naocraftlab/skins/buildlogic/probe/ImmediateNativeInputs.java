package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class ImmediateNativeInputs implements Opcodes {
    private static final String SELF="com/naocraftlab/skins/buildlogic/probe/ImmediateNativeInputs";
    public static net.minecraft.client.renderer.texture.TextureAtlas atlas(net.minecraft.client.resources.model.sprite.AtlasManager manager,net.minecraft.resources.Identifier id) {
        if(manager!=null||!id.equals(net.minecraft.data.AtlasIds.ARMOR_TRIMS))throw new AssertionError("exact readonly armor-trim atlas input");
        return null;
    }
    public static net.minecraft.core.component.DataComponentType<?> component() throws Exception {
        var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        return (net.minecraft.core.component.DataComponentType<?>)((sun.misc.Unsafe)field.get(null)).allocateInstance(Class.forName("net.minecraft.core.component.DataComponentType$Builder$SimpleType"));
    }
    private static ClassNode load(String name) throws Exception {
        var url=ClassLoader.getSystemResource(name+".class");if(url==null)throw new AssertionError("native metadata owner missing: "+name);
        byte[] bytes;try(var in=url.openStream()){bytes=in.readAllBytes();}
        System.out.println("INPUT "+name+" source="+url+" sha256="+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        var node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;
    }
    private static void save(Path out,ClassNode node) throws Exception {
        var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);byte[] bytes=writer.toByteArray();
        var file=out.resolve(node.name+".class");Files.createDirectories(file.getParent());Files.write(file,bytes);
        System.out.println("OUTPUT "+node.name+" sha256="+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);var context=load("net/minecraft/client/renderer/entity/EntityRendererProvider$Context");int atlas=0;
        for(var m:context.methods)if(m.name.equals("<init>"))for(var ins:m.instructions.toArray())if(ins instanceof MethodInsnNode c&&c.owner.equals("net/minecraft/client/resources/model/sprite/AtlasManager")&&c.name.equals("getAtlasOrThrow")&&c.desc.equals("(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/texture/TextureAtlas;")) {
            c.setOpcode(INVOKESTATIC);c.owner=SELF;c.name="atlas";c.desc="(Lnet/minecraft/client/resources/model/sprite/AtlasManager;Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/texture/TextureAtlas;";atlas++;
        }
        if(atlas!=1)throw new AssertionError("exact context atlas call: "+atlas);save(out,context);
        var item=load("net/minecraft/world/item/ItemStack");var init=item.methods.stream().filter(m->m.name.equals("<clinit>")&&m.desc.equals("()V")).findFirst().orElseThrow();long empty=Arrays.stream(init.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).filter(c->c.owner.equals(item.name)&&c.name.equals("<init>")&&c.desc.equals("(Ljava/lang/Void;)V")).count();
        if(empty!=1)throw new AssertionError("one native empty constructor: "+empty);init.instructions.clear();init.tryCatchBlocks.clear();init.localVariables=null;
        init.visitTypeInsn(NEW,item.name);init.visitInsn(DUP);init.visitInsn(ACONST_NULL);init.visitMethodInsn(INVOKESPECIAL,item.name,"<init>","(Ljava/lang/Void;)V",false);init.visitFieldInsn(PUTSTATIC,item.name,"EMPTY","Lnet/minecraft/world/item/ItemStack;");init.visitInsn(RETURN);save(out,item);
        var executor=load("net/minecraft/TracingExecutor");int flags=0;
        for(var m:executor.methods)if(m.name.equals("forName"))for(var ins:m.instructions.toArray())if(ins instanceof FieldInsnNode f&&f.getOpcode()==GETSTATIC&&f.owner.equals("net/minecraft/SharedConstants")&&f.name.equals("IS_RUNNING_IN_IDE")&&f.desc.equals("Z")){m.instructions.set(f,new InsnNode(ICONST_0));flags++;}
        if(flags!=1)throw new AssertionError("one diagnostic IDE scalar: "+flags);save(out,executor);
        var fields=new TreeMap<String,Integer>();for(String name:List.of("HumanoidArmorLayer","CapeLayer","WingsLayer","EquipmentLayerRenderer")) {
            var layer=load("net/minecraft/client/renderer/entity/layers/"+name);
            for(var m:layer.methods)for(var ins:m.instructions.toArray())if(ins instanceof FieldInsnNode f&&f.owner.equals("net/minecraft/core/component/DataComponents")) {
                if(f.getOpcode()!=GETSTATIC||!f.desc.equals("Lnet/minecraft/core/component/DataComponentType;"))throw new AssertionError("not readonly component "+f.name);fields.merge(f.name,1,Integer::sum);
            }
        }
        if(!fields.keySet().equals(Set.of("EQUIPPABLE","TRIM")))throw new AssertionError("exact readonly component closure: "+fields);
        var components=load("net/minecraft/core/component/DataComponents");var ci=components.methods.stream().filter(m->m.name.equals("<clinit>")&&m.desc.equals("()V")).findFirst().orElseThrow();ci.instructions.clear();ci.tryCatchBlocks.clear();ci.localVariables=null;
        for(String name:fields.keySet()){ci.visitMethodInsn(INVOKESTATIC,SELF,"component","()Lnet/minecraft/core/component/DataComponentType;",false);ci.visitFieldInsn(PUTSTATIC,components.name,name,"Lnet/minecraft/core/component/DataComponentType;");}ci.visitInsn(RETURN);save(out,components);
        System.out.println("READONLY_SUBSTITUTIONS contextAtlas=1 itemEmpty=1 ideFlag=1 componentReads="+fields);
    }
}
