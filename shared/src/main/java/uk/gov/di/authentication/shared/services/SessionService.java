package uk.gov.di.authentication.shared.services;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.gov.di.authentication.shared.entity.Session;
import uk.gov.di.authentication.shared.helpers.CookieHelper;
import uk.gov.di.authentication.shared.helpers.IdGenerator;
import uk.gov.di.authentication.shared.serialization.Json;

import java.util.Map;
import java.util.Optional;

import static uk.gov.di.authentication.shared.domain.RequestHeaders.SESSION_ID_HEADER;
import static uk.gov.di.authentication.shared.helpers.InputSanitiser.sanitiseBase64;
import static uk.gov.di.authentication.shared.helpers.InstrumentationHelper.segmentedFunctionCall;
import static uk.gov.di.authentication.shared.helpers.RequestHeaderHelper.getHeaderValueFromHeaders;
import static uk.gov.di.authentication.shared.helpers.RequestHeaderHelper.headersContainValidHeader;

public class SessionService {

    private static final Logger LOG = LogManager.getLogger(SessionService.class);

    private static final Json OBJECT_MAPPER = SerializationService.getInstance();

    private final ConfigurationService configurationService;
    private final RedisConnectionService redisConnectionService;
    private final CookieHelper cookieHelper;

    public SessionService(
            ConfigurationService configurationService,
            RedisConnectionService redisConnectionService) {
        this.configurationService = configurationService;
        this.redisConnectionService = redisConnectionService;
        this.cookieHelper = new CookieHelper();
    }

    public SessionService(ConfigurationService configurationService) {
        this(
                configurationService,
                new RedisConnectionService(
                        configurationService.getRedisHost(),
                        configurationService.getRedisPort(),
                        configurationService.getUseRedisTLS(),
                        configurationService.getRedisPassword()));
    }

    public Session createSession() {
        return new Session(IdGenerator.generate());
    }

    public void save(Session session) {
        try {
            redisConnectionService.saveWithExpiry(
                    session.getSessionId(),
                    OBJECT_MAPPER.writeValueAsString(session),
                    configurationService.getSessionExpiry());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void updateSessionId(Session session) {
        try {
            String oldSessionId = session.getSessionId();
            session.setSessionId(IdGenerator.generate());
            session.resetProcessingIdentityAttempts();
            save(session);
            redisConnectionService.deleteValue(oldSessionId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Optional<Session> getSessionFromRequestHeaders(Map<String, String> headers) {
        if (!headersContainValidHeader(
                headers, SESSION_ID_HEADER, configurationService.getHeadersCaseInsensitive())) {
            LOG.warn("Headers are missing Session-Id header");
            return Optional.empty();
        }
        String sessionId =
                getHeaderValueFromHeaders(
                        headers,
                        SESSION_ID_HEADER,
                        configurationService.getHeadersCaseInsensitive());
        if (sessionId == null) {
            LOG.warn("Value not found for Session-Id header");
            return Optional.empty();
        }

        return sanitiseBase64(sessionId)
                .flatMap(
                        id -> {
                            try {
                                return readSessionFromRedis(id);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
    }

    public Optional<Session> getSessionFromSessionCookie(Map<String, String> headers) {
        try {
            Optional<CookieHelper.SessionCookieIds> ids = cookieHelper.parseSessionCookie(headers);
            return ids.flatMap(s -> readSessionFromRedis(s.getSessionId()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void deleteSessionFromRedis(String sessionId) {
        redisConnectionService.deleteValue(sessionId);
    }

    public Optional<Session> readSessionFromRedis(String sessionId) {
        try {
            if (redisConnectionService.keyExists(sessionId)) {
                return Optional.of(
                        segmentedFunctionCall(
                                "Deserialise session",
                                () ->
                                        OBJECT_MAPPER.readValue(
                                                redisConnectionService.getValue(sessionId),
                                                Session.class)));
            } else {
                return Optional.empty();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
