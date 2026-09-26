package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.SneakyCapeDecoder;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.io.IOException;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

final class SneakyCapeProcessor implements AutoCloseable {
    private static final int MAX_ACTIVE = 4;
    private static final int MAX_TEXTURES = 512;
    private static final int MAX_DEMAND = 513;
    private final Executor worker;
    private final ClientExecutor client;
    private final Consumer<String> ready;
    private final Supplier<Set<String>> retained;
    private final BooleanSupplier current;
    private final Map<String, Prepared> cache = new LinkedHashMap<>();
    private final Map<String, Demand> demand = new LinkedHashMap<>();
    private int active;
    private long epoch;
    private boolean pumping;
    private boolean closed;

    SneakyCapeProcessor(Executor worker, ClientExecutor client, Consumer<String> ready, Supplier<Set<String>> retained, BooleanSupplier current) {
        this.worker = worker;
        this.client = client;
        this.ready = ready;
        this.retained = retained;
        this.current = current;
    }

    synchronized void prepare(String identity, int[] pixels) {
        if (closed || contains(identity)) return;
        int[] immutable = pixels.clone();
        prepare(identity, () -> {
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, 64, 64, immutable, 0, 64);
            return new Prepared(new SneakyCapeDecoder().decode(image).orElse(null), null);
        });
    }

    synchronized void prepare(String identity, Preparation preparation) {
        if (closed || demand.containsKey(identity)) return;
        if (cache.containsKey(identity)) {
            ready.accept(identity);
            return;
        }
        if (demand.size() == MAX_DEMAND) {
            Set<String> current = retained.get();
            if (!current.contains(identity)) return;
            String displaced = demand.keySet().stream().filter(key -> !current.contains(key))
                    .findFirst().orElse(null);
            if (displaced == null) return;
            demand.remove(displaced);
        }
        demand.put(identity, new Demand(preparation, epoch));
        pump();
    }

    synchronized boolean contains(String identity) {
        return cache.containsKey(identity) || demand.containsKey(identity);
    }

    synchronized Prepared prepared(String identity) { return cache.get(identity); }

    synchronized void forget(String identity) {
        demand.remove(identity);
    }

    synchronized void invalidate() {
        epoch++;
        demand.clear();
    }

    private void pump() {
        if (pumping || closed) return;
        pumping = true;
        try {
            while (active < MAX_ACTIVE) {
                var next = demand.entrySet().stream().filter(entry -> !entry.getValue().submitted)
                        .findFirst().orElse(null);
                if (next == null) break;
                String identity = next.getKey();
                Demand job = next.getValue();
                job.submitted = true;
                active++;
                worker.execute(() -> {
                    Prepared result = null;
                    try {
                        result = job.preparation.prepare();
                    } catch (IOException | PngValidationException | RuntimeException unavailable) {
                        result = null;
                    }
                    Prepared completed = result;
                    client.execute(() -> complete(identity, job, completed));
                });
            }
        } finally {
            pumping = false;
        }
    }

    private synchronized void complete(String identity, Demand job, Prepared result) {
        active--;
        if (!closed && job.epoch == epoch && demand.remove(identity, job) && result != null && current.getAsBoolean()) {
            if (cache.size() == MAX_TEXTURES && !cache.containsKey(identity)) {
                cache.remove(cache.keySet().iterator().next());
            }
            cache.put(identity, result);
            ready.accept(identity);
        }
        pump();
    }

    @Override
    public synchronized void close() {
        closed = true;
        invalidate();
        cache.clear();
    }

    record Prepared(PngValidator.CapePng cape, String cacheKey) {}

    interface Preparation {
        Prepared prepare() throws IOException, PngValidationException;
    }

    private static final class Demand {
        private final Preparation preparation;
        private final long epoch;
        private boolean submitted;

        private Demand(Preparation preparation, long epoch) {
            this.preparation = preparation;
            this.epoch = epoch;
        }
    }
}
