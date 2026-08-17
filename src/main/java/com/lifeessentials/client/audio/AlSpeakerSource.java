package com.lifeessentials.client.audio;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.world.phys.Vec3;

/**
 * One streaming OpenAL source, positioned in the world.
 *
 * <p>This replaces the {@code javax.sound.sampled} line the speaker used to write
 * to. That line was a second, separate audio device: it bypassed Minecraft's mixer
 * entirely, which meant panning and roll-off had to be computed by hand from the
 * player's yaw, and no other mod could ever act on the sound.
 *
 * <p>Here the source lives in Minecraft's own AL context, so OpenAL does the
 * distance attenuation and the stereo placement against the listener transform the
 * game already maintains — and mods that hook OpenAL sources, Sound Physics
 * Remastered in particular, occlude and reverberate the speaker like any other
 * sound in the world.
 *
 * <p><b>Threading.</b> OpenAL is not thread-safe and Minecraft shares one context.
 * Every method here must be called from the client thread; decoding happens
 * elsewhere and reaches this class only as finished byte arrays.
 */
final class AlSpeakerSource {
	/** 16-bit stereo, so one frame is 4 bytes — same as {@link PcmSource}. */
	private static final int BYTES_PER_FRAME = PcmSource.BYTES_PER_FRAME;
	/** Roughly two seconds of audio in flight; enough to ride out a lag spike. */
	static final int MAX_QUEUED_BUFFERS = 8;

	private final int source;
	private final ArrayDeque<Integer> queued = new ArrayDeque<>();
	private final ArrayDeque<Integer> spare = new ArrayDeque<>();

	private ByteBuffer transfer = MemoryUtil.memAlloc(1 << 16);
	private long framesRetired;
	private boolean destroyed;
	private boolean started;

	AlSpeakerSource() {
		AL10.alGetError(); // clear anything the game left behind
		source = AL10.alGenSources();
		if (AL10.alGetError() != AL10.AL_NO_ERROR) {
			// no sound device, or the source pool is exhausted — stay inert rather
			// than throwing every tick out of the render loop
			destroyed = true;
			MemoryUtil.memFree(transfer);
			transfer = null;
			return;
		}
		AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
		AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
	}

	// ------------------------------------------------------------- placement

	/**
	 * Positions the source and gives it the same attenuation curve vanilla sounds
	 * use — {@code AL_LINEAR_DISTANCE} out to the speaker's configured range, with
	 * a reference distance of zero so it starts rolling off immediately.
	 *
	 * <p>Setting {@code AL_DISTANCE_MODEL} per source requires
	 * {@code AL_SOFT_source_distance_model}, which Minecraft enables globally when
	 * it initialises its sound library.
	 */
	void place(Vec3 centre, float gain, int range) {
		if (destroyed) return;
		AL10.alSource3f(source, AL10.AL_POSITION,
				(float) centre.x, (float) centre.y, (float) centre.z);
		AL10.alSourcef(source, AL10.AL_GAIN, gain);
		AL10.alSourcei(source, AL10.AL_DISTANCE_MODEL, AL11.AL_LINEAR_DISTANCE);
		AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, range);
		AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1.0f);
		AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 0.0f);
	}

	// -------------------------------------------------------------- streaming

	/** Frees every buffer the hardware has finished with, so they can be refilled. */
	void reclaim() {
		if (destroyed) return;
		int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
		for (int i = 0; i < processed; i++) {
			int buffer = AL10.alSourceUnqueueBuffers(source);
			queued.poll();
			framesRetired += AL10.alGetBufferi(buffer, AL10.AL_SIZE) / BYTES_PER_FRAME;
			spare.add(buffer);
		}
	}

	boolean hasRoom() {
		return !destroyed && queued.size() < MAX_QUEUED_BUFFERS;
	}

	/** Uploads one chunk of PCM and queues it behind whatever is already playing. */
	void enqueue(byte[] pcm, int length) {
		if (destroyed || length <= 0) return;
		if (transfer.capacity() < length) {
			MemoryUtil.memFree(transfer);
			transfer = MemoryUtil.memAlloc(length);
		}
		transfer.clear();
		transfer.put(pcm, 0, length);
		transfer.flip();

		int buffer = spare.isEmpty() ? AL10.alGenBuffers() : spare.poll();
		AL10.alBufferData(buffer, AL10.AL_FORMAT_STEREO16, transfer, PcmSource.FRAMES_PER_SECOND);
		AL10.alSourceQueueBuffers(source, buffer);
		queued.add(buffer);
	}

	/** Starts (or restarts after an underrun) playback. */
	void play() {
		if (destroyed || queued.isEmpty()) return;
		started = true;
		if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
			AL10.alSourcePlay(source);
		}
	}

	void pause() {
		if (!destroyed && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
			AL10.alSourcePause(source);
		}
	}

	/** True once real audio has reached the hardware. */
	boolean hasOutput() {
		return started && framesRetired > 0;
	}

	/**
	 * Frames the listener has actually heard.
	 *
	 * <p>Buffers already retired, plus how far into the still-queued ones the
	 * hardware has got. Reclaiming first keeps the offset relative to a short queue.
	 */
	long playedFrames() {
		if (destroyed) return framesRetired;
		return framesRetired + Math.max(0, AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET));
	}

	void destroy() {
		if (destroyed) return;
		destroyed = true;
		AL10.alSourceStop(source);
		reclaimAllQuietly();
		AL10.alDeleteSources(source);
		MemoryUtil.memFree(transfer);
	}

	private void reclaimAllQuietly() {
		int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
		for (int i = 0; i < processed; i++) {
			spare.add(AL10.alSourceUnqueueBuffers(source));
		}
		// anything still attached goes away with the source; delete what we own
		for (Integer buffer : spare) {
			AL10.alDeleteBuffers(buffer);
		}
		for (Integer buffer : queued) {
			AL10.alDeleteBuffers(buffer);
		}
		spare.clear();
		queued.clear();
	}
}
