package me.pepperbell.continuity.client.ctm;

import java.util.function.BiPredicate;

import me.pepperbell.continuity.client.config.ContinuityConfig;
import me.pepperbell.continuity.client.processor.ConnectionPredicate;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/**
 * Connection logic matching the CTM Mod format's {@code ConnectionCheck} semantics:
 * <ul>
 * <li>By default two states connect when they are the same {@link IBlockState} object.</li>
 * <li>{@code ignore_states}: connect by {@link net.minecraft.block.Block} identity instead.</li>
 * <li>{@code use_actual_state}: resolve each state through {@code getActualState} before comparing.</li>
 * <li>{@code connect_to}: when present, both states must match the CTM blockstate predicate.</li>
 * <li>The "obscured face" check (a block directly in front of the checked face also connecting)
 * can be toggled via {@code connect_inside}; the CTM default is to perform it.</li>
 * </ul>
 */
public class CtmConnectionPredicate implements ConnectionPredicate {
	protected final boolean ignoreStates;
	protected final boolean useActualState;
	protected final boolean disableObscuredFaceCheck;
	protected final BiPredicate<EnumFacing, IBlockState> connectToPredicate;

	public CtmConnectionPredicate(boolean ignoreStates, boolean useActualState, boolean disableObscuredFaceCheck) {
		this(ignoreStates, useActualState, disableObscuredFaceCheck, null);
	}

	private CtmConnectionPredicate(boolean ignoreStates, boolean useActualState, boolean disableObscuredFaceCheck,
			BiPredicate<EnumFacing, IBlockState> connectToPredicate) {
		this.ignoreStates = ignoreStates;
		this.useActualState = useActualState;
		this.disableObscuredFaceCheck = disableObscuredFaceCheck;
		this.connectToPredicate = connectToPredicate;
	}

	@Override
	public boolean shouldConnect(IBlockAccess level, BlockPos pos, IBlockState appearanceState, IBlockState state,
			BlockPos otherPos, EnumFacing face, TextureAtlasSprite quadSprite) {
		IBlockState otherState = level.getBlockState(otherPos);
		return shouldConnect(level, pos, appearanceState, state, otherPos, otherState, otherState, face, quadSprite);
	}

	@Override
	public boolean shouldConnect(IBlockAccess level, BlockPos pos, IBlockState appearanceState, IBlockState state, BlockPos otherPos, IBlockState otherAppearanceState, IBlockState otherState, EnumFacing face, TextureAtlasSprite quadSprite) {
		// CTM Vintage starts from the world blockstates and only asks for actual states when the
		// metadata explicitly enables use_actual_state.
		IBlockState from = resolveConnectionState(state, level, pos);
		IBlockState to = resolveConnectionState(otherState, level, otherPos);

		boolean connects = statesConnect(from, to, face);

		if (!disableObscuredFaceCheck) {
			// The "obscured face" test: if the block beyond the neighbor (in the face direction)
			// also matches, the face is treated as not connected (avoids showing seams at inner
			// corners of thick connected regions).
			BlockPos obscuringPos = otherPos.offset(face);
			IBlockState obscuringRaw = level.getBlockState(obscuringPos);
			IBlockState obscuring = resolveConnectionState(obscuringRaw, level, obscuringPos);
			if (statesConnect(from, obscuring, face)) {
				connects = false;
			}
		}

		return connects;
	}

	public IBlockState resolveConnectionState(IBlockState state, IBlockAccess level, BlockPos pos) {
		if (useActualState) {
			return state.getActualState(level, pos);
		}
		return state;
	}

	public boolean statesConnect(IBlockState from, IBlockState to, EnumFacing face) {
		if (connectToPredicate != null) {
			return connectToPredicate.test(face, from) && connectToPredicate.test(face, to);
		}
		if (ignoreStates) {
			return from.getBlock() == to.getBlock();
		}
		return from == to;
	}

	public static CtmConnectionPredicate fromProperties(CtmDefinition properties, boolean forceDisableObscured) {
		Boolean connectInside = properties.getConnectInside();
		boolean disableObscured;
		if (properties.getType() == CtmType.SCTM) {
			// CTM Vintage's SCTM type overrides the metadata option and always connects inside.
			disableObscured = true;
		} else if (connectInside != null) {
			disableObscured = connectInside;
		} else {
			disableObscured = forceDisableObscured || ContinuityConfig.INSTANCE.ctmConnectInside.get();
		}
		return new CtmConnectionPredicate(properties.isIgnoreStates(), properties.isUseActualState(), disableObscured,
				properties.getConnectToPredicate());
	}
}
