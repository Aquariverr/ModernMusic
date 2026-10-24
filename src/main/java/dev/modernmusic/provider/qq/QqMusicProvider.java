package dev.modernmusic.provider.qq;

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

public final class QqMusicProvider implements MusicProvider {
    private final HttpTransport http;
    private final Clock clock;
    private final Supplier<SessionCookie> credentials;
    private final Consumer<SessionCookie> expired;
    private final ThreadPoolExecutor worker = AsyncTasks.executor("modernmusic-qq", 2, 8);
    public QqMusicProvider(HttpTransport http, Clock clock, Supplier<SessionCookie> credentials, Consumer<SessionCookie> expired) {
        this.http = http; this.clock = clock; this.credentials = credentials; this.expired = expired;
    }
    private <T> CompletableFuture<T> submit(Function<SessionCookie, T> action) {
        return ProviderTasks.submit(worker, credentials, expired, action);
    }
    private JsonObject detail(String reference, SessionCookie cookie) {
        var param = new JsonObject();
        if (reference.matches("[0-9]+")) param.addProperty("song_id", Long.parseLong(reference)); else param.addProperty("song_mid", reference);
        JsonObject song = QqApi.rpc(http, cookie, "music.pf_song_detail_svr", "get_song_detail_yqq", param).getAsJsonObject("track_info");
        if (song == null) throw new MusicException(MusicError.UNAVAILABLE);
        if (reference.matches("[0-9]+") ? !reference.equals(QqApi.text(song, "id")) : !reference.equals(QqApi.text(song, "mid")))
            throw new MusicException(MusicError.INVALID_RESPONSE);
        return song;
    }
    public CompletableFuture<Track> getReference(String reference) {
        String valid = QqReferenceParser.song(reference);
        return submit(cookie -> track(detail(valid, cookie)));
    }
    @Override public CompletableFuture<Track> getTrack(SongId id) { requireQq(id); return getReference(Long.toString(id.value())); }
    static Track track(JsonObject song) {
        boolean legacy = song.has("songid");
        var id = new SongId(song.get(legacy ? "songid" : "id").getAsLong(), MusicPlatform.QQ);
        String title = DisplayText.clean(QqApi.text(song, legacy ? "songname" : "name"));
        if (title.isBlank()) title = DisplayText.clean(QqApi.text(song, "title"));
        List<String> artists = new ArrayList<>();
        if (song.has("singer")) for (var singer : song.getAsJsonArray("singer")) if (artists.size() < 20) artists.add(DisplayText.clean(QqApi.text(singer.getAsJsonObject(), "name")));
        String album = legacy ? QqApi.text(song, "albummid") : QqApi.text(song.getAsJsonObject("album"), "mid");
        URI artwork = album.matches("[A-Za-z0-9]{14}") ? QqUris.image("https://y.gtimg.cn/music/photo_new/T002R300x300M000" + album + ".jpg") : null;
        long duration = song.has("interval") ? song.get("interval").getAsLong() * 1000 : 0;
        if (duration < 0 || duration > 3_600_000) throw new MusicException(MusicError.INVALID_RESPONSE);
        return new Track(id, title, artists, duration, artwork);
    }
    @Override public CompletableFuture<SongSearchResult> searchSongs(String input, int offset, int limit) {
        String query = SongSearchResult.query(input);
        if (offset < 0 || offset > SongSearchResult.MAX_OFFSET || limit < 1 || limit > SongSearchResult.MAX_PAGE_SIZE || offset % limit != 0)
            return CompletableFuture.failedFuture(new MusicException(MusicError.INVALID_RESPONSE));
        return submit(cookie -> {
            var param = new JsonObject(); param.addProperty("query", query); param.addProperty("search_type", 0);
            param.addProperty("num_per_page", limit); param.addProperty("page_num", offset / limit + 1);
            param.addProperty("searchid", Long.toString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)));
            param.addProperty("highlight", false); param.addProperty("grp", true);
            var data = QqApi.rpc(http, cookie, "music.search.SearchCgiService", "DoSearchForQQMusicMobile", param);
            var body = data.getAsJsonObject("body");
            var tracks = new ArrayList<Track>();
            for (var song : body.getAsJsonArray("item_song")) { if (tracks.size() >= limit) break; tracks.add(track(song.getAsJsonObject())); }
            int total = data.has("meta") && data.getAsJsonObject("meta").has("sum") ? data.getAsJsonObject("meta").get("sum").getAsInt() : offset + tracks.size();
            return new SongSearchResult(tracks, Math.max(total, tracks.size()), offset + tracks.size() < total && !tracks.isEmpty());
        });
    }
    @Override public CompletableFuture<Playlist> getPlaylist(SongId id) {
        requireQq(id);
        return submit(cookie -> {
            var root = QqApi.json(http.qqApi(URI.create("https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?format=json&type=1&utf8=1&onlysong=0&nosign=1&disstid="
                    + id.value() + "&g_tk=" + QqApi.token(cookie)), 4 * 1024 * 1024, cookie, null).body());
            var lists = root.getAsJsonArray("cdlist");
            if (lists == null || lists.isEmpty()) throw new MusicException(MusicError.UNAVAILABLE);
            var list = lists.get(0).getAsJsonObject(); var tracks = new ArrayList<Track>();
            for (var song : list.getAsJsonArray("songlist")) { if (tracks.size() >= PlaybackQueue.MAX_TRACKS) break; tracks.add(track(song.getAsJsonObject())); }
            int total = list.has("total_song_num") ? list.get("total_song_num").getAsInt() : tracks.size();
            return new Playlist(DisplayText.clean(QqApi.text(list, "dissname")), tracks.stream().map(Track::id).toList(), tracks, Math.max(total, tracks.size()));
        });
    }
    @Override public CompletableFuture<Lyrics> getLyrics(SongId id) {
        requireQq(id);
        return submit(cookie -> {
            var param = new JsonObject(); param.addProperty("songID", id.value());
            var root = QqApi.rpc(http, cookie, "music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo", param);
            return Lyrics.parse(decode(QqApi.text(root, "lyric")), decode(QqApi.text(root, "trans")));
        });
    }
    private static String decode(String text) { return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8); }
    @Override public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) {
        requireQq(id);
        return submit(cookie -> {
            var song = detail(Long.toString(id.value()), cookie);
            String mid = QqApi.text(song, "mid"), media = QqApi.text(song.getAsJsonObject("file"), "media_mid");
            if (!mid.matches("[A-Za-z0-9]{14}")) throw new MusicException(MusicError.INVALID_RESPONSE);
            if (!media.matches("[A-Za-z0-9]{14}")) media = mid;
            var param = new JsonObject(); var mids = new JsonArray(); mids.add(mid); param.add("songmid", mids);
            var types = new JsonArray(); types.add(0); param.add("songtype", types);
            var files = new JsonArray(); files.add("M500" + media + ".mp3"); param.add("filename", files);
            param.addProperty("guid", Long.toString(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L)));
            param.addProperty("uin", cookie == null ? "0" : cookie.qqUin()); param.addProperty("loginflag", cookie == null ? 0 : 1); param.addProperty("platform", "20");
            var data = QqApi.rpc(http, cookie, "vkey.GetVkeyServer", "CgiGetVkey", param);
            var info = data.getAsJsonArray("midurlinfo");
            if (info == null || info.isEmpty()) throw new MusicException(MusicError.UNAVAILABLE);
            var entry = info.get(0).getAsJsonObject(); String path = QqApi.text(entry, "purl");
            if (path.isEmpty()) throw new MusicException(MusicError.UNAVAILABLE);
            if (!mid.equals(QqApi.text(entry, "songmid")) || !path.startsWith("M500") || !path.split("\\?", 2)[0].endsWith(".mp3")) throw new MusicException(MusicError.UNSUPPORTED_FORMAT);
            for (var sip : data.getAsJsonArray("sip")) {
                try {
                    URI base = QqUris.audio(sip.getAsString());
                    return new PlaybackResource(id, QqUris.audio(base.resolve(path).toString()), clock.instant().plusSeconds(300));
                } catch (MusicException ignored) { }
            }
            throw new MusicException(MusicError.INVALID_RESPONSE);
        });
    }
    private static void requireQq(SongId id) { if (id.platform() != MusicPlatform.QQ) throw new IllegalArgumentException("Wrong music platform"); }
    @Override public void close() { worker.shutdownNow(); }
}
