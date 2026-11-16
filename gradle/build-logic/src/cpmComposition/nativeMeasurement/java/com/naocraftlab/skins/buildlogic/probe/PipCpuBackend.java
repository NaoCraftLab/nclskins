package com.naocraftlab.skins.buildlogic.probe;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.systems.*;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;
import java.util.function.*;

public final class PipCpuBackend {
    static final Map<String,Integer> operations=new TreeMap<>();
    static final List<Texture> textures=new ArrayList<>();
    static final List<View> views=new ArrayList<>();
    static final List<Buffer> buffers=new ArrayList<>();
    static int draws;
    record DrawRecord(GpuTextureView target,List<GpuTextureView> sampled,java.util.List<Object> arguments){}
    static final List<DrawRecord> drawRecords=new ArrayList<>();
    static final List<Fence> nativeFences=new ArrayList<>();
    static final class Fence implements GpuFence {boolean signaled,closed;public void close(){check(!closed,"exactly-once fence close");closed=true;}public boolean awaitCompletion(long timeout){return signaled;}}
    static void signal(){for(var f:nativeFences)f.signaled=true;RenderSystem.executePendingTasks();}
    static Runnable acquisition=()->{};
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler handler){return(T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    static void count(Method m){check(Thread.currentThread()==thread,"terminal render-thread confinement");String key=m.getDeclaringClass().getName()+"."+m.getName()+org.objectweb.asm.Type.getMethodDescriptor(m);operations.merge(key,1,Integer::sum);}
    static Thread thread;
    public static GpuDevice device(){
        thread=Thread.currentThread();
        var backend=proxy(GpuDeviceBackend.class,(p,m,a)->{
            count(m);
            return switch(m.getName()){
                case "getDeviceInfo"->new DeviceInfo("CPU terminal","fixture","no driver",true,"CPU",1,new DeviceLimits(16,256,16384,1L<<30,1024,8),null,Set.of(),null,null);
                case "createTexture"->{String label=a[0] instanceof Supplier<?> s?(String)s.get():(String)a[0];var t=new Texture((int)a[1],label,(GpuFormat)a[2],(int)a[3],(int)a[4],(int)a[5],(int)a[6]);textures.add(t);yield t;}
                case "createTextureView"->new View((GpuTexture)a[0],a.length==1?0:(int)a[1],a.length==1?((GpuTexture)a[0]).getMipLevels():(int)a[2]);
                case "createBuffer"->{var b=new Buffer((int)a[1],a[2] instanceof ByteBuffer bytes?bytes.remaining():((Number)a[2]).longValue());if(a[2] instanceof ByteBuffer bytes)b.data.put(bytes.duplicate()).clear();buffers.add(b);yield b;}
                case "createSampler"->new Sampler((AddressMode)a[0],(AddressMode)a[1],(FilterMode)a[2],(FilterMode)a[3],(int)a[4],(OptionalDouble)a[5]);
                case "createCommandEncoder"->encoder();
                case "close"->null;
                default->throw new AssertionError("unadmitted device terminal "+m);
            };
        });
        return new GpuDevice(backend,()->{throw new AssertionError("shader bootstrap forbidden");});
    }
    static CommandEncoderBackend encoder(){return proxy(CommandEncoderBackend.class,(p,m,a)->{
        count(m);
        return switch(m.getName()){
            case "copyToBuffer"->{var src=(GpuBufferSlice)a[0];var dst=(GpuBufferSlice)a[1];try(var s=src.map(true,false);var d=dst.map(false,true)){d.data().put(s.data());}yield null;}
            case "writeToBuffer"->{try(var d=((GpuBufferSlice)a[0]).map(false,true)){d.data().put(((ByteBuffer)a[1]).duplicate());}yield null;}
            case "createFence"->{var f=new Fence();nativeFences.add(f);yield f;}
            case "clearColorAndDepthTextures","clearColorTexture","clearDepthTexture","writeToTexture","copyBufferToTexture","submit","submitRenderPass"->null;
            case "createRenderPass"->pass((RenderPassDescriptor)a[0]);
            default->throw new AssertionError("unadmitted encoder terminal "+m);
        };
    });}
    static RenderPassBackend pass(RenderPassDescriptor descriptor){var sampled=new ArrayList<GpuTextureView>();return proxy(RenderPassBackend.class,(p,m,a)->{
        count(m);
        switch(m.getName()){
            case "bindTexture"->{var view=(GpuTextureView)a[1];check(view!=null&&!view.isClosed()&&!view.texture().isClosed(),"native material binding uses live owned texture");sampled.add(view);acquisition.run();}
            case "drawIndexed","draw"->{draws++;drawRecords.add(new DrawRecord(descriptor.colorAttachments().get(0).textureView(),List.copyOf(sampled),List.of(a)));}
            case "setPipeline","setUniform","setVertexBuffer","setIndexBuffer","enableScissor","disableScissor","pushDebugGroup","popDebugGroup"->{}
            default->throw new AssertionError("unadmitted render-pass terminal "+m);
        }
        return null;
    });}
    public static final class Texture extends GpuTexture {
        int closes;boolean closed;
        Texture(int usage,String label,GpuFormat format,int w,int h,int layers,int mips){super(usage,label,format,w,h,layers,mips);}
        public void close(){closes++;closed=true;}
        public boolean isClosed(){return closed;}
    }
    public static final class View extends GpuTextureView {
        int closes;boolean closed;
        View(GpuTexture t,int base,int levels){super(t,base,levels);views.add(this);}
        public void close(){closes++;closed=true;}
        public boolean isClosed(){return closed;}
    }
    public static final class Buffer extends GpuBuffer {
        final ByteBuffer data;boolean closed;
        Buffer(int usage,long size){super(usage,size);data=ByteBuffer.allocateDirect(Math.toIntExact(size)).order(ByteOrder.nativeOrder());}
        public boolean isClosed(){return closed;}
        public void close(){closed=true;}
        public GpuBufferSlice.MappedView map(long offset,long length,boolean read,boolean write){check(!closed,"live CPU buffer");var bytes=data.duplicate().order(ByteOrder.nativeOrder());bytes.position(Math.toIntExact(offset));bytes.limit(Math.toIntExact(offset+length));return new GpuBufferSlice.MappedView(slice(offset,length),bytes.slice().order(ByteOrder.nativeOrder()),()->{});}
    }
    public static final class Sampler extends GpuSampler {
        final AddressMode u,v;final FilterMode min,mag;final int anisotropy;final OptionalDouble lod;
        Sampler(AddressMode u,AddressMode v,FilterMode min,FilterMode mag,int anisotropy,OptionalDouble lod){this.u=u;this.v=v;this.min=min;this.mag=mag;this.anisotropy=anisotropy;this.lod=lod;}
        public AddressMode getAddressModeU(){return u;}public AddressMode getAddressModeV(){return v;}public FilterMode getMinFilter(){return min;}public FilterMode getMagFilter(){return mag;}public int getMaxAnisotropy(){return anisotropy;}public OptionalDouble getMaxLod(){return lod;}public void close(){}
    }
}
