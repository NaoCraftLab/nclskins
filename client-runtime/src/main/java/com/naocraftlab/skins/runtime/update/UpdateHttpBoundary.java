package com.naocraftlab.skins.runtime.update;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

interface UpdateHttpBoundary {
    UpdateHttpResponse get(URI uri, Duration timeout) throws IOException, InterruptedException;
}
