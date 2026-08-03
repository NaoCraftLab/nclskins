package com.naocraftlab.skins.server.vanilla;


public interface PlatformScheduler {
    boolean isPlatformThread();

    void execute(Runnable action);

    void nextTick(Runnable action);

    long nanoTime();


    long tickId();
}
