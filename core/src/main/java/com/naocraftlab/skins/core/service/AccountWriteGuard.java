package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.core.model.AccountState;
import java.io.IOException;

@FunctionalInterface
public interface AccountWriteGuard {
    void verify(AccountState current) throws IOException;
}
