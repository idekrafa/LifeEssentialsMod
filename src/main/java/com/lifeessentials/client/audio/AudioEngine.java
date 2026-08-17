package com.lifeessentials.client.audio;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.lifeessentials.client.ClientNet;
import com.lifeessentials.client.ClientSpeakers;
import com.lifeessentials.client.music.MusicClientState;
import com.lifeessentials.music.Track;
import com.lifeessentials.music.TrackSource;
import com.lifeessentials.net.MusicPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps this client's speakers playing whatever the server says they are.
 *
 * <p>Everyone in range decodes the same track locally and starts at the same
 * offset, which is what makes a speaker sound like one shared sound instead of
 * a stream the server would have to push to every player.
 */
public final class AudioEngine {
	/** Restart the decoder when we drift further than this from the server. */
	private static final long RESYNC_THRESHOLD_MS = 2500;
	/** Keep a paused decoder warm this long before throwing it away. */
	private static final long PAUSE_GRACE_MS = 60000;
	/** Don't repeat the same error for the same track more than this often. */
	private static final long ERROR_COOLDOWN_MS = 60000;
	/** Full volume this close to the speaker, then a smooth roll-off. */
	private static final double NEAR_FIELD = 2.0;

	private static final Map<BlockPos, SpeakerPlayback> ACTIVE = new HashMap<>();
	private static final Map<BlockPos, Long> PAUSED_SINCE = new HashMap<>();
	private static final Map<String, Long> ERRORS = new HashMap<>();

	/** Loudest speaker audible right now, 0..1 — drives the game-audio duck. */
	private static volatile float audibleGain = 0.0f;

	private static String lastSpotifyKey = "";

	private AudioEngine() {
	}

	public static float audibleGain() {
		return audibleGain;
	}

	public static boolean isAnythingPlaying() {
		return !ACTIVE.isEmpty();
	}

	public static void clientTick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			stopAll();
			return;
		}
		ClientSpeakers.pruneStale();

		float master = minecraft.options.getSoundSourceVolume(SoundSource.MASTER);
		float loudest = 0.0f;
		boolean spotifyClaimed = false;

		for (ClientSpeakers.View view : ClientSpeakers.all()) {
			BlockPos pos = view.pos();
			Track track = view.track();
			if (track.isEmpty() || view.range() <= 0) {
				stop(pos);
				continue;
			}

			Vec3 speakerCenter = view.center();
			double distance = player.position().distanceTo(speakerCenter);
			if (distance > view.range()) {
				stop(pos);
				continue;
			}

			// OpenAL applies the distance roll-off itself, so the source gain is just
			// the speaker's own volume. The attenuated value is still worth computing
			// for the duck, which needs to know how loud this *sounds* from here.
			float sourceGain = master * (view.volume() / 100.0f);
			float perceived = sourceGain * attenuation(distance, view.range());

			if (track.source() == TrackSource.SPOTIFY) {
				if (view.playing() && !spotifyClaimed) {
					spotifyClaimed = true;
					relayToSpotify(view, track);
				}
				continue;
			}

			if (!view.playing()) {
				pauseOrDrop(pos);
				continue;
			}
			if (perceived > loudest) loudest = perceived;

			drive(minecraft, view, track, speakerCenter, sourceGain);
		}

		// speakers that vanished from the sync map
		for (Iterator<Map.Entry<BlockPos, SpeakerPlayback>> it = ACTIVE.entrySet().iterator();
				it.hasNext(); ) {
			Map.Entry<BlockPos, SpeakerPlayback> entry = it.next();
			if (ClientSpeakers.get(entry.getKey()) == null || entry.getValue().isDone()) {
				entry.getValue().stop();
				PAUSED_SINCE.remove(entry.getKey());
				it.remove();
			}
		}

		audibleGain = loudest;
		if (!spotifyClaimed) {
			lastSpotifyKey = ""; // walked away — let the next hand-off happen again
		}
	}

	private static void drive(Minecraft minecraft, ClientSpeakers.View view, Track track,
			Vec3 centre, float gain) {
		BlockPos pos = view.pos();
		String key = playbackKey(view, track);
		SpeakerPlayback playback = ACTIVE.get(pos);

		if (playback != null && (!playback.key().equals(key) || playback.isDone())) {
			playback.stop();
			ACTIVE.remove(pos);
			playback = null;
		}
		if (playback == null) {
			if (recentlyFailed(key)) return;
			playback = new SpeakerPlayback(pos, track, view.trackIndex(), key, view.positionMs(),
					() -> reportEnded(minecraft, pos, view.trackIndex()),
					message -> reportError(minecraft, key, track, message));
			ACTIVE.put(pos, playback);
			PAUSED_SINCE.remove(pos);
		}

		playback.setPaused(false);
		PAUSED_SINCE.remove(pos);
		// all OpenAL work happens here, on the client thread
		playback.clientTick(centre, gain, view.range());

		if (!playback.hasOutput()) return; // still opening — nothing to compare against yet
		long drift = view.positionMs() - playback.positionMs();
		if (Math.abs(drift) > RESYNC_THRESHOLD_MS) {
			playback.stop();
			ACTIVE.remove(pos);
		}
	}

	/** Server says paused: keep the decoder warm for a while, then let it go. */
	private static void pauseOrDrop(BlockPos pos) {
		SpeakerPlayback playback = ACTIVE.get(pos);
		if (playback == null) return;
		playback.pauseAudio();
		long since = PAUSED_SINCE.computeIfAbsent(pos, key -> System.currentTimeMillis());
		if (System.currentTimeMillis() - since > PAUSE_GRACE_MS) {
			stop(pos);
		}
	}

	private static String playbackKey(ClientSpeakers.View view, Track track) {
		return view.pos().asLong() + "|" + view.trackIndex() + "|" + track.id();
	}

	private static void stop(BlockPos pos) {
		SpeakerPlayback playback = ACTIVE.remove(pos);
		PAUSED_SINCE.remove(pos);
		if (playback != null) playback.stop();
	}

	public static void stopAll() {
		for (SpeakerPlayback playback : ACTIVE.values()) {
			playback.stop();
		}
		ACTIVE.clear();
		PAUSED_SINCE.clear();
		ERRORS.clear();
		audibleGain = 0.0f;
		lastSpotifyKey = "";
	}

	// ------------------------------------------------------------- positioning

	private static float attenuation(double distance, int range) {
		if (distance <= NEAR_FIELD) return 1.0f;
		double t = (distance - NEAR_FIELD) / Math.max(1.0, range - NEAR_FIELD);
		return (float) Math.pow(Math.max(0.0, 1.0 - t), 1.6);
	}

	// ------------------------------------------------------------- side effects

	private static void reportEnded(Minecraft minecraft, BlockPos pos, int trackIndex) {
		minecraft.execute(() -> ClientNet.send(new MusicPayloads.SpeakerTrackEndedC2S(pos, trackIndex)));
	}

	private static boolean recentlyFailed(String key) {
		Long when = ERRORS.get(key);
		return when != null && System.currentTimeMillis() - when < ERROR_COOLDOWN_MS;
	}

	private static void reportError(Minecraft minecraft, String key, Track track, String message) {
		ERRORS.put(key, System.currentTimeMillis());
		minecraft.execute(() -> {
			if (minecraft.player == null) return;
			minecraft.player.displayClientMessage(Component.literal("🔊 " + track.displayTitle()
					+ ": " + message).withStyle(ChatFormatting.YELLOW), false);
		});
	}

	/** Spotify can't be decoded — hand the track to the listener's desktop app. */
	private static void relayToSpotify(ClientSpeakers.View view, Track track) {
		String key = view.pos().asLong() + "|" + track.uri();
		if (key.equals(lastSpotifyKey)) return;
		lastSpotifyKey = key;
		MusicClientState.handleListenAlong(track.uri());
	}
}
