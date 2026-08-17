package com.lifeessentials.client.audio;

import java.io.IOException;
import java.util.function.Consumer;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import com.lifeessentials.LifeEssentials;
import com.lifeessentials.music.Track;
import net.minecraft.core.BlockPos;

/**
 * One track playing out of one speaker on this client.
 *
 * <p>Decoding and mixing happen on a dedicated daemon thread; the game thread
 * only ever writes the two volatile gain fields, so a stalling network stream
 * can never hitch the render loop.
 */
public final class SpeakerPlayback {
	/** A quarter second of audio buffered in the mixer. */
	private static final int LINE_BUFFER_BYTES = PcmSource.BYTES_PER_SECOND / 4;
	private static final int CHUNK_BYTES = 8192;
	/** Don't bother compensating for an open faster than this. */
	private static final long CATCH_UP_THRESHOLD_MS = 250;
	/**
	 * Retries when a stream opens but yields nothing. YouTube throttles bursts of
	 * requests with a 403, and a speaker starting up asks every listener in range
	 * to fetch the same track at the same moment — so a couple of spaced retries
	 * are the difference between "it works" and "it randomly doesn't".
	 */
	private static final int MAX_ATTEMPTS = 3;
	/** Long enough for YouTube's throttle to let go; short enough to still feel live. */
	private static final long RETRY_DELAY_MS = 3000;
	/** Never burn more than a minute of audio catching up. */
	private static final long MAX_CATCH_UP_BYTES = 60L * PcmSource.BYTES_PER_SECOND;

	private final BlockPos pos;
	private final Track track;
	private final int trackIndex;
	private final String key;
	/** Where playback actually begins, raised to cover a slow open. */
	private volatile long startOffsetMs;
	private final Runnable onEnded;
	private final Consumer<String> onError;

	private volatile float targetLeft;
	private volatile float targetRight;
	private volatile boolean paused;
	private volatile boolean cancelled;
	private volatile boolean done;
	private volatile long framesWritten;
	private volatile SourceDataLine line;
	private volatile PcmSource source;

	private float currentLeft;
	private float currentRight;

	public SpeakerPlayback(BlockPos pos, Track track, int trackIndex, String key, long startOffsetMs,
			float initialLeft, float initialRight, Runnable onEnded, Consumer<String> onError) {
		this.pos = pos;
		this.track = track;
		this.trackIndex = trackIndex;
		this.key = key;
		this.startOffsetMs = Math.max(0, startOffsetMs);
		this.targetLeft = initialLeft;
		this.targetRight = initialRight;
		this.currentLeft = initialLeft;
		this.currentRight = initialRight;
		this.onEnded = onEnded;
		this.onError = onError;

		Thread thread = new Thread(this::run, "life-essentials-speaker-" + pos.toShortString());
		thread.setDaemon(true);
		thread.start();
	}

	// ------------------------------------------------------------- game thread

	public String key() {
		return key;
	}

	public int trackIndex() {
		return trackIndex;
	}

	public BlockPos pos() {
		return pos;
	}

	public boolean isDone() {
		return done;
	}

	public boolean isPaused() {
		return paused;
	}

	/** True once real audio has reached the mixer — before that, drift is meaningless. */
	public boolean hasOutput() {
		return framesWritten > 0;
	}

	public void setGains(float left, float right) {
		targetLeft = left;
		targetRight = right;
	}

	public void setPaused(boolean value) {
		paused = value;
	}

	public void stop() {
		cancelled = true;
		SourceDataLine current = line;
		if (current != null) {
			// unblocks a write that is waiting on a full buffer
			current.flush();
			current.stop();
		}
		// and a read that is waiting on a stalled network stream
		PcmSource currentSource = source;
		if (currentSource != null) {
			currentSource.close();
		}
	}

	/** Milliseconds of the track the listener has actually heard by now. */
	public long positionMs() {
		SourceDataLine current = line;
		long played = framesWritten;
		if (current != null) {
			int buffered = current.getBufferSize() - current.available();
			played -= Math.max(0, buffered) / PcmSource.BYTES_PER_FRAME;
		}
		return startOffsetMs + Math.max(0, played) * 1000L / PcmSource.FRAMES_PER_SECOND;
	}

	// ------------------------------------------------------------ audio thread

	/**
	 * YouTube hands out stream urls that occasionally come back 403 — a stale
	 * url, or simply several listeners resolving the same track at once. One
	 * retry re-resolves from scratch, which clears it.
	 */
	private void run() {
		for (int attempt = 1; attempt <= MAX_ATTEMPTS && !cancelled; attempt++) {
			if (attemptPlayback(attempt == MAX_ATTEMPTS)) return;
			sleep(RETRY_DELAY_MS);
		}
	}

	/** Returns true when the attempt is final — played, cancelled, or reported. */
	private boolean attemptPlayback(boolean lastAttempt) {
		PcmSource opening = null;
		SourceDataLine opened = null;
		try {
			long openBegan = System.currentTimeMillis();
			opening = AudioSources.open(track, startOffsetMs);
			source = opening;
			if (cancelled) return true;
			// Resolving a YouTube url or spinning up ffmpeg can take seconds, and the
			// rest of the room didn't wait. Throw away the audio we were meant to have
			// played during the open so we come in level with everyone else, instead of
			// starting late and being yanked back by the drift check forever.
			catchUp(opening, openBegan);
			if (cancelled) return true;
			opened = AudioSystem.getSourceDataLine(PcmSource.FORMAT);
			opened.open(PcmSource.FORMAT, LINE_BUFFER_BYTES);
			opened.start();
			line = opened;
			pump(opening, opened);
			if (cancelled) return true;
			if (framesWritten == 0) {
				// the decoder exited without ever handing us audio; its own
				// complaint is far more useful than "the track ended"
				if (!lastAttempt) return false;
				String why = opening.diagnostics();
				onError.accept(why.isEmpty() ? "the decoder produced no audio" : why);
				return true;
			}
			opened.drain();
			onEnded.run();
			return true;
		} catch (AudioSources.OpenFailure e) {
			// a missing tool or a bad file won't fix itself on a retry
			onError.accept(e.getMessage());
			return true;
		} catch (LineUnavailableException e) {
			onError.accept("No audio device available for the speaker");
			return true;
		} catch (IOException e) {
			if (cancelled) return true;
			if (!lastAttempt && framesWritten == 0) return false;
			onError.accept("The stream for \"" + track.displayTitle() + "\" dropped");
			return true;
		} catch (Exception e) {
			LifeEssentials.LOGGER.warn("Speaker playback failed at {}", pos, e);
			if (!cancelled) onError.accept("Playback failed — see the log");
			return true;
		} finally {
			line = null;
			source = null;
			if (opened != null) {
				opened.stop();
				opened.close();
			}
			if (opening != null) opening.close();
			// only give up the slot once no further attempt is coming
			if (cancelled || lastAttempt || framesWritten > 0) {
				done = true;
			}
		}
	}

	/**
	 * Discards however much audio elapsed while the stream was opening, so we
	 * come in level with the listeners who were already playing.
	 *
	 * <p>The target is recomputed every pass rather than fixed up front: with a
	 * piped source ffmpeg emits nothing until it has decoded up to the seek
	 * point, so most of the delay lands on the first read, not on the open. This
	 * converges because decoding outruns realtime by a wide margin.
	 */
	private void catchUp(PcmSource source, long openBegan) throws IOException {
		byte[] scratch = new byte[CHUNK_BYTES];
		long discarded = 0;
		while (!cancelled && discarded < MAX_CATCH_UP_BYTES) {
			long playedMs = discarded * 1000L / PcmSource.BYTES_PER_SECOND;
			long behindMs = (System.currentTimeMillis() - openBegan) - playedMs;
			if (behindMs < CATCH_UP_THRESHOLD_MS) break;
			long want = Math.min(scratch.length, behindMs * PcmSource.BYTES_PER_SECOND / 1000);
			int read = source.read(scratch, 0, (int) Math.max(PcmSource.BYTES_PER_FRAME, want));
			if (read < 0) break; // track is shorter than the seek point
			discarded += read;
		}
		startOffsetMs += discarded * 1000L / PcmSource.BYTES_PER_SECOND;
	}

	private void pump(PcmSource source, SourceDataLine out) throws IOException {
		byte[] input = new byte[CHUNK_BYTES];
		byte[] mixed = new byte[CHUNK_BYTES];
		int carry = 0;

		while (!cancelled) {
			if (paused) {
				if (out.isRunning()) out.stop();
				sleep(40);
				continue;
			}
			if (!out.isRunning()) out.start();

			int read = source.read(input, carry, input.length - carry);
			if (read < 0) return;
			int available = carry + read;
			int usable = available - available % PcmSource.BYTES_PER_FRAME;
			if (usable > 0) {
				applyGain(input, mixed, usable);
				out.write(mixed, 0, usable);
				framesWritten += usable / PcmSource.BYTES_PER_FRAME;
			}
			carry = available - usable;
			if (carry > 0) {
				System.arraycopy(input, usable, input, 0, carry);
			}
		}
	}

	/** Ramps from the previous gains to the current targets across the chunk. */
	private void applyGain(byte[] in, byte[] out, int length) {
		float endLeft = targetLeft;
		float endRight = targetRight;
		int frames = length / PcmSource.BYTES_PER_FRAME;
		float stepLeft = frames == 0 ? 0 : (endLeft - currentLeft) / frames;
		float stepRight = frames == 0 ? 0 : (endRight - currentRight) / frames;
		float gainLeft = currentLeft;
		float gainRight = currentRight;

		for (int frame = 0; frame < frames; frame++) {
			int i = frame * PcmSource.BYTES_PER_FRAME;
			short left = (short) ((in[i] & 0xFF) | (in[i + 1] << 8));
			short right = (short) ((in[i + 2] & 0xFF) | (in[i + 3] << 8));
			int scaledLeft = clamp((int) (left * gainLeft));
			int scaledRight = clamp((int) (right * gainRight));
			out[i] = (byte) (scaledLeft & 0xFF);
			out[i + 1] = (byte) ((scaledLeft >> 8) & 0xFF);
			out[i + 2] = (byte) (scaledRight & 0xFF);
			out[i + 3] = (byte) ((scaledRight >> 8) & 0xFF);
			gainLeft += stepLeft;
			gainRight += stepRight;
		}
		currentLeft = endLeft;
		currentRight = endRight;
	}

	private static int clamp(int sample) {
		if (sample > Short.MAX_VALUE) return Short.MAX_VALUE;
		if (sample < Short.MIN_VALUE) return Short.MIN_VALUE;
		return sample;
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
