package me.pepperbell.continuity.client.ctm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonParser;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

class CtmRenderLayerRouterTest {
	@Test
	void routedTextureLeavesTheOriginalLayerWhileOrdinaryTextureStaysThere() {
		CtmDefinition definition = CtmMcmetaParser.parse(new ResourceLocation("test:glow_e"),
				JsonParser.parseString("{\"ctm_version\":1,\"layer\":\"CUTOUT\"}").getAsJsonObject(), "test", 0);
		CtmRenderLayerRouter.reload(List.of(definition));
		try {
			TextureAtlasSprite glow = new Sprite("test:glow_e");
			TextureAtlasSprite ordinary = new Sprite("test:ordinary");
			assertFalse(CtmRenderLayerRouter.shouldRender(glow, BlockRenderLayer.SOLID, false));
			assertTrue(CtmRenderLayerRouter.shouldRender(glow, BlockRenderLayer.CUTOUT, true));
			assertTrue(CtmRenderLayerRouter.shouldRender(ordinary, BlockRenderLayer.SOLID, false));
			assertFalse(CtmRenderLayerRouter.shouldRender(ordinary, BlockRenderLayer.CUTOUT, true));
			assertFalse(CtmRenderLayerRouter.shouldProcessWrappedOverlay(glow, BlockRenderLayer.SOLID, false));
			assertTrue(CtmRenderLayerRouter.shouldProcessWrappedOverlay(glow, BlockRenderLayer.CUTOUT, true));
			assertTrue(CtmRenderLayerRouter.shouldProcessWrappedOverlay(ordinary, BlockRenderLayer.SOLID, false));
			assertFalse(CtmRenderLayerRouter.shouldProcessWrappedOverlay(ordinary, BlockRenderLayer.CUTOUT, true));
			assertFalse(CtmRenderLayerRouter.shouldGenerateSuffixOverlay(glow));
			assertTrue(CtmRenderLayerRouter.shouldGenerateSuffixOverlay(ordinary));
		} finally {
			CtmRenderLayerRouter.reload(List.of());
		}
	}

	@Test
	void optedInEmissiveTextureRendersInOriginalAndBloomLayers() {
		CtmDefinition definition = CtmMcmetaParser.parse(new ResourceLocation("test:glow_e"),
				JsonParser.parseString("{\"ctm_version\":1,\"layer\":\"CUTOUT\",\"extra\":{\"emissive_fallback\":true}}")
						.getAsJsonObject(), "test", 0);
		CtmRenderLayerRouter.reload(List.of(definition));
		try {
			TextureAtlasSprite glow = new Sprite("test:glow_e");
			assertTrue(CtmRenderLayerRouter.shouldRender(glow, BlockRenderLayer.SOLID, false));
			assertTrue(CtmRenderLayerRouter.shouldRender(glow, BlockRenderLayer.CUTOUT, true));
			assertTrue(CtmRenderLayerRouter.shouldProcessWrappedOverlay(glow, BlockRenderLayer.SOLID, false));
			assertTrue(CtmRenderLayerRouter.shouldProcessWrappedOverlay(glow, BlockRenderLayer.CUTOUT, true));
			assertFalse(CtmRenderLayerRouter.shouldGenerateSuffixOverlay(glow));
			assertTrue(CtmRenderLayerRouter.shouldFullbrightEmissiveFallback(glow, false));
			assertFalse(CtmRenderLayerRouter.shouldFullbrightEmissiveFallback(glow, true));
		} finally {
			CtmRenderLayerRouter.reload(List.of());
		}
	}

	private static class Sprite extends TextureAtlasSprite {
		Sprite(String name) { super(name); }
	}
}
