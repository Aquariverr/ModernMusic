package dev.modernmusic.provider.netease;

import com.google.gson.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.auth.SessionCookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;
import java.util.function.Consumer;

/** Direct Java adapter. Optional account cookies are confined to the first-party API transport. */
public final class NeteaseMusicProvider implements MusicProvider {
    private static final int MAX_JSON = 1024 * 1024;
    private final HttpTransport transport;
    private final Clock clock;
    private final Supplier<SessionCookie> credentials;
    private final Consumer<SessionCookie> expired;
    private final java.util.function.Function<String, byte[]> encoder;
    private final ThreadPoolExecutor executor = AsyncTasks.executor("modernmusic-provider", 2, 8);
    private SessionCookie playbackOwner;
    private LoginCookies playbackCookies;

    public NeteaseMusicProvider(HttpTransport transport, Clock clock) {
        this(transport, clock, () -> null, cookie -> { });
    }

    public NeteaseMusicProvider(HttpTransport transport, Clock clock, Supplier<SessionCookie> credentials, Consumer<SessionCookie> expired) {
        this(transport, clock, credentials, expired, WeapiCodec::encode);
    }

    NeteaseMusicProvider(HttpTransport transport, Clock clock, Supplier<SessionCookie> credentials,
                         Consumer<SessionCookie> expired, java.util.function.Function<String, byte[]> encoder) {
        this.transport = transport;
        this.clock = clock;
        this.credentials = credentials;
        this.expired = expired;
        this.encoder = encoder;
    }

    private record RequestSession(SessionCookie cookie, LoginCookies cookies) {}

    /** Capture the account and its cookie jar before queueing; late work cannot replace a new jar. */
    private synchronized <T> CompletableFuture<T> submit(java.util.function.Function<RequestSession, T> action) {
        if (executor.isShutdown()) return CompletableFuture.failedFuture(new MusicException(MusicError.RATE_LIMITED));
        SessionCookie cookie = credentials.get();
        var session = new RequestSession(cookie, cookiesFor(cookie));
        return AsyncTasks.submit(executor, () -> action.apply(session));
    }

    @Override
    public CompletableFuture<Track> getTrack(SongId id) {
        return submit(session -> {
            JsonObject root = request("/api/song/detail/?id=" + id + "&ids=%5B" + id + "%5D", session);
            try {
                JsonArray songs = root.getAsJsonArray("songs");
                if (songs == null || songs.isEmpty()) throw new MusicException(MusicError.UNAVAILABLE);
                JsonObject song = songs.get(0).getAsJsonObject();
                if (!id.toString().equals(song.get("id").getAsString())) throw new MusicException(MusicError.INVALID_RESPONSE);
                return readTrack(song);
            } catch (MusicException exception) { throw exception; }
            catch (RuntimeException exception) { throw new MusicException(MusicError.INVALID_RESPONSE); }
        });
    }

    private static Track readTrack(JsonObject song) {
        var artists = new ArrayList<String>();
        for (JsonElement artist : song.getAsJsonArray(song.has("ar") ? "ar" : "artists"))
            artists.add(DisplayText.clean(artist.getAsJsonObject().get("name").getAsString()));
        JsonObject album = song.getAsJsonObject(song.has("al") ? "al" : "album");
        URI art = album != null && album.has("picUrl") && !album.get("picUrl").isJsonNull()
                ? NeteaseUris.image(album.get("picUrl").getAsString()) : null;
        return new Track(new SongId(song.get("id").getAsLong()), DisplayText.clean(song.get("name").getAsString()),
                artists, song.get(song.has("dt") ? "dt" : "duration").getAsLong(), art);
    }

    @Override public CompletableFuture<java.util.List<Track>> getTracks(java.util.List<SongId> ids) {
        var songs = java.util.List.copyOf(ids);
        if (songs.isEmpty()) return CompletableFuture.completedFuture(java.util.List.of());
        if (songs.size() > 50) throw new IllegalArgumentException("metadata batch");
        return submit(session -> {
            String query = songs.stream().map(SongId::toString).collect(java.util.stream.Collectors.joining("%2C", "%5B", "%5D"));
            var root = request("/api/song/detail/?ids=" + query, session);
            try {
                var found = new ArrayList<Track>();
                for (var element : root.getAsJsonArray("songs")) {
                    var track = readTrack(element.getAsJsonObject());
                    if (songs.contains(track.id())) found.add(track);
                }
                return java.util.List.copyOf(found);
            } catch (RuntimeException invalid) { throw new MusicException(MusicError.INVALID_RESPONSE); }
        });
    }

    @Override public CompletableFuture<SongSearchResult> searchSongs(String input, int offset, int limit) {
        final String query;
        try {
            query = SongSearchResult.query(input);
            if (offset < 0 || offset > SongSearchResult.MAX_OFFSET || limit < 1 || limit > SongSearchResult.MAX_PAGE_SIZE)
                throw new IllegalArgumentException("search_page");
        } catch (IllegalArgumentException invalid) { return CompletableFuture.failedFuture(invalid); }
        return submit(session -> {
            SessionCookie cookie = session.cookie();
            try {
                LoginCookies cookies = session.cookies();
                var sent = cookies.snapshot();
                JsonObject payload = new JsonObject();
                payload.addProperty("s", query); payload.addProperty("type", 1);
                payload.addProperty("offset", offset); payload.addProperty("limit", limit);
                payload.addProperty("total", true); payload.addProperty("csrf_token", sent.csrf());
                var response = transport.postAccount(URI.create("https://music.163.com/weapi/search/get"),
                        MAX_JSON, sent.header(), encoder.apply(payload.toString()));
                cookies.accept(response.setCookies(), sent);
                byte[] body = response.body();
                JsonObject root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                checkCode(root.get("code").getAsInt());
                JsonObject result = root.getAsJsonObject("result");
                if (result == null) throw new MusicException(MusicError.INVALID_RESPONSE);
                int total = result.get("songCount").getAsInt();
                if (total < 0) throw new MusicException(MusicError.INVALID_RESPONSE);
                JsonArray songs = result.getAsJsonArray("songs");
                if (songs == null && total > 0 && offset < total) throw new MusicException(MusicError.INVALID_RESPONSE);
                var tracks = new ArrayList<Track>();
                if (songs != null) for (JsonElement song : songs) {
                    if (tracks.size() == limit) break;
                    tracks.add(readTrack(song.getAsJsonObject()));
                }
                boolean more = !tracks.isEmpty() && offset + tracks.size() < total
                        && offset + limit <= SongSearchResult.MAX_OFFSET;
                return new SongSearchResult(tracks, Math.max(total, tracks.size()), more);
            } catch (MusicException failure) {
                if (cookie != null && failure.error() == MusicError.AUTH_EXPIRED) expired.accept(cookie);
                throw failure;
            } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
            catch (RuntimeException invalid) { throw new MusicException(MusicError.INVALID_RESPONSE); }
        });
    }

    @Override public CompletableFuture<Lyrics> getLyrics(SongId id) {
        return submit(session -> {
            JsonObject root = request("/api/song/lyric?id=" + id + "&lv=-1&kv=-1&tv=-1&rv=-1", session);
            return Lyrics.parse(lyricText(root, "lrc"), lyricText(root, "tlyric"));
        });
    }
    private static String lyricText(JsonObject root, String name) {
        if (!root.has(name) || !root.get(name).isJsonObject()) return "";
        JsonElement value = root.getAsJsonObject(name).get("lyric");
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    @Override public CompletableFuture<Playlist> getPlaylist(SongId id) {
        return submit(session -> {
            JsonObject root = request("/api/v6/playlist/detail?id=" + id + "&n=" + PlaybackQueue.MAX_TRACKS + "&s=0", session, 4 * MAX_JSON);
            try {
                JsonObject list = root.getAsJsonObject("playlist");
                if (list == null || !id.toString().equals(list.get("id").getAsString())) throw new MusicException(MusicError.INVALID_RESPONSE);
                var ids = new ArrayList<SongId>(); var details = new ArrayList<Track>();
                JsonArray ordered = list.getAsJsonArray("trackIds");
                if (ordered == null) ordered = list.getAsJsonArray("tracks");
                if (ordered == null) throw new MusicException(MusicError.INVALID_RESPONSE);
                for (JsonElement entry : ordered) {
                    if (ids.size() == PlaybackQueue.MAX_TRACKS) break;
                    ids.add(new SongId(entry.getAsJsonObject().get("id").getAsLong()));
                }
                if (list.has("tracks") && list.get("tracks").isJsonArray()) for (JsonElement entry : list.getAsJsonArray("tracks")) {
                    try { Track track = readTrack(entry.getAsJsonObject()); if (ids.contains(track.id())) details.add(track); }
                    catch (RuntimeException ignored) { /* Missing metadata is loaded when that song is selected. */ }
                    if (details.size() >= PlaybackQueue.MAX_TRACKS) break;
                }
                int total = list.has("trackCount") ? Math.max(ids.size(), list.get("trackCount").getAsInt()) : ordered.size();
                return new Playlist(DisplayText.clean(list.get("name").getAsString()), ids, details, total);
            } catch (MusicException error) { throw error; }
            catch (RuntimeException error) { throw new MusicException(MusicError.INVALID_RESPONSE); }
        });
    }

    @Override
    public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) {
        return submit(session -> {
            SessionCookie cookie = session.cookie();
            try {
                LoginCookies cookies = session.cookies();
                // Keep resource bootstrap/rotation ordered without serializing unrelated HTTP.
                cookies.lockPlayback();
                try {
                    String before = cookies.playbackToken();
                    PlaybackResource resource = resolve(id, cookie, cookies);
                    if (!cookies.playbackToken().isEmpty() && !before.equals(cookies.playbackToken())) {
                        // The first response may create/rotate the session while returning an unusable URL.
                        // Resolve once with the server-issued cookie; never invent NMTID or send it to the CDN.
                        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                        org.slf4j.LoggerFactory.getLogger(NeteaseMusicProvider.class).info(
                                "Playback session initialized or rotated; resolving again authenticated={}", cookie != null);
                        resource = resolve(id, cookie, cookies);
                    }
                    return resource;
                } finally { cookies.unlockPlayback(); }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException();
            } catch (MusicException exception) {
                if (cookie != null && exception.error() == MusicError.AUTH_EXPIRED) expired.accept(cookie);
                throw exception;
            } catch (java.util.concurrent.CancellationException exception) { throw exception; }
            catch (RuntimeException exception) { throw new MusicException(MusicError.INVALID_RESPONSE); }
        });
    }

    private synchronized LoginCookies cookiesFor(SessionCookie cookie) {
        if (playbackCookies == null || playbackOwner != cookie) {
            playbackOwner = cookie;
            playbackCookies = new LoginCookies(cookie);
        }
        return playbackCookies;
    }

    private PlaybackResource resolve(SongId id, SessionCookie cookie, LoginCookies cookies) {
        // Match the web player's resource endpoint, with an MP3 quality supported by our decoder.
        var sent = cookies.snapshot();
        JsonObject payload = new JsonObject();
        payload.addProperty("ids", "[" + id + "]");
        payload.addProperty("level", "standard");
        payload.addProperty("encodeType", "mp3");
        payload.addProperty("csrf_token", sent.csrf());
        payload.addProperty("timestamp", clock.millis());
        boolean sessionReady = !sent.playbackToken().isEmpty();
        var response = transport.postAccount(URI.create("https://music.163.com/weapi/song/enhance/player/url/v1"),
                MAX_JSON, sent.header(), encoder.apply(payload.toString()));
        cookies.accept(response.setCookies(), sent);
        JsonObject root = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        checkCode(root.get("code").getAsInt());
        JsonArray rows = root.getAsJsonArray("data");
        if (rows == null || rows.isEmpty()) throw new MusicException(MusicError.UNAVAILABLE);
        JsonObject data = rows.get(0).getAsJsonObject();
        if (!id.toString().equals(data.get("id").getAsString())) throw new MusicException(MusicError.INVALID_RESPONSE);
        int code = data.get("code").getAsInt();
        boolean trial = data.has("freeTrialInfo") && !data.get("freeTrialInfo").isJsonNull();
        org.slf4j.LoggerFactory.getLogger(NeteaseMusicProvider.class).info(
                "Playback resource id={} authenticated={} code={} trial={} sessionReady={}", id, cookie != null, code, trial, sessionReady);
        if (trial) {
            throw new MusicException(MusicError.TRIAL_ONLY);
        }
        if (code == -110) throw new MusicException(MusicError.AUTH_REQUIRED);
        checkCode(code);
        if (!data.has("url") || data.get("url").isJsonNull() || data.get("url").getAsString().isBlank()) {
            throw new MusicException(MusicError.UNAVAILABLE);
        }
        if (!"mp3".equalsIgnoreCase(data.get("type").getAsString())) {
            throw new MusicException(MusicError.UNSUPPORTED_FORMAT);
        }
        long expires = data.get("expi").getAsLong();
        if (expires <= 0 || expires > 86400) throw new MusicException(MusicError.INVALID_RESPONSE);
        Instant deadline = clock.instant().plusSeconds(expires);
        return new PlaybackResource(id, NeteaseUris.audio(data.get("url").getAsString()), deadline);
    }

    private JsonObject request(String path, RequestSession session) {
        return request(path, session, MAX_JSON);
    }
    private JsonObject request(String path, RequestSession session, int maxBytes) {
        SessionCookie cookie = session.cookie();
        try {
            URI uri = URI.create("https://music.163.com" + path);
            byte[] body;
            if (cookie == null) body = transport.get(uri, maxBytes);
            else {
                LoginCookies cookies = session.cookies();
                var sent = cookies.snapshot();
                var response = transport.getWithCookies(uri, maxBytes, sent.session());
                cookies.accept(response.setCookies(), sent);
                body = response.body();
            }
            JsonObject result = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            checkCode(result.get("code").getAsInt());
            return result;
        } catch (MusicException exception) {
            if (cookie != null && exception.error() == MusicError.AUTH_EXPIRED) expired.accept(cookie);
            throw exception;
        }
        catch (RuntimeException exception) { throw new MusicException(MusicError.INVALID_RESPONSE); }
    }

    private static void checkCode(int code) {
        if (code == 200) return;
        throw new MusicException(switch (code) {
            case 301, 401 -> MusicError.AUTH_EXPIRED;
            case 429, 405, -460 -> MusicError.RATE_LIMITED;
            default -> MusicError.UNAVAILABLE;
        });
    }

    @Override public synchronized void close() {
        executor.shutdownNow(); playbackCookies = null; playbackOwner = null;
    }
}
