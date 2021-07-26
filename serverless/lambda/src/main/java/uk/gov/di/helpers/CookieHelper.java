package uk.gov.di.helpers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpCookie;
import java.util.Map;
import java.util.Optional;

public class CookieHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(CookieHelper.class);

    public static final String REQUEST_COOKIE_HEADER = "Cookie";

    public static Optional<SessionCookieIds> parseSessionCookie(Map<String, String> headers) {
        if (headers == null
                || !headers.containsKey(REQUEST_COOKIE_HEADER)
                || headers.get(REQUEST_COOKIE_HEADER).isEmpty()) {
            return Optional.empty();
        }
        String cookies = headers.get(REQUEST_COOKIE_HEADER);
        LOGGER.debug("Session Cookie: {}", cookies);
        HttpCookie httpCookie;
        try {
            httpCookie =
                    HttpCookie.parse(cookies).stream()
                            .filter(t -> t.getName().equals("gs"))
                            .findFirst()
                            .orElse(null);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (httpCookie == null) {
            return Optional.empty();
        }

        String[] cookieValues = httpCookie.getValue().split("\\.");
        if (cookieValues.length != 2) {
            return Optional.empty();
        }
        final String sid = cookieValues[0];
        final String csid = cookieValues[1];

        return Optional.of(
                new SessionCookieIds() {
                    public String getSessionId() {
                        return sid;
                    }

                    public String getClientSessionId() {
                        return csid;
                    }
                });
    }

    public interface SessionCookieIds {
        String getSessionId();

        String getClientSessionId();
    }
}
