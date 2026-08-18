package com.lifeessentials.client.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.lifeessentials.LifeEssentials;
import com.lifeessentials.music.TrackUris;
import net.minecraft.client.Minecraft;

/**
 * {@code <game dir>/lifeessentials-music} — drop your mp3s in here.
 *
 * <p>Playlists store only the file name, never an absolute path, so the same
 * playlist works for everyone: each player's own copy of the file is used. A
 * player missing the file simply hears nothing for that track.
 */
public final class MusicFolder {
	public static final String FOLDER_NAME = "lifeessentials-music";
	private static final int MAX_DEPTH = 3;
	private static final int MAX_FILES = 500;

	private MusicFolder() {
	}

	public static Path root() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve(FOLDER_NAME);
	}

	/** Creates the folder (with a short readme) if it isn't there yet. */
	public static void ensureExists() {
		try {
			Path root = root();
			if (Files.isDirectory(root)) return;
			Files.createDirectories(root);
			Files.writeString(root.resolve("README.txt"), """
					Life Essentials — music folder

					Drop audio files in here (mp3, wav, ogg, flac, m4a, opus, aac, aiff, wma)
					and they show up in the iPhone's Playlists app under "Local files".

					Playlists share only the file NAME, so every player who wants to hear a
					local track needs their own copy of that file in this folder. Use an
					http(s) link or a YouTube link instead if you want everyone covered
					automatically.

					mp3 and everything other than .wav needs ffmpeg on your PATH (or dropped
					into lifeessentials/bin). YouTube additionally needs yt-dlp.
					""");
		} catch (IOException e) {
			LifeEssentials.LOGGER.warn("Could not create the music folder", e);
		}
	}

	/** Relative names of every audio file in the folder, sorted, capped. */
	public static List<String> list() {
		ensureExists();
		Path root = root();
		List<String> names = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
			walk.filter(Files::isRegularFile)
					.map(root::relativize)
					.map(path -> path.toString().replace('\\', '/'))
					.filter(TrackUris::hasAudioExtension)
					.sorted(String.CASE_INSENSITIVE_ORDER)
					.limit(MAX_FILES)
					.forEach(names::add);
		} catch (IOException e) {
			LifeEssentials.LOGGER.warn("Could not read the music folder", e);
		}
		return names;
	}

	/**
	 * Resolves a library file name against the music folder. Returns {@code null}
	 * for anything that escapes the folder or doesn't exist — the name arrives
	 * over the network, so it is never trusted.
	 */
	public static Path resolve(String relativeName) {
		if (!TrackUris.isSafeRelativePath(relativeName)) return null;
		Path root = root().toAbsolutePath().normalize();
		Path resolved = root.resolve(relativeName).normalize();
		if (!resolved.startsWith(root)) return null;
		return Files.isRegularFile(resolved) ? resolved : null;
	}
}
