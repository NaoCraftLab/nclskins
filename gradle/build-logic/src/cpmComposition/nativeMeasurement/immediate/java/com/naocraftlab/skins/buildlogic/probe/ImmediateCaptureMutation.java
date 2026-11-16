package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Negative control: genuine mesh still drains, but its capture vertex hook is omitted. */
public final class ImmediateCaptureMutation {
    public static void droppedVertex(float x,float y,float z) {}
    public static void main(String[] args) throws Exception {
        String owner="net/minecraft/client/renderer/MultiBufferSource$BufferSource";
        var resource=ClassLoader.getSystemResource(owner+".class");if(resource==null)throw new AssertionError("native BufferSource missing");
        byte[] before;try(var in=resource.openStream()){before=in.readAllBytes();}
        var node=new ClassNode();new ClassReader(before).accept(node,0);int count=0;
        for(var method:node.methods)for(var instruction:method.instructions.toArray())
            if(instruction instanceof MethodInsnNode call && call.owner.equals("com/naocraftlab/skins/compat/client/identifier/measurement/AvatarBodyCapture") && call.name.equals("vertex") && call.desc.equals("(FFF)V")) {
                if(call.getOpcode()!=Opcodes.INVOKESTATIC||call.itf)throw new AssertionError("exact vertex hook");
                call.owner="com/naocraftlab/skins/buildlogic/probe/ImmediateCaptureMutation";call.name="droppedVertex";count++;
            }
        if(count!=1)throw new AssertionError("one native capture vertex hook: "+count);
        var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);byte[] after=writer.toByteArray();
        var file=Path.of(args[0],owner+".class");Files.createDirectories(file.getParent());Files.write(file,after);
        var sha=MessageDigest.getInstance("SHA-256");System.out.println("OMIT_VERTEX_BINDING source="+resource+" before="+HexFormat.of().formatHex(sha.digest(before))+" after="+HexFormat.of().formatHex(sha.digest(after)));
    }
}
