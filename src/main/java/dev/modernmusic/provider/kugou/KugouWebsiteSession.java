package dev.modernmusic.provider.kugou;

import dev.modernmusic.auth.*;
import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.provider.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;

/** Website CrossDomainLogin confirms identity through first-party Set-Cookie headers, never evaluated scripts. */
final class KugouWebsiteSession {
    private static final Pattern ERROR = Pattern.compile("(?:var\\s+)?error_code\\s*=\\s*([0-9]{1,9})\\s*;");
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("%u([a-fA-F0-9]{4})");
    private KugouWebsiteSession() {}

    static AccountSession validate(HttpTransport.CookieResponse response, SessionCookie input, Clock clock) {
        if (response.body().length > 65536 || response.setCookies().size() > 64 || !response.location().isEmpty()) throw invalid();
        String body = new String(response.body(), StandardCharsets.UTF_8).strip();
        if (!body.isEmpty()) {
            var error = ERROR.matcher(body);
            if (!error.matches()) throw invalid();
            int code = Integer.parseInt(error.group(1));
            if (code == 20017 || code == 20018) throw new MusicException(MusicError.AUTH_EXPIRED);
            if (code != 0) throw new MusicException(MusicError.UNAVAILABLE);
        }
        String packed = null;
        int total = 0;
        for (String header : response.setCookies()) {
            total += header.length();
            if (total > 65536) throw invalid();
            String pair = header.split(";", 2)[0].strip();
            if (!pair.startsWith("KuGoo=")) continue;
            String value = pair.substring(6);
            if (value.isEmpty()) continue;
            if (value.length() > SessionCookie.MAX_LENGTH || packed != null && !packed.equals(value)) throw invalid();
            packed = value;
        }
        if (packed == null) throw invalid();
        try {
            var confirmed = SessionCookie.parse("KuGoo=" + packed, MusicPlatform.KUGOU);
            if (!confirmed.value("userid").equals(input.value("userid")) || !Set.of("1010", "1014").contains(confirmed.value("appid"))) throw invalid();
            Map<String, String> profile = profile(packed);
            String nickname = KugouApi.display(profile.getOrDefault("NickName", ""));
            if (nickname.isBlank()) throw invalid();
            var fields = new LinkedHashMap<String, String>();
            for (String name : List.of("userid", "token", "appid", "ct")) {
                String value = confirmed.value(name).isEmpty() ? input.value(name) : confirmed.value(name);
                if (!value.isEmpty()) fields.put(name, value);
            }
            for (String name : List.of("mid", "dfid")) if (!input.value(name).isEmpty()) fields.put(name, input.value(name));
            var cookie = SessionCookie.parse(fields.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(java.util.stream.Collectors.joining("; ")), MusicPlatform.KUGOU);
            String picture = profile.getOrDefault("Pic", "");
            if (picture.matches("[0-9]{8}[A-Za-z0-9_.-]{0,240}\\.(?:jpg|png)"))
                picture = "https://imge.kugou.com/kugouicon/165/" + picture.substring(0, 8) + "/" + picture;
            return new AccountSession(cookie.value("userid"), nickname.substring(0, Math.min(64, nickname.length())), cookie, clock.instant(), KugouUris.image(picture));
        } catch (RuntimeException error) { throw invalid(); }
    }
    private static Map<String, String> profile(String packed) {
        if (!packed.contains("=")) packed = decode(packed);
        var fields = new HashMap<String, String>();
        for (String pair : packed.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2 || !Set.of("NickName", "Pic").contains(parts[0])) continue;
            String value = displayValue(parts[1]);
            String previous = fields.putIfAbsent(parts[0], value);
            if (previous != null && !previous.equals(value)) throw invalid();
        }
        return fields;
    }
    private static String displayValue(String value) {
        // The official cookie also uses JavaScript's legacy %uXXXX escapes; never eval its text.
        var matcher = UNICODE_ESCAPE.matcher(value);
        var result = new StringBuilder(); int end = 0;
        while (matcher.find()) {
            result.append(decode(value.substring(end, matcher.start()))).append((char) Integer.parseInt(matcher.group(1), 16));
            end = matcher.end();
        }
        return result.append(decode(value.substring(end))).toString();
    }
    private static String decode(String value) { return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8); }
    private static AuthException invalid() { return new AuthException("invalid_response"); }
}
