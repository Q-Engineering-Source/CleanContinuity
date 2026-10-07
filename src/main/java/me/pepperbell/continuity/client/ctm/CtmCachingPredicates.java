package me.pepperbell.continuity.client.ctm;

import java.util.Set;
import java.util.function.Function;

import me.pepperbell.continuity.api.client.CachingPredicates;
import me.pepperbell.continuity.api.client.CtmProperties;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ResourceLocation;

/**
 * Texture-driven predicates for the CTM Mod format: a definition applies to a blockstate if that
 * state's sprite (or one of the state's face sprites) is the base texture of the definition. This
 * mirrors how CTM picks textures by sprite identity rather than by a properties {@code matchBlocks}
 * predicate.
 */
public class CtmCachingPredicates implements CachingPredicates {
	protected final Set<ResourceLocation> spriteIdSet;

	public CtmCachingPredicates(Set<ResourceLocation> spriteIdSet) {
		this.spriteIdSet = spriteIdSet;
	}

	@Override
	public boolean affectsSprites() {
		return true;
	}

	@Override
	public boolean affectsSprite(TextureAtlasSprite sprite) {
		ResourceLocation spriteId = new ResourceLocation(sprite.getIconName());
		return spriteIdSet.contains(spriteId);
	}

	@Override
	public boolean affectsBlockStates() {
		return false;
	}

	@Override
	public boolean affectsBlockState(IBlockState state) {
		return true;
	}

	@Override
	public boolean isValidForMultipass() {
		// CTM Vintage transforms the texture's source quad once; generated texture sprites are not
		// recursively interpreted as another CTM definition.
		return false;
	}

	public static class Factory<T extends CtmDefinition> implements CachingPredicates.Factory<T> {
		@Override
		public CachingPredicates createPredicates(T properties, Function<ResourceLocation, TextureAtlasSprite> spriteGetter) {
			return new CtmCachingPredicates(Set.of(properties.getResourceId()));
		}
	}
}
