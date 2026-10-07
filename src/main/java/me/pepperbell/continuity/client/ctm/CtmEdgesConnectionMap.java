package me.pepperbell.continuity.client.ctm;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;

/** The edge-aware connection check used by CTM Vintage's {@code edges} and {@code edges_full}. */
public final class CtmEdgesConnectionMap {
	private CtmEdgesConnectionMap() {
	}

	public static Result compute(IBlockAccess world, BlockPos current, IBlockState currentState, EnumFacing face,
			CtmConnectionPredicate predicate) {
		int connections = 0;
		boolean obscured = false;
		// CTM Vintage's edges type rebuilds its ConnectionCheck but does not copy actualStates,
		// so this algorithm intentionally compares the raw world states.
		IBlockState from = currentState;
		for (CtmDir dir : CtmDir.VALUES) {
			if (obscured) {
				break;
			}
			DirectionResult result = isConnected(world, current, from, dir.apply(current, face), face, predicate);
			if (result.obscured()) {
				obscured = true;
				break;
			}
			if (result.connected()) {
				connections |= 1 << dir.ordinal();
			}
		}
		return new Result(connections, obscured);
	}

	private static DirectionResult isConnected(IBlockAccess world, BlockPos current, IBlockState from,
			BlockPos connection, EnumFacing face, CtmConnectionPredicate predicate) {
		BlockPos outwardFromCurrent = current.offset(face);
		IBlockState obscuring = world.getBlockState(outwardFromCurrent);
		if (predicate.statesConnect(from, obscuring, face)) {
			return new DirectionResult(false, true);
		}

		IBlockState connected = world.getBlockState(connection);
		BlockPos connectionOutward = connection.offset(face);
		IBlockState obscuringConnection = world.getBlockState(connectionOutward);
		if (!predicate.statesConnect(from, connected, face)
				&& !predicate.statesConnect(from, obscuringConnection, face)) {
			return new DirectionResult(false, false);
		}

		BlockPos difference = connection.subtract(current);
		if (new Vec3d(difference).lengthSquared() <= 1.0D) {
			return new DirectionResult(true, false);
		}

		Vec3d direction = new Vec3d(difference).normalize();
		if (face.getAxis() == EnumFacing.Axis.Z) {
			direction = direction.rotateYaw((float) (-Math.PI / 2));
		}
		float angle = (float) Math.PI / 4;
		Vec3d a = face.getAxis().isVertical() ? direction.rotateYaw(angle) : direction.rotatePitch(angle);
		Vec3d b = face.getAxis().isVertical() ? direction.rotateYaw(-angle) : direction.rotatePitch(-angle);
		BlockPos posA = new BlockPos(a).add(current);
		BlockPos posB = new BlockPos(b).add(current);
		return new DirectionResult(isUnobscuredMatchingState(world, posA, face, from, predicate)
				|| isUnobscuredMatchingState(world, posB, face, from, predicate), false);
	}

	private static boolean isUnobscuredMatchingState(IBlockAccess world, BlockPos pos, EnumFacing face,
			IBlockState state, CtmConnectionPredicate predicate) {
		IBlockState candidate = world.getBlockState(pos);
		if (candidate != state) {
			return false;
		}
		BlockPos outward = pos.offset(face);
		IBlockState obscuring = world.getBlockState(outward);
		return !predicate.statesConnect(state, obscuring, face);
	}

	public record Result(int connections, boolean obscured) {
	}

	private record DirectionResult(boolean connected, boolean obscured) {
	}
}
