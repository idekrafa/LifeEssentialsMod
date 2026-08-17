package com.lifeessentials.phone;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.lifeessentials.LifeEssentials;
import com.lifeessentials.ModComponents;
import com.lifeessentials.ModItems;
import com.lifeessentials.net.ModPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = LifeEssentials.MOD_ID)
public final class ServerPhoneManager {
	private static final double SPEAKER_RANGE = 24.0;
	private static final long SPEAKER_TIMEOUT_MS = 15000;

	private record SpeakerInfo(String service, String trackId, String title, String artist,
			long updatedAt) {
	}

	private static final Map<UUID, SpeakerInfo> SPEAKERS = new ConcurrentHashMap<>();

	private ServerPhoneManager() {
	}

	// ----------------------------------------------------------- texting

	public static void handleSendMessage(ServerPlayer sender, ModPayloads.SendMessageC2S payload) {
		MinecraftServer server = sender.getServer();
		if (server == null) return;
		String from = PhoneNumbers.normalize(payload.fromNumber());
		String to = PhoneNumbers.normalize(payload.toNumber());
		String text = payload.text().strip();
		if (from == null || to == null || text.isEmpty()) return;
		if (text.length() > 256) text = text.substring(0, 256);
		if (!holdsPhoneWithNumber(sender, from)) return; // can only text from a phone you hold

		PhoneDirectoryState state = PhoneDirectoryState.get(server);
		if (!state.numberExists(to)) {
			sender.sendSystemMessage(phonePrefix()
					.append(Component.literal("Message to " + PhoneNumbers.pretty(to)
							+ " failed: number not in service.").withStyle(ChatFormatting.GRAY)));
			return;
		}

		TextMessage message = new TextMessage(from, text, System.currentTimeMillis());
		state.appendMessage(from, to, message);

		ServerPlayer holder = findHolder(server, to);
		if (holder != null) {
			PacketDistributor.sendToPlayer(holder,
					new ModPayloads.IncomingMessageS2C(to, from, text, message.time()));
			if (holder != sender) {
				holder.sendSystemMessage(phonePrefix()
						.append(Component.literal(PhoneNumbers.pretty(from)).withStyle(ChatFormatting.WHITE))
						.append(Component.literal(" → you: ").withStyle(ChatFormatting.GRAY))
						.append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
				holder.serverLevel().playSound(null, holder.blockPosition(),
						SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8f, 1.6f);
			}
		}
	}

	private static MutableComponent phonePrefix() {
		return Component.literal("[iPhone] ").withStyle(ChatFormatting.AQUA);
	}

	public static void handleRequestConversations(ServerPlayer player,
			ModPayloads.RequestConversationsC2S payload) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		String number = PhoneNumbers.normalize(payload.myNumber());
		if (number == null || !holdsPhoneWithNumber(player, number)) return;

		PhoneDirectoryState state = PhoneDirectoryState.get(server);
		CompoundTag data = new CompoundTag();
		ListTag convos = new ListTag();
		state.conversationsFor(number).forEach((other, messages) -> {
			CompoundTag convo = new CompoundTag();
			convo.putString("other", other);
			ListTag msgs = new ListTag();
			for (TextMessage message : messages) {
				msgs.add(message.toNbt());
			}
			convo.put("msgs", msgs);
			convos.add(convo);
		});
		data.put("convos", convos);
		PacketDistributor.sendToPlayer(player, new ModPayloads.ConversationsS2C(number, data));
	}

	public static boolean holdsPhoneWithNumber(ServerPlayer player, String number) {
		var inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(ModItems.PHONE.get())
					&& number.equals(stack.get(ModComponents.PHONE_NUMBER.get()))) {
				return true;
			}
		}
		return false;
	}

	public static ServerPlayer findHolder(MinecraftServer server, String number) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (holdsPhoneWithNumber(player, number)) {
				return player;
			}
		}
		return null;
	}

	// ------------------------------------------------- speaker / now playing

	public static void handleSpeakerUpdate(ServerPlayer player, ModPayloads.SpeakerUpdateC2S p) {
		if (!p.on() || !p.playing()) {
			if (SPEAKERS.remove(player.getUUID()) != null) {
				broadcastNearby(player, stoppedPayload(player));
			}
			return;
		}
		SPEAKERS.put(player.getUUID(),
				new SpeakerInfo(p.service(), p.trackId(), p.title(), p.artist(), System.currentTimeMillis()));
		broadcastNearby(player, new ModPayloads.NearbyMusicS2C(player.getGameProfile().getName(),
				p.service(), p.trackId(), p.title(), p.artist(), true));
	}

	private static ModPayloads.NearbyMusicS2C stoppedPayload(ServerPlayer player) {
		return new ModPayloads.NearbyMusicS2C(player.getGameProfile().getName(), "", "", "", "", false);
	}

	private static void broadcastNearby(ServerPlayer source, ModPayloads.NearbyMusicS2C payload) {
		for (ServerPlayer other : source.serverLevel().getPlayers(p -> true)) {
			if (other != source && other.distanceToSqr(source) <= SPEAKER_RANGE * SPEAKER_RANGE) {
				PacketDistributor.sendToPlayer(other, payload);
			}
		}
	}

	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post event) {
		tick(event.getServer());
	}

	public static void tick(MinecraftServer server) {
		int ticks = server.getTickCount();

		// wearing AirPods requires actually carrying them
		if (ticks % 20 == 0) {
			PhoneDirectoryState state = PhoneDirectoryState.get(server);
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (state.isWearingAirpods(player.getUUID())
						&& !player.getInventory().hasAnyOf(Set.of(ModItems.AIRPODS.get()))) {
					state.setAirpods(player.getUUID(), false);
					syncAirpods(player, false);
				}
			}
		}

		if (SPEAKERS.isEmpty()) return;
		long now = System.currentTimeMillis();
		List<ServerPlayer> stopped = new ArrayList<>();
		for (Iterator<Map.Entry<UUID, SpeakerInfo>> it = SPEAKERS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, SpeakerInfo> entry = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			SpeakerInfo info = entry.getValue();
			if (player == null || now - info.updatedAt() > SPEAKER_TIMEOUT_MS) {
				it.remove();
				if (player != null) stopped.add(player);
				continue;
			}
			if (ticks % 10 == 0) {
				player.serverLevel().sendParticles(ParticleTypes.NOTE,
						player.getX() + (player.getRandom().nextDouble() - 0.5) * 0.9,
						player.getEyeY() + 0.5 + player.getRandom().nextDouble() * 0.4,
						player.getZ() + (player.getRandom().nextDouble() - 0.5) * 0.9,
						1, 0.0, 0.0, 0.0, 1.0);
			}
			if (ticks % 100 == 0) {
				broadcastNearby(player, new ModPayloads.NearbyMusicS2C(
						player.getGameProfile().getName(), info.service(), info.trackId(),
						info.title(), info.artist(), true));
			}
		}
		for (ServerPlayer player : stopped) {
			broadcastNearby(player, stoppedPayload(player));
		}
	}

	// ------------------------------------------------------------- airpods

	public static void syncAirpods(ServerPlayer player, boolean wearing) {
		ModPayloads.AirpodsSyncS2C payload = new ModPayloads.AirpodsSyncS2C(player.getUUID(), wearing);
		PacketDistributor.sendToPlayer(player, payload);
		PacketDistributor.sendToPlayersTrackingEntity(player, payload);
	}

	public static void sendAllAirpodsStates(ServerPlayer to, MinecraftServer server) {
		PhoneDirectoryState state = PhoneDirectoryState.get(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			boolean wearing = state.isWearingAirpods(player.getUUID());
			if (wearing || player == to) {
				PacketDistributor.sendToPlayer(to, new ModPayloads.AirpodsSyncS2C(player.getUUID(), wearing));
			}
		}
	}

	@SubscribeEvent
	public static void onStartTracking(PlayerEvent.StartTracking event) {
		if (event.getTarget() instanceof ServerPlayer tracked
				&& event.getEntity() instanceof ServerPlayer watcher
				&& watcher.getServer() != null) {
			boolean wearing = PhoneDirectoryState.get(watcher.getServer())
					.isWearingAirpods(tracked.getUUID());
			PacketDistributor.sendToPlayer(watcher,
					new ModPayloads.AirpodsSyncS2C(tracked.getUUID(), wearing));
		}
	}

	@SubscribeEvent
	public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
		if (event.getEntity() instanceof ServerPlayer player && player.getServer() != null) {
			sendAllAirpodsStates(player, player.getServer());
		}
	}
}
