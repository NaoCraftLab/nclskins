package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.core.model.FullPreviewModelFit;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("try")
final class PreviewModelFitStorageTest {
    @TempDir Path directory;

    @org.junit.jupiter.api.BeforeEach void realTemporaryRoot() throws Exception {
        directory = directory.toRealPath();
    }
    private static final UUID ACCOUNT = new UUID(0, 1);
    private static final String HASH = "a".repeat(64);
    private static final FullPreviewModelFit FIRST = new FullPreviewModelFit(2, 0.5, 4, 1, 2, -3, 8, 5, 0.25, 0.95, -0.125);
    private static final FullPreviewModelFit SECOND = new FullPreviewModelFit(2, 1, 1, -1, -2, 3, 1, 2, -0.25, 1.5, 0.5);

    private NclSkinsStorage storage(Path root) {
        return new NclSkinsStorage(root, new PngValidator(), Clock.systemUTC());
    }

    @Test void numericalDtoRejectsBakedNonfiniteAndInconsistentFullValues() {
        double[] valid = {2, 0.5, 4, 1, 2, -3, 8, 5, 0.25, 0.95, -0.125};
        for (int field = 0; field < valid.length; field++) {
            for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                double[] values = valid.clone(); values[field] = invalid;
                assertThrows(IllegalArgumentException.class, () -> fit(values));
            }
        }
        for (int field : new int[] {0, 1, 2}) {
            for (double invalid : new double[] {0, -1}) {
                double[] values = valid.clone(); values[field] = invalid;
                assertThrows(IllegalArgumentException.class, () -> fit(values));
            }
        }
        for (int field : new int[] {6, 7}) {
            double[] values = valid.clone(); values[field] = -1;
            assertThrows(IllegalArgumentException.class, () -> fit(values));
        }
        assertThrows(IllegalArgumentException.class, () -> new FullPreviewModelFit(2, 1.5, 4, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new FullPreviewModelFit(2, 1, 4, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new FullPreviewModelFit(Double.MIN_VALUE, Double.MIN_VALUE,
                Double.MAX_VALUE, 0, 0, 0, 0, 0, 0, 0, 0));
        assertEquals(1, new FullPreviewModelFit(2, 1, 1, -1, -2, -3, 0, 0, 0, 0, 0).normalization());
    }

    private static FullPreviewModelFit fit(double[] values) {
        return new FullPreviewModelFit(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9], values[10]);
    }

    @Test void invalidUtf8DoesNotBecomeAValidRecordThroughReplacementDecoding() throws Exception {
        var storage = storage(directory);
        assertEquals(FIRST, storage.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, FIRST, () -> true).fit());
        Path target = storage.layout().previewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC);
        byte[] invalid = Files.readAllBytes(target);
        invalid[5] = (byte) 0xff;
        Files.write(target, invalid);
        assertEquals(PreviewModelFitResult.Status.UNAVAILABLE, storage.loadPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC).status());
        assertEquals(PreviewModelFitResult.Status.UNAVAILABLE,
                storage.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, SECOND, () -> true).status());
        assertArrayEquals(invalid, Files.readAllBytes(target));
    }

    @Test void twoInstancesSerializeFirstCommitAndLoserReadsActualWinner() throws Exception {
        var first = storage(directory);
        var second = storage(directory);
        var publication = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var secondThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var secondWriter = new java.util.concurrent.atomic.AtomicInteger();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var winner = executor.submit(() -> first.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, FIRST, () -> {
                if (atImmutableWriter() && checks.incrementAndGet() == 2) {
                    publication.countDown();
                    try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                }
                return true;
            }));
            assertTrue(publication.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var loser = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                return second.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, SECOND, () -> {
                    if (atImmutableWriter()) secondWriter.incrementAndGet();
                    return true;
                });
            });
            awaitLockWait(secondThread);
            assertFalse(Files.exists(first.layout().previewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC)));
            release.countDown();
            assertEquals(FIRST, winner.get(5, java.util.concurrent.TimeUnit.SECONDS).fit());
            assertEquals(FIRST, loser.get(5, java.util.concurrent.TimeUnit.SECONDS).fit());
            assertEquals(0, secondWriter.get());
            assertEquals(FIRST, storage(directory).loadPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC).fit());
            try (var files = Files.list(first.layout().previewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC).getParent())) {
                assertEquals(1, files.count());
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test void libraryProducerPresetsReuseContentWhileVariantContentAndAccountStayDistinct() throws Exception {
        var storage = storage(directory);
        var adapter = new LibraryStorageAdapter(storage);
        var library = new com.naocraftlab.skins.core.service.LibraryService(adapter, adapter, Clock.systemUTC());
        byte[] png = syntheticPng(0xff112233);
        var first = library.savePresetWithPersonalSkin(ACCOUNT, java.util.Optional.empty(), "First", "First",
                SkinVariant.CLASSIC, com.naocraftlab.skins.core.model.PersonalSkinSource.FILE, png, null);
        var duplicate = library.savePresetWithPersonalSkin(ACCOUNT, java.util.Optional.empty(), "Duplicate", "Duplicate",
                SkinVariant.CLASSIC, com.naocraftlab.skins.core.model.PersonalSkinSource.FILE, png, null);
        assertNotEquals(first.preset().id(), duplicate.preset().id());
        var a = library.resolveSkin(duplicate.state(), first.asset().id());
        var b = library.resolveSkin(duplicate.state(), duplicate.asset().id());
        assertEquals(a.sha256(), b.sha256());
        assertArrayEquals(a.pngBytes(), b.pngBytes());
        Path state = storage.layout().accountState(ACCOUNT);
        byte[] originalState = Files.readAllBytes(state);
        byte[] originalAsset = Files.readAllBytes(storage.assetPath(a.sha256()));
        assertEquals(FIRST, storage.createPreviewModelFit(ACCOUNT, a.sha256(), a.variant(), FIRST, () -> true).fit());
        assertEquals(FIRST, storage.createPreviewModelFit(ACCOUNT, b.sha256(), b.variant(), SECOND, () -> true).fit());
        assertEquals(PreviewModelFitResult.Status.ABSENT, storage.loadPreviewModelFit(ACCOUNT, a.sha256(), SkinVariant.SLIM).status());
        assertEquals(SECOND, storage.createPreviewModelFit(ACCOUNT, a.sha256(), SkinVariant.SLIM, SECOND, () -> true).fit());
        UUID otherAccount = new UUID(0, 2);
        assertEquals(PreviewModelFitResult.Status.ABSENT, storage.loadPreviewModelFit(otherAccount, a.sha256(), a.variant()).status());
        assertEquals(SECOND, storage.createPreviewModelFit(otherAccount, a.sha256(), a.variant(), SECOND, () -> true).fit());
        var other = storage.storeAsset(syntheticPng(0xff445566));
        assertNotEquals(a.sha256(), other.sha256());
        assertEquals(PreviewModelFitResult.Status.ABSENT, storage.loadPreviewModelFit(ACCOUNT, other.sha256(), a.variant()).status());
        assertEquals(SECOND, storage.createPreviewModelFit(ACCOUNT, other.sha256(), a.variant(), SECOND, () -> true).fit());
        var fresh = storage(directory);
        assertEquals(FIRST, fresh.loadPreviewModelFit(ACCOUNT, a.sha256(), a.variant()).fit());
        assertEquals(SECOND, fresh.loadPreviewModelFit(ACCOUNT, a.sha256(), SkinVariant.SLIM).fit());
        assertEquals(SECOND, fresh.loadPreviewModelFit(otherAccount, a.sha256(), a.variant()).fit());
        assertEquals(SECOND, fresh.loadPreviewModelFit(ACCOUNT, other.sha256(), a.variant()).fit());
        assertArrayEquals(originalState, Files.readAllBytes(state));
        assertArrayEquals(originalAsset, Files.readAllBytes(storage.assetPath(a.sha256())));
        assertEquals(2, fresh.loadOrCreateAccount(ACCOUNT).presets().size());
    }

    @Test void fullEncodedContentNotRasterOrAssetIdDefinesTheNumericAssociation() throws Exception {
        byte[] original = syntheticPng(0xff112233);
        byte[] type = "tEXt".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] payload = "synthetic\\0different encoding".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var crc = new java.util.zip.CRC32();
        crc.update(type); crc.update(payload);
        var output = new java.io.ByteArrayOutputStream();
        var data = new java.io.DataOutputStream(output);
        data.write(original, 0, original.length - 12);
        data.writeInt(payload.length); data.write(type); data.write(payload); data.writeInt((int) crc.getValue());
        data.write(original, original.length - 12, 12);
        byte[] modified = output.toByteArray();
        new PngValidator().validate(original);
        new PngValidator().validate(modified);
        var imageA = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(original));
        var imageB = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(modified));
        assertArrayEquals(imageA.getRGB(0, 0, 64, 64, null, 0, 64), imageB.getRGB(0, 0, 64, 64, null, 0, 64));
        var a = new com.naocraftlab.skins.core.service.ResolvedSkinAsset(new UUID(0, 10), hash(original), SkinVariant.CLASSIC, original);
        var b = new com.naocraftlab.skins.core.service.ResolvedSkinAsset(a.assetId(), hash(modified), SkinVariant.CLASSIC, modified);
        assertNotEquals(a.sha256(), b.sha256());
        var storage = storage(directory);
        assertEquals(FIRST, storage.createPreviewModelFit(ACCOUNT, a.sha256(), a.variant(), FIRST, () -> true).fit());
        assertEquals(PreviewModelFitResult.Status.ABSENT, storage.loadPreviewModelFit(ACCOUNT, b.sha256(), b.variant()).status());
        assertEquals(SECOND, storage.createPreviewModelFit(ACCOUNT, b.sha256(), b.variant(), SECOND, () -> true).fit());
        assertEquals(FIRST, storage(directory).loadPreviewModelFit(ACCOUNT, a.sha256(), a.variant()).fit());
        assertEquals(SECOND, storage(directory).loadPreviewModelFit(ACCOUNT, b.sha256(), b.variant()).fit());
    }

    @Test void failedAtomicMoveDoesNotReportDurableSuccessOrLeavePartialTarget() throws Exception {
        var storage = storage(directory);
        Path target = storage.layout().previewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var result = storage.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, FIRST, () -> {
            if (atImmutableWriter() && checks.incrementAndGet() == 2) {
                try (var files = Files.list(target.getParent())) {
                    Path temporary = files.filter(path -> path.getFileName().toString().endsWith(".tmp")).findFirst().orElseThrow();
                    assertTrue(Files.size(temporary) > 0);
                    Files.delete(temporary);
                } catch (Exception failure) { throw new AssertionError(failure); }
            }
            return true;
        });
        assertEquals(2, checks.get());
        assertEquals(PreviewModelFitResult.Status.UNAVAILABLE, result.status());
        assertEquals(PreviewModelFitResult.Status.ABSENT, storage(directory).loadPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC).status());
        try (var files = Files.list(target.getParent())) { assertEquals(0, files.count()); }
    }

    private static String hash(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] syntheticPng(int color) throws Exception {
        var image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) image.setRGB(x, y, color);
        var output = new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(image, "PNG", output));
        return output.toByteArray();
    }

    @Test void missingOrMalformedInputsHaveNoFilesystemEffects() {
        Path root = directory.resolve("uninitialized");
        var storage = storage(root);
        assertEquals(PreviewModelFitResult.Status.UNAVAILABLE,
                storage.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, null, () -> true).status());
        assertFalse(Files.exists(root));
        for (String hash : java.util.Arrays.asList(null, "../" + HASH, HASH.toUpperCase(java.util.Locale.ROOT), HASH + "x", "")) {
            assertEquals(PreviewModelFitResult.Status.UNAVAILABLE,
                    storage.createPreviewModelFit(ACCOUNT, hash, SkinVariant.CLASSIC, FIRST, () -> true).status());
            assertEquals(PreviewModelFitResult.Status.UNAVAILABLE,
                    storage.loadPreviewModelFit(ACCOUNT, hash, SkinVariant.CLASSIC).status());
        }
        assertEquals(PreviewModelFitResult.Status.UNAVAILABLE,
                storage.createPreviewModelFit(ACCOUNT, HASH, null, FIRST, () -> true).status());
        assertEquals(PreviewModelFitResult.Status.UNAVAILABLE,
                storage.createPreviewModelFit(null, HASH, SkinVariant.CLASSIC, FIRST, () -> true).status());
        assertEquals(PreviewModelFitResult.Status.UNAVAILABLE,
                storage.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, FIRST, null).status());
        assertFalse(Files.exists(root));
    }

    @Test void targetInsertedAtPublishPhaseIsNotOverwritten() throws Exception {
        var storage = storage(directory);
        Path target = storage.layout().previewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var result = storage.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, FIRST, () -> {
            if (atImmutableWriter() && checks.incrementAndGet() == 2) {
                try { Files.writeString(target, "partial foreign record", java.nio.file.StandardOpenOption.CREATE_NEW); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }
            return true;
        });
        assertEquals(2, checks.get());
        assertEquals(PreviewModelFitResult.Status.UNAVAILABLE, result.status());
        assertEquals("partial foreign record", Files.readString(target));
        try (var files = Files.list(target.getParent())) { assertEquals(java.util.List.of(target), files.toList()); }
    }

    @Test void publishPhaseParentRetargetCannotMoveOrCleanForeignFiles() throws Exception {
        var storage = storage(directory);
        Path target = storage.layout().previewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC);
        Path parent = target.getParent();
        Path detached = parent.resolveSibling("detached");
        Path foreign = directory.resolve("foreign");
        Files.createDirectory(foreign);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var foreignBefore = new java.util.concurrent.atomic.AtomicReference<java.util.Map<String, String>>();
        var result = storage.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, FIRST, () -> {
            if (atImmutableWriter() && checks.incrementAndGet() == 2) {
                try {
                    Path temporary;
                    try (var paths = Files.list(parent)) {
                        temporary = paths.filter(path -> path.getFileName().toString().endsWith(".tmp")).findFirst().orElseThrow();
                    }
                    assertTrue(Files.size(temporary) > 0, "Retarget occurs after real temporary bytes are synced");
                    Files.writeString(foreign.resolve(temporary.getFileName()), "foreign temporary sentinel");
                    Files.writeString(foreign.resolve("sentinel"), "unrelated");
                    foreignBefore.set(snapshot(foreign));
                    Files.move(parent, detached);
                    Files.createSymbolicLink(parent, foreign);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
            return true;
        });
        assertEquals(2, checks.get());
        assertEquals(PreviewModelFitResult.Status.UNAVAILABLE, result.status());
        assertEquals(foreignBefore.get(), snapshot(foreign), "Neither publication nor finally-cleanup may follow the new parent");
        assertFalse(Files.exists(detached.resolve(target.getFileName())));
    }

    @Test void retirementAtActualPublicationCleansPartialAndPreservesExistingRecord() throws Exception {
        var storage = storage(directory);
        Path target = storage.layout().previewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var sawTemporary = new java.util.concurrent.atomic.AtomicBoolean();
        assertEquals(PreviewModelFitResult.Status.UNAVAILABLE,
                storage.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, FIRST, () -> {
                    if (atImmutableWriter() && checks.incrementAndGet() == 2) {
                        try (var files = Files.list(target.getParent())) {
                            sawTemporary.set(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
                        } catch (Exception failure) { throw new AssertionError(failure); }
                        return false;
                    }
                    return true;
                }).status());
        assertTrue(sawTemporary.get());
        assertFalse(Files.exists(target));
        try (var files = Files.list(target.getParent())) { assertEquals(0, files.count()); }
        assertEquals(FIRST, storage.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, FIRST, () -> true).fit());
        byte[] saved = Files.readAllBytes(target);
        assertEquals(PreviewModelFitResult.Status.UNAVAILABLE,
                storage.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, SECOND, () -> false).status());
        assertArrayEquals(saved, Files.readAllBytes(target));
        assertEquals(FIRST, storage(directory).loadPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC).fit());
    }

    @Test void retirementAfterRealAccountLockContentionNeverEntersWriter() throws Exception {
        var storage = storage(directory);
        storage.initialize();
        var current = new java.util.concurrent.atomic.AtomicBoolean(true);
        var writerChecks = new java.util.concurrent.atomic.AtomicInteger();
        var thread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<PreviewModelFitResult> pending;
        try {
            try (var held = new InterProcessLockManager().acquire(storage.layout().accountLock(ACCOUNT))) {
                pending = executor.submit(() -> {
                    thread.set(Thread.currentThread());
                    return storage(directory).createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, FIRST, () -> {
                        if (atImmutableWriter()) writerChecks.incrementAndGet();
                        return current.get();
                    });
                });
                awaitLockWait(thread);
                current.set(false);
            }
            assertEquals(PreviewModelFitResult.Status.UNAVAILABLE, pending.get(5, java.util.concurrent.TimeUnit.SECONDS).status());
            assertEquals(0, writerChecks.get(), "Retired waiter must stop before immutable writer admission");
            assertFalse(Files.exists(storage.layout().previewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC).getParent()));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test void lockRetargetedDuringContentionDoesNotCreateForeignFile() throws Exception {
        var storage = storage(directory);
        storage.initialize();
        Path lock = storage.layout().accountLock(ACCOUNT);
        Path foreign = directory.resolve("foreign-lock");
        var thread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<PreviewModelFitResult> pending;
        try {
            try (var held = new InterProcessLockManager().acquire(lock)) {
                pending = executor.submit(() -> {
                    thread.set(Thread.currentThread());
                    return storage(directory).createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, FIRST, () -> true);
                });
                awaitLockWait(thread);
                Files.move(lock, lock.resolveSibling("held.lock"));
                Files.createSymbolicLink(lock, foreign);
            }
            assertEquals(PreviewModelFitResult.Status.UNAVAILABLE, pending.get(5, java.util.concurrent.TimeUnit.SECONDS).status());
            assertFalse(Files.exists(foreign), "Rejected lock path must not be opened with CREATE after contention");
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private static boolean atImmutableWriter() {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                frame.getClassName().equals(AtomicFileWriter.class.getName()) && frame.getMethodName().equals("createImmutable")));
    }

    private static void awaitLockWait(java.util.concurrent.atomic.AtomicReference<Thread> reference) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread thread = reference.get();
            if (thread != null && thread.getState() == Thread.State.WAITING
                    && java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                    frame.getClassName().equals(InterProcessLockManager.class.getName()) && frame.getMethodName().equals("acquire"))) return;
            Thread.sleep(1);
        }
        fail("Writer did not reach actual contended account lock");
    }

    @Test void symlinkAndNonregularPathsRefuseBeforeFilesystemEffects() throws Exception {
        for (String component : java.util.List.of("root", "accounts", "account", "derived", "fit-parent",
                "target", "locks", "account-lock", "preflight-lock", "assets", "cache", "backups", "dangling", "directory-target")) {
            Path root = directory.resolve(component).resolve("storage");
            var storage = storage(root);
            Path target = storage.layout().previewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC);
            Path foreign = directory.resolve(component).resolve("foreign");
            Files.createDirectories(foreign);
            Path sentinel = foreign.resolve("sentinel");
            Files.writeString(sentinel, "unrelated");
            Path link = switch (component) {
                case "root" -> root;
                case "accounts" -> storage.layout().accounts();
                case "account" -> storage.layout().accountState(ACCOUNT).getParent();
                case "derived" -> target.getParent().getParent();
                case "fit-parent" -> target.getParent();
                case "locks" -> storage.layout().locks();
                case "account-lock" -> storage.layout().accountLock(ACCOUNT);
                case "preflight-lock" -> storage.layout().locks().resolve("storage-preflight.lock");
                case "assets" -> storage.layout().assetsSha256();
                case "cache" -> storage.layout().textureCache();
                case "backups" -> storage.layout().backups();
                default -> target;
            };
            Files.createDirectories(link.getParent());
            if (component.equals("directory-target")) Files.createDirectory(link);
            else Files.createSymbolicLink(link, component.equals("dangling") ? foreign.resolve("missing")
                    : component.endsWith("lock") || component.equals("target") ? sentinel : foreign);
            var before = snapshot(directory.resolve(component));
            assertEquals(PreviewModelFitResult.Status.UNAVAILABLE,
                    storage.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, FIRST, () -> true).status(), component);
            assertEquals(before, snapshot(directory.resolve(component)), component);
            if (!java.util.Set.of("locks", "account-lock", "preflight-lock", "assets", "cache", "backups").contains(component)) {
                assertEquals(PreviewModelFitResult.Status.UNAVAILABLE,
                        storage.loadPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC).status(), component);
            }
            assertEquals("unrelated", Files.readString(sentinel));
        }
    }

    private static java.util.Map<String, String> snapshot(Path root) throws Exception {
        var result = new java.util.TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                result.put(root.relativize(path).toString(), Files.isSymbolicLink(path)
                        ? "link:" + Files.readSymbolicLink(path)
                        : Files.isDirectory(path) ? "directory" : java.util.HexFormat.of().formatHex(Files.readAllBytes(path)));
            }
        }
        return result;
    }

    @Test void malformedNumericRecordsRemainUnavailableAndUnchanged() throws Exception {
        var storage = storage(directory);
        assertEquals(FIRST, storage.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, FIRST, () -> true).fit());
        Path target = storage.layout().previewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC);
        String valid = Files.readString(target);
        for (String bad : java.util.List.of(
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1.5"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                valid.replace("\"bodyHeight\":4.0", "\"bodyHeight\":0"),
                valid.replace("\"normalization\":0.5", "\"normalization\":0.75"),
                valid.replace("\"centerX\":1.0", "\"centerX\":\"1.0\""),
                valid.replace("\"centerY\":2.0", "\"centerY\":1e999"),
                valid.replace("\"centerX\":1.0", "\"centerX\":1e-999"),
                valid.replace("\"halfWidth\":8.0", "\"halfWidth\":-1"),
                valid.replace("\"referenceCenterX\":0.25", "\"referenceCenterX\":\"0.25\""),
                valid.replace("\"referenceCenterY\":0.95", "\"referenceCenterY\":1e999"),
                valid.replace("\"referenceCenterZ\":-0.125", "\"referenceCenterZ\":1e-999"),
                valid.replace("\"referenceCenterX\":0.25,", ""),
                valid.replace(",\"referenceCenterX\":0.25,\"referenceCenterY\":0.95,\"referenceCenterZ\":-0.125", ""),
                valid.replace("\"units\":\"entity\"", "\"units\":\"pixels\""),
                valid.replace("\"variant\":\"CLASSIC\"", "\"variant\":\"SLIM\""),
                valid.replace("\"accountId\":\"" + ACCOUNT, "\"accountId\":\"" + new UUID(0, 2)),
                valid.replace(HASH, "b".repeat(64)),
                valid.substring(0, valid.length() / 2), valid + " false",
                valid.replaceFirst("\\{", "{\"schemaVersion\":2,"),
                valid.replace("\"centerX\":1.0", "\"centerX\":NaN"),
                valid.replace("\"bodyHeight\":4.0,", ""), " ".repeat(8193))) {
            assertNotEquals(valid, bad);
            Files.writeString(target, bad);
            assertEquals(PreviewModelFitResult.Status.UNAVAILABLE,
                    storage.loadPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC).status(), bad);
            assertEquals(PreviewModelFitResult.Status.UNAVAILABLE,
                    storage.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, SECOND, () -> true).status());
            assertEquals(bad, Files.readString(target));
        }
        Files.writeString(target, valid);
        assertEquals(FIRST, storage(directory).loadPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC).fit());
    }

    @Test void retiredCreateDoesNotInitializeOrAcquireLock() {
        Path root = directory.resolve("uninitialized");
        var storage = storage(root);
        assertEquals(PreviewModelFitResult.Status.UNAVAILABLE,
                storage.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, FIRST, () -> false).status());
        assertFalse(Files.exists(root));
    }

    @Test void firstCreateSurvivesFreshReaderWithoutReplacingWinner() throws Exception {
        Path root = directory.resolve("storage");
        var writer = storage(root);
        assertEquals(PreviewModelFitResult.Status.ABSENT,
                writer.loadPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC).status());
        assertFalse(Files.exists(root));
        assertEquals(FIRST, writer.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, FIRST, () -> true).fit());
        var reader = storage(root);
        assertEquals(FIRST, reader.loadPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC).fit());
        assertEquals(FIRST, reader.createPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC, SECOND, () -> true).fit());
        assertEquals(FIRST, storage(root).loadPreviewModelFit(ACCOUNT, HASH, SkinVariant.CLASSIC).fit());
        assertFalse(Files.exists(writer.layout().accountState(ACCOUNT)));
    }
}
