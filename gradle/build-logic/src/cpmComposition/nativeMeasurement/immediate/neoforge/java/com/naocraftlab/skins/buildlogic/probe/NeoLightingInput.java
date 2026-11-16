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

public final class NeoLightingInput implements Opcodes {
    public static boolean enhancedLighting() { return true; }

    public static void main(String[] args) throws Exception {
        String owner="net/neoforged/neoforge/client/model/ao/EnhancedBlockModelLighter";
        var node=new ClassNode();
        try(var input=ClassLoader.getSystemResourceAsStream(owner+".class")) {
            if(input==null)throw new AssertionError("missing exact Neo lighting owner");
            new ClassReader(input).accept(node,0);
        }
        int matches=0;
        for(var method:node.methods)if(method.name.equals("newInstance"))for(var instruction:method.instructions.toArray())
            if(instruction instanceof FieldInsnNode first&&first.getOpcode()==GETSTATIC&&first.owner.equals("net/neoforged/neoforge/client/config/NeoForgeClientConfig")&&first.name.equals("INSTANCE")) {
                var field=instruction.getNext();while(field.getOpcode()<0)field=field.getNext();
                var call=field.getNext();while(call.getOpcode()<0)call=call.getNext();
                if(!(field instanceof FieldInsnNode f)||f.getOpcode()!=GETFIELD||!f.name.equals("enhancedLighting")||!f.desc.equals("Lnet/neoforged/neoforge/common/ModConfigSpec$BooleanValue;")||!(call instanceof MethodInsnNode m)||!m.owner.equals("net/neoforged/neoforge/common/ModConfigSpec$BooleanValue")||!m.name.equals("getAsBoolean")||!m.desc.equals("()Z"))throw new AssertionError("exact native readonly lighting config chain");
                method.instructions.set(instruction,new MethodInsnNode(INVOKESTATIC,Type.getInternalName(NeoLightingInput.class),"enhancedLighting","()Z",false));
                method.instructions.remove(field);method.instructions.remove(call);matches++;
            }
        if(matches!=1)throw new AssertionError("exact lighting config read count "+matches);
        var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);
        var output=Path.of(args[0]).resolve(owner+".class");Files.createDirectories(output.getParent());Files.write(output,writer.toByteArray());
        System.out.println("NEO_LIGHTING_INPUT readonly admitted CPU scalar; native lighting algorithms retained");
    }
}
