package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.naocraftlab.skins.runtime.CapeProjection.*;
import java.util.UUID;

public final class EffectiveCapeResolver {
    public static Result resolve(Snapshot snapshot, UUID profileId, String canonicalName,
            Candidate offline, Candidate minecraft, boolean self) {
        Identity identity = new Identity(profileId, canonicalName);
        Candidate optifine = snapshot.optifine().get(identity);
        Candidate skinmc = snapshot.skinmc().get(identity);
        Candidate sneaky = snapshot.sneaky().get(identity);
        if (self && identity.equals(snapshot.selfIdentity())) {
            offline = snapshot.selfOffline();
            minecraft = snapshot.selfMinecraft();
        }
        for (BuiltinProvider provider : snapshot.order()) {
            Candidate candidate = switch (provider) {
                case OFFLINE -> self ? offline : null;
                case MINECRAFT -> minecraft;
                case OPTIFINE -> optifine;
                case SKINMC -> skinmc;
                case SNEAKY -> sneaky;
            };
            if (candidate != null && candidate.capeLocation() != null) {
                return new Result(candidate.capeLocation(), candidate.elytraLocation(),
                        candidate.hasElytra(), provider);
            }
        }
        return new Result(null, null, false, null);
    }

    private EffectiveCapeResolver() {}
}
