package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** The same narrow production counter instrumentation used by ProductionFitWeave. */
public final class ImmediateFitCountingWeave {
    public static void main(String[] args) throws Exception {
        for(String[] target:List.of(
                new String[]{"com.naocraftlab.skins.core.compatibility.SkinFeatureAnalyzer","analyze","classifier"},
                new String[]{"com.naocraftlab.skins.compat.client.identifier.ears.EarsNeutralBody","<init>","copy"},
                new String[]{"com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture","<init>","capture"},
                new String[]{"com.naocraftlab.skins.compat.cpm.CpmNeutralBody","<init>","decode"})) {
            String entry=target[0].replace('.','/')+".class";var url=ClassLoader.getSystemResource(entry);if(url==null)throw new AssertionError("missing counter owner "+entry);
            byte[] before;try(var input=url.openStream()){before=input.readAllBytes();}
            var node=new ClassNode();new ClassReader(before).accept(node,0);int count=0;
            for(var method:node.methods)if(method.name.equals(target[1])) {
                var ins=new InsnList();ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"com/naocraftlab/skins/buildlogic/probe/FitCountingObservation",target[2],"()V",false));method.instructions.insert(ins);count++;
            }
            if(count!=1)throw new AssertionError("counting target mismatch "+target[0]+" "+count);
            var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);byte[] after=writer.toByteArray();
            var file=Path.of(args[0],entry);Files.createDirectories(file.getParent());Files.write(file,after);
            var sha=MessageDigest.getInstance("SHA-256");System.out.println("COUNTER_BINDING "+entry+" source="+url+" before="+HexFormat.of().formatHex(sha.digest(before))+" after="+HexFormat.of().formatHex(sha.digest(after)));
        }
    }
}
