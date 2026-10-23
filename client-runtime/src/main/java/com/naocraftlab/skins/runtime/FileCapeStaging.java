package com.naocraftlab.skins.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import com.naocraftlab.skins.core.png.CapeAssetDecoder;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;
import com.naocraftlab.skins.core.png.PngValidationException;

public final class FileCapeStaging implements CapeStagingFiles {
    @Override public PreparedCapeAsset prepare(Path encoded, Path backing) throws IOException, PngValidationException {
        return new CapeAssetDecoder().prepare(encoded, backing);
    }
    @Override public Path createEncoded() throws IOException {
        return Files.createTempFile("nclskins-pack-cape-", ".encoded");
    }

    @Override public Path createBacking() throws IOException {
        Path path = Files.createTempFile("nclskins-pack-cape-", ".frames");
        Files.delete(path);
        return path;
    }

    @Override public OutputStream writeEncoded(Path encoded) throws IOException {
        return Files.newOutputStream(encoded, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override public void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }
}
