package me.pepperbell.continuity.client.model;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BakedQuadRetextured;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;

public class EmissiveBakedQuad extends BakedQuadRetextured implements OverlayTintedQuad {
	private static final int FULL_BRIGHT_LIGHTMAP = 0x00F000F0;
	private final boolean hasTintOverride;
	private final int tintOverride;

	public EmissiveBakedQuad(BakedQuad quad, TextureAtlasSprite emissiveSprite) {
		this(quad, emissiveSprite, quad instanceof OverlayTintedQuad overlay ? overlay : null);
	}

	private EmissiveBakedQuad(BakedQuad quad, TextureAtlasSprite emissiveSprite,
			OverlayTintedQuad tintSource) {
		super(quad, emissiveSprite);
		if (tintSource != null) {
			hasTintOverride = tintSource.hasTintOverride();
			tintOverride = tintSource.getTintOverride();
		} else {
			hasTintOverride = false;
			tintOverride = -1;
		}
		VertexFormat format = getFormat();
		if (format.hasUvOffset(1)) {
			int uvIndex = format.getUvOffsetById(1) / 4;
			int vertexSize = format.getIntegerSize();
			for (int i = 0; i < 4; i++) {
				vertexData[i * vertexSize + uvIndex] = FULL_BRIGHT_LIGHTMAP;
			}
		}
	}

	public EmissiveBakedQuad withVertexData(int[] vertexData, VertexFormat format) {
		BakedQuad dataQuad = new BakedQuad(vertexData, getTintIndex(), getFace(), getSprite(),
				shouldApplyDiffuseLighting(), format);
		return new EmissiveBakedQuad(dataQuad, getSprite(), this);
	}

	@Override
	public boolean hasTintOverride() {
		return hasTintOverride;
	}

	@Override
	public int getTintOverride() {
		return tintOverride;
	}
}
