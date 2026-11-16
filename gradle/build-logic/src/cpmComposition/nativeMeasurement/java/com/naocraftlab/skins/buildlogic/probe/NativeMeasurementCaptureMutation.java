package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class NativeMeasurementCaptureMutation {
    public static void droppedVertex(float x,float y,float z) {}
    public static void main(String[] args) throws Exception {
        String owner="net/minecraft/client/renderer/StagedVertexBuffer";
        var node=PipNativeSetup.load(owner);int calls=0;
        for(var method:node.methods)for(var instruction:method.instructions.toArray())
            if(instruction instanceof MethodInsnNode call && call.owner.equals("com/naocraftlab/skins/compat/client/identifier/measurement/AvatarBodyCapture")
                    && call.name.equals("vertex") && call.desc.equals("(FFF)V")) {
                if(call.getOpcode()!=Opcodes.INVOKESTATIC||call.itf)throw new AssertionError("exact capture vertex invocation");
                call.owner=Type.getInternalName(NativeMeasurementCaptureMutation.class);call.name="droppedVertex";calls++;
            }
        if(calls!=1)throw new AssertionError("one actual native capture vertex hook: "+calls);
        PipNativeSetup.save(Path.of(args[0]),node);
    }
}
