package com.naocraftlab.skins.buildlogic.probe;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
public final class SubmissionCaptureSetup implements Opcodes {
 static Path output;
 static ClassNode load(String name)throws Exception{
  var n=new ClassNode();var file=output.resolve(name+".class");
  if(Files.isRegularFile(file)){new ClassReader(Files.readAllBytes(file)).accept(n,0);return n;}
  var resources=ClassLoader.getSystemResources(name+".class");
  while(resources.hasMoreElements()){var url=resources.nextElement();if(!url.getProtocol().equals("jar"))continue;try(var input=url.openStream()){new ClassReader(input).accept(n,0);}return n;}
  throw new AssertionError("missing pinned raw dependency "+name);
 }
 static void save(Path out,ClassNode n)throws Exception{var w=new ClassWriter(ClassWriter.COMPUTE_MAXS);n.accept(w);var f=out.resolve(n.name+".class");Files.createDirectories(f.getParent());Files.write(f,w.toByteArray());}
 public static net.minecraft.client.renderer.texture.TextureAtlas atlas(net.minecraft.client.resources.model.AtlasManager map,net.minecraft.resources.Identifier id){SubmissionCaptureBackend.check(map==null&&id.equals(net.minecraft.data.AtlasIds.ARMOR_TRIMS),"exact readonly armor-trim constructor atlas");return null;}
 static void readonly(String[] args)throws Exception{
  Path out=Path.of(args[0]);
  var collector=load("net/minecraft/client/gui/render/state/GuiRenderState");var collectorFlags=new TreeMap<String,Integer>();
  for(var method:collector.methods)for(var instruction:method.instructions.toArray())if(instruction instanceof FieldInsnNode field&&field.owner.equals("net/minecraft/SharedConstants")) {
   if(field.getOpcode()!=GETSTATIC||!field.desc.equals("Z")||!Set.of("DEBUG_RENDER_UI_LAYERING_RECTANGLES","DEBUG_SHUFFLE_UI_RENDERING_ORDER").contains(field.name))throw new AssertionError("unexpected collector diagnostic boundary");
   collectorFlags.merge(field.name,1,Integer::sum);field.owner=Type.getInternalName(CpmExtractionBodyProbe.SdkDebugFlags.class);
  }
  if(!collectorFlags.equals(Map.of("DEBUG_RENDER_UI_LAYERING_RECTANGLES",1,"DEBUG_SHUFFLE_UI_RENDERING_ORDER",1)))throw new AssertionError("exact collector diagnostic field census "+collectorFlags);
  save(out,collector);System.out.println("READONLY_JOINED_COLLECTOR debugScalars=2 native collector algorithms retained");
  var models=load("net/minecraft/client/renderer/feature/ModelFeatureRenderer");int shuffle=0;
  for(var method:models.methods)for(var instruction:method.instructions.toArray())if(instruction instanceof FieldInsnNode field&&field.owner.equals("net/minecraft/SharedConstants")) {
   if(field.getOpcode()!=GETSTATIC||!field.name.equals("DEBUG_SHUFFLE_MODELS")||!field.desc.equals("Z"))throw new AssertionError("unexpected model diagnostic boundary");
   method.instructions.set(field,new InsnNode(ICONST_0));shuffle++;
  }
  if(shuffle!=1)throw new AssertionError("exact model diagnostic field census "+shuffle);save(out,models);
  System.out.println("READONLY_JOINED_MODELS shuffle=0 count=1 native mesh algorithms retained");
  var n=load("net/minecraft/client/renderer/entity/EntityRendererProvider$Context");int count=0;
  for(var m:n.methods)if(m.name.equals("<init>"))for(var ins:m.instructions.toArray())if(ins instanceof MethodInsnNode c&&c.owner.equals("net/minecraft/client/resources/model/AtlasManager")&&c.name.equals("getAtlasOrThrow")&&c.desc.equals("(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/texture/TextureAtlas;")){c.setOpcode(INVOKESTATIC);c.owner=Type.getInternalName(SubmissionCaptureSetup.class);c.name="atlas";c.desc="(Lnet/minecraft/client/resources/model/AtlasManager;Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/texture/TextureAtlas;";count++;}
  if(count!=1)throw new AssertionError("exact readonly context atlas input "+count);save(out,n);System.out.println("EXACT_JOINED_CONTEXT atlas=1 constructor algorithms retained");
  n=load("net/minecraft/world/item/ItemStack");var ci=n.methods.stream().filter(m->m.name.equals("<clinit>")&&m.desc.equals("()V")).findFirst().orElseThrow();int empties=0;for(var ins:ci.instructions.toArray())if(ins instanceof MethodInsnNode c&&c.owner.equals(n.name)&&c.name.equals("<init>")&&c.desc.equals("(Ljava/lang/Void;)V"))empties++;if(empties!=1)throw new AssertionError("exact native EMPTY construction "+empties);ci.instructions.clear();ci.tryCatchBlocks.clear();ci.localVariables=null;ci.visitTypeInsn(NEW,n.name);ci.visitInsn(DUP);ci.visitInsn(ACONST_NULL);ci.visitMethodInsn(INVOKESPECIAL,n.name,"<init>","(Ljava/lang/Void;)V",false);ci.visitFieldInsn(PUTSTATIC,n.name,"EMPTY","Lnet/minecraft/world/item/ItemStack;");ci.visitInsn(RETURN);save(out,n);System.out.println("READONLY_JOINED_EMPTY original Void constructor retained; unused codecs/registry constants excluded; all instance methods unchanged");
  n=load("net/minecraft/TracingExecutor");int flags=0;for(var m:n.methods)if(m.name.equals("forName"))for(var ins:m.instructions.toArray())if(ins instanceof FieldInsnNode f&&f.getOpcode()==GETSTATIC&&f.owner.equals("net/minecraft/SharedConstants")&&f.name.equals("IS_RUNNING_IN_IDE")&&f.desc.equals("Z")){m.instructions.set(f,new InsnNode(ICONST_0));flags++;}if(flags!=1)throw new AssertionError("exact native IO diagnostic scalar "+flags);save(out,n);System.out.println("READONLY_JOINED_IO_FLAG IDE=0 count=1 genuine executor unchanged");
  var reads=new TreeMap<String,Integer>();for(String layer:List.of("HumanoidArmorLayer","CapeLayer","WingsLayer","EquipmentLayerRenderer")){var subject=load("net/minecraft/client/renderer/entity/layers/"+layer);for(var m:subject.methods)for(var ins:m.instructions.toArray())if(ins instanceof FieldInsnNode f&&f.owner.equals("net/minecraft/core/component/DataComponents")){if(f.getOpcode()!=GETSTATIC||!f.desc.equals("Lnet/minecraft/core/component/DataComponentType;"))throw new AssertionError("not readonly metadata "+f.name);reads.merge(f.name,1,Integer::sum);}}
  if(!reads.keySet().equals(Set.of("EQUIPPABLE","TRIM")))throw new AssertionError("native metadata closure "+reads);
  n=load("net/minecraft/core/component/DataComponents");ci=n.methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElseThrow();ci.instructions.clear();ci.tryCatchBlocks.clear();ci.localVariables=null;for(String field:reads.keySet()){ci.visitMethodInsn(INVOKESTATIC,Type.getInternalName(SubmissionCaptureSetup.class),"component","()Lnet/minecraft/core/component/DataComponentType;",false);ci.visitFieldInsn(PUTSTATIC,n.name,field,"Lnet/minecraft/core/component/DataComponentType;");}ci.visitInsn(RETURN);save(out,n);System.out.println("READONLY_JOINED_COMPONENTS "+reads+" native instance consumers retained");
 }
 public static net.minecraft.core.component.DataComponentType<?> component()throws Exception{return (net.minecraft.core.component.DataComponentType<?>)SubmissionCaptureBackend.allocate(Class.forName("net.minecraft.core.component.DataComponentType$Builder$SimpleType"));}

 public static java.net.URLConnection forbidden(java.net.URL url,java.net.Proxy proxy){throw new AssertionError("submission fixture network forbidden");}
 static void config(Path directory,String name,String namespace,List<String> mixins)throws Exception{
  Files.writeString(directory.resolve(name),new com.google.gson.Gson().toJson(Map.of("required",true,"package",namespace,"compatibilityLevel","JAVA_17","client",mixins,"injectors",Map.of("defaultRequire",1))));
 }
 public static void main(String[] args)throws Exception{
  output=Path.of(args[0]);Files.createDirectories(output);Path classes=Path.of(args[1]);
  for(String name:List.of("net/minecraft/client/model/Model","net/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer","net/minecraft/client/renderer/rendertype/RenderType","net/minecraft/client/renderer/rendertype/RenderTypes","net/minecraft/client/resources/model/EquipmentClientInfo","net/minecraft/client/resources/model/EquipmentClientInfo$LayerType"))save(output,load(name));
  readonly(args);
  config(classes,"submission-fit-binding.json","com.naocraftlab.skins.compat.client.identifier.submission.mixin",List.of("LivingEntityRendererFitMixin","BufferSourceFitMixin","PlayerModelFitMixin","CapeLayerFitMixin","WingsLayerFitMixin","PictureInPictureRendererDepthMixin","CachedOrthoProjectionDepthMixin"));
  config(classes,"submission-source-binding.json","com.naocraftlab.skins.compat.client.identifier.mixin",List.of("SkinTextureDownloaderProviderMixin","NativeImageSneakyPixelsMixin","TextureManagerRegistrationMixin","DynamicTextureRegistrationMixin"));
  org.spongepowered.asm.launch.MixinBootstrap.init();var phase=org.spongepowered.asm.mixin.MixinEnvironment.class.getDeclaredMethod("gotoPhase",org.spongepowered.asm.mixin.MixinEnvironment.Phase.class);phase.setAccessible(true);phase.invoke(null,org.spongepowered.asm.mixin.MixinEnvironment.Phase.DEFAULT);var environment=org.spongepowered.asm.mixin.MixinEnvironment.getDefaultEnvironment();environment.setSide(org.spongepowered.asm.mixin.MixinEnvironment.Side.CLIENT);var constructor=Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();constructor.setAccessible(true);constructor.newInstance();com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
  for(String config:List.of("cpm.mixins.json","cpm.mixins.shared.json","nclskins.cpm.mixins.json","submission-fit-binding.json","submission-source-binding.json"))org.spongepowered.asm.mixin.Mixins.addConfiguration(config);
  var transformer=(org.spongepowered.asm.mixin.transformer.IMixinTransformer)environment.getActiveTransformer();
  for(String name:List.of("net/minecraft/client/renderer/entity/player/AvatarRenderer","net/minecraft/client/renderer/entity/state/AvatarRenderState","net/minecraft/client/renderer/entity/LivingEntityRenderer","net/minecraft/client/model/player/PlayerModel","net/minecraft/client/model/geom/ModelPart","net/minecraft/client/renderer/entity/layers/CapeLayer","net/minecraft/client/renderer/entity/layers/WingsLayer","net/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer","net/minecraft/client/renderer/MultiBufferSource$BufferSource","net/minecraft/client/gui/render/pip/PictureInPictureRenderer","net/minecraft/client/renderer/CachedOrthoProjectionMatrixBuffer","net/minecraft/client/renderer/texture/SkinTextureDownloader","net/minecraft/client/renderer/texture/TextureManager","net/minecraft/client/renderer/texture/DynamicTexture","com/mojang/blaze3d/platform/NativeImage","com/tom/cpm/shared/animation/AnimationEngine","com/tom/cpm/shared/model/render/ModelRenderManager","com/tom/cpm/shared/definition/ModelDefinitionLoader","com/tom/cpm/shared/config/Player","com/tom/cpm/client/PlayerProfile")){
   var node=load(name);var writer=new ClassWriter(0);node.accept(writer);byte[] before=writer.toByteArray(),after=transformer.transformClass(environment,name.replace('/','.'),before);new ClassReader(after).accept(node=new ClassNode(),0);
   if(name.endsWith("SkinTextureDownloader")){int matches=0;for(var method:node.methods)for(var instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode call&&call.owner.equals("java/net/URL")&&call.name.equals("openConnection")){call.setOpcode(INVOKESTATIC);call.owner=Type.getInternalName(SubmissionCaptureSetup.class);call.name="forbidden";call.desc="(Ljava/net/URL;Ljava/net/Proxy;)Ljava/net/URLConnection;";matches++;}if(matches!=1)throw new AssertionError("exact native no-network leaf "+matches);}
   if(name.endsWith("SkinTextureDownloader")||name.endsWith("TextureManager")||name.endsWith("DynamicTexture")){writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(new org.objectweb.asm.commons.ClassRemapper(writer,new org.objectweb.asm.commons.SimpleRemapper("net/minecraft/client/Minecraft","com/naocraftlab/skins/buildlogic/probe/SubmissionCaptureBackend$SourceHost")));new ClassReader(writer.toByteArray()).accept(node=new ClassNode(),0);}
   save(output,node);
   for(var method:node.methods)for(var instruction:method.instructions.toArray()){String generated=instruction instanceof TypeInsnNode value?value.desc:instruction instanceof MethodInsnNode value?value.owner:null;if(generated!=null&&generated.startsWith("org/spongepowered/asm/synthetic/")){var bytes=transformer.generateClass(environment,generated.replace('/','.'));if(bytes==null)throw new AssertionError("missing generated current mixin adapter "+generated);var file=output.resolve(generated+".class");Files.createDirectories(file.getParent());Files.write(file,bytes);}}
  }
  for(String name:List.of("com/naocraftlab/skins/compat/client/identifier/IdentifierTextureRegistry","com/naocraftlab/skins/compat/client/identifier/IdentifierTextureRegistry$NativeTexture","com/naocraftlab/skins/compat/client/identifier/IdentifierNativeTextureBackend")){
   var node=new ClassNode();try(var input=ClassLoader.getSystemResourceAsStream(name+".class")){new ClassReader(input.readAllBytes()).accept(node,0);}var writer=new ClassWriter(0);node.accept(new org.objectweb.asm.commons.ClassRemapper(writer,new org.objectweb.asm.commons.SimpleRemapper("net/minecraft/client/Minecraft","com/naocraftlab/skins/buildlogic/probe/SubmissionCaptureBackend$SourceHost")));new ClassReader(writer.toByteArray()).accept(node=new ClassNode(),0);save(output,node);
  }
  var connection=(java.net.JarURLConnection)ClassLoader.getSystemResource("com/tom/cpm/client/PlayerRenderManager.class").openConnection();var archive=connection.getJarFile();boolean fabric=archive.getJarEntry("cpm.accesswidener")!=null;String access;try(var input=archive.getInputStream(archive.getJarEntry(fabric?"cpm.accesswidener":"META-INF/accesstransformer.cfg"))){access=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
  for(String name:List.of("net/minecraft/client/model/geom/ModelPart","net/minecraft/client/model/Model","net/minecraft/client/model/HumanoidModel","net/minecraft/client/model/player/PlayerModel","net/minecraft/client/model/player/PlayerCapeModel","net/minecraft/client/model/object/equipment/ElytraModel","net/minecraft/client/model/object/skull/SkullModel")){
   var node=load(name);for(String line:access.split("\\R")){var tokens=line.trim().split("\\s+");if(fabric){if(tokens.length<3||!tokens[2].equals(name))continue;if(tokens[1].equals("class"))node.access=(node.access&~(ACC_PRIVATE|ACC_PROTECTED|ACC_FINAL))|ACC_PUBLIC;else if(tokens[1].equals("field"))for(var field:node.fields)if(field.name.equals(tokens[3])&&field.desc.equals(tokens[4])){if(tokens[0].equals("mutable"))field.access&=~ACC_FINAL;else field.access=(field.access&~(ACC_PRIVATE|ACC_PROTECTED))|ACC_PUBLIC;}}else{if(tokens.length<2||!tokens[1].replace('.','/').equals(name))continue;if(tokens.length==2)node.access=(node.access&~(ACC_PRIVATE|ACC_PROTECTED|ACC_FINAL))|ACC_PUBLIC;else for(var field:node.fields)if(field.name.equals(tokens[2])){field.access=(field.access&~(ACC_PRIVATE|ACC_PROTECTED))|ACC_PUBLIC;if(tokens[0].equals("public-f"))field.access&=~ACC_FINAL;}}}save(output,node);
  }
  if(args.length>2&&args[2].equals("omit-vertices")) {
   String owner="com/naocraftlab/skins/compat/client/identifier/measurement/AvatarBodyCapture";
   var node=new ClassNode();try(var input=ClassLoader.getSystemResourceAsStream(owner+".class")){if(input==null)throw new AssertionError("actual capture owner present");new ClassReader(input.readAllBytes()).accept(node,0);}
   int omitted=0;
   for(var method:node.methods)if(method.name.equals("vertex")&&method.desc.equals("(FFF)V")) {
    method.instructions.clear();method.tryCatchBlocks.clear();method.localVariables=null;method.visitInsn(RETURN);omitted++;
   }
   if(omitted!=1)throw new AssertionError("exact single captured vertex mutation "+omitted);save(output,node);System.out.println("PREPARED omitted capture vertex only; genuine mesh producer retained");
  }
  System.out.println("PREPARED current submission root/mesh hooks; genuine native producers; CPU effects remain separate");
 }
}
