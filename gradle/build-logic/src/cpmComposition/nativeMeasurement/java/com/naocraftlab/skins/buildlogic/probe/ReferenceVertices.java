package com.naocraftlab.skins.buildlogic.probe;

import java.util.*;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.model.Model;

public final class ReferenceVertices implements VertexConsumer {
    public static String phase="BODY";
    static final Map<Model<?>,String> models=new IdentityHashMap<>();
    public static final Map<String,Integer> counts=new TreeMap<>();
    final VertexConsumer delegate;
    final String category;
    ReferenceVertices(VertexConsumer delegate,String category){this.delegate=delegate;this.category=category;}
    public static void captureFallback(Model<?> model){models.put(model,"FALLBACK_"+phase);}
    public static void capture(Model<?> model){models.put(model,"CPM_"+phase);}
    public static void enter(String part){phase=part;}
    public static void leave(){phase="BODY";}
    public static VertexConsumer wrap(Model<?> model,VertexConsumer delegate){
        String category=models.getOrDefault(model,model.getClass().getSimpleName());return new ReferenceVertices(delegate,category);
    }
    public VertexConsumer addVertex(float x,float y,float z){counts.merge(category,1,Integer::sum);delegate.addVertex(x,y,z);return this;}
    public VertexConsumer setColor(int r,int g,int b,int a){delegate.setColor(r,g,b,a);return this;}
    public VertexConsumer setColor(int c){delegate.setColor(c);return this;}
    public VertexConsumer setUv(float u,float v){delegate.setUv(u,v);return this;}
    public VertexConsumer setUv1(int u,int v){delegate.setUv1(u,v);return this;}
    public VertexConsumer setUv2(int u,int v){delegate.setUv2(u,v);return this;}
    public VertexConsumer setUv3(float u,float v){NativeFixtureAbi.uv3(delegate,u,v);return this;}
    public VertexConsumer setNormal(float x,float y,float z){delegate.setNormal(x,y,z);return this;}
    public VertexConsumer setLineWidth(float w){delegate.setLineWidth(w);return this;}
}
