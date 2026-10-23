package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.naocraftlab.skins.client.AbstractTextureRegistry;
import java.util.HashSet;
import java.util.Set;

public final class CapeRegistry extends AbstractTextureRegistry<String> {
    private final Thread owner = Thread.currentThread();
    public int loads;
    private final Set<String> resources = new HashSet<>();

    @Override protected LoadedTexture<String> load(TextureKind kind, String hash, byte[] bytes) {
        return make(hash, 64, 32);
    }
    @Override protected LoadedTexture<String> loadRgba(String hash, int width, int height, byte[] bytes) {
        return make(hash, width, height);
    }
    private LoadedTexture<String> make(String hash, int width, int height) {
        String location = "nclskins:fixture/" + hash;
        resources.add(location);
        loads++;
        return new LoadedTexture<>(new TextureHandle(location, width, height), location);
    }
    @Override protected void unload(String resource) { resources.remove(resource); }
    @Override protected void checkClientThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Fixture client thread");
    }
    public boolean live(String location) { return resources.contains(location); }
    public int size() { return resources.size(); }
}
