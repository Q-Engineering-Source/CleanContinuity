package me.pepperbell.continuity.client.ctm;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import me.pepperbell.continuity.client.ContinuityClient;
import me.pepperbell.continuity.client.config.ContinuityConfig;
import me.pepperbell.continuity.client.processor.overlay.StandardOverlayQuadProcessor;
import me.pepperbell.continuity.client.properties.overlay.StandardOverlayCtmProperties;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;

/** Routes CTM metadata and OptiFine overlay quads through the block's normal layer checks. */
public final class CtmRenderLayerRouter {
	private static volatile Snapshot snapshot = new Snapshot(Map.of(), List.of(), Set.of());
	private static final Map<IBlockState, ModelLayers> modelLayers = new ConcurrentHashMap<>();
	private static final Set<BlockLayer> forcedLayers = ConcurrentHashMap.newKeySet();
	private static final Set<BlockLayer> forcedOverlayLayers = ConcurrentHashMap.newKeySet();
	private static final ThreadLocal<Boolean> scanningModel = ThreadLocal.withInitial(() -> false);

	private CtmRenderLayerRouter() {
	}

	public static void reload(List<CtmDefinition> definitions) {
		reload(definitions, List.of());
	}

	public static void reload(List<CtmDefinition> definitions, List<StandardOverlayCtmProperties> overlayProperties) {
		Map<String, LayerRule> next = new HashMap<>();
		for (CtmDefinition definition : definitions) {
			if (definition.getLayer() != null) {
				next.putIfAbsent(definition.getResourceId().toString(),
						new LayerRule(definition.getLayer(), definition.hasEmissiveFallback()));
			}
		}
		modelLayers.clear();
		forcedLayers.clear();
		forcedOverlayLayers.clear();
		Set<BlockRenderLayer> targetLayers = EnumSet.noneOf(BlockRenderLayer.class);
		for (LayerRule rule : next.values()) {
			targetLayers.add(rule.layer());
		}
		for (StandardOverlayCtmProperties properties : overlayProperties) {
			targetLayers.add(properties.getLayer());
		}
		snapshot = new Snapshot(Map.copyOf(next), List.copyOf(overlayProperties), Set.copyOf(targetLayers));
	}

	public static boolean allowAdditionalLayer(IBlockState state, BlockRenderLayer layer) {
		boolean metadataLayersEnabled = ContinuityConfig.INSTANCE.ctmModTextures.get()
				&& !snapshot.spriteLayers().isEmpty();
		boolean overlayLayersEnabled = !snapshot.overlayProperties().isEmpty();
		if (!ContinuityConfig.INSTANCE.connectedTextures.get()
				|| (!metadataLayersEnabled && !overlayLayersEnabled)
				|| !snapshot.targetLayers().contains(layer) || scanningModel.get()) {
			return false;
		}
		ModelLayers layers = modelLayers.computeIfAbsent(state, CtmRenderLayerRouter::findModelLayers);
		if (!layers.layers().contains(layer)) {
			return false;
		}
		BlockLayer key = new BlockLayer(state, layer);
		forcedLayers.add(key);
		if (layers.overlayLayers().contains(layer)) {
			forcedOverlayLayers.add(key);
		}
		return true;
	}

	public static boolean isRoutedLayer(IBlockState state, BlockRenderLayer layer) {
		return forcedLayers.contains(new BlockLayer(state, layer));
	}

	public static boolean isOverlayRoutedLayer(IBlockState state, BlockRenderLayer layer) {
		return forcedOverlayLayers.contains(new BlockLayer(state, layer));
	}

	public static boolean shouldRender(TextureAtlasSprite sprite, BlockRenderLayer layer, boolean routedLayer) {
		LayerRule rule = sprite == null ? null : snapshot.spriteLayers().get(sprite.getIconName());
		return rule == null ? !routedLayer : rule.layer() == layer || (rule.emissiveFallback() && !routedLayer);
	}

	public static boolean shouldProcessWrappedOverlay(TextureAtlasSprite sprite, BlockRenderLayer layer,
			boolean routedLayer) {
		return shouldRender(sprite, layer, routedLayer);
	}

	public static boolean shouldGenerateSuffixOverlay(TextureAtlasSprite sprite) {
		return sprite != null && !snapshot.spriteLayers().containsKey(sprite.getIconName());
	}

	public static boolean shouldFullbrightEmissiveFallback(TextureAtlasSprite sprite, boolean routedLayer) {
		LayerRule rule = sprite == null ? null : snapshot.spriteLayers().get(sprite.getIconName());
		return rule != null && rule.emissiveFallback() && !routedLayer;
	}

	private static ModelLayers findModelLayers(IBlockState state) {
		EnumSet<BlockRenderLayer> found = EnumSet.noneOf(BlockRenderLayer.class);
		EnumSet<BlockRenderLayer> overlayFound = EnumSet.noneOf(BlockRenderLayer.class);
		BlockRenderLayer previousLayer = MinecraftForgeClient.getRenderLayer();
		scanningModel.set(true);
		try {
			IBakedModel model = Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes().getModelForState(state);
			if (model == null) {
				return new ModelLayers(Set.of(), Set.of());
			}
			Set<BlockRenderLayer> sourceLayers = snapshot.overlayProperties().isEmpty()
					? snapshot.targetLayers()
					: EnumSet.allOf(BlockRenderLayer.class);
			for (BlockRenderLayer candidate : sourceLayers) {
				ForgeHooksClient.setRenderLayer(candidate);
				for (EnumFacing face : EnumFacing.VALUES) {
					collectLayers(model.getQuads(state, face, 0), state, found, overlayFound);
				}
				collectLayers(model.getQuads(state, null, 0), state, found, overlayFound);
			}
		} catch (RuntimeException e) {
			ContinuityClient.LOGGER.warn("Could not inspect block model layers for CTM or overlays on '{}'", state, e);
		} finally {
			ForgeHooksClient.setRenderLayer(previousLayer);
			scanningModel.remove();
		}
		return new ModelLayers(Set.copyOf(found), Set.copyOf(overlayFound));
	}

	private static void collectLayers(List<BakedQuad> quads, IBlockState state,
			Set<BlockRenderLayer> found, Set<BlockRenderLayer> overlayFound) {
		for (BakedQuad quad : quads) {
			TextureAtlasSprite sprite = quad.getSprite();
			if (sprite == null) {
				continue;
			}
			LayerRule rule = snapshot.spriteLayers().get(sprite.getIconName());
			if (rule != null) {
				found.add(rule.layer());
			}
			for (StandardOverlayCtmProperties overlay : snapshot.overlayProperties()) {
				if (overlay.matches(state, sprite) && StandardOverlayQuadProcessor.supportsOverlay(quad, state)) {
					found.add(overlay.getLayer());
					overlayFound.add(overlay.getLayer());
				}
			}
		}
	}

	private record BlockLayer(IBlockState state, BlockRenderLayer layer) {
	}

	private record ModelLayers(Set<BlockRenderLayer> layers, Set<BlockRenderLayer> overlayLayers) {
	}

	private record LayerRule(BlockRenderLayer layer, boolean emissiveFallback) {
	}

	private record Snapshot(Map<String, LayerRule> spriteLayers,
			List<StandardOverlayCtmProperties> overlayProperties, Set<BlockRenderLayer> targetLayers) {
	}
}
