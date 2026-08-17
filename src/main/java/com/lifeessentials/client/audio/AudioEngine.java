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
	/** Hard panning is unpleasant on headphones; keep some signal in both ears. */
	private static final double MAX_PAN = 0.75;

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

			float attenuation = attenuation(distance, view.range());
			float gain = master * (view.volume() / 100.0f) * attenuation;

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
			if (gain > loudest) loudest = gain;

			float[] stereo = stereoGains(player, speakerCenter, gain, distance);
			drive(minecraft, view, track, stereo[0], stereo[1]);
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
			float left, float right) {
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
					left, right,
					() -> reportEnded(minecraft, pos, view.trackIndex()),
					message -> reportError(minecraft, key, track, message));
			ACTIVE.put(pos, playback);
			PAUSED_SINCE.remove(pos);
			return;
		}

		playback.setPaused(false);
		PAUSED_SINCE.remove(pos);
		playback.setGains(left, right);

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
		playback.setPaused(true);
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

	private static float[] stereoGains(LocalPlayer player, Vec3 speaker, float gain, double distance) {
		return stereoGains(speaker.x - player.getX(), speaker.z - player.getZ(),
				player.getYRot(), gain, distance);
	}

	/**
	 * Constant-power stereo placement: the speaker drifts to the ear it is on
	 * relative to where the player is looking, and centres when standing on it.
	 *
	 * <p>Minecraft yaw 0 looks down +Z, so the listener's forward vector is
	 * {@code (-sin yaw, cos yaw)} and their right is {@code (-cos yaw, -sin yaw)}.
	 */
	static float[] stereoGains(double dx, double dz, float yawDegrees, float gain, double distance) {
		double flat = Math.sqrt(dx * dx + dz * dz);
		if (flat < 0.001 || gain <= 0.0f) {
			float centre = gain * 0.7071f;
			return new float[] { centre, centre };
		}
		double yaw = Math.toRadians(yawDegrees);
		double rightward = (-dx * Math.cos(yaw) - dz * Math.sin(yaw)) / flat;
		// a speaker you're standing next to shouldn't swing hard left/right, and
		// even one straight off to the side keeps a little in the far ear
		double spread = Math.min(MAX_PAN, distance / 4.0 * MAX_PAN);
		double pan = Math.max(-1.0, Math.min(1.0, rightward)) * spread;
		double angle = (pan + 1.0) * Math.PI / 4.0;
		return new float[] { (float) (gain * Math.cos(angle)), (float) (gain * Math.sin(angle)) };
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
