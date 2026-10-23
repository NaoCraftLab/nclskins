package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class CpmProjectMutationProbe {
    public static void main(String[] args) throws Exception {
        Path source=Path.of(args[0]),output=Path.of(args[1]);String mode=args[2];
        String name=mode.equals("log")?"com/tom/cpm/shared/editor/Exporter":mode.equals("stream")?"com/tom/cpm/shared/editor/template/EditorTemplate":"com/naocraftlab/skins/compat/cpm/CpmProjectImportScope";
        var n=new ClassNode();new ClassReader(Files.readAllBytes(source.resolve(name+".class"))).accept(n,0);int changes=0;
        for(var m:n.methods){
            if(mode.equals("reject-all")&&m.name.equals("compile")) {m.instructions.clear();m.tryCatchBlocks.clear();if(m.localVariables!=null)m.localVariables.clear();m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));m.instructions.add(new InsnNode(Opcodes.ARETURN));changes++;}
            else for(var i:m.instructions.toArray())if(i instanceof MethodInsnNode c&&c.owner.equals("com/naocraftlab/skins/compat/cpm/CpmProjectImportScope")&&c.name.equals("owns")){
                m.instructions.insertBefore(c,new InsnNode(Opcodes.POP));m.instructions.set(c,new InsnNode(Opcodes.ICONST_0));changes++;
            }
        }
        if(changes!=1)throw new AssertionError("targeted single mutant "+mode+" changed "+changes);
        var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);n.accept(writer);var p=output.resolve(name+".class");Files.createDirectories(p.getParent());Files.write(p,writer.toByteArray());System.out.println("MUTANT "+mode+" one targeted bypass generated");
    }
}
