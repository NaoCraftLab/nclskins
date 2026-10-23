package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CpmModelFileConversionTest {
    @Test void absentEnvironmentDoesNotLoadOptionalExporter() throws Exception {
        assertTrue(SkinExtensionEnvironmentSource.unknown().modelFileConversion().isEmpty());
        for (var method : CpmModelFileConversion.class.getDeclaredMethods()) assertNotNull(method.getReturnType());
    }
    @Test void rasterDefensivelyOwnsBothDirections() {
        int[] pixels = new int[4096]; pixels[0] = 0x00123456;
        var raster = new CpmModelFileConversion.Raster(SkinModel.SLIM, pixels);
        pixels[0] = 0; assertEquals(0x00123456, raster.argb()[0]);
        raster.argb()[0] = 0; assertEquals(0x00123456, raster.argb()[0]);
        assertEquals(SkinModel.SLIM, raster.variant());
        assertThrows(IllegalArgumentException.class, () -> new CpmModelFileConversion.Raster(SkinModel.CLASSIC, new int[1]));
    }
    @Test void ioFailureRemainsDistinctFromModelFormat() {
        var reason = CpmModelFileConversion.Reason.valueOf("IO_FAILED");
        assertNotEquals(CpmModelFileConversion.Reason.INVALID_MODEL, reason);
        var failure = new CpmModelFileConversion.Failure(reason);
        assertEquals(reason, failure.reason());
        assertEquals("IO_FAILED", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }
    @Test void failuresDoNotRetainUpstreamData() {
        var failure = new CpmModelFileConversion.Failure(CpmModelFileConversion.Reason.INVALID_MODEL);
        assertEquals("INVALID_MODEL", failure.getMessage()); assertNull(failure.getCause());
    }
}
