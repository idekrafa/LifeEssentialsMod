package com.lifeessentials.music;

import java.util.Locale;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One entry in the shared music library.
 *
 * <p>{@code uri} means something different per {@link TrackSource}: a file name
 * relative to the music folder, a full http(s) url, a bare YouTube video id, or
 * a {@code spotify:track:…} uri.
 */
public record Track(String id, String title, String artist, TrackSource source, String uri,
		int durationSeconds) {

	public static final Track EMPTY = new Track("", "", "", TrackSource.URL, "", 0);

	public static final int MAX_TEXT = 120;

	public static final StreamCodec<RegistryFriendlyByteBuf, Track> STREAM_CODEC =
			StreamCodec.of(Track::write, Track::read);

	public Track {
		title = clip(title);
		artist = clip(artist);
		durationSeconds = Math.max(0, durationSeconds);
	}

	private static String clip(String text) {
		if (text == null) return "";
		return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
	}

	public boolean isEmpty() {
		return id.isEmpty() || uri.isEmpty();
	}

	public String displayTitle() {
		return title.isBlank() ? uri : title;
	}

	/** {@code 3:07}, or an empty string when the length isn't known. */
	public String clock() {
		if (durationSeconds <= 0) return "";
		return (durationSeconds / 60) + ":" + String.format(Locale.ROOT, "%02d", durationSeconds % 60);
	}

	/** True when playing this needs a decoder rather than the desktop Spotify app. */
	public boolean isStreamable() {
		return source != TrackSource.SPOTIFY;
	}

	// ------------------------------------------------------------------ nbt

	public CompoundTag toNbt() {
		CompoundTag tag = new CompoundTag();
		tag.putString("id", id);
		tag.putString("title", title);
		tag.putString("artist", artist);
		tag.putString("source", source.name());
		tag.putString("uri", uri);
		tag.putInt("len", durationSeconds);
		return tag;
	}

	public static Track fromNbt(CompoundTag tag) {
		return new Track(tag.getString("id"), tag.getString("title"), tag.getString("artist"),
				TrackSource.byName(tag.getString("source")), tag.getString("uri"), tag.getInt("len"));
	}

	// -------------------------------------------------------------- network

	private static void write(RegistryFriendlyByteBuf buf, Track track) {
		ByteBufCodecs.STRING_UTF8.encode(buf, track.id);
		ByteBufCodecs.STRING_UTF8.encode(buf, track.title);
		ByteBufCodecs.STRING_UTF8.encode(buf, track.artist);
		buf.writeVarInt(track.source.ordinal());
		ByteBufCodecs.STRING_UTF8.encode(buf, track.uri);
		buf.writeVarInt(track.durationSeconds);
	}

	private static Track read(RegistryFriendlyByteBuf buf) {
		String id = ByteBufCodecs.STRING_UTF8.decode(buf);
		String title = ByteBufCodecs.STRING_UTF8.decode(buf);
		String artist = ByteBufCodecs.STRING_UTF8.decode(buf);
		int ordinal = buf.readVarInt();
		TrackSource[] sources = TrackSource.values();
		TrackSource source = ordinal >= 0 && ordinal < sources.length ? sources[ordinal] : TrackSource.URL;
		String uri = ByteBufCodecs.STRING_UTF8.decode(buf);
		int length = buf.readVarInt();
		return new Track(id, title, artist, source, uri, length);
	}
}
