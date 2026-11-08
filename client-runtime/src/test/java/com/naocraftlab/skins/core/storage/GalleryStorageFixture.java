package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.core.png.PngValidator;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;

public final class GalleryStorageFixture {
    @FunctionalInterface
    public interface Fault {
        void reached(String stage) throws IOException;
    }

    public static NclSkinsStorage create(Path root, Clock clock, Fault fault) {
        return new NclSkinsStorage(root, new PngValidator(), clock, stage -> fault.reached(stage.name()));
    }
}
