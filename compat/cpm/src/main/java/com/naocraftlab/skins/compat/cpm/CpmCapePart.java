package com.naocraftlab.skins.compat.cpm;

import com.tom.cpm.shared.model.PartValues;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.model.render.VanillaModelPart;

public enum CpmCapePart implements VanillaModelPart {
    ORDINARY;

    @Override public String getName() { return "nclskins:ordinary_cape"; }
    @Override public PartValues getDefaultSize(SkinType skinType) { return RootModelType.CAPE.getDefaultSize(skinType); }
}
