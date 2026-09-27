package com.naocraftlab.skins.runtime;

public interface PublicSkinImports {
    ImportOperations.ImportDraft loadPlayer(String playerNameOrUuid) throws Exception;
    ImportOperations.ImportDraft loadUrl(String url) throws Exception;
}
