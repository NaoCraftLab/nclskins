package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.importing.*;
import com.naocraftlab.skins.core.model.*;
import com.naocraftlab.skins.core.png.PngValidator;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PreparedImportPortsTest {
    @Test
    void controlledSourcesKeepOrderErrorsAndOneAtomicCommit() throws Exception {
        var accounts = new PreparedCatalogServiceTest.MemoryAccounts();
        byte[] image = PreparedCatalogServiceTest.png(64, 64, 0);
        var serviceCatalog = new PreparedCatalogService((collection, skin, model) -> image, accounts);
        AtomicInteger commits = new AtomicInteger();
        ExternalImportCommit commit = (account, selected) -> {
            commits.incrementAndGet();
            assertEquals(List.of("First", "Last"), selected.stream().map(ImportOperations.ExternalImportCandidate::displayName).toList());
            return new ExternalImportCommit.BatchResult(accounts.state, selected.size(), 0);
        };
        var service = new ExternalAppearanceImportService(sources(image, false), serviceCatalog, commit);
        var context = new ExternalImportContext(accounts.current, "Player", Path.of("."));
        var owned = new OwnedCapeInventory(OwnedCapeInventory.CURRENT_SCHEMA_VERSION, accounts.current, List.of(), java.time.Instant.EPOCH);
        var review = service.prepareAppearances(accounts.current, ExternalImportSource.QUICK_SKIN, Optional.empty(), context, owned);
        assertEquals(1, review.skipped());
        assertEquals(List.of(0, 2), review.candidates().stream().map(ImportOperations.ExternalImportCandidate::sourceOrder).toList());
        assertEquals(0, commits.get());
        var result = service.commitAppearances(accounts.current, review.candidates(), review.skipped(), review.warnings());
        assertEquals(2, result.imported());
        assertEquals(1, commits.get());
        var failing = new ExternalAppearanceImportService(sources(image, false), serviceCatalog,
                (account, candidates) -> { commits.incrementAndGet(); throw new IOException("batch rejected"); });
        assertThrows(IOException.class, () -> failing.commitAppearances(accounts.current, review.candidates(), 1, 0));
        assertEquals(2, commits.get());
        accounts.state = AccountState.empty(accounts.current, java.time.Instant.EPOCH.plusSeconds(1));
        assertThrows(IOException.class, () -> service.commitAppearances(accounts.current, review.candidates(), 1, 0));
        assertEquals(2, commits.get());
    }

    @Test
    void cancellationPropagatesWithoutCommitOrEmptySuccess() throws Exception {
        var accounts = new PreparedCatalogServiceTest.MemoryAccounts();
        byte[] image = PreparedCatalogServiceTest.png(64, 64, 0);
        var catalog = new PreparedCatalogService((collection, skin, model) -> image, accounts);
        AtomicInteger commits = new AtomicInteger();
        var service = new ExternalAppearanceImportService(sources(image, true), catalog,
                (account, candidates) -> { commits.incrementAndGet(); return new ExternalImportCommit.BatchResult(accounts.state, 0, 0); });
        try {
            assertThrows(InterruptedException.class, () -> service.prepareAppearances(accounts.current,
                    ExternalImportSource.QUICK_SKIN, Optional.empty(),
                    new ExternalImportContext(accounts.current, "Player", Path.of(".")),
                    new OwnedCapeInventory(OwnedCapeInventory.CURRENT_SCHEMA_VERSION, accounts.current, List.of(), java.time.Instant.EPOCH)));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, commits.get());
        } finally { Thread.interrupted(); }
    }

    private static ExternalImportSourceAccess sources(byte[] image, boolean interrupt) {
        return new ExternalImportSourceAccess() {
            @Override public ExternalImportProbe probe(ExternalImportSource source, Optional<Path> root, ExternalImportContext context) {
                return ExternalImportProbe.AVAILABLE;
            }
            @Override public ExternalImportBatch discover(ExternalImportSource source, Optional<Path> root, ExternalImportContext context) {
                return new ExternalImportBatch(source, List.of(record("First", 0), record("Broken", 1), record("Last", 2)), List.of());
            }
            @Override public Resolution resolve(ExternalAppearanceRecord record) throws Exception {
                if (interrupt) throw new InterruptedException();
                if (record.sourceOrder() == 1) throw new IOException("unavailable");
                return new Resolution(new PngValidator().normalizeSkin(image), SkinVariant.CLASSIC, PersonalSkinSource.FILE);
            }
            private ExternalAppearanceRecord record(String name, int order) {
                return new ExternalAppearanceRecord("item-" + order, name, Optional.empty(),
                        new SkinLocator.EmbeddedPng(image), Optional.empty(), order);
            }
        };
    }
}
