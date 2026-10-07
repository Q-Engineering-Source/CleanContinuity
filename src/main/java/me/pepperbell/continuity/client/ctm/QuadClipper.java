package me.pepperbell.continuity.client.ctm;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.util.EnumFacing;

/**
 * Clips a {@link BakedQuad} to a sub-region (submap) of its texture, adjusting both the geometry
 * (moving vertices so only the sub-rect of the block face remains) and the UVs (bilinear
 * interpolation into the sub-rect). This is the equivalent of the CTM Mod's quad "subsect" +
 * "transformUVs" operations, reimplemented directly on vanilla vertex data so no CTM dependency is
 * introduced.
 * <p>
 * The approach mirrors the reference algorithm: the quad is first "derotated" so the vertex with
 * the minimum U/V comes first (making corner indexing stable), the face normal is derived from the
 * winding, geometry is projected onto the face's 2D axes, vertices are clamped to the submap
 * bounds with face-sign-dependent min/max, and the UVs are recomputed by bilinear interpolation.
 */
public final class QuadClipper {
	private static final float EPSILON = 1e-6f;

	private QuadClipper() {
	}

	/**
	 * Splits a quad into the 4 quadrants of its texture (2x2 grid), in the same order as the CTM
	 * format's {@code Submap.X2} layout: top-left, top-right, bottom-left, bottom-right.
	 * <p>
	 * Unlike {@link #clip}, this operates purely in UV space: each quadrant is the corresponding
	 * quarter of the quad's UV box, and the geometry is the matching sub-rect of the face. No
	 * face-sign flipping is applied, because here UV quadrant and geometry quadrant coincide.
	 */
	public static BakedQuad[] subdivide4(BakedQuad quad, TextureAtlasSprite sprite) {
		BakedQuad[] ret = new BakedQuad[4];
		// sub-regions in UV fraction space: {uMin, vMin, uMax, vMax} per quadrant, top-left first
		float[][] quadrants = {
				{0, 0, 0.5f, 0.5f},
				{0.5f, 0, 1, 0.5f},
				{0, 0.5f, 0.5f, 1},
				{0.5f, 0.5f, 1, 1}
		};
		for (int i = 0; i < 4; i++) {
			ret[i] = subdivideUv(quad, sprite, quadrants[i][0], quadrants[i][1], quadrants[i][2], quadrants[i][3]);
		}
		return ret;
	}

	/** Clips the quad to a sub-rect of its UV box, expressed in 0..1 fractions of the quad. */
	private static BakedQuad subdivideUv(BakedQuad quad, TextureAtlasSprite sprite, float uMin, float vMin, float uMax, float vMax) {
		VertexFormat format = quad.getFormat();
		float[][] positions = new float[4][3];
		float[][] uvs = new float[4][2];
		for (int i = 0; i < 4; i++) {
			positions[i][0] = positionComponent(quad, i, 0);
			positions[i][1] = positionComponent(quad, i, 1);
			positions[i][2] = positionComponent(quad, i, 2);
			uvs[i][0] = getU(quad, i);
			uvs[i][1] = getV(quad, i);
		}

		float uMinF = Float.MAX_VALUE;
		float vMinF = Float.MAX_VALUE;
		float uMaxF = -Float.MAX_VALUE;
		float vMaxF = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			uMinF = Math.min(uMinF, uvs[i][0]);
			vMinF = Math.min(vMinF, uvs[i][1]);
			uMaxF = Math.max(uMaxF, uvs[i][0]);
			vMaxF = Math.max(vMaxF, uvs[i][1]);
		}
		float uSpan = uMaxF - uMinF;
		float vSpan = vMaxF - vMinF;

		// Locate the world position of each UV corner of the quad. Block-model quads are
		// axis-aligned rectangles and map their UV box affinely onto the face, so the sub-rect's
		// world corners are a bilinear interpolation of these four corners. This works for every
		// face orientation without any flip rules (texture V=0 is the top of the texture, which is
		// wherever the quad's own UVs say it is).
		float[][] corners = new float[4][3];
		corners[0] = positionAtUv(positions, uvs, uMinF, vMinF); // (minU, minV)
		corners[1] = positionAtUv(positions, uvs, uMaxF, vMinF); // (maxU, minV)
		corners[2] = positionAtUv(positions, uvs, uMaxF, vMaxF); // (maxU, maxV)
		corners[3] = positionAtUv(positions, uvs, uMinF, vMaxF); // (minU, maxV)

		// Each output vertex keeps the input winding; its (u,v) fraction maps 1:1 into the sub-rect.
		float[][] newPos = new float[4][3];
		float[][] newUvs = new float[4][2];
		for (int j = 0; j < 4; j++) {
			float fu = uSpan == 0 ? 0.5f : (uvs[j][0] - uMinF) / uSpan;
			float fv = vSpan == 0 ? 0.5f : (uvs[j][1] - vMinF) / vSpan;
			// fraction within the sub-rect
			float gu = uMin + fu * (uMax - uMin);
			float gv = vMin + fv * (vMax - vMin);
			newUvs[j][0] = uMinF + gu * uSpan;
			newUvs[j][1] = vMinF + gv * vSpan;
			bilinear(corners, gu, gv, newPos[j]);
		}

		// Write back geometry and UVs
		int[] data = quad.getVertexData().clone();
		for (int i = 0; i < 4; i++) {
			int uvIndex = format.getUvOffsetById(0) / 4 + i * format.getIntegerSize();
			data[uvIndex] = Float.floatToIntBits(newUvs[i][0]);
			data[uvIndex + 1] = Float.floatToIntBits(newUvs[i][1]);

			int posIndex = format.getOffset(0) / 4 + i * format.getIntegerSize();
			data[posIndex] = Float.floatToIntBits(newPos[i][0]);
			data[posIndex + 1] = Float.floatToIntBits(newPos[i][1]);
			data[posIndex + 2] = Float.floatToIntBits(newPos[i][2]);
		}
		return new BakedQuad(data, quad.getTintIndex(), quad.getFace(), sprite, true, format);
	}

	/** World position of the vertex whose UV equals the given (u, v) within the quad. */
	private static float[] positionAtUv(float[][] positions, float[][] uvs, float u, float v) {
		int best = 0;
		float bestDist = Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			float du = uvs[i][0] - u;
			float dv = uvs[i][1] - v;
			float dist = du * du + dv * dv;
			if (dist < bestDist) {
				bestDist = dist;
				best = i;
			}
		}
		return positions[best];
	}

	/**
	 * Bilinear interpolation over the four corners ordered (minU,minV), (maxU,minV),
	 * (maxU,maxV), (minU,maxV), at fractions (u, v) of the quad's UV box. Output goes into
	 * {@code out}.
	 */
	private static void bilinear(float[][] corners, float u, float v, float[] out) {
		for (int a = 0; a < 3; a++) {
			float bottom = lerp(corners[0][a], corners[1][a], u);
			float top = lerp(corners[3][a], corners[2][a], u);
			out[a] = lerp(bottom, top, v);
		}
	}

	/**
	 * Expands the UVs of a quadrant quad (produced by {@link #subdivide4}) to fill the entire
	 * 0..1 range of its sprite, matching the CTM format's "grow" (normalizeQuadrant) step.
	 * The quad's own UV box (one quadrant of the sprite) is normalized and stretched to the full
	 * sprite UV range.
	 */
	public static BakedQuad grow(BakedQuad quad, TextureAtlasSprite sprite) {
		VertexFormat format = quad.getFormat();
		int[] data = quad.getVertexData().clone();

		float quadMinU = Float.MAX_VALUE;
		float quadMinV = Float.MAX_VALUE;
		float quadMaxU = -Float.MAX_VALUE;
		float quadMaxV = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			quadMinU = Math.min(quadMinU, getU(quad, i));
			quadMinV = Math.min(quadMinV, getV(quad, i));
			quadMaxU = Math.max(quadMaxU, getU(quad, i));
			quadMaxV = Math.max(quadMaxV, getV(quad, i));
		}
		float quadUSpan = quadMaxU - quadMinU;
		float quadVSpan = quadMaxV - quadMinV;

		float srcMinU = sprite.getMinU();
		float srcMaxU = sprite.getMaxU();
		float srcMinV = sprite.getMinV();
		float srcMaxV = sprite.getMaxV();
		float srcW = srcMaxU - srcMinU;
		float srcH = srcMaxV - srcMinV;

		for (int i = 0; i < 4; i++) {
			float u01 = quadUSpan == 0 ? 0.5f : (getU(quad, i) - quadMinU) / quadUSpan;
			float v01 = quadVSpan == 0 ? 0.5f : (getV(quad, i) - quadMinV) / quadVSpan;
			int uvIndex = format.getUvOffsetById(0) / 4 + i * format.getIntegerSize();
			data[uvIndex] = Float.floatToIntBits(srcMinU + u01 * srcW);
			data[uvIndex + 1] = Float.floatToIntBits(srcMinV + v01 * srcH);
		}

		return new BakedQuad(data, quad.getTintIndex(), quad.getFace(), sprite, true, format);
	}

	/**
	 * Returns the UV quadrant (0..3) of the given quad, using the same convention as the CTM
	 * format: 0 = top-left, 1 = top-right, 2 = bottom-left, 3 = bottom-right (in 0..1 UV space).
	 */
	public static int getQuadrant(BakedQuad quad, TextureAtlasSprite sprite) {
		float minU = Float.MAX_VALUE;
		float minV = Float.MAX_VALUE;
		float maxU = -Float.MAX_VALUE;
		float maxV = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			float u = getU(quad, i);
			float v = getV(quad, i);
			minU = Math.min(minU, u);
			minV = Math.min(minV, v);
			maxU = Math.max(maxU, u);
			maxV = Math.max(maxV, v);
		}
		// Normalize to 0..1
		float w = sprite.getMaxU() - sprite.getMinU();
		float h = sprite.getMaxV() - sprite.getMinV();
		float nMaxU = w == 0 ? 0.5f : (maxU - sprite.getMinU()) / w;
		float nMaxV = h == 0 ? 0.5f : (maxV - sprite.getMinV()) / h;
		if (nMaxU <= 0.5f) {
			return nMaxV <= 0.5f ? 3 : 0;
		}
		return nMaxV <= 0.5f ? 2 : 1;
	}

	/**
	 * Clips the quad to the given sub-region of the sprite's texture.
	 *
	 * @param quad   the input quad (any vertex order)
	 * @param sprite the sprite whose UVs the submap is expressed in
	 * @param submap the sub-region, in 16th-pixel units of a 16x16 sheet
	 */
	public static BakedQuad clip(BakedQuad quad, TextureAtlasSprite sprite, CtmSubmap submap) {
		VertexFormat format = quad.getFormat();
		int vertexCount = format.getElementCount();
		float[][] positions = new float[4][3];
		float[][] uvs = new float[4][2];
		for (int i = 0; i < 4; i++) {
			positions[i][0] = positionComponent(quad, i, 0);
			positions[i][1] = positionComponent(quad, i, 1);
			positions[i][2] = positionComponent(quad, i, 2);
			uvs[i][0] = getU(quad, i);
			uvs[i][1] = getV(quad, i);
		}

		// Derotate: find the vertex with minimum U/V and make it index 0
		int start = 0;
		float minU = Float.MAX_VALUE;
		float minV = Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			if (uvs[i][0] <= minU + EPSILON && uvs[i][1] <= minV + EPSILON) {
				minU = uvs[i][0];
				minV = uvs[i][1];
				start = i;
			}
		}

		float[][] rotPositions = new float[4][3];
		float[][] rotUvs = new float[4][2];
		for (int i = 0; i < 4; i++) {
			int idx = (start + i) % 4;
			rotPositions[i] = positions[idx];
			rotUvs[i] = uvs[idx];
		}

		// UV box of the quad
		float uMin = Float.MAX_VALUE;
		float vMin = Float.MAX_VALUE;
		float uMax = -Float.MAX_VALUE;
		float vMax = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			uMin = Math.min(uMin, rotUvs[i][0]);
			vMin = Math.min(vMin, rotUvs[i][1]);
			uMax = Math.max(uMax, rotUvs[i][0]);
			vMax = Math.max(vMax, rotUvs[i][1]);
		}
		float uSpan = uMax - uMin;
		float vSpan = vMax - vMin;

		// World position of each UV corner (minU,minV), (maxU,minV), (maxU,maxV), (minU,maxV).
		// Affine quad mapping -> bilinear interpolation gives the sub-rect's world corners, with no
		// face-sign assumptions.
		float[][] corners = new float[4][3];
		corners[0] = positionAtUv(rotPositions, rotUvs, uMin, vMin);
		corners[1] = positionAtUv(rotPositions, rotUvs, uMax, vMin);
		corners[2] = positionAtUv(rotPositions, rotUvs, uMax, vMax);
		corners[3] = positionAtUv(rotPositions, rotUvs, uMin, vMax);

		// Target sub-region of the sprite, in 0..1 UV space
		float subMinU = submap.xOffset * CtmSubmap.UNITS_PER_PIXEL;
		float subMaxU = (submap.xOffset + submap.width) * CtmSubmap.UNITS_PER_PIXEL;
		float subMinV = submap.yOffset * CtmSubmap.UNITS_PER_PIXEL;
		float subMaxV = (submap.yOffset + submap.height) * CtmSubmap.UNITS_PER_PIXEL;

		float[][] newUvs = new float[4][2];
		float[][] newPos = new float[4][3];
		for (int i = 0; i < 4; i++) {
			float fu = uSpan == 0 ? 0.5f : (rotUvs[i][0] - uMin) / uSpan;
			float fv = vSpan == 0 ? 0.5f : (rotUvs[i][1] - vMin) / vSpan;
			newUvs[i][0] = subMinU + fu * (subMaxU - subMinU);
			newUvs[i][1] = subMinV + fv * (subMaxV - subMinV);
			// Geometry follows the same affine UV->world mapping: the fraction of the quad's UV
			// box that the new UV sits at decides where on the face the vertex lands.
			float gu = uSpan == 0 ? 0.5f : (newUvs[i][0] - uMin) / uSpan;
			float gv = vSpan == 0 ? 0.5f : (newUvs[i][1] - vMin) / vSpan;
			bilinear(corners, gu, gv, newPos[i]);
		}

		// Rebuild the vertex data
		int[] data = quad.getVertexData().clone();
		for (int i = 0; i < 4; i++) {
			int uvIndex = format.getUvOffsetById(0) / 4 + i * format.getIntegerSize();
			data[uvIndex] = Float.floatToIntBits(newUvs[i][0]);
			data[uvIndex + 1] = Float.floatToIntBits(newUvs[i][1]);

			int posIndex = format.getOffset(0) / 4 + i * format.getIntegerSize();
			data[posIndex] = Float.floatToIntBits(newPos[i][0]);
			data[posIndex + 1] = Float.floatToIntBits(newPos[i][1]);
			data[posIndex + 2] = Float.floatToIntBits(newPos[i][2]);
		}

		return new BakedQuad(data, quad.getTintIndex(), quad.getFace(), sprite, true, format);
	}

	/**
	 * Re-targets a quad's UVs onto a sub-region of the given sprite, while keeping the geometry
	 * unchanged. The quad's current UVs are treated as fractions of its original sprite's UV box
	 * and remapped into the destination submap of the destination sprite.
	 *
	 * @param quad       the quad (whose geometry is already correct)
	 * @param fromSprite the sprite the quad's current UVs are relative to
	 * @param toSprite   the destination sprite
	 * @param submap     the sub-region of the destination sprite, in 16th-pixel units
	 */
	public static BakedQuad transformUVs(BakedQuad quad, TextureAtlasSprite fromSprite, TextureAtlasSprite toSprite, CtmSubmap submap) {
		VertexFormat format = quad.getFormat();
		int[] data = quad.getVertexData().clone();

		float srcMinU = fromSprite.getMinU();
		float srcMaxU = fromSprite.getMaxU();
		float srcMinV = fromSprite.getMinV();
		float srcMaxV = fromSprite.getMaxV();
		float srcW = srcMaxU - srcMinU;
		float srcH = srcMaxV - srcMinV;

		float dstMinU = toSprite.getMinU() + (toSprite.getMaxU() - toSprite.getMinU()) * submap.getMinU();
		float dstMaxU = toSprite.getMinU() + (toSprite.getMaxU() - toSprite.getMinU()) * submap.getMaxU();
		float dstMinV = toSprite.getMinV() + (toSprite.getMaxV() - toSprite.getMinV()) * submap.getMinV();
		float dstMaxV = toSprite.getMinV() + (toSprite.getMaxV() - toSprite.getMinV()) * submap.getMaxV();

		for (int i = 0; i < 4; i++) {
			float u = getU(quad, i);
			float v = getV(quad, i);
			float uFrac = srcW == 0 ? 0.5f : (u - srcMinU) / srcW;
			float vFrac = srcH == 0 ? 0.5f : (v - srcMinV) / srcH;
			int uvIndex = format.getUvOffsetById(0) / 4 + i * format.getIntegerSize();
			data[uvIndex] = Float.floatToIntBits(lerp(dstMinU, dstMaxU, uFrac));
			data[uvIndex + 1] = Float.floatToIntBits(lerp(dstMinV, dstMaxV, vFrac));
		}

		return new BakedQuad(data, quad.getTintIndex(), quad.getFace(), toSprite, true, format);
	}

	/** Assigns the whole output submap to the clipped quad, matching CTM Vintage's {@code setUVs}. */
	public static BakedQuad setUVs(BakedQuad quad, TextureAtlasSprite toSprite, CtmSubmap submap) {
		VertexFormat format = quad.getFormat();
		int[] data = quad.getVertexData().clone();
		float minU = Float.MAX_VALUE;
		float minV = Float.MAX_VALUE;
		float maxU = -Float.MAX_VALUE;
		float maxV = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			minU = Math.min(minU, getU(quad, i));
			minV = Math.min(minV, getV(quad, i));
			maxU = Math.max(maxU, getU(quad, i));
			maxV = Math.max(maxV, getV(quad, i));
		}

		float targetMinU = toSprite.getMinU() + (toSprite.getMaxU() - toSprite.getMinU()) * submap.getMinU();
		float targetMaxU = toSprite.getMinU() + (toSprite.getMaxU() - toSprite.getMinU()) * submap.getMaxU();
		float targetMinV = toSprite.getMinV() + (toSprite.getMaxV() - toSprite.getMinV()) * submap.getMinV();
		float targetMaxV = toSprite.getMinV() + (toSprite.getMaxV() - toSprite.getMinV()) * submap.getMaxV();

		for (int i = 0; i < 4; i++) {
			float u = getU(quad, i);
			float v = getV(quad, i);
			float uFraction = maxU == minU ? 0.5f : (u - minU) / (maxU - minU);
			float vFraction = maxV == minV ? 0.5f : (v - minV) / (maxV - minV);
			int uvIndex = format.getUvOffsetById(0) / 4 + i * format.getIntegerSize();
			data[uvIndex] = Float.floatToIntBits(lerp(targetMinU, targetMaxU, uFraction));
			data[uvIndex + 1] = Float.floatToIntBits(lerp(targetMinV, targetMaxV, vFraction));
		}

		return new BakedQuad(data, quad.getTintIndex(), quad.getFace(), toSprite, true, format);
	}

	/**
	 * Rotates a quad's UVs in sprite space. 0 = identity, 1 = 90° (u,v)->(v,1-u), 2 = 180°,
	 * 3 = 270° (matching the CTM format's {@code Quad.rotate}).
	 */
	public static BakedQuad rotate(BakedQuad quad, TextureAtlasSprite sprite, int amount) {
		if (amount % 4 == 0) {
			return quad;
		}
		VertexFormat format = quad.getFormat();
		int[] data = quad.getVertexData().clone();

		float srcMinU = sprite.getMinU();
		float srcMaxU = sprite.getMaxU();
		float srcMinV = sprite.getMinV();
		float srcMaxV = sprite.getMaxV();
		float srcW = srcMaxU - srcMinU;
		float srcH = srcMaxV - srcMinV;

		for (int i = 0; i < 4; i++) {
			float u = srcW == 0 ? 0.5f : (getU(quad, i) - srcMinU) / srcW;
			float v = srcH == 0 ? 0.5f : (getV(quad, i) - srcMinV) / srcH;
			float nu;
			float nv;
			switch (amount % 4) {
				case 1 -> {
					nu = v;
					nv = 1 - u;
				}
				case 2 -> {
					nu = 1 - u;
					nv = 1 - v;
				}
				default -> {
					nu = 1 - v;
					nv = u;
				}
			}
			int uvIndex = format.getUvOffsetById(0) / 4 + i * format.getIntegerSize();
			data[uvIndex] = Float.floatToIntBits(srcMinU + nu * srcW);
			data[uvIndex + 1] = Float.floatToIntBits(srcMinV + nv * srcH);
		}

		return new BakedQuad(data, quad.getTintIndex(), quad.getFace(), sprite, true, format);
	}

	/** Applies a small UV offset only to the interior vertices of a subdivided quad. */
	public static BakedQuad offsetInteriorUv(BakedQuad quad, BakedQuad uvSource, TextureAtlasSprite sprite,
			float offsetU, float offsetV) {
		VertexFormat format = quad.getFormat();
		int[] data = quad.getVertexData().clone();
		float minU = Float.MAX_VALUE;
		float minV = Float.MAX_VALUE;
		float maxU = -Float.MAX_VALUE;
		float maxV = -Float.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			minU = Math.min(minU, getU(uvSource, i));
			minV = Math.min(minV, getV(uvSource, i));
			maxU = Math.max(maxU, getU(uvSource, i));
			maxV = Math.max(maxV, getV(uvSource, i));
		}

		for (int i = 0; i < 4; i++) {
			float u = getU(quad, i);
			float v = getV(quad, i);
			if (u != minU && u != maxU && v != minV && v != maxV) {
				float normalizedU = normalize(minU, maxU, u) + offsetU;
				float normalizedV = normalize(minV, maxV, v) + offsetV;
				int uvIndex = format.getUvOffsetById(0) / 4 + i * format.getIntegerSize();
				data[uvIndex] = Float.floatToIntBits(lerp(minU, maxU, normalizedU));
				data[uvIndex + 1] = Float.floatToIntBits(lerp(minV, maxV, normalizedV));
			}
		}

		return new BakedQuad(data, quad.getTintIndex(), quad.getFace(), sprite, true, format);
	}

	static float getU(BakedQuad quad, int vertex) {
		VertexFormat format = quad.getFormat();
		if (!format.hasUvOffset(0)) {
			return 0;
		}
		int index = format.getUvOffsetById(0) / 4 + vertex * format.getIntegerSize();
		return Float.intBitsToFloat(quad.getVertexData()[index]);
	}

	static float getV(BakedQuad quad, int vertex) {
		VertexFormat format = quad.getFormat();
		if (!format.hasUvOffset(0)) {
			return 0;
		}
		int index = format.getUvOffsetById(0) / 4 + vertex * format.getIntegerSize() + 1;
		return Float.intBitsToFloat(quad.getVertexData()[index]);
	}

	static float positionComponent(BakedQuad quad, int vertex, int axis) {
		VertexFormat format = quad.getFormat();
		int index = format.getOffset(0) / 4 + vertex * format.getIntegerSize() + axis;
		return Float.intBitsToFloat(quad.getVertexData()[index]);
	}

	private static float normalize(float min, float max, float x) {
		if (Math.abs(min - max) < EPSILON) {
			return 0.5f;
		}
		return (x - min) / (max - min);
	}

	private static float lerp(float a, float b, float f) {
		return (a * (1 - f)) + (b * f);
	}
}
