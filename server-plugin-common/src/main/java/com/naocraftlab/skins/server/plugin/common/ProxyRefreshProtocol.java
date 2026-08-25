package com.naocraftlab.skins.server.plugin.common;

import com.naocraftlab.skins.server.SignedTexturesProperty;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


public final class ProxyRefreshProtocol {
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    public static final int NONCE_BYTES = 16;
    public static final byte WIRE_VERSION = 1;
    private static final int MAX_PROTOCOLS = 16;
    private static final int MAX_SMALL_STRING_BYTES = 64;
    private static final int MAX_TEXTURE_VALUE_BYTES = 12 * 1024;
    private static final int MAX_TEXTURE_SIGNATURE_BYTES = 2 * 1024;

    public byte[] encode(Message message) {
        Objects.requireNonNull(message, "message");
        validateNonce(message.nonce());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(WIRE_VERSION);
                output.writeByte(message.type().id);
                output.write(message.nonce());
                if (message instanceof Bind bind) {
                    if (bind.relayProtocols().isEmpty()
                            || bind.relayProtocols().size() > MAX_PROTOCOLS) {
                        throw new IllegalArgumentException("Relay protocol count is out of bounds");
                    }
                    output.writeByte(bind.relayProtocols().size());
                    for (String protocol : bind.relayProtocols()) {
                        writeString(output, protocol, MAX_SMALL_STRING_BYTES);
                    }
                    writeString(output, bind.pluginVersion().toString(), MAX_SMALL_STRING_BYTES);
                } else if (message instanceof Dirty dirty) {
                    writeRevision(output, dirty.revision());
                } else if (message instanceof State state) {
                    writeRevision(output, state.revision());
                    output.writeBoolean(state.signedTextures().isPresent());
                    if (state.signedTextures().isPresent()) {
                        SignedTexturesProperty property = state.signedTextures().orElseThrow();
                        writeString(output, property.value(), MAX_TEXTURE_VALUE_BYTES);
                        writeString(output, property.signature(), MAX_TEXTURE_SIGNATURE_BYTES);
                    }
                } else if (message instanceof Refresh refresh) {
                    writeRevision(output, refresh.revision());
                }
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Proxy refresh payload exceeds 16 KiB");
            }
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory relay encoding failed", impossible);
        }
    }

    public Message decode(byte[] payload) {
        try {
            PluginProtocolBytes.requireBounded(payload, MAX_PAYLOAD_BYTES, "proxy refresh payload");
        } catch (PluginProtocolBytes.ProtocolBytesException invalid) {
            throw new ProtocolException(invalid.getMessage(), invalid);
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int wireVersion = input.readUnsignedByte();
            if (wireVersion != WIRE_VERSION) {
                throw new ProtocolException("Unsupported proxy refresh wire version " + wireVersion);
            }
            Type type = Type.byId(input.readUnsignedByte());
            byte[] nonce = input.readNBytes(NONCE_BYTES);
            if (nonce.length != NONCE_BYTES) {
                throw new EOFException("Truncated nonce");
            }
            Message message = switch (type) {
                case BIND -> decodeBind(input, nonce);
                case DIRTY -> new Dirty(nonce, readRevision(input));
                case STATE -> decodeState(input, nonce);
                case REFRESH -> new Refresh(nonce, readRevision(input));
            };
            if (input.read() != -1) {
                throw new ProtocolException("Trailing proxy refresh bytes");
            }
            return message;
        } catch (EOFException truncated) {
            throw new ProtocolException("Truncated proxy refresh payload", truncated);
        } catch (IOException failure) {
            throw new ProtocolException("Unable to decode proxy refresh payload", failure);
        } catch (IllegalArgumentException invalid) {
            if (invalid instanceof ProtocolException protocol) {
                throw protocol;
            }
            throw new ProtocolException("Invalid proxy refresh payload", invalid);
        }
    }

    private static Bind decodeBind(DataInputStream input, byte[] nonce) throws IOException {
        int count = input.readUnsignedByte();
        if (count == 0 || count > MAX_PROTOCOLS) {
            throw new ProtocolException("Relay protocol count is out of bounds");
        }
        Set<String> protocols = new LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            String protocol = readString(input, MAX_SMALL_STRING_BYTES);
            if (!protocol.matches("[a-z][a-z0-9-]*-v[1-9][0-9]*")
                    || !protocols.add(protocol)) {
                throw new ProtocolException("Invalid or duplicate relay protocol");
            }
        }
        return new Bind(nonce, List.copyOf(protocols),
                SemanticVersion.parse(readString(input, MAX_SMALL_STRING_BYTES)));
    }

    private static State decodeState(DataInputStream input, byte[] nonce) throws IOException {
        long revision = readRevision(input);
        if (!input.readBoolean()) {
            return new State(nonce, revision, Optional.empty());
        }
        return new State(nonce, revision, Optional.of(new SignedTexturesProperty(
                readString(input, MAX_TEXTURE_VALUE_BYTES),
                readString(input, MAX_TEXTURE_SIGNATURE_BYTES))));
    }

    private static void writeRevision(DataOutputStream output, long revision) throws IOException {
        if (revision <= 0) {
            throw new IllegalArgumentException("Revision must be positive");
        }
        output.writeLong(revision);
    }

    private static long readRevision(DataInputStream input) throws IOException {
        long revision = input.readLong();
        if (revision <= 0) {
            throw new ProtocolException("Revision must be positive");
        }
        return revision;
    }

    private static void writeString(DataOutputStream output, String value, int maximumBytes)
            throws IOException {
        byte[] encoded = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > maximumBytes) {
            throw new IllegalArgumentException("Relay string length is out of bounds");
        }
        output.writeShort(encoded.length);
        output.write(encoded);
    }

    private static String readString(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > maximumBytes) {
            throw new ProtocolException("Relay string length is out of bounds");
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new EOFException("Truncated relay string");
        }
        return PluginProtocolBytes.decodeUtf8(value);
    }

    private static void validateNonce(byte[] nonce) {
        if (nonce == null || nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("Relay nonce must contain exactly 16 bytes");
        }
    }

    public sealed interface Message permits Bind, Dirty, State, Refresh {
        byte[] nonce();

        Type type();
    }

    public record Bind(
            byte[] nonce,
            List<String> relayProtocols,
            SemanticVersion pluginVersion) implements Message {
        public Bind {
            nonce = nonce.clone();
            relayProtocols = List.copyOf(Objects.requireNonNull(relayProtocols, "relayProtocols"));
            Objects.requireNonNull(pluginVersion, "pluginVersion");
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }

        @Override
        public Type type() {
            return Type.BIND;
        }
    }

    public record Dirty(byte[] nonce, long revision) implements Message {
        public Dirty {
            nonce = nonce.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }

        @Override
        public Type type() {
            return Type.DIRTY;
        }
    }

    public record State(
            byte[] nonce,
            long revision,
            Optional<SignedTexturesProperty> signedTextures) implements Message {
        public State {
            nonce = nonce.clone();
            signedTextures = Objects.requireNonNull(signedTextures, "signedTextures");
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }

        @Override
        public Type type() {
            return Type.STATE;
        }
    }

    public record Refresh(byte[] nonce, long revision) implements Message {
        public Refresh {
            nonce = nonce.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }

        @Override
        public Type type() {
            return Type.REFRESH;
        }
    }

    public enum Type {
        BIND(1),
        DIRTY(2),
        STATE(3),
        REFRESH(4);

        private final int id;

        Type(int id) {
            this.id = id;
        }

        private static Type byId(int id) {
            for (Type type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            throw new ProtocolException("Unknown proxy refresh message type " + id);
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
