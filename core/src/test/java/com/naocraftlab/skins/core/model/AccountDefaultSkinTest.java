package com.naocraftlab.skins.core.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccountDefaultSkinTest {
    @Test
    void coversEveryVanillaSlotAndBothModels() {
        String[] names = {"alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};
        for (int index = 0; index < 18; index++) {
            assertEquals(new AccountDefaultSkin(names[index % 9],
                    index < 9 ? SkinVariant.SLIM : SkinVariant.CLASSIC),
                    AccountDefaultSkin.forProfile(new UUID(0, index)));
        }
    }

    @Test
    void usesSignedFloorModAndAllUuidWords() {
        assertEquals(new AccountDefaultSkin("zuri", SkinVariant.CLASSIC),
                AccountDefaultSkin.forProfile(new UUID(0, 0xffffffffL)));
        assertEquals(new AccountDefaultSkin("sunny", SkinVariant.CLASSIC),
                AccountDefaultSkin.forProfile(new UUID(0, 0x80000000L)));
        assertEquals(new AccountDefaultSkin("ari", SkinVariant.SLIM),
                AccountDefaultSkin.forProfile(new UUID(0, 0x7fffffffL)));
        assertEquals(AccountDefaultSkin.forProfile(new UUID(0, 1 ^ 2 ^ 4 ^ 8)),
                AccountDefaultSkin.forProfile(new UUID((1L << 32) | 2, (4L << 32) | 8)));
    }
}
