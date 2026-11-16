package com.naocraftlab.skins.probe;

import java.nio.file.*;
import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import diy.y2k.five.thermite.EarsTexture;

public final class PipNativeInputs {
    public static EarsJoinBridge.Processed process(Path path)throws Exception{
        var registry=EarsProbe.start(Files.readAllBytes(path));var id=EarsProbe.source;
        if(NativePlayerSkinLifecycle.isReady(id.toString()))throw new AssertionError("changed native source initially pending");
        EarsProbe.queued().run();var texture=(EarsTexture)EarsProbe.Host.INSTANCE.textures.map.get(id);var observation=EarsProbe.observe(id);
        if(observation==null||!observation.current()||!NativePlayerSkinLifecycle.isReady(id.toString()))throw new AssertionError("changed native source genuinely processed and owned");
        return new EarsJoinBridge.Processed(registry,texture,id,observation);
    }
    public static void select(EarsJoinBridge.Processed input){EarsProbe.source=input.id();EarsJoinBridge.use(input,false);if(!input.current())throw new AssertionError("selected exact native input is no longer owned/current");}
}
