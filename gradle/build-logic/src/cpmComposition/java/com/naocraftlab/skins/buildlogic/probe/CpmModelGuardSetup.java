package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import java.util.jar.JarFile;
import org.objectweb.asm.*;

public final class CpmModelGuardSetup {
    public static void main(String[] args) throws Exception {
        String owner="com/tom/cpm/shared/io/ModelFile";
        try(var jar=new JarFile(args[0])) {
            byte[] original;
            try (var input = ClassLoader.getSystemResourceAsStream(owner + ".class")) { original = input.readAllBytes(); }
            var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
            int[] count={0};
            new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9,writer) {
                @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                    var method=super.visitMethod(access,name,desc,signature,exceptions);
                    if(!name.equals("load") || !desc.equals("(Ljava/lang/String;Ljava/io/InputStream;)Lcom/tom/cpm/shared/io/ModelFile;"))return method;
                    count[0]++;
                    return new MethodVisitor(Opcodes.ASM9,method) {
                        @Override public void visitCode() {
                            super.visitCode();
                            visitVarInsn(Opcodes.ALOAD, 1);
                            visitMethodInsn(Opcodes.INVOKESTATIC,"com/naocraftlab/skins/buildlogic/probe/CpmModelConversionProbe","upstreamLoad","(Ljava/io/InputStream;)V",false);
                        }
                    };
                }
            },0);
            if(count[0]!=1)throw new AssertionError("Expected exact public load binding");
            Path output=Path.of(args[1]).resolve(owner+".class");Files.createDirectories(output.getParent());Files.write(output,writer.toByteArray());
            String mode=args.length>2?args[2]:"observe";
            mutateExporter(jar,Path.of(args[1]),mode);
            System.out.println("PASS one actual public parser entry sentinel; exporter mode="+mode);
        }
    }
    private static void mutateExporter(JarFile jar,Path output,String mode) throws Exception {
        String owner="com/tom/cpm/shared/editor/Exporter";
        byte[] original=jar.getInputStream(jar.getJarEntry(owner+".class")).readAllBytes();
        var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);int[] count={0};
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9,writer) {
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                var method=super.visitMethod(access,name,desc,signature,exceptions);
                if(!name.equals("convert") || !desc.equals("(Lcom/tom/cpm/shared/io/ModelFile;Lcom/tom/cpl/util/Image;Lcom/tom/cpm/shared/model/SkinType;Ljava/util/function/Consumer;Ljava/lang/Runnable;)V"))return method;
                count[0]++;
                if(mode.equals("observe"))return new MethodVisitor(Opcodes.ASM9,method) {
                    @Override public void visitCode(){super.visitCode();visitMethodInsn(Opcodes.INVOKESTATIC,"com/naocraftlab/skins/buildlogic/probe/CpmModelConversionProbe","upstreamConvert","()V",false);}
                };
                method.visitCode();
                if(mode.equals("throw") || mode.equals("linkage")) {
                    String error=mode.equals("throw")?"java/lang/IllegalStateException":"java/lang/NoSuchMethodError";
                    method.visitTypeInsn(Opcodes.NEW,error);method.visitInsn(Opcodes.DUP);method.visitMethodInsn(Opcodes.INVOKESPECIAL,error,"<init>","()V",false);method.visitInsn(Opcodes.ATHROW);
                } else {
                    if(mode.equals("duplicate") || mode.equals("success-error") || mode.equals("invalid")) {
                        for(int i=0;i<(mode.equals("duplicate")?2:1);i++) {
                            method.visitVarInsn(Opcodes.ALOAD,3);
                            if(mode.equals("invalid")){method.visitTypeInsn(Opcodes.NEW,"com/tom/cpl/util/Image");method.visitInsn(Opcodes.DUP);method.visitIntInsn(Opcodes.BIPUSH,32);method.visitIntInsn(Opcodes.BIPUSH,32);method.visitMethodInsn(Opcodes.INVOKESPECIAL,"com/tom/cpl/util/Image","<init>","(II)V",false);}
                            else method.visitVarInsn(Opcodes.ALOAD,1);
                            method.visitMethodInsn(Opcodes.INVOKEINTERFACE,"java/util/function/Consumer","accept","(Ljava/lang/Object;)V",true);
                        }
                    }
                    if(mode.equals("error") || mode.equals("success-error")){method.visitVarInsn(Opcodes.ALOAD,4);method.visitMethodInsn(Opcodes.INVOKEINTERFACE,"java/lang/Runnable","run","()V",true);}
                    method.visitInsn(Opcodes.RETURN);
                }
                method.visitMaxs(0,0);method.visitEnd();return null;
            }
        },0);
        if(count[0]!=1)throw new AssertionError("Expected exact public convert binding");
        Path file=output.resolve(owner+".class");Files.createDirectories(file.getParent());Files.write(file,writer.toByteArray());
    }
}