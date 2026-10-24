package dev.modernmusic.domain;

/** Plain metadata labels shared by providers and persisted queues. */
public final class DisplayText {
    private DisplayText() {}

    public static String clean(String value) {
        String cleaned = value.replaceAll("[\\p{Cntrl}§]", "");
        return cleaned.substring(0, Math.min(256, cleaned.length()));
    }
}
