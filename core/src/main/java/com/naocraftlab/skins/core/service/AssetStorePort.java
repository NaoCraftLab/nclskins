package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.png.PngInfo;
import com.naocraftlab.skins.core.png.PngValidationException;
import java.io.IOException;
import java.util.Objects;

public interface AssetStorePort {
    Asset storeAsset(byte[] bytes) throws IOException, PngValidationException;
    byte[] readAsset(String sha256) throws IOException, PngValidationException;

    record Asset(String sha256, PngInfo pngInfo, boolean alreadyPresent) {
        public Asset {
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(pngInfo, "pngInfo");
        }
    }
}
