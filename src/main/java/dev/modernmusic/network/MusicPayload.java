package dev.modernmusic.network;

import net.minecraft.resources.ResourceLocation;

/** Typed packet identity shared by the codecs and the Forge channel registry. */
public interface MusicPayload {
    Type<? extends MusicPayload> type();
    record Type<T extends MusicPayload>(ResourceLocation id, Class<T> packetClass) {}
}
