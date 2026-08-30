package com.lifeessentials.music;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** A named, ordered list of {@link Track} ids. Shared by everyone on the server. */
public final class Playlist {
	public static final int MAX_TRACKS = 500;
	public static final int MAX_NAME = 40;

	private final String id;
	private String name;
	private final String ownerName;
	private final List<String> trackIds;

	public Playlist(String id, String name, String ownerName, List<String> trackIds) {
		this.id = id;
		this.name = clipName(name);
		this.ownerName = ownerName == null ? "" : ownerName;
		this.trackIds = new ArrayList<>(trackIds);
	}

	public static String clipName(String name) {
		String trimmed = name == null ? "" : name.strip();
		if (trimmed.isEmpty()) trimmed = "New Playlist";
		return trimmed.length() > MAX_NAME ? trimmed.substring(0, MAX_NAME) : trimmed;
	}

	public String id() {
		return id;
	}

	public String name() {
		return name;
	}

	public void rename(String newName) {
		this.name = clipName(newName);
	}

	public String ownerName() {
		return ownerName;
	}

	/** Live list — mutate through the helpers below so the size cap holds. */
	public List<String> trackIds() {
		return trackIds;
	}

	public int size() {
		return trackIds.size();
	}

	public boolean add(String trackId) {
		if (trackIds.size() >= MAX_TRACKS) return false;
		return trackIds.add(trackId);
	}

	public void remove(int index) {
		if (index >= 0 && index < trackIds.size()) {
			trackIds.remove(index);
		}
	}

	public void removeAll(String trackId) {
		trackIds.removeIf(trackId::equals);
	}

	/** Swaps the entry at {@code index} with its neighbour; no-op at the ends. */
	public void move(int index, int delta) {
		int target = index + delta;
		if (index < 0 || index >= trackIds.size() || target < 0 || target >= trackIds.size()) return;
		trackIds.set(index, trackIds.set(target, trackIds.get(index)));
	}

	// ------------------------------------------------------------------ nbt

	public CompoundTag toNbt() {
		CompoundTag tag = new CompoundTag();
		tag.putString("id", id);
		tag.putString("name", name);
		tag.putString("owner", ownerName);
		ListTag list = new ListTag();
		for (String trackId : trackIds) {
			list.add(StringTag.valueOf(trackId));
		}
		tag.put("tracks", list);
		return tag;
	}

	public static Playlist fromNbt(CompoundTag tag) {
		List<String> ids = new ArrayList<>();
		ListTag list = tag.getList("tracks", Tag.TAG_STRING);
		for (int i = 0; i < list.size(); i++) {
			ids.add(list.getString(i));
		}
		return new Playlist(tag.getString("id"), tag.getString("name"), tag.getString("owner"), ids);
	}
}
