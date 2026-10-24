package dev.modernmusic.auth;

import com.google.gson.*;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import dev.modernmusic.provider.ProviderUris;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Arrays;

/** Automatic local session restore. Windows uses current-user DPAPI; POSIX uses an owner-only file. */
public final class RememberedSessionStore {
    private static final int MAGIC_WINDOWS = 0x4D4D5231, MAGIC_POSIX = 0x4D4D5232, MAX_FILE = 65536;
    private final Path directory, file, legacy;

    public RememberedSessionStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        file = this.directory.resolve("remembered-session.bin");
        legacy = this.directory.resolve("client-session.bin");
    }
    public boolean exists() { return Files.exists(file, LinkOption.NOFOLLOW_LINKS); }
    public boolean hasLegacy() { return Files.exists(legacy, LinkOption.NOFOLLOW_LINKS); }

    public synchronized void save(AccountSession session) {
        Path temporary = null; byte[] plaintext = null;
        try {
            checkPaths();
            if (Thread.currentThread().isInterrupted()) throw new AuthException("storage_error");
            if (!Files.exists(directory)) {
                if (Platform.isWindows()) Files.createDirectories(directory);
                else Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            }
            JsonObject root = new JsonObject();
            root.addProperty("userId", session.userId()); root.addProperty("nickname", session.nickname());
            root.addProperty("platform", session.cookie().platform().name()); root.addProperty("cookie", session.cookie().header()); root.addProperty("validatedAt", session.validatedAt().toString());
            if (session.avatar() != null) root.addProperty("avatar", session.avatar().toString());
            plaintext = root.toString().getBytes(StandardCharsets.UTF_8);
            byte[] payload = Platform.isWindows() ? Crypt32Util.cryptProtectData(plaintext) : plaintext;
            if (payload.length + 4 > MAX_FILE) throw new AuthException("storage_error");
            temporary = Platform.isWindows() ? Files.createTempFile(directory, "remember-", ".tmp")
                    : Files.createTempFile(directory, "remember-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(temporary, ByteBuffer.allocate(payload.length + 4).putInt(Platform.isWindows() ? MAGIC_WINDOWS : MAGIC_POSIX).put(payload).array());
            if (Thread.currentThread().isInterrupted()) throw new AuthException("storage_error");
            checkPaths();
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            Files.deleteIfExists(legacy);
        } catch (Exception error) { throw new AuthException("storage_error"); }
        finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
        }
    }
    public synchronized AccountSession load() {
        byte[] plaintext = null;
        try {
            checkPaths();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE) throw new IllegalArgumentException();
            if (!Platform.isWindows() && !Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------")))
                throw new IllegalArgumentException();
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_FILE + 1); }
            if (bytes.length < 5 || bytes.length > MAX_FILE) throw new IllegalArgumentException();
            int magic = ByteBuffer.wrap(bytes).getInt();
            if (magic != (Platform.isWindows() ? MAGIC_WINDOWS : MAGIC_POSIX)) throw new IllegalArgumentException();
            byte[] payload = Arrays.copyOfRange(bytes, 4, bytes.length);
            plaintext = Platform.isWindows() ? Crypt32Util.cryptUnprotectData(payload) : payload;
            JsonObject root = JsonParser.parseString(new String(plaintext, StandardCharsets.UTF_8)).getAsJsonObject();
            String userId = root.get("userId").getAsString();
            String nickname = root.get("nickname").getAsString().replaceAll("[\\p{Cntrl}§]", "");
            if (!userId.matches("[1-9][0-9]{0,18}") || nickname.length() > 64) throw new IllegalArgumentException();
            return new AccountSession(userId, nickname, SessionCookie.parse(root.get("cookie").getAsString(), root.has("platform") ? dev.modernmusic.domain.MusicPlatform.valueOf(root.get("platform").getAsString()) : dev.modernmusic.domain.MusicPlatform.NETEASE),
                    Instant.parse(root.get("validatedAt").getAsString()), root.has("avatar") ? ProviderUris.image(root.get("avatar").getAsString()) : null);
        } catch (Exception error) { throw new AuthException("restore_failed"); }
        finally { if (plaintext != null) Arrays.fill(plaintext, (byte) 0); }
    }
    public synchronized void clear() {
        try { checkPaths(); Files.deleteIfExists(file); Files.deleteIfExists(legacy); }
        catch (Exception error) { throw new AuthException("storage_error"); }
    }
    private void checkPaths() {
        for (Path path = directory; path != null; path = path.getParent())
            if (Files.isSymbolicLink(path)) throw new AuthException("storage_error");
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(legacy)) throw new AuthException("storage_error");
    }
}
