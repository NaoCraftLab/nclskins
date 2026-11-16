package com.naocraftlab.skins.core.storage;

import com.naocraftlab.skins.core.model.FullPreviewModelFit;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.BooleanSupplier;

final class PreviewModelFitStore {
    private final PreviewModelFitJson json = new PreviewModelFitJson();
    private final NclSkinsStorage storage;
    private final InterProcessLockManager locks;

    PreviewModelFitStore(NclSkinsStorage storage, InterProcessLockManager locks) {
        this.storage = storage;
        this.locks = locks;
    }

    PreviewModelFitResult load(UUID account, String hash, SkinVariant variant) {
        try {
            Path target = storage.layout().previewModelFit(account, hash, variant);
            if (!PreviewModelFitPaths.regular(target)) return PreviewModelFitResult.absent();
            byte[] bytes;
            try (var input = Files.newInputStream(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(8193);
            }
            if (bytes.length > 8192) return PreviewModelFitResult.unavailable();
            return PreviewModelFitResult.present(json.decode(bytes, account, hash, variant));
        } catch (IOException | RuntimeException failure) {
            return PreviewModelFitResult.unavailable();
        }
    }

    private boolean writable(UUID account, Path target, BooleanSupplier current) {
        if (!current.getAsBoolean()) return false;
        try {
            PreviewModelFitPaths.forWrite(storage.layout(), account, target);
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    private boolean publishable(UUID account, Path target, BooleanSupplier current) {
        if (!writable(account, target, current)) return false;
        try {
            return !PreviewModelFitPaths.regular(target);
        } catch (IOException failure) {
            return false;
        }
    }

    @SuppressWarnings("try")
    PreviewModelFitResult create(UUID account, String hash, SkinVariant variant, FullPreviewModelFit fit,
            BooleanSupplier current) {
        try {
            Path target = storage.layout().previewModelFit(account, hash, variant);
            if (fit == null || current == null || !current.getAsBoolean()) return PreviewModelFitResult.unavailable();
            PreviewModelFitPaths.beforeInitialization(storage.layout(), account, target);
            storage.initialize();
            PreviewModelFitPaths.forWrite(storage.layout(), account, target);
            try (ProcessFileLock ignored = locks.acquire(storage.layout().accountLock(account),
                    () -> writable(account, target, current))) {
                if (!writable(account, target, current)) return PreviewModelFitResult.unavailable();
                PreviewModelFitResult existing = load(account, hash, variant);
                if (existing.status() != PreviewModelFitResult.Status.ABSENT) return existing;
                AtomicFileWriter.createImmutable(target, json.encode(account, hash, variant, fit),
                        () -> publishable(account, target, current), () -> PreviewModelFitPaths.safeParent(target));
                return load(account, hash, variant);
            }
        } catch (IOException | RuntimeException failure) {
            return PreviewModelFitResult.unavailable();
        }
    }
}
