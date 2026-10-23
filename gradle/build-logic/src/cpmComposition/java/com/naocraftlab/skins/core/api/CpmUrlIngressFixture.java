package com.naocraftlab.skins.core.api;

import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.storage.TextureCache;
import com.naocraftlab.skins.runtime.PublicSkinImports;
import com.naocraftlab.skins.runtime.PublicSkinImportService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CpmUrlIngressFixture {
    public static int requests;
    public static PublicSkinImports imports(Path root, byte[] source) {
        var terminal = new PinnedHttpsTransport() {
            @Override <T> StreamResponse<T> getStreaming(URI uri, String host, List<InetAddress> addresses,
                    Duration timeout, long maximum, PublicHttpsImageFetcher.BodyReader<T> reader) throws IOException {
                if (!host.equals("example.com") || !addresses.equals(List.of(InetAddress.getByName("8.8.8.8"))))
                    throw new AssertionError("Public validated/pinned acquisition reaches terminal transport");
                requests++;
                return new StreamResponse<>(200, uri, Map.of("content-type", List.of("text/plain")),
                        reader.read(new ByteArrayInputStream(source)));
            }
        };
        var remote = new SafeRemotePngFetcher(terminal, new PngValidator(),
                host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});
        var images = new PublicSkinImageAdapter(new TextureCache(new NclSkinsStorage(root,
                new PngValidator(), Clock.systemUTC())), remote, new PngValidator());
        return new PublicSkinImportService(Optional.empty(), images, (collection, skin, type) -> {
            throw new AssertionError("No catalog/profile lookup while acquiring explicit source URL");
        });
    }
}
