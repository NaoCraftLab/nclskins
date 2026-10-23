package com.naocraftlab.skins.buildlogic.probe.fixture.material;
import net.minecraft.resources.Identifier;
import java.util.function.Function;
public class ModelTexture {
 private Identifier texture;
 public ModelTexture(Identifier texture){this.texture=texture;}
 public ModelTexture(Identifier texture,Function<?,?> renderType){this(texture);}
 public Identifier getTexture(){return texture;} public void setTexture(Identifier value){texture=value;}
}
