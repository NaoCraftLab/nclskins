package com.naocraftlab.skins.buildlogic.probe.fixture.raw;
import com.naocraftlab.skins.buildlogic.probe.fixture.raw.ResourceLocation;
public final class DefaultPlayerSkin {
 public static ResourceLocation getDefaultSkin(){return getDefaultSkin(null);}
 public static ResourceLocation getDefaultSkin(java.util.UUID id){return ResourceLocation.fromNamespaceAndPath("fixture","fallback");}
 public static PlayerSkin get(java.util.UUID id){return new PlayerSkin();}
}
