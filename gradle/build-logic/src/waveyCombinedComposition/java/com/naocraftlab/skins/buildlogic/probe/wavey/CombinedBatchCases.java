package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Consumer;

public final class CombinedBatchCases {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_ROWS = 512;
    private static final Set<String> SCOPES = Set.of("world", "worldless");
    private static final Set<String> SCENARIOS = Set.of("live", "stale-before", "stale-after", "retire-build", "retire-draw",
            "own-exception", "foreign-exception", "draw-exception", "no-support", "wrong-material");
    private static final Set<String> ORDERS = Set.of("foreign-first", "foreign-last");
    private static final Set<String> STAGES = Set.of("normal", "ACCUMULATE", "DEPTH_BOUNDS", "TRANSMITTANCE");
    private static final Set<String> STYLES = Set.of("SMOOTH", "BLOCKY");
    private static final String REJECTION = "COMBINED_INTEGRATION_MISSING";

    public record Case(String name, int expectedExit, Path storageRoot, String scope, String scenario, String order, String stage, String style) {
        public Case {
            require(name != null && name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,239}"), "INVALID_CASE_NAME");
            require(storageRoot != null && storageRoot.isAbsolute() && storageRoot.normalize().equals(storageRoot), "INVALID_STORAGE_ROOT");
            require(scope != null && SCOPES.contains(scope), "INVALID_SCOPE");
            require(scenario != null && SCENARIOS.contains(scenario), "INVALID_SCENARIO");
            require(order != null && ORDERS.contains(order), "INVALID_ORDER");
            require(stage != null && STAGES.contains(stage), "INVALID_STAGE");
            require(style != null && STYLES.contains(style), "INVALID_STYLE");
            boolean rejection = scenario.equals("no-support") || scenario.equals("wrong-material");
            require(expectedExit == (rejection ? 1 : 0), "INVALID_EXPECTED_OUTCOME");
        }

        public String outcome() { return expectedExit == 0 ? "POSITIVE" : "EXPECTED_REJECTION"; }
        public String[] arguments() { return new String[]{storageRoot.toString(), scope, scenario, order, stage, style}; }
    }

    public record Manifest(String sha256, List<Case> cases) {
        public Manifest {
            require(sha256 != null && sha256.matches("[a-f0-9]{64}"), "INVALID_MANIFEST_HASH");
            require(cases != null && !cases.isEmpty() && cases.size() <= MAX_ROWS, "INVALID_CASE_COUNT");
            Set<String> names = new HashSet<>();
            Set<Path> roots = new HashSet<>();
            for (Case row : cases) {
                require(row != null && names.add(row.name()) && roots.add(row.storageRoot()), "DUPLICATE_OR_NULL_CASE");
            }
            cases = List.copyOf(cases);
        }
    }

    @FunctionalInterface public interface Body { void run(Case row) throws Exception; }
    @FunctionalInterface public interface StateAdapter { List<CaseLifecycle.Action> capture(Case row) throws Exception; }

    public static Manifest readManifest(Path file) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        require(bytes.length > 0 && bytes.length <= MAX_BYTES, "INVALID_MANIFEST_SIZE");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) { throw new IllegalArgumentException("INVALID_MANIFEST_UTF8", failure); }
        require(text.endsWith("\n") && text.indexOf('\r') < 0 && text.indexOf('\0') < 0, "INVALID_MANIFEST_LINES");
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        require(lines.length <= MAX_ROWS, "INVALID_CASE_COUNT");
        Path directory = file.toAbsolutePath().normalize().getParent();
        List<Case> cases = new ArrayList<>();
        for (String line : lines) {
            String[] fields = line.split("\t", -1);
            require(fields.length == 8 && Arrays.stream(fields).noneMatch(String::isEmpty), "INVALID_MANIFEST_ROW");
            require(fields[1].equals("0") || fields[1].equals("1"), "INVALID_EXPECTED_OUTCOME");
            Path storage = Path.of(fields[2]);
            Case row = new Case(fields[0], Integer.parseInt(fields[1]), storage, fields[3], fields[4], fields[5], fields[6], fields[7]);
            require(storage.toString().equals(fields[2]) && storage.equals(directory.resolve(row.name())), "STORAGE_ROOT_ESCAPE");
            require(!Files.isSymbolicLink(storage), "STORAGE_ROOT_SYMLINK");
            cases.add(row);
        }
        try {
            return new Manifest(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), cases);
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    public static void execute(Manifest manifest, Body body, StateAdapter state, Consumer<String> output) throws Exception {
        Objects.requireNonNull(manifest);
        Objects.requireNonNull(body);
        Objects.requireNonNull(state);
        Objects.requireNonNull(output);
        output.accept("COMBINED_BATCH_MANIFEST\t" + manifest.sha256() + "\t" + manifest.cases().size());
        int positive = 0, negative = 0;
        for (Case row : manifest.cases()) {
            output.accept(String.join("\t", "COMBINED_BATCH_BEGIN", row.name(), row.outcome(), row.scope(), row.scenario(), row.order(), row.stage(), row.style()));
            List<CaseLifecycle.Action> steps = List.copyOf(state.capture(row));
            CaseLifecycle.execute(() -> {
                runBody(row, body);
                output.accept(String.join("\t", "COMBINED_BATCH_OUTCOME", row.name(), row.outcome(), row.expectedExit() == 0 ? "NONE" : REJECTION));
            }, () -> cleanupAll(steps));
            output.accept("COMBINED_BATCH_RESET\t" + row.name());
            if (row.expectedExit() == 0) positive++; else negative++;
        }
        output.accept("COMBINED_BATCH_COMPLETE\t" + manifest.cases().size() + "\t" + positive + "\t" + negative);
    }

    private static void runBody(Case row, Body body) throws Exception {
        try { body.run(row); }
        catch (AssertionError failure) {
            if (row.expectedExit() != 1 || failure.getClass() != AssertionError.class || !REJECTION.equals(failure.getMessage())
                    || failure.getCause() != null || failure.getSuppressed().length != 0) throw failure;
            return;
        }
        if (row.expectedExit() == 1) throw new AssertionError("COMBINED_EXPECTED_REJECTION_MISSING");
    }

    public static void cleanupAll(List<CaseLifecycle.Action> steps) throws Exception {
        Throwable primary = null;
        for (CaseLifecycle.Action step : steps) {
            try { step.run(); }
            catch (Exception | Error failure) {
                if (primary == null) primary = failure;
                else if (primary != failure) primary.addSuppressed(failure);
            }
        }
        if (primary instanceof Error error) throw error;
        if (primary instanceof Exception failure) throw failure;
    }

    private static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
}
