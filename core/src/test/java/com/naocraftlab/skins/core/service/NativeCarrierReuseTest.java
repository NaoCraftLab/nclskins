package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.LibraryStorageAdapter;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

final class NativeCarrierReuseTest {
    @TempDir Path directory;
    private static byte[] png(int marker) throws Exception {
        var image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(8, 8, 0xff654321);
        image.setRGB(0, 0, marker);
        var output = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
    private static SavedPersonalSkinPreset save(LibraryService library, UUID account, byte[] bytes, String name, boolean exact) throws Exception {
        return library.savePresetWithPersonalSkin(account, Optional.empty(), name, name, SkinVariant.SLIM,
                PersonalSkinSource.FILE, bytes, OuterLayerVisibility.allVisible(), null, null, exact);
    }
    @Test void exactInitialSelectionSkipsUnequalThenFindsGenuineDuplicate() throws Exception {
        var storage = new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
        var port = new LibraryStorageAdapter(storage); var library = new LibraryService(port, port, Clock.systemUTC());
        UUID account = UUID.randomUUID(); byte[] old = png(0x00123456), incoming = png(0x00123457);
        assertEquals(new PngValidator().renderSha256(old), new PngValidator().renderSha256(incoming));
        save(library, account, old, "Original", false);
        var selected = save(library, account, incoming, "Selected", true);
        assertEquals(2, selected.state().personalSkins().size());
        assertArrayEquals(incoming, storage.readAsset(selected.asset().sha256()));
        var duplicate = save(library, account, incoming, "Must not rename", true);
        assertEquals(selected.asset().sha256(), duplicate.asset().sha256());
        assertEquals(2, duplicate.state().personalSkins().size());
        assertEquals(selected.state().personalSkins().stream().map(e -> e.sha256() + ":" + e.displayName()).toList(),
                duplicate.state().personalSkins().stream().map(e -> e.sha256() + ":" + e.displayName()).toList());
    }
    @Test void lockedRecheckDoesNotReplaceIncomingWithNewRenderEquivalentCandidate() throws Exception {
        var storage = new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
        var port = new LibraryStorageAdapter(storage); var other = new LibraryService(port, port, Clock.systemUTC());
        UUID account = UUID.randomUUID(); byte[] old = png(0x00123456), incoming = png(0x00123457);
        assertEquals(new PngValidator().renderSha256(old), new PngValidator().renderSha256(incoming));
        var armed = new AtomicBoolean(true);
        LibraryStatePort delayed = (LibraryStatePort) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{LibraryStatePort.class}, (proxy, method, args) -> {
            if (method.getName().equals("updateAccount") && armed.compareAndSet(true, false)) save(other, account, old, "Racing", false);
            try { return method.invoke(port, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        var selected = save(new LibraryService(delayed, port, Clock.systemUTC()), account, incoming, "Selected", true);
        assertFalse(armed.get());
        assertEquals(2, selected.state().personalSkins().size());
        assertArrayEquals(incoming, storage.readAsset(selected.asset().sha256()));
    }
    @Test void ordinaryRenderEquivalentEncodingStillReusesExistingCard() throws Exception {
        var storage = new NclSkinsStorage(directory, new PngValidator(), Clock.systemUTC());
        var port = new LibraryStorageAdapter(storage); var library = new LibraryService(port, port, Clock.systemUTC());
        UUID account = UUID.randomUUID(); byte[] old = png(0x00123456), incoming = png(0x00123457);
        var first = save(library, account, old, "Original", false);
        var duplicate = save(library, account, incoming, "Must not rename", false);
        assertEquals(first.asset().sha256(), duplicate.asset().sha256());
        assertEquals(1, duplicate.state().personalSkins().size());
        assertEquals("Original", duplicate.personalSkin().displayName());
    }
}
