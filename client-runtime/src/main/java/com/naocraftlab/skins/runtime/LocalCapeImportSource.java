package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.png.PngValidationException;
import java.io.IOException;
import java.nio.file.Path;

public interface LocalCapeImportSource {
    Source read(Path path) throws IOException, PngValidationException;
    record Source(String fileName, byte[] bytes) {
        public Source { java.util.Objects.requireNonNull(fileName); bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
