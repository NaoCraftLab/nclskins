package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class StrictIdeInput implements Opcodes {
    static int reads;
    static Thread readThread;
    public static boolean ide(){reads++;readThread=Thread.currentThread();return true;}
    public static boolean tracingIde(){return true;}
    public static void main(String[] args)throws Exception{
        String owner="com/mojang/renderpearl/frontend/FrontendGpuDevice";
        byte[] original;try(var in=ClassLoader.getSystemResourceAsStream(owner+".class")){original=in.readAllBytes();}
        var node=new ClassNode();new ClassReader(original).accept(node,0);
        int count=0,assignments=0,modifiers=0;
        for(var m:node.methods)for(var ins:m.instructions.toArray()){
            if(ins instanceof FieldInsnNode f&&f.owner.equals("net/minecraft/SharedConstants")&&f.name.equals("IS_RUNNING_IN_IDE")){
                if(!m.name.equals("<clinit>")||!m.desc.equals("()V")||f.getOpcode()!=GETSTATIC||!f.desc.equals("Z"))throw new AssertionError("unexpected IDE site");
                m.instructions.set(ins,new MethodInsnNode(INVOKESTATIC,Type.getInternalName(StrictIdeInput.class),"ide","()Z",false));count++;
            }
            if(ins instanceof FieldInsnNode f&&f.getOpcode()==PUTSTATIC&&f.owner.equals(owner)&&f.name.equals("STRICT_VALIDATION")&&f.desc.equals("Z"))assignments++;
            if(ins instanceof LdcInsnNode c&&"neoforge.disableBlaze3DValidation".equals(c.cst))modifiers++;
        }
        if(count!=1||assignments!=1||modifiers!=(ClassLoader.getSystemResource("net/neoforged/fml/loading/FMLLoader.class")==null?0:1))throw new AssertionError("exact scalar/assignment/modifier "+count+"/"+assignments+"/"+modifiers);
        var writer=new ClassWriter(0);node.accept(writer);var bytes=writer.toByteArray();
        Path out=Path.of(args[0]),f=out.resolve(owner+".class");Files.createDirectories(f.getParent());Files.write(f,bytes);
        var sha=java.security.MessageDigest.getInstance("SHA-256");
        Files.writeString(out.resolve("strict-ide-audit.json"),new com.google.gson.Gson().toJson(Map.of("owner",owner,"method","<clinit>()V","source","net/minecraft/SharedConstants.IS_RUNNING_IN_IDE:Z","opcode","GETSTATIC","count",count,"value",true,"nativeAssignmentsRetained",assignments,"neoModifierRetained",modifiers,"originalSha256",HexFormat.of().formatHex(sha.digest(original)),"effectiveSha256",HexFormat.of().formatHex(sha.digest(bytes)))));
        System.out.println("STRICT_IDE_INPUT count=1 IDE=true assignment=1 nativeNeoModifier="+modifiers);
        String tracing="net/minecraft/TracingExecutor";
        try(var in=ClassLoader.getSystemResourceAsStream(tracing+".class")){new ClassReader(in).accept(node=new ClassNode(),0);}
        var sites=new TreeMap<String,Integer>();
        for(var m:node.methods)for(var ins:m.instructions.toArray())if(ins instanceof FieldInsnNode field&&field.owner.equals("net/minecraft/SharedConstants")){
            if(field.getOpcode()!=GETSTATIC||!field.name.equals("IS_RUNNING_IN_IDE")||!field.desc.equals("Z"))throw new AssertionError("unadmitted tracing metadata");
            sites.merge(m.name+m.desc,1,Integer::sum);m.instructions.set(ins,new MethodInsnNode(INVOKESTATIC,Type.getInternalName(StrictIdeInput.class),"tracingIde","()Z",false));
        }
        if(!sites.equals(Map.of("forName(Ljava/lang/String;)Ljava/util/concurrent/Executor;",1,"lambda$wrapUnnamed$0(Ljava/lang/Runnable;)V",1,"lambda$forName$3(Ljava/lang/String;Ljava/lang/Runnable;)V",1,"lambda$forName$1(Ljava/lang/String;Ljava/lang/Runnable;)V",1)))throw new AssertionError("exact tracing data sites "+sites);
        writer=new ClassWriter(0);node.accept(writer);f=out.resolve(tracing+".class");Files.createDirectories(f.getParent());Files.write(f,writer.toByteArray());Files.writeString(out.resolve("tracing-ide-audit.json"),new com.google.gson.Gson().toJson(sites));
        System.out.println("READONLY_TRACING_IDE sites=4 IDE=true native scheduler/thread-name restoration/Tracy branch retained");
    }
}
