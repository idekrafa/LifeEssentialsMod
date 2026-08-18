package com.lifeessentials.client.audio;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.lifeessentials.LifeEssentials;
import net.minecraft.client.Minecraft;

/**
 * Unpacks the audio engine and loads it in isolation.
 *
 * <p>The engine ships as jars under {@code lifeessentials/lib/} rather than as
 * classes in this one. Resources declare no packages, so the module system has
 * nothing to collide on, and mods that bundle LavaPlayer flat — Iam Music Player
 * among them — no longer stop the game from booting.
 */
public final class BackendLoader {
	private static final String RESOURCE_DIR = "lifeessentials/lib/";
	private static final String INDEX = RESOURCE_DIR + "index.txt";
	private static final String IMPLEMENTATION = "com.lifeessentials.backend.LavaBackend";

	/**
	 * Packages the child must <em>not</em> load itself.
	 *
	 * <p>The JDK for obvious reasons, and {@code com.lifeessentials} because the two
	 * sides have to agree on {@link AudioBackend} and {@link PcmSource} — resolving
	 * those twice would mean two incompatible copies of the same interface. slf4j
	 * and log4j go up so the engine's own logging lands in the game log.
	 */
	private static final String[] PARENT_FIRST = {
		"java.", "javax.", "jdk.", "sun.", "com.sun.",
		"com.lifeessentials.client.audio.", "com.lifeessentials.LifeEssentials",
		"org.slf4j.", "org.apache.logging.",
		"net.minecraft.", "net.neoforged.",
	};

	private static AudioBackend backend;
	private static String failure;
	private static boolean tried;

	private BackendLoader() {
	}

	public static synchronized AudioBackend backend() {
		if (tried) return backend;
		tried = true;
		try {
			Path libDir = gameDir().resolve("lifeessentials").resolve("lib");
			List<URL> urls = unpack(libDir);
			if (urls.isEmpty()) {
				failure = "the engine's libraries are missing from the mod jar";
				LifeEssentials.LOGGER.error("No audio engine libraries found at {}", RESOURCE_DIR);
				return null;
			}

			// LavaPlayer extracts its native library and System.load()s it by path.
			// Another mod loading the same file from the same path in a different
			// classloader is an UnsatisfiedLinkError, so ours goes somewhere of its own.
			Path natives = gameDir().resolve("lifeessentials").resolve("natives");
			Files.createDirectories(natives);
			System.setProperty("lavaplayer.native.dir", natives.toAbsolutePath().toString());

			ClassLoader loader = new IsolatedLoader(urls.toArray(new URL[0]),
					BackendLoader.class.getClassLoader());
			backend = (AudioBackend) Class.forName(IMPLEMENTATION, true, loader)
					.getDeclaredConstructor().newInstance();
			LifeEssentials.LOGGER.info("Audio engine loaded in isolation ({} libraries)", urls.size());
		} catch (Throwable e) {
			// a missing native, a bad unpack, anything — degrade, never crash the game
			failure = e.getClass().getSimpleName()
					+ (e.getMessage() == null ? "" : ": " + e.getMessage());
			LifeEssentials.LOGGER.warn("Audio engine unavailable, falling back to ffmpeg", e);
		}
		return backend;
	}

	public static synchronized String unavailableReason() {
		backend();
		return failure == null ? "" : failure;
	}

	/** Non-blocking, for render code. */
	public static boolean isReady() {
		return backend != null;
	}

	public static synchronized void shutdown() {
		if (backend != null) {
			backend.shutdown();
			backend = null;
		}
		tried = false;
		failure = null;
	}

	// ---------------------------------------------------------------- unpacking

	private static Path gameDir() {
		return Minecraft.getInstance().gameDirectory.toPath();
	}

	/**
	 * Copies each shipped jar out to disk, skipping ones already there at the same
	 * size. They have to be real files: a classloader cannot read a jar nested
	 * inside another jar.
	 */
	private static List<URL> unpack(Path libDir) throws IOException {
		List<URL> urls = new ArrayList<>();
		Files.createDirectories(libDir);
		for (String name : index()) {
			Path target = libDir.resolve(name);
			try (InputStream in = resource(RESOURCE_DIR + name)) {
				if (in == null) {
					LifeEssentials.LOGGER.warn("Engine library {} is not in the jar", name);
					continue;
				}
				if (!Files.exists(target) || Files.size(target) == 0) {
					Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			urls.add(target.toUri().toURL());
		}
		return urls;
	}

	private static List<String> index() throws IOException {
		try (InputStream in = resource(INDEX)) {
			if (in == null) return List.of();
			List<String> names = new ArrayList<>();
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				String name = line.strip();
				// a name is a file name, never a path — don't let one escape the folder
				if (!name.isEmpty() && name.endsWith(".jar") && !name.contains("/")) {
					names.add(name);
				}
			}
			return names;
		}
	}

	private static InputStream resource(String path) {
		return BackendLoader.class.getClassLoader().getResourceAsStream(path);
	}

	// ------------------------------------------------------------------- loader

	/**
	 * Child-first for the engine, parent-first for everything shared.
	 *
	 * <p>The direction matters more than it looks. Ordinary delegation asks the
	 * parent first, and on a server running another LavaPlayer mod the parent
	 * <em>has</em> an answer — that mod's copy, bound to its own relocated base
	 * classes. Taking it would throw {@code IncompatibleClassChangeError} deep in a
	 * decode. Loading our own copy first is the entire point of this class.
	 */
	private static final class IsolatedLoader extends URLClassLoader {
		static {
			registerAsParallelCapable();
		}

		private IsolatedLoader(URL[] urls, ClassLoader parent) {
			super("lifeessentials-audio", urls, parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null) {
					loaded = sharedWithParent(name) ? super.loadClass(name, false) : ownFirst(name);
				}
				if (resolve) resolveClass(loaded);
				return loaded;
			}
		}

		private Class<?> ownFirst(String name) throws ClassNotFoundException {
			try {
				return findClass(name);
			} catch (ClassNotFoundException notOurs) {
				return super.loadClass(name, false);
			}
		}

		private static boolean sharedWithParent(String name) {
			for (String prefix : PARENT_FIRST) {
				if (name.startsWith(prefix)) return true;
			}
			return false;
		}
	}
}
