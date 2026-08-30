package com.lifeessentials.client.audio;

/**
 * Why a track could not be opened, in words safe to show a player in chat.
 *
 * <p>Deliberately has no Minecraft types on it: this class crosses the classloader
 * boundary into the backend, so it has to compile against the JDK alone.
 */
public class BackendFailure extends Exception {
	public BackendFailure(String message) {
		super(message);
	}
}
