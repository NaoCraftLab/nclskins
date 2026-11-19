package com.naocraftlab.skins.probe;

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.util.*;
import java.io.*;
import java.security.*;

public final class Prepare implements Opcodes {
    static final String B = "com/naocraftlab/skins/probe/EarsProbe";
    private static final String TEXTURE_BACKEND = "com/naocraftlab/skins/compat/client/identifier/IdentifierNativeTextureBackend";
    static Path out;
    public static void main(String[] args) throws Exception {
        out = Path.of(args[0]);
        for (String name : List.of("net/minecraft/TracingExecutor", "net/minecraft/client/renderer/texture/DynamicTexture", "net/minecraft/client/renderer/rendertype/RenderTypes", "net/minecraft/client/model/geom/ModelPart", "diy/y2k/five/thermite/EarsLayerRenderer$1", "diy/y2k/five/thermite/EarsLayerRenderer", "net/minecraft/client/renderer/texture/SkinTextureDownloader", "net/minecraft/client/renderer/texture/TextureManager", "com/mojang/blaze3d/platform/NativeImage", "com/naocraftlab/skins/compat/client/identifier/IdentifierTextureRegistry", "com/naocraftlab/skins/compat/client/identifier/IdentifierTextureRegistry$NativeTexture", TEXTURE_BACKEND)) {
            ClassNode c = new ClassNode();
            try (var in = Prepare.class.getClassLoader().getResourceAsStream(name + ".class")) { new ClassReader(in).accept(c, 0); }
            if (name.endsWith("/ModelPart")) {
                c.interfaces.add("diy/y2k/five/thermite/mixin/AccessorModelPart");
                MethodNode m = new MethodNode(ACC_PUBLIC, "ears$getChildren", "()Ljava/util/Map;", null, null);
                m.instructions.add(new VarInsnNode(ALOAD, 0));
                m.instructions.add(new FieldInsnNode(GETFIELD, name, "children", "Ljava/util/Map;"));
                m.instructions.add(new InsnNode(ARETURN)); c.methods.add(m);
            }
            for (MethodNode m : c.methods) {
                if(name.equals("net/minecraft/TracingExecutor")&&m.name.equals("forName")&&Boolean.getBoolean("probe.nofml")) {
                    int count=0;
                    for(AbstractInsnNode instruction:m.instructions.toArray()) if(instruction instanceof FieldInsnNode field&&field.getOpcode()==GETSTATIC&&field.owner.equals("net/minecraft/SharedConstants")&&field.name.equals("IS_RUNNING_IN_IDE")&&field.desc.equals("Z")) {m.instructions.set(field,new InsnNode(ICONST_0));count++;}
                    if(count!=1)throw new AssertionError("exact IDE diagnostic flag count="+count);
                    System.out.println("ENVIRONMENT_TERMINAL SharedConstants.IS_RUNNING_IN_IDE=false; original native IO service/supplier preserved");
                }
                if (name.endsWith("/DynamicTexture") && (m.name.equals("createTexture") || m.name.equals("upload"))) { clear(m); if(m.name.equals("createTexture")) m.instructions.add(new MethodInsnNode(INVOKESTATIC,B,"createTexture","()V",false)); m.instructions.add(new InsnNode(RETURN)); }
                if((name.endsWith("/DynamicTexture")||name.endsWith("/NativeImage"))&&m.name.equals("close")) {
                    InsnList witness=new InsnList();witness.add(new VarInsnNode(ALOAD,0));witness.add(new MethodInsnNode(INVOKESTATIC,B,name.endsWith("/NativeImage")?"closedImage":"closedTexture","(Ljava/lang/Object;)V",false));m.instructions.insert(witness);
                }
                if(name.endsWith("/SkinTextureDownloader")&&m.name.equals("downloadAndRegisterSkin")) {
                    int count=0;
                    for(AbstractInsnNode instruction:m.instructions.toArray())if(instruction instanceof MethodInsnNode call&&call.owner.equals("java/util/concurrent/CompletableFuture")&&call.name.equals("supplyAsync")) {call.owner=B;call.name="ioSupply";count++;}
                    if(count!=1)throw new AssertionError("exact async I/O producer count="+count);
                }
                if(name.endsWith("/SkinTextureDownloader")&&m.name.equals("downloadSkin")) {
                    InsnList witness=new InsnList();witness.add(new VarInsnNode(ALOAD,1));witness.add(new MethodInsnNode(INVOKESTATIC,B,"ioPath","(Ljava/nio/file/Path;)V",false));m.instructions.insert(witness);
                }
                if(name.endsWith("IdentifierTextureRegistry$NativeTexture")&&m.name.equals("attach")) for(AbstractInsnNode instruction:m.instructions.toArray())
                    if(instruction instanceof MethodInsnNode call&&call.name.equals("whenComplete"))m.instructions.insert(instruction,new MethodInsnNode(INVOKESTATIC,B,"terminal","(Ljava/util/concurrent/CompletableFuture;)Ljava/util/concurrent/CompletableFuture;",false));
                if (name.endsWith("/RenderTypes")) {
                    if (m.name.equals("<clinit>")) { clear(m); m.instructions.add(new InsnNode(RETURN)); }
                    if (Set.of("armorEntityGlint", "entityTranslucentCull", "entityTranslucentCullItemTarget").contains(m.name)) {
                        clear(m);
                        if (m.desc.startsWith("()")) m.instructions.add(new InsnNode(ACONST_NULL)); else m.instructions.add(new VarInsnNode(ALOAD, 0));
                        m.instructions.add(new MethodInsnNode(INVOKESTATIC, B, "material", "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;", false));
                        m.instructions.add(new InsnNode(ARETURN));
                    }
                }
                if (name.endsWith("/EarsLayerRenderer$1")) {
                    if (m.name.equals("getEarsFeatures")) {
                        for(AbstractInsnNode n:m.instructions.toArray()) if(n instanceof MethodInsnNode call&&call.owner.equals("diy/y2k/five/thermite/Thermite")&&call.name.equals("getEarsFeatures")){call.owner=B;call.name="selectedFeatures";}
                    }
                    if (Set.of("isWearingBoots", "isWearingLeggings", "isWearingChestplate", "isWearingHelmet", "isWearingElytra").contains(m.name)) {
                        clear(m);m.instructions.add(new InsnNode(ICONST_0));m.instructions.add(new InsnNode(IRETURN));
                    }
                    if (m.name.equals("doUploadAux")) {clear(m);m.instructions.add(new InsnNode(RETURN));}
                }
                if (name.endsWith("/MixinSkinTextureDownloader") && m.name.equals("setNoAlpha")) {
                    clear(m);m.instructions.add(new VarInsnNode(ALOAD,0));for(int j=1;j<=4;j++)m.instructions.add(new VarInsnNode(ILOAD,j));m.instructions.add(new MethodInsnNode(INVOKESTATIC,B,"reenterAlpha",m.desc,false));m.instructions.add(new InsnNode(RETURN));
                }
                if(name.endsWith("/SkinTextureDownloader")&&m.name.equals("downloadSkin")) for(AbstractInsnNode n:m.instructions.toArray()) if(n instanceof MethodInsnNode call&&call.owner.equals("java/net/URL")&&call.name.equals("openConnection")){call.setOpcode(INVOKESTATIC);call.owner=B;call.name="forbiddenNetwork";call.desc="(Ljava/net/URL;Ljava/net/Proxy;)Ljava/net/URLConnection;";}
                if (name.endsWith("/EarsLayerRenderer")) for (AbstractInsnNode n : m.instructions.toArray()) {
                    if (n instanceof MethodInsnNode call && call.owner.equals("net/minecraft/client/renderer/entity/LivingEntityRenderer") && call.name.equals("getOverlayCoords")) {call.owner=B;call.name="overlay";}
                }
            }
            if(name.endsWith("/SkinTextureDownloader")) for(MethodNode method:c.methods) {
                if(method.name.equals("lambda$downloadAndRegisterSkin$0")) {
                    int count=0;
                    for(AbstractInsnNode instruction:method.instructions.toArray()) if(instruction.getOpcode()==ARETURN) {
                        method.instructions.insertBefore(instruction,new MethodInsnNode(INVOKESTATIC,B,"holdOldIo","(Lcom/mojang/blaze3d/platform/NativeImage;)Lcom/mojang/blaze3d/platform/NativeImage;",false)); count++;
                    }
                    if(count!=1)throw new AssertionError("exact I/O return count="+count);
                }
                if(method.name.equals("downloadAndRegisterSkin")) for(AbstractInsnNode instruction:method.instructions.toArray()) if(instruction.getOpcode()==ARETURN)
                    method.instructions.insertBefore(instruction,new MethodInsnNode(INVOKESTATIC,B,"downloaded","(Ljava/util/concurrent/CompletableFuture;)Ljava/util/concurrent/CompletableFuture;",false));
            }
            ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            if(name.endsWith("/SkinTextureDownloader")||name.endsWith("/TextureManager")||name.startsWith("com/naocraftlab/skins/compat/client/identifier/IdentifierTextureRegistry")||name.equals(TEXTURE_BACKEND)) c.accept(new ClassRemapper(w,new SimpleRemapper(Map.of("net/minecraft/client/Minecraft",B+"$Host")))); else c.accept(w);
            Path target = out.resolve(name + ".class");Files.createDirectories(target.getParent());Files.write(target,w.toByteArray());
            ClassNode original=new ClassNode();try(var in=Prepare.class.getClassLoader().getResourceAsStream(name+".class")){new ClassReader(in).accept(original,0);}
            ClassNode emitted=new ClassNode();new ClassReader(w.toByteArray()).accept(emitted,0);
            if(name.endsWith("/EarsLayerRenderer$1"))for(MethodNode before:original.methods){
                if(Set.of("getEarsFeatures","doUploadAux","isWearingBoots","isWearingLeggings","isWearingChestplate","isWearingHelmet","isWearingElytra").contains(before.name))continue;
                MethodNode after=emitted.methods.stream().filter(m->m.name.equals(before.name)&&m.desc.equals(before.desc)).findFirst().orElseThrow();
                String a=text(before),b=text(after);if(!a.equals(b))throw new AssertionError("unexpected native geometry/delegate modification "+before.name+before.desc);
                System.out.println("PRESERVED "+before.name+before.desc+" sha256="+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(a.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            }
            System.out.println("BOUND " + name);
        }
    }
    static String text(MethodNode m){Textifier p=new Textifier();m.accept(new TraceMethodVisitor(p));StringWriter s=new StringWriter();p.print(new PrintWriter(s));return s.toString().replaceAll("(?m)^.*MAXSTACK.*\\n|^.*MAXLOCALS.*\\n","");}
    static void clear(MethodNode m) {m.instructions.clear();m.tryCatchBlocks.clear();if(m.localVariables != null)m.localVariables.clear();m.visibleLocalVariableAnnotations=null;m.invisibleLocalVariableAnnotations=null;}
}
