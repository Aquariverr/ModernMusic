package dev.modernmusic.auth;

import javax.annotation.Nonnull;
/** Validated request inputs, never a URL/query string or persisted account profile. */
public record PhoneLogin(String countryCode, String number) {
    public PhoneLogin {
        countryCode = countryCode == null ? "" : countryCode.trim().replaceFirst("^\\+", "");
        number = number == null ? "" : number.trim().replace(" ", "").replace("-", "");
        if (!countryCode.matches("[1-9][0-9]{0,3}") || !number.matches("[0-9]{5,14}")
                || countryCode.length() + number.length() > 15 || (countryCode.equals("86") && !number.matches("1[3-9][0-9]{9}")))
            throw new AuthException("invalid_phone");
    }
    public static String code(String value) {
        String code = value == null ? "" : value.trim();
        if (!code.matches("[0-9]{4,8}")) throw new AuthException("invalid_sms_code");
        return code;
    }
    @Nonnull @Override public String toString() { return "PhoneLogin[redacted]"; }
}
