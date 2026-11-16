package com.naocraftlab.skins.core.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

final class InterProcessLockManager {
    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    ProcessFileLock acquire(Path path) throws IOException {
        return acquire(path, null);
    }

    ProcessFileLock acquire(Path path, java.util.function.BooleanSupplier admission) throws IOException {
        if (admission != null && !admission.getAsBoolean()) throw new IOException("Lock admission retired");
        Path normalized = path.toAbsolutePath().normalize();
        Files.createDirectories(normalized.getParent());
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(normalized, ignored -> new ReentrantLock(true));
        jvmLock.lock();
        FileChannel channel = null;
        FileLock fileLock = null;
        try {
            if (admission != null && !admission.getAsBoolean()) throw new IOException("Lock admission retired");
            channel = admission == null
                    ? FileChannel.open(normalized, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                    : FileChannel.open(normalized, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
            fileLock = channel.lock();
            FileChannel acquiredChannel = channel;
            FileLock acquiredFileLock = fileLock;
            return new ProcessFileLock() {
                private boolean closed;

                @Override
                public synchronized void close() throws IOException {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    IOException failure = null;
                    try {
                        acquiredFileLock.release();
                    } catch (IOException exception) {
                        failure = exception;
                    }
                    try {
                        acquiredChannel.close();
                    } catch (IOException exception) {
                        if (failure == null) {
                            failure = exception;
                        } else {
                            failure.addSuppressed(exception);
                        }
                    } finally {
                        jvmLock.unlock();
                    }
                    if (failure != null) {
                        throw failure;
                    }
                }
            };
        } catch (IOException | RuntimeException exception) {
            if (fileLock != null) {
                try {
                    fileLock.release();
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            jvmLock.unlock();
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }
}
