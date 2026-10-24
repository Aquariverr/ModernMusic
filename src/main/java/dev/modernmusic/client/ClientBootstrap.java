package dev.modernmusic.client;

/** DistExecutor may inspect this method on a server; keep all client types inside the method body. */
public final class ClientBootstrap {
    private ClientBootstrap() {}
    public static void initialize() { ModernMusicClient.initialize(); }
}
