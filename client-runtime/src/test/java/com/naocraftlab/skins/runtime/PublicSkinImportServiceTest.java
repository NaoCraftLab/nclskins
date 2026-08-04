package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.api.PublicSkinImportException;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.storage.TextureCacheException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PublicSkinImportServiceTest {
    @Test
    void mapsPlayerTextureFailuresToSafeTypedReasons() {
        assertEquals(
                PublicSkinImportException.Code.NETWORK_FAILURE,
                PublicSkinImportService.playerTextureFailure(textureFailure(
                        TextureCacheException.Code.NETWORK_FAILURE)).code());
        assertEquals(
                PublicSkinImportException.Code.PROFILE_REJECTED,
                PublicSkinImportService.playerTextureFailure(textureFailure(
                        TextureCacheException.Code.REDIRECT_REJECTED)).code());
        assertEquals(
                PublicSkinImportException.Code.OVERSIZED,
                PublicSkinImportService.playerTextureFailure(textureFailure(
                        TextureCacheException.Code.OVERSIZED)).code());
        assertEquals(
                PublicSkinImportException.Code.OVERSIZED,
                PublicSkinImportService.playerTextureFailure(new PngValidationException(
                        PngValidationException.Reason.OVERSIZED, "sensitive detail")).code());
        assertEquals(
                PublicSkinImportException.Code.PROFILE_REJECTED,
                PublicSkinImportService.playerTextureFailure(new PngValidationException(
                        PngValidationException.Reason.BAD_CHECKSUM, "sensitive detail")).code());
    }

    private static TextureCacheException textureFailure(TextureCacheException.Code code) {
        return new TextureCacheException(code, "sensitive detail");
    }
}
