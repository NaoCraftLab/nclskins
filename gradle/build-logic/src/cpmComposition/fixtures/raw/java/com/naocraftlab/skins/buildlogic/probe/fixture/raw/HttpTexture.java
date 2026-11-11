package com.naocraftlab.skins.buildlogic.probe.fixture.raw;
import com.naocraftlab.skins.buildlogic.probe.fixture.raw.NativeImage;
import com.naocraftlab.skins.buildlogic.probe.fixture.raw.ResourceLocation;
public class HttpTexture extends net.minecraft.client.renderer.texture.SimpleTexture {
 private final boolean processLegacySkin; private final java.io.File file;
 public HttpTexture(java.io.File file,String url,ResourceLocation fallback,boolean legacy,Runnable done){super(null);this.file=file;this.processLegacySkin=legacy;if(!url.startsWith("nclskins-local://"))throw new AssertionError("Unexpected HTTP source");}
 @Override public void load(net.minecraft.server.packs.resources.ResourceManager resources){Minecraft.getInstance().execute(()->{throw new AssertionError("Raw-capture fixture excludes native fallback");});}
 private void upload(NativeImage image){throw new AssertionError("Raw-capture fixture excludes native upload");}
 public void loadFixture() throws Exception {try(var in=new java.io.FileInputStream(file);var image=NativeImage.read(in)){processLegacySkin(image);}}
 private NativeImage processLegacySkin(NativeImage image){image.setPixelRGBA(0,0,0xffffffff);return image;}
}
