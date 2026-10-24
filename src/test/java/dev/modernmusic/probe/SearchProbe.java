package dev.modernmusic.probe;

import dev.modernmusic.provider.BoundedHttpTransport;
import dev.modernmusic.provider.netease.NeteaseMusicProvider;
import java.time.Clock;

/** Explicit guest-only live search check; never runs as part of build/test. */
public final class SearchProbe {
    public static void main(String[] args) {
        try (var http = new BoundedHttpTransport(); var provider = new NeteaseMusicProvider(http, Clock.systemUTC())) {
            var first = provider.searchSongs("晴天", 0, 5).join();
            if (first.tracks().isEmpty() || !first.hasMore()) throw new IllegalStateException("Missing public search results");
            var next = provider.searchSongs("晴天", 5, 5).join();
            if (next.tracks().isEmpty() || first.tracks().equals(next.tracks())) throw new IllegalStateException("Pagination failed");
            System.out.printf("PASS: guest search total=%d first=%d next=%d firstId=%s%n", first.total(), first.tracks().size(), next.tracks().size(), first.tracks().get(0).id());
        }
    }
}
