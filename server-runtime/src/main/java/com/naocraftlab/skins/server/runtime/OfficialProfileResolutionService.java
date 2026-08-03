package com.naocraftlab.skins.server.runtime;

import com.naocraftlab.skins.server.ConnectionSnapshot;
import com.naocraftlab.skins.server.OfficialProfileResolver;
import com.naocraftlab.skins.server.OfficialTextureSignatureVerifier;
import com.naocraftlab.skins.server.SignedTexturesProperty;
import com.naocraftlab.skins.server.TextureAppearance;
import com.naocraftlab.skins.server.VerifiedOfficialProfile;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;


public final class OfficialProfileResolutionService implements OfficialProfileResolver {
    private final OfficialSessionProfileClient profileClient;
    private final OfficialTextureSignatureVerifier signatureVerifier;

    public OfficialProfileResolutionService(
            OfficialSessionProfileClient profileClient,
            OfficialTextureSignatureVerifier signatureVerifier) {
        this.profileClient = Objects.requireNonNull(profileClient, "profileClient");
        this.signatureVerifier = Objects.requireNonNull(signatureVerifier, "signatureVerifier");
    }

    @Override
    public CompletionStage<Resolution> resolve(ConnectionSnapshot expectedConnection) {
        Objects.requireNonNull(expectedConnection, "expectedConnection");
        CompletableFuture<OfficialSessionProfileClient.Result> upstream = profileClient
                .fetchAsync(expectedConnection.identity())
                .toCompletableFuture();
        CompletableFuture<Resolution> result = new CompletableFuture<>();
        upstream.whenComplete((profile, failure) -> {
            if (failure != null) {
                result.complete(Resolution.transientFailure());
                return;
            }
            try {
                result.complete(map(expectedConnection, profile));
            } catch (RuntimeException invalidResolution) {
                result.complete(Resolution.rejected());
            }
        });
        result.whenComplete((ignored, ignoredFailure) -> {
            if (result.isCancelled()) {
                upstream.cancel(true);
            }
        });
        return result;
    }

    private Resolution map(
            ConnectionSnapshot expectedConnection,
            OfficialSessionProfileClient.Result result) {
        return switch (result.status()) {
            case TRANSIENT_FAILURE -> Resolution.transientFailure();
            case THROTTLED -> Resolution.throttled(result.retryAfter().orElseThrow());
            case REJECTED -> Resolution.rejected();
            case RESOLVED -> resolveFetched(expectedConnection, result.profile().orElseThrow());
        };
    }

    private Resolution resolveFetched(
            ConnectionSnapshot expectedConnection,
            OfficialSessionProfileClient.FetchedProfile fetched) {
        if (!expectedConnection.identity().equals(fetched.identity())) {
            return Resolution.rejected();
        }
        Optional<SignedTexturesProperty> textures = fetched.textures();
        if (textures.isEmpty()) {
            return Resolution.resolved(new VerifiedOfficialProfile(
                    fetched.identity(),
                    TextureAppearance.accountDefault(),
                    Optional.empty()));
        }
        Optional<TextureAppearance> appearance;
        try {
            appearance = signatureVerifier.verify(textures.orElseThrow(), fetched.identity());
        } catch (RuntimeException verificationFailure) {
            return Resolution.rejected();
        }
        if (appearance == null || appearance.isEmpty() || appearance.orElseThrow().isUnknown()) {
            return Resolution.rejected();
        }
        TextureAppearance verifiedAppearance = appearance.orElseThrow();


        Optional<SignedTexturesProperty> canonicalTextures =
                verifiedAppearance.isAccountDefault() ? Optional.empty() : textures;
        return Resolution.resolved(new VerifiedOfficialProfile(
                fetched.identity(),
                verifiedAppearance,
                canonicalTextures));
    }
}
