package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PlayerAppearanceSink;
import com.naocraftlab.skins.client.PlayerAppearanceSink.CapeSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

final class OwnedCapeTextures {
    private static final Map<PlayerAppearanceSink<?>, Map<Key, Object>> OWNERS = new WeakHashMap<>();
    private final PlayerAppearanceSink<?> sink;
    private final Object owner = new Object();

    OwnedCapeTextures(PlayerAppearanceSink<?> sink) { this.sink = sink; }

    Optional<String> register(UUID player, CapeSource source, String hash, byte[] bytes) {
        synchronized (OWNERS) {
            Optional<String> location = sink.registerCapeTexture(player, source, hash, bytes);
            if (location.isPresent()) OWNERS.computeIfAbsent(sink, ignored -> new HashMap<>())
                    .put(new Key(player, source), owner);
            return location;
        }
    }

    void release(UUID player, CapeSource source) {
        synchronized (OWNERS) {
            Map<Key, Object> owners = OWNERS.get(sink);
            if (owners != null && owners.remove(new Key(player, source), owner)) {
                sink.releaseCapeTexture(player, source);
                if (owners.isEmpty()) OWNERS.remove(sink);
            }
        }
    }

    private record Key(UUID player, CapeSource source) {}
}
