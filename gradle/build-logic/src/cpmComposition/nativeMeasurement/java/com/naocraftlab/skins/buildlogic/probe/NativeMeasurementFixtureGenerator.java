package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import com.tom.cpl.util.Image;
import static com.naocraftlab.skins.buildlogic.probe.PipMaterialProbe.*;

public final class NativeMeasurementFixtureGenerator {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]);Files.createDirectories(output);
        CpmBehaviorProbe.initializeHost(output.resolve("config.json").toFile());
        set(CpmNeutralGeometryProbe.class,"config",null,get(CpmBehaviorProbe.class,"config",null));
        set(CpmNeutralGeometryProbe.class,"tags",null,get(CpmBehaviorProbe.class,"tags",null));
        Image image=(Image)call(CpmCurrentNativeGeometryProbe.class,"fixture",new Class<?>[]{boolean.class,boolean.class,boolean.class,boolean.class,float.class,float.class},null,true,true,true,false,1F,0F);
        var raster=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        raster.setRGB(0,0,64,64,image.getData(),0,64);
        check(javax.imageio.ImageIO.write(raster,"PNG",output.resolve("authored-jump-decor-equipment.png").toFile()),"genuine CPM exporter synthetic carrier encoded");
        com.naocraftlab.skins.probe.EarsProbe.writeFixture(output.resolve("ears.png"),com.unascribed.ears.api.features.EarsFeatures.EarMode.TALL);
        com.naocraftlab.skins.probe.EarsProbe.writeFixture(output.resolve("combined.png"),com.unascribed.ears.api.features.EarsFeatures.EarMode.TALL,
                Files.readAllBytes(output.resolve("authored-jump-decor-equipment.png")));
    }
}
