package com.lifeessentials.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.lifeessentials.music.Track;
import com.lifeessentials.net.MusicPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Everything this client knows about the speakers around it. */
public final class ClientSpeakers {
	/** Views older than this are dropped — the speaker is out of sync range. */
	private static final long STALE_MS = 30000;

	private static final Map<BlockPos, View> VIEWS = new ConcurrentHashMap<>();

	/** One speaker's last known state, plus when it landed on this client. */
	public record View(MusicPayloads.SpeakerSyncS2C data, long receivedAt) {

		public BlockPos pos() {
			return data.pos();
		}

		public Track track() {
			return data.track();
		}

		public boolean playing() {
			return data.playing();
		}

		public int volume() {
			return data.volume();
		}

		public int range() {
			return data.range();
		}

		public String playlistId() {
			return data.playlistId();
		}

		public String playlistName() {
			return data.playlistName();
		}

		public int trackIndex() {
			return data.trackIndex();
		}

		public int queueSize() {
			return data.queueSize();
		}

		public boolean shuffle() {
			return data.shuffle();
		}

		public int repeat() {
			return data.repeat();
		}

		/** Where the track should be right now, advancing the server's snapshot. */
		public long positionMs() {
			if (!data.playing()) return data.positionMs();
			return data.positionMs() + Math.max(0, System.currentTimeMillis() - receivedAt);
		}

		public Vec3 center() {
			return Vec3.atCenterOf(data.pos());
		}
	}

	private ClientSpeakers() {
	}

	public static void accept(MusicPayloads.SpeakerSyncS2C payload) {
		if (!payload.present()) {
			VIEWS.remove(payload.pos());
			return;
		}
		VIEWS.put(payload.pos(), new View(payload, System.currentTimeMillis()));
	}

	public static View get(BlockPos pos) {
		return VIEWS.get(pos);
	}

	public static List<View> all() {
		return new ArrayList<>(VIEWS.values());
	}

	/** The closest speaker this client has heard about, or {@code null}. */
	public static View nearest(Vec3 from, double maxDistance) {
		View best = null;
		double bestDistance = maxDistance * maxDistance;
		for (View view : VIEWS.values()) {
			double distance = view.center().distanceToSqr(from);
			if (distance <= bestDistance) {
				bestDistance = distance;
				best = view;
			}
		}
		return best;
	}

	public static void pruneStale() {
		long now = System.currentTimeMillis();
		for (Iterator<Map.Entry<BlockPos, View>> it = VIEWS.entrySet().iterator(); it.hasNext(); ) {
			if (now - it.next().getValue().receivedAt() > STALE_MS) {
				it.remove();
			}
		}
	}

	public static void reset() {
		VIEWS.clear();
	}
}
