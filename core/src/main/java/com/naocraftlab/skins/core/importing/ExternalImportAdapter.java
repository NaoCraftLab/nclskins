package com.naocraftlab.skins.core.importing;

import java.io.IOException;
import java.nio.file.Path;


public interface ExternalImportAdapter {
    ExternalImportSource source();

    boolean probe(Path root, ExternalImportContext context) throws IOException;

    ExternalImportBatch discover(Path root, ExternalImportContext context) throws IOException;
}
