package me.pepperbell.continuity.client.ctm;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import me.pepperbell.continuity.client.ContinuityClient;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.JsonUtils;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;

/**
 * Parses the {@code "ctm"} section of a texture's {@code .png.mcmeta} into a
 * {@link CtmDefinition}. Mirrors the CTM Mod format's v1 metadata (and the namespaced custom-logic
 * type reference) semantics.
 */
public final class CtmMcmetaParser {
	public static final String SECTION_NAME = "ctm";

	private CtmMcmetaParser() {
	}

	/**
	 * Reads the mcmeta of the given texture resource and parses its {@code "ctm"} section.
	 *
	 * @return the parsed properties, or {@code null} if the resource has no ctm section
	 */
	@Nullable
	public static CtmDefinition parse(ResourceLocation baseTextureId, IResource resource, String packId, int packPriority) {
		return parseDetailed(baseTextureId, resource, packId, packPriority).definition();
	}

	public static ParseResult parseDetailed(ResourceLocation baseTextureId, IResource resource, String packId, int packPriority) {
		try {
			JsonObject mcmeta = readMcmeta(resource);
			if (mcmeta == null) {
				// A non-object .mcmeta cannot contain a CTM section. It is not a CTM definition.
				return new ParseResult(null, false);
			}
			JsonElement ctmElement = mcmeta.get(SECTION_NAME);
			if (ctmElement == null) {
				return new ParseResult(null, false);
			}
			if (!ctmElement.isJsonObject()) {
				return new ParseResult(null, false);
			}
			JsonObject ctm = ctmElement.getAsJsonObject();
			JsonElement version = ctm.get("ctm_version");
			if (version != null && (!version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
					|| version.getAsInt() != 1)) {
				// CTM Vintage returns no CTM section for an unsupported or non-numeric version.
				return new ParseResult(null, false);
			}
			CtmDefinition definition = parse(baseTextureId, ctm, packId, packPriority);
			return new ParseResult(definition, definition == null);
		} catch (JsonParseException | IOException e) {
			ContinuityClient.LOGGER.error("Failed to parse CTM metadata of texture '" + baseTextureId + "' in pack '" + packId + "'", e);
			return new ParseResult(null, true);
		}
	}

	public record ParseResult(@Nullable CtmDefinition definition, boolean invalid) {
	}

	@Nullable
	private static JsonObject readMcmeta(IResource resource) throws IOException {
		// Re-read the stream (a resource's getInputStream may only be usable once in some pack impls)
		try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (root == null || !root.isJsonObject()) {
				ContinuityClient.LOGGER.debug("Ignoring non-object mcmeta for resource '{}'", resource.getResourceLocation());
				return null;
			}
			return root.getAsJsonObject();
		}
	}

	/**
	 * Parses a ctm section JSON object into properties.
	 *
	 * @param baseTextureId the texture whose mcmeta this is (sprite index 0)
	 */
	public static CtmDefinition parse(ResourceLocation baseTextureId, JsonObject ctm, String packId, int packPriority) {
		CtmDefinition properties = new CtmDefinition(baseTextureId, packId, packPriority);

		Integer version = getVersion(ctm);
		if (version == null || version != 1) {
			ContinuityClient.LOGGER.warn("Unsupported ctm_version " + version + " in mcmeta of '" + baseTextureId + "'; treating as plain texture");
			return null;
		}

		if (ctm.has("proxy") && ctm.get("proxy").isJsonPrimitive()
				&& ctm.getAsJsonPrimitive("proxy").isString()) {
			JsonElement proxyEle = ctm.get("proxy");
			properties.proxy = proxyEle.getAsString();
			// Proxy may not combine with other fields (per format spec); ignore extras beyond version.
			finalizeSprites(properties);
			return properties;
		}

		if (ctm.has("type")) {
			JsonElement typeEle = ctm.get("type");
			if (typeEle.isJsonPrimitive() && typeEle.getAsJsonPrimitive().isString()) {
				String typeStr = typeEle.getAsString();
				CtmType type = CtmType.fromId(typeStr);
				if (type != null) {
					properties.type = type;
				} else {
					// Namespaced type: custom logic reference (e.g. "ctm:optifine_full_3tile")
					CtmCustomLogic logic = CtmDefinitionManager.getLogic(typeStr);
					if (logic != null) {
						properties.logic = logic;
					} else {
						ContinuityClient.LOGGER.warn("Unknown CTM type '" + typeStr + "' in mcmeta of '" + baseTextureId + "'");
						return null;
					}
				}
			}
		}

		if (ctm.has("layer")) {
			JsonElement layerEle = ctm.get("layer");
			if (layerEle.isJsonPrimitive() && layerEle.getAsJsonPrimitive().isString()) {
				try {
					properties.layer = BlockRenderLayer.valueOf(layerEle.getAsString());
				} catch (IllegalArgumentException e) {
					throw new JsonParseException("Invalid block layer given: " + layerEle, e);
				}
			}
		}

		if (ctm.has("textures")) {
			JsonElement texturesEle = ctm.get("textures");
			if (texturesEle.isJsonArray()) {
				List<ResourceLocation> additional = new ArrayList<>();
				for (JsonElement e : texturesEle.getAsJsonArray()) {
					if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
						throw new JsonParseException("Texture entries must be resource location strings");
					}
					additional.add(new ResourceLocation(e.getAsString()));
				}
				properties.additionalTextures = additional.toArray(new ResourceLocation[0]);
			}
		}

		if (ctm.has("extra") && ctm.get("extra").isJsonObject()) {
			JsonObject extra = ctm.getAsJsonObject("extra");
			properties.extraData = extra;
			properties.emissiveFallback = baseTextureId.getPath().endsWith("_e")
					&& JsonUtils.getBoolean(extra, "emissive_fallback", false);
			if (supportsConnectionOptions(properties)) {
				properties.ignoreStates = JsonUtils.getBoolean(extra, "ignore_states", false);
				properties.useActualState = JsonUtils.getBoolean(extra, "use_actual_state", false);
				if (extra.has("connect_inside")) {
					properties.connectInside = JsonUtils.getBoolean(extra, "connect_inside", false);
				}
				properties.connectToDefined = extra.has("connect_to");
				if (properties.connectToDefined) {
					properties.connectToPredicate = CtmBlockstatePredicateParser.parse(extra.get("connect_to"));
				}
			}
			parseLight(properties, extra);
			if (properties.getType() == CtmType.RANDOM || properties.getType() == CtmType.PATTERN) {
				parseMap(properties, extra);
			}
		}

		finalizeSprites(properties);
		return properties;
	}

	private static void parseLight(CtmDefinition properties, JsonObject extra) {
		JsonElement light = extra.get("light");
		if (light == null) {
			return;
		}
		if (light.isJsonPrimitive()) {
			properties.hasLight = true;
			int value = parseLightValue(light);
			properties.blocklight = value;
			properties.skylight = value;
		} else if (light.isJsonObject()) {
			properties.hasLight = true;
			JsonObject lightObj = light.getAsJsonObject();
			properties.blocklight = parseLightValue(lightObj.get("block"));
			properties.skylight = parseLightValue(lightObj.get("sky"));
		}
	}

	private static boolean supportsConnectionOptions(CtmDefinition properties) {
		if (properties.getLogic() != null) {
			return true;
		}
		return switch (properties.getType()) {
			case CTM, SCTM, HORIZONTAL, VERTICAL, EDGES, EDGES_FULL -> true;
			default -> false;
		};
	}

	private static void parseMap(CtmDefinition properties, JsonObject extra) {
		if (extra.has("width") && extra.has("height")) {
			properties.mapWidth = JsonUtils.getInt(extra, "width", 2);
			properties.mapHeight = JsonUtils.getInt(extra, "height", 2);
		} else if (extra.has("size")) {
			int size = JsonUtils.getInt(extra, "size", 2);
			properties.mapWidth = size;
			properties.mapHeight = size;
		}
		if (properties.mapWidth <= 0 || properties.mapHeight <= 0) {
			throw new JsonParseException("Texture map dimensions must be positive");
		}
		properties.mapXOffset = JsonUtils.getInt(extra, "x_offset", 0);
		properties.mapYOffset = JsonUtils.getInt(extra, "y_offset", 0);
	}

	private static int parseLightValue(@Nullable JsonElement data) {
		if (data != null && data.isJsonPrimitive() && data.getAsJsonPrimitive().isNumber()) {
			return MathHelper.clamp(data.getAsInt(), 0, 15);
		}
		return 0;
	}

	private static void finalizeSprites(CtmDefinition properties) {
		List<ResourceLocation> spriteIds = new ObjectArrayList<>(properties.additionalTextures.length + 1);
		spriteIds.add(properties.resourceId);
		Collections.addAll(spriteIds, properties.additionalTextures);
		properties.spriteIds = spriteIds;
		Set<ResourceLocation> dependencies = new ObjectOpenHashSet<>();
		dependencies.addAll(spriteIds);
		if (properties.proxy != null) {
			dependencies.add(new ResourceLocation(properties.proxy));
		}
		properties.spriteDependencies = dependencies;
	}

	@Nullable
	private static Integer getVersion(JsonObject ctm) {
		JsonElement version = ctm.get("ctm_version");
		if (version != null && version.isJsonPrimitive() && version.getAsJsonPrimitive().isNumber()) {
			return version.getAsInt();
		}
		if (version == null) {
			throw new JsonParseException("Found ctm section without ctm_version");
		}
		return null;
	}

	/**
	 * For proxy resolution: replaces the sprite-0 (base) of a proxy-target definition with the
	 * proxy target itself. The definition's {@code resourceId} (used for sprite matching) is
	 * unchanged (still the actual base texture), but rendering uses the proxy target's sprite.
	 */
	public static void overrideBaseTexture(CtmDefinition definition, ResourceLocation proxyTarget) {
		if (definition.spriteIds == null || definition.spriteIds.isEmpty()) {
			return;
		}
		definition.spriteIds = new ObjectArrayList<>(definition.spriteIds);
		definition.spriteIds.set(0, proxyTarget);
		definition.spriteDependencies = new ObjectOpenHashSet<>(definition.spriteIds);
	}
}
