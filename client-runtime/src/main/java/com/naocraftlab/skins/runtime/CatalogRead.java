package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.CapeCatalogSource;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public interface CatalogRead {
    List<SkinCatalogSource.CollectionDescriptor> catalogCollections() throws Exception;
    Map<CatalogVariant, SkinFeatureEvidence> catalogFeatureEvidence();
    CapeEditorData loadCapeEditorData(UUID accountId) throws Exception;
    long capeCatalogGeneration();
    default boolean capeCatalogCapacityIncomplete() { return false; }
    default CapacityWatch capeCatalogCapacityWatch(Consumer<CapacityWatch> ready) {
        throw new UnsupportedOperationException("Cape catalog capacity notice unavailable");
    }
    default boolean supportsStartupResourceCapeWarmup() { return false; }
    void warmResourceCapeCatalog(long generation) throws Exception;
    void warmCapeCatalog(UUID accountId, long generation) throws Exception;
    Optional<CapeEditorData> warmedCapeEditorData(UUID accountId);
    Map<String, byte[]> warmedCapePreviews(UUID accountId);
    Optional<byte[]> loadResourceCapePreview(UUID accountId, ResourceCapeSelection selection) throws Exception;
    default Optional<PreparedCapeLease> acquirePreparedResourceCape(UUID accountId,
            ResourceCapeSelection selection) throws Exception {
        return Optional.empty();
    }

    interface PreparedCapeLease extends AutoCloseable {
        PreparedCapeAsset asset();
        @Override void close() throws IOException;
    }

    interface CapacityWatch extends AutoCloseable {
        void arm();
        @Override void close();
    }

    record ResourceCapeKey(String collectionId, String capeId) {
        public ResourceCapeKey {
            Objects.requireNonNull(collectionId, "collectionId");
            Objects.requireNonNull(capeId, "capeId");
        }
    }

    record ResourceCapeSelection(
            String collectionId,
            String capeId,
            String displayName,
            String contentIdentity,
            String sourceSha256,
            long generation,
            boolean hasElytra) {
        public ResourceCapeSelection {
            Objects.requireNonNull(collectionId, "collectionId");
            Objects.requireNonNull(capeId, "capeId");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(contentIdentity, "contentIdentity");
            Objects.requireNonNull(sourceSha256, "sourceSha256");
            if (collectionId.isBlank() || capeId.isBlank() || displayName.isBlank()
                    || !contentIdentity.matches("[0-9a-f]{64}")
                    || !sourceSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid resource-pack cape selection");
            }
        }

        public ResourceCapeKey key() {
            return new ResourceCapeKey(collectionId, capeId);
        }
    }

    record CapeEditorData(
            AccountState account,
            List<CapeCatalogSource.CollectionDescriptor> resourceCollections,
            Map<ResourceCapeKey, String> sourceHashes,
            long resourceGeneration) {
        public CapeEditorData {
            account = Objects.requireNonNull(account, "account");
            resourceCollections = List.copyOf(Objects.requireNonNull(
                    resourceCollections, "resourceCollections"));
            sourceHashes = Map.copyOf(Objects.requireNonNull(sourceHashes, "sourceHashes"));
        }
    }

    record CatalogVariant(String collectionId, String skinId, SkinVariant variant) {
        public CatalogVariant {
            Objects.requireNonNull(collectionId, "collectionId");
            Objects.requireNonNull(skinId, "skinId");
            Objects.requireNonNull(variant, "variant");
        }
    }


}
