package com.naocraftlab.skins.compat.cpm;

import java.io.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class CpmProjectBindings {
    private static final String ROOT="com/tom/cpm/shared/";
    public static boolean supported(ClassLoader loader) {
        if(loader==null || loader.getResource(ROOT+"MinecraftClientAccess.class")==null)return false;
        try {
            var exporter=read(loader,ROOT+"editor/Exporter");var template=read(loader,ROOT+"editor/template/EditorTemplate");
            var generators=read(loader,ROOT+"editor/Generators");
            var defaults=read(loader,ROOT+"editor/util/GetFreeSkinSlots");
            String texture="(L"+ROOT+"editor/Editor;Ljava/util/function/BiConsumer;L"+ROOT+"model/TextureSheetType;)V";
            String compiler="(L"+ROOT+"editor/Editor;)L"+ROOT+"parts/ModelPartDefinition;";
            String factory="(L"+ROOT+"editor/Editor;L"+ROOT+"editor/project/JsonMap;)L"+ROOT+"editor/template/EditorTemplate;";
            String resource="(L"+ROOT+"definition/Link;L"+ROOT+"config/ResourceLoader$ResourceEncoding;L"+ROOT+"definition/ModelDefinition;)Ljava/io/InputStream;";
            return method(exporter,"prepareDefinition",compiler,Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC)
                && method(defaults,"getDefault","(L"+ROOT+"editor/Editor;)L"+ROOT+"editor/anim/AnimationEncodingData;",Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC)
                && java.util.stream.Stream.of("lambda$loadTextures$0", "lambda$loadTextures$15")
                    .filter(name -> method(generators,name,texture,Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC)).count()==1
                && java.util.stream.Stream.of("lambda$loadTextures$0", "lambda$loadTextures$15").filter(name ->
                    method(generators,name,texture,Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC)
                    && calls(generators,name,texture,ROOT+"MinecraftClientAccess","get","()L"+ROOT+"MinecraftClientAccess;",Opcodes.INVOKESTATIC,true)==1).count()==1
                && method(template,"load",factory,Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC)
                && calls(exporter,"prepareDefinition",compiler,ROOT+"util/Log","info","(Ljava/lang/Object;)V",Opcodes.INVOKESTATIC,false)==1
                && calls(template,"load",factory,ROOT+"definition/ModelDefinitionLoader","load",resource,Opcodes.INVOKEVIRTUAL,false)==1;
        } catch(IOException | RuntimeException failure){return false;}
    }
    private static ClassNode read(ClassLoader loader,String name) throws IOException {
        try(var in=loader.getResourceAsStream(name+".class")){if(in==null)throw new IOException();var node=new ClassNode();new ClassReader(in).accept(node,0);return node;}
    }
    private static boolean method(ClassNode node,String name,String desc,int flags) {
        return node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)&&(m.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_PRIVATE|Opcodes.ACC_PROTECTED|Opcodes.ACC_STATIC))==flags).count()==1;
    }
    private static long calls(ClassNode node,String name,String descriptor,String owner,String target,String desc,int opcode,boolean itf) {
        return node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(descriptor)).flatMap(m->java.util.Arrays.stream(m.instructions.toArray()))
            .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
            .filter(c->c.owner.equals(owner)&&c.name.equals(target)&&c.desc.equals(desc)&&c.getOpcode()==opcode&&c.itf==itf).count();
    }
}
