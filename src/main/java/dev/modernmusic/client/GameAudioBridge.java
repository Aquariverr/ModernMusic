package dev.modernmusic.client;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import java.util.function.Supplier;

/** Applies game volume and temporarily suppresses background music without changing saved options. */
public final class GameAudioBridge {
    // Vanilla calls this category "Jukebox/Note Blocks" (唱片机/音符盒).
    public static final SoundSource SOURCE = SoundSource.RECORDS;
    private final Supplier<PlaybackController> player;
    private boolean suppressing;

    public GameAudioBridge(Supplier<PlaybackController> player) { this.player = player; }

    public void tick(Minecraft game) {
        PlaybackController current = player.get();
        if (current != null) current.tick(game.options.getSoundSourceVolume(SoundSource.MASTER)
                * game.options.getSoundSourceVolume(SOURCE));
        boolean active = active();
        if (active && !suppressing) {
            game.getMusicManager().stopPlaying();
            game.getSoundManager().stop(null, SoundSource.MUSIC);
        }
        suppressing = active;
    }

    public void selectMusic(SelectMusicEvent event) { if (active()) event.setMusic(null); }
    public void playSound(PlaySoundEvent event) {
        if (active() && event.getSound() != null && event.getSound().getSource() == SoundSource.MUSIC)
            event.setSound(null);
    }
    private boolean active() {
        PlaybackController current = player.get();
        return current != null && current.suppressesGameMusic();
    }
}
