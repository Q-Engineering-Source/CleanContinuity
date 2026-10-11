package me.pepperbell.continuity.client.model;

/** Exposes a resource-pack tint override through quad wrappers used by the renderer. */
public interface OverlayTintedQuad {
	boolean hasTintOverride();

	int getTintOverride();
}
