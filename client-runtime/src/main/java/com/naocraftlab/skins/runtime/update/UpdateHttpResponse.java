package com.naocraftlab.skins.runtime.update;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.OptionalLong;

final class UpdateHttpResponse implements AutoCloseable {
    private final int status;
    private final OptionalLong contentLength;
    private final InputStream body;

    UpdateHttpResponse(int status, OptionalLong contentLength, InputStream body) {
        this.status = status;
        this.contentLength = Objects.requireNonNull(contentLength, "contentLength");
        this.body = Objects.requireNonNull(body, "body");
    }

    int status() {
        return status;
    }

    OptionalLong contentLength() {
        return contentLength;
    }

    InputStream body() {
        return body;
    }

    @Override
    public void close() throws IOException {
        body.close();
    }
}
