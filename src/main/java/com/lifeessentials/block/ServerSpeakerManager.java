package com.lifeessentials.block;

import java.util.ArrayList;
import java.util.List;

import com.lifeessentials.LifeEssentials;
import com.lifeessentials.music.MusicLibraryState;
import com.lifeessentials.music.Playlist;
import com.lifeessentials.music.Track;
import com.lifeessentials.music.TrackUris;
import com.lifeessentials.net.MusicPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server side of the library and the speaker deck. */
@EventBusSubscriber(modid = LifeEssentials.MOD_ID)
public final class ServerSpeakerManager {
	/** How far a player may be from a speaker to press its buttons. */
	private static final double CONTROL_REACH = 64.0;
	/** Extra blocks beyond the audible range that still receive sync packets. */
	private static final int SYNC_MARGIN = 16;

	private ServerSpeakerManager() {
	}

	// ---------------------------------------------------------------- library

	/** Everyone gets the playlists as soon as they join, before opening anything. */
	@SubscribeEvent
	public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			sendLibrary(player);
		}
	}

	public static void sendLibrary(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		PacketDistributor.sendToPlayer(player,
				new MusicPayloads.LibrarySyncS2C(MusicLibraryState.get(server).toSyncTag()));
	}

	private static void broadcastLibrary(MinecraftServer server) {
		MusicPayloads.LibrarySyncS2C payload =
				new MusicPayloads.LibrarySyncS2C(MusicLibraryState.get(server).toSyncTag());
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			PacketDistributor.sendToPlayer(player, payload);
		}
	}

	public static void handleLibraryCommand(ServerPlayer player, MusicPayloads.LibraryCommandC2S p) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		MusicLibraryState library = MusicLibraryState.get(server);
		switch (p.action()) {
			case "create" -> {
				Playlist created = library.createPlaylist(p.text(), player.getGameProfile().getName());
				if (created == null) {
					warn(player, "The server's playlist list is full.");
					return;
				}
			}
			case "delete" -> {
				if (!mayEdit(player, library.playlist(p.playlistId()))) {
					warn(player, "Only the player who made that playlist can delete it.");
					return;
				}
				library.deletePlaylist(p.playlistId());
			}
			case "rename" -> {
				if (!mayEdit(player, library.playlist(p.playlistId()))) {
					warn(player, "Only the player who made that playlist can rename it.");
					return;
				}
				library.renamePlaylist(p.playlistId(), p.text());
			}
			case "remove_track" -> library.removeFromPlaylist(p.playlistId(), p.index());
			case "move_up" -> library.movePlaylistTrack(p.playlistId(), p.index(), -1);
			case "move_down" -> library.movePlaylistTrack(p.playlistId(), p.index(), 1);
			default -> {
				return;
			}
		}
		broadcastLibrary(server);
	}

	/**
	 * Anyone can add to or reorder a playlist — that's the point of a shared
	 * library — but throwing one away is left to whoever made it (or an op).
	 */
	private static boolean mayEdit(ServerPlayer player, Playlist playlist) {
		if (playlist == null) return false;
		return playlist.ownerName().isEmpty()
				|| playlist.ownerName().equals(player.getGameProfile().getName())
				|| player.hasPermissions(2);
	}

	public static void handleAddTracks(ServerPlayer player, MusicPayloads.LibraryAddTracksC2S p) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		MusicLibraryState library = MusicLibraryState.get(server);
		if (library.playlist(p.playlistId()) == null) {
			warn(player, "That playlist no longer exists.");
			return;
		}

		List<Track> clean = new ArrayList<>();
		int rejected = 0;
		for (Track candidate : p.tracks()) {
			Track sane = TrackUris.sanitize(candidate);
			if (sane == null) {
				rejected++;
			} else {
				clean.add(sane);
			}
		}
		int added = library.addToPlaylist(p.playlistId(), clean);
		broadcastLibrary(server);

		if (added > 0) {
			player.displayClientMessage(Component.literal("Added " + added
					+ (added == 1 ? " track" : " tracks")).withStyle(ChatFormatting.GREEN), true);
		}
		if (rejected > 0) {
			warn(player, rejected + " entr" + (rejected == 1 ? "y was" : "ies were")
					+ " rejected — only local music files, http(s) links, YouTube and Spotify work.");
		}
	}

	// ---------------------------------------------------------------- speaker

	public static void handleSpeakerCommand(ServerPlayer player, MusicPayloads.SpeakerCommandC2S p) {
		SpeakerBlockEntity speaker = speakerFor(player, p.pos());
		if (speaker == null) return;
		switch (p.action()) {
			case "play_playlist" -> {
				MinecraftServer server = player.getServer();
				if (server == null) return;
				if (MusicLibraryState.get(server).playlist(p.arg()) == null) return;
				speaker.setPlaylist(p.arg(), p.value());
				clickSound(player.serverLevel(), p.pos());
			}
			case "toggle" -> {
				speaker.togglePlay();
				clickSound(player.serverLevel(), p.pos());
			}
			case "next" -> speaker.skip(1);
			case "prev" -> speaker.skip(-1);
			case "play_index" -> {
				speaker.setPlaylist(speaker.playlistId(), p.value());
			}
			case "stop" -> speaker.stop();
			case "volume" -> speaker.setVolume(p.value());
			case "range" -> speaker.setRange(p.value());
			case "shuffle" -> speaker.toggleShuffle();
			case "repeat" -> speaker.cycleRepeat();
			case "seek" -> speaker.seekTo(p.value() * 1000L);
			case "refresh" -> {
				// just fall through to the sync below
			}
			default -> {
				return;
			}
		}
		syncToNearby(player.serverLevel(), speaker);
	}

	public static void handleTrackEnded(ServerPlayer player, MusicPayloads.SpeakerTrackEndedC2S p) {
		SpeakerBlockEntity speaker = speakerFor(player, p.pos());
		if (speaker == null || !speaker.isPlaying()) return;
		int before = speaker.trackIndex();
		speaker.reportTrackEnded(p.trackIndex());
		if (before != speaker.trackIndex() || !speaker.isPlaying()) {
			syncToNearby(player.serverLevel(), speaker);
		}
	}

	private static SpeakerBlockEntity speakerFor(ServerPlayer player, BlockPos pos) {
		ServerLevel level = player.serverLevel();
		if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
				> CONTROL_REACH * CONTROL_REACH) {
			return null;
		}
		if (!level.isLoaded(pos)) return null;
		return level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker ? speaker : null;
	}

	// ------------------------------------------------------------------- sync

	public static MusicPayloads.SpeakerSyncS2C snapshot(SpeakerBlockEntity speaker) {
		Track track = speaker.currentTrack();
		return new MusicPayloads.SpeakerSyncS2C(speaker.getBlockPos(), true,
				speaker.playlistId(), speaker.playlistName(), speaker.queue().size(),
				speaker.trackIndex(), track, speaker.isPlaying(), speaker.positionMs(),
				speaker.volume(), speaker.range(), speaker.shuffle(), speaker.repeat().ordinal());
	}

	public static void syncToPlayer(ServerPlayer player, SpeakerBlockEntity speaker) {
		PacketDistributor.sendToPlayer(player, snapshot(speaker));
	}

	public static void syncToNearby(ServerLevel level, SpeakerBlockEntity speaker) {
		MusicPayloads.SpeakerSyncS2C payload = snapshot(speaker);
		BlockPos pos = speaker.getBlockPos();
		double reach = speaker.range() + SYNC_MARGIN;
		for (ServerPlayer player : level.players()) {
			if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
					<= reach * reach) {
				PacketDistributor.sendToPlayer(player, payload);
			}
		}
	}

	public static void onSpeakerRemoved(Level level, BlockPos pos) {
		if (!(level instanceof ServerLevel serverLevel)) return;
		MusicPayloads.SpeakerSyncS2C payload = MusicPayloads.SpeakerSyncS2C.gone(pos);
		double reach = SpeakerBlockEntity.MAX_RANGE + SYNC_MARGIN;
		for (ServerPlayer player : serverLevel.players()) {
			if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
					<= reach * reach) {
				PacketDistributor.sendToPlayer(player, payload);
			}
		}
	}

	private static void clickSound(ServerLevel level, BlockPos pos) {
		level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.4f, 1.8f);
	}

	private static void warn(ServerPlayer player, String message) {
		player.displayClientMessage(Component.literal(message).withStyle(ChatFormatting.YELLOW), false);
	}
}
