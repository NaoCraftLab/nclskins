package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class ReferenceNeoProfile implements Opcodes {
    public static boolean firstPersonInstalled(){return false;}
    public static void main(String[] args)throws Exception{
        var node=PipNativeSetup.load("com/tom/cpm/client/PlayerProfile");int calls=0;
        for(var m:node.methods)if(m.name.equals("<clinit>")&&m.desc.equals("()V"))for(var ins:m.instructions.toArray())if(ins instanceof MethodInsnNode c&&c.owner.equals("com/tom/cpm/mixinplugin/FPMDetector")&&c.name.equals("doApply")&&c.desc.equals("()Z")){
            if(c.getOpcode()!=INVOKESTATIC||c.itf)throw new AssertionError("exact readonly optional firstperson-mod input");c.owner=Type.getInternalName(ReferenceNeoProfile.class);c.name="firstPersonInstalled";calls++;
        }
        if(calls!=1)throw new AssertionError("one fixed absent optional firstperson-mod input "+calls);PipNativeSetup.save(Path.of(args[0]),node);System.out.println("REFERENCE_NEO_PROFILE_METADATA exact firstperson=false synthetic modset; genuine native PlayerProfile constructor, source/variant/textures and state methods retained; no FMLLoader bootstrap");
    }
}
