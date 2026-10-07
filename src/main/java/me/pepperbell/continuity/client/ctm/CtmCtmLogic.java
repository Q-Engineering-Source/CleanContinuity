package me.pepperbell.continuity.client.ctm;

/**
 * The classic CTM "magic number" logic: maps the 8-bit connection map of a face to the four
 * sub-regions used to render the face. Transcribed from the CTM Mod format's {@code CTMLogic}.
 *
 * <p>Sub-regions are indexed into two sheets: indices 0-15 are 4x4 cells of the CTM sheet
 * (sprite 1), indices 16-19 are 8x8 quadrants of the base texture (sprite 0).
 */
public final class CtmCtmLogic {
	/** magic sub-regions: 0-15 = 4x4 cells of the CTM sheet, 16-19 = quadrants of the base texture */
	public static final CtmSubmap[] UVS = new CtmSubmap[20];

	static {
		for (int i = 0; i < 16; i++) {
			UVS[i] = CtmSubmap.fromPixelScale(4, 4, (i % 4) * 4, (i / 4) * 4);
		}
		UVS[16] = CtmSubmap.fromPixelScale(8, 8, 0, 0);
		UVS[17] = CtmSubmap.fromPixelScale(8, 8, 8, 0);
		UVS[18] = CtmSubmap.fromPixelScale(8, 8, 0, 8);
		UVS[19] = CtmSubmap.fromPixelScale(8, 8, 8, 8);
	}

	private static final int[] SUBMAP_OFFSETS = {4, 5, 1, 0};

	private static final CtmDir[][] SUBMAP_MAP = {
			{CtmDir.BOTTOM, CtmDir.LEFT, CtmDir.BOTTOM_LEFT},
			{CtmDir.BOTTOM, CtmDir.RIGHT, CtmDir.BOTTOM_RIGHT},
			{CtmDir.TOP, CtmDir.RIGHT, CtmDir.TOP_RIGHT},
			{CtmDir.TOP, CtmDir.LEFT, CtmDir.TOP_LEFT}
	};

	private CtmCtmLogic() {
	}

	/**
	 * Computes the 4 sub-region indices for a face from its 8-bit connection map.
	 *
	 * @return array of 4 indices (0-19), ordered bottom-left, bottom-right, top-right, top-left
	 */
	public static int[] getSubmapIndices(int connections, CtmConnectionMap connectionMap) {
		return getSubmapIndices(connections, connectionMap, false);
	}

	/**
	 * The CTM Vintage {@code edges} logic also uses an isolated diagonal connection to select the
	 * corresponding corner tile. Classic CTM leaves that quadrant on the base texture.
	 */
	public static int[] getSubmapIndices(int connections, CtmConnectionMap connectionMap, boolean includeIsolatedCorners) {
		int[] submapCache = {18, 19, 17, 16};
		for (int i = 0; i < 4; i++) {
			fillSubmap(submapCache, i, connections, connectionMap, includeIsolatedCorners);
		}
		return submapCache;
	}

	private static void fillSubmap(int[] submapCache, int idx, int connections, CtmConnectionMap connectionMap,
			boolean includeIsolatedCorners) {
		CtmDir[] dirs = SUBMAP_MAP[idx];
		if (includeIsolatedCorners && !connectionMap.connectedOr(connections, dirs[0], dirs[1])
				&& connectionMap.connected(connections, dirs[2])) {
			submapCache[idx] = SUBMAP_OFFSETS[idx];
			return;
		}
		if (connectionMap.connectedOr(connections, dirs[0], dirs[1])) {
			if (connectionMap.connectedAnd(connections, dirs)) {
				// All three (both edges + corner) connected -> base cell
				submapCache[idx] = SUBMAP_OFFSETS[idx];
			} else {
				// Magic offsets: first edge dir adds 2, second adds 8
				submapCache[idx] = SUBMAP_OFFSETS[idx]
						+ (connectionMap.connected(connections, dirs[0]) ? 2 : 0)
						+ (connectionMap.connected(connections, dirs[1]) ? 8 : 0);
			}
		}
	}

	public static boolean isDefaultTexture(int id) {
		return id >= 16 && id <= 19;
	}
}
