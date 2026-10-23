package com.naocraftlab.skins.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;
import com.naocraftlab.skins.core.png.PngValidationException;

public interface CapeStagingFiles {
    Path createEncoded() throws IOException;
    Path createBacking() throws IOException;
    OutputStream writeEncoded(Path encoded) throws IOException;
    void delete(Path path) throws IOException;
    default PreparedCapeAsset prepare(Path encoded, Path backing) throws IOException, PngValidationException {
        throw new UnsupportedOperationException("Prepared cape staging unavailable");
    }
}
