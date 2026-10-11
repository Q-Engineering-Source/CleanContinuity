package me.pepperbell.continuity.client.mixin;

import java.util.Arrays;

import com.dhj.actinium.render.terrain.compile.pipeline.VintageBlockRenderer;
import dhj.embeddedt.embeddium.impl.model.quad.BakedQuadView;
import me.pepperbell.continuity.client.model.OverlayTintedQuad;
import net.minecraft.client.renderer.color.IBlockColor;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VintageBlockRenderer.class, remap = false)
public abstract class VintageBlockRendererOverlayTintMixin {
	@Shadow
	@Final
	private int[] quadColors;

	@Inject(method = "getVertexColors", at = @At("HEAD"), cancellable = true, remap = false)
	private void continuity$useOverlayTint(BlockPos pos, IBlockColor colorProvider, BakedQuadView quad,
			CallbackInfoReturnable<int[]> cir) {
		if (!((Object) quad instanceof OverlayTintedQuad overlay) || !overlay.hasTintOverride()) {
			return;
		}
		int color = overlay.getTintOverride();
		if (color == -1) {
			color = 0xFFFFFF;
		}
		int abgr = ((color & 0xFF) << 16) | (color & 0x00FF00) | ((color >>> 16) & 0xFF);
		Arrays.fill(quadColors, 0xFF000000 | abgr);
		cir.setReturnValue(quadColors);
	}
}
