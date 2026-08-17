package com.lifeessentials.phone;

import java.util.Random;

public final class PhoneNumbers {
	private PhoneNumbers() {
	}

	/** Random 10-digit number as a digits-only string, e.g. "2382302939". */
	public static String random(Random random) {
		int area = 200 + random.nextInt(800);
		int mid = 200 + random.nextInt(800);
		int last = random.nextInt(10000);
		return String.format("%03d%03d%04d", area, mid, last);
	}

	/** Formats "2382302939" as "(238) 230-2939". */
	public static String pretty(String digits) {
		if (digits == null) return "?";
		if (digits.length() != 10) return digits;
		return "(" + digits.substring(0, 3) + ") " + digits.substring(3, 6) + "-" + digits.substring(6);
	}

	/** Strips formatting; returns the 10-digit string or null if it isn't a valid number. */
	public static String normalize(String input) {
		if (input == null) return null;
		StringBuilder sb = new StringBuilder();
		for (char c : input.toCharArray()) {
			if (c >= '0' && c <= '9') sb.append(c);
		}
		if (sb.length() == 11 && sb.charAt(0) == '1') sb.deleteCharAt(0);
		return sb.length() == 10 ? sb.toString() : null;
	}
}
