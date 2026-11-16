package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.compat.cpm.OwnedCpmDefinition;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.ModelDefinition;

public final class ReferenceNeutral implements AutoCloseable {
    static ReferenceNeutral active;
    final Player<?> player;
    final OwnedCpmDefinition definition;
    final Thread thread=Thread.currentThread();
    public static int calls;
    final Object state;
    final boolean bodyOnly;
    public static int capeExclusions,wingExclusions;
    public static boolean excludes(Object state){return active!=null&&active.bodyOnly&&active.state==state&&matches(active.player,active.definition);}
    public ReferenceNeutral(Player<?> player,OwnedCpmDefinition definition,Object state,boolean bodyOnly){
        this.state=state;this.bodyOnly=bodyOnly;
        if(active!=null||!definition.source().active()||!definition.doRender())throw new AssertionError("owned isolated exact-source neutral scope");this.player=player;this.definition=definition;active=this;
    }
    public static boolean matches(Player<?> player,ModelDefinition definition){
        if(active==null||active.player!=player||active.definition!=definition)return false;
        if(active.thread!=Thread.currentThread()||!active.definition.source().active()||!active.definition.doRender())throw new AssertionError("isolated neutral scope lost ownership/currentness");return true;
    }
    public void close(){if(active!=this||thread!=Thread.currentThread())throw new AssertionError("exact neutral scope restore");active=null;}
}
