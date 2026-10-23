package com.naocraftlab.skins.core.png;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class FrameReadFixture {
    public final AtomicInteger failures = new AtomicInteger();
    public final AtomicInteger reads = new AtomicInteger();
    public final AtomicInteger opens = new AtomicInteger();
    public final AtomicInteger transientOpenFailures = new AtomicInteger();
    public final AtomicInteger integrityOpenFailures = new AtomicInteger();
    public final AtomicBoolean closeNextRead = new AtomicBoolean();
    public volatile BooleanSupplier workerContext = () -> false;
    public volatile boolean lastOpenWasWorkerOwned;
    public final PreparedCapeAsset asset;

    public FrameReadFixture(PreparedCapeAsset source) throws IOException {
        asset = new PreparedCapeAsset(PreparedCapeAsset.Metadata.fromDescriptor(source.descriptorBytes()),
                new PreparedCapeAsset.Backing() {
                    public Path path() { return source.backing(); }
                    public PreparedCapeAsset.BackingLease open(PreparedCapeAsset.Metadata metadata) throws IOException {
                        opens.incrementAndGet();
                        lastOpenWasWorkerOwned = workerContext.getAsBoolean();
                        if (integrityOpenFailures.get() > 0 && integrityOpenFailures.getAndDecrement() > 0) {
                            throw new CapeIntegrityException("fixture backing open integrity failure");
                        }
                        if (transientOpenFailures.get() > 0 && transientOpenFailures.getAndDecrement() > 0) {
                            throw new IOException("fixture transient backing open failure");
                        }
                        return wrap(source.openBackingLease());
                    }
                    public PreparedCapeAsset.BackingLease openThumbnail(PreparedCapeAsset.Metadata metadata) throws IOException {
                        opens.incrementAndGet();
                        return wrap(source.openThumbnailLease());
                    }
                });
    }

    private PreparedCapeAsset.BackingLease wrap(PreparedCapeAsset.BackingLease delegate) {
        return new PreparedCapeAsset.BackingLease() {
            public byte[] readFrame(int index) throws IOException {
                reads.incrementAndGet();
                if (closeNextRead.getAndSet(false)) delegate.close();
                if (failures.get() > 0 && failures.getAndDecrement() > 0) throw new IOException("transient");
                return delegate.readFrame(index);
            }
            public PreparedCapeAsset.Thumbnail readThumbnail(int index) throws IOException { return delegate.readThumbnail(index); }
            public void verifyAll() throws IOException { delegate.verifyAll(); }
            public byte[] framePng(int index) throws IOException { return delegate.framePng(index); }
            public void close() throws IOException { delegate.close(); }
        };
    }
}
