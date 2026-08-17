package com.lifeessentials.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lifeessentials.phone.TextMessage;

/** Client-side caches synced from the server. */
public final class ClientPhoneData {
	/** The phone number whose conversations are currently cached. */
	public static String conversationsNumber = null;
	/** other number -> messages (oldest first) */
	public static final Map<String, List<TextMessage>> conversations = new LinkedHashMap<>();
	public static final Set<String> unread = new HashSet<>();
	public static final Map<UUID, Boolean> wearingAirpods = new HashMap<>();

	private ClientPhoneData() {
	}

	public static boolean isWearing(UUID uuid) {
		return wearingAirpods.getOrDefault(uuid, false);
	}

	public static void appendMessage(String myNumber, String otherNumber, TextMessage message) {
		if (myNumber != null && !myNumber.equals(conversationsNumber)) {
			conversationsNumber = myNumber;
			conversations.clear();
		}
		conversations.computeIfAbsent(otherNumber, k -> new ArrayList<>()).add(message);
	}

	public static void reset() {
		conversationsNumber = null;
		conversations.clear();
		unread.clear();
		wearingAirpods.clear();
	}
}
