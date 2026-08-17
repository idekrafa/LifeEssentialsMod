package com.lifeessentials.client;

import java.util.ArrayList;
import java.util.List;

import com.lifeessentials.client.gui.ConversationScreen;
import com.lifeessentials.client.music.MusicClientState;
import com.lifeessentials.net.ModPayloads;
import com.lifeessentials.phone.TextMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/**
 * Bodies of the S2C payload handlers. Kept separate from the registration so
 * dedicated servers never execute client-only code.
 */
public final class ClientPayloadHandler {
	private ClientPayloadHandler() {
	}

	public static void handleConversations(ModPayloads.ConversationsS2C payload) {
		ClientPhoneData.conversationsNumber = payload.myNumber();
		ClientPhoneData.conversations.clear();
		ListTag convos = payload.data().getList("convos", Tag.TAG_COMPOUND);
		for (int i = 0; i < convos.size(); i++) {
			CompoundTag convo = convos.getCompound(i);
			ListTag msgs = convo.getList("msgs", Tag.TAG_COMPOUND);
			List<TextMessage> messages = new ArrayList<>();
			for (int j = 0; j < msgs.size(); j++) {
				messages.add(TextMessage.fromNbt(msgs.getCompound(j)));
			}
			ClientPhoneData.conversations.put(convo.getString("other"), messages);
		}
	}

	public static void handleIncomingMessage(ModPayloads.IncomingMessageS2C payload) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientPhoneData.appendMessage(payload.toNumber(), payload.fromNumber(),
				new TextMessage(payload.fromNumber(), payload.text(), payload.time()));
		boolean viewing = minecraft.screen instanceof ConversationScreen conversation
				&& conversation.otherNumber().equals(payload.fromNumber());
		if (!viewing) {
			ClientPhoneData.unread.add(payload.fromNumber());
		}
	}

	public static void handleNearbyMusic(ModPayloads.NearbyMusicS2C payload) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!payload.playing() || minecraft.player == null) return;
		minecraft.player.displayClientMessage(Component.literal("♪ " + payload.playerName()
						+ " ▶ " + payload.title() + " — " + payload.artist())
				.withStyle(ChatFormatting.AQUA), true);
		if ("spotify".equals(payload.service())) {
			MusicClientState.handleListenAlong(payload.trackId());
		}
	}

	public static void handleAirpodsSync(ModPayloads.AirpodsSyncS2C payload) {
		ClientPhoneData.wearingAirpods.put(payload.player(), payload.wearing());
	}
}
