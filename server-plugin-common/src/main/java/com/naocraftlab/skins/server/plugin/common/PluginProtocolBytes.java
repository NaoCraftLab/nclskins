package com.naocraftlab.skins.server.plugin.common;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;


final class PluginProtocolBytes {
    private PluginProtocolBytes() {
    }

    static String decodeUtf8(byte[] value) throws IOException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value)).toString();
    }

    static void requireBounded(byte[] payload, int maximum, String label) {
        if (payload == null || payload.length == 0 || payload.length > maximum) {
            throw new ProtocolBytesException(label + " size is out of bounds");
        }
    }

    static final class ProtocolBytesException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        ProtocolBytesException(String message) {
            super(message);
        }
    }
}
