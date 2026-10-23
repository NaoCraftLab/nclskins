package com.naocraftlab.skins.buildlogic.probe.fixture.raw;
public record ResourceLocation(String namespace,String path) {
 public static ResourceLocation fromNamespaceAndPath(String namespace,String path){return new ResourceLocation(namespace,path);}
 @Override public String toString(){return namespace+":"+path;}
}
