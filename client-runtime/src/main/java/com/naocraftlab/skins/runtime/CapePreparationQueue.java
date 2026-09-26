package com.naocraftlab.skins.runtime;

import java.util.EnumMap;
import java.util.concurrent.Executor;

final class CapePreparationQueue implements AutoCloseable {
    enum Slot { SELF_CANDIDATES, OPTIFINE_ADOPTION, SKINMC_ADOPTION }
    private final Executor worker;
    private final EnumMap<Slot, Pending> slots = new EnumMap<>(Slot.class);
    private boolean closed;

    CapePreparationQueue(Executor worker) { this.worker = worker; }

    synchronized void submit(Slot slot, Runnable action) {
        if (closed) return;
        Pending pending = slots.get(slot);
        if (pending != null) {
            pending.latest = action;
            return;
        }
        pending = new Pending();
        slots.put(slot, pending);
        dispatch(slot, pending, action);
    }

    private void dispatch(Slot slot, Pending pending, Runnable action) {
        worker.execute(() -> {
            try { action.run(); }
            finally { finished(slot, pending); }
        });
    }

    private synchronized void finished(Slot slot, Pending pending) {
        if (closed || slots.get(slot) != pending) return;
        Runnable next = pending.latest;
        if (next == null) slots.remove(slot);
        else {
            pending.latest = null;
            dispatch(slot, pending, next);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        slots.clear();
    }

    private static final class Pending {
        private Runnable latest;
    }
}
