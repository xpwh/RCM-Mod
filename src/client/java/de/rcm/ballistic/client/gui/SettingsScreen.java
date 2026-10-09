package de.rcm.ballistic.client.gui;

import de.rcm.ballistic.client.ModConfig;
import de.rcm.ballistic.client.effect.VolumetricClouds;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The mod's settings: the volumetric clouds (on/off, cover, quality, shadows, light shafts) and the
 * effects (strength of the screen effects, amount of smoke, loudness of explosions, bullet holes).
 * Opened from Mod Menu's mod list or with {@code /bmconfig}; saved when it is closed.
 */
public class SettingsScreen extends Screen {
	private static final int W = 150;
	private static final int H = 20;
	private static final int GAP = 24;
	private final Screen parent;

	public SettingsScreen(Screen parent) {
		super(Component.translatable("options.ballisticmissiles.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int left = this.width / 2 - W - 5;
		int right = this.width / 2 + 5;
		int top = Math.max(42, this.height / 2 - 92);

		// ---- clouds
		int y = top;
		this.addRenderableWidget(CycleButton.onOffBuilder(ModConfig.clouds)
			.create(left, y, W, H, Component.translatable("options.ballisticmissiles.clouds"), (b, v) -> VolumetricClouds.setEnabled(v)));
		y += GAP;
		this.addRenderableWidget(new PercentSlider(left, y, "options.ballisticmissiles.cloud_amount", ModConfig.cloudAmount, v -> ModConfig.cloudAmount = v));
		y += GAP;
		this.addRenderableWidget(CycleButton.builder((ModConfig.Quality q) -> Component.translatable(
				"options.ballisticmissiles.quality." + q.name().toLowerCase(java.util.Locale.ROOT)), ModConfig.cloudQuality)
			.withValues(ModConfig.Quality.values())
			.withTooltip(q -> net.minecraft.client.gui.components.Tooltip.create(Component.translatable("options.ballisticmissiles.quality.tooltip")))
			.create(left, y, W, H, Component.translatable("options.ballisticmissiles.cloud_quality"), (b, v) -> ModConfig.cloudQuality = v));
		y += GAP;
		this.addRenderableWidget(CycleButton.onOffBuilder(ModConfig.cloudShadows)
			.create(left, y, W, H, Component.translatable("options.ballisticmissiles.cloud_shadows"), (b, v) -> ModConfig.cloudShadows = v));
		y += GAP;
		this.addRenderableWidget(CycleButton.onOffBuilder(ModConfig.lightShafts)
			.withTooltip(v -> net.minecraft.client.gui.components.Tooltip.create(Component.translatable("options.ballisticmissiles.light_shafts.tooltip")))
			.create(left, y, W, H, Component.translatable("options.ballisticmissiles.light_shafts"), (b, v) -> ModConfig.lightShafts = v));

		// ---- effects
		y = top;
		this.addRenderableWidget(new PercentSlider(right, y, "options.ballisticmissiles.screen_effects", ModConfig.screenEffects, v -> ModConfig.screenEffects = v));
		y += GAP;
		this.addRenderableWidget(new PercentSlider(right, y, "options.ballisticmissiles.smoke_amount", ModConfig.smokeAmount, v -> ModConfig.smokeAmount = v));
		y += GAP;
		this.addRenderableWidget(new PercentSlider(right, y, "options.ballisticmissiles.explosion_volume", ModConfig.explosionVolume,
			v -> ModConfig.explosionVolume = v));
		y += GAP;
		this.addRenderableWidget(CycleButton.onOffBuilder(ModConfig.bulletHoles)
			.create(right, y, W, H, Component.translatable("options.ballisticmissiles.bullet_holes"), (b, v) -> ModConfig.bulletHoles = v));

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
			.bounds(this.width / 2 - 100, top + GAP * 5 + 12, 200, H).build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		int top = Math.max(42, this.height / 2 - 92);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, top - 30, 0xFFFFFFFF);
		graphics.drawCenteredString(this.font, Component.translatable("options.ballisticmissiles.section.clouds"), this.width / 2 - W / 2 - 5, top - 13,
			0xFFA0D0FF);
		graphics.drawCenteredString(this.font, Component.translatable("options.ballisticmissiles.section.effects"), this.width / 2 + W / 2 + 5, top - 13,
			0xFFFFC080);
	}

	@Override
	public void onClose() {
		ModConfig.save();
		this.minecraft.setScreen(this.parent);
	}

	/** A 0-100 % slider. */
	private static final class PercentSlider extends AbstractSliderButton {
		private final String key;
		private final IntConsumer setter;

		PercentSlider(int x, int y, String key, int percent, IntConsumer setter) {
			super(x, y, W, H, Component.empty(), percent / 100.0);
			this.key = key;
			this.setter = setter;
			this.updateMessage();
		}

		private int percent() {
			return Mth.clamp((int) Math.round(this.value * 100.0), 0, 100);
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Component.translatable(this.key, this.percent()));
		}

		@Override
		protected void applyValue() {
			this.setter.accept(this.percent());
		}
	}
}
