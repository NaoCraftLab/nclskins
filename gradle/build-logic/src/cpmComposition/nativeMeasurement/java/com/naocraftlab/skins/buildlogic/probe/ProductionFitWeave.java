package com.naocraftlab.skins.buildlogic.probe;
import java.nio.file.*;import java.util.*;
public final class ProductionFitWeave {
 public static void main(String[] args)throws Exception{
  org.spongepowered.asm.launch.MixinBootstrap.init();org.spongepowered.asm.mixin.Mixins.addConfiguration("nclskins.preview-fit.mixins.json");org.spongepowered.asm.mixin.Mixins.addConfiguration("nclskins.cpm-neutral.probe.mixins.json");org.spongepowered.asm.mixin.Mixins.addConfiguration("nclskins.ears-measurement.mixins.json");org.spongepowered.asm.mixin.Mixins.addConfiguration("ears-model-binding.json");
  var phase=org.spongepowered.asm.mixin.MixinEnvironment.class.getDeclaredMethod("gotoPhase",org.spongepowered.asm.mixin.MixinEnvironment.Phase.class);phase.setAccessible(true);phase.invoke(null,org.spongepowered.asm.mixin.MixinEnvironment.Phase.DEFAULT);
  var env=org.spongepowered.asm.mixin.MixinEnvironment.getDefaultEnvironment();env.setSide(org.spongepowered.asm.mixin.MixinEnvironment.Side.CLIENT);
  var ctor=Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();ctor.setAccessible(true);ctor.newInstance();com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
  var transformer=(org.spongepowered.asm.mixin.transformer.IMixinTransformer)env.getActiveTransformer();
  for(String owner:List.of("net.minecraft.client.renderer.StagedVertexBuffer","net.minecraft.client.renderer.entity.LivingEntityRenderer","net.minecraft.client.renderer.entity.layers.CapeLayer","net.minecraft.client.renderer.entity.layers.WingsLayer","com.tom.cpm.shared.animation.AnimationEngine","com.tom.cpm.shared.model.render.ModelRenderManager","diy.y2k.five.thermite.EarsLayerRenderer$1","net.minecraft.client.model.geom.ModelPart")){
   byte[] before;
   if(owner.startsWith("com.tom.")){before=null;for(String item:System.getProperty("java.class.path").split(java.io.File.pathSeparator))if(item.endsWith(".jar")){try(var jar=new java.util.jar.JarFile(item)){var entry=jar.getJarEntry(owner.replace('.','/')+".class");if(entry!=null){try(var in=jar.getInputStream(entry)){before=in.readAllBytes();}break;}}}if(before==null)throw new AssertionError("raw upstream missing "+owner);}
   else try(var in=ClassLoader.getSystemResourceAsStream(owner.replace('.','/')+".class")){before=in.readAllBytes();}
   byte[] after=transformer.transformClass(env,owner,before);if(Arrays.equals(before,after))throw new AssertionError("production hook not woven "+owner);
   if(owner.equals("diy.y2k.five.thermite.EarsLayerRenderer$1")){
    var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(after).accept(node,0);int observed=0;
    for(var method:node.methods)if(method.name.equals("getEarsFeatures")&&method.desc.equals("()Lcom/unascribed/ears/api/features/EarsFeatures;"))for(var instruction:method.instructions.toArray())if(instruction.getOpcode()==org.objectweb.asm.Opcodes.ARETURN){
     var observation=new org.objectweb.asm.tree.InsnList();observation.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD,0));observation.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKEINTERFACE,"com/unascribed/ears/common/render/EarsRenderDelegate","getPeer","()Ljava/lang/Object;",true));observation.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.CHECKCAST,"net/minecraft/client/renderer/entity/state/AvatarRenderState"));observation.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,"com/naocraftlab/skins/buildlogic/probe/EarsScopeObservation","observe","(Lcom/unascribed/ears/api/features/EarsFeatures;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lcom/unascribed/ears/api/features/EarsFeatures;",false));method.instructions.insertBefore(instruction,observation);observed++;
    }
    if(observed!=1)throw new AssertionError("one readonly installed Ears return observer");var writer=new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);node.accept(writer);after=writer.toByteArray();
   }
   Path file=Path.of(args[0],owner.replace('.','/')+".class");Files.createDirectories(file.getParent());Files.write(file,after);System.out.println("PRODUCT_WEAVE "+owner);
  }
  for(String[] target:List.of(new String[]{"com.naocraftlab.skins.core.compatibility.SkinFeatureAnalyzer","analyze","classifier"},new String[]{"com.naocraftlab.skins.compat.client.identifier.ears.EarsNeutralBody","<init>","copy"},new String[]{"com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture","<init>","capture"},new String[]{"com.naocraftlab.skins.compat.cpm.CpmNeutralBody","<init>","decode"})){
   String entry=target[0].replace('.','/')+".class";byte[] original;try(var in=ClassLoader.getSystemResourceAsStream(entry)){original=in.readAllBytes();}
   var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(original).accept(node,0);int count=0;
   for(var method:node.methods)if(method.name.equals(target[1])){var ins=new org.objectweb.asm.tree.InsnList();ins.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,"com/naocraftlab/skins/buildlogic/probe/FitCountingObservation",target[2],"()V",false));method.instructions.insert(ins);count++;}
   if(count!=1)throw new AssertionError("counting target mismatch "+target[0]+" "+count);var writer=new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);node.accept(writer);Path file=Path.of(args[0],entry);Files.createDirectories(file.getParent());Files.write(file,writer.toByteArray());
  }
 }
}
