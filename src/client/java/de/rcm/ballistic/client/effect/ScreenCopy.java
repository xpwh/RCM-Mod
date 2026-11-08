package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;

/** A copy of the world's depth or picture, taken mid-frame for the screen-space passes to read. */
final class ScreenCopy extends net.minecraft.client.renderer.texture.AbstractTexture {
	private final String name;

	ScreenCopy(String name) {
		this.name = name;
	}

	void copy(GpuTexture source) {
		int w = source.getWidth(0);
		int h = source.getHeight(0);
		if (this.texture == null || this.texture.getWidth(0) != w || this.texture.getHeight(0) != h || this.texture.getFormat() != source.getFormat()) {
			if (this.textureView != null) {
				this.textureView.close();
			}
			if (this.texture != null) {
				this.texture.close();
			}
			var device = RenderSystem.getDevice();
			this.texture = device.createTexture(() -> "Ballistic Missiles " + this.name, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
				source.getFormat(), w, h, 1, 1);
			this.textureView = device.createTextureView(this.texture);
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		}
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(source, this.texture, 0, 0, 0, 0, 0, w, h);
	}
}
