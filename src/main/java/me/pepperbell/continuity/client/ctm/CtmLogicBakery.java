package me.pepperbell.continuity.client.ctm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.pepperbell.continuity.client.ctm.CtmCustomLogic.LocalDirection;
import me.pepperbell.continuity.client.ctm.CtmCustomLogic.OutputFace;
import me.pepperbell.continuity.client.ctm.CtmLogicDefinition.MultiSubmap;
import me.pepperbell.continuity.client.ctm.CtmLogicDefinition.NamedSubmap;
import me.pepperbell.continuity.client.ctm.CtmLogicDefinition.Position;
import me.pepperbell.continuity.client.ctm.CtmLogicDefinition.Rule;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

/**
 * Bakes a {@link CtmLogicDefinition} into a complete truth-table {@link CtmCustomLogic}: every
 * possible combination of the input connection bits maps to an ordered list of outputs.
 * <p>
 * Positions define the input bits (bit index assigned from the last position down). Outputs are
 * indexed in submap enumeration order. Each rule contributes its {@code connected}/{@code
 * unconnected} requirements to the desired state of its output; because multiple rules may target
 * the same output, those requirements are merged (AND). Rules that match a state all contribute
 * their output, so a state may map to several sub-quads.
 */
public final class CtmLogicBakery {
	private CtmLogicBakery() {
	}

	private enum Trinary {
		FALSE, TRUE, DONT_CARE
	}

	public static CtmCustomLogic bake(CtmLogicDefinition def) {
		int size = def.positions.size();

		// Bit index per position id, assigned last-to-first (matches the reference bakery)
		Map<String, Integer> bitNames = new HashMap<>();
		List<LocalDirection> directions = new ArrayList<>(Collections.nCopies(size, (LocalDirection) null));
		int bit = size - 1;
		for (Position position : def.positions) {
			bitNames.put(position.id(), bit);
			directions.set(bit, new OffsetDirection(CtmDir.fromDirections(position.directions().toArray(new EnumFacing[0]))));
			bit--;
		}

		// Output id per named submap, in submap enumeration order
		Map<String, Integer> outputIds = new HashMap<>();
		List<String> outputOrder = new ArrayList<>();
		int outputId = 0;
		for (Map.Entry<String, MultiSubmap> e : def.submaps.entrySet()) {
			for (NamedSubmap submap : e.getValue().forName(e.getKey())) {
				outputIds.put(submap.name(), outputId++);
				outputOrder.add(submap.name());
			}
		}
		int outputCount = outputOrder.size();

		// Face (geometry clipping) submap per name
		Map<String, CtmSubmap> faces = new HashMap<>();
		for (Map.Entry<String, MultiSubmap> e : def.faces.entrySet()) {
			for (NamedSubmap submap : e.getValue().forName(e.getKey())) {
				faces.put(submap.name(), submap.submap());
			}
		}

		Map<String, CtmSubmap> submapByOutput = new HashMap<>();
		for (Map.Entry<String, MultiSubmap> e : def.submaps.entrySet()) {
			for (NamedSubmap submap : e.getValue().forName(e.getKey())) {
				submapByOutput.put(submap.name(), submap.submap());
			}
		}

		// CTM Vintage calls output() for every rule. Repeated rules for one output therefore
		// replace that output's texture index and face with the values from the last rule.
		OutputFace[] outputFaces = new OutputFace[outputCount];
		for (int i = 0; i < outputCount; i++) {
			String name = outputOrder.get(i);
			outputFaces[i] = new OutputFace(0, submapByOutput.get(name), CtmSubmap.X1);
		}
		for (Rule rule : def.rules) {
			Integer id = outputIds.get(rule.output());
			if (id == null) {
				throw new IllegalArgumentException("Unknown output '" + rule.output() + "'");
			}
			CtmSubmap face = rule.at().map(faces::get).orElse(CtmSubmap.X1);
			if (face == null) {
				throw new IllegalArgumentException("Unknown face '" + rule.at().orElse("") + "'");
			}
			outputFaces[id] = new OutputFace(rule.from(), submapByOutput.get(rule.output()), face);
		}

		// Desired state per output id (conditions merged across rules targeting the same output)
		Int2ObjectMap<DesiredState> desired = new Int2ObjectOpenHashMap<>();
		for (Rule rule : def.rules) {
			Integer id = outputIds.get(rule.output());
			if (id == null) {
				throw new IllegalArgumentException("Unknown output '" + rule.output() + "'");
			}
			DesiredState state = desired.get(id);
			if (state == null) {
				state = new DesiredState(size, id);
				desired.put(id, state);
			}
			for (String connected : rule.connected()) {
				Integer b = bitNames.get(connected);
				if (b != null) {
					state.with(b, Trinary.TRUE);
				}
			}
			for (String unconnected : rule.unconnected()) {
				Integer b = bitNames.get(unconnected);
				if (b != null) {
					state.with(b, Trinary.FALSE);
				}
			}
		}

		// Build the full lookup table: every state -> ordered list of matching output ids
		int max = 1 << size;
		int[][] lookupArray = new int[max][];
		for (int s = 0; s < max; s++) {
			List<Integer> matches = new ArrayList<>();
			for (Int2ObjectMap.Entry<DesiredState> e : desired.int2ObjectEntrySet()) {
				if (e.getValue().test(s)) {
					matches.add(e.getIntKey());
				}
			}
			if (matches.isEmpty()) {
				throw new IllegalStateException("Input state found that is not in lookup table: " + Integer.toBinaryString(s));
			}
			lookupArray[s] = matches.stream().mapToInt(Integer::intValue).toArray();
		}

		return new CtmCustomLogic(directions, lookupArray, outputFaces);
	}

	private static class DesiredState {
		final Trinary[] input;

		DesiredState(int size, int output) {
			this.input = new Trinary[size];
			Arrays.fill(this.input, Trinary.DONT_CARE);
		}

		DesiredState with(int bit, Trinary in) {
			if (bit >= 0 && bit < input.length) {
				this.input[bit] = in;
			}
			return this;
		}

		boolean test(int state) {
			for (int i = 0; i < input.length; i++) {
				Trinary req = input[i];
				boolean bit = ((state >> i) & 1) == 1;
				if (req != Trinary.DONT_CARE && bit != (req == Trinary.TRUE)) {
					return false;
				}
			}
			return true;
		}
	}

	/** A connection direction: a standard {@link CtmDir} (matched from the logic's facings). */
	private record OffsetDirection(CtmDir dir) implements LocalDirection {
		@Override
		public BlockPos getOffset(EnumFacing normal) {
			return dir.getOffset(normal);
		}
	}
}
