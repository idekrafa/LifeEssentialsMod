package com.lifeessentials.backend;

import java.io.IOException;

import javax.sound.sampled.AudioInputStream;

import com.lifeessentials.client.audio.PcmSource;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;

/**
 * One decoding track, exposed to the mod as a plain {@link PcmSource}.
 *
 * <p>{@code PcmSource} is loaded by the parent classloader, so an instance created
 * here is the same type the mod's mixer expects — which is what lets the engine live
 * behind a boundary without the mod knowing anything about it.
 */
final class LavaPcmSource implements PcmSource {
	private final AudioPlayer player;
	private final AudioInputStream stream;
	private volatile String diagnostics = "";

	LavaPcmSource(AudioPlayer player, AudioInputStream stream) {
		this.player = player;
		this.stream = stream;
	}

	AudioEventListener events() {
		return new AudioEventAdapter() {
			@Override
			public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException e) {
				diagnostics = LavaBackend.friendly(e);
			}

			@Override
			public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
				diagnostics = "The stream stopped responding";
			}

			@Override
			public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason reason) {
				if (reason == AudioTrackEndReason.LOAD_FAILED && diagnostics.isEmpty()) {
					diagnostics = "The stream failed partway through";
				}
			}
		};
	}

	@Override
	public int read(byte[] buffer, int offset, int length) throws IOException {
		// AudioInputStream refuses a length that isn't a whole number of frames
		int aligned = length - length % BYTES_PER_FRAME;
		if (aligned <= 0) return 0;
		return stream.read(buffer, offset, aligned);
	}

	@Override
	public String diagnostics() {
		return diagnostics;
	}

	@Override
	public void close() {
		try {
			stream.close();
		} catch (IOException ignored) {
			// the player is going away regardless
		}
		player.destroy();
	}
}
