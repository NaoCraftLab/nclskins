package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

public final class NeoDebugInput implements Opcodes {
    public static boolean shuffleModels() {
        if(System.getProperty("MC_DEBUG_ENABLED")!=null)throw new AssertionError("stock debug properties required");
        return false;
    }

    public static void main(String[] args) throws Exception {
        String owner="net/minecraft/client/renderer/feature/ModelFeatureRenderer";
        var node=new ClassNode();
        try(var input=ClassLoader.getSystemResourceAsStream(owner+".class")) {
            if(input==null)throw new AssertionError("missing exact model feature owner");
            new ClassReader(input).accept(node,0);
        }
        int matches=0;
        for(var method:node.methods)for(var instruction:method.instructions.toArray())
            if(instruction instanceof FieldInsnNode field&&field.owner.equals("net/minecraft/SharedConstants")&&field.name.equals("DEBUG_SHUFFLE_MODELS")) {
                if(!method.name.equals("renderBatch")||field.getOpcode()!=GETSTATIC||!field.desc.equals("Z"))throw new AssertionError("unexpected debug model read");
                method.instructions.set(instruction,new MethodInsnNode(INVOKESTATIC,Type.getInternalName(NeoDebugInput.class),"shuffleModels","()Z",false));matches++;
            }
        if(matches!=1)throw new AssertionError("exact debug model read count "+matches);
        var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);
        var output=Path.of(args[0]).resolve(owner+".class");Files.createDirectories(output.getParent());Files.write(output,writer.toByteArray());
        System.out.println("NEO_DEBUG_INPUT stock absent debug flag; native feature rendering retained");
    }
}
