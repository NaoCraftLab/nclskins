package com.naocraftlab.skins.runtime;

public interface PublicSkinImports {
    ImportOperations.AcquiredInput acquireUrl(String url,
            java.util.Optional<com.naocraftlab.skins.client.CpmModelFileConversion> converter,
            com.naocraftlab.skins.client.CpmModelFileConversion.Reason unavailable) throws Exception;
    ImportOperations.AcquiredInput acquireFile(java.nio.file.Path path,
            java.util.Optional<com.naocraftlab.skins.client.CpmModelFileConversion> converter,
            com.naocraftlab.skins.client.CpmModelFileConversion.Reason unavailable) throws Exception;
    ImportOperations.ImportDraft loadPlayer(String playerNameOrUuid) throws Exception;
    ImportOperations.ImportDraft loadUrl(String url) throws Exception;
}
