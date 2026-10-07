package me.pepperbell.continuity.client;

import java.util.List;

import com.dhj.actinium.api.render.terrain.BlockQuadTransformer;
import com.dhj.actinium.world.cloned.ActiniumBlockAccess;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.pepperbell.continuity.api.client.EmissiveSpriteApi;
import me.pepperbell.continuity.api.client.QuadProcessor;
import me.pepperbell.continuity.client.model.EmissiveBakedQuad;
import me.pepperbell.continuity.client.model.BakedQuadLightmap;
import me.pepperbell.continuity.client.model.QuadProcessors;
import me.pepperbell.continuity.client.config.ContinuityConfig;
import me.pepperbell.continuity.client.ctm.CtmRenderLayerRouter;
import me.pepperbell.continuity.impl.client.ProcessingContextImpl;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

public class ContinuityCtmTransformer implements BlockQuadTransformer {
	/**
	 * MCPatcher/OptiFine packs chain CTM rules: one rule picks a variant entry tile (e.g. random),
	 * then another rule matches that tile's sprite and expands it (e.g. repeat over a large surface).
	 * OptiFine re-applies rules to the same quad with its new sprite up to three times. Mirror that
	 * here so e.g. {@code stone1.properties} -> {@code 1.properties} connects a stone field.
	 */
	private static final int MAX_PROCESSING_PASSES = 3;

	@Override
	public List<BakedQuad> transform(IBlockState state, BlockPos pos, ActiniumBlockAccess blockAccess, BlockRenderLayer layer, EnumFacing side, List<BakedQuad> quads) {
		ObjectArrayList<BakedQuad> output = new ObjectArrayList<>();
		if (quads.isEmpty()) {
			return output;
		}
		boolean routeCtmLayers = ContinuityConfig.INSTANCE.connectedTextures.get()
				&& ContinuityConfig.INSTANCE.ctmModTextures.get();
		boolean routedLayer = routeCtmLayers && CtmRenderLayerRouter.isRoutedLayer(state, layer);

		if (!ContinuityConfig.INSTANCE.connectedTextures.get()) {
			for (BakedQuad quad : quads) {
				if (quad instanceof EmissiveBakedQuad
						&& routeCtmLayers && !CtmRenderLayerRouter.shouldProcessWrappedOverlay(
								quad.getSprite(), layer, routedLayer)) {
					continue;
				}
				if (!routeCtmLayers || CtmRenderLayerRouter.shouldRender(quad.getSprite(), layer, routedLayer)) {
					output.add(quad);
				}
			}
		} else {
			long rand = MathHelper.getPositionRandom(pos);
			ProcessingContextImpl context = new ProcessingContextImpl();

			for (BakedQuad quad : quads) {
				if (quad instanceof EmissiveBakedQuad
						&& routeCtmLayers && !CtmRenderLayerRouter.shouldProcessWrappedOverlay(
								quad.getSprite(), layer, routedLayer)) {
					continue;
				}
				if (!routeCtmLayers || CtmRenderLayerRouter.shouldRender(quad.getSprite(), layer, routedLayer)) {
					int firstOutput = output.size();
					processQuadChain(quad, state, pos, blockAccess, rand, context, output);
					if (routeCtmLayers
							&& CtmRenderLayerRouter.shouldFullbrightEmissiveFallback(quad.getSprite(), routedLayer)) {
						for (int i = firstOutput; i < output.size(); i++) {
							output.set(i, BakedQuadLightmap.withMinimum(output.get(i), 15, 15));
						}
					}
				}
			}
		}

		if (ContinuityConfig.INSTANCE.emissiveTextures.get()) {
			int originalOutputSize = output.size();
			for (int i = 0; i < originalOutputSize; i++) {
				BakedQuad quad = output.get(i);
				TextureAtlasSprite sprite = quad.getSprite();
				if (sprite == null) {
					continue;
				}
				TextureAtlasSprite emissiveSprite = EmissiveSpriteApi.get().getEmissiveSprite(sprite);
				if (emissiveSprite != null
						&& (!routeCtmLayers || CtmRenderLayerRouter.shouldGenerateSuffixOverlay(emissiveSprite))) {
					output.add(BakedQuadLightmap.withMinimum(new EmissiveBakedQuad(quad, emissiveSprite), 15, 15));
				}
			}
		}

		return output;
	}

	/**
	 * Processes a single quad through the CTM pipeline, re-applying rules when a processor replaces
	 * the quad's sprite with a new one (MCPatcher variant -> repeat chaining). The chain is bounded
	 * to avoid infinite loops from circular rules.
	 */
	private void processQuadChain(BakedQuad input, IBlockState state, BlockPos pos, ActiniumBlockAccess blockAccess, long rand, ProcessingContextImpl context, ObjectArrayList<BakedQuad> output) {
		BakedQuad current = input;
		boolean firstPass = true;
		for (int pass = 0; pass < MAX_PROCESSING_PASSES; pass++) {
			TextureAtlasSprite sprite = current.getSprite();
			if (sprite == null) {
				output.add(current);
				return;
			}

			QuadProcessors.Slice slice = QuadProcessors.getCache(state).apply(sprite);
			QuadProcessor[] processors = firstPass ? slice.processors() : slice.multipassProcessors();
			if (processors.length == 0) {
				output.add(current);
				return;
			}

			context.reset();
			boolean discarded = false;
			boolean processed = false;
			BakedQuad chained = null;
			for (QuadProcessor processor : processors) {
				QuadProcessor.ProcessingResult result = processor.processQuad(current, sprite, blockAccess, pos, state, state, rand, pass, context);
				if (result == QuadProcessor.ProcessingResult.DISCARD) {
					discarded = true;
					break;
				}
				if (result == QuadProcessor.ProcessingResult.NEXT_PROCESSOR) {
					continue;
				}

				if (!context.getExtraQuads().isEmpty()) {
					// A single re-textured quad lets a later rule chain onto the new sprite (MCPatcher
					// variant -> repeat). Multiple outputs (or no change) end the chain here.
					if (context.getExtraQuads().size() == 1) {
						BakedQuad next = context.getExtraQuads().get(0);
						if (next.getSprite() != null && next.getSprite() != sprite) {
							chained = next;
							break;
						}
					}
					output.addAll(context.getExtraQuads());
				} else {
					output.add(current);
				}
				processed = true;
				break;
			}

			if (discarded) {
				return;
			}
			if (chained != null) {
				current = chained;
				firstPass = false;
				continue;
			}
			if (!processed) {
				output.add(current);
			}
			return;
		}
		// Chain limit reached: emit whatever is left.
		output.add(current);
	}
}
