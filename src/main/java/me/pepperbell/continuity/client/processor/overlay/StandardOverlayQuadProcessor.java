package me.pepperbell.continuity.client.processor.overlay;

import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import me.pepperbell.continuity.api.client.ProcessingDataProvider;
import me.pepperbell.continuity.api.client.QuadProcessor;
import me.pepperbell.continuity.client.model.OverlayBakedQuad;
import me.pepperbell.continuity.client.processor.AbstractQuadProcessorFactory;
import me.pepperbell.continuity.client.processor.BaseProcessingPredicate;
import me.pepperbell.continuity.client.processor.ConnectionPredicate;
import me.pepperbell.continuity.client.processor.DirectionMaps;
import me.pepperbell.continuity.client.processor.ProcessingDataKeys;
import me.pepperbell.continuity.client.processor.ProcessingPredicate;
import me.pepperbell.continuity.client.properties.overlay.StandardOverlayCtmProperties;
import me.pepperbell.continuity.client.util.QuadUtil;
import net.minecraft.init.Blocks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/** Implements OptiFine's standard 17-tile block-transition overlay method. */
public final class StandardOverlayQuadProcessor implements QuadProcessor {
	private static final float GRASS_PATH_HEIGHT = 15.0f / 16.0f;
	private final TextureAtlasSprite[] sprites;
	private final ProcessingPredicate processingPredicate;
	@Nullable
	private final Set<ResourceLocation> matchTilesSet;
	@Nullable
	private final Predicate<IBlockState> matchBlocksPredicate;
	@Nullable
	private final Set<ResourceLocation> connectTilesSet;
	@Nullable
	private final Predicate<IBlockState> connectBlocksPredicate;
	private final ConnectionPredicate connectionPredicate;
	private final StandardOverlayCtmProperties properties;
	private final ThreadLocal<SpriteCollector> spriteCollectors = ThreadLocal.withInitial(SpriteCollector::new);

	public StandardOverlayQuadProcessor(TextureAtlasSprite[] sprites, StandardOverlayCtmProperties properties) {
		this.sprites = sprites;
		this.properties = properties;
		this.processingPredicate = BaseProcessingPredicate.fromProperties(properties);
		this.matchTilesSet = properties.getMatchTilesSet();
		this.matchBlocksPredicate = properties.getMatchBlocksPredicate();
		this.connectTilesSet = properties.getConnectTilesSet();
		this.connectBlocksPredicate = properties.getConnectBlocksPredicate();
		this.connectionPredicate = properties.getConnectionPredicate();

		// A skipped or missing overlay tile has no corresponding quad to emit.
		for (int i = 0; i < sprites.length; i++) {
			if (sprites[i] != null && sprites[i] == Minecraft.getMinecraft().getTextureMapBlocks().getMissingSprite()) {
				sprites[i] = null;
			}
		}
	}

	@Override
	public ProcessingResult processQuad(BakedQuad quad, TextureAtlasSprite sprite, IBlockAccess level, BlockPos pos,
			IBlockState appearanceState, IBlockState state, long rand, int pass, ProcessingContext context) {
		EnumFacing face = quad.getFace();
		if (face == null || !supportsOverlay(quad, state)
				|| !processingPredicate.shouldProcessQuad(quad, sprite, level, pos, appearanceState, state, context)) {
			return ProcessingResult.NEXT_PROCESSOR;
		}

		SpriteCollector collector = spriteCollectors.get();
		collector.clear();
		getSprites(level, pos, appearanceState, state, face, sprite,
				DirectionMaps.getMap(face)[0], context, collector);
		if (collector.spriteAmount != 0) {
			int tintIndex = properties.getTintIndex();
			boolean tintOverride = properties.getTintBlock() != null && tintIndex >= 0;
			int tintColor = tintOverride
					? Minecraft.getMinecraft().getBlockColors().colorMultiplier(properties.getTintBlock(), level, pos, tintIndex)
					: -1;
			if (tintOverride) {
				tintIndex = -1;
			}
			for (int i = 0; i < collector.spriteAmount; i++) {
				TextureAtlasSprite overlaySprite = collector.sprites[i];
				int[] vertexData = quad.getVertexData().clone();
				writeCanonicalFaceUvs(vertexData, quad, overlaySprite, state.getBlock() == Blocks.GRASS_PATH);
				writeWhiteVertexColors(vertexData, quad.getFormat());
				context.getExtraQuads().add(new OverlayBakedQuad(vertexData, tintIndex, face, overlaySprite,
						quad.shouldApplyDiffuseLighting(), quad.getFormat(), properties.getLayer(), tintOverride, tintColor));
			}
			collector.clear();
		}
		return ProcessingResult.NEXT_PROCESSOR;
	}

	/**
	 * Overlay tiles are oriented against the world face, independently of the base model's UV
	 * rotation. This matches the canonical face-square winding used by the standard overlay format.
	 */
	private static void writeCanonicalFaceUvs(int[] vertexData, BakedQuad quad, TextureAtlasSprite sprite,
			boolean grassPath) {
		VertexFormat format = quad.getFormat();
		if (!format.hasUvOffset(0)) {
			return;
		}
		int uvOffset = format.getUvOffsetById(0) / 4;
		int stride = format.getIntegerSize();
		float minU = sprite.getMinU();
		float minV = sprite.getMinV();
		float uSpan = sprite.getMaxU() - minU;
		float vSpan = sprite.getMaxV() - minV;
		EnumFacing face = quad.getFace();
		for (int vertex = 0; vertex < 4; vertex++) {
			float x = QuadUtil.positionComponent(quad, vertex, 0);
			float y = QuadUtil.positionComponent(quad, vertex, 1);
			float z = QuadUtil.positionComponent(quad, vertex, 2);
			float vertical = grassPath ? y / GRASS_PATH_HEIGHT : y;
			float u;
			float v;
			switch (face) {
				case DOWN -> { u = x; v = 1.0f - z; }
				case UP -> { u = x; v = z; }
				case WEST -> { u = z; v = 1.0f - vertical; }
				case EAST -> { u = 1.0f - z; v = 1.0f - vertical; }
				case NORTH -> { u = 1.0f - x; v = 1.0f - vertical; }
				case SOUTH -> { u = x; v = 1.0f - vertical; }
				default -> throw new IllegalArgumentException("Unsupported overlay face: " + face);
			}
			int index = vertex * stride + uvOffset;
			vertexData[index] = Float.floatToIntBits(minU + u * uSpan);
			vertexData[index + 1] = Float.floatToIntBits(minV + v * vSpan);
		}
	}

	private static void writeWhiteVertexColors(int[] vertexData, VertexFormat format) {
		int colorOffset = -1;
		for (int i = 0; i < format.getElementCount(); i++) {
			if (format.getElement(i).getUsage() == VertexFormatElement.EnumUsage.COLOR) {
				colorOffset = format.getOffset(i) / 4;
				break;
			}
		}
		if (colorOffset < 0) {
			return;
		}
		int stride = format.getIntegerSize();
		for (int i = 0; i < 4; i++) {
			vertexData[i * stride + colorOffset] = 0xFFFFFFFF;
		}
	}

	public static boolean isUnitSquare(BakedQuad quad) {
		EnumFacing face = quad.getFace();
		if (face == null) {
			return false;
		}
		int normalAxis = axisIndex(face.getAxis());
		float plane = face.getAxisDirection() == EnumFacing.AxisDirection.POSITIVE ? 1.0f : 0.0f;
		int axisA = (normalAxis + 1) % 3;
		int axisB = (normalAxis + 2) % 3;
		int corners = 0;
		for (int i = 0; i < 4; i++) {
			float normal = QuadUtil.positionComponent(quad, i, normalAxis);
			float a = QuadUtil.positionComponent(quad, i, axisA);
			float b = QuadUtil.positionComponent(quad, i, axisB);
			if (Math.abs(normal - plane) > 0.0001f || !isUnitEdge(a) || !isUnitEdge(b)) {
				return false;
			}
			corners |= 1 << ((a > 0.5f ? 1 : 0) | (b > 0.5f ? 2 : 0));
		}
		return corners == 0b1111;
	}

	/** Includes the 15/16-height faces of 1.12.2's grass-path block. */
	public static boolean supportsOverlay(BakedQuad quad, IBlockState state) {
		if (isUnitSquare(quad)) {
			return true;
		}
		return state.getBlock() == Blocks.GRASS_PATH && isGrassPathFace(quad);
	}

	private static boolean isGrassPathFace(BakedQuad quad) {
		EnumFacing face = quad.getFace();
		if (face == null) {
			return false;
		}
		int normalAxis = axisIndex(face.getAxis());
		float expectedPlane;
		if (face == EnumFacing.UP) {
			expectedPlane = GRASS_PATH_HEIGHT;
		} else if (face == EnumFacing.DOWN) {
			expectedPlane = 0.0f;
		} else {
			expectedPlane = face.getAxisDirection() == EnumFacing.AxisDirection.POSITIVE ? 1.0f : 0.0f;
		}
		float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
		float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
		for (int vertex = 0; vertex < 4; vertex++) {
			for (int axis = 0; axis < 3; axis++) {
				float value = QuadUtil.positionComponent(quad, vertex, axis);
				min[axis] = Math.min(min[axis], value);
				max[axis] = Math.max(max[axis], value);
			}
		}
		if (Math.abs(min[normalAxis] - expectedPlane) > 0.0001f
				|| Math.abs(max[normalAxis] - expectedPlane) > 0.0001f) {
			return false;
		}
		int axisA = (normalAxis + 1) % 3;
		int axisB = (normalAxis + 2) % 3;
		if (!isExpectedGrassPathExtent(axisA, min[axisA], max[axisA])
				|| !isExpectedGrassPathExtent(axisB, min[axisB], max[axisB])) {
			return false;
		}
		int corners = 0;
		for (int vertex = 0; vertex < 4; vertex++) {
			float a = QuadUtil.positionComponent(quad, vertex, axisA);
			float b = QuadUtil.positionComponent(quad, vertex, axisB);
			int aCorner = Math.abs(a - max[axisA]) <= 0.0001f ? 1 : 0;
			int bCorner = Math.abs(b - max[axisB]) <= 0.0001f ? 1 : 0;
			corners |= 1 << (aCorner | (bCorner << 1));
		}
		return corners == 0b1111;
	}

	private static boolean isExpectedGrassPathExtent(int axis, float min, float max) {
		float expectedMax = axis == 1 ? GRASS_PATH_HEIGHT : 1.0f;
		return Math.abs(min) <= 0.0001f && Math.abs(max - expectedMax) <= 0.0001f;
	}

	private static int axisIndex(EnumFacing.Axis axis) {
		return switch (axis) {
			case X -> 0;
			case Y -> 1;
			case Z -> 2;
		};
	}

	private static boolean isUnitEdge(float value) {
		return Math.abs(value) <= 0.0001f || Math.abs(value - 1.0f) <= 0.0001f;
	}

	private static boolean matchesAny(Set<ResourceLocation> tiles, IBlockState state, EnumFacing face) {
		IBakedModel model = Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes().getModelForState(state);
		if (model == null) {
			return false;
		}
		if (containsAny(tiles, model.getQuads(state, face, 0))) {
			return true;
		}
		return containsAny(tiles, model.getQuads(state, null, 0));
	}

	private static boolean containsAny(Set<ResourceLocation> tiles, java.util.List<BakedQuad> quads) {
		for (BakedQuad quad : quads) {
			TextureAtlasSprite sprite = quad.getSprite();
			if (sprite != null && tiles.contains(new ResourceLocation(sprite.getIconName()))) {
				return true;
			}
		}
		return false;
	}

	private boolean appliesOverlay(BlockPos otherPos, IBlockState otherAppearanceState, IBlockState otherState,
			IBlockAccess level, BlockPos pos, IBlockState appearanceState, IBlockState state,
			EnumFacing face, TextureAtlasSprite quadSprite) {
		if (!otherState.isFullCube()) {
			return false;
		}
		if (connectBlocksPredicate != null && !connectBlocksPredicate.test(otherAppearanceState)) {
			return false;
		}
		if (connectTilesSet != null && !matchesAny(connectTilesSet, otherAppearanceState, face)) {
			return false;
		}
		return !connectionPredicate.shouldConnect(level, pos, appearanceState, state, otherPos,
				otherAppearanceState, otherState, face, quadSprite);
	}

	private boolean hasSameOverlay(@Nullable IBlockState otherAppearanceState, EnumFacing face) {
		if (otherAppearanceState == null) {
			return false;
		}
		if (matchBlocksPredicate != null && !matchBlocksPredicate.test(otherAppearanceState)) {
			return false;
		}
		return matchTilesSet == null || matchesAny(matchTilesSet, otherAppearanceState, face);
	}

	private boolean appliesOverlayCorner(EnumFacing dir0, EnumFacing dir1, BlockPos.MutableBlockPos mutablePos,
			IBlockAccess level, BlockPos pos, IBlockState appearanceState, IBlockState state,
			EnumFacing face, TextureAtlasSprite quadSprite) {
		mutablePos.setPos(pos).move(dir0).move(dir1);
		IBlockState otherState = level.getBlockState(mutablePos);
		IBlockState otherAppearanceState = getAppearanceState(level, mutablePos, otherState);
		if (appliesOverlay(mutablePos, otherAppearanceState, otherState, level, pos,
				appearanceState, state, face, quadSprite)) {
			mutablePos.move(face);
			return !level.getBlockState(mutablePos).isOpaqueCube();
		}
		return false;
	}

	private SpriteCollector fromTwoSidesAdj(SpriteCollector collector,
			@Nullable IBlockState appearanceState0, @Nullable IBlockState appearanceState1,
			EnumFacing dir0, EnumFacing dir1, int sprite, int spriteCorner,
			BlockPos.MutableBlockPos mutablePos, IBlockAccess level, BlockPos pos,
			IBlockState appearanceState, IBlockState state, EnumFacing face, TextureAtlasSprite quadSprite) {
		collector.add(sprites[sprite]);
		if ((hasSameOverlay(appearanceState0, face) || hasSameOverlay(appearanceState1, face))
				&& appliesOverlayCorner(dir0, dir1, mutablePos, level, pos, appearanceState,
						state, face, quadSprite)) {
			collector.add(sprites[spriteCorner]);
		}
		return collector;
	}

	private SpriteCollector fromOneSide(SpriteCollector collector,
			@Nullable IBlockState appearanceState0, @Nullable IBlockState appearanceState1,
			@Nullable IBlockState appearanceState2, EnumFacing dir0, EnumFacing dir1,
			EnumFacing dir2, int sprite, int spriteCorner01, int spriteCorner12,
			BlockPos.MutableBlockPos mutablePos, IBlockAccess level, BlockPos pos,
			IBlockState appearanceState, IBlockState state, EnumFacing face, TextureAtlasSprite quadSprite) {
		boolean corner01;
		boolean corner12;
		if (hasSameOverlay(appearanceState1, face)) {
			corner01 = true;
			corner12 = true;
		} else {
			corner01 = hasSameOverlay(appearanceState0, face);
			corner12 = hasSameOverlay(appearanceState2, face);
		}
		collector.add(sprites[sprite]);
		if (corner01 && appliesOverlayCorner(dir0, dir1, mutablePos, level, pos,
				appearanceState, state, face, quadSprite)) {
			collector.add(sprites[spriteCorner01]);
		}
		if (corner12 && appliesOverlayCorner(dir1, dir2, mutablePos, level, pos,
				appearanceState, state, face, quadSprite)) {
			collector.add(sprites[spriteCorner12]);
		}
		return collector;
	}

	private SpriteCollector prepare(SpriteCollector collector, int sprite) {
		collector.add(sprites[sprite]);
		return collector;
	}

	private SpriteCollector prepare(SpriteCollector collector, int sprite0, int sprite1) {
		collector.add(sprites[sprite0]);
		collector.add(sprites[sprite1]);
		return collector;
	}

	@Nullable
	private SpriteCollector getSprites(IBlockAccess level, BlockPos pos, IBlockState appearanceState,
			IBlockState state, EnumFacing face, TextureAtlasSprite quadSprite, EnumFacing[] directions,
			ProcessingDataProvider dataProvider, SpriteCollector collector) throws IllegalStateException {
		BlockPos.MutableBlockPos mutablePos = dataProvider.getData(ProcessingDataKeys.MUTABLE_POS);
		int applications = 0;
		IBlockState[] adjacentAppearanceStates = new IBlockState[4];

		for (int i = 0; i < 4; i++) {
			mutablePos.setPos(pos).move(directions[i]).move(face);
			if (!level.getBlockState(mutablePos).isOpaqueCube()) {
				mutablePos.setPos(pos).move(directions[i]);
				IBlockState otherState = level.getBlockState(mutablePos);
				IBlockState otherAppearanceState = getAppearanceState(level, mutablePos, otherState);
				adjacentAppearanceStates[i] = otherAppearanceState;
				if (appliesOverlay(mutablePos, otherAppearanceState, otherState, level, pos,
						appearanceState, state, face, quadSprite)) {
					applications |= 1 << i;
				}
			}
		}

		// 0: corner D+R, 1: D, 2: corner L+D, 3: D+R, 4: L+D, 5: L+D+R,
		// 6: L+D+U, 7: R, 8: all, 9: L, 10: R+U, 11: L+U,
		// 12: D+R+U, 13: L+R+U, 14: corner R+U, 15: U, 16: corner L+U.
		switch (applications) {
			case 0b1111: return prepare(collector, 8);
			case 0b0111: return prepare(collector, 5);
			case 0b1011: return prepare(collector, 6);
			case 0b1101: return prepare(collector, 13);
			case 0b1110: return prepare(collector, 12);
			case 0b0101: return prepare(collector, 9, 7);
			case 0b1010: return prepare(collector, 1, 15);
			case 0b0011: return fromTwoSidesAdj(collector, adjacentAppearanceStates[2], adjacentAppearanceStates[3], directions[2], directions[3], 4, 14, mutablePos, level, pos, appearanceState, state, face, quadSprite);
			case 0b0110: return fromTwoSidesAdj(collector, adjacentAppearanceStates[3], adjacentAppearanceStates[0], directions[3], directions[0], 3, 16, mutablePos, level, pos, appearanceState, state, face, quadSprite);
			case 0b1100: return fromTwoSidesAdj(collector, adjacentAppearanceStates[0], adjacentAppearanceStates[1], directions[0], directions[1], 10, 2, mutablePos, level, pos, appearanceState, state, face, quadSprite);
			case 0b1001: return fromTwoSidesAdj(collector, adjacentAppearanceStates[1], adjacentAppearanceStates[2], directions[1], directions[2], 11, 0, mutablePos, level, pos, appearanceState, state, face, quadSprite);
			case 0b0001: return fromOneSide(collector, adjacentAppearanceStates[1], adjacentAppearanceStates[2], adjacentAppearanceStates[3], directions[1], directions[2], directions[3], 9, 0, 14, mutablePos, level, pos, appearanceState, state, face, quadSprite);
			case 0b0010: return fromOneSide(collector, adjacentAppearanceStates[2], adjacentAppearanceStates[3], adjacentAppearanceStates[0], directions[2], directions[3], directions[0], 1, 14, 16, mutablePos, level, pos, appearanceState, state, face, quadSprite);
			case 0b0100: return fromOneSide(collector, adjacentAppearanceStates[3], adjacentAppearanceStates[0], adjacentAppearanceStates[1], directions[3], directions[0], directions[1], 7, 16, 2, mutablePos, level, pos, appearanceState, state, face, quadSprite);
			case 0b1000: return fromOneSide(collector, adjacentAppearanceStates[0], adjacentAppearanceStates[1], adjacentAppearanceStates[2], directions[0], directions[1], directions[2], 15, 2, 0, mutablePos, level, pos, appearanceState, state, face, quadSprite);
			case 0b0000: {
				boolean s0 = hasSameOverlay(adjacentAppearanceStates[0], face);
				boolean s1 = hasSameOverlay(adjacentAppearanceStates[1], face);
				boolean s2 = hasSameOverlay(adjacentAppearanceStates[2], face);
				boolean s3 = hasSameOverlay(adjacentAppearanceStates[3], face);
				boolean c01 = (s0 || s1) && appliesOverlayCorner(directions[0], directions[1], mutablePos, level, pos, appearanceState, state, face, quadSprite);
				boolean c12 = (s1 || s2) && appliesOverlayCorner(directions[1], directions[2], mutablePos, level, pos, appearanceState, state, face, quadSprite);
				boolean c23 = (s2 || s3) && appliesOverlayCorner(directions[2], directions[3], mutablePos, level, pos, appearanceState, state, face, quadSprite);
				boolean c30 = (s3 || s0) && appliesOverlayCorner(directions[3], directions[0], mutablePos, level, pos, appearanceState, state, face, quadSprite);
				if (c01 || c12 || c23 || c30) {
					if (c01) collector.add(sprites[2]);
					if (c12) collector.add(sprites[0]);
					if (c23) collector.add(sprites[14]);
					if (c30) collector.add(sprites[16]);
					return collector;
				}
				return null;
			}
			default: throw new IllegalStateException("Unexpected overlay connection mask: " + applications);
		}
	}

	private static IBlockState getAppearanceState(IBlockAccess level, BlockPos pos, IBlockState state) {
		IBlockState actualState = state.getActualState(level, pos);
		return actualState.getBlock().getExtendedState(actualState, level, pos);
	}

	private static final class SpriteCollector {
		private final TextureAtlasSprite[] sprites = new TextureAtlasSprite[4];
		private int spriteAmount;

		private void add(@Nullable TextureAtlasSprite sprite) {
			if (sprite != null && spriteAmount < sprites.length) {
				sprites[spriteAmount++] = sprite;
			}
		}

		private void clear() {
			java.util.Arrays.fill(sprites, null);
			spriteAmount = 0;
		}
	}

	public static final class Factory extends AbstractQuadProcessorFactory<StandardOverlayCtmProperties> {
		@Override
		public QuadProcessor createProcessor(StandardOverlayCtmProperties properties, TextureAtlasSprite[] sprites) {
			return new StandardOverlayQuadProcessor(sprites, properties);
		}

		@Override
		public int getSpriteAmount(StandardOverlayCtmProperties properties) {
			return 17;
		}

		@Override
		public boolean supportsNullSprites(StandardOverlayCtmProperties properties) {
			return false;
		}
	}
}
