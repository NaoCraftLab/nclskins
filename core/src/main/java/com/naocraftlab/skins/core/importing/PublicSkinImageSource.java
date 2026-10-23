package com.naocraftlab.skins.core.importing;

import com.naocraftlab.skins.core.png.NormalizedSkin;

public interface PublicSkinImageSource {
    @FunctionalInterface
    interface DependencySource {
        byte[] acquireDependency(java.net.URI uri, java.time.Duration timeout, int maximum) throws java.io.IOException;
    }
    Acquired acquireFile(java.nio.file.Path path) throws Exception;
    Acquired acquireUrl(String url) throws Exception;
    sealed interface Acquired permits PngInput, ModelInput { }
    record PngInput(NormalizedSkin skin) implements Acquired {
        public PngInput { java.util.Objects.requireNonNull(skin); }
    }
    record ModelInput(com.naocraftlab.skins.client.ImportInput input) implements Acquired {
        public ModelInput { java.util.Objects.requireNonNull(input); }
    }
    NormalizedSkin loadVerified(PublicProfileLookup.VerifiedTexture texture) throws Exception;
    NormalizedSkin loadUntrusted(String url) throws Exception;
}
