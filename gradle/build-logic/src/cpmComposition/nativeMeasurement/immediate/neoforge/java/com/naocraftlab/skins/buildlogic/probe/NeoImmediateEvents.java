package com.naocraftlab.skins.buildlogic.probe;

import com.tom.cpm.client.CustomPlayerModelsClient;
import java.util.function.Consumer;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class NeoImmediateEvents implements AutoCloseable {
    private final net.neoforged.bus.api.IEventBus bus;
    private final Consumer<RenderPlayerEvent.Pre> pre;
    private final Consumer<RenderPlayerEvent.Post> post;
    private boolean preOwned,postOwned;

    public NeoImmediateEvents(Object instance) {
        this(NeoForge.EVENT_BUS,((CustomPlayerModelsClient)instance)::playerRenderPre,((CustomPlayerModelsClient)instance)::playerRenderPost);
    }

    NeoImmediateEvents(net.neoforged.bus.api.IEventBus bus,Consumer<RenderPlayerEvent.Pre> pre,Consumer<RenderPlayerEvent.Post> post) {
        this.bus=bus;this.pre=pre;this.post=post;
        try {
            preOwned=true;bus.addListener(RenderPlayerEvent.Pre.class,pre);
            postOwned=true;bus.addListener(RenderPlayerEvent.Post.class,post);
            bus.start();
        } catch(Throwable failure) {
            try(var cleanup=new FixtureCleanup()) {
                cleanup.attempt(()->{throw failure;});
                cleanup.attempt(this::close);
            }
        }
    }

    @Override public void close() {
        boolean removePre=preOwned,removePost=postOwned;
        preOwned=false;postOwned=false;
        try(var cleanup=new FixtureCleanup()) {
            cleanup.attempt(()->{if(removePre)bus.unregister(pre);});
            cleanup.attempt(()->{if(removePost)bus.unregister(post);});
        }
    }
}
