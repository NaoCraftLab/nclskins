package com.naocraftlab.skins.buildlogic.probe;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.api.device.*;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.api.*;
import com.mojang.renderpearl.frontend.*;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public final class PipCpuBackend {
    static final Map<String,Integer> operations=new TreeMap<>();
    static final List<Texture> textures=new ArrayList<>();
    static final List<View> views=new ArrayList<>();
    static final List<Buffer> buffers=new ArrayList<>();
    static final List<Fence> nativeFences=new ArrayList<>();
    static final List<DrawRecord> drawRecords=new ArrayList<>();
    static int draws;
    static Thread thread;
    static Runnable acquisition=()->{};
    record DrawRecord(GpuTextureView target,List<GpuTextureView> sampled,List<Object> arguments){}
    static final class Fence implements GpuFence {
        boolean signaled,closed;
        public void close(){check(!closed,"exactly-once fence close");closed=true;}
        public boolean awaitCompletion(long timeout){return signaled;}
    }
    static void signal(){for(var f:nativeFences)f.signaled=true;RenderSystem.executePendingTasks();}
    static void check(boolean value,String message){CompileHandleProbe.check(value,message);}
    static <T>T proxy(Class<T> type,InvocationHandler handler){return CompileHandleProbe.proxy(type,handler);}
    static void count(Method m){check(Thread.currentThread()==thread,"terminal render-thread confinement");operations.merge(CompileHandleProbe.signature(m),1,Integer::sum);}
    static Object unknown(Method m){throw new AssertionError("unadmitted CPU device effect "+CompileHandleProbe.signature(m));}
    static Object allocate(Class<?> type)throws Exception{var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);return ((sun.misc.Unsafe)field.get(null)).allocateInstance(type);}
    static void set(Class<?> type,String name,Object target,Object value)throws Exception{var field=type.getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    public static GpuDevice device(){
        try{
            thread=Thread.currentThread();CompileHandleProbe.renderThread=thread;
            var backend=proxy(GpuDeviceBackend.class,(p,m,a)->{
                count(m);
                return switch(m.getName()){
                    case "getDeviceInfo"->new DeviceInfo("owned bounded CPU backend","fixture","no driver",true,"CPU",1,new DeviceLimits(16,256,16384,1L<<30,1024,8,1024),new DeviceFeatures(false,false,false,false,false,false,false,false),Set.of(),new HintsAndWorkarounds(false,false,false,false),null);
                    case "createTexture"->{var t=new Texture((int)a[1],(String)a[0],(GpuFormat)a[2],(int)a[3],(int)a[4],(int)a[5],(int)a[6]);textures.add(t);yield t;}
                    case "createTextureView"->new View((GpuTexture)a[0],(int)a[1],(int)a[2]);
                    case "createBuffer"->{var b=new Buffer((int)a[1],a[2] instanceof ByteBuffer bytes?bytes.remaining():((Number)a[2]).longValue());if(a[2] instanceof ByteBuffer bytes)b.data.put(bytes.duplicate()).clear();buffers.add(b);yield b;}
                    case "createSampler"->new Sampler((AddressMode)a[0],(AddressMode)a[1],(FilterMode)a[2],(FilterMode)a[3],(int)a[4],(OptionalDouble)a[5]);
                    case "isDebuggingEnabled"->false;
                    case "getLastDebugMessages"->List.of();
                    case "close"->null;
                    default->unknown(m);
                };
            });
            var frontend=(FrontendGpuDevice)allocate(FrontendGpuDevice.class);
            check(FrontendGpuDevice.STRICT_VALIDATION&&StrictIdeInput.reads==1&&StrictIdeInput.readThread==thread,"parent-admitted IDE=true exact native strict initializer on requesting render thread");
            check(System.getProperty("neoforge.disableBlaze3DValidation")==null,"no validation disable property");
            set(FrontendGpuDevice.class,"backend",frontend,backend);
            set(FrontendGpuDevice.class,"encoder",frontend,new FrontendCommandEncoder(null,backend,encoder()));
            return proxy(GpuDevice.class,(p,m,a)->{
                if(m.getName().equals("compilePipeline")){count(m);return CompileHandleProbe.compile((RenderPipeline)a[0],(ShaderSource)a[1],(Executor)a[2]);}
                if(m.getName().equals("createSurface")||m.getName().equals("close"))return unknown(m);
                try{return m.invoke(frontend,a);}catch(InvocationTargetException e){throw e.getCause();}
            });
        }catch(Exception e){throw new AssertionError(e);}
    }
    static CommandEncoderBackend encoder(){return proxy(CommandEncoderBackend.class,(p,m,a)->{
        count(m);
        return switch(m.getName()){
            case "copyToBuffer"->{try(var src=((GpuBufferSlice)a[0]).map(true,false);var dst=((GpuBufferSlice)a[1]).map(false,true)){dst.data().put(src.data());}yield null;}
            case "writeToBuffer"->{try(var dst=((GpuBufferSlice)a[0]).map(false,true)){dst.data().put(((ByteBuffer)a[1]).duplicate());}yield null;}
            case "createFence"->{var f=new Fence();nativeFences.add(f);yield f;}
            case "clearColorAndDepthTextures","clearColorTexture","clearDepthTexture","writeToTexture","copyBufferToTexture","submit","submitRenderPass"->null;
            case "createRenderPass"->pass((RenderPassDescriptor)a[0]);
            default->unknown(m);
        };
    });}
    static RenderPassBackend pass(RenderPassDescriptor descriptor){
        var uniforms=new TreeMap<Integer,Object>();var current=new CompileHandleProbe.Handle[1];
        return proxy(RenderPassBackend.class,(p,m,a)->{
            count(m);
            switch(m.getName()){
                case "setPipeline"->{check(a[0] instanceof CompileHandleProbe.Handle&&!((CompileHandleProbe.Handle)a[0]).isClosed(),"genuine frontend forwards exact owned typed live backend handle");current[0]=(CompileHandleProbe.Handle)a[0];uniforms.clear();}
                case "setUniform"->{check(current[0]!=null,"typed pipeline precedes uniform");int index=(int)a[0];var declarations=BindGroupLayout.flattenUniforms(current[0].pipeline.getBindGroupLayouts());check(index>=0&&index<declarations.size(),"CPU descriptor index derived only from genuine public flattened uniform layout");Object value=a[1];if(value instanceof com.mojang.renderpearl.util.TextureViewAndSampler pair){check(declarations.get(index).type()==UniformType.COMBINED_IMAGE_SAMPLER&&!pair.view().isClosed()&&!pair.view().texture().isClosed()&&!pair.sampler().isClosed(),"actual public descriptor type matches live selected native material");if(declarations.get(index).name().equals("Sampler0"))acquisition.run();}uniforms.put(index,value);}
                case "drawIndexed","draw"->{check(current[0]!=null,"draw typed pipeline");var declared=BindGroupLayout.flattenUniforms(current[0].pipeline.getBindGroupLayouts());for(int i=0;i<declared.size();i++)if(!uniforms.containsKey(i))throw new IllegalStateException("CPU public-layout binding missing: "+declared.get(i).name());var sampled=uniforms.values().stream().filter(com.mojang.renderpearl.util.TextureViewAndSampler.class::isInstance).map(com.mojang.renderpearl.util.TextureViewAndSampler.class::cast).map(com.mojang.renderpearl.util.TextureViewAndSampler::view).toList();draws++;drawRecords.add(new DrawRecord(descriptor.colorAttachments().get(0).textureView(),sampled,Arrays.asList(a)));}
                case "setVertexBuffer","setIndexBuffer","enableScissor","disableScissor","pushDebugGroup","popDebugGroup"->{}
                default->unknown(m);
            }
            return null;
        });
    }
    public static final class Texture implements GpuTexture {
        final int usage,w,h,layers,mips;final String label;final GpuFormat format;int closes;boolean closed;
        Texture(int usage,String label,GpuFormat format,int w,int h,int layers,int mips){this.usage=usage;this.label=label;this.format=format;this.w=w;this.h=h;this.layers=layers;this.mips=mips;}
        public int getWidth(int mip){return Math.max(1,w>>mip);}public int getHeight(int mip){return Math.max(1,h>>mip);}public int getDepthOrLayers(){return layers;}public int getMipLevels(){return mips;}public GpuFormat getFormat(){return format;}public int usage(){return usage;}public String getLabel(){return label;}
        public void close(){check(++closes==1,"texture close once");closed=true;}public boolean isClosed(){return closed;}
    }
    public static final class View implements GpuTextureView {
        final GpuTexture texture;final int base,levels;int closes;boolean closed;
        View(GpuTexture texture,int base,int levels){this.texture=texture;this.base=base;this.levels=levels;views.add(this);}
        public GpuTexture texture(){return texture;}public int baseMipLevel(){return base;}public int mipLevels(){return levels;}public int getWidth(int mip){return texture.getWidth(base+mip);}public int getHeight(int mip){return texture.getHeight(base+mip);}
        public void close(){check(++closes==1,"view close once");closed=true;}public boolean isClosed(){return closed;}
    }
    public static final class Buffer extends com.mojang.renderpearl.backend.common.BaseGpuBuffer {
        final ByteBuffer data;final int usage;boolean closed;
        Buffer(int usage,long size){super(usage,size);this.usage=usage;data=ByteBuffer.allocateDirect(Math.toIntExact(size)).order(ByteOrder.nativeOrder());}
        public long size(){return data.capacity();}public int usage(){return usage;}public boolean isClosed(){return closed;}public void close(){closed=true;}
        public GpuBufferSlice.MappedView map(long offset,long length,boolean read,boolean write){check(!closed,"live CPU buffer");var bytes=data.duplicate().order(ByteOrder.nativeOrder());bytes.position(Math.toIntExact(offset));bytes.limit(Math.toIntExact(offset+length));return new GpuBufferSlice.MappedView(slice(offset,length),bytes.slice().order(ByteOrder.nativeOrder()),()->{});}
    }
    public static final class Sampler implements GpuSampler {
        final AddressMode u,v;final FilterMode min,mag;final int anisotropy;final OptionalDouble lod;boolean closed;
        Sampler(AddressMode u,AddressMode v,FilterMode min,FilterMode mag,int anisotropy,OptionalDouble lod){this.u=u;this.v=v;this.min=min;this.mag=mag;this.anisotropy=anisotropy;this.lod=lod;}
        public AddressMode getAddressModeU(){return u;}public AddressMode getAddressModeV(){return v;}public FilterMode getMinFilter(){return min;}public FilterMode getMagFilter(){return mag;}public int getMaxAnisotropy(){return anisotropy;}public OptionalDouble getMaxLod(){return lod;}public void close(){closed=true;}public boolean isClosed(){return closed;}
    }
}
