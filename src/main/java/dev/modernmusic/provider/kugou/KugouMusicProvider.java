package dev.modernmusic.provider.kugou;

import com.google.gson.*;
import dev.modernmusic.auth.SessionCookie;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Bounded Kugou search, public library and authorized MP3 playback. */
public final class KugouMusicProvider implements MusicProvider {
    private final KugouApi api;
    private final Clock clock;
    private final Supplier<SessionCookie> credentials;
    private final Consumer<SessionCookie> expired;
    private final ThreadPoolExecutor worker = AsyncTasks.executor("modernmusic-kugou", 2, 8);
    public KugouMusicProvider(HttpTransport http, Clock clock, Supplier<SessionCookie> credentials, Consumer<SessionCookie> expired) {
        api = new KugouApi(http, clock); this.clock = clock; this.credentials = credentials; this.expired = expired;
    }
    private <T> CompletableFuture<T> submit(Function<SessionCookie, T> action) {
        return ProviderTasks.submit(worker, credentials, expired, action);
    }
    @Override public CompletableFuture<Track> getReference(String reference) {
        var parsed = KugouReferenceParser.song(reference);
        if (!parsed.encoded()) return getTrack(new SongId(parsed.value(), MusicPlatform.KUGOU));
        return submit(cookie -> {
            var song = api.sharedSong(parsed.value());
            // Web page durations are milliseconds, unlike getSongInfo's timeLength in seconds.
            var normalized = new JsonObject();
            normalized.addProperty("hash", KugouApi.text(song, "hash"));
            normalized.addProperty("SongName", KugouApi.text(song, "song_name"));
            normalized.addProperty("SingerName", KugouApi.text(song, "author_name"));
            normalized.addProperty("filename", KugouApi.text(song, "audio_name"));
            normalized.add("timelen", song.get("timelength"));
            return track(normalized, null);
        });
    }
    private JsonObject detail(SongId id) {
        var data = api.get("https://m.kugou.com/app/i/getSongInfo.php", Map.of("cmd", "playInfo", "hash", id.key()));
        if (!id.key().equalsIgnoreCase(KugouApi.text(data, "req_hash", "hash"))) throw new MusicException(MusicError.INVALID_RESPONSE);
        return data;
    }
    @Override public CompletableFuture<Track> getTrack(SongId id) {
        requireSong(id); return submit(cookie -> track(detail(id), id));
    }
    static Track track(JsonObject song, SongId requested) {
        String hash = KugouApi.text(song, "FileHash", "hash", "filehash");
        SongId id = requested == null ? new SongId(hash, MusicPlatform.KUGOU) : requested;
        requireSong(id);
        String title = KugouApi.display(KugouApi.text(song, "SongName", "songName", "songname"));
        String artist = KugouApi.display(KugouApi.text(song, "SingerName", "author_name", "singername"));
        String filename = KugouApi.display(KugouApi.text(song, "FileName", "fileName", "filename", "name"));
        if (title.isBlank()) {
            String[] parts = filename.split(" - ", 2);
            title = parts.length == 2 ? parts[1] : filename;
            if (artist.isBlank() && parts.length == 2) artist = parts[0];
        }
        if (title.isBlank()) throw new MusicException(MusicError.INVALID_RESPONSE);
        String seconds = KugouApi.text(song, "Duration", "duration", "timeLength", "timelength");
        long duration = seconds.isEmpty() ? 0 : Long.parseLong(seconds) * 1000;
        if (song.has("timelen")) duration = song.get("timelen").getAsLong();
        if (duration <= 0 || duration > 3_600_000) throw new MusicException(MusicError.INVALID_RESPONSE);
        String cover = KugouApi.text(song, "album_img", "Image", "img", "imgUrl", "imgurl", "cover");
        if (cover.isEmpty() && song.has("trans_param") && song.get("trans_param").isJsonObject()) cover = KugouApi.text(song.getAsJsonObject("trans_param"), "union_cover");
        return new Track(id, title, artist.isBlank() ? List.of() : List.of(artist), duration, KugouUris.image(cover));
    }
    @Override public CompletableFuture<SongSearchResult> searchSongs(String input, int offset, int limit) {
        String query = SongSearchResult.query(input);
        if (offset < 0 || offset > SongSearchResult.MAX_OFFSET || limit < 1 || limit > SongSearchResult.MAX_PAGE_SIZE || offset % limit != 0)
            return CompletableFuture.failedFuture(new MusicException(MusicError.INVALID_RESPONSE));
        return submit(cookie -> {
            var root = api.get("https://songsearch.kugou.com/song_search_v2", Map.of("keyword", query, "page", Integer.toString(offset / limit + 1),
                    "pagesize", Integer.toString(limit), "clientver", "2000", "platform", "WebFilter"));
            var data = root.getAsJsonObject("data"); var songs = new ArrayList<Track>();
            for (var item : data.getAsJsonArray("lists")) { if (songs.size() >= limit) break; songs.add(track(item.getAsJsonObject(), null)); }
            int total = Math.max(songs.size(), data.get("total").getAsInt());
            return new SongSearchResult(songs, total, !songs.isEmpty() && offset + songs.size() < total);
        });
    }
    @Override public CompletableFuture<Playlist> getPlaylist(SongId id) {
        if (id.platform() != MusicPlatform.KUGOU) throw new IllegalArgumentException("Wrong platform");
        KugouReferenceParser.playlist(id.key());
        return submit(cookie -> {
            var songs = new ArrayList<Track>();
            String name = "";
            int total = 0, pageSize = 100;
            for (int page = 1; page <= PlaybackQueue.MAX_TRACKS / pageSize; page++) {
                JsonObject root;
                if (id.key().startsWith("collection_")) {
                    root = api.gateway("/pubsongs/v2/get_other_list_file_nofilt", Map.of("global_collection_id", id.key(), "begin_idx", Integer.toString((page - 1) * pageSize),
                            "pagesize", Integer.toString(pageSize), "area_code", "1", "plat", "1", "type", "1", "mode", "1", "personal_switch", "1"), null, cookie);
                } else root = api.gateway("/api/v3/special/song", Map.of("specialid", id.key(), "page", Integer.toString(page), "pagesize", Integer.toString(pageSize)), null, null);
                var data = root.getAsJsonObject("data");
                if (data.has("list_info")) name = KugouApi.display(KugouApi.text(data.getAsJsonObject("list_info"), "name"));
                var entries = data.has("info") ? data.getAsJsonArray("info") : data.getAsJsonArray("songs");
                if (entries == null) throw new MusicException(MusicError.INVALID_RESPONSE);
                String count = KugouApi.text(data, "total", "count");
                total = count.isEmpty() ? songs.size() + entries.size() : Math.max(0, Integer.parseInt(count));
                for (var entry : entries) { if (songs.size() >= PlaybackQueue.MAX_TRACKS) break; songs.add(track(entry.getAsJsonObject(), null)); }
                if (entries.size() < pageSize || songs.size() >= total) break;
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            }
            return new Playlist(name, songs.stream().map(Track::id).toList(), songs, Math.max(total, songs.size()));
        });
    }
    @Override public CompletableFuture<Lyrics> getLyrics(SongId id) {
        requireSong(id);
        return submit(cookie -> {
            var root = api.get("https://krcs.kugou.com/search", Map.of("ver", "1", "man", "yes", "client", "pc", "hash", id.key()));
            var candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) return Lyrics.EMPTY;
            var candidate = candidates.get(0).getAsJsonObject();
            String lyric = KugouApi.text(candidate, "id"), key = KugouApi.text(candidate, "accesskey");
            if (!lyric.matches("[0-9]{1,19}") || !key.matches("[A-Za-z0-9]{1,128}")) throw new MusicException(MusicError.INVALID_RESPONSE);
            var data = api.get("https://krcs.kugou.com/download", Map.of("ver", "1", "client", "pc", "id", lyric, "accesskey", key, "fmt", "lrc", "charset", "utf8"));
            return Lyrics.parse(new String(Base64.getDecoder().decode(KugouApi.text(data, "content")), StandardCharsets.UTF_8), "");
        });
    }
    @Override public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) {
        requireSong(id);
        return submit(cookie -> {
            String hash = id.key().toLowerCase(Locale.ROOT);
            var params = new HashMap<String, String>();
            params.put("hash", hash); params.put("key", KugouApi.md5(hash + "57ae12eb6890223e355ccfcb74edf70d" + api.appid(cookie) + api.mid(cookie) + (cookie == null ? "0" : cookie.value("userid"))));
            params.put("quality", "128"); params.put("pid", "2"); params.put("cmd", "26"); params.put("behavior", "play"); params.put("IsFreePart", "0");
            params.put("album_id", "0"); params.put("album_audio_id", "0"); params.put("ssa_flag", "is_fromtrack"); params.put("version", "11430");
            params.put("clientver", "11430"); params.put("pidversion", "3001"); params.put("area_code", "1");
            var data = api.gateway("/v5/url", params, null, cookie);
            if (data.has("is_free_part") && data.get("is_free_part").getAsInt() != 0 || data.has("free_part") && !data.get("free_part").isJsonNull())
                throw new MusicException(MusicError.TRIAL_ONLY);
            var urls = data.getAsJsonArray("url");
            if (urls == null || urls.isEmpty()) throw new MusicException(MusicError.UNAVAILABLE);
            if (!"mp3".equalsIgnoreCase(KugouApi.text(data, "extName", "extname"))) throw new MusicException(MusicError.UNSUPPORTED_FORMAT);
            for (var url : urls) {
                URI uri;
                try { uri = KugouUris.audio(url.getAsString()); } catch (RuntimeException invalid) { continue; }
                if (uri.getPath().contains("/yp/clip/") || uri.getPath().contains("/yp/preview/")) throw new MusicException(MusicError.TRIAL_ONLY);
                if (!uri.getPath().endsWith(".mp3")) continue;
                return new PlaybackResource(id, uri, clock.instant().plusSeconds(300));
            }
            throw new MusicException(MusicError.INVALID_RESPONSE);
        });
    }
    private static void requireSong(SongId id) {
        if (id.platform() != MusicPlatform.KUGOU || !id.key().matches("[A-F0-9]{32}")) throw new IllegalArgumentException("Invalid Kugou song");
    }
    @Override public void close() { worker.shutdownNow(); }
}
