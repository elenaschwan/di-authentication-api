package uk.gov.di.authentication.shared.validation;

import org.apache.commons.codec.binary.Base32;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.MFAMethod;
import uk.gov.di.authentication.shared.entity.MFAMethodType;
import uk.gov.di.authentication.shared.helpers.NowHelper;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.CodeStorageService;
import uk.gov.di.authentication.shared.services.ConfigurationService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class AuthAppCodeValidator extends MfaCodeValidator {

    private final int windowTime;
    private final int allowedWindows;
    private final AuthenticationService dynamoService;
    private final String emailAddress;
    private static final MFAMethodType mfaMethodType = MFAMethodType.AUTH_APP;

    public AuthAppCodeValidator(
            String emailAddress,
            CodeStorageService codeStorageService,
            ConfigurationService configurationService,
            AuthenticationService dynamoService,
            int maxRetries) {
        super(emailAddress, codeStorageService, maxRetries);
        this.dynamoService = dynamoService;
        this.emailAddress = emailAddress;
        this.windowTime = configurationService.getAuthAppCodeWindowLength();
        this.allowedWindows = configurationService.getAuthAppCodeAllowedWindows();
    }

    @Override
    public Optional<ErrorResponse> validateCode(String code) {

        if (isCodeBlockedForSession(mfaMethodType)) {
            LOG.info("Code blocked for session");
            return Optional.of(ErrorResponse.ERROR_1042);
        }

        incrementRetryCount(mfaMethodType);

        if (hasExceededRetryLimit(mfaMethodType)) {
            LOG.info("Exceeded code retry limit");
            return Optional.of(ErrorResponse.ERROR_1042);
        }

        Optional<String> storedSecret = getMfaCredentialValue();

        if (storedSecret.isEmpty()) {
            LOG.info("No auth app secret found");
            return Optional.of(ErrorResponse.ERROR_1043);
        }

        if (!isCodeValid(code, storedSecret.get())) {
            LOG.info("Auth code is not valid");
            return Optional.of(ErrorResponse.ERROR_1043);
        }
        LOG.info("Auth code valid. Resetting code request count");
        resetCodeRequestCount(mfaMethodType);

        return Optional.empty();
    }

    public Optional<String> getMfaCredentialValue() {
        var userCredentials = dynamoService.getUserCredentialsFromEmail(emailAddress);

        if (userCredentials == null) {
            LOG.info("User credentials not found");
            return Optional.empty();
        }

        var mfaMethod =
                userCredentials.getMfaMethods().stream()
                        .filter(
                                method ->
                                        method.getMfaMethodType()
                                                .equals(MFAMethodType.AUTH_APP.getValue()))
                        .filter(MFAMethod::isEnabled)
                        .findAny();

        return mfaMethod.map(MFAMethod::getCredentialValue);
    }

    public boolean isCodeValid(String code, String secret) {
        if (code.isEmpty() || code.length() > 6) {
            return false;
        }

        int codeToCheck = Integer.parseInt(code);

        if (secret == null) {
            throw new IllegalArgumentException("Secret cannot be null.");
        }

        if (codeToCheck <= 0 || codeToCheck >= (int) Math.pow(10, 6)) {
            return false;
        }

        return checkCode(secret, codeToCheck, NowHelper.now().getTime());
    }

    private boolean checkCode(String secret, long code, long timestamp) {
        byte[] decodedKey = decodeSecret(secret);

        final long timeWindow = getTimeWindowFromTime(timestamp);

        for (int i = -((allowedWindows - 1) / 2); i <= allowedWindows / 2; ++i) {
            try {
                int calculatedCodeHash = calculateCode(decodedKey, timeWindow + i);
                if (calculatedCodeHash == code) {
                    return true;
                }
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                LOG.error("Error calculating TOTP hash from decoded secret", e);
                return false;
            }
        }
        return false;
    }

    private byte[] decodeSecret(String secret) {
        Base32 codec32 = new Base32();
        return codec32.decode(secret.toUpperCase());
    }

    private int calculateCode(byte[] key, long time)
            throws NoSuchAlgorithmException, InvalidKeyException {
        byte[] data = new byte[8];

        for (int i = 8; i-- > 0; time >>>= 8) {
            data[i] = (byte) time;
        }

        SecretKeySpec signKey = new SecretKeySpec(key, "HmacSHA1");

        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(signKey);

        byte[] hash = mac.doFinal(data);

        int offset = hash[hash.length - 1] & 0xF;

        long truncatedHash = 0;

        for (int i = 0; i < 4; ++i) {
            truncatedHash <<= 8;

            truncatedHash |= (hash[offset + i] & 0xFF);
        }

        truncatedHash &= 0x7FFFFFFF;
        truncatedHash %= (int) Math.pow(10, 6);

        return (int) truncatedHash;
    }

    private long getTimeWindowFromTime(long time) {
        return time / TimeUnit.SECONDS.toMillis(windowTime);
    }
}
