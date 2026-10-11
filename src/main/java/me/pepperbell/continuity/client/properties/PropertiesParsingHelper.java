package me.pepperbell.continuity.client.properties;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.apache.commons.io.FilenameUtils;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import me.pepperbell.continuity.client.ContinuityClient;
import me.pepperbell.continuity.client.processor.OrientationMode;
import me.pepperbell.continuity.client.processor.Symmetry;
import me.pepperbell.continuity.client.resource.ResourceRedirectHandler;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;

public final class PropertiesParsingHelper {
	public static final Predicate<IBlockState> EMPTY_BLOCK_STATE_PREDICATE = state -> false;
	private static final Map<String, String> LEGACY_VANILLA_BLOCK_STATES = Map.ofEntries(
			Map.entry("grass_block", "grass"),
			Map.entry("dirt_path", "grass_path"),
			Map.entry("dirt_path_top", "grass_path"),
			Map.entry("bricks", "brick_block"),
			Map.entry("end_stone_bricks", "end_bricks"),
			Map.entry("nether_bricks", "nether_brick"),
			Map.entry("red_nether_bricks", "red_nether_brick"),
			Map.entry("stone_bricks", "stonebrick:variant=stonebrick"),
			Map.entry("mossy_stone_bricks", "stonebrick:variant=mossy_stonebrick"),
			Map.entry("cracked_stone_bricks", "stonebrick:variant=cracked_stonebrick"),
			Map.entry("chiseled_stone_bricks", "stonebrick:variant=chiseled_stonebrick"),
			Map.entry("coarse_dirt", "dirt:variant=coarse_dirt"),
			Map.entry("podzol", "dirt:variant=podzol"),
			Map.entry("granite", "stone:variant=granite"),
			Map.entry("diorite", "stone:variant=diorite"),
			Map.entry("andesite", "stone:variant=andesite"),
			Map.entry("polished_granite", "stone:variant=smooth_granite"),
			Map.entry("polished_diorite", "stone:variant=smooth_diorite"),
			Map.entry("polished_andesite", "stone:variant=smooth_andesite"),
			Map.entry("red_sand", "sand:variant=red_sand"),
			Map.entry("cut_sandstone", "sandstone:variant=smooth_sandstone"),
			Map.entry("smooth_sandstone", "sandstone:variant=smooth_sandstone"),
			Map.entry("chiseled_sandstone", "sandstone:variant=chiseled_sandstone"),
			Map.entry("cut_red_sandstone", "red_sandstone:variant=smooth_red_sandstone"),
			Map.entry("smooth_red_sandstone", "red_sandstone:variant=smooth_red_sandstone"),
			Map.entry("chiseled_red_sandstone", "red_sandstone:variant=chiseled_red_sandstone"),
			Map.entry("snow_block", "snow"),
			Map.entry("nether_quartz_ore", "quartz_ore"),
			Map.entry("prismarine_bricks", "prismarine:variant=prismarine_bricks"),
			Map.entry("oak_log", "log:variant=oak"),
			Map.entry("spruce_log", "log:variant=spruce"),
			Map.entry("birch_log", "log:variant=birch"),
			Map.entry("jungle_log", "log:variant=jungle"),
			Map.entry("acacia_log", "log2:variant=acacia"),
			Map.entry("dark_oak_log", "log2:variant=dark_oak"),
			Map.entry("oak_wood", "log:variant=oak"),
			Map.entry("spruce_wood", "log:variant=spruce"),
			Map.entry("birch_wood", "log:variant=birch"),
			Map.entry("jungle_wood", "log:variant=jungle"),
			Map.entry("acacia_wood", "log2:variant=acacia"),
			Map.entry("dark_oak_wood", "log2:variant=dark_oak"),
			Map.entry("oak_planks", "planks:variant=oak"),
			Map.entry("spruce_planks", "planks:variant=spruce"),
			Map.entry("birch_planks", "planks:variant=birch"),
			Map.entry("jungle_planks", "planks:variant=jungle"),
			Map.entry("acacia_planks", "planks:variant=acacia"),
			Map.entry("dark_oak_planks", "planks:variant=dark_oak"),
			Map.entry("terracotta", "hardened_clay"),
			Map.entry("stone_slab", "stone_slab:variant=stone"),
			Map.entry("cobblestone_slab", "stone_slab:variant=cobblestone"),
			Map.entry("mossy_cobblestone_slab", "stone_slab:variant=cobblestone"),
			Map.entry("brick_slab", "stone_slab:variant=brick"),
			Map.entry("stone_brick_slab", "stone_slab:variant=stone_brick"),
			Map.entry("mossy_stone_brick_slab", "stone_slab:variant=stone_brick"),
			Map.entry("nether_brick_slab", "stone_slab:variant=nether_brick"),
			Map.entry("sandstone_slab", "stone_slab:variant=sandstone"),
			Map.entry("smooth_sandstone_slab", "stone_slab:variant=sandstone"),
			Map.entry("red_sandstone_slab", "stone_slab2:variant=red_sandstone"),
			Map.entry("smooth_red_sandstone_slab", "stone_slab2:variant=red_sandstone"),
			Map.entry("purpur_slab", "purpur_slab"));

	private PropertiesParsingHelper() {
	}

	/** Maps modern vanilla block names to the closest 1.12.2 block state when one exists. */
	public static ResourceLocation resolveLegacyVanillaBlockId(ResourceLocation blockId) {
		if (!blockId.getNamespace().equals("minecraft")) {
			return blockId;
		}
		String legacyState = legacyVanillaBlockState(blockId.getPath());
		return new ResourceLocation(blockId.getNamespace(), legacyState.split(":", 2)[0]);
	}

	private static String legacyVanillaBlockState(String blockState) {
		String[] parts = blockState.split(":");
		String namespace = "minecraft";
		String blockName;
		int propertyStart;
		if (parts.length > 1 && !parts[1].contains("=")) {
			namespace = parts[0];
			blockName = parts[1];
			propertyStart = 2;
		} else {
			blockName = parts[0];
			propertyStart = 1;
		}
		if (!namespace.equals("minecraft")) {
			return blockState;
		}

		String legacyState = LEGACY_VANILLA_BLOCK_STATES.get(blockName);
		if (legacyState == null) {
			legacyState = legacyColorBlockState(blockName);
		}
		if (legacyState == null) {
			return blockState;
		}

		StringBuilder result = new StringBuilder(legacyState);
		for (int i = propertyStart; i < parts.length; i++) {
			String property = parts[i];
			if (property.startsWith("type=") && blockName.endsWith("_slab")) {
				property = "half=" + property.substring("type=".length());
			}
			result.append(':').append(property);
		}
		return result.toString();
	}

	@Nullable
	private static String legacyColorBlockState(String blockName) {
		String[] colors = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
				"silver", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
		for (String color : colors) {
			String legacyColor = color.equals("light_gray") ? "silver" : color;
			if (blockName.equals(color + "_terracotta")) {
				return "stained_hardened_clay:color=" + legacyColor;
			}
			if (blockName.equals(color + "_concrete_powder")) {
				return "concrete_powder:color=" + legacyColor;
			}
		}
		return null;
	}

	@Nullable
	public static Set<ResourceLocation> parseMatchTiles(Properties properties, String propertyKey, ResourceLocation fileLocation, String packId) {
		String matchTilesStr = properties.getProperty(propertyKey);
		if (matchTilesStr == null) {
			return null;
		}

		String[] matchTileStrs = matchTilesStr.trim().split(" ");
		if (matchTileStrs.length != 0) {
			String basePath = FilenameUtils.getPath(fileLocation.getPath());
			ObjectOpenHashSet<ResourceLocation> set = new ObjectOpenHashSet<>();

			for (int i = 0; i < matchTileStrs.length; i++) {
				String matchTileStr = matchTileStrs[i];
				if (matchTileStr.isEmpty()) {
					continue;
				}

				String[] parts = matchTileStr.split(":", 2);
				String namespace = null;
				String path;
				if (parts.length > 1) {
					namespace = parts[0];
					path = parts[1];
				} else {
					path = parts[0];
				}

				if (path.endsWith(".png")) {
					path = path.substring(0, path.length() - 4);
				}

				if (namespace == null) {
					if (path.startsWith("assets/minecraft/")) {
						path = path.substring(17);
					} else if (path.startsWith("./")) {
						// MCPatcher-style relative matchTiles ("matchTiles=./1.png") name a tile
						// inside the properties directory. OptiFine resolves these to the tile's own
						// sprite id (basePath + name), matching CTM tile output; it does NOT infer a
						// vanilla "blocks/<block>" sprite from the ctm/<block>/ path.
						path = basePath + path.substring(2);
					} else if (path.startsWith("~/")) {
						path = "optifine/" + path.substring(2);
					} else if (path.startsWith("/")) {
						path = "optifine/" + path.substring(1);
					}
				}

				if (path.startsWith("textures/")) {
					path = path.substring(9);
				} else if (path.startsWith("optifine/")) {
					path = ResourceRedirectHandler.SPRITE_PATH_START + path.substring(9);
					if (namespace == null) {
						namespace = fileLocation.getNamespace();
					}
				} else if (path.startsWith("mcpatcher/")) {
					// Relative tiles inside an MCPatcher ctm directory are registered in the atlas
					// under the continuity_reserved/ redirect (see BaseCtmProperties.parseTiles), so a
					// relative matchTiles must resolve to the same id to chain onto tile output.
					path = ResourceRedirectHandler.SPRITE_PATH_START + path;
					if (namespace == null) {
						namespace = fileLocation.getNamespace();
					}
				} else if (!path.contains("/")) {
					// 1.12.2 vanilla block textures live under blocks/ (plural)
					path = "blocks/" + path;
				}

				if (namespace == null) {
					namespace = "minecraft";
				}

				set.add(new ResourceLocation(namespace, path));
			}

			set.trim();
			return set;
		}
		return Collections.emptySet();
	}

	@Nullable
	public static Predicate<IBlockState> parseBlockStates(Properties properties, String propertyKey, ResourceLocation fileLocation, String packId) {
		String blockStatesStr = properties.getProperty(propertyKey);
		if (blockStatesStr == null) {
			return null;
		}

		String[] blockStateStrs = blockStatesStr.trim().split(" ");
		if (blockStateStrs.length != 0) {
			ReferenceOpenHashSet<Block> blockSet = new ReferenceOpenHashSet<>();
			Reference2ObjectOpenHashMap<Block, Object2ObjectOpenHashMap<IProperty<?>, ObjectOpenHashSet<Comparable<?>>>> propertyMaps = new Reference2ObjectOpenHashMap<>();

			Block:
			for (int i = 0; i < blockStateStrs.length; i++) {
				String blockStateStr = legacyVanillaBlockState(blockStateStrs[i].trim());
				if (blockStateStr.isEmpty()) {
					continue;
				}

				String[] parts = blockStateStr.split(":");
				ResourceLocation blockId;
				Block block;
				int startIndex;
				if (isNumericBlockId(blockStateStr)) {
					// OptiFine/MCPatcher packs for 1.12.2 commonly use legacy numeric block ids.
					// Resolve them through the same registry used by vanilla's numeric block lookup.
					int numericBlockId = Integer.parseInt(blockStateStr);
					try {
						block = Block.getBlockById(numericBlockId);
					} catch (NumberFormatException e) {
						block = null;
					}
					if (block == null || (numericBlockId != 0 && block == Blocks.AIR)) {
						ContinuityClient.LOGGER.warn("Unknown block id '{}' in '{}' element '{}' at index {} in file '{}' in pack '{}'", numericBlockId, propertyKey, blockStateStr, i, fileLocation, packId);
						continue;
					}
					ResourceLocation registeredId = block == null ? null : Block.REGISTRY.getNameForObject(block);
					blockId = registeredId == null ? new ResourceLocation("minecraft", blockStateStr) : registeredId;
					startIndex = 1;
				} else {
					if (parts.length == 1 || parts[1].contains("=")) {
						blockId = new ResourceLocation(parts[0]);
						startIndex = 1;
					} else {
						blockId = new ResourceLocation(parts[0], parts[1]);
						startIndex = 2;
					}
					block = Block.REGISTRY.getObject(blockId);
				}

				if (block == null || !Block.REGISTRY.containsKey(blockId)) {
					ContinuityClient.LOGGER.warn("Unknown block '" + blockId + "' in '" + propertyKey + "' element '" + blockStateStr + "' at index " + i + " in file '" + fileLocation + "' in pack '" + packId + "'");
					continue;
				}
				if (blockSet.contains(block)) {
					continue;
				}

				if (parts.length > startIndex) {
					Object2ObjectOpenHashMap<IProperty<?>, ObjectOpenHashSet<Comparable<?>>> propertyMap = new Object2ObjectOpenHashMap<>();

					for (int j = startIndex; j < parts.length; j++) {
						String part = parts[j];
						if (part.isEmpty()) {
							continue;
						}

						String[] propertyParts = part.split("=", 2);
						if (propertyParts.length != 2) {
							ContinuityClient.LOGGER.warn("Invalid block property definition for block '" + blockId + "' in '" + propertyKey + "' element '" + blockStateStr + "' at index " + i + " in file '" + fileLocation + "' in pack '" + packId + "'");
							continue Block;
						}

						IProperty<?> property = block.getBlockState().getProperty(propertyParts[0]);
						if (property == null) {
							ContinuityClient.LOGGER.warn("Unknown block property '" + propertyParts[0] + "' for block '" + blockId + "' in '" + propertyKey + "' element '" + blockStateStr + "' at index " + i + " in file '" + fileLocation + "' in pack '" + packId + "'");
							continue Block;
						}

						ObjectOpenHashSet<Comparable<?>> valueSet = propertyMap.computeIfAbsent(property, p -> new ObjectOpenHashSet<>());
						for (String propertyValueStr : propertyParts[1].split(",")) {
							com.google.common.base.Optional<?> optionalValue = property.parseValue(propertyValueStr);
							if (optionalValue.isPresent()) {
								valueSet.add((Comparable<?>) optionalValue.get());
							} else {
								ContinuityClient.LOGGER.warn("Invalid block property value '" + propertyValueStr + "' for property '" + propertyParts[0] + "' for block '" + blockId + "' in '" + propertyKey + "' element '" + blockStateStr + "' at index " + i + " in file '" + fileLocation + "' in pack '" + packId + "'");
								continue Block;
							}
						}
					}

					if (!propertyMap.isEmpty()) {
						Object2ObjectOpenHashMap<IProperty<?>, ObjectOpenHashSet<Comparable<?>>> existingPropertyMap = propertyMaps.get(block);
						if (existingPropertyMap == null) {
							propertyMaps.put(block, propertyMap);
						} else {
							propertyMap.forEach((property, valueSet) -> {
								ObjectOpenHashSet<Comparable<?>> existingValueSet = existingPropertyMap.get(property);
								if (existingValueSet == null) {
									existingPropertyMap.put(property, valueSet);
								} else {
									existingValueSet.addAll(valueSet);
								}
							});
						}
					}
				} else {
					blockSet.add(block);
					propertyMaps.remove(block);
				}
			}

			if (!blockSet.isEmpty() || !propertyMaps.isEmpty()) {
				if (propertyMaps.isEmpty()) {
					if (blockSet.size() == 1) {
						Block block = blockSet.toArray(new Block[0])[0];
						return state -> state.getBlock() == block;
					} else {
						blockSet.trim();
						return state -> blockSet.contains(state.getBlock());
					}
				} else {
					Reference2ReferenceOpenHashMap<Block, Predicate<IBlockState>> predicateMap = new Reference2ReferenceOpenHashMap<>();
					blockSet.forEach(block -> predicateMap.put(block, state -> true));
					propertyMaps.forEach((block, propertyMap) -> {
						ObjectArrayList<Map.Entry<IProperty<?>, ObjectOpenHashSet<Comparable<?>>>> entryList = new ObjectArrayList<>(propertyMap.entrySet());
						entryList.forEach(entry -> entry.getValue().trim());
						predicateMap.put(block, state -> {
							Map<IProperty<?>, Comparable<?>> stateProperties = state.getProperties();
							for (Map.Entry<IProperty<?>, ObjectOpenHashSet<Comparable<?>>> entry : entryList) {
								Comparable<?> targetValue = stateProperties.get(entry.getKey());
								if (targetValue != null && !entry.getValue().contains(targetValue)) {
									return false;
								}
							}
							return true;
						});
					});
					return state -> {
						Predicate<IBlockState> predicate = predicateMap.get(state.getBlock());
						return predicate != null && predicate.test(state);
					};
				}
			}
		}
		return EMPTY_BLOCK_STATE_PREDICATE;
	}

	@Nullable
	public static Symmetry parseSymmetry(Properties properties, String propertyKey, ResourceLocation fileLocation, String packId) {
		String symmetryStr = properties.getProperty(propertyKey);
		if (symmetryStr == null) {
			return null;
		}

		try {
			return Symmetry.valueOf(symmetryStr.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			ContinuityClient.LOGGER.warn("Unknown '" + propertyKey + "' value '" + symmetryStr + "' in file '" + fileLocation + "' in pack '" + packId + "'");
		}
		return null;
	}

	@Nullable
	public static OrientationMode parseOrientationMode(Properties properties, String propertyKey, ResourceLocation fileLocation, String packId) {
		String orientationModeStr = properties.getProperty(propertyKey);
		if (orientationModeStr == null) {
			return null;
		}

		try {
			return OrientationMode.valueOf(orientationModeStr.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			ContinuityClient.LOGGER.warn("Unknown '" + propertyKey + "' value '" + orientationModeStr + "' in file '" + fileLocation + "' in pack '" + packId + "'");
		}
		return null;
	}

	public static boolean parseOptifineOnly(Properties properties, ResourceLocation fileLocation) {
		if (!fileLocation.getNamespace().equals("minecraft")) {
			return false;
		}

		String optifineOnlyStr = properties.getProperty("optifineOnly");
		if (optifineOnlyStr == null) {
			return false;
		}

		return Boolean.parseBoolean(optifineOnlyStr.trim());
	}

	private static boolean isNumericBlockId(String value) {
		if (value.isEmpty()) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			if (!Character.isDigit(value.charAt(i))) {
				return false;
			}
		}
		return true;
	}
}
