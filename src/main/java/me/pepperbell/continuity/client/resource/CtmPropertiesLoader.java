package me.pepperbell.continuity.client.resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import me.pepperbell.continuity.api.client.CachingPredicates;
import me.pepperbell.continuity.api.client.CtmLoader;
import me.pepperbell.continuity.api.client.CtmLoaderRegistry;
import me.pepperbell.continuity.api.client.CtmProperties;
import me.pepperbell.continuity.api.client.QuadProcessor;
import me.pepperbell.continuity.client.ContinuityClient;
import me.pepperbell.continuity.client.model.QuadProcessors;
import me.pepperbell.continuity.client.properties.overlay.StandardOverlayCtmProperties;
import me.pepperbell.continuity.client.util.biome.BiomeHolderManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.AbstractResourcePack;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.FMLClientHandler;

public class CtmPropertiesLoader {
	private final IResourceManager resourceManager;
	private final List<LoadingContainer<?>> containers = new ObjectArrayList<>();
	private final Set<ResourceLocation> blockAtlasSpriteDependencies = new ObjectOpenHashSet<>();
	private final Set<ResourceLocation> missingBlockAtlasSpriteDependencies = new ObjectOpenHashSet<>();

	private CtmPropertiesLoader(IResourceManager resourceManager) {
		this.resourceManager = resourceManager;
	}

	public static LoadingResult loadAllWithState() {
		BiomeHolderManager.clearCache();
		LoadingResult result = loadAll();
		BiomeHolderManager.refreshHolders();
		return result;
	}

	public static LoadingResult loadAll() {
		return new CtmPropertiesLoader(Minecraft.getMinecraft().getResourceManager()).loadAllPacks();
	}

	private LoadingResult loadAllPacks() {
		int packPriority = 0;
		Set<String> seenPacks = new HashSet<>();

		for (IResourcePack pack : FMLClientHandler.instance().getResourcePackList()) {
			if (seenPacks.add(pack.getPackName())) {
				loadAll(pack, packPriority++);
			}
		}

		ResourcePackRepository repository = Minecraft.getMinecraft().getResourcePackRepository();
		for (ResourcePackRepository.Entry entry : repository.getRepositoryEntries()) {
			if (seenPacks.add(entry.getResourcePackName())) {
				loadAll(entry.getResourcePack(), packPriority++);
			}
		}

		IResourcePack serverPack = repository.getServerResourcePack();
		if (serverPack != null && seenPacks.add(serverPack.getPackName())) {
			loadAll(serverPack, packPriority++);
		}

		containers.sort(Comparator.reverseOrder());
		ContinuityClient.LOGGER.debug("Loaded {} CTM property containers from {} packs", containers.size(), seenPacks.size());
		if (!missingBlockAtlasSpriteDependencies.isEmpty()) {
			ContinuityClient.LOGGER.debug("Skipped {} missing CTM tile sprites", missingBlockAtlasSpriteDependencies.size());
		}
		return new LoadingResult(containers, blockAtlasSpriteDependencies);
	}

	private void loadAll(IResourcePack pack, int packPriority) {
		int[] propertyCount = new int[1];
		scanPack(pack, (namespace, path) -> {
			if (!path.endsWith(".properties")) {
				return;
			}
			propertyCount[0]++;
			try (InputStream stream = pack.getInputStream(new ResourceLocation(namespace, path))) {
				Properties properties = new Properties();
				properties.load(stream);
				load(properties, new ResourceLocation(namespace, path), pack, packPriority);
			} catch (Exception e) {
				ContinuityClient.LOGGER.error("Failed to load CTM properties from file '" + namespace + ":" + path + "' in pack '" + pack.getPackName() + "'", e);
			}
		});
		ContinuityClient.LOGGER.debug("Scanned {} CTM property files in pack '{}'", propertyCount[0], pack.getPackName());
	}

	private void load(Properties properties, ResourceLocation resourceId, IResourcePack pack, int packPriority) {
		String method = properties.getProperty("method", "ctm").trim();
		CtmLoader<?> loader = CtmLoaderRegistry.get().getLoader(method);
		if (loader != null) {
			load(loader, properties, resourceId, pack, packPriority, method);
		} else {
			ContinuityClient.LOGGER.error("Unknown 'method' value '" + method + "' in file '" + resourceId + "' in pack '" + pack.getPackName() + "'");
		}
	}

	private <T extends CtmProperties> void load(CtmLoader<T> loader, Properties properties, ResourceLocation resourceId, IResourcePack pack, int packPriority, String method) {
		T ctmProperties = loader.getPropertiesFactory().createProperties(properties, resourceId, pack, packPriority, resourceManager, method);
		if (ctmProperties != null) {
			LoadingContainer<T> container = new LoadingContainer<>(loader, ctmProperties);
			containers.add(container);
			for (ResourceLocation spriteId : ctmProperties.getSpriteDependencies()) {
				if (hasSpriteResource(spriteId)) {
					blockAtlasSpriteDependencies.add(spriteId);
				} else {
					missingBlockAtlasSpriteDependencies.add(spriteId);
				}
			}
		}
	}

	private boolean hasSpriteResource(ResourceLocation spriteId) {
		String spritePath = spriteId.getPath();
		if (!spritePath.endsWith(".png")) {
			spritePath += ".png";
		}
		ResourceLocation atlasResource = new ResourceLocation(spriteId.getNamespace(), "textures/" + spritePath);
		ResourceLocation redirected = ResourceRedirectHandler.redirect(atlasResource);
		String resourcePath = redirected.getPath();
		if (!resourcePath.startsWith("textures/")
				&& !resourcePath.startsWith("optifine/")
				&& !resourcePath.startsWith("mcpatcher/")) {
			resourcePath = "textures/" + resourcePath;
		}
		try (var ignored = resourceManager.getResource(new ResourceLocation(redirected.getNamespace(), resourcePath))) {
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private static void scanPack(IResourcePack pack, ScanConsumer consumer) {
		if (!(pack instanceof AbstractResourcePack abstractPack)) {
			ContinuityClient.LOGGER.debug("Skipping non-abstract resource pack '{}' while scanning CTM properties", pack.getPackName());
			return;
		}

		File file = abstractPack.getResourcePackFile();
		if (file.isDirectory()) {
			scanDirectory(file.toPath(), consumer);
		} else if (file.isFile()) {
			scanZip(file, consumer);
		}
	}

	private static void scanDirectory(Path root, ScanConsumer consumer) {
		try (var stream = Files.walk(root)) {
			stream.filter(Files::isRegularFile).forEach(path -> {
				String relative = root.relativize(path).toString().replace('\\', '/');
				ResourcePackPath resourcePath = parseResourcePackPath(relative);
				if (resourcePath != null) {
					consumer.accept(resourcePath.namespace(), resourcePath.path());
				}
			});
		} catch (Exception e) {
			ContinuityClient.LOGGER.error("Failed to scan CTM properties in folder pack '" + root + "'", e);
		}
	}

	private static void scanZip(File file, ScanConsumer consumer) {
		try (ZipFile zipFile = new ZipFile(file)) {
			Enumeration<? extends ZipEntry> entries = zipFile.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory()) {
					continue;
				}
				ResourcePackPath resourcePath = parseResourcePackPath(entry.getName());
				if (resourcePath != null) {
					consumer.accept(resourcePath.namespace(), resourcePath.path());
				}
			}
		} catch (Exception e) {
			ContinuityClient.LOGGER.error("Failed to scan CTM properties in zip pack '" + file + "'", e);
		}
	}

	@Nullable
	private static ResourcePackPath parseResourcePackPath(String relative) {
		if (!relative.startsWith("assets/")) {
			return null;
		}
		String rest = relative.substring("assets/".length());
		int slash = rest.indexOf('/');
		if (slash <= 0 || slash >= rest.length() - 1) {
			return null;
		}
		String namespace = rest.substring(0, slash);
		String path = rest.substring(slash + 1);
		if (!path.startsWith("optifine/ctm/") && !path.startsWith("mcpatcher/ctm/")) {
			return null;
		}
		return new ResourcePackPath(namespace, path);
	}

	private record ResourcePackPath(String namespace, String path) {
	}

	@FunctionalInterface
	private interface ScanConsumer {
		void accept(String namespace, String path);
	}

	private record LoadingContainer<T extends CtmProperties>(CtmLoader<T> loader, T properties) implements Comparable<LoadingContainer<?>> {
		public QuadProcessors.ProcessorHolder toProcessorHolder(Function<ResourceLocation, TextureAtlasSprite> spriteGetter) {
			QuadProcessor processor = loader.getProcessorFactory().createProcessor(properties, spriteGetter);
			CachingPredicates predicates = loader.getPredicatesFactory().createPredicates(properties, spriteGetter);
			return new QuadProcessors.ProcessorHolder(processor, predicates);
		}

		@Override
		public int compareTo(LoadingContainer<?> o) {
			return properties.compareTo(o.properties);
		}
	}

	public static class LoadingResult {
		private final List<LoadingContainer<?>> containers;
		private final Set<ResourceLocation> blockAtlasSpriteDependencies;

		private LoadingResult(List<LoadingContainer<?>> containers, Set<ResourceLocation> blockAtlasSpriteDependencies) {
			this.containers = containers;
			this.blockAtlasSpriteDependencies = blockAtlasSpriteDependencies;
		}

		public List<QuadProcessors.ProcessorHolder> createProcessorHolders(Function<ResourceLocation, TextureAtlasSprite> spriteGetter) {
			List<QuadProcessors.ProcessorHolder> processorHolders = new ObjectArrayList<>();
			for (LoadingContainer<?> container : containers) {
				processorHolders.add(container.toProcessorHolder(spriteGetter));
			}
			return processorHolders;
		}

		public Set<ResourceLocation> getBlockAtlasSpriteDependencies() {
			return blockAtlasSpriteDependencies;
		}

		public List<StandardOverlayCtmProperties> getOverlayProperties() {
			List<StandardOverlayCtmProperties> overlays = new ObjectArrayList<>();
			for (LoadingContainer<?> container : containers) {
				if (container.properties() instanceof StandardOverlayCtmProperties properties) {
					overlays.add(properties);
				}
			}
			return overlays;
		}
	}
}
