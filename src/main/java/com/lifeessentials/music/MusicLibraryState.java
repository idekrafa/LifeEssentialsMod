package com.lifeessentials.music;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The server-wide music library: every imported track plus the playlists built
 * from them. Lives next to the phone directory in the world save, so playlists
 * survive restarts and every player on the server sees the same ones.
 */
public class MusicLibraryState extends SavedData {
	/**
	 * The whole library is resent to every client whenever it changes, so these
	 * caps exist to keep that packet comfortably under the 1 MB payload limit
	 * even if every entry is maximum length.
	 */
	public static final int MAX_TRACKS = 750;
	public static final int MAX_PLAYLISTS = 60;

	public static final Factory<MusicLibraryState> FACTORY =
			new Factory<>(MusicLibraryState::new, MusicLibraryState::load, null);

	private final Map<String, Track> tracks = new LinkedHashMap<>();
	private final Map<String, Playlist> playlists = new LinkedHashMap<>();

	public static MusicLibraryState get(MinecraftServer server) {
		return server.overworld().getDataStorage()
				.computeIfAbsent(FACTORY, "lifeessentials_music");
	}

	// ---------------------------------------------------------------- tracks

	public Track track(String id) {
		return tracks.getOrDefault(id, Track.EMPTY);
	}

	public Collection<Track> tracks() {
		return tracks.values();
	}

	/**
	 * Stores a track, reusing an existing entry when the same source/uri was
	 * already imported so re-adding a song doesn't grow the library forever.
	 */
	public Track addTrack(Track incoming) {
		for (Track existing : tracks.values()) {
			if (existing.source() == incoming.source() && existing.uri().equals(incoming.uri())) {
				return existing;
			}
		}
		if (tracks.size() >= MAX_TRACKS) return Track.EMPTY;
		String id = newId();
		Track stored = new Track(id, incoming.title(), incoming.artist(), incoming.source(),
				incoming.uri(), incoming.durationSeconds());
		tracks.put(id, stored);
		setDirty();
		return stored;
	}

	/** Drops tracks no playlist references any more. */
	private void pruneOrphans() {
		if (tracks.size() < MAX_TRACKS / 2) return;
		List<String> used = new ArrayList<>();
		for (Playlist playlist : playlists.values()) {
			used.addAll(playlist.trackIds());
		}
		tracks.keySet().retainAll(used);
	}

	// ------------------------------------------------------------- playlists

	public Playlist playlist(String id) {
		return playlists.get(id);
	}

	public Collection<Playlist> playlists() {
		return playlists.values();
	}

	public Playlist createPlaylist(String name, String ownerName) {
		if (playlists.size() >= MAX_PLAYLISTS) return null;
		Playlist playlist = new Playlist(newId(), name, ownerName, List.of());
		playlists.put(playlist.id(), playlist);
		setDirty();
		return playlist;
	}

	public void deletePlaylist(String id) {
		if (playlists.remove(id) != null) {
			pruneOrphans();
			setDirty();
		}
	}

	public void renamePlaylist(String id, String name) {
		Playlist playlist = playlists.get(id);
		if (playlist != null) {
			playlist.rename(name);
			setDirty();
		}
	}

	/** Imports the tracks and appends them to the playlist. Returns how many landed. */
	public int addToPlaylist(String playlistId, List<Track> incoming) {
		Playlist playlist = playlists.get(playlistId);
		if (playlist == null) return 0;
		int added = 0;
		for (Track candidate : incoming) {
			// incoming tracks have no id yet — that's assigned here, in addTrack
			if (candidate.uri().isBlank()) continue;
			Track stored = addTrack(candidate);
			if (!stored.isEmpty() && playlist.add(stored.id())) {
				added++;
			}
		}
		if (added > 0) setDirty();
		return added;
	}

	public void removeFromPlaylist(String playlistId, int index) {
		Playlist playlist = playlists.get(playlistId);
		if (playlist == null) return;
		playlist.remove(index);
		setDirty();
	}

	public void movePlaylistTrack(String playlistId, int index, int delta) {
		Playlist playlist = playlists.get(playlistId);
		if (playlist == null) return;
		playlist.move(index, delta);
		setDirty();
	}

	/** Resolved tracks of a playlist, skipping ids whose track went missing. */
	public List<Track> resolve(String playlistId) {
		Playlist playlist = playlists.get(playlistId);
		if (playlist == null) return List.of();
		List<Track> resolved = new ArrayList<>(playlist.size());
		for (String trackId : playlist.trackIds()) {
			Track track = tracks.get(trackId);
			if (track != null) resolved.add(track);
		}
		return resolved;
	}

	private static String newId() {
		return UUID.randomUUID().toString().substring(0, 12);
	}

	// ------------------------------------------------------ sync / serialise

	/** The whole library in one tag — small enough to just resend on change. */
	public CompoundTag toSyncTag() {
		CompoundTag tag = new CompoundTag();
		ListTag trackList = new ListTag();
		for (Track track : tracks.values()) {
			trackList.add(track.toNbt());
		}
		tag.put("tracks", trackList);
		ListTag playlistList = new ListTag();
		for (Playlist playlist : playlists.values()) {
			playlistList.add(playlist.toNbt());
		}
		tag.put("playlists", playlistList);
		return tag;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		tag.merge(toSyncTag());
		return tag;
	}

	public static MusicLibraryState load(CompoundTag tag, HolderLookup.Provider registries) {
		MusicLibraryState state = new MusicLibraryState();
		ListTag trackList = tag.getList("tracks", Tag.TAG_COMPOUND);
		for (int i = 0; i < trackList.size(); i++) {
			Track track = Track.fromNbt(trackList.getCompound(i));
			if (!track.isEmpty()) state.tracks.put(track.id(), track);
		}
		ListTag playlistList = tag.getList("playlists", Tag.TAG_COMPOUND);
		for (int i = 0; i < playlistList.size(); i++) {
			Playlist playlist = Playlist.fromNbt(playlistList.getCompound(i));
			if (!playlist.id().isEmpty()) state.playlists.put(playlist.id(), playlist);
		}
		return state;
	}
}
