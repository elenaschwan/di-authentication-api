package uk.gov.di.authentication.clientregistry.services;

import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.client.RegistrationError;
import com.nimbusds.openid.connect.sdk.SubjectType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.gov.di.authentication.clientregistry.entity.ClientRegistrationRequest;
import uk.gov.di.authentication.shared.entity.ClientType;
import uk.gov.di.authentication.shared.entity.UpdateClientConfigRequest;
import uk.gov.di.authentication.shared.entity.ValidClaims;
import uk.gov.di.authentication.shared.entity.ValidScopes;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static java.util.Collections.singletonList;
import static uk.gov.di.authentication.shared.entity.ServiceType.MANDATORY;
import static uk.gov.di.authentication.shared.entity.ServiceType.OPTIONAL;

public class ClientConfigValidationService {
    private static final Logger LOG = LogManager.getLogger(ClientConfigValidationService.class);
    public static final ErrorObject INVALID_POST_LOGOUT_URI =
            new ErrorObject("invalid_client_metadata", "Invalid Post logout redirect URIs");
    public static final ErrorObject INVALID_SCOPE =
            new ErrorObject("invalid_client_metadata", "Insufficient Scope");
    public static final ErrorObject INVALID_PUBLIC_KEY =
            new ErrorObject("invalid_client_metadata", "Invalid Public Key");
    public static final ErrorObject INVALID_SERVICE_TYPE =
            new ErrorObject("invalid_client_metadata", "Invalid Service Type");
    public static final ErrorObject INVALID_SUBJECT_TYPE =
            new ErrorObject("invalid_client_metadata", "Invalid Subject Type");
    public static final ErrorObject INVALID_CLAIM =
            new ErrorObject("invalid_client_metadata", "Insufficient Claim");
    public static final ErrorObject INVALID_SECTOR_IDENTIFIER_URI =
            new ErrorObject("invalid_client_metadata", "Invalid Sector Identifier URI");
    public static final ErrorObject INVALID_CLIENT_TYPE =
            new ErrorObject("invalid_client_metadata", "Invalid Client Type");

    public Optional<ErrorObject> validateClientRegistrationConfig(
            ClientRegistrationRequest registrationRequest) {
        if (!Optional.ofNullable(registrationRequest.getPostLogoutRedirectUris())
                .map(this::areUrisValid)
                .orElse(true)) {
            return Optional.of(INVALID_POST_LOGOUT_URI);
        }
        if (!areUrisValid(registrationRequest.getRedirectUris())) {
            return Optional.of(RegistrationError.INVALID_REDIRECT_URI);
        }
        if (!Optional.ofNullable(registrationRequest.getSectorIdentifierUri())
                .map(t -> areUrisValid(singletonList(t)))
                .orElse(true)) {
            return Optional.of(INVALID_SECTOR_IDENTIFIER_URI);
        }
        if (!Optional.ofNullable(registrationRequest.getBackChannelLogoutUri())
                .map(t -> areUrisValid(singletonList(t)))
                .orElse(true)) {
            return Optional.of(RegistrationError.INVALID_REDIRECT_URI);
        }
        if (!isPublicKeyValid(registrationRequest.getPublicKey())) {
            return Optional.of(INVALID_PUBLIC_KEY);
        }
        if (!areScopesValid(registrationRequest.getScopes())) {
            return Optional.of(INVALID_SCOPE);
        }
        if (!isValidServiceType(registrationRequest.getServiceType())) {
            return Optional.of(INVALID_SERVICE_TYPE);
        }
        if (!isValidSubjectType(registrationRequest.getSubjectType())) {
            return Optional.of(INVALID_SUBJECT_TYPE);
        }
        if (!Optional.ofNullable(registrationRequest.getClaims())
                .map(this::areClaimsValid)
                .orElse(true)) {
            return Optional.of(INVALID_CLAIM);
        }
        if (Arrays.stream(ClientType.values())
                .noneMatch(t -> t.getValue().equals(registrationRequest.getClientType()))) {
            return Optional.of(INVALID_CLIENT_TYPE);
        }
        return Optional.empty();
    }

    public Optional<ErrorObject> validateClientUpdateConfig(
            UpdateClientConfigRequest updateRequest) {
        if (!Optional.ofNullable(updateRequest.getPostLogoutRedirectUris())
                .map(this::areUrisValid)
                .orElse(true)) {
            return Optional.of(INVALID_POST_LOGOUT_URI);
        }
        if (!Optional.ofNullable(updateRequest.getRedirectUris())
                .map(this::areUrisValid)
                .orElse(true)) {
            return Optional.of(RegistrationError.INVALID_REDIRECT_URI);
        }
        if (!Optional.ofNullable(updateRequest.getPublicKey())
                .map(this::isPublicKeyValid)
                .orElse(true)) {
            return Optional.of(INVALID_PUBLIC_KEY);
        }
        if (!Optional.ofNullable(updateRequest.getScopes())
                .map(this::areScopesValid)
                .orElse(true)) {
            return Optional.of(INVALID_SCOPE);
        }
        if (!Optional.ofNullable(updateRequest.getServiceType())
                .map(this::isValidServiceType)
                .orElse(true)) {
            return Optional.of(INVALID_SERVICE_TYPE);
        }
        if (!Optional.ofNullable(updateRequest.getSectorIdentifierUri())
                .map(t -> areUrisValid(singletonList(t)))
                .orElse(true)) {
            return Optional.of(INVALID_SECTOR_IDENTIFIER_URI);
        }
        if (!Optional.ofNullable(updateRequest.getClientType())
                .map(c -> Arrays.stream(ClientType.values()).anyMatch(t -> t.getValue().equals(c)))
                .orElse(true)) {
            return Optional.of(INVALID_CLIENT_TYPE);
        }
        return Optional.empty();
    }

    private boolean areUrisValid(List<String> uris) {
        try {
            for (String uri : uris) {
                new URL(uri);
            }
        } catch (MalformedURLException e) {
            return false;
        }
        return true;
    }

    private boolean isPublicKeyValid(String publicKey) {
        byte[] decodedKey;
        X509EncodedKeySpec x509publicKey = null;

        try {
            decodedKey = Base64.getMimeDecoder().decode(publicKey);
            x509publicKey = new X509EncodedKeySpec(decodedKey);
            KeyFactory rsaKeyFactory = KeyFactory.getInstance("RSA");
            rsaKeyFactory.generatePublic(x509publicKey);
            LOG.info("Valid RSA key found");
            return true;
        } catch (Exception e) {
            try {
                KeyFactory ecKeyFactory = KeyFactory.getInstance("EC");
                ecKeyFactory.generatePublic(x509publicKey);
            } catch (Exception ex) {
                LOG.info("Valid key not found (checked RSA and EC)");
                return false;
            }
            LOG.info("Valid EC key found");
            return true;
        }
    }

    private boolean areScopesValid(List<String> scopes) {
        for (String scope : scopes) {
            if (ValidScopes.getPublicValidScopes().stream().noneMatch((t) -> t.equals(scope))) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidServiceType(String serviceType) {
        return serviceType.equalsIgnoreCase(String.valueOf(MANDATORY))
                || serviceType.equalsIgnoreCase(String.valueOf(OPTIONAL));
    }

    private boolean isValidSubjectType(String subjectType) {
        return List.of(SubjectType.PUBLIC.toString(), SubjectType.PAIRWISE.toString())
                .contains(subjectType);
    }

    private boolean areClaimsValid(List<String> claims) {
        for (String claim : claims) {
            if (ValidClaims.getAllValidClaims().stream().noneMatch(t -> t.equals(claim))) {
                return false;
            }
        }
        return true;
    }
}
