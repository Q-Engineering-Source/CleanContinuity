package me.pepperbell.continuity.client.ctm;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;

import java.util.Map;
import java.util.Locale;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import me.pepperbell.continuity.client.ContinuityClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

/**
 * Registry of custom CTM logic definitions ({@code ctm.json} + {@code ctm_logic/*.json}),
 * keyed by their namespaced id (e.g. {@code ctm:optifine_full_3tile}).
 * <p>
 * On reload, every pack domain's {@code assets/<ns>/ctm.json} is read; each listed logic loads
 * {@code assets/<ns>/ctm_logic/<name>.json}, is baked into a {@link CtmCustomLogic} and
 * registered under {@code <ns>:<name>}. {@code CtmMcmetaParser} then resolves mcmeta
 * {@code "type"} strings that reference these ids.
 */
public final class CtmDefinitionManager {
	private static final Map<String, CtmCustomLogic> LOGICS = new Object2ObjectOpenHashMap<>();

	private CtmDefinitionManager() {
	}

	public static void reload() {
		IResourceManager resourceManager = Minecraft.getMinecraft().getResourceManager();
		clear();
		for (String domain : resourceManager.getResourceDomains()) {
			try {
				for (IResource ctmFile : resourceManager.getAllResources(new ResourceLocation(domain, "ctm.json"))) {
					try (IResource resource = ctmFile;
							InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
						JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
						loadLogics(domain, json, resourceManager);
					} catch (Exception e) {
						ContinuityClient.LOGGER.warn("Invalid ctm.json in domain '{}'", domain, e);
					}
				}
			} catch (IOException ignored) {
				// no ctm.json in this domain
			}
		}
	}

	private static void loadLogics(String domain, JsonObject ctmFile, IResourceManager resourceManager) {
		if (ctmFile.has("logics") && ctmFile.get("logics").isJsonArray()) {
			for (var element : ctmFile.getAsJsonArray("logics")) {
				String logicName = element.getAsString();
				try {
					try (IResource resource = resourceManager.getResource(new ResourceLocation(domain, "ctm_logic/" + logicName + ".json"));
							InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
						JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
						CtmLogicDefinition def = CtmLogicDefinition.fromJson(json);
						CtmCustomLogic logic = CtmLogicBakery.bake(def);
						String resourcePath = resource.getResourceLocation().getPath();
						String canonicalName = resourcePath.substring("ctm_logic/".length(), resourcePath.length() - ".json".length());
						String id = resource.getResourceLocation().getNamespace() + ":" + canonicalName;
						registerLogic(id, logic);
						ContinuityClient.LOGGER.debug("Registered CTM logic '{}' with {} positions", id, def.positions.size());
					}
				} catch (Exception e) {
					ContinuityClient.LOGGER.error("Failed to load CTM logic '" + domain + ":ctm_logic/" + logicName + ".json'", e);
				}
			}
		}
	}

	public static void registerLogic(String id, CtmCustomLogic logic) {
		LOGICS.put(id.toLowerCase(Locale.ROOT), logic);
	}

	@Nullable
	public static CtmCustomLogic getLogic(String id) {
		return LOGICS.get(id.toLowerCase(Locale.ROOT));
	}

	public static void clear() {
		LOGICS.clear();
	}

	public static boolean isEmpty() {
		return LOGICS.isEmpty();
	}
}
