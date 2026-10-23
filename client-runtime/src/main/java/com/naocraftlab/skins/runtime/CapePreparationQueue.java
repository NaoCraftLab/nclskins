package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.png.PreparedCapeAsset;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.client.TextureRegistry;
import com.naocraftlab.skins.client.EncodedTextureLimit;
import com.naocraftlab.skins.core.service.ProviderTextureStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class CapePreparationQueue implements AutoCloseable {
    static final long DECODE_CPU_BYTES = 32L * 1024 * 1024;
    static final long CAPE_PREPARATION_PEAK = EncodedTextureLimit.MAX_ENCODED_TEXTURE_BYTES
            + PreparedCapeAsset.MAX_BACKING_BYTES;
    @FunctionalInterface
    interface Decoder {
        PreparedCapeAsset prepare(Path encoded, Path backing) throws IOException, PngValidationException;
    }
    private static final long MAX_SCRATCH = 2L * 1024 * 1024 * 1024;
    private static final long MAX_CPU = 64L * 1024 * 1024;
    private static long scratchBytes;
    private static long cpuBytes;
    private static long cacheAccessSequence;
    private static final Map<Path, ObservedCacheEntry> OBSERVED_CACHE = new HashMap<>();
    private static final Map<Object, Set<String>> OBSERVED_PINS = new IdentityHashMap<>();
    private static final List<CapacityNotice> CAPACITY_NOTICES = new ArrayList<>();
    enum Slot { SELF_CANDIDATES, OPTIFINE_ADOPTION, SKINMC_ADOPTION, MINECRAFTCAPES_ADOPTION }
    private final Executor worker;
    private final EnumMap<Slot, Pending> slots = new EnumMap<>(Slot.class);
    private boolean closed;

    static Reservation reserveScratch(long bytes) throws InterruptedException {
        return reserve(Kind.SCRATCH, bytes, true, () -> true);
    }

    static Reservation reserveScratch(long bytes, BooleanSupplier active) throws InterruptedException {
        return reserve(Kind.SCRATCH, bytes, true, active);
    }

    static Reservation tryReserveScratch(long bytes) {
        try { return reserve(Kind.SCRATCH, bytes, false, () -> true); }
        catch (InterruptedException impossible) { throw new AssertionError(impossible); }
    }

    static Reservation reserveCpu(long bytes) throws InterruptedException {
        return reserve(Kind.CPU, bytes, true, () -> true);
    }

    static Reservation reserveCpu(long bytes, BooleanSupplier active) throws InterruptedException {
        return reserve(Kind.CPU, bytes, true, active);
    }

    static Reservation tryReserveCpu(long bytes) {
        try { return reserve(Kind.CPU, bytes, false, () -> true); }
        catch (InterruptedException impossible) { throw new AssertionError(impossible); }
    }

    static synchronized void wakeWaiters() {
        CapePreparationQueue.class.notifyAll();
    }

    static synchronized long reservedScratchBytes() { return scratchBytes; }

    static CapacityNotice capacityNotice(Consumer<CapacityNotice> ready) {
        return capacityNotice(CAPE_PREPARATION_PEAK, DECODE_CPU_BYTES, ready);
    }

    static CapacityNotice capacityNotice(long scratch, long cpu, Consumer<CapacityNotice> ready) {
        return new CapacityNotice(scratch, cpu, ready);
    }

    private static boolean capacityAvailable(long scratch, long cpu) {
        return scratchBytes <= MAX_SCRATCH - scratch && cpuBytes <= MAX_CPU - cpu;
    }

    private static List<CapacityNotice> readyNotices() {
        List<CapacityNotice> ready = new ArrayList<>();
        Iterator<CapacityNotice> iterator = CAPACITY_NOTICES.iterator();
        while (iterator.hasNext()) {
            CapacityNotice notice = iterator.next();
            if (capacityAvailable(notice.scratch, notice.cpu)) {
                iterator.remove();
                notice.state = CapacityNotice.FIRED;
                ready.add(notice);
            }
        }
        return ready;
    }

    private static void dispatchReadyNotices() {
        if (Thread.holdsLock(CapePreparationQueue.class)) return;
        List<CapacityNotice> ready;
        synchronized (CapePreparationQueue.class) {
            ready = readyNotices();
        }
        ready.forEach(CapacityNotice::dispatch);
    }

    static final class CapacityNotice implements CatalogRead.CapacityWatch {
        private static final int NEW = 0;
        private static final int ARMED = 1;
        private static final int FIRED = 2;
        private static final int CLOSED = 3;
        private final long scratch;
        private final long cpu;
        private final Consumer<CapacityNotice> ready;
        private int state = NEW;

        private CapacityNotice(long scratch, long cpu, Consumer<CapacityNotice> ready) {
            if (scratch <= 0 || scratch > MAX_SCRATCH || cpu < 0 || cpu > MAX_CPU) {
                throw new IllegalArgumentException("Invalid cape capacity notice");
            }
            this.scratch = scratch;
            this.cpu = cpu;
            this.ready = java.util.Objects.requireNonNull(ready, "ready");
        }

        @Override public void arm() {
            boolean dispatch = false;
            synchronized (CapePreparationQueue.class) {
                if (state != NEW) return;
                if (capacityAvailable(scratch, cpu)) {
                    state = FIRED;
                    dispatch = true;
                } else {
                    state = ARMED;
                    CAPACITY_NOTICES.add(this);
                }
            }
            if (dispatch) dispatch();
        }

        private void dispatch() {
            synchronized (CapePreparationQueue.class) {
                if (state != FIRED) return;
            }
            try {
                ready.accept(this);
            } catch (RuntimeException unavailable) {
                close();
            }
        }

        @Override public void close() {
            synchronized (CapePreparationQueue.class) {
                if (state == CLOSED) return;
                state = CLOSED;
                CAPACITY_NOTICES.remove(this);
            }
        }
    }

    static synchronized void pinObservedFor(Object owner, Set<String> visualKeys) {
        if (visualKeys.isEmpty()) OBSERVED_PINS.remove(owner);
        else OBSERVED_PINS.put(owner, Set.copyOf(visualKeys));
    }

    static boolean reconcileObservedCache(ProviderTextureStore store) throws IOException {
        try {
            synchronized (CapePreparationQueue.class) {
                return reconcileObservedCacheLocked(store);
            }
        } finally {
            dispatchReadyNotices();
        }
    }

    private static boolean reconcileObservedCacheLocked(ProviderTextureStore store) throws IOException {
        var inventory = new ArrayList<>(store.listObservedPreparedCapes());
        inventory.sort(Comparator.comparingLong(ProviderTextureStore.ObservedPreparedCape::lastModifiedMillis));
        for (var item : inventory) {
            Path path = item.backing().toAbsolutePath().normalize();
            ObservedCacheEntry existing = OBSERVED_CACHE.get(path);
            if (existing != null) {
                existing.store = store;
                if (!existing.resize(item.storedBytes())) return false;
                continue;
            }
            Reservation held = item.storedBytes() == 0 ? null
                    : tryReserveScratch(item.storedBytes());
            if (item.storedBytes() != 0 && held == null) return false;
            OBSERVED_CACHE.put(path, new ObservedCacheEntry(path, item.visualSha256(), store,
                    item.storedBytes(), held, ++cacheAccessSequence));
        }
        return true;
    }

    static ObservedPin retainObservedCache(ProviderTextureStore store,
            PreparedCapeAsset asset, Reservation staged) throws IOException {
        try {
            synchronized (CapePreparationQueue.class) {
                return retainObservedCacheLocked(store, asset, staged);
            }
        } finally {
            dispatchReadyNotices();
        }
    }

    private static ObservedPin retainObservedCacheLocked(ProviderTextureStore store,
            PreparedCapeAsset asset, Reservation staged) throws IOException {
        Path path = asset.backing().toRealPath();
        long storedBytes = Math.addExact(asset.backingBytes(), asset.descriptorBytes().length);
        ObservedCacheEntry existing = OBSERVED_CACHE.get(path);
        if (existing != null) {
            if (!existing.resize(storedBytes)) throw new IOException("Cape cache exceeds scratch budget");
            staged.close();
            existing.store = store;
            existing.touch();
            existing.pins++;
            return new ObservedPin(existing);
        }
        staged.shrinkTo(storedBytes);
        ObservedCacheEntry added = new ObservedCacheEntry(path, asset.visualSha256(), store,
                storedBytes, staged, ++cacheAccessSequence);
        added.pins++;
        OBSERVED_CACHE.put(path, added);
        return new ObservedPin(added);
    }

    static synchronized ObservedPin pinObservedCache(PreparedCapeAsset asset) throws IOException {
        ObservedCacheEntry entry = OBSERVED_CACHE.get(asset.backing().toRealPath());
        if (entry == null) return null;
        entry.pins++;
        entry.touch();
        return new ObservedPin(entry);
    }

    private static boolean pinned(ObservedCacheEntry entry) {
        if (entry.pins > 0) return true;
        for (Set<String> keys : OBSERVED_PINS.values()) {
            if (keys.contains(entry.visualKey)) return true;
        }
        return false;
    }

    private static void evictObserved(long requested, ObservedCacheEntry excluded) {
        ArrayList<ObservedCacheEntry> candidates = new ArrayList<>(OBSERVED_CACHE.values());
        candidates.sort(Comparator.comparingLong(entry -> entry.lastAccess));
        for (ObservedCacheEntry entry : candidates) {
            if (scratchBytes <= MAX_SCRATCH - requested) break;
            if (entry == excluded || pinned(entry)) continue;
            try {
                if (!entry.store.removeObservedPreparedCape(entry.visualKey)) continue;
                OBSERVED_CACHE.remove(entry.path, entry);
                if (entry.reservation != null) entry.reservation.close();
            } catch (IOException | UnsupportedOperationException unavailable) {
            }
        }
    }

    @SuppressWarnings("try")
    static PreparedCapeAsset decode(Decoder decoder, Path encoded, Path backing)
            throws IOException, PngValidationException, InterruptedException {
        try (var cpu = reserveCpu(DECODE_CPU_BYTES,
                () -> !Thread.currentThread().isInterrupted())) {
            return decoder.prepare(encoded, backing);
        }
    }

    private static Reservation reserve(Kind kind, long bytes, boolean wait,
            BooleanSupplier active)
            throws InterruptedException {
        try {
            synchronized (CapePreparationQueue.class) {
                return reserveLocked(kind, bytes, wait, active);
            }
        } finally {
            dispatchReadyNotices();
        }
    }

    private static Reservation reserveLocked(Kind kind, long bytes, boolean wait,
            BooleanSupplier active) throws InterruptedException {
        java.util.Objects.requireNonNull(active, "active");
        long maximum = switch (kind) {
            case SCRATCH -> MAX_SCRATCH;
            case CPU -> MAX_CPU;
        };
        if (bytes <= 0 || bytes > maximum) throw new IllegalArgumentException("Invalid cape reservation");
        while (used(kind) > maximum - bytes) {
            if (kind == Kind.SCRATCH) evictObserved(bytes, null);
            if (used(kind) <= maximum - bytes) break;
            if (!wait) return null;
            if (!active.getAsBoolean()) throw new InterruptedException("Cape preparation closed");
            CapePreparationQueue.class.wait();
        }
        if (!active.getAsBoolean()) throw new InterruptedException("Cape preparation closed");
        add(kind, bytes);
        return new Reservation(kind, bytes);
    }

    private static long used(Kind kind) {
        return switch (kind) {
            case SCRATCH -> scratchBytes;
            case CPU -> cpuBytes;
        };
    }

    private static void add(Kind kind, long bytes) {
        switch (kind) {
            case SCRATCH -> scratchBytes += bytes;
            case CPU -> cpuBytes += bytes;
        }
    }

    enum Kind { SCRATCH, CPU }

    static final class Reservation implements TextureRegistry.CpuLease {
        private final Kind kind;
        private long bytes;
        private boolean closed;

        private Reservation(Kind kind, long bytes) { this.kind = kind; this.bytes = bytes; }

        @Override
        public void sourcePixelsReleased(long retainedNativeBytes) {
            if (kind != Kind.CPU) throw new IllegalStateException("Only CPU reservations contain source pixels");
            shrinkToIfOpen(retainedNativeBytes);
        }

        void shrinkTo(long retainedBytes) {
            shrinkTo(retainedBytes, false);
        }

        private void shrinkToIfOpen(long retainedBytes) {
            shrinkTo(retainedBytes, true);
        }

        private void shrinkTo(long retainedBytes, boolean ignoreClosed) {
            List<CapacityNotice> ready;
            boolean nested = Thread.holdsLock(CapePreparationQueue.class);
            synchronized (CapePreparationQueue.class) {
                if (closed && ignoreClosed) return;
                if (closed || retainedBytes < 0 || retainedBytes > bytes) {
                    throw new IllegalArgumentException("Invalid cape reservation reduction");
                }
                add(kind, retainedBytes - bytes);
                bytes = retainedBytes;
                CapePreparationQueue.class.notifyAll();
                ready = nested ? List.of() : readyNotices();
            }
            ready.forEach(CapacityNotice::dispatch);
        }

        boolean growTo(long retainedBytes, ObservedCacheEntry excluded) {
            synchronized (CapePreparationQueue.class) {
                if (closed || retainedBytes < bytes) throw new IllegalArgumentException("Invalid cape reservation growth");
                long extra = retainedBytes - bytes;
                if (kind == Kind.SCRATCH && scratchBytes > MAX_SCRATCH - extra) {
                    evictObserved(extra, excluded);
                }
                long maximum = kind == Kind.SCRATCH ? MAX_SCRATCH : MAX_CPU;
                if (used(kind) > maximum - extra) return false;
                add(kind, extra);
                bytes = retainedBytes;
                return true;
            }
        }

        @Override public void close() {
            List<CapacityNotice> ready;
            boolean nested = Thread.holdsLock(CapePreparationQueue.class);
            synchronized (CapePreparationQueue.class) {
                if (closed) return;
                closed = true;
                add(kind, -bytes);
                CapePreparationQueue.class.notifyAll();
                ready = nested ? List.of() : readyNotices();
            }
            ready.forEach(CapacityNotice::dispatch);
        }
    }

    CapePreparationQueue(Executor worker) { this.worker = worker; }

    void submit(Slot slot, Runnable action) { submit(slot, action, () -> {}); }

    synchronized void submit(Slot slot, Runnable action, Runnable rejection) {
        Submission submission = new Submission(action, rejection);
        if (closed) { rejection.run(); return; }
        Pending pending = slots.get(slot);
        if (pending != null) {
            pending.latest = submission;
            return;
        }
        pending = new Pending();
        slots.put(slot, pending);
        try { dispatch(slot, pending, submission); }
        catch (java.util.concurrent.RejectedExecutionException rejected) {
            slots.remove(slot, pending);
            submission.rejection.run();
        }
    }

    private void dispatch(Slot slot, Pending pending, Submission submission) {
        pending.running = submission;
        worker.execute(() -> {
            try {
                synchronized (CapePreparationQueue.this) {
                    if (closed || slots.get(slot) != pending) return;
                }
                submission.action.run();
            }
            finally { finished(slot, pending); }
        });
    }

    private synchronized void finished(Slot slot, Pending pending) {
        if (closed || slots.get(slot) != pending) return;
        Submission next = pending.latest;
        if (next == null) slots.remove(slot);
        else {
            pending.latest = null;
            try { dispatch(slot, pending, next); }
            catch (java.util.concurrent.RejectedExecutionException rejected) {
                slots.remove(slot, pending);
                next.rejection.run();
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (Pending pending : slots.values()) {
            if (pending.running != null) pending.running.rejection.run();
            if (pending.latest != null) pending.latest.rejection.run();
        }
        slots.clear();
        synchronized (CapePreparationQueue.class) {
            CapePreparationQueue.class.notifyAll();
        }
    }

    private static final class Pending {
        private Submission running;
        private Submission latest;
    }

    private record Submission(Runnable action, Runnable rejection) {}

    static final class ObservedPin implements AutoCloseable {
        private ObservedCacheEntry entry;
        private ObservedPin(ObservedCacheEntry entry) { this.entry = entry; }
        @Override public void close() {
            synchronized (CapePreparationQueue.class) {
                if (entry == null) return;
                entry.pins--;
                entry = null;
            }
        }
    }

    private static final class ObservedCacheEntry {
        private final Path path;
        private final String visualKey;
        private ProviderTextureStore store;
        private long storedBytes;
        private Reservation reservation;
        private long lastAccess;
        private int pins;

        private ObservedCacheEntry(Path path, String visualKey, ProviderTextureStore store,
                long storedBytes, Reservation reservation, long lastAccess) {
            this.path = path;
            this.visualKey = visualKey;
            this.store = store;
            this.storedBytes = storedBytes;
            this.reservation = reservation;
            this.lastAccess = lastAccess;
        }

        private void touch() { lastAccess = ++cacheAccessSequence; }

        private boolean resize(long bytes) {
            if (bytes == storedBytes) return true;
            if (reservation == null) {
                if (bytes == 0) return true;
                reservation = tryReserveScratch(bytes);
                if (reservation == null) return false;
                storedBytes = bytes;
                return true;
            }
            if (bytes < storedBytes) reservation.shrinkTo(bytes);
            else if (!reservation.growTo(bytes, this)) return false;
            storedBytes = bytes;
            return true;
        }
    }
}
