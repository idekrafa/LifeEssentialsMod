package com.lifeessentials.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client-side send helpers with connection/channel guards. */
public final class ClientNet {
	private ClientNet() {
	}

	public static boolean canSend(CustomPacketPayload.Type<?> type) {
		var connection = Minecraft.getInstance().getConnection();
		return connection != null && connection.hasChannel(type);
	}

	public static void send(CustomPacketPayload payload) {
		if (canSend(payload.type())) {
			PacketDistributor.sendToServer(payload);
		}
	}
}
