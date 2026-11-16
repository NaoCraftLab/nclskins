package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class ReferencePhaseData implements Opcodes {
    public static final class Flags {public static final boolean DEBUG_SHUFFLE_MODELS=false;}
    public static void main(String[] args)throws Exception{
        String owner="net/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase";var node=PipNativeSetup.load(owner);int reads=0;
        for(var method:node.methods)for(var ins:method.instructions.toArray())if(ins instanceof FieldInsnNode field&&field.owner.equals("net/minecraft/SharedConstants")){
            if(field.getOpcode()!=GETSTATIC||!field.name.equals("DEBUG_SHUFFLE_MODELS")||!field.desc.equals("Z"))throw new AssertionError("unadmitted phase metadata read "+field.name+field.desc);field.owner=Type.getInternalName(Flags.class);reads++;
        }
        if(reads!=2)throw new AssertionError("exact two list/array phase shuffle scalars "+reads);PipNativeSetup.save(Path.of(args[0]),node);System.out.println("REFERENCE_PHASE_DATA exact DEBUG_SHUFFLE_MODELS:Z reads=2; native grouping/sortInto/maybeShuffle algorithms retained; no SharedConstants/FML bootstrap");
    }
}
