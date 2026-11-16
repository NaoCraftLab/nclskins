package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Adds one read-only witness to the genuine immediate mesh consumer. */
public final class ImmediateMeshObservation {
    public static void main(String[] args) throws Exception {
        String owner="net/minecraft/client/renderer/rendertype/RenderType";
        var resource=ClassLoader.getSystemResource(owner+".class");if(resource==null)throw new AssertionError("native RenderType missing");
        byte[] before;try(var input=resource.openStream()){before=input.readAllBytes();}
        var node=new ClassNode();new ClassReader(before).accept(node,0);
        var methods=node.methods.stream().filter(m->m.name.equals("draw")&&m.desc.equals("(Lcom/mojang/blaze3d/vertex/MeshData;)V")).toList();
        if(methods.size()!=1)throw new AssertionError("one exact native mesh consumer");
        var head=new InsnList();head.add(new VarInsnNode(Opcodes.ALOAD,1));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"com/naocraftlab/skins/buildlogic/probe/ReferenceBodyProbe","mesh","(Lcom/mojang/blaze3d/vertex/MeshData;)V",false));methods.get(0).instructions.insert(head);
        var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);byte[] after=writer.toByteArray();
        var file=Path.of(args[0],owner+".class");Files.createDirectories(file.getParent());Files.write(file,after);
        var sha=MessageDigest.getInstance("SHA-256");System.out.println("NATIVE_MESH_WITNESS source="+resource+" before="+HexFormat.of().formatHex(sha.digest(before))+" after="+HexFormat.of().formatHex(sha.digest(after)));
    }
}
