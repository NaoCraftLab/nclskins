package com.naocraftlab.skins.client;

import java.io.IOException;
import java.io.InputStream;

public interface ImportInput extends AutoCloseable {
    enum Kind { PNG, MODEL, PROJECT }
    Kind kind();
    long size();
    Reader open() throws IOException;
    @Override void close() throws IOException;

    abstract class Reader extends InputStream {
        public abstract long size();
        public abstract Kind kind();
        public abstract java.nio.file.Path file() throws IOException;
        public abstract int readAt(long at, byte[] bytes, int offset, int count) throws IOException;
    }
}
