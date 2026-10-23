package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.compat.cpm.*;
import com.naocraftlab.skins.runtime.*;
import com.tom.cpl.math.Vec2i;
import com.tom.cpl.util.Image;
import com.tom.cpm.shared.animation.AnimationState;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.*;
import com.tom.cpm.shared.io.*;
import com.tom.cpm.shared.model.*;
import com.tom.cpm.shared.model.render.ModelRenderManager;
import com.tom.cpm.shared.parts.*;
import com.tom.cpm.shared.skin.*;
import java.util.*;
import java.io.*;

public final class CpmMaterialBoundaryProbe {
 public static ModelRenderManager<Object,Object,Object,Object> manager;
 public static void sharedSheets(ModelDefinitionLoader<?> loader,Player<?> player,CapeProjection.Registration registration) throws Exception {
  Object main=new Object(), child=new Object();
  int[] material={0};
  var shared=new ModelRenderManager<Object,Object,Object,Object>() {
   {for(Object model:List.of(main,child))holders.put(model,new RedirectHolder<>(this,model){
    protected void swapIn0(){} protected void swapOut0(){}
    protected void bindTexture(Object input,TextureProvider sheet){material[0]=sheet.getImage().getRGB(0,0);}
    protected void bindDefaultTexture(Object input,TextureSheetType type){material[0]=0;}
   });}
  };
  var previous=((CpmPlayerAccess)player).nclskins$source();
  UUID id=new UUID(0,81);
  CapeProjection.skinTextureReady("fixture:shared_sheets",new int[4096],new Object());
  var source=CapeProjection.effectiveSkin(id,id,"fixture:shared_sheets",SkinModel.CLASSIC,false);
  ((CpmPlayerAccess)player).nclskins$source(source);
  try {
   for(int marker:new int[]{0xff112233,0xff445566,0,-1}) {
    ModelDefinition def=marker==-1?null:loader.loadModel(payload(marker),player);
    if(def!=null){def.resolveAll();CpmAccessoryProbe.select(registration,player,source,def,true);check(def instanceof OwnedCpmDefinition && def.doRender(),"decoded sheet definition must render");shared.bindModel(main,null,null,def,player,new AnimationState());}
    shared.bindSubModel(main,child,null);
    material[0]=0;
    shared.bindSkin(child,new Object(),TextureSheetType.ELYTRA);
    check(material[0]==(marker==-1?0:marker),"submodel receives current decoded ELYTRA sheet or fallback");
    material[0]=0;
    shared.bindSkin(child,new Object(),TextureSheetType.CAPE);
    check(material[0]==(marker==-1?0:0xff778899),"CAPE sheet remains distinct");
    shared.unbindModel(child);shared.unbindModel(main);if(def!=null)def.cleanup();
   }
  } finally {((CpmPlayerAccess)player).nclskins$source(previous);CapeProjection.retireSkin(id);}
  System.out.println("Pinned shared decoded sheets: CAPE/ELYTRA submodel transfer, A/B/fallback passed");
 }

 public static void run(ModelDefinitionLoader<?> loader,Player<?> player,CapeProjection.Registration registration) throws Exception {
  Class<?> modelType=Class.forName("net.minecraft.client.model.Model");
  Class<?> identifier=Class.forName("net.minecraft.resources.Identifier");
  Class<?> texture=Class.forName("com.tom.cpm.client.ModelTexture");
  Class<?> layerType=Class.forName("net.minecraft.client.resources.model.EquipmentClientInfo$LayerType");
  Object wingsType=Enum.valueOf((Class)layerType,"WINGS"), armorType=Enum.valueOf((Class)layerType,"HUMANOID");
  Object fallback=identifier.getMethod("parse",String.class).invoke(null,"fixture:vanilla");
  var setTexture=texture.getMethod("setTexture",identifier);
  Object main=modelType.getConstructor().newInstance(), wings=modelType.getConstructor().newInstance(), cape=modelType.getConstructor().newInstance();
  manager=new ModelRenderManager<>() {
   {for(Object model:List.of(main,wings,cape))holders.put(model,new RedirectHolder<>(this,model){
    protected void swapIn0(){} protected void swapOut0(){}
    protected void bindTexture(Object material,TextureProvider sheet){
     try {setTexture.invoke(material,identifier.getMethod("parse",String.class).invoke(null,"fixture:sheet_"+Integer.toHexString(sheet.getImage().getRGB(0,0))));}
     catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
   });}
  };
  Class<?> equipment=Class.forName("net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer");
  Object renderer=equipment.getConstructor().newInstance();
  var render=Arrays.stream(equipment.getMethods()).filter(m->m.getName().equals("renderLayers") && m.getParameterCount()==11).findFirst().orElseThrow();
  var last=Class.forName("net.minecraft.client.renderer.rendertype.RenderTypes").getField("last");
  var chosen=Class.forName("net.minecraft.client.renderer.rendertype.RenderType").getMethod("texture");
  UUID worldId=new UUID(0,91), editorId=new UUID(0,92), providersId=new UUID(0,93);
  for(int pass=0;pass<3;pass++) {
   UUID cache=pass==0?worldId:pass==1?editorId:providersId;
   String location="fixture:material_"+pass;
   CapeProjection.skinTextureReady(location,new int[4096],new Object());
   EffectiveSkinSource source=CapeProjection.effectiveSkin(cache,worldId,location,SkinModel.CLASSIC,pass!=0);
   ((CpmPlayerAccess)player).nclskins$source(source);
   var appearance=new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle(location,64,64),SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.ELYTRA,OuterLayerVisibility.allVisible(),true,false,true);
   try(var frame=pass==0?null:CpmFrame.open(appearance)) {
    for(int marker:new int[]{0xff112233,0xff445566,0,-1}) {
     ModelDefinition definition=marker==-1?null:loader.loadModel(payload(marker),player);
     if(definition!=null){
      check(definition instanceof OwnedCpmDefinition && definition.getError()==null,"decoded definition must be owned and valid");
      definition.resolveAll();
      CpmAccessoryProbe.select(registration,player,source,definition,true);
      manager.bindModel(main,null,null,definition,player,new AnimationState());
     }
     manager.bindSubModel(main,wings,null);manager.bindSubModel(main,cape,null);
     Object capeMaterial=texture.getConstructor(identifier).newInstance(fallback);
     manager.bindSkin(cape,capeMaterial,TextureSheetType.CAPE);
     check(texture.getMethod("getTexture").invoke(capeMaterial).toString().equals(marker==-1?"fixture:vanilla":"fixture:sheet_ff778899"),"CAPE sheet remains independent");
     for (int glint = 0; glint <= (Boolean.getBoolean("nclskins.cpm.materialGlint") ? 1 : 0); glint++) {
      render.invoke(renderer,wingsType,null,wings,null,null,null,null,0,fallback,0,glint);
      String actual=chosen.invoke(last.get(null)).toString();
      String expected=(marker==0 || marker==-1)?"fixture:vanilla":"fixture:sheet_"+Integer.toHexString(marker);
      check(actual.equals(expected),"native material argument must select ELYTRA sheet after upstream local edit: "+actual+" != "+expected);
     }
     render.invoke(renderer,armorType,null,wings,null,null,null,null,0,fallback,0,0);
     check(chosen.invoke(last.get(null)).equals(fallback),"armor material remains vanilla");
     manager.unbindModel(wings);manager.unbindModel(cape);manager.unbindModel(main);
     if(definition!=null)definition.cleanup();
    }
   }
   CapeProjection.retireSkin(cache);
  }
  System.out.println("Decoded CAPE/ELYTRA -> bindSubModel -> transformed native material passed: world/Editor/Providers, A/B/ordinary, fallback and armor isolation");
 }
 public static com.naocraftlab.skins.core.png.PngValidator.CapePng preparedSneaky(boolean wings) throws Exception {
  var image=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);
  int[] marker={0xfffff42f,0xffffffff,0xff9c59d1,0xff292929};
  for(int index=0;index<marker.length;index++)image.setRGB(60,48+index,marker[index]);
  image.setRGB(56,16,0xff123456);
  if(wings)image.setRGB(0,0,0xffabcdef);
  var encoded=new ByteArrayOutputStream();
  javax.imageio.ImageIO.write(image,"png",encoded);
  byte[] source=encoded.toByteArray();
  var validator=new com.naocraftlab.skins.core.png.PngValidator();
  check(validator.projectStoredRender(source).featureEvidence()!=null,"Sneaky source passes actual stored projection");
  var prepared=new com.naocraftlab.skins.core.png.SneakyCapeDecoder().decode(source).orElseThrow();
  var size=validator.validate(prepared.bytes());
  check(size.width()==64 && size.height()==32 && prepared.hasElytra()==wings,"actual Sneaky preparation retains atlas and elytra classification");
  return prepared;
 }

 public static void sneakyPreview(ModelDefinitionLoader<?> loader,File fixtures) throws Exception {
  UUID id=new UUID(0,194);
  String location="fixture:sneaky_cpm_body";
  var source=CapeProjection.effectiveSkin(id,id,location,SkinModel.CLASSIC,true);
  var player=CpmBehaviorProbe.fixturePlayer(id);
  ((CpmPlayerAccess)player).nclskins$source(source);
  ModelDefinition definition;
  try(var input=new FileInputStream(new File(fixtures,"accessory-geometry-sheet.png"))) {
   definition=loader.loadModel(Image.loadFrom(input),player);
  }
  definition.resolveAll();
  player.setModelDefinition(java.util.concurrent.CompletableFuture.completedFuture(definition),false);
  player.getModelDefinition();
  check(definition instanceof OwnedCpmDefinition && definition.doRender() && source.accessories().present(),"independently confirmed owned CPM body and accessories");
  Object geometry=definition.getModelElementFor(PlayerModelParts.BODY), animations=definition.getAnimations();
  Object body=new Object(), accessory=new Object();
  int[] bindings={0};
  var manager=new ModelRenderManager<Object,Object,Object,Object>() {
   {for(Object model:List.of(body,accessory))holders.put(model,new RedirectHolder<>(this,model){
    protected void swapIn0(){} protected void swapOut0(){}
    protected void bindTexture(Object input,TextureProvider sheet){bindings[0]++;((String[])input)[0]="fixture:cpm_sheet";}
    protected void bindDefaultTexture(Object input,TextureSheetType type){bindings[0]++;((String[])input)[0]="fixture:cpm_default";}
   });}
  };
  var render=new RenderManager<>(manager,loader,value->null);
  try {
   for(boolean wings:new boolean[]{true,false}) {
    var prepared=preparedSneaky(wings);
    var atlas=new TextureRegistry.TextureHandle("fixture:sneaky_"+prepared.renderSha256(),64,32);
    for(var mode:List.of(PreviewRenderer.CapeMode.CAPE,PreviewRenderer.CapeMode.ELYTRA)) {
     for(boolean nativeBundle:new boolean[]{true,false,true,false}) {
      var appearance=new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle(location,64,64),SkinModel.CLASSIC,
       nativeBundle?Optional.empty():Optional.of(atlas),mode,OuterLayerVisibility.allVisible(),prepared.hasElytra(),false,nativeBundle);
      try(var frame=CpmFrame.open(appearance)) {
       check(CpmFrame.accepts((OwnedCpmDefinition)definition),"ordinary Sneaky appearance accepts the same confirmed CPM BODY");
       check(CpmIntegration.accessories(player,definition)==nativeBundle,"Sneaky never requests native CPM accessory override");
       manager.bindModel(body,null,null,definition,player,new AnimationState());
       render.bindElytra(body,accessory);
       check(manager.isBound(accessory)==nativeBundle,"actual transformed wings gate follows Sneaky override");
       for(var sheet:List.of(TextureSheetType.CAPE,TextureSheetType.ELYTRA)) {
        String selected=mode==PreviewRenderer.CapeMode.ELYTRA && !prepared.hasElytra()?"minecraft:textures/entity/elytra.png":atlas.location();
        String[] material={selected};
        bindings[0]=0;
        manager.bindSkin(body,material,sheet);
        check(bindings[0]==(nativeBundle?1:0),"exact transformed bindSkin suppresses prior CPM "+sheet);
        check(nativeBundle?!material[0].equals(selected):material[0].equals(selected),"Sneaky/default texture does not leak a previous CPM sheet");
       }
       bindings[0]=0;
       manager.bindSkin(body,new String[]{location},TextureSheetType.SKIN);
       check(bindings[0]==1 && manager.isBound(body),"CPM BODY skin binding remains accepted independently of accessories");
       check(definition.getModelElementFor(PlayerModelParts.BODY)==geometry && definition.getAnimations()==animations,"Sneaky preserves CPM body and animation identity");
       if(!nativeBundle)MinecraftCapeFallbackProbe.sneakyPreview(appearance);
       manager.unbindModel(accessory);
      }
      check(!manager.isBound(body),"frame retires BODY binding before next accessory selection");
     }
    }
   }
  } finally {
   manager.unbindModel(accessory);manager.unbindModel(body);
   definition.cleanup();CapeProjection.retireSkin(id);
  }
  System.out.println("Prepared Sneaky ordinary preview on confirmed CPM BODY passed: transformed CAPE/ELYTRA suppression, prior-sheet transitions and native default elytra; independent synthetic formats, not mixed-skin acceptance");
 }

 private static byte[] payload(int marker) throws Exception {
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();bytes.write(83);
  try(var checksum=new ChecksumOutputStream(bytes)) {
   IOHelper io=new IOHelper(checksum);
   io.writeObjectBlock(new ModelPartSkinType(SkinType.DEFAULT));
   for(TextureSheetType type:new TextureSheetType[]{TextureSheetType.CAPE,TextureSheetType.ELYTRA}) {
    if(type==TextureSheetType.ELYTRA && marker==0)continue;
    io.writeEnum(ModelPartType.TEXTURE);
    try(IOHelper block=io.writeNextBlock()) {
     block.writeEnum(type);
     Image image=new Image(64,32);image.fill(type==TextureSheetType.CAPE?0xff778899:marker);
     new TextureProvider(image,new Vec2i(64,32)).write(block);
    }
   }
   io.writeEnum(ModelPartType.END);try(var end=io.writeNextBlock()){}
  }
  return bytes.toByteArray();
 }
 private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
