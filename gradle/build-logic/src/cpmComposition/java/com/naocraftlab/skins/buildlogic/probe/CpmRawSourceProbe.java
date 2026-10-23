package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.runtime.CapeProjection;
import java.nio.file.*;
import java.util.*;

public final class CpmRawSourceProbe {
 public static void main(String[] args) throws Exception {
  CpmBehaviorProbe.main(new String[]{args[2],args[1]});
  String family=args[0];
  Class<?> registryType=Class.forName("com.naocraftlab.skins.compat.client.resourcelocation."+family+".ResourceLocationTextureRegistry");
  Class<?> bridge=Class.forName("com.naocraftlab.skins.compat.client.resourcelocation."+family+".RegisteredSkinTexture");
  Object registry=registryType.getConstructor().newInstance();
  UUID subject=new UUID(0,1), draft=new UUID(0,2);
  int[] captures={0};
  try(var events=CapeProjection.installEvents(new CapeProjection.Events(){
   public void trackedPlayer(UUID id,String name){} public void playerInfoUpdated(UUID id,String name){} public void untrackedPlayer(UUID id){} public void worldChanged(){} public void worldEntered(){}
   public void skinTextureReady(String location,int[] pixels,long revision){captures[0]++;pixels[0]=0;}
  })) {
   byte[] bytes=Files.readAllBytes(Path.of(args[1],"classic-a.png"));
   var handle=(TextureRegistry.TextureHandle)registryType.getMethod("registerSkin",String.class,byte[].class).invoke(registry,"a".repeat(64),bytes);
   check(!NativePlayerSkinLifecycle.isReady(handle.location()),"local skin remains pending before upload");
   Class<?> mc=Class.forName("net.minecraft.client.Minecraft"); Object host=mc.getMethod("getInstance").invoke(null);
   Object manager=mc.getMethod("getTextureManager").invoke(host);
   var textures=(Map<?,?>)manager.getClass().getField("textures").get(manager);
   Object texture=textures.values().iterator().next();
   texture.getClass().getMethod("loadFixture").invoke(texture);
   check(captures[0]==1,"compiled direct registration must capture raw pixels for its exact location");
   NativeTextureUploadTracker.uploaded(texture);
   check(NativePlayerSkinLifecycle.isReady(handle.location()),"upload transitions to ready");
   var world=CapeProjection.effectiveSkin(subject,subject,handle.location(),SkinModel.CLASSIC,false);
   var preview=CapeProjection.effectiveSkin(draft,subject,handle.location(),SkinModel.CLASSIC,true);
   var png=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
   check(world.pixels()!=null && Arrays.equals(world.pixels(),png.getRGB(0,0,64,64,null,0,64)),"capture precedes preprocessing and event consumers cannot mutate it");
   CpmBehaviorProbe.assertDecodedSource(world);
   CpmBehaviorProbe.assertDecodedSource(preview);
   int[] copy=world.pixels();copy[0]=0;
   check(preview.pixels()[0]==png.getRGB(0,0),"preview receives independent immutable source pixels");
   registryType.getMethod("release",TextureRegistry.TextureHandle.class).invoke(registry,handle);
   var replacement=(TextureRegistry.TextureHandle)registryType.getMethod("registerSkin",String.class,byte[].class).invoke(registry,"a".repeat(64),bytes);
   Object next=textures.values().iterator().next();
   check(next!=texture && replacement.location().equals(handle.location()) && !NativePlayerSkinLifecycle.isReady(replacement.location()),"same-location re-registration owns a new pending native lifetime");
   next.getClass().getMethod("loadFixture").invoke(next);
   NativeTextureUploadTracker.uploaded(next);
   var replacementWorld=CapeProjection.effectiveSkin(subject,subject,replacement.location(),SkinModel.CLASSIC,false);
   var replacementPreview=CapeProjection.effectiveSkin(draft,subject,replacement.location(),SkinModel.CLASSIC,true);
   check(!world.active() && !preview.active() && replacementWorld.pixels()!=null && replacementPreview.pixels()!=null,"new capture retires both old sources at the same location");
   CpmBehaviorProbe.assertDecodedSource(replacementWorld);
   CpmBehaviorProbe.assertDecodedSource(replacementPreview);
   registryType.getMethod("release",TextureRegistry.TextureHandle.class).invoke(registry,replacement);
   Path file=Files.createTempFile("cpm-official-fixture",".png");Files.write(file,bytes);
   try {
    Class<?> location=Class.forName("net.minecraft.resources.ResourceLocation");
    Object id=location.getMethod("fromNamespaceAndPath",String.class,String.class).invoke(null,"fixture","official");
    Object official=texture.getClass().getConstructor(java.io.File.class,String.class,location,boolean.class,Runnable.class).newInstance(file.toFile(),"nclskins-local://fixture",id,true,(Runnable)()->{});
    bridge.getMethod("nclskins$registeredSkinLocation",location).invoke(official,id);
    official.getClass().getMethod("loadFixture").invoke(official);
    var officialSource=CapeProjection.effectiveSkin(subject,subject,id.toString(),SkinModel.SLIM,false);
    check(!replacementWorld.active() && officialSource.pixels()!=null,"official source replaces local without stale source reuse");
    CpmBehaviorProbe.assertDecodedSource(officialSource);
    CapeProjection.retireSkins();
    check(!replacementPreview.active() && !officialSource.active(),"reload retires world and synthetic preview sources");
   } finally {Files.delete(file);}
  } finally {registryType.getMethod("close").invoke(registry);}
  System.out.println("Compiled "+family+" local/official raw capture: pending/ready, private pixels, replacement and reload passed");
 }
 private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
