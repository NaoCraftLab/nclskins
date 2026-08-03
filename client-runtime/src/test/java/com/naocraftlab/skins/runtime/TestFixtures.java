package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.AppearancePreset;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.SkinAsset;
import com.naocraftlab.skins.core.model.SkinReference;
import com.naocraftlab.skins.core.model.SkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.service.SessionFailureContext;
import com.naocraftlab.skins.core.service.SessionStatus;
import com.naocraftlab.skins.core.service.SessionValidation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class TestFixtures {
    static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID CLASSIC_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID SLIM_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");

    private TestFixtures() {}

    static AccountState account(int presetCount) {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        List<SkinAsset> skins = List.of(
                new SkinAsset(
                        CLASSIC_ID,
                        "Steve",
                        "1".repeat(64),
                        SkinVariant.CLASSIC,
                        SkinSource.VANILLA_DEFAULT,
                        base,
                        base),
                new SkinAsset(
                        SLIM_ID,
                        "Alex",
                        "2".repeat(64),
                        SkinVariant.SLIM,
                        SkinSource.VANILLA_DEFAULT,
                        base,
                        base));
        List<AppearancePreset> presets = new ArrayList<>();
        for (int index = 0; index < presetCount; index++) {
            Instant timestamp = base.plusSeconds(index + 1L);
            presets.add(new AppearancePreset(
                    new UUID(2L, index + 1L),
                    "Preset " + (index + 1),
                    SkinReference.asset(index % 2 == 0 ? CLASSIC_ID : SLIM_ID),
                    index % 2 == 0 ? null : "cape-" + index,
                    timestamp,
                    timestamp));
        }
        return new AccountState(
                AccountState.CURRENT_SCHEMA_VERSION,
                ACCOUNT_ID,
                skins,
                presets,
                base.plusSeconds(presetCount + 1L));
    }

    static SessionValidation validSession() {
        RemoteProfile profile = new RemoteProfile(ACCOUNT_ID, "Player", List.of(), List.of(), Set.of());
        return new SessionValidation(
                SessionStatus.VALID,
                new GameSessionTokenSource.SessionIdentity(ACCOUNT_ID, "Player"),
                profile,
                (SessionFailureContext) null,
                "valid");
    }

    static ClientSnapshot ready(AccountState account, UUID activePreset, int offset) {
        SessionValidation session = validSession();
        return new ClientSnapshot(
                ClientSnapshot.Lifecycle.READY,
                Optional.of(account),
                Optional.of(session),
                session.optionalProfile(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.ofNullable(activePreset),
                Optional.empty(),
                UiMessage.success("nclskins.status.profile_loaded"),
                false,
                false,
                offset,
                1L);
    }
}
