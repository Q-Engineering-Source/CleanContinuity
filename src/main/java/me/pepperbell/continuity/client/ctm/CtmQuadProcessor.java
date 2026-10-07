package me.pepperbell.continuity.client.ctm;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import javax.annotation.Nullable;

import me.pepperbell.continuity.api.client.QuadProcessor;
import me.pepperbell.continuity.client.model.BakedQuadLightmap;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.IBlockAccess;

/**
 * Processes quads for textures that have a CTM Mod format definition. This is the CTM-format
 * counterpart of {@code SimpleQuadProcessor}: it computes the connection state for a quad's face
 * and emits the appropriately sub-regioned quads.
 *
 * <p>Each CTM type is handled here (delegating per-type decisions to small helper methods), and
 * the output quads are produced by {@link QuadClipper} plus {@code transformUVs} remapping.</p>
 */
public class CtmQuadProcessor implements QuadProcessor {
	protected final CtmDefinition properties;
	protected final TextureAtlasSprite[] sprites;
	protected final CtmConnectionPredicate connectionPredicate;
	protected final CtmConnectionMap connectionMap;
	protected final CtmType type;
	@Nullable
	protected final CtmCustomLogic logic;

	public CtmQuadProcessor(@Nullable CtmDefinition properties, TextureAtlasSprite[] sprites) {
		this.properties = properties;
		this.sprites = sprites;
		if (properties == null) {
			// Test / fallback construction: never connects
			this.type = CtmType.NORMAL;
			this.logic = null;
			this.connectionPredicate = new CtmConnectionPredicate(false, false, true) {
				@Override
				public boolean shouldConnect(IBlockAccess level, BlockPos pos, IBlockState appearanceState, IBlockState state, BlockPos otherPos, IBlockState otherAppearanceState, IBlockState otherState, EnumFacing face, TextureAtlasSprite quadSprite) {
					return false;
				}
			};
			this.connectionMap = new CtmConnectionMap(connectionPredicate);
			return;
		}
		boolean disableObscured = properties.getType() == CtmType.SCTM;
		this.connectionPredicate = CtmConnectionPredicate.fromProperties(properties, disableObscured);
		this.connectionMap = new CtmConnectionMap(connectionPredicate);
		this.type = properties.getType();
		this.logic = properties.getLogic();
	}

	@Override
	public ProcessingResult processQuad(BakedQuad quad, TextureAtlasSprite sprite, IBlockAccess level, BlockPos pos, IBlockState appearanceState, IBlockState state, long rand, int pass, ProcessingContext context) {
		List<BakedQuad> out = context.getExtraQuads();
		out.clear();
		transformQuad(quad, sprite, level, pos, appearanceState, state, rand, out);
		if (out.isEmpty()) {
			return ProcessingResult.DISCARD;
		}
		return ProcessingResult.NEXT_PASS;
	}

	protected void transformQuad(BakedQuad quad, TextureAtlasSprite sprite, IBlockAccess level, BlockPos pos, IBlockState appearanceState, IBlockState state, long rand, List<BakedQuad> out) {
		// CTM Vintage proxy metadata replaces the base sprite before the texture implementation
		// transforms UVs. Mirror that here while keeping the source model's geometry intact.
		if (sprite != null && sprites.length > 0
				&& !sprite.getIconName().equals(sprites[0].getIconName())) {
			quad = QuadClipper.transformUVs(quad, sprite, sprites[0], CtmSubmap.X1);
			sprite = sprites[0];
		}
		EnumFacing face = quad.getFace();
		switch (type) {
			case NORMAL -> handleNormal(quad, sprite, out);
			case CTM -> handleCtm(quad, sprite, level, pos, appearanceState, state, face, out);
			case SCTM -> handleSctm(quad, sprite, level, pos, appearanceState, state, face, out);
			case HORIZONTAL, VERTICAL -> handlePlane(quad, sprite, level, pos, appearanceState, state, face, out);
			case PILLAR -> handlePillar(quad, sprite, level, pos, appearanceState, state, face, out);
			case RANDOM, PATTERN -> handleMap(quad, sprite, level, pos, appearanceState, state, face, rand, out);
			case EDGES -> handleEdges(quad, sprite, level, pos, appearanceState, state, face, out);
			case EDGES_FULL -> handleEdgesFull(quad, sprite, level, pos, appearanceState, state, face, out);
			case ELDRITCH -> handleEldritch(quad, sprite, level, pos, appearanceState, state, rand, out);
			default -> {
				if (logic != null) {
					handleCustomLogic(quad, sprite, level, pos, appearanceState, state, face, out);
				} else {
					out.add(quad);
				}
			}
		}
		if (properties != null && properties.hasLight()) {
			out.replaceAll(this::withConfiguredLight);
		}
	}

	private BakedQuad withConfiguredLight(BakedQuad quad) {
		return BakedQuadLightmap.withMinimum(quad, properties.getBlocklight(), properties.getSkylight());
	}

	protected void handleNormal(BakedQuad quad, TextureAtlasSprite sprite, List<BakedQuad> out) {
		out.add(quad);
	}

	protected void handleCtm(BakedQuad quad, TextureAtlasSprite sprite, IBlockAccess level, BlockPos pos, IBlockState appearanceState, IBlockState state, EnumFacing face, List<BakedQuad> out) {
		int connections = connectionMap.compute(level, pos, appearanceState, state, face, sprite);
		handleCtmWithConnections(quad, sprite, connections, out, false);
	}

	/** Keeps the original classic-CTM helper signature used by existing callers and tests. */
	protected void handleCtmWithConnections(BakedQuad quad, TextureAtlasSprite sprite, int connections,
			List<BakedQuad> out) {
		handleCtmWithConnections(quad, sprite, connections, out, false);
	}

	protected void handleCtmWithConnections(BakedQuad quad, TextureAtlasSprite sprite, int connections,
			List<BakedQuad> out, boolean includeIsolatedCorners) {
		int[] submapIndices = CtmCtmLogic.getSubmapIndices(connections, connectionMap, includeIsolatedCorners);

		TextureAtlasSprite baseSprite = sprites[0];
		TextureAtlasSprite ctmSheet = sprites.length > 1 ? sprites[1] : baseSprite;

		BakedQuad[] quadrants = QuadClipper.subdivide4(quad, baseSprite);
		for (int i = 0; i < 4; i++) {
			BakedQuad q = quadrants[i];
			if (q == null) {
				continue;
			}
			int quadrant = QuadClipper.getQuadrant(q, baseSprite);
			int ctmid = submapIndices[quadrant];
			// magic indices 16-19 use the base texture's quadrants; 0-15 use the CTM sheet's 4x4 cells
			TextureAtlasSprite target = CtmCtmLogic.isDefaultTexture(ctmid) ? baseSprite : ctmSheet;
			q = QuadClipper.grow(q, baseSprite);
			q = QuadClipper.transformUVs(q, baseSprite, target, CtmCtmLogic.UVS[ctmid]);
			out.add(q);
		}
	}

	protected void handleSctm(BakedQuad quad, TextureAtlasSprite sprite, IBlockAccess level, BlockPos pos, IBlockState appearanceState, IBlockState state, EnumFacing face, List<BakedQuad> out) {
		int connections = connectionMap.compute(level, pos, appearanceState, state, face, sprite);
		CtmSubmap cell = getSctmCell(connections);
		TextureAtlasSprite base = sprites[0];
		out.add(QuadClipper.transformUVs(quad, base, base, cell));
	}

	/** SCTM single-quad selection table over the 2x2 grid. */
	protected CtmSubmap getSctmCell(int connections) {
		CtmSubmap[][] x2 = CtmSubmap.x2Grid();
		boolean top = connectionMap.connected(connections, CtmDir.TOP);
		boolean bottom = connectionMap.connected(connections, CtmDir.BOTTOM);
		boolean left = connectionMap.connected(connections, CtmDir.LEFT);
		boolean right = connectionMap.connected(connections, CtmDir.RIGHT);

		if (top || bottom || left || right) {
			if (!top || !bottom) {
				// A vertical edge exists
				return x2[0][left && right ? 1 : 0];
			}
			if (!left || !right) {
				// A horizontal edge exists (and both vertical)
				return x2[1][0];
			}
			if (connectionMap.connected(connections, CtmDir.TOP_LEFT) && connectionMap.connected(connections, CtmDir.TOP_RIGHT)) {
				if (connectionMap.connected(connections, CtmDir.BOTTOM_LEFT) && connectionMap.connected(connections, CtmDir.BOTTOM_RIGHT)) {
					return x2[1][1];
				}
			}
		}
		return x2[0][0];
	}

	protected void handlePlane(BakedQuad quad, TextureAtlasSprite sprite, IBlockAccess level, BlockPos pos, IBlockState appearanceState, IBlockState state, EnumFacing face, List<BakedQuad> out) {
		int connections = connectionMap.compute(level, pos, appearanceState, state, face, sprite);
		CtmSubmap cell = getPlaneCell(connections, type == CtmType.VERTICAL);
		TextureAtlasSprite base = sprites[0];
		out.add(QuadClipper.transformUVs(quad, base, base, cell));
	}

	/** Plane (horizontal/vertical) 2x2 cell selection. Vertical tests TOP/BOTTOM, horizontal tests LEFT/RIGHT. */
	protected CtmSubmap getPlaneCell(int connections, boolean vertical) {
		CtmSubmap[][] x2 = CtmSubmap.x2Grid();
		if (vertical) {
			boolean top = connectionMap.connected(connections, CtmDir.TOP);
			boolean bottom = connectionMap.connected(connections, CtmDir.BOTTOM);
			int u = (top == bottom) ? 0 : 1;
			int v = top ? 1 : 0;
			return x2[v][u];
		} else {
			boolean left = connectionMap.connected(connections, CtmDir.LEFT);
			boolean right = connectionMap.connected(connections, CtmDir.RIGHT);
			int u = left ? 1 : 0;
			int v = (left == right) ? 0 : 1;
			return x2[v][u];
		}
	}

	protected void handlePillar(BakedQuad quad, TextureAtlasSprite sprite, IBlockAccess level, BlockPos pos, IBlockState appearanceState, IBlockState state, EnumFacing face, List<BakedQuad> out) {
		// CTM Vintage pillar checks raw IBlockState identity and does not use the custom CTM
		// connection options (connect_to, ignore_states, or use_actual_state).
		TextureAtlasSprite base = sprites[0];
		TextureAtlasSprite pillar = sprites.length > 1 ? sprites[1] : base;
		IBlockState pillarState = level.getBlockState(pos);

		// connections of the current block per facing
		EnumSet<EnumFacing> connections = EnumSet.noneOf(EnumFacing.class);
		for (EnumFacing f : EnumFacing.VALUES) {
			if (level.getBlockState(pos.offset(f)) == pillarState) {
				connections.add(f);
			}
		}

		// per-neighbor connection sets (for the blockConnectionY/Z pruning)
		Map<EnumFacing, EnumSet<EnumFacing>> neighborConnections = new EnumMap<>(EnumFacing.class);
		for (EnumFacing f : EnumFacing.VALUES) {
			BlockPos other = pos.offset(f);
			EnumSet<EnumFacing> set = EnumSet.noneOf(EnumFacing.class);
			if (level.getBlockState(other) == pillarState) {
				for (EnumFacing f2 : EnumFacing.VALUES) {
					if (level.getBlockState(other.offset(f2)) == pillarState) {
						set.add(f2);
					}
				}
			}
			neighborConnections.put(f, set);
		}

		// Prune connections by priority
		EnumSet<EnumFacing> real = EnumSet.copyOf(connections);
		if (connectedOr(real, EnumFacing.UP, EnumFacing.DOWN)) {
			real.removeIf(f -> f.getAxis().isHorizontal());
		} else if (connectedOr(real, EnumFacing.EAST, EnumFacing.WEST)) {
			real.removeIf(f -> f == EnumFacing.NORTH || f == EnumFacing.SOUTH);
			real.removeIf(f -> blockConnectionZ(f, neighborConnections));
		} else {
			real.removeIf(f -> blockConnectionY(f, neighborConnections));
		}

		int rotation = 0;
		CtmSubmap uvs = CtmSubmap.x2Grid()[0][0];
		if (face.getAxis().isHorizontal() && connectedOr(real, EnumFacing.UP, EnumFacing.DOWN)) {
			uvs = pillarUvs(real, EnumFacing.UP, EnumFacing.DOWN);
		} else if (connectedOr(real, EnumFacing.EAST, EnumFacing.WEST)) {
			rotation = 1;
			uvs = pillarUvs(real, EnumFacing.EAST, EnumFacing.WEST);
		} else if (connectedOr(real, EnumFacing.NORTH, EnumFacing.SOUTH)) {
			uvs = pillarUvs(real, EnumFacing.NORTH, EnumFacing.SOUTH);
			if (face == EnumFacing.DOWN) {
				rotation += 2;
			}
		}

		boolean connected = !real.isEmpty();
		if (connected && !connectedOr(real, EnumFacing.UP, EnumFacing.DOWN)) {
			if (face == EnumFacing.EAST) {
				rotation += 1;
			}
			if (face == EnumFacing.NORTH) {
				rotation += 2;
			}
			if (face == EnumFacing.WEST) {
				rotation += 3;
			}
		}
		// End cap: connection opposite this face -> render as unconnected base
		if (connected && real.contains(face.getOpposite())) {
			connected = false;
		}
		// Free-standing horizontal face -> short column texture
		if (real.isEmpty() && face.getAxis().isHorizontal()) {
			connected = true;
		}

		BakedQuad q = QuadClipper.rotate(quad, base, rotation);
		if (connected) {
			out.add(QuadClipper.transformUVs(q, base, pillar, uvs));
		} else {
			out.add(QuadClipper.transformUVs(q, base, base, CtmSubmap.X1));
		}
	}

	private static boolean connectedOr(EnumSet<EnumFacing> set, EnumFacing... facings) {
		for (EnumFacing f : facings) {
			if (set.contains(f)) {
				return true;
			}
		}
		return false;
	}

	private static boolean blockConnectionZ(EnumFacing dir, Map<EnumFacing, EnumSet<EnumFacing>> neighborConnections) {
		return blockConnection(dir, EnumFacing.Axis.Z, neighborConnections);
	}

	private static boolean blockConnectionY(EnumFacing dir, Map<EnumFacing, EnumSet<EnumFacing>> neighborConnections) {
		return blockConnection(dir, EnumFacing.Axis.Y, neighborConnections)
				|| blockConnection(dir, dir.rotateY().getAxis(), neighborConnections);
	}

	private static boolean blockConnection(EnumFacing dir, EnumFacing.Axis axis, Map<EnumFacing, EnumSet<EnumFacing>> neighborConnections) {
		EnumFacing rot = dir.rotateAround(axis);
		EnumSet<EnumFacing> set = neighborConnections.get(dir);
		return set != null && (set.contains(rot) || set.contains(rot.getOpposite()));
	}

	private static CtmSubmap pillarUvs(EnumSet<EnumFacing> set, EnumFacing face1, EnumFacing face2) {
		CtmSubmap[][] x2 = CtmSubmap.x2Grid();
		if (set.contains(face1) && set.contains(face2)) {
			return x2[1][0];
		} else if (set.contains(face1)) {
			return x2[1][1];
		} else {
			return x2[0][1];
		}
	}

	protected void handleCustomLogic(BakedQuad quad, TextureAtlasSprite sprite, IBlockAccess level, BlockPos pos, IBlockState appearanceState, IBlockState state, EnumFacing face, List<BakedQuad> out) {
		// Custom logic: build connection bitmask over the logic's input directions
		int key = 0;
		var directions = logic.getDirections();
		for (int i = 0; i < directions.size(); i++) {
			CtmCustomLogic.LocalDirection dir = directions.get(i);
			BlockPos otherPos = pos.add(dir.getOffset(face));
			if (connectionPredicate.shouldConnect(level, pos, appearanceState, state, otherPos, face, sprite)) {
				key |= 1 << i;
			}
		}
		int[] outputIds = logic.getOutputsForState(key);
		for (int outputId : outputIds) {
			CtmCustomLogic.OutputFace output = logic.getOutput(outputId);
			TextureAtlasSprite target = sprites[output.tex()];
			BakedQuad clipped = QuadClipper.clip(quad, sprite, output.face());
			clipped = QuadClipper.setUVs(clipped, target, output.uvs());
			out.add(clipped);
		}
	}

	protected void handleUnimplemented(BakedQuad quad, TextureAtlasSprite sprite, List<BakedQuad> out) {
		out.add(quad);
	}

	protected void handleMap(BakedQuad quad, TextureAtlasSprite sprite, IBlockAccess level, BlockPos pos, IBlockState appearanceState, IBlockState state, EnumFacing face, long rand, List<BakedQuad> out) {
		TextureAtlasSprite base = sprites[0];
		int width = properties.getMapWidth();
		int height = properties.getMapHeight();
		BlockPos mapPosition = pos.add(getFaceOffset(face, properties.getMapXOffset(), properties.getMapYOffset()));

		int x;
		int y;
		if (type == CtmType.RANDOM) {
			Random rng = new Random(MathHelper.getPositionRandom(mapPosition) + face.ordinal());
			rng.nextBoolean(); // consume one value to match the reference seeding
			x = rng.nextInt(width) + 1;
			y = rng.nextInt(height) + 1;
			// 1-based coords -> cell index
			CtmSubmap cell = CtmSubmap.fromUnitScale(1f / width, 1f / height,
					(x - 1) * (1f / width), (y - 1) * (1f / height));
			out.add(QuadClipper.transformUVs(quad, base, base, cell));
			return;
		}

		// Patterned: world-coordinate modulo
		int px = mapPosition.getX();
		int py = mapPosition.getY();
		int pz = mapPosition.getZ();
		int tx;
		int ty;
		EnumFacing.Axis faceAxis = face.getAxis();
		if (faceAxis == EnumFacing.Axis.Y) {
			tx = px % width;
			ty = (face.getYOffset() * pz + 1) % height;
		} else if (faceAxis == EnumFacing.Axis.Z) {
			tx = px % width;
			ty = -py % height;
		} else {
			tx = (pz + 1) % width;
			ty = -py % height;
		}
		if (face == EnumFacing.NORTH || face == EnumFacing.EAST) {
			tx = (width - tx - 1) % width;
		}
		if (tx < 0) {
			tx += width;
		}
		if (ty < 0) {
			ty += height;
		}

		CtmSubmap cell = CtmSubmap.fromUnitScale(1f / width, 1f / height,
				tx * (1f / width), ty * (1f / height));
		out.add(QuadClipper.transformUVs(quad, base, base, cell));
	}

	private static BlockPos getFaceOffset(EnumFacing face, int xOffset, int yOffset) {
		return switch (face) {
			case DOWN -> new BlockPos(xOffset, 0, yOffset);
			case NORTH -> new BlockPos(-xOffset, yOffset, 0);
			case SOUTH -> new BlockPos(xOffset, yOffset, 0);
			case WEST -> new BlockPos(0, yOffset, xOffset);
			case EAST -> new BlockPos(0, yOffset, -xOffset);
			default -> new BlockPos(xOffset, 0, -yOffset);
		};
	}

	protected void handleEdges(BakedQuad quad, TextureAtlasSprite sprite, IBlockAccess level, BlockPos pos, IBlockState appearanceState, IBlockState state, EnumFacing face, List<BakedQuad> out) {
		CtmEdgesConnectionMap.Result result = CtmEdgesConnectionMap.compute(level, pos, state, face, connectionPredicate);
		TextureAtlasSprite base = sprites[0];
		TextureAtlasSprite obscured = sprites.length > 2 ? sprites[2] : base;

		if (result.obscured()) {
			// Render the whole face from the obscured sprite, subdivided into 4
			for (BakedQuad q : QuadClipper.subdivide4(quad, base)) {
				out.add(QuadClipper.transformUVs(q, base, obscured, CtmSubmap.X1));
			}
			return;
		}

		// CTM Vintage gives an isolated diagonal a corner tile for the edges type.
		handleCtmWithConnections(quad, sprite, result.connections(), out, true);
	}

	protected void handleEdgesFull(BakedQuad quad, TextureAtlasSprite sprite, IBlockAccess level, BlockPos pos, IBlockState appearanceState, IBlockState state, EnumFacing face, List<BakedQuad> out) {
		// EdgesFull: 16-cell selection over the full 4x4 sheet, one quad per face.
		CtmEdgesConnectionMap.Result result = CtmEdgesConnectionMap.compute(level, pos, state, face, connectionPredicate);
		TextureAtlasSprite base = sprites[0];
		TextureAtlasSprite sheet = sprites.length > 1 ? sprites[1] : base;

		CtmSubmap[][] x4 = CtmSubmap.x4Grid();
		CtmSubmap cell = edgesFullCell(result.connections(), result.obscured(), x4);
		if (cell == null) {
			// full normal texture
			out.add(QuadClipper.transformUVs(quad, base, base, CtmSubmap.X1));
		} else {
			out.add(QuadClipper.transformUVs(quad, base, sheet, cell));
		}
	}

	/** Returns the 4x4 cell for the given connection map, or null for the full normal texture. */
	private CtmSubmap edgesFullCell(int connections, boolean isObscured, CtmSubmap[][] x4) {
		if (isObscured) {
			return x4[2][1];
		}
		boolean top = connectionMap.connected(connections, CtmDir.TOP)
				|| connectionMap.connectedAnd(connections, CtmDir.TOP_LEFT, CtmDir.TOP_RIGHT);
		boolean right = connectionMap.connected(connections, CtmDir.RIGHT)
				|| connectionMap.connectedAnd(connections, CtmDir.TOP_RIGHT, CtmDir.BOTTOM_RIGHT);
		boolean bottom = connectionMap.connected(connections, CtmDir.BOTTOM)
				|| connectionMap.connectedAnd(connections, CtmDir.BOTTOM_LEFT, CtmDir.BOTTOM_RIGHT);
		boolean left = connectionMap.connected(connections, CtmDir.LEFT)
				|| connectionMap.connectedAnd(connections, CtmDir.TOP_LEFT, CtmDir.BOTTOM_LEFT);

		boolean any = top || right || bottom || left
				|| connectionMap.connectedOr(connections, CtmDir.TOP_LEFT, CtmDir.TOP_RIGHT, CtmDir.BOTTOM_LEFT, CtmDir.BOTTOM_RIGHT);
		if (!any) {
			return null;
		}
		if ((top && bottom) || (right && left)) {
			return x4[2][1];
		}
		if (!top && !right && !bottom && !left) {
			if (connectionMap.connected(connections, CtmDir.TOP_LEFT) && connectionMap.connected(connections, CtmDir.BOTTOM_RIGHT)) {
				return x4[0][1];
			}
			if (connectionMap.connected(connections, CtmDir.TOP_RIGHT) && connectionMap.connected(connections, CtmDir.BOTTOM_LEFT)) {
				return x4[0][2];
			}
		}
		if (!bottom && !right
				&& connectionMap.connectedOr(connections, CtmDir.LEFT, CtmDir.BOTTOM_LEFT)
				&& connectionMap.connectedOr(connections, CtmDir.TOP, CtmDir.TOP_RIGHT)) {
			return x4[0][3];
		}
		if (!bottom && !left
				&& connectionMap.connectedOr(connections, CtmDir.TOP, CtmDir.TOP_LEFT)
				&& connectionMap.connectedOr(connections, CtmDir.RIGHT, CtmDir.BOTTOM_RIGHT)) {
			return x4[1][3];
		}
		if (!top && !left
				&& connectionMap.connectedOr(connections, CtmDir.RIGHT, CtmDir.TOP_RIGHT)
				&& connectionMap.connectedOr(connections, CtmDir.BOTTOM, CtmDir.BOTTOM_LEFT)) {
			return x4[2][3];
		}
		if (!top && !right
				&& connectionMap.connectedOr(connections, CtmDir.BOTTOM, CtmDir.BOTTOM_RIGHT)
				&& connectionMap.connectedOr(connections, CtmDir.LEFT, CtmDir.TOP_LEFT)) {
			return x4[3][3];
		}
		if (bottom) {
			return x4[1][1];
		}
		if (right) {
			return x4[2][0];
		}
		if (left) {
			return x4[2][2];
		}
		if (top) {
			return x4[3][1];
		}
		if (connectionMap.connected(connections, CtmDir.BOTTOM_LEFT)) {
			return x4[1][2];
		}
		if (connectionMap.connected(connections, CtmDir.BOTTOM_RIGHT)) {
			return x4[1][0];
		}
		if (connectionMap.connected(connections, CtmDir.TOP_RIGHT)) {
			return x4[3][0];
		}
		if (connectionMap.connected(connections, CtmDir.TOP_LEFT)) {
			return x4[3][2];
		}
		return null;
	}

	protected void handleEldritch(BakedQuad quad, TextureAtlasSprite sprite, IBlockAccess level, BlockPos pos, IBlockState appearanceState, IBlockState state, long rand, List<BakedQuad> out) {
		// Match CTM Vintage's deterministic Gaussian jitter, seeded by the wrapped 8x8 position
		// and the face normal. The shared inner corner is the only UV vertex moved in each tile.
		TextureAtlasSprite base = sprites[0];
		BlockPos wrapped = new BlockPos(pos.getX() & 7, pos.getY() & 7, pos.getZ() & 7);
		Random rng = new Random(MathHelper.getPositionRandom(wrapped) + quad.getFace().ordinal());
		float offsetU = (float) rng.nextGaussian() * 0.08f;
		float offsetV = (float) rng.nextGaussian() * 0.08f;
		for (BakedQuad q : QuadClipper.subdivide4(quad, base)) {
			out.add(QuadClipper.offsetInteriorUv(q, quad, base, offsetU, offsetV));
		}
	}

	public static class Factory implements QuadProcessor.Factory<CtmDefinition> {
		@Override
		public QuadProcessor createProcessor(CtmDefinition properties, Function<ResourceLocation, TextureAtlasSprite> spriteGetter) {
			List<ResourceLocation> spriteIds = properties.getSpriteIds();
			int amount = spriteIds.size();
			TextureAtlasSprite[] sprites = new TextureAtlasSprite[amount];
			for (int i = 0; i < amount; i++) {
				sprites[i] = spriteGetter.apply(spriteIds.get(i));
			}
			return new CtmQuadProcessor(properties, sprites);
		}
	}
}
