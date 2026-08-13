package com.naocraftlab.skins.server.plugin.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;


public final class ServerCapabilityProtocol {
    public static final int MAX_RESPONSE_BYTES = 512;
    public static final byte WIRE_VERSION = 1;
    private static final int MAX_VERSION_BYTES = 64;
    private static final int MAX_PROTOCOL_BYTES = 64;
    private static final int MAX_PROTOCOLS = 16;

    public byte[] request() {
        return new byte[0];
    }

    public boolean isRequest(byte[] payload) {
        return payload != null && payload.length == 0;
    }

    public byte[] encodeResponse(Response response) {
        Objects.requireNonNull(response, "response");
        if (response.protocolIds().isEmpty() || response.protocolIds().size() > MAX_PROTOCOLS) {
            throw new IllegalArgumentException("Capability response protocol count is out of bounds");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(WIRE_VERSION);
                writeString(output, response.serverImplementationVersion().toString(), MAX_VERSION_BYTES);
                output.writeByte(response.protocolIds().size());
                for (String protocolId : response.protocolIds()) {
                    writeString(output, protocolId, MAX_PROTOCOL_BYTES);
                }
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_RESPONSE_BYTES) {
                throw new IllegalArgumentException("Capability response exceeds 512 bytes");
            }
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory capability encoding failed", impossible);
        }
    }

    public Response decodeResponse(byte[] payload) {
        requireBounded(payload, MAX_RESPONSE_BYTES, "capability response");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int wireVersion = input.readUnsignedByte();
            if (wireVersion != WIRE_VERSION) {
                throw new ProtocolException("Unsupported capability wire version " + wireVersion);
            }
            SemanticVersion implementation = SemanticVersion.parse(
                    readString(input, MAX_VERSION_BYTES));
            int count = input.readUnsignedByte();
            if (count == 0 || count > MAX_PROTOCOLS) {
                throw new ProtocolException("Capability protocol count is out of bounds");
            }
            Set<String> protocols = new LinkedHashSet<>();
            for (int index = 0; index < count; index++) {
                String protocol = readString(input, MAX_PROTOCOL_BYTES);
                if (!protocol.matches("[a-z][a-z0-9-]*-v[1-9][0-9]*") ||
                        !protocols.add(protocol)) {
                    throw new ProtocolException("Invalid or duplicate capability protocol");
                }
            }
            if (input.read() != -1) {
                throw new ProtocolException("Trailing capability response bytes");
            }
            return new Response(implementation, List.copyOf(protocols));
        } catch (EOFException truncated) {
            throw new ProtocolException("Truncated capability response", truncated);
        } catch (IOException failure) {
            throw new ProtocolException("Unable to decode capability response", failure);
        } catch (IllegalArgumentException invalid) {
            throw new ProtocolException("Invalid capability response", invalid);
        }
    }

    public Compatibility compatibility(
            SemanticVersion requiredImplementation,
            Set<String> requiredProtocols,
            Response response) {
        Objects.requireNonNull(requiredImplementation, "requiredImplementation");
        Objects.requireNonNull(requiredProtocols, "requiredProtocols");
        Objects.requireNonNull(response, "response");
        boolean implementation = response.serverImplementationVersion()
                .compareTo(requiredImplementation) >= 0;
        boolean protocol = response.protocolIds().stream().anyMatch(requiredProtocols::contains);
        return new Compatibility(implementation, protocol);
    }

    private static void writeString(DataOutputStream output, String value, int maximumBytes)
            throws IOException {
        byte[] encoded = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > maximumBytes) {
            throw new IllegalArgumentException("Protocol string length is out of bounds");
        }
        output.writeByte(encoded.length);
        output.write(encoded);
    }

    private static String readString(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readUnsignedByte();
        if (length == 0 || length > maximumBytes) {
            throw new ProtocolException("Protocol string length is out of bounds");
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new EOFException("Truncated protocol string");
        }
        return decodeUtf8(value);
    }

    static String decodeUtf8(byte[] value) throws IOException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value)).toString();
    }

    static void requireBounded(byte[] payload, int maximum, String label) {
        if (payload == null || payload.length == 0 || payload.length > maximum) {
            throw new ProtocolException(label + " size is out of bounds");
        }
    }

    public record Response(
            SemanticVersion serverImplementationVersion,
            List<String> protocolIds) {
        public Response {
            Objects.requireNonNull(serverImplementationVersion, "serverImplementationVersion");
            protocolIds = List.copyOf(Objects.requireNonNull(protocolIds, "protocolIds"));
        }
    }

    public record Compatibility(boolean implementationCompatible, boolean protocolCompatible) {
        public boolean compatible() {
            return implementationCompatible && protocolCompatible;
        }
    }

    public static final class ProtocolException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public ProtocolException(String message) {
            super(message);
        }

        public ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
