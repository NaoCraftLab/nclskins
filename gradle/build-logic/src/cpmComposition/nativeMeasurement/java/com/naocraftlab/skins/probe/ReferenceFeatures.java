package com.naocraftlab.skins.probe;

import com.unascribed.ears.api.features.EarsFeatures;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

public final class ReferenceFeatures {
    public static String remove="";
    public static EarsFeatures features(AvatarRenderState state){
        var features=EarsJoinBridge.features(state);
        if(remove.equals("body-live"))return EarsFeatures.copyOf(features).capeEnabled(false).build();
        if(remove.equals("wing"))return EarsFeatures.copyOf(features).wingMode(EarsFeatures.WingMode.NONE).build();
        if(remove.equals("tail"))return EarsFeatures.copyOf(features).tailMode(EarsFeatures.TailMode.NONE).build();
        if(remove.equals("ear"))return EarsFeatures.copyOf(features).earMode(EarsFeatures.EarMode.NONE).build();
        return features;
    }
    public static EarsFeatures selectedFeatures(AvatarRenderState state){
        EarsProbe.require(EarsProbe.drawingObservation!=null&&EarsProbe.drawingObservation.current()&&EarsProbe.drawingObservation.id().equals(state.skin.body().texturePath()),"exact source owned processed Ears texture at full native delegate");
        return features(state);
    }
}
