package com.naocraftlab.skins.diagnostics;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


public final class SanitizedFailure {
    static final int MAX_CAUSES = 8;
    static final int MAX_FRAMES = 64;

    private final List<Node> causes;

    private SanitizedFailure(List<Node> causes) {
        this.causes = List.copyOf(causes);
    }

    public static SanitizedFailure from(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        List<Node> nodes = new ArrayList<>(MAX_CAUSES);
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        Throwable current = failure;
        int remainingFrames = MAX_FRAMES;
        while (current != null && nodes.size() < MAX_CAUSES
                && seen.put(current, Boolean.TRUE) == null) {
            StackTraceElement[] source = current.getStackTrace();
            int length = Math.min(source.length, remainingFrames);
            StackTraceElement[] sanitized = new StackTraceElement[length];
            for (int index = 0; index < length; index++) {
                StackTraceElement frame = source[index];
                sanitized[index] = new StackTraceElement(
                        safeIdentifier(frame.getClassName()),
                        safeIdentifier(frame.getMethodName()),
                        safeFileName(frame.getFileName()),
                        frame.getLineNumber());
            }
            remainingFrames -= length;
            nodes.add(new Node(current.getClass().getName(), sanitized));
            current = current.getCause();
        }
        return new SanitizedFailure(nodes);
    }

    public Throwable asThrowable() {
        DiagnosticFailure root = null;
        DiagnosticFailure previous = null;
        for (Node node : causes) {
            DiagnosticFailure next = new DiagnosticFailure(node.type());
            next.setStackTrace(node.frames());
            if (previous == null) {
                root = next;
            } else {
                previous.initCause(next);
            }
            previous = next;
        }
        return Objects.requireNonNull(root, "root");
    }

    public int causeCount() {
        return causes.size();
    }

    public int frameCount() {
        return causes.stream().mapToInt(node -> node.frames().length).sum();
    }

    private static String safeIdentifier(String value) {
        if (value == null || value.isBlank() || value.length() > 512) {
            return "unknown";
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(Character.isJavaIdentifierPart(character)
                    || character == '.' || character == '$' || character == '<'
                    || character == '>')) {
                return "unknown";
            }
        }
        return value;
    }

    private static String safeFileName(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(Character.isLetterOrDigit(character) || character == '.'
                    || character == '_' || character == '$' || character == '-')) {
                return null;
            }
        }
        return value;
    }

    private record Node(String type, StackTraceElement[] frames) {
        private Node {
            Objects.requireNonNull(type, "type");
            frames = frames.clone();
        }

        @Override
        public StackTraceElement[] frames() {
            return frames.clone();
        }
    }

    private static final class DiagnosticFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String type;

        private DiagnosticFailure(String type) {
            this.type = type;
        }

        @Override
        public String getMessage() {
            return type;
        }
    }
}
