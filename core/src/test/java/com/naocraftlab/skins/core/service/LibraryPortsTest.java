package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.test.TestPng;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class LibraryPortsTest {
    @Test
    @SuppressWarnings("unchecked")
    void batchImportUsesOneGuardedTransactionWithoutFilesystemAdapter() throws Exception {
        UUID accountId = UUID.randomUUID();
        var state = new AtomicReference<>(AccountState.empty(accountId, Instant.EPOCH));
        var writes = new AtomicInteger();
        LibraryStatePort libraryState = (LibraryStatePort) Proxy.newProxyInstance(
                LibraryStatePort.class.getClassLoader(), new Class<?>[]{LibraryStatePort.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "loadOrCreateAccount" -> state.get();
                    case "updateAccount" -> {
                        if (args.length == 3) ((AccountWriteGuard) args[2]).verify(state.get());
                        AccountState next = ((UnaryOperator<AccountState>) args[1]).apply(state.get());
                        writes.incrementAndGet();
                        state.set(next);
                        yield next;
                    }
                    default -> throw new AssertionError("Unexpected operation: " + method.getName());
                });
        Map<String, byte[]> bytes = new HashMap<>();
        AssetStorePort assets = new AssetStorePort() {
            public Asset storeAsset(byte[] png) throws com.naocraftlab.skins.core.png.PngValidationException {
                try {
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
                    boolean present = bytes.putIfAbsent(hash, png.clone()) != null;
                    return new Asset(hash, new PngValidator().validate(png), present);
                } catch (java.security.NoSuchAlgorithmException impossible) {
                    throw new AssertionError(impossible);
                }
            }
            public byte[] readAsset(String hash) { return bytes.get(hash).clone(); }
        };
        var library = new LibraryService(libraryState, assets, Clock.systemUTC());
        byte[] png = TestPng.create(64, 64);
        var imports = List.of(
                new LibraryService.PersonalSkinPresetImport("Classic", "Skin", SkinVariant.CLASSIC,
                        PersonalSkinSource.FILE, png, null),
                new LibraryService.PersonalSkinPresetImport("Slim", "Skin", SkinVariant.SLIM,
                        PersonalSkinSource.FILE, png, null));
        var result = library.importPersonalSkinPresets(accountId, imports);
        assertEquals(2, result.state().presets().size());
        assertEquals(1, writes.get());
        assertThrows(java.io.IOException.class, () -> library.importPersonalSkinPresets(accountId, imports,
                current -> { throw new java.io.IOException("stale account"); }));
        assertEquals(1, writes.get());
        assertEquals(result.state(), state.get());
    }
}
