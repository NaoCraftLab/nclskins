package com.naocraftlab.skins.buildlogic.probe.fixture.raw;
import com.naocraftlab.skins.buildlogic.probe.fixture.raw.ResourceLocation;
public final class TextureManager {
 public final java.util.Map<ResourceLocation,AbstractTexture> textures=new java.util.HashMap<>();
 public void register(ResourceLocation location,AbstractTexture texture){textures.put(location,texture);}
 public ResourceLocation register(String name,DynamicTexture texture){var location=ResourceLocation.fromNamespaceAndPath("fixture",name);register(location,texture);return location;}
 public void release(ResourceLocation location){var texture=textures.remove(location);if(texture!=null)texture.close();}
}
