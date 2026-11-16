package com.naocraftlab.skins.buildlogic.probe.fixture.raw;
public record ResourceLocation(String namespace,String path) {
 public ResourceLocation(String value){this(value.substring(0,value.indexOf(':')),value.substring(value.indexOf(':')+1));}
 public static ResourceLocation parse(String value){return new ResourceLocation(value);}
 public static ResourceLocation fromNamespaceAndPath(String namespace,String path){return new ResourceLocation(namespace,path);}
 @Override public String toString(){return namespace+":"+path;}
}
