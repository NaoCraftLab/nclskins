package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.PersonalCapeEntry;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;
import java.io.IOException;
import java.util.UUID;

public interface CatalogAccountAccess {
    UUID currentAccountId() throws IOException;
    AccountState load(UUID accountId) throws IOException;
    byte[] readAsset(String hash) throws IOException, PngValidationException;
    byte[] resolveSkin(AccountState account, UUID assetId) throws IOException, PngValidationException;
    PersonalCapeEntry importCape(UUID accountId, String name, byte[] bytes, com.naocraftlab.skins.core.service.AccountWriteGuard guard) throws IOException, PngValidationException;
    default PersonalCapeEntry importPreparedCape(UUID accountId, String name, PreparedCapeAsset prepared,
            com.naocraftlab.skins.core.service.AccountWriteGuard guard) throws IOException, PngValidationException {
        throw new UnsupportedOperationException("Prepared cape import unavailable");
    }
    void requireCurrent(UUID accountId) throws IOException;
}
