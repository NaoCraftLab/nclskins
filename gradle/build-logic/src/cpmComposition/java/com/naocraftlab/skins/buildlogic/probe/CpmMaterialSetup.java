package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class CpmMaterialSetup {
 public static void main(String[] args) throws Exception {
  Path output=Path.of(args[0]);
  patch(output,"com.tom.cpm.mixin.render.EquipmentLayerRendererMixin");
 }
 private static void patch(Path output,String name) throws Exception {
  ClassNode node=new ClassNode();
  try(var input=ClassLoader.getSystemResourceAsStream(name.replace('.','/')+".class")){new ClassReader(input.readAllBytes()).accept(node,0);}
  node.methods.removeIf(m->m.name.equals("onSumbitArmorTrim"));
  int replaced=0;
  for(var method:node.methods) for(var instruction:method.instructions.toArray()) {
   if(instruction instanceof FieldInsnNode field && field.getOpcode()==Opcodes.GETSTATIC && field.name.equals("mc") && field.owner.startsWith("com/tom/cpm/client/")) {
    var call=instruction.getNext();
    if(!(call instanceof MethodInsnNode getter) || !getter.name.equals("getPlayerRenderManager"))throw new AssertionError("Unexpected CPM host seam");
    method.instructions.set(field,new FieldInsnNode(Opcodes.GETSTATIC,"com/naocraftlab/skins/buildlogic/probe/CpmMaterialBoundaryProbe","manager","Lcom/tom/cpm/shared/model/render/ModelRenderManager;"));
    method.instructions.remove(call); replaced++;
   } else if(instruction instanceof MethodInsnNode call && call.owner.equals("com/tom/cpm/client/PlayerRenderManager") && (call.name.equals("bindSkin") || call.name.equals("getHolderSafe"))) {
    call.owner="com/tom/cpm/shared/model/render/ModelRenderManager";
   }
  }
  if(replaced==0)throw new AssertionError("Missing compiled material host seam: "+name);
  ClassWriter writer=new ClassWriter(0);node.accept(writer);
  Path path=output.resolve(name.replace('.','/')+".class");Files.createDirectories(path.getParent());Files.write(path,writer.toByteArray());
 }
}
