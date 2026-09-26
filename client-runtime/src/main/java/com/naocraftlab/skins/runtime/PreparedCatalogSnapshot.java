package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CatalogCollectionOrder;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

record PreparedCatalogSnapshot(long generation, List<Entry> entries) {
    PreparedCatalogSnapshot { entries = List.copyOf(entries); }

    enum Component { SKIN, CAPE }
    record LogicalKey(String source, String collection, String item, Component component, Optional<SkinVariant> model) {
        LogicalKey {
            Objects.requireNonNull(source); Objects.requireNonNull(collection); Objects.requireNonNull(item);
            Objects.requireNonNull(component); Objects.requireNonNull(model);
        }
    }
    record SourceIdentity(String sha256) { SourceIdentity { Objects.requireNonNull(sha256); } }
    record VisualIdentity(String sha256) { VisualIdentity { Objects.requireNonNull(sha256); } }
    record RawPixelIdentity(String sha256) { RawPixelIdentity { Objects.requireNonNull(sha256); } }
    record Entry(LogicalKey key, SourceIdentity source, VisualIdentity visual, RawPixelIdentity rawPixels,
            CatalogCollectionOrder provenance, int discoveryOrder, Optional<SkinVariant> detectedModel,
            SkinFeatureEvidence evidence) {
        Entry {
            Objects.requireNonNull(key); Objects.requireNonNull(source); Objects.requireNonNull(visual);
            Objects.requireNonNull(rawPixels); Objects.requireNonNull(provenance);
            Objects.requireNonNull(detectedModel); Objects.requireNonNull(evidence);
        }
    }
    CatalogEquivalenceIndex<Entry> equivalenceIndex() {
        return CatalogEquivalenceIndex.build(entries, Entry::visual);
    }
}
