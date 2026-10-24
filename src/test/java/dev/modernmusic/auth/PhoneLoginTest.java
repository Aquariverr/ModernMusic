package dev.modernmusic.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PhoneLoginTest {
    @Test void acceptsCountryCodeAndFormatsWithoutIncludingValuesInDiagnostics() {
        var phone = new PhoneLogin("+1", "202-555-0123");
        assertEquals("1", phone.countryCode()); assertEquals("2025550123", phone.number());
        assertFalse(phone.toString().contains("2025550123"));
        assertEquals("123456", PhoneLogin.code(" 123456 "));
    }
    @ParameterizedTest @CsvSource({"86,123", "86,12345678901", "0,2025550123", "12345,2025550123", "1,http://example.com", "1,123456789012345"})
    void rejectsInvalidPhoneBeforeAnyRequest(String country, String phone) { assertThrows(AuthException.class, () -> new PhoneLogin(country, phone)); }
    @ParameterizedTest @ValueSource(strings={"", "12", "123456789", "1234a", "12\n34", "１２３４"})
    void validatesVerificationCode(String code) { assertThrows(AuthException.class, () -> PhoneLogin.code(code)); }
}
