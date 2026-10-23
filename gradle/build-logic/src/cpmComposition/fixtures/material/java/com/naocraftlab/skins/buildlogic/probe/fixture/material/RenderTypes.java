package com.naocraftlab.skins.buildlogic.probe.fixture.material;
import net.minecraft.resources.Identifier;
public class RenderTypes {
 public static RenderType last;
 public static RenderType armorCutoutNoCull(Identifier texture){return last=new RenderType(texture,false);}
 public static RenderType armorCutoutNoCullGlint(Identifier texture){return last=new RenderType(texture,true);}
}
