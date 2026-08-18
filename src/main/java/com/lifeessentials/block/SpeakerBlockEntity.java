package com.lifeessentials.block;

import java.util.List;

import com.lifeessentials.ModBlocks;
import com.lifeessentials.music.MusicLibraryState;
import com.lifeessentials.music.Playlist;
import com.lifeessentials.music.Track;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Playback state of one JBL speaker. The block entity is the single source of
 * truth: it owns the queue position and a wall-clock anchor, and every client in
 * range decodes the same track locally from the same offset.
 */
public class SpeakerBlockEntity extends BlockEntity {
	public static final int MIN_RANGE = 8;
	public static final int MAX_RANGE = 64;
	/** Ignore repeated "track ended" reports from other clients for this long. */
	private static final long ADVANCE_DEBOUNCE_MS = 3000;
	/** A client report can't cut a track shorter than this — guards against noise. */
	private static final long MIN_TRACK_MS = 1500;
	/** Give up on a track of unknown length nobody can decode after this long. */
	private static final long STUCK_TRACK_TIMEOUT_MS = 30 * 60 * 1000L;

	public enum RepeatMode {
		OFF("Repeat: Off"), ALL("Repeat: All"), ONE("Repeat: One");

		private final String label;

		RepeatMode(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		public RepeatMode next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	private String playlistId = "";
	private int trackIndex = 0;
	private boolean playing = false;
	/** Offset inside the current track that the current segment started at. */
	private long anchorMs = 0;
	/** {@code System.currentTimeMillis()} when the current segment started. */
	private long anchorWallClock = 0;
	private int volume = 80;
	private int range = 32;
	private boolean shuffle = false;
	private RepeatMode repeat = RepeatMode.ALL;

	private boolean powered = false;

	private long lastAdvanceMs = 0;
	private int syncTimer = 0;

	public SpeakerBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlocks.SPEAKER_BE.get(), pos, state);
	}

	// ------------------------------------------------------------- accessors

	public String playlistId() {
		return playlistId;
	}

	public int trackIndex() {
		return trackIndex;
	}

	public boolean isPlaying() {
		return playing;
	}

	public int volume() {
		return volume;
	}

	public int range() {
		return range;
	}

	public boolean shuffle() {
		return shuffle;
	}

	public RepeatMode repeat() {
		return repeat;
	}

	/** Milliseconds into the current track right now. */
	public long positionMs() {
		if (!playing) return anchorMs;
		return anchorMs + Math.max(0, System.currentTimeMillis() - anchorWallClock);
	}

	private MusicLibraryState library() {
		MinecraftServer server = level == null ? null : level.getServer();
		return server == null ? null : MusicLibraryState.get(server);
	}

	public List<Track> queue() {
		MusicLibraryState library = library();
		return library == null ? List.of() : library.resolve(playlistId);
	}

	public String playlistName() {
		MusicLibraryState library = library();
		if (library == null) return "";
		Playlist playlist = library.playlist(playlistId);
		return playlist == null ? "" : playlist.name();
	}

	public Track currentTrack() {
		List<Track> queue = queue();
		if (queue.isEmpty()) return Track.EMPTY;
		int index = Math.floorMod(trackIndex, queue.size());
		return queue.get(index);
	}

	// --------------------------------------------------------------- control

	public void setPlaylist(String id, int startIndex) {
		this.playlistId = id == null ? "" : id;
		this.trackIndex = Math.max(0, startIndex);
		seekTo(0);
		setPlaying(!queue().isEmpty());
	}

	public void togglePlay() {
		if (playing) {
			pause();
		} else if (!queue().isEmpty()) {
			resume();
		}
	}

	public void resume() {
		if (playing) return;
		anchorWallClock = System.currentTimeMillis();
		setPlaying(true);
	}

	public void pause() {
		if (!playing) return;
		anchorMs = positionMs();
		setPlaying(false);
	}

	public void stop() {
		seekTo(0);
		setPlaying(false);
	}

	public void seekTo(long ms) {
		anchorMs = Math.max(0, ms);
		anchorWallClock = System.currentTimeMillis();
		markChanged();
	}

	/** Manual skip — always moves, even with {@link RepeatMode#ONE}. */
	public void skip(int delta) {
		List<Track> queue = queue();
		if (queue.isEmpty()) return;
		trackIndex = pickNext(queue.size(), delta, true);
		seekTo(0);
		if (!playing) resume();
	}

	/** The current track ran out. Honours repeat/shuffle and may stop the speaker. */
	private void advanceAutomatically() {
		List<Track> queue = queue();
		if (queue.isEmpty()) {
			stop();
			return;
		}
		if (repeat == RepeatMode.ONE) {
			seekTo(0);
			return;
		}
		int last = queue.size() - 1;
		if (repeat == RepeatMode.OFF && !shuffle && trackIndex >= last) {
			trackIndex = 0;
			stop();
			return;
		}
		trackIndex = pickNext(queue.size(), 1, false);
		seekTo(0);
	}

	private int pickNext(int size, int delta, boolean manual) {
		if (shuffle && size > 1) {
			int pick;
			do {
				pick = level == null ? 0 : level.getRandom().nextInt(size);
			} while (pick == Math.floorMod(trackIndex, size));
			return pick;
		}
		int next = Math.floorMod(trackIndex, size) + delta;
		if (manual || repeat != RepeatMode.OFF) {
			return Math.floorMod(next, size);
		}
		return Math.max(0, Math.min(size - 1, next));
	}

	/**
	 * A client reported the current track finished. Debounced, because every
	 * listener in range reports it at roughly the same moment.
	 */
	public void reportTrackEnded(int reportedIndex) {
		List<Track> queue = queue();
		if (queue.isEmpty()) return;
		if (Math.floorMod(reportedIndex, queue.size()) != Math.floorMod(trackIndex, queue.size())) return;
		long now = System.currentTimeMillis();
		if (now - lastAdvanceMs < ADVANCE_DEBOUNCE_MS) return;
		// don't let a mis-reporting client cut a track short
		if (positionMs() < MIN_TRACK_MS) return;
		lastAdvanceMs = now;
		advanceAutomatically();
	}

	public void setVolume(int newVolume) {
		volume = Math.max(0, Math.min(100, newVolume));
		markChanged();
	}

	public void setRange(int newRange) {
		range = Math.max(MIN_RANGE, Math.min(MAX_RANGE, newRange));
		markChanged();
	}

	public void toggleShuffle() {
		shuffle = !shuffle;
		markChanged();
	}

	/** A rising redstone edge works like tapping play/pause on the speaker itself. */
	public void onRedstoneChanged(boolean nowPowered) {
		if (nowPowered == powered) return;
		powered = nowPowered;
		setChanged();
		if (nowPowered) {
			togglePlay();
			if (level instanceof ServerLevel serverLevel) {
				ServerSpeakerManager.syncToNearby(serverLevel, this);
			}
		}
	}

	public void cycleRepeat() {
		repeat = repeat.next();
		markChanged();
	}

	private void setPlaying(boolean nowPlaying) {
		boolean changed = playing != nowPlaying;
		playing = nowPlaying;
		if (changed && level != null && !level.isClientSide) {
			BlockState state = getBlockState();
			if (state.hasProperty(JblSpeakerBlock.PLAYING)) {
				level.setBlock(worldPosition, state.setValue(JblSpeakerBlock.PLAYING, playing), 3);
			}
			level.updateNeighbourForOutputSignal(worldPosition, state.getBlock());
		}
		markChanged();
	}

	private void markChanged() {
		setChanged();
		syncTimer = 0; // push a fresh sync on the next tick
	}

	/**
	 * Advances the queue when the current track's clock runs out. Tracks whose
	 * length we never learned rely on the listeners' decoders reporting the end;
	 * the long stop is a backstop for when nobody can play the track at all.
	 */
	private void checkTrackClock() {
		Track track = currentTrack();
		if (track.isEmpty()) {
			stop();
			return;
		}
		long position = positionMs();
		if (track.durationSeconds() > 0) {
			if (position >= track.durationSeconds() * 1000L) {
				lastAdvanceMs = System.currentTimeMillis();
				advanceAutomatically();
			}
		} else if (position > STUCK_TRACK_TIMEOUT_MS) {
			// nothing in range could decode it — don't sit on it forever
			lastAdvanceMs = System.currentTimeMillis();
			advanceAutomatically();
		}
	}

	// ------------------------------------------------------------------ tick

	public static void serverTick(Level level, BlockPos pos, BlockState state, SpeakerBlockEntity be) {
		if (!(level instanceof ServerLevel serverLevel)) return;

		if (be.playing) {
			if (level.getGameTime() % 8 == 0) {
				serverLevel.sendParticles(ParticleTypes.NOTE,
						pos.getX() + 0.5 + (level.random.nextDouble() - 0.5) * 0.8,
						pos.getY() + 0.9 + level.random.nextDouble() * 0.3,
						pos.getZ() + 0.5 + (level.random.nextDouble() - 0.5) * 0.8,
						1, 0.0, 0.0, 0.0, 1.0);
			}
			// resolving the queue allocates, so only check the clock twice a second
			if (level.getGameTime() % 10 == 0) {
				be.checkTrackClock();
			}
		}

		if (--be.syncTimer <= 0) {
			be.syncTimer = be.playing ? 100 : 200; // 5s while playing, 10s idle
			ServerSpeakerManager.syncToNearby(serverLevel, be);
		}
	}

	// ------------------------------------------------------------------- nbt

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		playlistId = tag.getString("playlist");
		trackIndex = tag.getInt("index");
		playing = tag.getBoolean("playing");
		anchorMs = tag.getLong("anchor");
		anchorWallClock = System.currentTimeMillis();
		volume = tag.contains("volume") ? tag.getInt("volume") : 80;
		range = tag.contains("range") ? tag.getInt("range") : 32;
		shuffle = tag.getBoolean("shuffle");
		powered = tag.getBoolean("powered");
		int repeatOrdinal = tag.getInt("repeat");
		RepeatMode[] modes = RepeatMode.values();
		repeat = repeatOrdinal >= 0 && repeatOrdinal < modes.length ? modes[repeatOrdinal] : RepeatMode.ALL;
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putString("playlist", playlistId);
		tag.putInt("index", trackIndex);
		tag.putBoolean("playing", playing);
		tag.putLong("anchor", positionMs());
		tag.putInt("volume", volume);
		tag.putInt("range", range);
		tag.putBoolean("shuffle", shuffle);
		tag.putBoolean("powered", powered);
		tag.putInt("repeat", repeat.ordinal());
	}
}
