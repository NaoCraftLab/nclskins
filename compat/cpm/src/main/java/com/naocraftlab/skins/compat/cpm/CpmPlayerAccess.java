package com.naocraftlab.skins.compat.cpm;

import com.naocraftlab.skins.runtime.EffectiveSkinSource;

public interface CpmPlayerAccess {
    EffectiveSkinSource nclskins$source();
    EffectiveSkinSource nclskins$accessorySource();
    void nclskins$source(EffectiveSkinSource source);
}
