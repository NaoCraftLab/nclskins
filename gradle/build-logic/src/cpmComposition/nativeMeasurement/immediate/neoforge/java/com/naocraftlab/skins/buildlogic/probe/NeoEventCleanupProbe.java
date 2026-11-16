package com.naocraftlab.skins.buildlogic.probe;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

public final class NeoEventCleanupProbe {
    public static void main(String[] args) {
        String mode=args[0];
        var primary=new AssertionError("registration or first unregister failure");
        var later=new AssertionError("later unregister failure");
        var live=Collections.newSetFromMap(new IdentityHashMap<>());
        var closes=new IdentityHashMap<Object,Integer>();
        Consumer<RenderPlayerEvent.Pre> pre=event->{};
        Consumer<RenderPlayerEvent.Post> post=event->{};
        int[] additions={0};
        var bus=(IEventBus)Proxy.newProxyInstance(IEventBus.class.getClassLoader(),new Class<?>[]{IEventBus.class},(owner,method,values)-> {
            switch(method.getName()) {
                case "addListener" -> {
                    live.add(values[1]);
                    if(++additions[0]==2&&mode.equals("second-add"))throw primary;
                }
                case "start" -> { if(mode.equals("start"))throw primary; }
                case "unregister" -> {
                    Object target=values[0];closes.merge(target,1,Integer::sum);live.remove(target);
                    if(mode.equals("unregister"))throw target==pre?primary:later;
                }
                default -> throw new AssertionError("event registration and teardown only");
            }
            return null;
        });
        Throwable caught=null;
        NeoImmediateEvents scope=null;
        try { scope=new NeoImmediateEvents(bus,pre,post);scope.close(); }
        catch(Throwable failure) { caught=failure; }
        if(!mode.equals("positive")&&caught!=primary)throw new AssertionError("exact primary event failure",caught);
        if(mode.equals("positive")&&caught!=null)throw new AssertionError("successful event lifetime",caught);
        if(!live.isEmpty()||closes.getOrDefault(pre,0)!=1||closes.getOrDefault(post,0)!=1)throw new AssertionError("all owned listeners removed exactly once despite earlier failure");
        if(mode.equals("unregister")&&(primary.getSuppressed().length!=1||primary.getSuppressed()[0]!=later))throw new AssertionError("later unregister exact suppressed identity");
        if(scope!=null)scope.close();
        if(closes.get(pre)!=1||closes.get(post)!=1)throw new AssertionError("event close remains exact-once");
        System.out.println("NEO_EVENT_CLEANUP_PASS "+mode);
    }
}
