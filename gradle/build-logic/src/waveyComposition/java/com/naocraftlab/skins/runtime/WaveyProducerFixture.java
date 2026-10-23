package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.png.*;
import com.naocraftlab.skins.core.provider.*;
import com.naocraftlab.skins.core.storage.*;
import com.naocraftlab.skins.buildlogic.probe.wavey.CapeSink;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;

public final class WaveyProducerFixture implements AutoCloseable {
    public final CapeSink sink = new CapeSink();
    public final UUID self = UUID.randomUUID();
    public final AtomicLong now = new AtomicLong();
    public final AtomicBoolean glint = new AtomicBoolean(true);
    public final byte[] firstFramePng;
    private final NclSkinsStorage storage;
    private final GameSessionTokenSource session;
    public final AtomicInteger profileReads = new AtomicInteger(), imageReads = new AtomicInteger();
    public final CapeProviderCoordinator coordinator;
    private final ArrayDeque<Runnable> jobs = new ArrayDeque<>();

    public WaveyProducerFixture(Path root) throws Exception {
        Files.createDirectories(root);
        storage = new NclSkinsStorage(root, new PngValidator(), Clock.systemUTC());
        storage.loadOrCreateAccount(self);
        storage.updateAppearance(self, state -> state.withProviders(state.providers()
                .enable(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFTCAPES)
                .move(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFTCAPES, -1)));
        session = new GameSessionTokenSource() {
            public SessionIdentity currentSession() { return new SessionIdentity(self, "Self"); }
            public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) {
                throw new AssertionError("Public cape does not use credentials");
            }
        };
        BufferedImage strip = new BufferedImage(128, 192, BufferedImage.TYPE_INT_ARGB);
        for (int index = 0; index < 3; index++) strip.setRGB(1, index * 64 + 1, 0xff102030 + index * 0x001100);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(strip, "png", encoded);
        ByteArrayOutputStream firstFrame = new ByteArrayOutputStream();
        ImageIO.write(strip.getSubimage(0, 0, 128, 64), "png", firstFrame);
        firstFramePng = firstFrame.toByteArray();
        MinecraftCapesCapeReader reader = new MinecraftCapesCapeReader(new MinecraftCapesCapeReader.Transport() {
            public MinecraftCapesCapeReader.ProfileResponse profile(URI uri, Duration timeout, long max) throws IOException {
                profileReads.incrementAndGet();
                return new MinecraftCapesCapeReader.ProfileResponse(200, MinecraftCapesCapeReader.parse(
                        ("{\"cape_url\":\"https://textures.minecraftcapes.net/capes/" + "a".repeat(64)
                                + "\",\"capeGlint\":" + glint.get() + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8)), Map.of());
            }
            public MinecraftCapesCapeReader.Response image(URI uri, Duration timeout, long max, Path destination) throws IOException {
                imageReads.incrementAndGet();
                Files.write(destination, encoded.toByteArray());
                return new MinecraftCapesCapeReader.Response(200, new byte[0], Map.of());
            }
        }, new CapeAssetDecoder(), Clock.systemUTC(), System::nanoTime, new SecureRandom(), root.resolve("reader"));
        ClientExecutor client = new ClientExecutor() {
            private final Thread owner = Thread.currentThread();
            public boolean isClientThread() { return Thread.currentThread() == owner; }
            public void execute(Runnable action) { action.run(); }
        };
        OptifineCapeReader.Transport absent = (uri, timeout, max, destination) ->
                new OptifineCapeReader.FileResponse(404, 0, Map.of());
        coordinator = new CapeProviderCoordinator(session, new AccountAppearanceStorageAdapter(storage),
                new LibraryStorageAdapter(storage), new ProviderTextureStorageAdapter(storage, new TextureCache(storage)),
                sink, client, jobs::add,
                new OptifineCapeReader(absent, root.resolve("optifine"), Clock.systemUTC()),
                new SkinMcCapeReader(absent, absent::getToFile, root.resolve("skinmc"), Clock.systemUTC(), now::get),
                reader, (id, cape) -> Optional.empty(), now::get);
        coordinator.start();
        drain();
    }
    public void drain() {
        int count = 0;
        while (!jobs.isEmpty() && count++ < 2000) jobs.remove().run();
        if (!jobs.isEmpty()) throw new AssertionError("Fixture exceeded bounded producer work");
    }
    public void glintOnlyRefresh(boolean value) throws Exception {
        glint.set(value);
        var before = storage.loadAppearance(self).providers();
        var request = ProviderRefreshRequest.one(AppearanceProviders.Component.CAPE, BuiltinProvider.MINECRAFTCAPES);
        var scope = new ProviderRefreshUseCases.Scope(request, session.currentSession(), before,
                request.resolve(before), coordinator.observationGeneration());
        coordinator.refreshScoped(scope, BuiltinProvider.MINECRAFTCAPES, ignored -> {});
        drain();
    }
    public void retireFrame() {
        for (int frame = 1; frame <= 2; frame++) {
            now.set(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(frame * 100L));
            CapeProjection.resolveSelf(self, "Self").orElseThrow();
            drain();
        }
    }
    @Override public void close() {
        try { coordinator.close(); } finally { sink.registry.close(); }
    }
}
