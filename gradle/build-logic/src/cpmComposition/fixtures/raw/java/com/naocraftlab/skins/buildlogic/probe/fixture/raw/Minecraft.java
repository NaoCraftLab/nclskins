package com.naocraftlab.skins.buildlogic.probe.fixture.raw;
public final class Minecraft {
 private static final Minecraft INSTANCE=new Minecraft();
 private final com.naocraftlab.skins.buildlogic.probe.fixture.raw.TextureManager textures=new com.naocraftlab.skins.buildlogic.probe.fixture.raw.TextureManager();
 public static Minecraft getInstance(){return INSTANCE;}
 public com.naocraftlab.skins.buildlogic.probe.fixture.raw.TextureManager getTextureManager(){return textures;}
 public boolean isSameThread(){return true;}
}
