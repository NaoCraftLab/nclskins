package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.png.NormalizedSkin;
import com.naocraftlab.skins.core.png.PngValidationException;
import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface LocalSkinImportSource {
    NormalizedSkin loadLocalSkin(Path path) throws IOException, PngValidationException;
}
