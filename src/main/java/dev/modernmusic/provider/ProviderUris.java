package dev.modernmusic.provider;

import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.provider.netease.NeteaseUris;
import dev.modernmusic.provider.qq.QqUris;
import java.net.URI;

public final class ProviderUris {
    private ProviderUris() {}
    public static URI audio(MusicPlatform platform, String value) {
        return switch (platform) {
            case NETEASE -> NeteaseUris.audio(value);
            case QQ -> QqUris.audio(value);
            case KUGOU -> dev.modernmusic.provider.kugou.KugouUris.audio(value);
            case LOCAL -> throw new MusicException(MusicError.RESOURCE_UNAVAILABLE);
        };
    }
    public static URI image(String value) {
        URI image = NeteaseUris.image(value);
        if (image == null) image = QqUris.image(value);
        return image != null ? image : dev.modernmusic.provider.kugou.KugouUris.image(value);
    }
}
