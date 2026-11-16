package com.naocraftlab.skins.probe;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.compat.client.identifier.IdentifierTextureRegistry;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.resources.Identifier;

public final class CardTextureBridge implements TextureRegistry {
    public static void holdNextIo(){EarsProbe.holdNextIo.set(true);}
    public static java.util.concurrent.CompletableFuture<?> awaitHeldIo() throws Exception {
        if(!EarsProbe.oldIoEntered.await(15,TimeUnit.SECONDS) || !EarsProbe.Host.INSTANCE.queue.isEmpty())throw new AssertionError("real old I/O held before client enqueue");
        return EarsProbe.lastTerminal;
    }
    public static void releaseHeldIo(java.util.concurrent.CompletableFuture<?> future) throws Exception {
        EarsProbe.releaseOldIo.countDown();EarsProbe.terminal(future,true);
        if(!EarsProbe.Host.INSTANCE.queue.isEmpty() || EarsProbe.networkAttempts!=0)throw new AssertionError("retired old I/O never enqueues or performs network");
    }
    private final IdentifierTextureRegistry delegate;
    public CardTextureBridge() {
        try {
            var constructor=IdentifierTextureRegistry.class.getDeclaredConstructor(String.class,Runnable.class);constructor.setAccessible(true);
            delegate=constructor.newInstance("cards",(Runnable)()->{});
        } catch(Exception failure){throw new AssertionError(failure);}
    }
    public final Map<String,byte[]> content = new HashMap<>();
    public final Map<String,Object> objects = new HashMap<>();
    public int registrations;
    public TextureHandle register(TextureKind kind,String hash,Path path) { throw new AssertionError("unexpected card path registration"); }
    public TextureHandle register(TextureKind kind,String hash,byte[] bytes) throws java.io.IOException {
        var handle=delegate.register(kind,hash,bytes);
        content.put(handle.location(),bytes.clone());registrations++;
        return handle;
    }
    public void release(TextureHandle handle) { delegate.release(handle);content.remove(handle.location());objects.remove(handle.location()); }
    public void settle() throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(content.keySet().stream().anyMatch(id -> !NativePlayerSkinLifecycle.isReady(id))) {
            if(System.nanoTime()>until)throw new AssertionError("native card registration did not complete");
            var next=EarsProbe.Host.INSTANCE.queue.poll(100,TimeUnit.MILLISECONDS);
            if(next!=null)next.run();
        }
        for(var id:content.keySet())objects.put(id,EarsProbe.Host.INSTANCE.textures.map.get(Identifier.parse(id)));
    }
    public Object registered(String id) {
        var texture=EarsProbe.Host.INSTANCE.textures.map.get(Identifier.parse(id));
        if(texture==null || texture!=objects.get(id) || !NativePlayerSkinLifecycle.isReady(id))throw new AssertionError("exact native card registration lost");
        return texture;
    }
    public void close() { delegate.close(); }
}
