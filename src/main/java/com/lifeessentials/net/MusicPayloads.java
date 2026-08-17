package com.lifeessentials.net;

import java.util.ArrayList;
import java.util.List;

import com.lifeessentials.LifeEssentials;
import com.lifeessentials.block.ServerSpeakerManager;
import com.lifeessentials.client.ClientMusicPayloadHandler;
import com.lifeessentials.music.Track;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Packets for the music library, playlists and the JBL speaker. */
public final class MusicPayloads {
	/** Hard cap on one import so a huge YouTube playlist can't blow the packet up. */
	public static final int MAX_IMPORT = 100;

	private MusicPayloads() {
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(LifeEssentials.MOD_ID, path);
	}

	// ------------------------------------------------------- client -> server

	/** "Send me the whole library" — answered with {@link LibrarySyncS2C}. */
	public record LibraryRequestC2S() implements CustomPacketPayload {
		public static final LibraryRequestC2S INSTANCE = new LibraryRequestC2S();
		public static final Type<LibraryRequestC2S> TYPE = new Type<>(id("library_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LibraryRequestC2S> CODEC =
				StreamCodec.unit(INSTANCE);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Playlist bookkeeping. {@code action} is one of {@code create}, {@code delete},
	 * {@code rename}, {@code remove_track}, {@code move_track}.
	 */
	public record LibraryCommandC2S(String action, String playlistId, String text, int index)
			implements CustomPacketPayload {
		public static final Type<LibraryCommandC2S> TYPE = new Type<>(id("library_command"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LibraryCommandC2S> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, LibraryCommandC2S::action,
						ByteBufCodecs.STRING_UTF8, LibraryCommandC2S::playlistId,
						ByteBufCodecs.STRING_UTF8, LibraryCommandC2S::text,
						ByteBufCodecs.VAR_INT, LibraryCommandC2S::index,
						LibraryCommandC2S::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Tracks the importing client already resolved (title/artist/length filled in). */
	public record LibraryAddTracksC2S(String playlistId, List<Track> tracks)
			implements CustomPacketPayload {
		public static final Type<LibraryAddTracksC2S> TYPE = new Type<>(id("library_add_tracks"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LibraryAddTracksC2S> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, LibraryAddTracksC2S::playlistId,
						ByteBufCodecs.collection(ArrayList::new, Track.STREAM_CODEC, MAX_IMPORT),
						LibraryAddTracksC2S::tracks,
						LibraryAddTracksC2S::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Deck controls. {@code action} is one of {@code play_playlist}, {@code toggle},
	 * {@code next}, {@code prev}, {@code stop}, {@code volume}, {@code range},
	 * {@code shuffle}, {@code repeat}, {@code seek}, {@code refresh}.
	 */
	public record SpeakerCommandC2S(BlockPos pos, String action, String arg, int value)
			implements CustomPacketPayload {
		public static final Type<SpeakerCommandC2S> TYPE = new Type<>(id("speaker_command"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SpeakerCommandC2S> CODEC =
				StreamCodec.composite(
						BlockPos.STREAM_CODEC, SpeakerCommandC2S::pos,
						ByteBufCodecs.STRING_UTF8, SpeakerCommandC2S::action,
						ByteBufCodecs.STRING_UTF8, SpeakerCommandC2S::arg,
						ByteBufCodecs.VAR_INT, SpeakerCommandC2S::value,
						SpeakerCommandC2S::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** "My decoder hit the end of this track" — the server advances the queue once. */
	public record SpeakerTrackEndedC2S(BlockPos pos, int trackIndex) implements CustomPacketPayload {
		public static final Type<SpeakerTrackEndedC2S> TYPE = new Type<>(id("speaker_track_ended"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SpeakerTrackEndedC2S> CODEC =
				StreamCodec.composite(
						BlockPos.STREAM_CODEC, SpeakerTrackEndedC2S::pos,
						ByteBufCodecs.VAR_INT, SpeakerTrackEndedC2S::trackIndex,
						SpeakerTrackEndedC2S::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// ------------------------------------------------------- server -> client

	public record LibrarySyncS2C(CompoundTag data) implements CustomPacketPayload {
		public static final Type<LibrarySyncS2C> TYPE = new Type<>(id("library_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, LibrarySyncS2C> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.TRUSTED_COMPOUND_TAG, LibrarySyncS2C::data,
						LibrarySyncS2C::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Full playback state of one speaker. {@code positionMs} is the offset into
	 * the current track at the moment the packet was built; the client adds its
	 * own elapsed time on top, which keeps everyone within a packet round-trip
	 * of each other without needing synchronised clocks.
	 */
	public record SpeakerSyncS2C(BlockPos pos, boolean present, String playlistId, String playlistName,
			int queueSize, int trackIndex, Track track, boolean playing, long positionMs,
			int volume, int range, boolean shuffle, int repeat) implements CustomPacketPayload {

		public static final Type<SpeakerSyncS2C> TYPE = new Type<>(id("speaker_sync"));

		public static final StreamCodec<RegistryFriendlyByteBuf, SpeakerSyncS2C> CODEC =
				StreamCodec.of(SpeakerSyncS2C::write, SpeakerSyncS2C::read);

		public static SpeakerSyncS2C gone(BlockPos pos) {
			return new SpeakerSyncS2C(pos, false, "", "", 0, 0, Track.EMPTY, false, 0, 0, 0, false, 0);
		}

		private static void write(RegistryFriendlyByteBuf buf, SpeakerSyncS2C p) {
			buf.writeBlockPos(p.pos);
			buf.writeBoolean(p.present);
			if (!p.present) return;
			ByteBufCodecs.STRING_UTF8.encode(buf, p.playlistId);
			ByteBufCodecs.STRING_UTF8.encode(buf, p.playlistName);
			buf.writeVarInt(p.queueSize);
			buf.writeVarInt(p.trackIndex);
			Track.STREAM_CODEC.encode(buf, p.track);
			buf.writeBoolean(p.playing);
			buf.writeVarLong(Math.max(0, p.positionMs));
			buf.writeVarInt(p.volume);
			buf.writeVarInt(p.range);
			buf.writeBoolean(p.shuffle);
			buf.writeVarInt(p.repeat);
		}

		private static SpeakerSyncS2C read(RegistryFriendlyByteBuf buf) {
			BlockPos pos = buf.readBlockPos();
			if (!buf.readBoolean()) return gone(pos);
			String playlistId = ByteBufCodecs.STRING_UTF8.decode(buf);
			String playlistName = ByteBufCodecs.STRING_UTF8.decode(buf);
			int queueSize = buf.readVarInt();
			int trackIndex = buf.readVarInt();
			Track track = Track.STREAM_CODEC.decode(buf);
			boolean playing = buf.readBoolean();
			long positionMs = buf.readVarLong();
			int volume = buf.readVarInt();
			int range = buf.readVarInt();
			boolean shuffle = buf.readBoolean();
			int repeat = buf.readVarInt();
			return new SpeakerSyncS2C(pos, true, playlistId, playlistName, queueSize, trackIndex,
					track, playing, positionMs, volume, range, shuffle, repeat);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// --------------------------------------------------------- registration

	public static void register(PayloadRegistrar registrar) {
		registrar.playToServer(LibraryRequestC2S.TYPE, LibraryRequestC2S.CODEC,
				(payload, context) -> {
					if (context.player() instanceof ServerPlayer player) {
						ServerSpeakerManager.sendLibrary(player);
					}
				});
		registrar.playToServer(LibraryCommandC2S.TYPE, LibraryCommandC2S.CODEC,
				(payload, context) -> {
					if (context.player() instanceof ServerPlayer player) {
						ServerSpeakerManager.handleLibraryCommand(player, payload);
					}
				});
		registrar.playToServer(LibraryAddTracksC2S.TYPE, LibraryAddTracksC2S.CODEC,
				(payload, context) -> {
					if (context.player() instanceof ServerPlayer player) {
						ServerSpeakerManager.handleAddTracks(player, payload);
					}
				});
		registrar.playToServer(SpeakerCommandC2S.TYPE, SpeakerCommandC2S.CODEC,
				(payload, context) -> {
					if (context.player() instanceof ServerPlayer player) {
						ServerSpeakerManager.handleSpeakerCommand(player, payload);
					}
				});
		registrar.playToServer(SpeakerTrackEndedC2S.TYPE, SpeakerTrackEndedC2S.CODEC,
				(payload, context) -> {
					if (context.player() instanceof ServerPlayer player) {
						ServerSpeakerManager.handleTrackEnded(player, payload);
					}
				});

		registrar.playToClient(LibrarySyncS2C.TYPE, LibrarySyncS2C.CODEC,
				(payload, context) -> ClientMusicPayloadHandler.handleLibrarySync(payload));
		registrar.playToClient(SpeakerSyncS2C.TYPE, SpeakerSyncS2C.CODEC,
				(payload, context) -> ClientMusicPayloadHandler.handleSpeakerSync(payload));
	}
}
