package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.naocraftlab.skins.client.*;
import java.io.IOException;
import java.util.*;

public final class CapeSink implements PlayerAppearanceSink<Object> {
    public final CapeRegistry registry = new CapeRegistry();
    private final Map<Key, TextureRegistry.TextureHandle> current = new HashMap<>();
    private final Map<Key, TextureRegistry.TextureHandle> previous = new HashMap<>();

    @Override public ApplyResult apply(SignedProfileResolver.ResolvedProfile<Object> profile) {
        return ApplyResult.UPDATED;
    }
    @Override public Optional<String> registerCapeTexture(UUID actor, CapeSource source, String hash, byte[] png) {
        try { return install(new Key(actor, source), registry.register(TextureRegistry.TextureKind.IMAGE, hash, png)); }
        catch (IOException unavailable) { return Optional.empty(); }
    }
    @Override public Optional<String> registerCapeFrame(UUID actor, CapeSource source, String hash,
            int width, int height, byte[] bytes, TextureRegistry.CpuLease lease) {
        try { return install(new Key(actor, source), registry.registerRgba(TextureRegistry.TextureKind.IMAGE,
                hash, width, height, bytes, lease)); }
        catch (IOException unavailable) { return Optional.empty(); }
    }
    private Optional<String> install(Key key, TextureRegistry.TextureHandle next) {
        TextureRegistry.TextureHandle old = current.put(key, next);
        release(previous.remove(key));
        if (old != null) previous.put(key, old);
        return Optional.of(next.location());
    }
    @Override public TextureUseValidity captureCapeTexture(UUID actor, CapeSource source, String exactLocation) {
        TextureRegistry.TextureHandle handle = current.get(new Key(actor, source));
        return handle != null && handle.location().equals(exactLocation)
                ? registry.capture(handle) : TextureUseValidity.INVALID;
    }
    @Override public void releaseCapeTexture(UUID actor, CapeSource source) {
        Key key = new Key(actor, source);
        release(current.remove(key));
        release(previous.remove(key));
    }
    private void release(TextureRegistry.TextureHandle handle) { if (handle != null) registry.release(handle); }
    private record Key(UUID actor, CapeSource source) {}
}
