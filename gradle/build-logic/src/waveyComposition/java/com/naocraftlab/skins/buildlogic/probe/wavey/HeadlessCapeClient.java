package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

public final class HeadlessCapeClient implements AutoCloseable {
    private final Field instance;
    private final Object previous;
    private HeadlessCapeClient(boolean world) throws Exception {
        instance = Minecraft.class.getDeclaredField("instance");
        instance.setAccessible(true);
        previous = instance.get(null);
        Minecraft client = allocate(Minecraft.class);
        Field thread = Minecraft.class.getDeclaredField("gameThread");
        thread.setAccessible(true);
        thread.set(client, Thread.currentThread());
        if (world) {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            client.level = allocate(ClientLevel.class);
        }
        instance.set(null, client);
        if (!client.isSameThread()) throw new AssertionError("Native client-thread fixture does not match actual contract");
    }
    public static HeadlessCapeClient open(boolean world) throws Exception { return new HeadlessCapeClient(world); }
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }
    @Override public void close() throws Exception { instance.set(null, previous); }
}
