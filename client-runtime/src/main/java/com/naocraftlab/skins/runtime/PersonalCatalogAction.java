package com.naocraftlab.skins.runtime;

import java.util.Objects;

record PersonalCatalogAction(String collectionId, String sha256) {
    PersonalCatalogAction {
        Objects.requireNonNull(collectionId, "collectionId");
        Objects.requireNonNull(sha256, "sha256");
    }
}
