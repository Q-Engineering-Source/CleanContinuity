package me.pepperbell.continuity.client.resource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import me.pepperbell.continuity.client.ctm.CtmDefinition;
import me.pepperbell.continuity.client.ctm.CtmMcmetaLoader;
import me.pepperbell.continuity.client.ctm.CtmModLoader;
import me.pepperbell.continuity.client.ctm.CtmRenderLayerRouter;
import me.pepperbell.continuity.client.config.ContinuityConfig;
import me.pepperbell.continuity.impl.client.EmissiveSpriteApiImpl;
import me.pepperbell.continuity.client.ContinuityClient;
import me.pepperbell.continuity.client.model.QuadProcessors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class ContinuityTextureEvents {
	private CtmPropertiesLoader.LoadingResult lastResult;

	@SubscribeEvent
	public void onTextureStitchPre(TextureStitchEvent.Pre event) {
		CtmRenderLayerRouter.reload(List.of(), List.of());
		EmissiveSuffixLoader.load(Minecraft.getMinecraft().getResourceManager());
		EmissiveSpriteApiImpl.INSTANCE.clear();
		lastResult = CtmPropertiesLoader.loadAll();
		TextureMap textureMap = event.getMap();
		ContinuityClient.LOGGER.debug("Registering {} redirected CTM sprite dependencies", lastResult.getBlockAtlasSpriteDependencies().size());
		for (ResourceLocation spriteId : lastResult.getBlockAtlasSpriteDependencies()) {
			textureMap.setTextureEntry(new RedirectedTextureAtlasSprite(spriteId));
		}

		// CTM Mod format: register additional textures (base + sheet) into the block atlas.
		// CTM texture paths are vanilla block-atlas paths (e.g. "minecraft:blocks/glass-ctm"),
		// loaded from textures/<path>.png, so the vanilla registerSprite path is used.
		if (ContinuityConfig.INSTANCE.ctmModTextures.get()) {
			List<CtmDefinition> ctmDefinitions = CtmMcmetaLoader.loadAll();
			String emissiveSuffix = EmissiveSuffixLoader.getEmissiveSuffix();
			for (CtmDefinition definition : ctmDefinitions) {
				for (ResourceLocation spriteId : definition.getSpriteDependencies()) {
					if (emissiveSuffix != null && !emissiveSuffix.isEmpty()
							&& spriteId.getPath().endsWith(emissiveSuffix)
							&& !EmissiveSuffixLoader.hasTexture(Minecraft.getMinecraft().getResourceManager(), spriteId)) {
						continue;
					}
					// registerSprite is idempotent for already-registered sprites
					textureMap.registerSprite(spriteId);
				}
			}
			ContinuityClient.LOGGER.debug("Registered CTM Mod sprite dependencies from {} definitions", ctmDefinitions.size());
		}
	}

	@SubscribeEvent
	public void onTextureStitchPost(TextureStitchEvent.Post event) {
		if (lastResult == null) {
			lastResult = CtmPropertiesLoader.loadAll();
		}
		TextureMap textureMap = Minecraft.getMinecraft().getTextureMapBlocks();
		Function<ResourceLocation, TextureAtlasSprite> spriteGetter = id -> textureMap.getAtlasSprite(id.toString());
		List<QuadProcessors.ProcessorHolder> processorHolders = lastResult.createProcessorHolders(spriteGetter);
		ContinuityClient.LOGGER.debug("Reloaded {} CTM processor holders", processorHolders.size());
		int bloomDefinitions = 0;
		int ctmDefinitionsLoaded = 0;
		List<CtmDefinition> ctmDefinitions = List.of();

		// CTM Mod format definitions (texture-mcmeta driven)
		if (ContinuityConfig.INSTANCE.ctmModTextures.get()) {
			ctmDefinitions = CtmMcmetaLoader.loadAll();
			ctmDefinitionsLoaded = ctmDefinitions.size();
			for (CtmDefinition definition : ctmDefinitions) {
				TextureAtlasSprite stitched = textureMap.mapUploadedSprites.get(definition.getResourceId().toString());
				if (definition.getLayer() != null && definition.getLayer().name().equals("BLOOM")
						&& stitched != null && stitched != textureMap.getMissingSprite()) {
					bloomDefinitions++;
				}
				CtmModLoader loader = new CtmModLoader(definition);
				QuadProcessors.ProcessorHolder holder = new QuadProcessors.ProcessorHolder(
						loader.getProcessorFactory().createProcessor(definition, spriteGetter),
						loader.getPredicatesFactory().createPredicates(definition, spriteGetter));
				processorHolders.add(holder);
			}
			ContinuityClient.LOGGER.debug("Reloaded {} CTM Mod processor holders", ctmDefinitions.size());
		}
		CtmRenderLayerRouter.reload(ctmDefinitions, lastResult.getOverlayProperties());

		QuadProcessors.reload(processorHolders);
		String suffix = EmissiveSuffixLoader.getEmissiveSuffix();
		int emissivePairs = 0;
		if (suffix != null && !suffix.isEmpty()) {
			for (Map.Entry<String, TextureAtlasSprite> entry : textureMap.mapUploadedSprites.entrySet()) {
				if (entry.getKey().endsWith(suffix) && entry.getValue() != textureMap.getMissingSprite()) {
					String baseKey = entry.getKey().substring(0, entry.getKey().length() - suffix.length());
					TextureAtlasSprite base = textureMap.mapUploadedSprites.get(baseKey);
					if (base != null && base != textureMap.getMissingSprite()) {
						EmissiveSpriteApiImpl.INSTANCE.setEmissiveSprite(base, entry.getValue());
						emissivePairs++;
					}
				}
			}
		}
		CtmMcmetaLoader.Diagnostics diagnostics = CtmMcmetaLoader.getLastDiagnostics();
		ContinuityClient.LOGGER.info("CTM reload: {} definitions, {} stitched BLOOM definitions, {} emissive sprite pairs, {} invalid metadata files, {} unresolved resources",
				ctmDefinitionsLoaded, bloomDefinitions, emissivePairs,
				ContinuityConfig.INSTANCE.ctmModTextures.get() ? diagnostics.invalidMetadata() : 0,
				ContinuityConfig.INSTANCE.ctmModTextures.get() ? diagnostics.unresolvedResources() : 0);
		lastResult = null;
	}
}
