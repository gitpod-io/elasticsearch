/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.security.authc.jwt;

import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.xpack.core.security.authc.RealmConfig;
import org.elasticsearch.xpack.core.security.authc.jwt.JwtRealmSettings;
import org.elasticsearch.xpack.core.ssl.SSLService;

import java.text.ParseException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This class performs validations of header, claims and signatures against the incoming {@link JwtAuthenticationToken}.
 * It returns the {@link JWTClaimsSet} associated to the token if validation is successful.
 * Note this class does not care about users nor its caching behaviour.
 */
public class JwtAuthenticator implements Releasable {

    private static final Logger logger = LogManager.getLogger(JwtAuthenticator.class);
    private final RealmConfig realmConfig;
    private final List<JwtFieldValidator> jwtFieldValidators;
    private final JwtSignatureValidator jwtSignatureValidator;
    private final JwtRealmSettings.TokenType tokenType;
    private final Map<String, String> fallbackClaimNames;

    public JwtAuthenticator(
        final RealmConfig realmConfig,
        final SSLService sslService,
        final JwtSignatureValidator.PkcJwkSetReloadNotifier reloadNotifier
    ) {
        this.realmConfig = realmConfig;
        this.tokenType = realmConfig.getSetting(JwtRealmSettings.TOKEN_TYPE);
        final List<JwtFieldValidator> jwtFieldValidators = new ArrayList<>();
        if (tokenType == JwtRealmSettings.TokenType.ID_TOKEN) {
            this.fallbackClaimNames = Map.of();
            jwtFieldValidators.addAll(configureFieldValidatorsForIdToken(realmConfig));
        } else {
            this.fallbackClaimNames = Map.ofEntries(
                Map.entry("sub", realmConfig.getSetting(JwtRealmSettings.FALLBACK_SUB_CLAIM)),
                Map.entry("aud", realmConfig.getSetting(JwtRealmSettings.FALLBACK_AUD_CLAIM))
            );
            jwtFieldValidators.addAll(configureFieldValidatorsForAccessToken(realmConfig, fallbackClaimNames));
        }
        jwtFieldValidators.addAll(getRequireClaimsValidators());
        this.jwtFieldValidators = List.copyOf(jwtFieldValidators);
        this.jwtSignatureValidator = new JwtSignatureValidator.DelegatingJwtSignatureValidator(realmConfig, sslService, reloadNotifier);
    }

    public void authenticate(JwtAuthenticationToken jwtAuthenticationToken, ActionListener<JWTClaimsSet> listener) {
        final String tokenPrincipal = jwtAuthenticationToken.principal();

        // JWT cache
        final SecureString serializedJwt = jwtAuthenticationToken.getEndUserSignedJwt();
        final SignedJWT signedJWT;
        try {
            signedJWT = SignedJWT.parse(serializedJwt.toString());
        } catch (ParseException e) {
            // TODO: No point to continue to another realm since parsing failed
            listener.onFailure(e);
            return;
        }

        final JWTClaimsSet jwtClaimsSet;
        try {
            jwtClaimsSet = signedJWT.getJWTClaimsSet();
        } catch (ParseException e) {
            // TODO: No point to continue to another realm since get claimset failed
            listener.onFailure(e);
            return;
        }

        final JWSHeader jwsHeader = signedJWT.getHeader();
        if (logger.isDebugEnabled()) {
            logger.debug(
                "Realm [{}] successfully parsed JWT token [{}] with header [{}] and claimSet [{}]",
                realmConfig.name(),
                tokenPrincipal,
                jwsHeader,
                jwtClaimsSet
            );
        }

        for (JwtFieldValidator jwtFieldValidator : jwtFieldValidators) {
            try {
                jwtFieldValidator.validate(jwsHeader, jwtClaimsSet);
            } catch (Exception e) {
                listener.onFailure(e);
                return;
            }
        }

        try {
            validateSignature(tokenPrincipal, signedJWT, listener.map(ignored -> jwtClaimsSet));
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    // Package private for testing
    void validateSignature(String tokenPrincipal, SignedJWT signedJWT, ActionListener<Void> listener) {
        jwtSignatureValidator.validate(tokenPrincipal, signedJWT, listener);
    }

    @Override
    public void close() {
        jwtSignatureValidator.close();
    }

    public JwtRealmSettings.TokenType getTokenType() {
        return tokenType;
    }

    public Map<String, String> getFallbackClaimNames() {
        return fallbackClaimNames;
    }

    // Package private for testing
    JwtSignatureValidator.DelegatingJwtSignatureValidator getJwtSignatureValidator() {
        assert jwtSignatureValidator instanceof JwtSignatureValidator.DelegatingJwtSignatureValidator;
        return (JwtSignatureValidator.DelegatingJwtSignatureValidator) jwtSignatureValidator;
    }

    private static List<JwtFieldValidator> configureFieldValidatorsForIdToken(RealmConfig realmConfig) {
        assert realmConfig.getSetting(JwtRealmSettings.TOKEN_TYPE) == JwtRealmSettings.TokenType.ID_TOKEN;
        final TimeValue allowedClockSkew = realmConfig.getSetting(JwtRealmSettings.ALLOWED_CLOCK_SKEW);
        final Clock clock = Clock.systemUTC();

        final JwtStringClaimValidator subjectClaimValidator;
        if (realmConfig.hasSetting(JwtRealmSettings.ALLOWED_SUBJECTS)) {
            subjectClaimValidator = new JwtStringClaimValidator("sub", realmConfig.getSetting(JwtRealmSettings.ALLOWED_SUBJECTS), true);
        } else {
            // Allow any value for the sub claim as long as there is a non-null value
            subjectClaimValidator = JwtStringClaimValidator.ALLOW_ALL_SUBJECTS;
        }

        return List.of(
            JwtTypeValidator.INSTANCE,
            new JwtStringClaimValidator("iss", List.of(realmConfig.getSetting(JwtRealmSettings.ALLOWED_ISSUER)), true),
            subjectClaimValidator,
            new JwtStringClaimValidator("aud", realmConfig.getSetting(JwtRealmSettings.ALLOWED_AUDIENCES), false),
            new JwtAlgorithmValidator(realmConfig.getSetting(JwtRealmSettings.ALLOWED_SIGNATURE_ALGORITHMS)),
            new JwtDateClaimValidator(clock, "iat", allowedClockSkew, JwtDateClaimValidator.Relationship.BEFORE_NOW, false),
            new JwtDateClaimValidator(clock, "exp", allowedClockSkew, JwtDateClaimValidator.Relationship.AFTER_NOW, false),
            new JwtDateClaimValidator(clock, "nbf", allowedClockSkew, JwtDateClaimValidator.Relationship.BEFORE_NOW, true),
            new JwtDateClaimValidator(clock, "auth_time", allowedClockSkew, JwtDateClaimValidator.Relationship.BEFORE_NOW, true)
        );
    }

    private static List<JwtFieldValidator> configureFieldValidatorsForAccessToken(
        RealmConfig realmConfig,
        Map<String, String> fallbackClaimLookup
    ) {
        assert realmConfig.getSetting(JwtRealmSettings.TOKEN_TYPE) == JwtRealmSettings.TokenType.ACCESS_TOKEN;
        final TimeValue allowedClockSkew = realmConfig.getSetting(JwtRealmSettings.ALLOWED_CLOCK_SKEW);
        final Clock clock = Clock.systemUTC();

        return List.of(
            JwtTypeValidator.INSTANCE,
            new JwtStringClaimValidator("iss", List.of(realmConfig.getSetting(JwtRealmSettings.ALLOWED_ISSUER)), true),
            new JwtStringClaimValidator("sub", fallbackClaimLookup, realmConfig.getSetting(JwtRealmSettings.ALLOWED_SUBJECTS), true),
            new JwtStringClaimValidator("aud", fallbackClaimLookup, realmConfig.getSetting(JwtRealmSettings.ALLOWED_AUDIENCES), false),
            new JwtAlgorithmValidator(realmConfig.getSetting(JwtRealmSettings.ALLOWED_SIGNATURE_ALGORITHMS)),
            new JwtDateClaimValidator(clock, "iat", allowedClockSkew, JwtDateClaimValidator.Relationship.BEFORE_NOW, false),
            new JwtDateClaimValidator(clock, "exp", allowedClockSkew, JwtDateClaimValidator.Relationship.AFTER_NOW, false)
        );
    }

    private List<JwtStringClaimValidator> getRequireClaimsValidators() {
        final Settings requiredClaims = realmConfig.getSetting(JwtRealmSettings.REQUIRED_CLAIMS);
        return requiredClaims.names().stream().map(name -> {
            final List<String> allowedValues = requiredClaims.getAsList(name);
            return new JwtStringClaimValidator(name, allowedValues, false);
        }).toList();
    }
}
