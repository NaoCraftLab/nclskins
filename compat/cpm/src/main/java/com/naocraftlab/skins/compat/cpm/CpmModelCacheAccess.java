package com.naocraftlab.skins.compat.cpm;

import com.naocraftlab.skins.runtime.EffectiveSkinSource;
import java.util.UUID;

public interface CpmModelCacheAccess {
    void nclskins$refresh(UUID cacheId, EffectiveSkinSource current);
}
