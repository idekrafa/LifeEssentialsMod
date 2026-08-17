package com.lifeessentials.client.music;

import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.Minecraft;

/** Controls a macOS media app (Spotify / Music) via AppleScript. */
public abstract class MacMediaController implements MediaController {
	protected volatile boolean running = false;
	protected volatile NowPlaying nowPlaying = NowPlaying.NONE;
	private final AtomicInteger pendingVolume = new AtomicInteger(-1);

	protected abstract String appName();

	protected abstract String processName();

	protected abstract String stateScript();

	protected abstract NowPlaying parseState(String output);

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
		if (!AppleScriptRunner.isMac() || !isInstalled()) {
			running = false;
			nowPlaying = NowPlaying.NONE;
			if (onUpdated != null) onUpdated.run();
			return;
		}
		AppleScriptRunner.submit(() -> {
			boolean nowRunning = AppleScriptRunner.processRunning(processName());
			NowPlaying result = NowPlaying.NONE;
			if (nowRunning) {
				String out = AppleScriptRunner.runBlocking(stateScript());
				if (out != null && !out.isEmpty() && !out.startsWith("stopped")) {
					result = parseState(out);
				}
			}
			running = nowRunning;
			nowPlaying = result;
			if (onUpdated != null) {
				Minecraft.getInstance().execute(onUpdated);
			}
		});
	}

	protected void tell(String command) {
		AppleScriptRunner.fireAndForget("tell application \"" + appName() + "\" to " + command);
	}

	@Override
	public void playPause() {
		// optimistic flip so the UI feels instant; the next poll corrects it
		NowPlaying np = nowPlaying;
		if (!np.isEmpty()) {
			nowPlaying = new NowPlaying(np.service(), np.trackId(), np.title(), np.artist(),
					!np.playing(), np.position(), np.duration(), np.volume());
		}
		tell("playpause");
		refreshSoon(400);
	}

	@Override
	public void next() {
		tell("next track");
		refreshSoon(400);
	}

	@Override
	public void previous() {
		tell("previous track");
		refreshSoon(400);
	}

	protected void refreshSoon(long delayMillis) {
		AppleScriptRunner.submit(() -> {
			try {
				Thread.sleep(delayMillis);
			} catch (InterruptedException ignored) {
			}
		});
		refreshStatus(null); // queued behind the sleep on the same single-thread executor
	}

	@Override
	public void setVolume(int volume) {
		// coalesce slider drags: only the latest pending value is sent
		if (pendingVolume.getAndSet(volume) == -1) {
			AppleScriptRunner.submit(() -> {
				int v = pendingVolume.getAndSet(-1);
				if (v >= 0) {
					AppleScriptRunner.runBlocking(
							"tell application \"" + appName() + "\" to set sound volume to " + v);
				}
			});
		}
	}

	@Override
	public void openApp() {
		try {
			new ProcessBuilder("open", "-a", appName()).start();
		} catch (Exception ignored) {
		}
		refreshSoon(2000);
	}
}
