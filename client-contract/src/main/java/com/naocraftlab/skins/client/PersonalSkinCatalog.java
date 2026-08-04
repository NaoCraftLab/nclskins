package com.naocraftlab.skins.client;


public final class PersonalSkinCatalog {
    public static final String COLLECTION_ID = "nclskins_your_skins";
    public static final String SOURCE_ID = "nclskins_personal";
    public static final String OTHER_PLAYERS_COLLECTION_ID = "nclskins_other_players";
    public static final String OTHER_PLAYERS_SOURCE_ID = "nclskins_player_imports";

    private PersonalSkinCatalog() {
    }

    public static boolean isCollection(String collectionId) {
        return COLLECTION_ID.equals(collectionId)
                || OTHER_PLAYERS_COLLECTION_ID.equals(collectionId);
    }

    public static boolean isOtherPlayersCollection(String collectionId) {
        return OTHER_PLAYERS_COLLECTION_ID.equals(collectionId);
    }
}
