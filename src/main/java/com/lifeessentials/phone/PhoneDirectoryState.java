package com.lifeessentials.phone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Server-wide phone directory: every assigned number, every phone's message
 * history, and who is currently wearing AirPods.
 */
public class PhoneDirectoryState extends SavedData {
	private static final int MAX_MESSAGES_PER_CONVERSATION = 100;

	public static final Factory<PhoneDirectoryState> FACTORY =
			new Factory<>(PhoneDirectoryState::new, PhoneDirectoryState::load, null);

	private final Set<String> numbers = new HashSet<>();
	/** number -> (other number -> messages, oldest first) */
	private final Map<String, Map<String, List<TextMessage>>> mailboxes = new HashMap<>();
	private final Set<UUID> wearingAirpods = new HashSet<>();
	private final java.util.Random random = new java.util.Random();

	public static PhoneDirectoryState get(MinecraftServer server) {
		return server.overworld().getDataStorage()
				.computeIfAbsent(FACTORY, "lifeessentials_phones");
	}

	public String assignNumber() {
		String number;
		do {
			number = PhoneNumbers.random(random);
		} while (!numbers.add(number));
		setDirty();
		return number;
	}

	public boolean numberExists(String number) {
		return numbers.contains(number);
	}

	public void appendMessage(String fromNumber, String toNumber, TextMessage message) {
		append(fromNumber, toNumber, message); // sender's copy, filed under the recipient
		if (!fromNumber.equals(toNumber)) {
			append(toNumber, fromNumber, message); // recipient's copy, filed under the sender
		}
		setDirty();
	}

	private void append(String owner, String other, TextMessage message) {
		List<TextMessage> list = mailboxes
				.computeIfAbsent(owner, k -> new LinkedHashMap<>())
				.computeIfAbsent(other, k -> new ArrayList<>());
		list.add(message);
		while (list.size() > MAX_MESSAGES_PER_CONVERSATION) {
			list.remove(0);
		}
	}

	public Map<String, List<TextMessage>> conversationsFor(String number) {
		return mailboxes.getOrDefault(number, Map.of());
	}

	public boolean isWearingAirpods(UUID player) {
		return wearingAirpods.contains(player);
	}

	public boolean toggleAirpods(UUID player) {
		boolean nowWearing = !wearingAirpods.remove(player);
		if (nowWearing) {
			wearingAirpods.add(player);
		}
		setDirty();
		return nowWearing;
	}

	public void setAirpods(UUID player, boolean wearing) {
		boolean changed = wearing ? wearingAirpods.add(player) : wearingAirpods.remove(player);
		if (changed) {
			setDirty();
		}
	}

	// --------------------------------------------------------------- NBT

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		ListTag numberList = new ListTag();
		for (String number : numbers) {
			numberList.add(StringTag.valueOf(number));
		}
		tag.put("numbers", numberList);

		ListTag airpodsList = new ListTag();
		for (UUID uuid : wearingAirpods) {
			airpodsList.add(StringTag.valueOf(uuid.toString()));
		}
		tag.put("airpods", airpodsList);

		ListTag boxes = new ListTag();
		mailboxes.forEach((number, convos) -> {
			CompoundTag box = new CompoundTag();
			box.putString("number", number);
			ListTag convoList = new ListTag();
			convos.forEach((other, messages) -> {
				CompoundTag convo = new CompoundTag();
				convo.putString("other", other);
				ListTag msgs = new ListTag();
				for (TextMessage message : messages) {
					msgs.add(message.toNbt());
				}
				convo.put("msgs", msgs);
				convoList.add(convo);
			});
			box.put("convos", convoList);
			boxes.add(box);
		});
		tag.put("mailboxes", boxes);
		return tag;
	}

	public static PhoneDirectoryState load(CompoundTag tag, HolderLookup.Provider registries) {
		PhoneDirectoryState state = new PhoneDirectoryState();
		ListTag numberList = tag.getList("numbers", Tag.TAG_STRING);
		for (int i = 0; i < numberList.size(); i++) {
			state.numbers.add(numberList.getString(i));
		}
		ListTag airpodsList = tag.getList("airpods", Tag.TAG_STRING);
		for (int i = 0; i < airpodsList.size(); i++) {
			try {
				state.wearingAirpods.add(UUID.fromString(airpodsList.getString(i)));
			} catch (IllegalArgumentException ignored) {
			}
		}
		ListTag boxes = tag.getList("mailboxes", Tag.TAG_COMPOUND);
		for (int i = 0; i < boxes.size(); i++) {
			CompoundTag box = boxes.getCompound(i);
			String number = box.getString("number");
			Map<String, List<TextMessage>> convos = new LinkedHashMap<>();
			ListTag convoList = box.getList("convos", Tag.TAG_COMPOUND);
			for (int j = 0; j < convoList.size(); j++) {
				CompoundTag convo = convoList.getCompound(j);
				List<TextMessage> messages = new ArrayList<>();
				ListTag msgs = convo.getList("msgs", Tag.TAG_COMPOUND);
				for (int k = 0; k < msgs.size(); k++) {
					messages.add(TextMessage.fromNbt(msgs.getCompound(k)));
				}
				convos.put(convo.getString("other"), messages);
			}
			state.mailboxes.put(number, convos);
		}
		return state;
	}
}
