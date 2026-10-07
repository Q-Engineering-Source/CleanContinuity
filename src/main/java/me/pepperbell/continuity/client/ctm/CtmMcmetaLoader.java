package me.pepperbell.continuity.client.ctm;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.pepperbell.continuity.client.ContinuityClient;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.AbstractResourcePack;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.Loader;

/**
 * Scans resource packs for CTM Mod format metadata. Pack files use
 * {@code assets/<ns>/<path>.png.mcmeta}; B.A.S.E and Resource Loader also expose
 * {@code resources/<ns>/<path>.png.mcmeta} (and Resource Loader's {@code oresources}).
 * Metadata with a {@code "ctm"} section becomes a {@link CtmDefinition}.
 * <p>
 * Runs alongside (and independently of) the OptiFine {@code optifine/ctm/*.properties} loader.
 */
public final class CtmMcmetaLoader {
	private static volatile Diagnostics lastDiagnostics = new Diagnostics(0, 0);
	private final IResourceManager resourceManager;
	private final List<CtmDefinition> properties = new ObjectArrayList<>();
	private final Set<ResourceLocation> invalidMetadata = new HashSet<>();
	private final Set<ResourceLocation> unresolvedResources = new HashSet<>();
	private final Set<ResourceLocation> processedMetadata = new HashSet<>();
	private final Map<String, Integer> packPriorities = new HashMap<>();

	private CtmMcmetaLoader(IResourceManager resourceManager) {
		this.resourceManager = resourceManager;
	}

	public static List<CtmDefinition> loadAll() {
		// Custom logic types (ctm.json + ctm_logic/*.json) must be loaded before mcmeta parsing,
		// since a mcmeta may reference a namespaced custom type.
		CtmDefinitionManager.reload();
		CtmMcmetaLoader loader = new CtmMcmetaLoader(Minecraft.getMinecraft().getResourceManager());
		loader.loadAllPacks();
		loader.properties.sort(null);
		lastDiagnostics = new Diagnostics(loader.invalidMetadata.size(), loader.unresolvedResources.size());
		return loader.properties;
	}

	public static Diagnostics getLastDiagnostics() {
		return lastDiagnostics;
	}

	public record Diagnostics(int invalidMetadata, int unresolvedResources) {
	}

	private void loadAllPacks() {
		int packPriority = 0;
		Set<String> seenPacks = new HashSet<>();
		Set<Path> scannedDirectories = new HashSet<>();
		int externalPackCount = 0;

		for (IResourcePack pack : FMLClientHandler.instance().getResourcePackList()) {
			if (seenPacks.add(pack.getPackName())) {
				rememberDirectory(pack, scannedDirectories);
				int priority = packPriority++;
				packPriorities.put(pack.getPackName(), priority);
				loadAll(pack, priority);
			}
		}

		ResourcePackRepository repository = Minecraft.getMinecraft().getResourcePackRepository();
		for (ResourcePackRepository.Entry entry : repository.getRepositoryEntries()) {
			if (seenPacks.add(entry.getResourcePackName())) {
				rememberDirectory(entry.getResourcePack(), scannedDirectories);
				int priority = packPriority++;
				packPriorities.put(entry.getResourcePackName(), priority);
				loadAll(entry.getResourcePack(), priority);
			}
		}

		IResourcePack serverPack = repository.getServerResourcePack();
		if (serverPack != null && seenPacks.add(serverPack.getPackName())) {
			rememberDirectory(serverPack, scannedDirectories);
			int priority = packPriority++;
			packPriorities.put(serverPack.getPackName(), priority);
			loadAll(serverPack, priority);
		}

		// B.A.S.E and Resource Loader expose namespace folders directly under these roots.
		// They may be inserted into Minecraft's default pack list rather than the lists above.
		boolean resourceLoaderPresent = Loader.isModLoaded("resourceloader");
		if (Loader.isModLoaded("base") || resourceLoaderPresent) {
			Path gameDir = Loader.instance().getConfigDir().getParentFile().toPath();
			for (Path root : externalRoots(gameDir, scannedDirectories, resourceLoaderPresent)) {
				String folder = root.getFileName().toString();
				int priority = folder.equals("resources") ? -1 : packPriority++;
				packPriorities.put(folder, priority);
				loadAll(folder, priority,
						consumer -> scanDirectory(root, consumer));
				externalPackCount++;
			}
		}

		ContinuityClient.LOGGER.debug("Loaded {} CTM Mod metadata definitions from {} packs", properties.size(), seenPacks.size() + externalPackCount);
	}

	private void loadAll(IResourcePack pack, int packPriority) {
		loadAll(pack.getPackName(), packPriority, consumer -> scanPack(pack, consumer));
	}

	private void loadAll(String packName, int packPriority, Consumer<BiConsumer<String, String>> scanner) {
		scanner.accept((namespace, path) -> {
			if (!path.endsWith(".mcmeta") || !path.contains("/")) {
				return;
			}
			String texturePath = path.substring(0, path.length() - ".mcmeta".length());
			if (!texturePath.endsWith(".png")) {
				return;
			}
			// The block-atlas sprite id is the file path relative to textures/, without the .png
			// extension (e.g. "blocks/glass"); the mcmeta resource lives at textures/<id>.png.mcmeta.
			String spriteIdPath = texturePath.startsWith("textures/")
					? texturePath.substring("textures/".length(), texturePath.length() - ".png".length())
					: texturePath.substring(0, texturePath.length() - ".png".length());
			ResourceLocation baseTextureId = new ResourceLocation(namespace, spriteIdPath);
			ResourceLocation baseTextureResourceId = new ResourceLocation(namespace, texturePath);
			ResourceLocation metadataId = new ResourceLocation(namespace, texturePath + ".mcmeta");
			if (!processedMetadata.add(metadataId)) {
				return;
			}
			try (IResource ignored = resourceManager.getResource(baseTextureResourceId)) {
				// Ignore orphan .mcmeta files; without their texture they cannot define a CTM sprite.
			} catch (FileNotFoundException e) {
				ContinuityClient.LOGGER.debug("Skipping CTM metadata '{}' because its texture is missing", metadataId);
				return;
			} catch (IOException e) {
				ContinuityClient.LOGGER.warn("Unable to check base texture '{}' for CTM metadata", baseTextureResourceId, e);
				return;
			}
			try (IResource resource = resourceManager.getResource(metadataId)) {
				String metadataPackName = resource.getResourcePackName();
				int metadataPackPriority = packPriorities.getOrDefault(metadataPackName, packPriority);
				CtmMcmetaParser.ParseResult result = CtmMcmetaParser.parseDetailed(baseTextureId, resource,
						metadataPackName, metadataPackPriority);
				if (result.invalid()) {
					invalidMetadata.add(metadataId);
				}
				CtmDefinition parsed = result.definition();
				if (parsed == null) {
					return;
				}

				// CTM Vintage proxies to the target texture's metadata; a target with no CTM
				// section uses the default v1 texture definition and still proxies its base sprite.
				if (parsed.getProxy() != null) {
					ResourceLocation proxyId = new ResourceLocation(parsed.getProxy());
					ResourceLocation proxyTextureId = getTextureResourceId(proxyId);
					ResourceLocation proxyMetadataId = new ResourceLocation(proxyId.getNamespace(),
							proxyTextureId.getPath() + ".mcmeta");
					CtmDefinition proxyDef = null;
					try (IResource proxyResource = resourceManager.getResource(proxyMetadataId)) {
						String proxyPackName = proxyResource.getResourcePackName();
						int proxyPackPriority = packPriorities.getOrDefault(proxyPackName, metadataPackPriority);
						CtmMcmetaParser.ParseResult proxyResult = CtmMcmetaParser.parseDetailed(parsed.getResourceId(),
								proxyResource, proxyPackName, proxyPackPriority);
						proxyDef = proxyResult.definition();
						if (proxyDef == null && proxyResult.invalid()) {
							invalidMetadata.add(proxyMetadataId);
						}
						if (proxyDef == null && !proxyResult.invalid()) {
							proxyDef = createDefaultProxyDefinition(parsed.getResourceId(),
									proxyPackName, proxyPackPriority);
						}
					} catch (FileNotFoundException e) {
						proxyDef = createDefaultProxyDefinition(parsed.getResourceId(),
								metadataPackName, metadataPackPriority);
					} catch (Exception e) {
						unresolvedResources.add(proxyMetadataId);
						ContinuityClient.LOGGER.warn("Failed to resolve CTM proxy '" + parsed.getProxy() + "' for '" + baseTextureId + "'", e);
					}
					if (proxyDef != null) {
						CtmMcmetaParser.overrideBaseTexture(proxyDef, proxyId);
						parsed = proxyDef;
					}
				}

				properties.add(parsed);
			} catch (Exception e) {
				unresolvedResources.add(metadataId);
				ContinuityClient.LOGGER.error("Failed to load CTM metadata from '" + namespace + ":" + texturePath + "' in pack '" + packName + "'", e);
			}
		});
	}

	private static CtmDefinition createDefaultProxyDefinition(ResourceLocation baseTextureId, String packName,
			int packPriority) {
		JsonObject defaultCtm = new JsonObject();
		defaultCtm.addProperty("ctm_version", 1);
		return CtmMcmetaParser.parse(baseTextureId, defaultCtm, packName, packPriority);
	}

	private static ResourceLocation getTextureResourceId(ResourceLocation spriteId) {
		String path = spriteId.getPath();
		if (!path.startsWith("textures/")) {
			path = "textures/" + path;
		}
		if (!path.endsWith(".png")) {
			path += ".png";
		}
		return new ResourceLocation(spriteId.getNamespace(), path);
	}

	private static void rememberDirectory(IResourcePack pack, Set<Path> scannedDirectories) {
		if (pack instanceof AbstractResourcePack abstractPack && abstractPack.getResourcePackFile().isDirectory()) {
			scannedDirectories.add(abstractPack.getResourcePackFile().toPath().toAbsolutePath().normalize());
		}
	}

	private static void scanPack(IResourcePack pack, BiConsumer<String, String> consumer) {
		if (!(pack instanceof AbstractResourcePack abstractPack)) {
			ContinuityClient.LOGGER.debug("Skipping non-abstract resource pack '{}' while scanning CTM Mod metadata", pack.getPackName());
			return;
		}

		File file = abstractPack.getResourcePackFile();
		if (file.isDirectory()) {
			scanDirectory(file.toPath(), consumer);
		} else if (file.isFile()) {
			scanZip(file, consumer);
		}
	}

	static void scanDirectory(Path root, BiConsumer<String, String> consumer) {
		boolean namespaceRoot = isExternalResourceRoot(root);
		try (var stream = Files.walk(root)) {
			stream.filter(Files::isRegularFile).forEach(path -> {
				String relative = root.relativize(path).toString().replace('\\', '/');
				ResourcePackPath resourcePath = parseResourcePackPath(relative, namespaceRoot);
				if (resourcePath != null) {
					consumer.accept(resourcePath.namespace(), resourcePath.path());
				}
			});
		} catch (Exception e) {
			ContinuityClient.LOGGER.error("Failed to scan CTM Mod metadata in folder pack '" + root + "'", e);
		}
	}

	static List<Path> externalRoots(Path gameDir, Set<Path> scannedDirectories, boolean includeOverriding) {
		List<Path> roots = new ObjectArrayList<>();
		List<String> folders = includeOverriding ? List.of("resources", "oresources") : List.of("resources");
		for (String folder : folders) {
			Path root = gameDir.resolve(folder);
			if (Files.isDirectory(root) && !scannedDirectories.contains(root.toAbsolutePath().normalize())) {
				roots.add(root);
			}
		}
		return roots;
	}

	private static boolean isExternalResourceRoot(Path root) {
		Path name = root.getFileName();
		return name != null && (name.toString().equals("resources") || name.toString().equals("oresources"));
	}

	private static void scanZip(File file, BiConsumer<String, String> consumer) {
		try (ZipFile zipFile = new ZipFile(file)) {
			Enumeration<? extends ZipEntry> entries = zipFile.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory()) {
					continue;
				}
				ResourcePackPath resourcePath = parseResourcePackPath(entry.getName(), false);
				if (resourcePath != null) {
					consumer.accept(resourcePath.namespace(), resourcePath.path());
				}
			}
		} catch (Exception e) {
			ContinuityClient.LOGGER.error("Failed to scan CTM Mod metadata in zip pack '" + file + "'", e);
		}
	}

	@Nullable
	private static ResourcePackPath parseResourcePackPath(String relative, boolean namespaceRoot) {
		if (!relative.startsWith("assets/") && !namespaceRoot) {
			return null;
		}
		String rest = namespaceRoot ? relative : relative.substring("assets/".length());
		int slash = rest.indexOf('/');
		if (slash <= 0 || slash >= rest.length() - 1) {
			return null;
		}
		String namespace = rest.substring(0, slash);
		String path = rest.substring(slash + 1);
		// Exclude our own reserved directory to avoid double-processing
		if (path.startsWith("optifine/")) {
			return null;
		}
		return new ResourcePackPath(namespace, path);
	}

	private record ResourcePackPath(String namespace, String path) {
	}
}
