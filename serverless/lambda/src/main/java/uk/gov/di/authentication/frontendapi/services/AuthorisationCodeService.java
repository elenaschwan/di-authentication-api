package uk.gov.di.authentication.frontendapi.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.di.authentication.shared.helpers.ObjectMapperFactory;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.RedisConnectionService;
import uk.gov.di.entity.AuthCodeExchangeData;

import java.util.Optional;

public class AuthorisationCodeService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthorisationCodeService.class);
    public static final String AUTH_CODE_PREFIX = "auth-code-";

    private final RedisConnectionService redisConnectionService;
    private final long authorisationCodeExpiry;
    private final ObjectMapper objectMapper;

    public AuthorisationCodeService(ConfigurationService configurationService) {
        this.redisConnectionService =
                new RedisConnectionService(
                        configurationService.getRedisHost(),
                        configurationService.getRedisPort(),
                        configurationService.getUseRedisTLS(),
                        configurationService.getRedisPassword());
        this.authorisationCodeExpiry = configurationService.getAuthCodeExpiry();
        this.objectMapper = ObjectMapperFactory.getInstance();
    }

    public AuthorizationCode generateAuthorisationCode(String clientSessionId, String email) {
        AuthorizationCode authorizationCode = new AuthorizationCode();
        try {
            redisConnectionService.saveWithExpiry(
                    AUTH_CODE_PREFIX.concat(authorizationCode.getValue()),
                    objectMapper.writeValueAsString(
                            new AuthCodeExchangeData()
                                    .setEmail(email)
                                    .setClientSessionId(clientSessionId)),
                    authorisationCodeExpiry);
            return authorizationCode;
        } catch (JsonProcessingException e) {
            LOG.error("Error persisting auth code to cache", e);
            throw new RuntimeException(e);
        }
    }

    public Optional<AuthCodeExchangeData> getExchangeDataForCode(String code) {
        return Optional.ofNullable(redisConnectionService.popValue(AUTH_CODE_PREFIX.concat(code)))
                .map(
                        s -> {
                            try {
                                return objectMapper.readValue(s, AuthCodeExchangeData.class);
                            } catch (JsonProcessingException e) {
                                LOG.error("Error deserialising auth code data from cache", e);
                                throw new RuntimeException(e);
                            }
                        });
    }
}