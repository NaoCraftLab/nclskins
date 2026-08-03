package com.naocraftlab.skins.core.storage;

import java.io.IOException;


public interface ProcessFileLock extends AutoCloseable {
    @Override
    void close() throws IOException;
}
