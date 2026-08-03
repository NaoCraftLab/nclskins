package com.naocraftlab.skins.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class ServerContractTest {
    private static final UUID PROFILE_ID = UUID.nameUUIDFromBytes(
            "portable-contract-player".getBytes(StandardCharsets.UTF_8));
    private static final ServerPlayerIdentity IDENTITY =
            new ServerPlayerIdentity(PROFILE_ID, "PortablePlayer");
    private static final TextureAppearance APPEARANCE = TextureAppearance.verified(
            Optional.of("a".repeat(64)),
            Optional.of(TextureAppearance.SkinModel.SLIM),
            Optional.empty(),
            Optional.empty());

    @Test
    void connectionValuesArePlatformNeutralValidatedAndRedacted() {
        ConnectionKey key = new ConnectionKey(PROFILE_ID, 12L);
        ConnectionSnapshot snapshot = new ConnectionSnapshot(
                key, IDENTITY.profileName(), IdentityAssurance.ONLINE);

        assertEquals(IDENTITY, snapshot.identity());
        assertEquals(key, snapshot.key());
        assertEquals(IdentityAssurance.ONLINE, snapshot.assurance());
        assertFalse(key.toString().contains(PROFILE_ID.toString()));
        assertFalse(snapshot.toString().contains(IDENTITY.profileName()));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionKey(PROFILE_ID, -1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConnectionSnapshot(key, " ", IdentityAssurance.ONLINE));
    }

    @Test
    void signedPropertiesUseIdentityEqualityAndNeverDiscloseContent() {
        SignedTexturesProperty first = new SignedTexturesProperty(
                "value-secret", "signature-secret");
        SignedTexturesProperty sameContent = new SignedTexturesProperty(
                "value-secret", "signature-secret");

        assertEquals(first, first);
        assertNotEquals(first, sameContent);
        assertFalse(first.toString().contains(first.value()));
        assertFalse(first.toString().contains(first.signature()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SignedTexturesProperty("", "signature"));
    }

    @Test
    void verifiedProfilesEnforceDefaultPropertyParityAndRedactAllFields() {
        SignedTexturesProperty property = new SignedTexturesProperty("value", "signature");
        VerifiedOfficialProfile custom = new VerifiedOfficialProfile(
                IDENTITY, APPEARANCE, Optional.of(property));
        VerifiedOfficialProfile accountDefault = new VerifiedOfficialProfile(
                IDENTITY, TextureAppearance.accountDefault(), Optional.empty());

        assertEquals(APPEARANCE, custom.appearance());
        assertTrue(accountDefault.appearance().isAccountDefault());
        assertEquals("VerifiedOfficialProfile[redacted]", custom.toString());
        assertThrows(
                IllegalArgumentException.class,
                () -> new VerifiedOfficialProfile(
                        IDENTITY, TextureAppearance.unknown(), Optional.of(property)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new VerifiedOfficialProfile(
                        IDENTITY, TextureAppearance.accountDefault(), Optional.of(property)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new VerifiedOfficialProfile(IDENTITY, APPEARANCE, Optional.empty()));
    }

    @Test
    void publicationAndSubmissionDiagnosticsContainStatusesOnly() {
        ConnectionKey key = new ConnectionKey(PROFILE_ID, 1L);
        VerifiedOfficialProfile profile = new VerifiedOfficialProfile(
                IDENTITY,
                APPEARANCE,
                Optional.of(new SignedTexturesProperty("value-secret", "signature-secret")));
        PublicationRequest request = new PublicationRequest(key, profile);
        PublicationMetrics metrics = new PublicationMetrics(
                10L, 9L, 2L, 3L, 18_432L, 500_000L, 200_000L, 2L, 1L);
        BatchPublicationResult result = BatchPublicationResult.of(
                Map.of(key, PublicationOutcome.UPDATED), metrics);
        RefreshSubmission submission = new RefreshSubmission(
                Admission.ACCEPTED,
                CompletableFuture.completedFuture(RefreshResult.UPDATED));

        assertEquals(PublicationOutcome.UPDATED, result.outcome(key).orElseThrow());
        assertEquals(1, result.size());
        assertEquals(metrics, result.metrics());
        assertFalse(request.toString().contains(PROFILE_ID.toString()));
        assertFalse(result.toString().contains(PROFILE_ID.toString()));
        assertEquals("RefreshSubmission[admission=ACCEPTED]", submission.toString());
        assertEquals(
                PublicationOutcome.STALE,
                BatchPublicationResult.all(List.of(request), PublicationOutcome.STALE)
                        .outcome(key)
                        .orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> new PublicationRequest(
                        new ConnectionKey(UUID.randomUUID(), 1L), profile));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PublicationMetrics(-1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PublicationMetrics(0L, 0L, 0L, 0L, 0L, 0L, -1L, 0L, 0L));
    }

    @Test
    void resolverOutcomesAreStatusOnlyAndValidateThrottleDelay() {
        VerifiedOfficialProfile profile = new VerifiedOfficialProfile(
                IDENTITY,
                APPEARANCE,
                Optional.of(new SignedTexturesProperty("value", "signature")));
        OfficialProfileResolver.Resolution resolved =
                OfficialProfileResolver.Resolution.resolved(profile);

        assertEquals(
                OfficialProfileResolver.Resolution.Status.RESOLVED,
                resolved.status());
        assertEquals(profile, resolved.profile().orElseThrow());
        assertEquals(
                Duration.ofSeconds(12),
                OfficialProfileResolver.Resolution.throttled(Duration.ofSeconds(12))
                        .retryAfter()
                        .orElseThrow());
        assertFalse(resolved.toString().contains(PROFILE_ID.toString()));
        assertThrows(
                IllegalArgumentException.class,
                () -> OfficialProfileResolver.Resolution.throttled(Duration.ofSeconds(-1)));
    }

    @Test
    void semanticAppearanceNeverExposesItsInputs() {
        TextureAppearance same = TextureAppearance.verified(
                Optional.of("A".repeat(64)),
                Optional.of(TextureAppearance.SkinModel.SLIM),
                Optional.empty(),
                Optional.empty());
        assertEquals(APPEARANCE, same);
        assertFalse(APPEARANCE.toString().contains("a".repeat(64)));
        assertTrue(TextureAppearance.accountDefault().isAccountDefault());
        assertTrue(TextureAppearance.unknown().isUnknown());
    }
}
