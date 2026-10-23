package com.naocraftlab.skins.core.api;

import com.naocraftlab.skins.core.importing.PublicSkinImageSource;
import com.naocraftlab.skins.core.png.PngValidator;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class CpmDependencyTransportProbe {
    @FunctionalInterface
    public interface Terminal {
        PublicHttpsImageFetcher.Response get(URI uri, Duration timeout, int maximum) throws IOException;
    }
    public static PublicSkinImageSource.DependencySource source(Terminal terminal, BooleanSupplier privateDns, LongSupplier clock) {
        var transport = new PinnedHttpsTransport() {
            @Override Response get(URI uri, String host, List<InetAddress> addresses, Duration timeout, int maximum) throws IOException {
                if (!host.equals(uri.getHost()) || addresses.size() != 1 || !addresses.get(0).getHostAddress().equals("8.8.8.8"))
                    throw new AssertionError("Exact public DNS pin must reach transport");
                var response = terminal.get(uri, timeout, maximum);
                return new Response(response.status(), uri, response.headers(), response.bytes());
            }
        };
        var admission = new SafeRemotePngFetcher(transport, new PngValidator(), host -> new InetAddress[]{
                InetAddress.getByAddress(privateDns.getAsBoolean() ? new byte[]{127, 0, 0, 1} : new byte[]{8, 8, 8, 8})}, clock);
        var fetcher = new PublicHttpsImageFetcher(admission, transport, clock);
        return (uri, timeout, maximum) -> PublicSkinImageAdapter.acquireDependency(fetcher, uri, timeout, maximum);
    }
}
