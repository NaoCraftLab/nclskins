package com.naocraftlab.skins.buildlogic.probe;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.*;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.api.*;
import com.mojang.renderpearl.frontend.*;
import it.unimi.dsi.fastutil.objects.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class CompileHandleProbe {
    static final Map<String,Integer> operations=new TreeMap<>();
    static final List<Handle> handles=new ArrayList<>();
    static final List<Map<String,Object>> metadata=new ArrayList<>();
    static final List<String> compileStacks=new ArrayList<>();
    static int compileCount,finishCount,sourceReads,sourceCloses,asyncRuns;
    static Thread renderThread;
    static final class Handle implements BackendRenderPipeline {
        final RenderPipeline pipeline;
        int closes;
        Handle(RenderPipeline pipeline){this.pipeline=pipeline;}
        public boolean isClosed(){return closes!=0;}
        public void close(){check(++closes==1,"exact-once compiled handle close");}
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static String signature(Method m){return m.getDeclaringClass().getName()+"."+m.getName()+org.objectweb.asm.Type.getMethodDescriptor(m);}
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler handler){return(T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    static Object get(String name)throws Exception{var field=RenderSystem.class.getDeclaredField(name);field.setAccessible(true);return field.get(null);}
    static void set(String name,Object value)throws Exception{var field=RenderSystem.class.getDeclaredField(name);field.setAccessible(true);field.set(null,value);}
    static String omittedUniform="";
    static CompletableFuture<CompiledRenderPipeline.Pending> compile(RenderPipeline pipeline,ShaderSource source,Executor executor){
        check(Thread.currentThread()==renderThread,"native cache requests on render thread");
        compileCount++;
        compileStacks.add(String.join("\n",Arrays.stream(Thread.currentThread().getStackTrace()).map(Object::toString).toList()));
        return CompletableFuture.supplyAsync(()->{
            synchronized(CompileHandleProbe.class){asyncRuns++;}
            var uniforms=List.copyOf(BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts()));
            var indices=new Object2IntOpenHashMap<String>();indices.defaultReturnValue(-1);
            for(int index=0;index<uniforms.size();index++)if(!uniforms.get(index).name().equals(omittedUniform))check(indices.putIfAbsent(uniforms.get(index).name(),index)==-1,"public layout unique uniform names");
            var backend=new Handle(pipeline);
            var compiled=new FrontendRenderPipeline(pipeline.getLocation().toString(),backend,Collections.unmodifiableList(new ArrayList<>(pipeline.getVertexFormatBindings())),Object2IntMaps.unmodifiable(indices),uniforms,Collections.unmodifiableList(new ArrayList<>(pipeline.getColorTargetStates())),pipeline.wantsDepthTexture(),pipeline.pushConstantSize());
            synchronized(CompileHandleProbe.class){handles.add(backend);metadata.add(Map.of("pipeline",pipeline.getLocation().toString(),"uniforms",uniforms.stream().map(u->Map.of("name",u.name(),"type",u.type().name())).toList(),"cpuBindingIndices",new TreeMap<String,Integer>(indices),"vertexStrides",pipeline.getVertexFormatBindings().stream().map(v->v==null?null:v.getVertexSize()).toList(),"wantsDepth",pipeline.wantsDepthTexture(),"pushConstantSize",pipeline.pushConstantSize()));}
            return ()->{check(Thread.currentThread()==renderThread,"native finishCompile on requesting render thread");finishCount++;return compiled;};
        },executor);
    }
}
