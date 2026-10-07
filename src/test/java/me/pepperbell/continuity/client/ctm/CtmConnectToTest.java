package me.pepperbell.continuity.client.ctm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.init.Bootstrap;
import org.junit.jupiter.api.Test;

class CtmConnectToTest {
	@Test
	void explicitBlockListConnectsAcrossAllowedBlocksOnly() {
		Bootstrap.register();
		CtmDefinition definition = CtmMcmetaParser.parse(new ResourceLocation("test:tile"),
				JsonParser.parseString("{\"ctm_version\":1,\"type\":\"ctm\",\"extra\":{\"connect_to\":[{\"block\":\"minecraft:stone\"},{\"block\":\"minecraft:glass\"}]}}").getAsJsonObject(),
				"test", 0);
		CtmConnectionPredicate predicate = CtmConnectionPredicate.fromProperties(definition, true);
		BlockPos from = BlockPos.ORIGIN;
		BlockPos to = from.east();
		assertTrue(predicate.shouldConnect(null, from, Blocks.STONE.getDefaultState(), Blocks.STONE.getDefaultState(),
				to, Blocks.GLASS.getDefaultState(), Blocks.GLASS.getDefaultState(), EnumFacing.NORTH, null));
		assertFalse(predicate.shouldConnect(null, from, Blocks.STONE.getDefaultState(), Blocks.STONE.getDefaultState(),
				to, Blocks.DIRT.getDefaultState(), Blocks.DIRT.getDefaultState(), EnumFacing.NORTH, null));
	}
}
