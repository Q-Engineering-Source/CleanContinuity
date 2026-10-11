package me.pepperbell.continuity.client.model;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;

/** Marks a generated overlay quad so the transformer can route it to its configured render layer. */
public final class OverlayBakedQuad extends BakedQuad implements OverlayTintedQuad {
	private final BlockRenderLayer renderLayer;
	private final boolean hasTintOverride;
	private final int tintOverride;
	public OverlayBakedQuad(int[] vertexData, int tintIndex, EnumFacing face, TextureAtlasSprite sprite,
			boolean applyDiffuseLighting, VertexFormat format, BlockRenderLayer renderLayer,
			boolean hasTintOverride, int tintOverride) {
		super(vertexData, tintIndex, face, sprite, applyDiffuseLighting, format);
		this.renderLayer = renderLayer;
		this.hasTintOverride = hasTintOverride;
		this.tintOverride = tintOverride;
	}

	public BlockRenderLayer getRenderLayer() {
		return renderLayer;
	}

	public boolean hasTintOverride() {
		return hasTintOverride;
	}

	public int getTintOverride() {
		return tintOverride;
	}

	public OverlayBakedQuad withVertexData(int[] vertexData, VertexFormat format) {
		return new OverlayBakedQuad(vertexData, getTintIndex(), getFace(), getSprite(),
				shouldApplyDiffuseLighting(), format, renderLayer, hasTintOverride, tintOverride);
	}
}
