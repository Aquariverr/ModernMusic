package dev.modernmusic.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.modernmusic.domain.MusicPlatform;

/** Account and playback session cookies. Default diagnostic output must never expose tokens. */
public final class SessionCookie {
    public static final int MAX_LENGTH = 16384;
    public static final int MAX_IMPORT_LENGTH = 1_048_576;
    private static final Set<String> NETEASE_NAMES = Set.of("MUSIC_U", "__csrf", "NMTID");
    private static final Set<String> QQ_NAMES = Set.of("uin", "wxuin", "qqmusic_key", "qm_keyst", "qqmusic_uin", "login_type",
            "psrf_qqunionid", "psrf_qqopenid", "psrf_qqaccess_token", "wxopenid", "wxrefresh_token");
    private static final Set<String> KUGOU_NAMES = Set.of("userid", "token", "dfid", "mid", "appid", "ct");
    private final String header;
    private final dev.modernmusic.domain.MusicPlatform platform;
    private SessionCookie(String header) { this(header, dev.modernmusic.domain.MusicPlatform.NETEASE); }
    private SessionCookie(String header, dev.modernmusic.domain.MusicPlatform platform) {
        if (header.length() > MAX_LENGTH) throw invalid();
        this.header = header; this.platform = platform;
    }
    public dev.modernmusic.domain.MusicPlatform platform() { return platform; }
    public String value(String name) {
        for (String pair : header.split("; ")) if (pair.startsWith(name + "=")) return pair.substring(name.length() + 1);
        return "";
    }
    public String qqUin() { return !value("uin").isEmpty() ? value("uin").replaceFirst("^o0*", "") : value("wxuin"); }
    public static SessionCookie parse(String input, dev.modernmusic.domain.MusicPlatform platform) {
        input = BrowserCookieImport.header(input, platform);
        return switch (platform) {
            case NETEASE -> parseNetease(input);
            case QQ -> parseQq(input);
            case KUGOU -> parseKugou(input);
            case LOCAL -> throw invalid();
        };
    }
    /** Used before the game's single-line field can strip JSON whitespace or truncate the export. */
    public static boolean isJsonImport(String input) {
        if (input == null) return false;
        String text = input.stripLeading();
        if (text.startsWith("\uFEFF")) text = text.substring(1).stripLeading();
        return text.startsWith("[") || text.startsWith("{");
    }
    static String canonicalName(String name, MusicPlatform platform) {
        if (name == null) return null;
        if (platform == MusicPlatform.KUGOU) name = KugouCookieFields.canonicalName(name);
        Set<String> allowed = switch (platform) {
            case NETEASE -> NETEASE_NAMES;
            case QQ -> QQ_NAMES;
            case KUGOU -> KUGOU_NAMES;
            case LOCAL -> Set.of();
        };
        return allowed.contains(name) ? name : null;
    }
    private static SessionCookie parseKugou(String input) {
        if (input == null || input.length() > MAX_LENGTH || input.chars().anyMatch(c -> c < 32 || c > 126)) throw invalid();
        String text = input.trim();
        if (text.regionMatches(true, 0, "Cookie:", 0, 7)) text = text.substring(7).trim();
        var values = new LinkedHashMap<String, String>();
        boolean webToken = false;
        for (String pair : text.split(";")) {
            String[] parts = pair.trim().split("=", 2);
            if (parts.length != 2) { if (!pair.isBlank()) throw invalid(); else continue; }
            String name = parts[0].trim(), value = parts[1].trim();
            if (name.equals("KuGoo")) {
                var fields = KugouCookieFields.unpack(value);
                fields.forEach((key, entry) -> KugouCookieFields.merge(values, key, entry));
                webToken |= fields.containsKey("token");
                continue;
            }
            if (name.equals("t")) webToken = true;
            name = canonicalName(name, MusicPlatform.KUGOU);
            if (name == null) continue;
            KugouCookieFields.merge(values, name, value);
        }
        if (webToken) values.putIfAbsent("appid", "1014");
        if (!values.getOrDefault("userid", "").matches("[1-9][0-9]{0,18}") || !values.containsKey("token")) throw invalid();
        try { Long.parseLong(values.get("userid")); } catch (NumberFormatException invalid) { throw invalid(); }
        if (values.containsKey("appid") && !Set.of("1005", "1010", "1014").contains(values.get("appid"))) throw invalid();
        if (values.containsKey("ct") && !values.get("ct").matches("[0-9]{1,10}")) throw invalid();
        return new SessionCookie(values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining("; ")), dev.modernmusic.domain.MusicPlatform.KUGOU);
    }
    private static SessionCookie parseQq(String input) {
        if (input == null || input.length() > MAX_LENGTH || input.chars().anyMatch(c -> c < 32 || c > 126)) throw invalid();
        String text = input.trim();
        if (text.regionMatches(true, 0, "Cookie:", 0, 7)) text = text.substring(7).trim();
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : text.split(";")) {
            String[] parts = pair.trim().split("=", 2);
            if (parts.length != 2) { if (!pair.isBlank()) throw invalid(); else continue; }
            String name = parts[0].trim(), value = parts[1].trim();
            if (canonicalName(name, MusicPlatform.QQ) == null) continue;
            if (value.isEmpty() || value.chars().anyMatch(c -> c <= 32 || c == '"' || c == ',' || c == '\\') || values.putIfAbsent(name, value) != null) throw invalid();
        }
        if (!values.containsKey("qqmusic_key") && !values.containsKey("qm_keyst")) throw invalid();
        if (!values.containsKey("login_type") && !values.containsKey("uin") && !values.containsKey("qqmusic_uin") && values.containsKey("wxuin"))
            values.put("login_type", "2");
        String uin = "2".equals(values.get("login_type")) ? values.get("wxuin")
                : values.getOrDefault("uin", values.get("qqmusic_uin"));
        if (uin == null) throw invalid();
        uin = uin.replaceFirst("^o0*", "");
        if (!uin.matches("[1-9][0-9]{0,18}")) throw invalid();
        values.put("uin", uin);
        return new SessionCookie(values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining("; ")), dev.modernmusic.domain.MusicPlatform.QQ);
    }
    public String header() { return header; }
    @Override public String toString() { return "SessionCookie[redacted]"; }

    public static SessionCookie parse(String input) { return parse(input, MusicPlatform.NETEASE); }
    private static SessionCookie parseNetease(String input) {
        if (input == null || input.length() > MAX_LENGTH || input.chars().anyMatch(c -> c < 32 || c > 126)) throw invalid();
        String text = input.trim();
        if (text.regionMatches(true, 0, "Cookie:", 0, 7)) text = text.substring(7).trim();
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : text.split(";")) {
            String[] parts = pair.trim().split("=", 2);
            if (parts.length != 2) { if (!pair.isBlank()) throw invalid(); else continue; }
            String name = parts[0].trim(), value = parts[1].trim();
            if (canonicalName(name, MusicPlatform.NETEASE) == null) continue;
            if (value.isEmpty() || value.chars().anyMatch(c -> c <= 32 || c == '"' || c == ',' || c == '\\')
                    || values.putIfAbsent(name, value) != null) throw invalid();
        }
        if (!values.containsKey("MUSIC_U")) throw invalid();
        return new SessionCookie("MUSIC_U=" + values.get("MUSIC_U")
                + (values.containsKey("__csrf") ? "; __csrf=" + values.get("__csrf") : "")
                + (values.containsKey("NMTID") ? "; NMTID=" + values.get("NMTID") : ""));
    }

    public static SessionCookie fromResponse(List<String> headers) {
        StringBuilder pairs = new StringBuilder();
        for (String header : headers) {
            if (header.length() > MAX_LENGTH || pairs.length() > MAX_LENGTH) throw invalid();
            String pair = header.split(";", 2)[0];
            if (pair.startsWith("MUSIC_U=") || pair.startsWith("__csrf=") || pair.startsWith("NMTID=")) pairs.append(pair).append(';');
        }
        return parse(pairs.toString());
    }
    private static AuthException invalid() { return new AuthException("invalid_cookie"); }
}
