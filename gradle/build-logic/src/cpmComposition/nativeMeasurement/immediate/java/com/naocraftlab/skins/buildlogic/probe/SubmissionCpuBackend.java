package com.naocraftlab.skins.buildlogic.probe;

import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.systems.*;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import java.util.function.*;

public final class SubmissionCpuBackend {
    static final Map<String,Integer> operations=new TreeMap<>();
    static final List<Texture> textures=new ArrayList<>();
    static final List<View> views=new ArrayList<>();
    static final List<Buffer> buffers=new ArrayList<>();
    static int draws;
    record DrawRecord(GpuTextureView target,List<GpuTextureView> sampled,List<Object> arguments){}
    static final List<DrawRecord> drawRecords=new ArrayList<>();
    static final List<Fence> fences=new ArrayList<>();
    static final class Fence implements GpuFence {
        boolean signaled,closed;
        public void close(){check(!closed,"exactly-once fence close");closed=true;}
        public boolean awaitCompletion(long timeout){return signaled;}
    }
    static void signal(){for(var f:fences)f.signaled=true;RenderSystem.executePendingTasks();}
    static Runnable acquisition=()->{};
    static Thread thread;
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler handler){return(T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    static void count(Method m){check(Thread.currentThread()==thread,"effect terminal thread");operations.merge(m.getDeclaringClass().getName()+"."+m.getName()+org.objectweb.asm.Type.getMethodDescriptor(m),1,Integer::sum);}
    static GpuDevice device(){
        thread=Thread.currentThread();
        return new GpuDevice(proxy(GpuDeviceBackend.class,(p,m,a)->{
            count(m);
            return switch(m.getName()){
                case "createTexture"->{String label=a[0] instanceof Supplier<?> s?(String)s.get():(String)a[0];var t=new Texture((int)a[1],label,(TextureFormat)a[2],(int)a[3],(int)a[4],(int)a[5],(int)a[6]);textures.add(t);yield t;}
                case "createTextureView"->new View((GpuTexture)a[0],a.length==1?0:(int)a[1],a.length==1?((GpuTexture)a[0]).getMipLevels():(int)a[2]);
                case "createBuffer"->{var b=new Buffer((int)a[1],a[2] instanceof ByteBuffer bytes?bytes.remaining():((Number)a[2]).longValue());if(a[2] instanceof ByteBuffer bytes)b.data.put(bytes.duplicate()).clear();buffers.add(b);yield b;}
                case "createSampler"->new Sampler((AddressMode)a[0],(AddressMode)a[1],(FilterMode)a[2],(FilterMode)a[3],(int)a[4],(OptionalDouble)a[5]);
                case "createCommandEncoder"->encoder();
                case "getUniformOffsetAlignment"->256;
                case "getMaxTextureSize"->16384;
                case "getMaxSupportedAnisotropy"->1;
                case "getEnabledExtensions","getLastDebugMessages"->List.of();
                case "getImplementationInformation","getVendor","getBackendName","getVersion","getRenderer"->"CPU-effect-terminal";
                case "isDebuggingEnabled","isZZeroToOne"->false;
                case "close"->null;
                default->throw new AssertionError("unadmitted device operation "+m);
            };
        }));
    }
    static ByteBuffer bytes(GpuBufferSlice s){var b=(Buffer)s.buffer();check(!b.closed,"live native upload buffer");var bytes=b.data.duplicate().order(ByteOrder.nativeOrder());bytes.position(Math.toIntExact(s.offset()));bytes.limit(Math.toIntExact(s.offset()+s.length()));return bytes.slice().order(ByteOrder.nativeOrder());}
    static CommandEncoderBackend encoder(){boolean[] active={false};return proxy(CommandEncoderBackend.class,(p,m,a)->{
        count(m);
        return switch(m.getName()){
            case "copyToBuffer"->{bytes((GpuBufferSlice)a[1]).put(bytes((GpuBufferSlice)a[0]));yield null;}
            case "writeToBuffer"->{bytes((GpuBufferSlice)a[0]).put(((ByteBuffer)a[1]).duplicate());yield null;}
            case "mapBuffer"->{var s=a[0] instanceof GpuBuffer b?b.slice():(GpuBufferSlice)a[0];var b=bytes(s);yield new GpuBuffer.MappedView(){public ByteBuffer data(){return b;}public void close(){}};}
            case "createFence"->{var f=new Fence();fences.add(f);yield f;}
            case "clearColorAndDepthTextures","clearColorTexture","clearDepthTexture","writeToTexture"->null;
            case "createRenderPass"->{check(!active[0],"no nested backend pass");active[0]=true;yield pass((GpuTextureView)a[1],()->active[0]=false);}
            case "isInRenderPass"->active[0];
            default->throw new AssertionError("unadmitted encoder operation "+m);
        };
    });}
    static RenderPassBackend pass(GpuTextureView target,Runnable done){boolean[] closed={false};var sampled=new ArrayList<GpuTextureView>();return proxy(RenderPassBackend.class,(p,m,a)->{
        count(m);
        switch(m.getName()){
            case "bindTexture"->{var view=(GpuTextureView)a[1];check(view!=null&&!view.isClosed()&&!view.texture().isClosed(),"native material binding live");sampled.add(view);if("Sampler0".equals(a[0]))acquisition.run();}
            case "drawIndexed","draw"->{draws++;drawRecords.add(new DrawRecord(target,List.copyOf(sampled),List.of(a)));}
            case "setPipeline","setUniform","setVertexBuffer","setIndexBuffer","enableScissor","disableScissor","pushDebugGroup","popDebugGroup"->{}
            case "isClosed"->{return closed[0];}
            case "close"->{check(!closed[0],"backend pass close once");closed[0]=true;done.run();}
            default->throw new AssertionError("unadmitted pass operation "+m);
        }
        return null;
    });}
    public static final class Texture extends GpuTexture {
        int closes;boolean closed;
        Texture(int usage,String label,TextureFormat format,int w,int h,int layers,int mips){super(usage,label,format,w,h,layers,mips);}
        public void close(){check(++closes==1,"texture exact-once close");closed=true;}
        public boolean isClosed(){return closed;}
    }
    public static final class View extends GpuTextureView {
        int closes;boolean closed;
        View(GpuTexture t,int base,int levels){super(t,base,levels);views.add(this);}
        public void close(){check(++closes==1,"view exact-once close");closed=true;}
        public boolean isClosed(){return closed;}
    }
    public static final class Buffer extends GpuBuffer {
        final ByteBuffer data;boolean closed;
        Buffer(int usage,long size){super(usage,size);data=ByteBuffer.allocateDirect(Math.toIntExact(size)).order(ByteOrder.nativeOrder());}
        public boolean isClosed(){return closed;}
        public void close(){closed=true;}
    }
    public static final class Sampler extends GpuSampler {
        final AddressMode u,v;final FilterMode min,mag;final int anisotropy;final OptionalDouble lod;
        Sampler(AddressMode u,AddressMode v,FilterMode min,FilterMode mag,int anisotropy,OptionalDouble lod){this.u=u;this.v=v;this.min=min;this.mag=mag;this.anisotropy=anisotropy;this.lod=lod;}
        public AddressMode getAddressModeU(){return u;}public AddressMode getAddressModeV(){return v;}public FilterMode getMinFilter(){return min;}public FilterMode getMagFilter(){return mag;}public int getMaxAnisotropy(){return anisotropy;}public OptionalDouble getMaxLod(){return lod;}public void close(){}
    }
}
