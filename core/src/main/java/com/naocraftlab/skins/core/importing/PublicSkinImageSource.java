package com.naocraftlab.skins.core.importing;

import com.naocraftlab.skins.core.png.NormalizedSkin;

public interface PublicSkinImageSource {
    NormalizedSkin loadVerified(PublicProfileLookup.VerifiedTexture texture) throws Exception;
    NormalizedSkin loadUntrusted(String url) throws Exception;
}
