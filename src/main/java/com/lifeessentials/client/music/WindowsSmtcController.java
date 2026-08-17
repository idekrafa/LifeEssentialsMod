package com.lifeessentials.client.music;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.Minecraft;

/**
 * Windows media control through the System Media Transport Controls (the API
 * behind Windows' volume/media overlay), driven from PowerShell. Works with
 * the Spotify desktop app, Apple Music for Windows, and iTunes — no account
 * setup needed.
 *
 * Limitations of SMTC: it exposes no track ids (speaker broadcasts from
 * Windows carry the song name but can't drive Listen Along on other clients)
 * and no per-app volume (the phone's slider still ducks game audio).
 */
public class WindowsSmtcController implements MediaController {
	private final String serviceId;
	private final String displayName;
	/** regex matched against the media session's SourceAppUserModelId */
	private final String sessionMatch;
	private final List<String> processNames;
	private final List<Path> installPaths;
	private final String openUri;
	private final boolean supportsPlayTrack;

	protected volatile boolean running = false;
	protected volatile NowPlaying nowPlaying = NowPlaying.NONE;
	private final AtomicBoolean refreshQueued = new AtomicBoolean(false);

	public static WindowsSmtcController spotify() {
		String appData = System.getenv("APPDATA");
		return new WindowsSmtcController("spotify", "Spotify", "Spotify",
				List.of("Spotify.exe"),
				appData == null ? List.of() : List.of(Path.of(appData, "Spotify", "Spotify.exe")),
				"spotify:", true);
	}

	public static WindowsSmtcController appleMusic() {
		String programFiles = System.getenv("ProgramFiles");
		return new WindowsSmtcController("apple_music", "Apple Music", "AppleMusic|AppleInc|iTunes",
				List.of("AppleMusic.exe", "iTunes.exe"),
				programFiles == null ? List.of()
						: List.of(Path.of(programFiles, "iTunes", "iTunes.exe")),
				"applemusic:", false);
	}

	private WindowsSmtcController(String serviceId, String displayName, String sessionMatch,
			List<String> processNames, List<Path> installPaths, String openUri,
			boolean supportsPlayTrack) {
		this.serviceId = serviceId;
		this.displayName = displayName;
		this.sessionMatch = sessionMatch;
		this.processNames = processNames;
		this.installPaths = installPaths;
		this.openUri = openUri;
		this.supportsPlayTrack = supportsPlayTrack;
	}

	@Override
	public String serviceId() {
		return serviceId;
	}

	@Override
	public String displayName() {
		return displayName;
	}

	@Override
	public boolean isInstalled() {
		// Store-app installs live in inaccessible WindowsApps folders, so a
		// running process counts as "installed" too.
		if (running) return true;
		for (Path path : installPaths) {
			if (Files.exists(path)) return true;
		}
		return false;
	}

	@Override
	public String unavailableReason() {
		return "Open " + displayName + " once so the phone can find it";
	}

	@Override
	public boolean supportsAppVolume() {
		return false;
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public NowPlaying nowPlaying() {
		return nowPlaying;
	}

	@Override
	public void refreshStatus(Runnable onUpdated) {
		if (!PowerShellRunner.isWindows()) {
			running = false;
			nowPlaying = NowPlaying.NONE;
			if (onUpdated != null) onUpdated.run();
			return;
		}
		if (!refreshQueued.compareAndSet(false, true)) return; // a poll is already pending
		PowerShellRunner.submit(() -> {
			try {
				boolean nowRunning = false;
				for (String name : processNames) {
					if (PowerShellRunner.processRunning(name)) {
						nowRunning = true;
						break;
					}
				}
				NowPlaying result = NowPlaying.NONE;
				if (nowRunning) {
					String out = PowerShellRunner.runBlocking(smtcScript(STATUS_BODY));
					if (out != null && !out.isEmpty() && !out.startsWith("stopped")) {
						result = parseState(out);
					}
				}
				running = nowRunning;
				nowPlaying = result;
			} finally {
				refreshQueued.set(false);
			}
			if (onUpdated != null) {
				Minecraft.getInstance().execute(onUpdated);
			}
		});
	}

	private NowPlaying parseState(String out) {
		String[] parts = out.split("\\|~\\|");
		if (parts.length < 7) return NowPlaying.NONE;
		boolean playing = parts[0].trim().equalsIgnoreCase("playing");
		String title = parts[1].trim();
		String artist = parts[2].trim();
		// SMTC has no track ids; synthesize one for change detection only
		String trackId = title.isEmpty() ? "" : "smtc:" + title + "|" + artist;
		return new NowPlaying(serviceId, trackId, title, artist, playing,
				SpotifyController.parseNumber(parts[5]), SpotifyController.parseNumber(parts[6]), 0);
	}

	@Override
	public void playPause() {
		NowPlaying np = nowPlaying;
		if (!np.isEmpty()) {
			nowPlaying = new NowPlaying(np.service(), np.trackId(), np.title(), np.artist(),
					!np.playing(), np.position(), np.duration(), np.volume());
		}
		command("TryTogglePlayPauseAsync");
	}

	@Override
	public void next() {
		command("TrySkipNextAsync");
	}

	@Override
	public void previous() {
		command("TrySkipPreviousAsync");
	}

	private void command(String method) {
		PowerShellRunner.fireAndForget(smtcScript(
				"$null = Await ($session." + method + "()) ([System.Boolean])"));
		refreshSoon(600);
	}

	private void refreshSoon(long delayMillis) {
		PowerShellRunner.submit(() -> {
			try {
				Thread.sleep(delayMillis);
			} catch (InterruptedException ignored) {
			}
		});
		refreshStatus(null); // queued behind the sleep on the same single-thread executor
	}

	@Override
	public void setVolume(int volume) {
		// SMTC exposes no per-app volume; the slider still ducks game audio.
	}

	@Override
	public void openApp() {
		PowerShellRunner.exec("cmd", "/c", "start", "", openUri);
		refreshSoon(2500);
	}

	@Override
	public void playTrack(String trackId) {
		if (!supportsPlayTrack || trackId == null || trackId.isEmpty()) return;
		String uri = trackId.replace("\"", "").replace("'", "");
		// the desktop exe takes --uri without stealing focus; fall back to the URI handler
		for (Path path : installPaths) {
			if (Files.exists(path)) {
				PowerShellRunner.exec(path.toString(), "--uri", uri);
				refreshSoon(800);
				return;
			}
		}
		PowerShellRunner.exec("cmd", "/c", "start", "", uri);
		refreshSoon(800);
	}

	// ------------------------------------------------------------ scripts

	private static final String STATUS_BODY = """
			$props = Await ($session.GetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
			$play = $session.GetPlaybackInfo().PlaybackStatus
			$tl = $session.GetTimelineProperties()
			$pos = [int]$tl.Position.TotalSeconds
			$dur = [int]$tl.EndTime.TotalSeconds
			$state = if ("$play" -eq 'Playing') { 'playing' } else { 'paused' }
			Write-Output ($state + '|~|' + $props.Title + '|~|' + $props.Artist + '|~|' + '' + '|~|' + '0' + '|~|' + $pos + '|~|' + $dur)""";

	/** Wraps a body with the WinRT await prelude and the session lookup. */
	private String smtcScript(String body) {
		return """
				try {
				Add-Type -AssemblyName System.Runtime.WindowsRuntime
				$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
				function Await($WinRtTask, $ResultType) {
					$asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
					$netTask = $asTask.Invoke($null, @($WinRtTask))
					$null = $netTask.Wait(5000)
					$netTask.Result
				}
				$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
				$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
				$session = $mgr.GetSessions() | Where-Object { $_.SourceAppUserModelId -match '"""
				+ sessionMatch + """
				' } | Select-Object -First 1
				if (-not $session) { Write-Output 'stopped'; exit }
				"""
				+ body + """

				} catch { Write-Output 'stopped' }""";
	}
}
