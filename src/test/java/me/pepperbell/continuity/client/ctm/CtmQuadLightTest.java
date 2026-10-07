package me.pepperbell.continuity.client.ctm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;
import me.pepperbell.continuity.client.model.BakedQuadLightmap;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

class CtmQuadLightTest {
	@Test
	void extraLightIsWrittenToEveryOutputVertexWithoutChangingInput() {
		TextureAtlasSprite sprite = new Sprite("test:glow");
		int[] data = new int[4 * DefaultVertexFormats.BLOCK.getIntegerSize()];
		int uv1 = DefaultVertexFormats.BLOCK.getUvOffsetById(1) / 4;
		data[uv1] = 0x00A000F0;
		BakedQuad input = new BakedQuad(data, -1, EnumFacing.NORTH, sprite, true, DefaultVertexFormats.BLOCK);
		CtmDefinition definition = CtmMcmetaParser.parse(new ResourceLocation("test:glow"),
				JsonParser.parseString("{\"ctm_version\":1,\"extra\":{\"light\":4}}").getAsJsonObject(), "test", 0);
		CtmQuadProcessor processor = new CtmQuadProcessor(definition, new TextureAtlasSprite[]{sprite});

		List<BakedQuad> output = new ArrayList<>();
		processor.transformQuad(input, sprite, null, null, null, null, 0, output);

		assertEquals(1, output.size());
		assertNotSame(input, output.get(0));
		int stride = DefaultVertexFormats.BLOCK.getIntegerSize();
		for (int i = 0; i < 4; i++) {
			assertEquals(i == 0 ? 0x00A000F0 : 0x00400040,
					output.get(0).getVertexData()[i * stride + uv1]);
			assertEquals(i == 0 ? 0x00A000F0 : 0, input.getVertexData()[i * stride + uv1]);
		}
	}

	@Test
	void extraLightAddsLightmapToItemFormatBlockModelQuads() {
		TextureAtlasSprite sprite = new Sprite("test:glow");
		int[] data = new int[4 * DefaultVertexFormats.ITEM.getIntegerSize()];
		BakedQuad input = new BakedQuad(data, -1, EnumFacing.NORTH, sprite, true, DefaultVertexFormats.ITEM);
		CtmDefinition definition = CtmMcmetaParser.parse(new ResourceLocation("test:glow"),
				JsonParser.parseString("{\"ctm_version\":1,\"extra\":{\"light\":4}}").getAsJsonObject(), "test", 0);
		CtmQuadProcessor processor = new CtmQuadProcessor(definition, new TextureAtlasSprite[]{sprite});

		List<BakedQuad> output = new ArrayList<>();
		processor.transformQuad(input, sprite, null, null, null, null, 0, output);

		assertEquals(DefaultVertexFormats.BLOCK, output.get(0).getFormat());
		int stride = output.get(0).getFormat().getIntegerSize();
		int uv1 = output.get(0).getFormat().getUvOffsetById(1) / 4;
		for (int vertex = 0; vertex < 4; vertex++) {
			assertEquals(0x00400040, output.get(0).getVertexData()[vertex * stride + uv1]);
		}
	}

	@Test
	void suffixOverlayGetsFullLightWhenSourceModelHasNoLightmap() {
		TextureAtlasSprite emissive = new Sprite("test:white_e");
		BakedQuad input = new BakedQuad(new int[4 * DefaultVertexFormats.ITEM.getIntegerSize()],
				-1, EnumFacing.NORTH, emissive, true, DefaultVertexFormats.ITEM);

		BakedQuad output = BakedQuadLightmap.withMinimum(input, 15, 15);

		assertSame(emissive, output.getSprite());
		assertEquals(DefaultVertexFormats.BLOCK, output.getFormat());
		int stride = output.getFormat().getIntegerSize();
		int uv1 = output.getFormat().getUvOffsetById(1) / 4;
		for (int vertex = 0; vertex < 4; vertex++) {
			assertEquals(0x00F000F0, output.getVertexData()[vertex * stride + uv1]);
		}
	}

	private static class Sprite extends TextureAtlasSprite {
		Sprite(String name) { super(name); }
	}
}
