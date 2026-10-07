package me.pepperbell.continuity.client.ctm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;

import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.JsonUtils;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/**
 * Parser for CTM Vintage's {@code extra.connect_to} blockstate predicates.
 * Supports block lists, per-face defaults, blockstate properties, comparisons, and defer
 * compositions, matching CTM Vintage's {@code BlockstatePredicateParser}.
 */
public final class CtmBlockstatePredicateParser {
	private static final Predicate<IBlockState> EMPTY = state -> false;

	private CtmBlockstatePredicateParser() {
	}

	@Nullable
	public static BiPredicate<EnumFacing, IBlockState> parse(@Nullable JsonElement json) {
		if (json == null) {
			return null;
		}
		if (json.isJsonArray()) {
			Predicate<IBlockState> predicate = parseStatePredicate(json, null);
			return (face, state) -> predicate.test(state);
		}
		if (!json.isJsonObject()) {
			throw new JsonSyntaxException("connect_to must be an object or an array. Found: " + json);
		}

		JsonObject object = json.getAsJsonObject();
		Predicate<IBlockState> defaultPredicate = null;
		if (object.has("default")) {
			defaultPredicate = parseStatePredicate(object.get("default"), null);
		}

		EnumMap<EnumFacing, Predicate<IBlockState>> predicates = new EnumMap<>(EnumFacing.class);
		for (var entry : object.entrySet()) {
			if (entry.getKey().equals("default")) {
				continue;
			}
			EnumFacing facing;
			try {
				facing = EnumFacing.valueOf(entry.getKey().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				throw new JsonSyntaxException("Invalid connect_to face '" + entry.getKey() + "'", e);
			}
			predicates.put(facing, parseStatePredicate(entry.getValue(), defaultPredicate));
		}

		Predicate<IBlockState> fallback = defaultPredicate == null ? EMPTY : defaultPredicate;
		for (EnumFacing facing : EnumFacing.VALUES) {
			predicates.putIfAbsent(facing, fallback);
		}
		return (face, state) -> predicates.get(face).test(state);
	}

	private static Predicate<IBlockState> parseStatePredicate(JsonElement json,
			@Nullable Predicate<IBlockState> defaultPredicate) {
		if (json.isJsonObject()) {
			JsonObject object = json.getAsJsonObject();
			Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(JsonUtils.getString(object, "block")));
			if (block == null || block == Blocks.AIR) {
				return EMPTY;
			}

			Composition defer = null;
			if (object.has("defer")) {
				if (defaultPredicate == null) {
					throw new JsonParseException("Cannot defer when no default is set!");
				}
				String name = JsonUtils.getString(object, "defer").toUpperCase(Locale.ROOT);
				try {
					defer = Composition.valueOf(name);
				} catch (IllegalArgumentException e) {
					throw new JsonSyntaxException(name + " is not a valid defer type.", e);
				}
			}

			Predicate<IBlockState> child;
			if (!object.has("predicate")) {
				child = state -> state.getBlock() == block;
			} else {
				JsonElement propertyElement = object.get("predicate");
				if (propertyElement.isJsonObject()) {
					child = parsePropertyPredicate(block, propertyElement.getAsJsonObject());
				} else if (propertyElement.isJsonArray()) {
					List<Predicate<IBlockState>> children = new ArrayList<>();
					for (JsonElement element : propertyElement.getAsJsonArray()) {
						if (!element.isJsonObject()) {
							throw new JsonSyntaxException("Predicate entry must be a JSON Object. Found: " + element);
						}
						children.add(parsePropertyPredicate(block, element.getAsJsonObject()));
					}
					child = composeAll(Composition.AND, children);
				} else {
					throw new JsonSyntaxException("Predicate must be an object or array. Found: " + propertyElement);
				}
			}
			return defer == null ? child : defer.compose(defaultPredicate, child);
		}

		if (json.isJsonArray()) {
			List<Predicate<IBlockState>> predicates = new ArrayList<>();
			for (JsonElement element : json.getAsJsonArray()) {
				Predicate<IBlockState> predicate = parseStatePredicate(element, defaultPredicate);
				if (predicate != EMPTY) {
					predicates.add(predicate);
				}
			}
			if (predicates.isEmpty()) {
				return EMPTY;
			}
			return predicates.size() == 1 ? predicates.get(0) : composeAll(Composition.OR, predicates);
		}

		throw new JsonSyntaxException("Predicate deserialization expects an object or an array. Found: " + json);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Predicate<IBlockState> parsePropertyPredicate(Block block, JsonObject object) {
		ComparisonType comparison = ComparisonType.EQUAL;
		if (object.has("compare_func")) {
			String name = JsonUtils.getString(object, "compare_func");
			comparison = Arrays.stream(ComparisonType.values())
					.filter(type -> type.key.equals(name))
					.findFirst()
					.orElseThrow(() -> new JsonParseException(name + " is not a valid comparison type!"));
		}
		String propertyName = null;
		JsonElement valueElement = null;
		for (var entry : object.entrySet()) {
			if (!entry.getKey().equals("compare_func")) {
				if (propertyName != null) {
					throw new JsonSyntaxException("Predicate entry must define exactly one property->value pair. Found more than one.");
				}
				propertyName = entry.getKey();
				valueElement = entry.getValue();
			}
		}
		if (propertyName == null) {
			throw new JsonSyntaxException("Predicate entry must define exactly one property->value pair. Found 0.");
		}
		final String finalPropertyName = propertyName;
		final JsonElement finalValueElement = valueElement;
		IProperty property = (IProperty) block.getBlockState().getProperties().stream()
				.filter(candidate -> candidate.getName().equals(finalPropertyName))
				.findFirst()
				.orElseThrow(() -> new JsonParseException(finalPropertyName + " is not a valid property for blockstate " + block.getDefaultState()));
		if (finalValueElement.isJsonArray()) {
			Set<Comparable> values = new HashSet<>();
			for (JsonElement element : finalValueElement.getAsJsonArray()) {
				values.add(parsePropertyValue(property, element));
			}
			return state -> state.getBlock() == block && values.contains(state.getValue(property));
		}

		Comparable value = parsePropertyValue(property, finalValueElement);
		ComparisonType finalComparison = comparison;
		return state -> state.getBlock() == block
				&& finalComparison.compareFunc.test(((Comparable) state.getValue(property)).compareTo(value));
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Comparable parsePropertyValue(IProperty property, JsonElement element) {
		String valueString = JsonUtils.getString(element, property.getName());
		Optional<Comparable> value = (Optional<Comparable>) property.getAllowedValues().stream()
				.filter(candidate -> property.getName((Comparable) candidate).equalsIgnoreCase(valueString))
				.findFirst();
		return value.orElseThrow(() -> new JsonParseException(valueString + " is not a valid value for property " + property));
	}

	private static Predicate<IBlockState> composeAll(Composition composition, List<Predicate<IBlockState>> predicates) {
		return state -> {
			if (composition == Composition.AND) {
				for (Predicate<IBlockState> predicate : predicates) {
					if (!predicate.test(state)) {
						return false;
					}
				}
				return true;
			}
			for (Predicate<IBlockState> predicate : predicates) {
				if (predicate.test(state)) {
					return true;
				}
			}
			return false;
		};
	}

	private enum ComparisonType {
		EQUAL("=", i -> i == 0),
		NOT_EQUAL("!=", i -> i != 0),
		GREATER_THAN(">", i -> i > 0),
		LESS_THAN("<", i -> i < 0),
		GREATER_THAN_EQ(">=", i -> i >= 0),
		LESS_THAN_EQ("<=", i -> i <= 0);

		private final String key;
		private final IntPredicate compareFunc;

		ComparisonType(String key, IntPredicate compareFunc) {
			this.key = key;
			this.compareFunc = compareFunc;
		}
	}

	private enum Composition {
		AND,
		OR;

		Predicate<IBlockState> compose(Predicate<IBlockState> first, Predicate<IBlockState> second) {
			return this == AND ? first.and(second) : first.or(second);
		}
	}
}
