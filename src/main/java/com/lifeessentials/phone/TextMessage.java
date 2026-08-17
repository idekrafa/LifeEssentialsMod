package com.lifeessentials.phone;

import net.minecraft.nbt.CompoundTag;

public record TextMessage(String from, String text, long time) {
	public CompoundTag toNbt() {
		CompoundTag tag = new CompoundTag();
		tag.putString("from", from);
		tag.putString("text", text);
		tag.putLong("time", time);
		return tag;
	}

	public static TextMessage fromNbt(CompoundTag tag) {
		return new TextMessage(tag.getString("from"), tag.getString("text"), tag.getLong("time"));
	}
}
