package dev.modernmusic.network;

import java.util.function.BiConsumer;
import java.util.function.Function;

public record PacketCodec<B, T>(BiConsumer<B, T> encoder, Function<B, T> decoder) {
    public void encode(B buffer, T packet) { encoder.accept(buffer, packet); }
    public T decode(B buffer) { return decoder.apply(buffer); }
    public static <B, T> PacketCodec<B, T> of(BiConsumer<B, T> encoder, Function<B, T> decoder) {
        return new PacketCodec<>(encoder, decoder);
    }
}
