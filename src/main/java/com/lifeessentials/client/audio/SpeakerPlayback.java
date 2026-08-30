package com.lifeessentials.client.audio;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.lifeessentials.LifeEssentials;
import com.lifeessentials.music.Track;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * One track playing out of one speaker on this client.
 *
 * <p>Split across two threads on purpose:
 *
 * <ul>
 *   <li>a daemon thread decodes and does nothing else, so a stalled network
 *       stream can never hitch the render loop;
 *   <li>the client thread moves finished bytes into OpenAL, because OpenAL is
 *       not thread-safe and Minecraft shares one context.
 * </ul>
 *
 * <p>The two meet at {@link #chunks}, a bounded queue — bounded so a fast decoder
 * (LavaPlayer runs hundreds of times faster than realtime) can't run away and
 * buffer the whole song in memory.
 */
public final class SpeakerPlayback {
	/** A quarter second of audio per chunk. */
	private static final int CHUNK_BYTES = PcmSource.BYTES_PER_SECOND / 4;
	/** Roughly four seconds decoded ahead of what OpenAL holds. */
	private static final int MAX_CHUNKS = 16;
	/** Don't bother compensating for an open faster than this. */
	private static final long CATCH_UP_THRESHOLD_MS = 250;
	/** Never burn more than a minute of audio catching up. */
	private static final long MAX_CATCH_UP_BYTES = 60L * PcmSource.BYTES_PER_SECOND;

	private final BlockPos pos;
	private final Track track;
	private final int trackIndex;
	private final String key;
	private final Runnable onEnded;
	private final Consumer<String> onError;

	/** Where playback actually begins, raised to cover a slow open. */
	private volatile long startOffsetMs;
	private volatile boolean paused;
	private volatile boolean cancelled;
	private volatile boolean decoderFinished;
	private volatile boolean reportedEnd;
	private volatile boolean producedAudio;
	private volatile PcmSource source;

	private final BlockingQueue<byte[]> chunks = new ArrayBlockingQueue<>(MAX_CHUNKS);

	/** Client thread only. */
	private AlSpeakerSource al;

	public SpeakerPlayback(BlockPos pos, Track track, int trackIndex, String key, long startOffsetMs,
			Runnable onEnded, Consumer<String> onError) {
		this.pos = pos;
		this.track = track;
		this.trackIndex = trackIndex;
		this.key = key;
		this.startOffsetMs = Math.max(0, startOffsetMs);
		this.onEnded = onEnded;
		this.onError = onError;

		Thread thread = new Thread(this::run, "life-essentials-speaker-" + pos.toShortString());
		thread.setDaemon(true);
		thread.start();
	}

	// ------------------------------------------------------------ client thread

	public String key() {
		return key;
	}

	public int trackIndex() {
		return trackIndex;
	}

	public BlockPos pos() {
		return pos;
	}

	public boolean isPaused() {
		return paused;
	}

	/**
	 * Finished once the decoder is done and the queued audio has drained.
	 *
	 * <p>The {@code !producedAudio} arm matters: a decoder that failed without ever
	 * yielding a sample will never reach the end-of-track report, and without this
	 * the slot would be held forever.
	 */
	public boolean isDone() {
		return cancelled || (decoderFinished && chunks.isEmpty() && (reportedEnd || !producedAudio));
	}

	public boolean hasOutput() {
		return al != null && al.hasOutput();
	}

	public void setPaused(boolean value) {
		paused = value;
	}

	/**
	 * Moves decoded audio into OpenAL and keeps the source where the block is.
	 *
	 * <p>Must run on the client thread. Called once per tick per audible speaker.
	 */
	public void clientTick(Vec3 centre, float gain, int range) {
		if (cancelled) return;
		if (al == null) al = new AlSpeakerSource();

		al.reclaim();
		while (al.hasRoom()) {
			byte[] chunk = chunks.poll();
			if (chunk == null) break;
			al.enqueue(chunk, chunk.length);
		}
		al.place(centre, gain, range);

		if (paused) {
			al.pause();
			return;
		}
		al.play();

		if (decoderFinished && chunks.isEmpty() && !reportedEnd && al.playedFrames() > 0) {
			reportedEnd = true;
			onEnded.run();
		}
	}

	/**
	 * Client thread: silences the source without tearing the decoder down.
	 *
	 * <p>A paused speaker never reaches {@link #clientTick}, so the OpenAL source
	 * has to be stopped explicitly or it would keep playing what is still queued.
	 */
	public void pauseAudio() {
		paused = true;
		if (al != null) al.pause();
	}

	/** Milliseconds of the track the listener has actually heard by now. */
	public long positionMs() {
		if (al == null) return startOffsetMs;
		return startOffsetMs + al.playedFrames() * 1000L / PcmSource.FRAMES_PER_SECOND;
	}

	/** Client thread: tears down the decoder and releases the OpenAL source. */
	public void stop() {
		cancelled = true;
		PcmSource current = source;
		if (current != null) current.close(); // unblocks a read on a stalled stream
		chunks.clear();
		if (al != null) {
			al.destroy();
			al = null;
		}
	}

	// ------------------------------------------------------------ audio thread

	private void run() {
		PcmSource opening = null;
		try {
			long openBegan = System.currentTimeMillis();
			opening = AudioSources.open(track, startOffsetMs);
			source = opening;
			if (cancelled) return;
			catchUp(opening, openBegan);
			if (cancelled) return;
			pump(opening);
		} catch (AudioSources.OpenFailure e) {
			onError.accept(e.getMessage());
		} catch (IOException e) {
			if (!cancelled) onError.accept("The stream for \"" + track.displayTitle() + "\" dropped");
		} catch (Exception e) {
			LifeEssentials.LOGGER.warn("Speaker playback failed at {}", pos, e);
			if (!cancelled) onError.accept("Playback failed — see the log");
		} finally {
			decoderFinished = true;
			source = null;
			if (opening != null) {
				String why = opening.diagnostics();
				if (!cancelled && !why.isEmpty() && !producedAudio) {
					onError.accept(why);
				}
				opening.close();
			}
		}
	}

	/**
	 * Discards however much audio elapsed while the stream was opening, so we come
	 * in level with the listeners who were already playing.
	 *
	 * <p>The target is recomputed every pass rather than fixed up front, because
	 * most of the delay lands on the first read rather than on the open. It
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
			if (read < 0) return; // track is shorter than the seek point
			discarded += read;
		}
		startOffsetMs += discarded * 1000L / PcmSource.BYTES_PER_SECOND;
	}

	/** Reads whole chunks and hands them to the client thread. */
	private void pump(PcmSource source) throws IOException {
		byte[] chunk = new byte[CHUNK_BYTES];
		int filled = 0;
		while (!cancelled) {
			int read = source.read(chunk, filled, chunk.length - filled);
			if (read < 0) {
				if (filled > 0) offer(trim(chunk, filled));
				return;
			}
			filled += read;
			if (filled == chunk.length) {
				offer(chunk.clone());
				producedAudio = true;
				filled = 0;
			}
		}
	}

	private static byte[] trim(byte[] chunk, int filled) {
		int usable = filled - filled % PcmSource.BYTES_PER_FRAME;
		byte[] copy = new byte[usable];
		System.arraycopy(chunk, 0, copy, 0, usable);
		return copy;
	}

	/** Blocks while the queue is full — that back-pressure is what paces decoding. */
	private void offer(byte[] chunk) {
		while (!cancelled) {
			try {
				if (chunks.offer(chunk, 200, TimeUnit.MILLISECONDS)) return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}
}
