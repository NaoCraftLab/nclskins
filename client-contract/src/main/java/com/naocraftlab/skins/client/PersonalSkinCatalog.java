package com.naocraftlab.skins.client;


public final class PersonalSkinCatalog {
    public static final String COLLECTION_ID = "nclskins_your_skins";
    public static final String SOURCE_ID = "nclskins_personal";

    private PersonalSkinCatalog() {
    }

    public static boolean isCollection(String collectionId) {
        return COLLECTION_ID.equals(collectionId);
    }
}
