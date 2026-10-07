package me.pepperbell.continuity.client.ctm;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

import com.google.gson.JsonObject;

import me.pepperbell.continuity.api.client.CtmProperties;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;

/**
 * A parsed CTM Mod format definition for a single texture. This is the format-specific counterpart
 * of the OptiFine {@code BaseCtmProperties}: it holds everything parsed from a texture's
 * {@code .png.mcmeta} {@code "ctm"} section.
 */
public class CtmDefinition implements CtmProperties {
	protected final ResourceLocation resourceId;
	protected final String packId;
	protected final int packPriority;

	protected CtmType type = CtmType.NORMAL;
	protected BlockRenderLayer layer = null;
	protected String proxy;
	protected ResourceLocation[] additionalTextures = new ResourceLocation[0];
	protected JsonObject extraData = new JsonObject();

	// sprite ids: index 0 is the base texture (the .png whose mcmeta this is), the rest are additional textures
	protected List<ResourceLocation> spriteIds;
	protected Set<ResourceLocation> spriteDependencies;

	// extra-data options
	protected boolean ignoreStates;
	protected boolean useActualState;
	protected Boolean connectInside; // null = use per-type default
	protected boolean connectToDefined; // connect_to predicates present
	protected BiPredicate<EnumFacing, IBlockState> connectToPredicate;
	protected int blocklight;
	protected int skylight;
	protected boolean hasLight;
	protected boolean emissiveFallback;

	// map (random/pattern) options
	protected int mapWidth = 2;
	protected int mapHeight = 2;
	protected int mapXOffset;
	protected int mapYOffset;

	// custom logic (ctm_logic) support
	protected CtmCustomLogic logic;

	public CtmDefinition(ResourceLocation resourceId, String packId, int packPriority) {
		this.resourceId = resourceId;
		this.packId = packId;
		this.packPriority = packPriority;
	}

	@Override
	public Collection<ResourceLocation> getSpriteDependencies() {
		return spriteDependencies;
	}

	@Override
	public int compareTo(CtmProperties o) {
		// Higher priority first (mirrors BaseCtmProperties ordering)
		if (o instanceof CtmDefinition o1) {
			int c = Integer.compare(o1.packPriority, packPriority);
			if (c != 0) {
				return c;
			}
			return o1.resourceId.compareTo(resourceId);
		}
		return 0;
	}

	public ResourceLocation getResourceId() {
		return resourceId;
	}

	public String getPackId() {
		return packId;
	}

	public CtmType getType() {
		return type;
	}

	public BlockRenderLayer getLayer() {
		return layer;
	}

	public String getProxy() {
		return proxy;
	}

	public ResourceLocation[] getAdditionalTextures() {
		return additionalTextures;
	}

	public List<ResourceLocation> getSpriteIds() {
		return spriteIds;
	}

	public boolean isIgnoreStates() {
		return ignoreStates;
	}

	public boolean isUseActualState() {
		return useActualState;
	}

	public Boolean getConnectInside() {
		return connectInside;
	}

	public boolean isConnectToDefined() {
		return connectToDefined;
	}

	public BiPredicate<EnumFacing, IBlockState> getConnectToPredicate() {
		return connectToPredicate;
	}

	public int getBlocklight() {
		return blocklight;
	}

	public int getSkylight() {
		return skylight;
	}

	public boolean hasLight() {
		return hasLight;
	}

	public boolean hasEmissiveFallback() {
		return emissiveFallback;
	}

	public int getMapWidth() {
		return mapWidth;
	}

	public int getMapHeight() {
		return mapHeight;
	}

	public int getMapXOffset() {
		return mapXOffset;
	}

	public int getMapYOffset() {
		return mapYOffset;
	}

	public CtmCustomLogic getLogic() {
		return logic;
	}

	public boolean isCustomLogic() {
		return logic != null;
	}

	public JsonObject getExtraData() {
		return extraData;
	}
}
