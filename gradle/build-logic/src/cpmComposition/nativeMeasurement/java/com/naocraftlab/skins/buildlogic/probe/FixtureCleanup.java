package com.naocraftlab.skins.buildlogic.probe;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class FixtureCleanup implements AutoCloseable {
    @FunctionalInterface public interface Action { void run() throws Throwable; }
    private Throwable first;
    private final Set<Throwable> failures=Collections.newSetFromMap(new IdentityHashMap<>());
    public void attempt(Action action) {
        try { action.run(); }
        catch(Throwable failure) {
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            if(failures.add(failure)) {
                if(first==null)first=failure;
                else first.addSuppressed(failure);
            }
        }
    }
    @Override public void close() {
        if(first!=null)FixtureCleanup.<RuntimeException>rethrow(first);
    }
    @SuppressWarnings("unchecked") private static <T extends Throwable> void rethrow(Throwable failure) throws T { throw (T)failure; }
}
