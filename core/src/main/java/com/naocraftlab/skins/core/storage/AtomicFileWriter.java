package com.naocraftlab.skins.core.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

final class AtomicFileWriter {
    private AtomicFileWriter() {}

    static void replace(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = temporarySibling(target);
        try {
            writeAndSync(temporary, bytes);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new StorageException(
                        StorageException.Code.ATOMIC_MOVE_UNSUPPORTED,
                        "Filesystem does not support atomic state replacement",
                        exception);
            }
            syncDirectory(target.getParent());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static boolean createImmutable(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            return false;
        }
        Path temporary = temporarySibling(target);
        try {
            writeAndSync(temporary, bytes);
            if (Files.exists(target)) {
                return false;
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                syncDirectory(target.getParent());
                return true;
            } catch (java.nio.file.FileAlreadyExistsException exception) {
                return false;
            } catch (AtomicMoveNotSupportedException exception) {
                throw new StorageException(
                        StorageException.Code.ATOMIC_MOVE_UNSUPPORTED,
                        "Filesystem does not support atomic asset creation",
                        exception);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeAndSync(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException exception) {


        }
    }

    private static Path temporarySibling(Path target) {
        return target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
    }
}
