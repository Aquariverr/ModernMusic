package dev.modernmusic.probe;

import dev.modernmusic.client.audio.Mp3Decoder;
import dev.modernmusic.domain.SongId;
import dev.modernmusic.domain.SongReferenceParser;
import dev.modernmusic.provider.*;
import dev.modernmusic.provider.netease.NeteaseMusicProvider;
import java.time.Clock;
import java.util.concurrent.CompletionException;

/** Opt-in probe: emits only public IDs/metadata and measurements, never full resource URLs. */
public final class GuestProbe {
    public static void main(String[] args) {
        try (var http = new BoundedHttpTransport(); var provider = new NeteaseMusicProvider(http, Clock.systemUTC())) {
            String[] references = args.length == 0 ? new String[]{"2058263032", "347230", "186016"} : args;
            for (String reference : references) {
                SongId id = SongReferenceParser.parse(reference);
                try {
                    var track = provider.getTrack(id).join();
                    System.out.printf("id=%s title=%s declaredDurationMs=%d%n", id, track.title(), track.durationMs());
                    var resource = provider.resolvePlayback(id).join();
                    byte[] mp3 = http.get(resource.uri(), 32 * 1024 * 1024);
                    var pcm = new Mp3Decoder().decode(mp3);
                    System.out.printf("id=%s format=MP3 bytes=%d sampleRate=%d channels=%d decodedDurationMs=%d tls=%s%n",
                            id, mp3.length, pcm.sampleRate(), pcm.channels(), pcm.durationMs(), resource.uri().getScheme());
                } catch (MusicException | CompletionException exception) {
                    Throwable failure = exception instanceof CompletionException ? exception.getCause() : exception;
                    System.out.printf("id=%s result=%s%n", id, failure instanceof MusicException music ? music.error() : "INTERNAL_ERROR");
                    if (!(failure instanceof MusicException music) || music.error() == MusicError.NETWORK_ERROR
                            || music.error() == MusicError.INVALID_RESPONSE) throw new IllegalStateException("Guest probe failed");
                }
            }
        }
    }
}
