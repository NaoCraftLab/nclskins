package com.naocraftlab.skins.buildlogic.probe.wavey;

public final class CaseLifecycle {
    @FunctionalInterface public interface Action { void run() throws Exception; }
    public static void execute(Action action, Action cleanup) throws Exception {
        Throwable primary = null;
        try { action.run(); }
        catch (Exception | Error failure) { primary = failure; throw failure; }
        finally {
            try { cleanup.run(); }
            catch (Exception | Error failure) {
                if (primary != null) primary.addSuppressed(failure); else throw failure;
            }
        }
    }
}
