package com.lifeessentials.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lifeessentials.music.Playlist;
import com.lifeessentials.music.Track;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Client-side mirror of the server's music library. */
public final class ClientMusicLibrary {
	private static final Map<String, Track> TRACKS = new LinkedHashMap<>();
	private static final Map<String, Playlist> PLAYLISTS = new LinkedHashMap<>();
	private static volatile boolean loaded = false;

	private ClientMusicLibrary() {
	}

	public static boolean isLoaded() {
		return loaded;
	}

	public static synchronized void load(CompoundTag tag) {
		TRACKS.clear();
		PLAYLISTS.clear();
		ListTag trackList = tag.getList("tracks", Tag.TAG_COMPOUND);
		for (int i = 0; i < trackList.size(); i++) {
			Track track = Track.fromNbt(trackList.getCompound(i));
			if (!track.isEmpty()) TRACKS.put(track.id(), track);
		}
		ListTag playlistList = tag.getList("playlists", Tag.TAG_COMPOUND);
		for (int i = 0; i < playlistList.size(); i++) {
			Playlist playlist = Playlist.fromNbt(playlistList.getCompound(i));
			if (!playlist.id().isEmpty()) PLAYLISTS.put(playlist.id(), playlist);
		}
		loaded = true;
	}

	public static synchronized List<Playlist> playlists() {
		return new ArrayList<>(PLAYLISTS.values());
	}

	public static synchronized Playlist playlist(String id) {
		return PLAYLISTS.get(id);
	}

	public static synchronized Track track(String id) {
		return TRACKS.getOrDefault(id, Track.EMPTY);
	}

	public static synchronized List<Track> resolve(String playlistId) {
		Playlist playlist = PLAYLISTS.get(playlistId);
		if (playlist == null) return List.of();
		List<Track> tracks = new ArrayList<>(playlist.size());
		for (String trackId : playlist.trackIds()) {
			Track track = TRACKS.get(trackId);
			if (track != null) tracks.add(track);
		}
		return tracks;
	}

	public static synchronized void reset() {
		TRACKS.clear();
		PLAYLISTS.clear();
		loaded = false;
	}
}
