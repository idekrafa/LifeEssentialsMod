package com.lifeessentials.client.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.lifeessentials.LifeEssentials;
import com.lifeessentials.music.Track;
import com.lifeessentials.music.TrackSource;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

/**
 * Metadata lookups for the import screen.
 *
 * <p>The same resolve that playback uses, so a title, artist and duration cost one
 * request and no external tool. Before 2.0 this was a separate {@code yt-dlp -j}
 * subprocess, which meant importing a YouTube link required yt-dlp even for people
 * who only ever wanted to see the track in a playlist.
 *
 * <p>Knowing the duration matters beyond the UI: when {@code Track#durationSeconds}
 * is set the speaker's block entity advances the queue on its own clock instead of
 * waiting for a listener to report the end of the track.
 */
final class LavaLookup {
	/** Import is interactive — fail visibly rather than hanging the screen. */
	private static final long TIMEOUT_SECONDS = 25;

	private LavaLookup() {
	}

	/**
	 * Resolves one identifier into library tracks, or an empty list on any failure.
	 *
	 * <p>A playlist with a <em>selected</em> track is treated as that one song:
	 * sharing from YouTube Music appends a radio playlist to the link, and the
	 * player meant the song they shared, not a hundred of its neighbours.
	 */
	static List<Track> resolve(String identifier, TrackSource source, int limit) {
		AudioPlayerManager manager = LavaEngine.manager();
		if (manager == null) return List.of();

		CompletableFuture<List<AudioTrack>> result = new CompletableFuture<>();
		manager.loadItem(identifier, new AudioLoadResultHandler() {
			@Override
			public void trackLoaded(AudioTrack track) {
				result.complete(List.of(track));
			}

			@Override
			public void playlistLoaded(AudioPlaylist playlist) {
				if (playlist.getSelectedTrack() != null) {
					result.complete(List.of(playlist.getSelectedTrack()));
					return;
				}
				List<AudioTrack> tracks = playlist.getTracks();
				result.complete(tracks.size() > limit ? tracks.subList(0, limit) : tracks);
			}

			@Override
			public void noMatches() {
				result.complete(List.of());
			}

			@Override
			public void loadFailed(FriendlyException e) {
				result.complete(List.of());
			}
		});

		try {
			List<Track> tracks = new ArrayList<>();
			for (AudioTrack track : result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				tracks.add(toTrack(track, source));
			}
			return tracks;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return List.of();
		} catch (Exception e) {
			LifeEssentials.LOGGER.debug("Import lookup failed for {}", identifier, e);
			return List.of();
		}
	}

	/** Duration of one identifier in seconds, or 0 when it can't be read. */
	static int durationSeconds(String identifier, TrackSource source) {
		List<Track> tracks = resolve(identifier, source, 1);
		return tracks.isEmpty() ? 0 : tracks.get(0).durationSeconds();
	}

	private static Track toTrack(AudioTrack track, TrackSource source) {
		AudioTrackInfo info = track.getInfo();
		// a live stream reports a nonsense length; leave it unknown so the speaker
		// waits for a listener to report the end instead of skipping on a timer
		int seconds = info.isStream ? 0 : (int) (track.getDuration() / 1000);
		String uri = source == TrackSource.YOUTUBE ? info.identifier : info.uri;
		return new Track("", orBlank(info.title), orBlank(info.author), source,
				uri == null ? info.identifier : uri, seconds);
	}

	private static String orBlank(String text) {
		return text == null ? "" : text;
	}
}
