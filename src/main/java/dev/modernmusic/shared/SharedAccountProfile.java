package dev.modernmusic.shared;

import javax.annotation.Nonnull;
import dev.modernmusic.provider.ProviderUris;
import java.net.URI;

/** Public display information only; never an account session or an administrator view. */
public record SharedAccountProfile(String nickname, String avatar) {
    public static final SharedAccountProfile EMPTY = new SharedAccountProfile("", "");
    public SharedAccountProfile {
        if (nickname == null || nickname.length() > 64 || avatar == null || avatar.length() > 4096)
            throw new IllegalArgumentException("Invalid shared profile");
        nickname = nickname.replaceAll("[\\p{Cntrl}§]", "").strip();
        URI image = ProviderUris.image(avatar);
        avatar = image == null || image.toASCIIString().length() > 4096 ? "" : image.toASCIIString();
    }
    public URI avatarUri() { return avatar.isEmpty() ? null : URI.create(avatar); }
    @Nonnull @Override public String toString() { return "SharedAccountProfile[display fields redacted]"; }
}
