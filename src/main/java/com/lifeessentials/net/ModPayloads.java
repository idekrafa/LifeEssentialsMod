package com.lifeessentials.net;

import java.util.UUID;

import com.lifeessentials.LifeEssentials;
import com.lifeessentials.client.ClientPayloadHandler;
import com.lifeessentials.phone.ServerPhoneManager;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModPayloads {

	private ModPayloads() {
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(LifeEssentials.MOD_ID, path);
	}

	// ------------------------------------------------------- client -> server

	public record RequestConversationsC2S(String myNumber) implements CustomPacketPayload {
		public static final Type<RequestConversationsC2S> TYPE = new Type<>(id("request_conversations"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestConversationsC2S> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, RequestConversationsC2S::myNumber,
						RequestConversationsC2S::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record SendMessageC2S(String fromNumber, String toNumber, String text)
			implements CustomPacketPayload {
		public static final Type<SendMessageC2S> TYPE = new Type<>(id("send_message"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SendMessageC2S> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, SendMessageC2S::fromNumber,
						ByteBufCodecs.STRING_UTF8, SendMessageC2S::toNumber,
						ByteBufCodecs.STRING_UTF8, SendMessageC2S::text,
						SendMessageC2S::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record SpeakerUpdateC2S(boolean on, String service, String trackId,
			String title, String artist, boolean playing) implements CustomPacketPayload {
		public static final Type<SpeakerUpdateC2S> TYPE = new Type<>(id("speaker_update"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SpeakerUpdateC2S> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.BOOL, SpeakerUpdateC2S::on,
						ByteBufCodecs.STRING_UTF8, SpeakerUpdateC2S::service,
						ByteBufCodecs.STRING_UTF8, SpeakerUpdateC2S::trackId,
						ByteBufCodecs.STRING_UTF8, SpeakerUpdateC2S::title,
						ByteBufCodecs.STRING_UTF8, SpeakerUpdateC2S::artist,
						ByteBufCodecs.BOOL, SpeakerUpdateC2S::playing,
						SpeakerUpdateC2S::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// ------------------------------------------------------- server -> client

	public record ConversationsS2C(String myNumber, CompoundTag data) implements CustomPacketPayload {
		public static final Type<ConversationsS2C> TYPE = new Type<>(id("conversations"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ConversationsS2C> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, ConversationsS2C::myNumber,
						ByteBufCodecs.TRUSTED_COMPOUND_TAG, ConversationsS2C::data,
						ConversationsS2C::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record IncomingMessageS2C(String toNumber, String fromNumber, String text, long time)
			implements CustomPacketPayload {
		public static final Type<IncomingMessageS2C> TYPE = new Type<>(id("incoming_message"));
		public static final StreamCodec<RegistryFriendlyByteBuf, IncomingMessageS2C> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, IncomingMessageS2C::toNumber,
						ByteBufCodecs.STRING_UTF8, IncomingMessageS2C::fromNumber,
						ByteBufCodecs.STRING_UTF8, IncomingMessageS2C::text,
						ByteBufCodecs.VAR_LONG, IncomingMessageS2C::time,
						IncomingMessageS2C::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record NearbyMusicS2C(String playerName, String service, String trackId,
			String title, String artist, boolean playing) implements CustomPacketPayload {
		public static final Type<NearbyMusicS2C> TYPE = new Type<>(id("nearby_music"));
		public static final StreamCodec<RegistryFriendlyByteBuf, NearbyMusicS2C> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, NearbyMusicS2C::playerName,
						ByteBufCodecs.STRING_UTF8, NearbyMusicS2C::service,
						ByteBufCodecs.STRING_UTF8, NearbyMusicS2C::trackId,
						ByteBufCodecs.STRING_UTF8, NearbyMusicS2C::title,
						ByteBufCodecs.STRING_UTF8, NearbyMusicS2C::artist,
						ByteBufCodecs.BOOL, NearbyMusicS2C::playing,
						NearbyMusicS2C::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record AirpodsSyncS2C(UUID player, boolean wearing) implements CustomPacketPayload {
		public static final Type<AirpodsSyncS2C> TYPE = new Type<>(id("airpods_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, AirpodsSyncS2C> CODEC =
				StreamCodec.composite(
						UUIDUtil.STREAM_CODEC, AirpodsSyncS2C::player,
						ByteBufCodecs.BOOL, AirpodsSyncS2C::wearing,
						AirpodsSyncS2C::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// --------------------------------------------------------- registration

	public static void register(RegisterPayloadHandlersEvent event) {
		// optional: joining a server without the mod stays possible
		PayloadRegistrar registrar = event.registrar("1").optional();

		registrar.playToServer(RequestConversationsC2S.TYPE, RequestConversationsC2S.CODEC,
				(payload, context) -> {
					if (context.player() instanceof ServerPlayer player) {
						ServerPhoneManager.handleRequestConversations(player, payload);
					}
				});
		registrar.playToServer(SendMessageC2S.TYPE, SendMessageC2S.CODEC,
				(payload, context) -> {
					if (context.player() instanceof ServerPlayer player) {
						ServerPhoneManager.handleSendMessage(player, payload);
					}
				});
		registrar.playToServer(SpeakerUpdateC2S.TYPE, SpeakerUpdateC2S.CODEC,
				(payload, context) -> {
					if (context.player() instanceof ServerPlayer player) {
						ServerPhoneManager.handleSpeakerUpdate(player, payload);
					}
				});

		// client handler bodies live in ClientPayloadHandler (client classes are
		// only touched when the handlers actually run, i.e. never on a server)
		registrar.playToClient(ConversationsS2C.TYPE, ConversationsS2C.CODEC,
				(payload, context) -> ClientPayloadHandler.handleConversations(payload));
		registrar.playToClient(IncomingMessageS2C.TYPE, IncomingMessageS2C.CODEC,
				(payload, context) -> ClientPayloadHandler.handleIncomingMessage(payload));
		registrar.playToClient(NearbyMusicS2C.TYPE, NearbyMusicS2C.CODEC,
				(payload, context) -> ClientPayloadHandler.handleNearbyMusic(payload));
		registrar.playToClient(AirpodsSyncS2C.TYPE, AirpodsSyncS2C.CODEC,
				(payload, context) -> ClientPayloadHandler.handleAirpodsSync(payload));

		MusicPayloads.register(registrar);
	}
}
