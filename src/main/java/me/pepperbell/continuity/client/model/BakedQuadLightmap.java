package me.pepperbell.continuity.client.model;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;

/** Writes a minimum per-vertex light value, adding UV1 to model quads that lack it. */
public final class BakedQuadLightmap {
	private BakedQuadLightmap() {
	}

	public static BakedQuad withMinimum(BakedQuad quad, int blocklight, int skylight) {
		VertexFormat sourceFormat = quad.getFormat();
		VertexFormat targetFormat = withLightmap(sourceFormat);
		int sourceStride = sourceFormat.getIntegerSize();
		int targetStride = targetFormat.getIntegerSize();
		int[] source = quad.getVertexData();
		int[] data = new int[4 * targetStride];
		int uv1 = targetFormat.getUvOffsetById(1) / 4;
		for (int vertex = 0; vertex < 4; vertex++) {
			System.arraycopy(source, vertex * sourceStride, data, vertex * targetStride,
					Math.min(sourceStride, targetStride));
			int index = vertex * targetStride + uv1;
			int previous = sourceFormat.hasUvOffset(1) ? data[index] : 0;
			data[index] = Math.max(previous & 0xFFFF, blocklight << 4)
					| (Math.max((previous >>> 16) & 0xFFFF, skylight << 4) << 16);
		}
		if (quad instanceof EmissiveBakedQuad emissive) {
			return emissive.withVertexData(data, targetFormat);
		}
		if (quad instanceof OverlayBakedQuad overlay) {
			return overlay.withVertexData(data, targetFormat);
		}
		return new BakedQuad(data, quad.getTintIndex(), quad.getFace(), quad.getSprite(),
				quad.shouldApplyDiffuseLighting(), targetFormat);
	}

	private static VertexFormat withLightmap(VertexFormat source) {
		if (source.hasUvOffset(1)) {
			return source;
		}
		if (source.equals(DefaultVertexFormats.ITEM)) {
			return DefaultVertexFormats.BLOCK;
		}
		VertexFormat expanded = new VertexFormat();
		for (VertexFormatElement element : source.getElements()) {
			expanded.addElement(element);
		}
		expanded.addElement(DefaultVertexFormats.TEX_2S);
		return expanded;
	}
}
