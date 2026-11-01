package de.rcm.ballistic.client.gui;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ai.SoldierDebug;
import de.rcm.ballistic.ai.SoldierEntity;
import de.rcm.ballistic.client.ModConfig;
import de.rcm.ballistic.client.effect.VolumetricClouds;
import de.rcm.ballistic.config.ServerConfig;
import de.rcm.ballistic.network.ModNetworking;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The mod's control panel ({@code /bmconfig}, {@code /bm}, Mod Menu): everything in one place, in tabs.
 * <ul>
 *   <li>Grafik, Effekte: the client settings (saved when the panel closes)</li>
 *   <li>Gore: blood and gore display, and every {@code /bmgore} test as a button, on yourself</li>
 *   <li>KI: {@code /bmai} - spawning soldiers, the debug view, the AI tool, removing them</li>
 *   <li>Spawnen: soldiers, and every item of the mod (read from the registry, so new ones appear by
 *       themselves), searchable - click for one, shift-click for a stack</li>
 *   <li>Server: the world settings ({@code /bmserver})</li>
 * </ul>
 * The actions run the mod's own commands, so the server decides what is allowed: without operator rights
 * (or cheats) they are greyed out. What the integrated server knows (the debug view, the creative setting) is
 * shown as it is now.
 */
public class SettingsScreen extends Screen {
	private static final int FOOTER = 46;
	private static final int SEARCH = 32;
	private static final int ITEM = 22;
	/** The sidebar with the pages, and the header over the content. */
	private static final int SIDE = 150;
	private static final int HEAD = 60;
	// a calm near-black palette: panel, sidebar, cards a step lighter, hairlines; text in three weights
	private static final int BACKDROP = 0x06080B;
	private static final int PANEL = 0x0F1216;
	private static final int SIDEBAR = 0x0B0D11;
	private static final int CARD = 0x161A20;
	private static final int CARD_HOVER = 0x1C2128;
	private static final int LINE = 0x22272F;
	private static final int CONTROL = 0x20252D;
	private static final int CONTROL_EDGE = 0x2F353F;
	private static final int TEXT = 0xF2F3F5;
	private static final int BODY = 0xB9BEC6;
	private static final int MUTED = 0x858C96;
	private static final int FAINT = 0x585E68;
	/** How wide a row of controls is (the content column), set by the layout. */
	private int rowW = 310;
	private long opened = -1L;
	private long lastFrame;
	/** Each page's highlight in the sidebar, easing in and out. */
	private final float[] sideHover = new float[7];
	private float pageSwitch = 1.0F;
	/** The toggles' knobs, sliding over. */
	private final java.util.Map<AbstractWidget, Float> knobs = new java.util.WeakHashMap<>();
	private static Page lastPage = Page.GRAPHICS;
	/** The soldier spawner's choices, kept while the game runs. */
	private static int soldierCount = 3;
	private static boolean soldierFriendly;
	private static String lastSearch = "";
	private static int craterRadius = 64;
	/** Who the gore tests are done on: "@s" (yourself), "@a" (everyone) or a player's name. */
	private static String goreTarget = "@s";

	private final Screen parent;
	private Rows list;
	private EditBox search;
	private Button done;
	private Page page = lastPage;
	private Component status;
	private long statusUntil;
	/** Ticks until the page is built again (after a command, to show what it changed). */
	private int refresh;

	public SettingsScreen(Screen parent) {
		super(Component.translatable("options.ballisticmissiles.title"));
		this.parent = parent;
	}

	private enum Page {
		GRAPHICS,
		EFFECTS,
		NATURE,
		GORE,
		AI,
		SPAWN,
		SERVER;

		Component title() {
			return Component.translatable("options.ballisticmissiles.tab." + this.name().toLowerCase(Locale.ROOT));
		}

		Component description() {
			return Component.translatable("options.ballisticmissiles.tab." + this.name().toLowerCase(Locale.ROOT) + ".desc");
		}

		/** The page's colour: its highlight in the sidebar, its headings, its switches. */
		int accent() {
			return switch (this) {
				case GRAPHICS -> 0x4C9AFF;
				case EFFECTS -> 0xF5A524;
				case NATURE -> 0x3FCF8E;
				case GORE -> 0xE5484D;
				case AI -> 0x9B7BFF;
				case SPAWN -> 0xF0C04A;
				case SERVER -> 0x5FC9D8;
			};
		}

		ItemStack icon() {
			return new ItemStack(switch (this) {
				case GRAPHICS -> Items.SPYGLASS;
				case EFFECTS -> Items.FIREWORK_ROCKET;
				case NATURE -> Items.OAK_SAPLING;
				case GORE -> Items.REDSTONE;
				case AI -> de.rcm.ballistic.ModRegistry.AK47;
				case SPAWN -> Items.CHEST;
				case SERVER -> Items.COMMAND_BLOCK;
			});
		}
	}

	@Override
	protected void init() {
		UiKit.init();
		if (this.opened < 0L) {
			this.opened = net.minecraft.util.Util.getMillis();
			this.lastFrame = this.opened;
		}
		this.rowW = Math.max(200, this.contentRight() - this.contentX() - 10);
		this.list = new Rows(this.minecraft, this.width, this.height, 0);
		this.addRenderableWidget(this.list);
		this.search = new EditBox(this.font, 0, 0, 200, 18, Component.translatable("options.ballisticmissiles.search"));
		this.search.setHint(Component.translatable("options.ballisticmissiles.search").withStyle(st -> st.withColor(FAINT)));
		this.search.setBordered(false);
		this.search.setValue(lastSearch);
		this.search.setResponder(text -> {
			lastSearch = text;
			if (this.page == Page.SPAWN) {
				this.build();
			}
		});
		this.addRenderableWidget(this.search);
		this.done = this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).width(92).build());
		this.build();
		this.repositionElements();
	}

	// ---------------------------------------------------------------- layout

	private int panelW() {
		return Math.min(this.width - 16, 680);
	}

	private int panelH() {
		return Math.min(this.height - 16, 620);
	}

	private int panelX() {
		return (this.width - this.panelW()) / 2;
	}

	private int panelY() {
		return (this.height - this.panelH()) / 2;
	}

	private int contentX() {
		return this.panelX() + SIDE + 24;
	}

	private int contentRight() {
		return this.panelX() + this.panelW() - 24;
	}

	/** The sidebar's page buttons: where the i-th one is (the world pages under a heading of their own). */
	private int sideItemY(int i) {
		return this.panelY() + 74 + i * 24 + (i >= 4 ? 26 : 0);
	}

	private void select(Page p) {
		if (p == this.page) {
			return;
		}
		this.page = p;
		lastPage = p;
		this.pageSwitch = 0.0F;
		this.list.setScrollAmount(0.0);
		this.build();
		this.repositionElements();
		this.minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
			net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.3F, 0.25F));
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			int x = this.panelX() + 10;
			for (Page p : Page.values()) {
				int y = this.sideItemY(p.ordinal());
				if (event.x() >= x && event.x() < x + SIDE - 20 && event.y() >= y && event.y() < y + 22) {
					this.select(p);
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void repositionElements() {
		if (this.list == null) {
			return;
		}
		int cx = this.contentX();
		int top = this.panelY() + HEAD + 1;
		boolean spawn = this.page == Page.SPAWN;
		this.search.visible = spawn;
		this.search.setWidth(Math.min(240, this.rowW) - 16);
		this.search.setHeight(10);
		this.search.setPosition(cx + 8, top + 14);
		int listTop = top + (spawn ? SEARCH : 0);
		int bottom = this.panelY() + this.panelH() - FOOTER;
		this.list.updateSizeAndPosition(this.rowW + 14, bottom - listTop, cx - 7, listTop);
		this.done.setWidth(92);
		this.done.setPosition(this.contentRight() - 92, bottom + 13);
	}

	@Override
	public void tick() {
		if (this.refresh > 0 && --this.refresh == 0) {
			double scroll = this.list.scrollAmount();
			this.build();
			this.list.setScrollAmount(scroll);
		}
	}

	// ---------------------------------------------------------------- pages

	private void build() {
		this.list.clear();
		switch (this.page) {
			case GRAPHICS -> this.graphics();
			case EFFECTS -> this.effects();
			case NATURE -> this.nature();
			case GORE -> this.gore();
			case AI -> this.ai();
			case SPAWN -> this.spawn();
			case SERVER -> this.server();
		}
		this.list.finish();
	}

	private void graphics() {
		this.list.header("options.ballisticmissiles.section.clouds", 0xFFA0D0FF);
		this.list.row(toggle("clouds", ModConfig.clouds, null, VolumetricClouds::setEnabled),
			new IntSlider(0, 100, "options.ballisticmissiles.cloud_amount", ModConfig.cloudAmount, v -> ModConfig.cloudAmount = v));
		this.list.row(CycleButton.builder((ModConfig.Quality q) -> Component.translatable("options.ballisticmissiles.quality." + q.name().toLowerCase(Locale.ROOT)),
					ModConfig.cloudQuality)
				.withValues(ModConfig.Quality.values())
				.withTooltip(q -> Tooltip.create(Component.translatable("options.ballisticmissiles.quality.tooltip")))
				.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.cloud_quality"), (b, v) -> ModConfig.cloudQuality = v),
			toggle("cloud_shadows", ModConfig.cloudShadows, null, v -> ModConfig.cloudShadows = v));
		this.list.row(toggle("light_shafts", ModConfig.lightShafts, "light_shafts", v -> ModConfig.lightShafts = v),
			CycleButton.booleanBuilder(Component.translatable("options.ballisticmissiles.cloud_style.cumulus"),
					Component.translatable("options.ballisticmissiles.cloud_style.layer"), ModConfig.cumulusClouds)
				.withTooltip(v -> Tooltip.create(Component.translatable("options.ballisticmissiles.cloud_style.tooltip")))
				.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.cloud_style"), (b, v) -> ModConfig.cumulusClouds = v));
		this.list.header("options.ballisticmissiles.section.light", 0xFFFFE080);
		this.list.row(toggle("real_sky", ModConfig.realSky, "real_sky", v -> ModConfig.realSky = v),
			CycleButton.builder((Integer q) -> q == 0 ? CommonComponents.OPTION_OFF
						: Component.translatable("options.ballisticmissiles.quality." + (q == 1 ? "low" : q == 2 ? "high" : "ultra")), ModConfig.lightQuality)
				.withValues(0, 1, 2, 3)
				.withTooltip(q -> Tooltip.create(Component.translatable("options.ballisticmissiles.dynamic_lights.tooltip")))
				.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.dynamic_lights"), (b, v) -> {
					ModConfig.lightQuality = v;
					ModConfig.dynamicLights = v > 0;
				}));
		this.list.row(toggle("eye_adaptation", ModConfig.eyeAdaptation, "eye_adaptation", v -> ModConfig.eyeAdaptation = v),
			toggle("auto_exposure", ModConfig.autoExposure, "auto_exposure", v -> ModConfig.autoExposure = v));
		this.list.header("options.ballisticmissiles.section.shaders", 0xFF90D0FF);
		this.list.row(toggle("real_water", ModConfig.realWater, "real_water", v -> ModConfig.realWater = v),
			CycleButton.booleanBuilder(Component.translatable("options.ballisticmissiles.sun_shadows.new").withStyle(ChatFormatting.AQUA),
					Component.translatable("options.ballisticmissiles.sun_shadows.classic"), ModConfig.sunShadows)
				.withTooltip(v -> Tooltip.create(Component.translatable("options.ballisticmissiles.sun_shadows.tooltip")))
				.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.sun_shadows"), (b, v) -> ModConfig.sunShadows = v));
		this.list.row(toggle("ambient_occlusion", ModConfig.ambientOcclusion, "ambient_occlusion", v -> ModConfig.ambientOcclusion = v),
			toggle("lens_flare", ModConfig.lensFlare, "lens_flare", v -> ModConfig.lensFlare = v));
		IntSlider bloom = new IntSlider(0, 100, "options.ballisticmissiles.bloom", ModConfig.bloom, v -> ModConfig.bloom = v);
		bloom.setTooltip(Tooltip.create(Component.translatable("options.ballisticmissiles.bloom.tooltip")));
		this.list.row(bloom, toggle("storms", ModConfig.storms, "storms", v -> ModConfig.storms = v));
	}

	private void nature() {
		this.list.header("options.ballisticmissiles.section.nature_look", 0xFF90E090);
		this.list.row(new IntSlider(0, 200, "options.ballisticmissiles.wind_amount", ModConfig.windAmount, v -> ModConfig.windAmount = v),
			CycleButton.builder((ModConfig.Quality q) -> Component.translatable("options.ballisticmissiles.quality." + q.name().toLowerCase(Locale.ROOT)),
					ModConfig.natureQuality)
				.withValues(ModConfig.Quality.LOW, ModConfig.Quality.MEDIUM, ModConfig.Quality.HIGH)
				.withTooltip(q -> Tooltip.create(Component.translatable("options.ballisticmissiles.nature_quality.tooltip")))
				.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.nature_quality"), (b, v) -> ModConfig.natureQuality = v));
		this.list.row(toggle("wildlife", ModConfig.wildlife, "wildlife", v -> ModConfig.wildlife = v),
			toggle("night_sounds", ModConfig.nightSounds, "night_sounds", v -> ModConfig.nightSounds = v));
		this.list.row(toggle("real_rain", ModConfig.realRain, "real_rain", v -> ModConfig.realRain = v));
		this.list.row(toggle("storm_front", ModConfig.stormFront, "storm_front", v -> ModConfig.stormFront = v),
			toggle("rainbow", ModConfig.rainbow, "rainbow", v -> ModConfig.rainbow = v));
		this.list.row(toggle("heat_shimmer", ModConfig.heatShimmer, "heat_shimmer", v -> ModConfig.heatShimmer = v),
			toggle("breath", ModConfig.breath, "breath", v -> ModConfig.breath = v));
		this.list.row(toggle("end_sky", ModConfig.endSky, "end_sky", v -> ModConfig.endSky = v),
			toggle("end_effects", ModConfig.endEffects, "end_effects", v -> ModConfig.endEffects = v));
		this.list.row(toggle("temperature_hud", ModConfig.temperatureHud, "temperature_hud", v -> ModConfig.temperatureHud = v));

		this.list.header("options.ballisticmissiles.section.nature_world", 0xFFFFC080);
		this.list.note("options.ballisticmissiles.nature_world.note");
		String[] names = de.rcm.ballistic.config.ServerConfig.NATURE_NAMES;
		List<AbstractWidget> pending = new ArrayList<>();
		for (int i = 0; i < names.length; i++) {
			String name = names[i];
			boolean on = (ModConfig.serverNature & (1 << i)) != 0;
			pending.add(command(CycleButton.onOffBuilder(on)
				.withTooltip(v -> Tooltip.create(Component.translatable("options.ballisticmissiles.nature." + name + ".tooltip")))
				.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.nature." + name),
					(b, v) -> this.run("bmserver natur " + name + " " + (v ? "an" : "aus")))));
			if (pending.size() == 2) {
				this.list.row(pending.get(0), pending.get(1));
				pending.clear();
			}
		}
		if (!pending.isEmpty()) {
			this.list.row(pending.get(0));
		}
	}

	private void effects() {
		this.list.header("options.ballisticmissiles.section.screen", 0xFFFFC080);
		this.list.row(new IntSlider(0, 100, "options.ballisticmissiles.screen_effects", ModConfig.screenEffects, v -> ModConfig.screenEffects = v),
			new IntSlider(0, 100, "options.ballisticmissiles.explosion_volume", ModConfig.explosionVolume, v -> ModConfig.explosionVolume = v));
		this.list.row(toggle("lens_flare", ModConfig.lensFlare, "lens_flare", v -> ModConfig.lensFlare = v),
			new IntSlider(0, 100, "options.ballisticmissiles.bloom", ModConfig.bloom, v -> ModConfig.bloom = v));
		this.list.header("options.ballisticmissiles.section.hud_camera", 0xFF90D0FF);
		this.list.row(toggle("hotbar_autohide", ModConfig.hotbarAutoHide, "hotbar_autohide", v -> ModConfig.hotbarAutoHide = v),
			new IntSlider(2, 20, "options.ballisticmissiles.hotbar_hide_seconds", ModConfig.hotbarHideSeconds, v -> ModConfig.hotbarHideSeconds = v));
		this.list.row(toggle("smooth_look", ModConfig.smoothLook, "smooth_look", v -> ModConfig.smoothLook = v),
			toggle("smooth_move", ModConfig.smoothMove, "smooth_move", v -> ModConfig.smoothMove = v));
		this.list.row(toggle("first_person_body", ModConfig.firstPersonBody, "first_person_body", v -> ModConfig.firstPersonBody = v));
		this.list.row(toggle("patch_notes", ModConfig.patchNotes, "patch_notes", v -> ModConfig.patchNotes = v),
			Button.builder(Component.translatable("options.ballisticmissiles.patch_notes_show"),
				b -> this.minecraft.setScreen(PatchNotesScreen.latest(this))).build());
		this.list.header("options.ballisticmissiles.section.world", 0xFFC0C0C0);
		this.list.row(new IntSlider(0, 100, "options.ballisticmissiles.smoke_amount", ModConfig.smokeAmount, v -> ModConfig.smokeAmount = v),
			toggle("bullet_holes", ModConfig.bulletHoles, null, v -> ModConfig.bulletHoles = v));
		this.list.row(toggle("tracks", ModConfig.tracks, "tracks", v -> ModConfig.tracks = v));
		this.list.row(CycleButton.booleanBuilder(Component.translatable("options.ballisticmissiles.smoke_fade.fast"),
				Component.translatable("options.ballisticmissiles.smoke_fade.long"), ModConfig.fastSmoke)
			.withTooltip(v -> Tooltip.create(Component.translatable("options.ballisticmissiles.smoke_fade.tooltip")))
			.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.smoke_fade"), (b, v) -> ModConfig.fastSmoke = v));
		this.list.row(CycleButton.booleanBuilder(Component.translatable("options.ballisticmissiles.smoke_render.volume").withStyle(ChatFormatting.AQUA),
				Component.translatable("options.ballisticmissiles.smoke_render.sprites"), ModConfig.volumetricSmoke)
			.withTooltip(v -> Tooltip.create(Component.translatable("options.ballisticmissiles.smoke_render.tooltip")))
			.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.smoke_render"), (b, v) -> ModConfig.volumetricSmoke = v));
	}

	private void gore() {
		this.list.header("options.ballisticmissiles.section.display", 0xFFFF7070);
		this.list.row(toggle("blood", ModConfig.blood, "blood", v -> ModConfig.blood = v), toggle("gore", ModConfig.gore, "gore", v -> ModConfig.gore = v));
		this.list.row(toggle("gore_warning", ModConfig.goreWarning, null, v -> ModConfig.goreWarning = v));
		this.list.row(toggle("update_notice", ModConfig.updateNotice, null, v -> ModConfig.updateNotice = v));
		this.list.header("options.ballisticmissiles.section.gore_test", 0xFFFF7070);
		this.list.note("options.ballisticmissiles.gore_test.note");
		List<String> targets = new ArrayList<>(List.of("@s", "@a"));
		if (this.minecraft.getConnection() != null) {
			this.minecraft.getConnection().getOnlinePlayers().stream().map(info -> info.getProfile().name()).sorted(String.CASE_INSENSITIVE_ORDER)
				.forEach(targets::add);
		}
		if (!targets.contains(goreTarget)) {
			goreTarget = "@s";
		}
		this.list.row(CycleButton.builder((String t) -> switch (t) {
				case "@s" -> Component.translatable("options.ballisticmissiles.target.self").withStyle(ChatFormatting.AQUA);
				case "@a" -> Component.translatable("options.ballisticmissiles.target.all").withStyle(ChatFormatting.GOLD);
				default -> Component.literal(t).withStyle(ChatFormatting.YELLOW);
			}, goreTarget)
			.withValues(targets)
			.withTooltip(t -> Tooltip.create(Component.translatable("options.ballisticmissiles.target.tooltip")))
			.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.target"), (b, v) -> {
				goreTarget = v;
				this.refresh = 1;
			}));
		this.list.row(gore("gore.leg_left", "bein %s links"), gore("gore.leg_right", "bein %s rechts"), gore("gore.leg_both", "bein %s beide"));
		this.list.row(gore("gore.arm_left", "arm %s links"), gore("gore.arm_right", "arm %s rechts"), gore("gore.arm_both", "arm %s beide"));
		this.list.row(gore("gore.graze", "kopf %s streifschuss"), gore("gore.headshot", "kopf %s toedlich"));
		this.list.row(gore("gore.jaw", "kiefer %s"), gore("gore.through", "durchschuss %s"));
		this.list.row(gore("gore.dying", "sterben %s"), gore("gore.heal", "heilen %s"));
		// the target's invulnerability, as the integrated server knows it (one player: theirs; everyone: off)
		var server = this.minecraft.getSingleplayerServer();
		boolean invulnerable = false;
		if (server != null && this.minecraft.player != null && !goreTarget.equals("@a")) {
			var target = goreTarget.equals("@s") ? server.getPlayerList().getPlayer(this.minecraft.player.getUUID())
				: server.getPlayerList().getPlayerByName(goreTarget);
			invulnerable = target != null && de.rcm.ballistic.injury.Injuries.invulnerable(target);
		}
		this.list.row(command(CycleButton.onOffBuilder(invulnerable)
			.withTooltip(v -> Tooltip.create(Component.translatable("options.ballisticmissiles.invulnerable.tooltip")))
			.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.invulnerable"),
				(b, v) -> this.run("bmgore unverwundbar " + goreTarget + " " + (v ? "an" : "aus")))));
	}

	/** A gore test on the chosen player(s): {@code %s} in the command is where they go. */
	private AbstractWidget gore(String key, String command) {
		return command(Button.builder(Component.translatable("options.ballisticmissiles." + key),
			b -> this.run("bmgore " + String.format(Locale.ROOT, command, goreTarget))).build());
	}

	private void ai() {
		this.soldiers();
		this.list.header("options.ballisticmissiles.section.ai_tools", 0xFF90E090);
		boolean local = this.minecraft.getSingleplayerServer() != null && this.minecraft.player != null;
		boolean debug = local && SoldierDebug.viewing(this.minecraft.player.getUUID());
		this.list.row(command(CycleButton.onOffBuilder(debug)
				.withTooltip(v -> Tooltip.create(Component.translatable("options.ballisticmissiles.ai_debug.tooltip")))
				.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.ai_debug"), (b, v) -> this.run("bmai debug"))),
			command(CycleButton.onOffBuilder(!local || !SoldierEntity.ignoreCreative)
				.withTooltip(v -> Tooltip.create(Component.translatable("options.ballisticmissiles.ai_creative.tooltip")))
				.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.ai_creative"),
					(b, v) -> this.run("bmai kreativ " + (v ? "an" : "aus")))));
		this.list.row(action("ai.tool", "bmai werkzeug"), action("ai.remove", "bmai entfernen"));
	}

	/** The soldier spawner: how many, which side, and the button. */
	private void soldiers() {
		this.list.header("options.ballisticmissiles.section.soldiers", 0xFF90E090);
		this.list.row(new IntSlider(1, 16, "options.ballisticmissiles.ai_count", soldierCount, v -> soldierCount = v),
			CycleButton.booleanBuilder(Component.translatable("options.ballisticmissiles.ai_friendly").withStyle(ChatFormatting.GREEN),
					Component.translatable("options.ballisticmissiles.ai_hostile").withStyle(ChatFormatting.RED), soldierFriendly)
				.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.ai_team"), (b, v) -> soldierFriendly = v));
		this.list.row(command(Button.builder(Component.translatable("options.ballisticmissiles.ai_spawn"),
				b -> this.run("bmai spawn " + soldierCount + (soldierFriendly ? " freund" : " feind")))
			.tooltip(Tooltip.create(Component.translatable("options.ballisticmissiles.ai_spawn.tooltip"))).build()));
	}

	private void spawn() {
		this.soldiers();
		this.list.header("options.ballisticmissiles.section.items", 0xFFFFE080);
		this.list.note("options.ballisticmissiles.items.note");
		String filter = lastSearch.trim().toLowerCase(Locale.ROOT);
		List<AbstractWidget> buttons = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			Identifier id = BuiltInRegistries.ITEM.getKey(item);
			if (!id.getNamespace().equals(BallisticMissiles.MOD_ID) || item == Items.AIR) {
				continue;
			}
			ItemStack stack = new ItemStack(item);
			if (!filter.isEmpty() && !stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(filter) && !id.getPath().contains(filter)) {
				continue;
			}
			buttons.add(command(new ItemButton(stack, id, shift -> this.run("give @s " + id + " " + (shift ? stack.getMaxStackSize() : 1)))));
		}
		if (buttons.isEmpty()) {
			this.list.note("options.ballisticmissiles.items.none");
		}
		int perRow = Math.max(1, (this.rowW - 24 + 2) / ITEM);
		for (int i = 0; i < buttons.size(); i += perRow) {
			int end = Math.min(buttons.size(), i + perRow);
			this.list.grid(buttons.subList(i, end), i == 0, end == buttons.size());
		}
	}

	private void server() {
		this.list.header("options.ballisticmissiles.section.world_settings", 0xFFFFC080);
		this.list.note("options.ballisticmissiles.server.note");
		// 0-100 %: sent to the server once the slider is let go, not for every step it passes while dragging
		IntSlider misfires = new IntSlider(0, 100, "options.ballisticmissiles.misfires.slider", ModConfig.serverMisfireChance,
			v -> this.run("bmserver fehlstarts " + v));
		misfires.commitOnRelease = true;
		misfires.setTooltip(Tooltip.create(Component.translatable("options.ballisticmissiles.misfires.tooltip")));
		int[] damage = {0, 25, 50, 75, 100, 150, 200, 300, 500};
		var gunDamage = CycleButton.builder((Integer c) -> Component.translatable("options.ballisticmissiles.misfires.value", c),
				nearest(damage, ModConfig.serverGunDamage))
			.withValues(boxed(damage))
			.withTooltip(c -> Tooltip.create(Component.translatable("options.ballisticmissiles.gun_damage.tooltip")))
			.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.gun_damage"), (b, v) -> this.run("bmgun schaden " + v));
		this.list.row(command(misfires), command(gunDamage));
		this.list.row(command(CycleButton.booleanBuilder(Component.translatable("options.ballisticmissiles.allowed").withStyle(ChatFormatting.GREEN),
					Component.translatable("options.ballisticmissiles.forbidden").withStyle(ChatFormatting.RED), ModConfig.serverGore)
				.withTooltip(v -> Tooltip.create(Component.translatable("options.ballisticmissiles.server_gore.tooltip")))
				.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.server_gore"), (b, v) -> this.run("bmserver gore " + (v ? "an" : "aus")))),
			command(CycleButton.booleanBuilder(Component.translatable("options.ballisticmissiles.allowed").withStyle(ChatFormatting.GREEN),
					Component.translatable("options.ballisticmissiles.forbidden").withStyle(ChatFormatting.RED), ModConfig.serverNukes)
				.withTooltip(v -> Tooltip.create(Component.translatable("options.ballisticmissiles.server_nukes.tooltip")))
				.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.server_nukes"),
					(b, v) -> this.run("bmserver nukes " + (v ? "erlauben" : "verbieten")))));
		this.list.row(command(CycleButton.onOffBuilder(ModConfig.serverKillcam)
			.withTooltip(v -> Tooltip.create(Component.translatable("options.ballisticmissiles.server_killcam.tooltip")))
			.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.server_killcam"), (b, v) -> this.run("bmserver killcam " + (v ? "an" : "aus")))));

		this.list.header("options.ballisticmissiles.section.events", 0xFF8080FF);
		// 1-100: sent to the server once the slider is let go
		IntSlider darkness = new IntSlider(1, 100, "options.ballisticmissiles.new_moon_darkness", de.rcm.ballistic.client.effect.NewMoonClient.darknessSetting(),
			v -> this.run("bmworld neumond dunkelheit " + v));
		darkness.commitOnRelease = true;
		darkness.setTooltip(Tooltip.create(Component.translatable("options.ballisticmissiles.new_moon_darkness.tooltip")));
		this.list.row(command(CycleButton.onOffBuilder(de.rcm.ballistic.client.effect.NewMoonClient.active())
			.withTooltip(v -> Tooltip.create(Component.translatable("options.ballisticmissiles.new_moon.tooltip")))
			.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.new_moon"),
				(b, v) -> this.run("bmworld neumond " + (v ? "an" : "aus")))),
			command(darkness));
		this.list.row(CycleButton.onOffBuilder(ModConfig.nightSky)
				.withTooltip(v -> Tooltip.create(Component.translatable("options.ballisticmissiles.night_sky.tooltip")))
				.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.night_sky"), (b, v) -> ModConfig.nightSky = v),
			command(CycleButton.onOffBuilder(de.rcm.ballistic.client.effect.AuroraClient.forced())
				.withTooltip(v -> Tooltip.create(Component.translatable("options.ballisticmissiles.aurora.tooltip")))
				.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.aurora"),
					(b, v) -> this.run("bmworld polarlicht " + (v ? "an" : "aus")))));
		this.list.row(command(CycleButton.onOffBuilder(de.rcm.ballistic.client.effect.AuroraClient.random())
			.withTooltip(v -> Tooltip.create(Component.translatable("options.ballisticmissiles.aurora_random.tooltip")))
			.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.aurora_random"),
				(b, v) -> this.run("bmworld polarlicht zufall " + (v ? "an" : "aus")))));

		this.list.header("options.ballisticmissiles.section.repair", 0xFF90C0FF);
		this.list.note("options.ballisticmissiles.repair.note");
		var server = this.minecraft.getSingleplayerServer();
		if (server != null && this.minecraft.level != null) {
			var level = server.getLevel(this.minecraft.level.dimension());
			if (level != null) {
				this.list.text(Component.translatable("options.ballisticmissiles.repair.remembered",
					String.format(Locale.ROOT, "%,d", de.rcm.ballistic.explosion.CraterLog.remembered(level)).replace(',', '.')));
			}
		}
		int[] radii = {16, 32, 64, 128, 256, 512};
		this.list.row(CycleButton.builder((Integer r) -> Component.translatable("options.ballisticmissiles.repair.blocks", r), nearest(radii, craterRadius))
				.withValues(boxed(radii))
				.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles.repair.radius"), (b, v) -> craterRadius = v),
			command(Button.builder(Component.translatable("options.ballisticmissiles.repair.button"), b -> this.run("bmworld krater reparieren " + craterRadius))
				.tooltip(Tooltip.create(Component.translatable("options.ballisticmissiles.repair.tooltip"))).build()));
	}

	private static Integer[] boxed(int[] values) {
		return Arrays.stream(values).boxed().toArray(Integer[]::new);
	}

	/** The step nearest to {@code value}. */
	private static int nearest(int[] steps, int value) {
		int best = steps[0];
		for (int st : steps) {
			if (Math.abs(st - value) < Math.abs(best - value)) {
				best = st;
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- building blocks

	private static CycleButton<Boolean> toggle(String key, boolean value, String tooltip, Consumer<Boolean> setter) {
		var builder = CycleButton.onOffBuilder(value);
		if (tooltip != null) {
			builder = builder.withTooltip(v -> Tooltip.create(Component.translatable("options.ballisticmissiles." + tooltip + ".tooltip")));
		}
		return builder.create(0, 0, 150, 20, Component.translatable("options.ballisticmissiles." + key), (b, v) -> setter.accept(v));
	}

	/** A button that runs a command. */
	private AbstractWidget action(String key, String command) {
		return command(Button.builder(Component.translatable("options.ballisticmissiles." + key), b -> this.run(command)).build());
	}

	/** A widget that acts through a command: greyed out where the player may not use them. */
	private <T extends AbstractWidget> T command(T widget) {
		if (!this.mayCommand()) {
			widget.active = false;
			widget.setTooltip(Tooltip.create(Component.translatable("options.ballisticmissiles.needs_op")));
		}
		return widget;
	}

	private boolean mayCommand() {
		return this.minecraft.player != null && this.minecraft.getConnection() != null
			&& this.minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private void run(String command) {
		if (this.minecraft.player == null || this.minecraft.getConnection() == null) {
			return;
		}
		this.minecraft.getConnection().sendCommand(command);
		this.show(Component.literal("/" + command));
		this.refresh = 4;
	}

	private void show(Component message) {
		this.status = message;
		this.statusUntil = System.currentTimeMillis() + 3000L;
	}

	// ---------------------------------------------------------------- drawing

	/** A card's piece: rounded where the card begins and ends, square where the next row joins on. */
	private static void segment(GuiGraphics g, int x, int y, int w, int h, boolean first, boolean last, int argb) {
		int r = 8;
		if (!first && !last) {
			g.fill(x, y, x + w, y + h, argb);
			return;
		}
		UiKit.round(g, UiKit.FILL, x, y, w, h, r, argb);
		if (!first) {
			g.fill(x, y, x + w, y + r, argb);
		}
		if (!last) {
			g.fill(x, y + h - r, x + w, y + h, argb);
		}
	}

	/** Dark text on a light colour, white on a dark one. */
	private static int inkOn(int rgb) {
		float l = 0.299F * (rgb >> 16 & 255) + 0.587F * (rgb >> 8 & 255) + 0.114F * (rgb & 255);
		return l > 150.0F ? 0x0B0D10 : 0xFFFFFF;
	}

	/** An item drawn smaller than its 16 pixels. */
	private static void smallItem(GuiGraphics g, ItemStack stack, float x, float y, float size) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(size / 16.0F, size / 16.0F);
		g.renderItem(stack, 0, 0);
		g.pose().popMatrix();
	}

	private String version() {
		return FabricLoader.getInstance().getModContainer(BallisticMissiles.MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		long now = net.minecraft.util.Util.getMillis();
		float dt = Math.min(0.1F, (now - this.lastFrame) / 1000.0F);
		this.lastFrame = now;
		this.pageSwitch = UiKit.approach(this.pageSwitch, 1.0F, dt * 5.0F);
		int accent = this.page.accent();
		int x0 = this.panelX();
		int y0 = this.panelY();
		int pw = this.panelW();
		int ph = this.panelH();
		// the world sunk into the dark, the page's colour a faint glow behind the panel, the panel lifted off it
		g.fill(0, 0, this.width, this.height, UiKit.argb(this.minecraft.level == null ? 1.0F : 0.8F, BACKDROP));
		UiKit.orb(g, x0 - pw * 0.3F, y0 - ph * 0.45F, pw * 1.6F, ph * 1.3F, accent, 0.09F);
		UiKit.orb(g, x0 - 32, y0 - 22, pw + 64, ph + 54, 0x000000, 0.8F);
		UiKit.round(g, UiKit.FILL, x0, y0, pw, ph, 12, UiKit.argb(1.0F, PANEL));
		// the sidebar, a shade darker, a hairline between it and the page
		UiKit.round(g, UiKit.FILL, x0, y0, SIDE, ph, 12, UiKit.argb(1.0F, SIDEBAR));
		g.fill(x0 + SIDE - 12, y0, x0 + SIDE, y0 + ph, UiKit.argb(1.0F, SIDEBAR));
		g.fill(x0 + SIDE, y0, x0 + SIDE + 1, y0 + ph, UiKit.argb(1.0F, LINE));
		UiKit.round(g, UiKit.EDGE, x0, y0, pw, ph, 12, UiKit.argb(1.0F, 0x252A33));
		// the mark: a missile on a red tile, the name beside it
		UiKit.round(g, UiKit.FILL, x0 + 16, y0 + 18, 24, 24, 7, UiKit.argb(1.0F, 0xE5484D));
		g.renderItem(new ItemStack(de.rcm.ballistic.ModRegistry.missileItem(de.rcm.ballistic.entity.MissileType.NUCLEAR)), x0 + 20, y0 + 22);
		g.drawString(this.font, Component.literal("BALLISTIC").withStyle(ChatFormatting.BOLD), x0 + 48, y0 + 20, UiKit.argb(1.0F, TEXT), false);
		g.drawString(this.font, Component.literal("MISSILES").withStyle(ChatFormatting.BOLD), x0 + 48, y0 + 31, UiKit.argb(1.0F, 0xE5484D), false);
		// the pages, in two groups
		g.drawString(this.font, Component.translatable("options.ballisticmissiles.group.client").getString().toUpperCase(Locale.ROOT), x0 + 18,
			this.sideItemY(0) - 14, UiKit.argb(1.0F, FAINT), false);
		g.drawString(this.font, Component.translatable("options.ballisticmissiles.group.world").getString().toUpperCase(Locale.ROOT), x0 + 18,
			this.sideItemY(4) - 14, UiKit.argb(1.0F, FAINT), false);
		for (Page p : Page.values()) {
			int i = p.ordinal();
			int y = this.sideItemY(i);
			int x = x0 + 10;
			int w = SIDE - 20;
			boolean selected = p == this.page;
			boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 22;
			this.sideHover[i] = UiKit.approach(this.sideHover[i], selected ? 1.0F : over ? 0.6F : 0.0F, dt * 12.0F);
			float k = this.sideHover[i];
			if (k > 0.01F) {
				UiKit.round(g, UiKit.FILL, x, y, w, 22, 6, UiKit.argb(selected ? 1.0F : k, 0x1A1F27));
			}
			if (selected) {
				UiKit.round(g, UiKit.FILL, x0 + 4, y + 5, 3, 12, 1, UiKit.argb(1.0F, p.accent()));
			}
			UiKit.round(g, UiKit.FILL, x + 5, y + 2, 18, 18, 5, UiKit.argb(1.0F, UiKit.lerpColor(0x161A20, p.accent(), selected ? 0.32F : 0.14F + 0.08F * k)));
			smallItem(g, p.icon(), x + 7, y + 4, 14.0F);
			int ink = selected ? TEXT : UiKit.lerpColor(MUTED, BODY, k);
			g.drawString(this.font, this.font.plainSubstrByWidth(p.title().getString(), w - 34), x + 30, y + 7, UiKit.argb(1.0F, ink), false);
		}
		// the version and the build's stage, at the bottom of the sidebar (where there is room)
		int by = y0 + ph - 26;
		if (by > this.sideItemY(6) + 30) {
			String v = "v" + this.version();
			int vw = this.font.width(v) + 10;
			UiKit.round(g, UiKit.FILL, x0 + 16, by, vw, 14, 5, UiKit.argb(1.0F, 0x171B21));
			g.drawString(this.font, v, x0 + 21, by + 3, UiKit.argb(1.0F, MUTED), false);
			int aw = this.font.width("ALPHA") + 10;
			UiKit.round(g, UiKit.FILL, x0 + 20 + vw, by, aw, 14, 5, UiKit.argb(0.16F, 0xF5A524));
			g.drawString(this.font, "ALPHA", x0 + 25 + vw, by + 3, UiKit.argb(1.0F, 0xF5B54A), false);
		}
		// the page's header: its icon on a tile of its colour, the title, what the page is for
		int cx = this.contentX();
		float e = UiKit.easeOutExpo(this.pageSwitch);
		int slide = Math.round((1.0F - e) * 8.0F);
		UiKit.round(g, UiKit.FILL, cx, y0 + 18, 30, 30, 8, UiKit.argb(1.0F, UiKit.lerpColor(PANEL, accent, 0.22F)));
		UiKit.round(g, UiKit.EDGE, cx, y0 + 18, 30, 30, 8, UiKit.argb(0.45F, accent));
		g.renderItem(this.page.icon(), cx + 7, y0 + 25);
		g.pose().pushMatrix();
		g.pose().translate(cx + 42 + slide, y0 + 19);
		g.pose().scale(1.5F, 1.5F);
		g.drawString(this.font, this.page.title().copy().withStyle(ChatFormatting.BOLD), 0, 0, UiKit.argb(e, TEXT), false);
		g.pose().popMatrix();
		g.drawString(this.font, this.font.plainSubstrByWidth(this.page.description().getString(), this.contentRight() - cx - 42), cx + 42 + slide, y0 + 37,
			UiKit.argb(e, MUTED), false);
		g.fill(cx, y0 + HEAD, this.contentRight(), y0 + HEAD + 1, UiKit.argb(1.0F, LINE));
		// the search field on the spawn page: a rounded well, outlined in the page's colour while typing
		if (this.page == Page.SPAWN) {
			int sx = cx;
			int sy = y0 + HEAD + 9;
			int sw = this.search.getWidth() + 16;
			UiKit.round(g, UiKit.FILL, sx, sy, sw, 20, 6, UiKit.argb(1.0F, CONTROL));
			UiKit.round(g, UiKit.EDGE, sx, sy, sw, 20, 6, UiKit.argb(1.0F, this.search.isFocused() ? accent : CONTROL_EDGE));
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		this.done.setAlpha(0.0F);
		super.render(graphics, mouseX, mouseY, partialTick);
		int accent = this.page.accent();
		int cx = this.contentX();
		int right = this.contentRight();
		// the list fades out under the header and over the footer instead of being cut off
		int listL = this.list.getX();
		int listR = this.list.getRight();
		graphics.fillGradient(listL, this.list.getY(), listR, this.list.getY() + 8, UiKit.argb(1.0F, PANEL), UiKit.argb(0.0F, PANEL));
		graphics.fillGradient(listL, this.list.getBottom() - 10, listR, this.list.getBottom(), UiKit.argb(0.0F, PANEL), UiKit.argb(1.0F, PANEL));
		int fy = this.panelY() + this.panelH() - FOOTER;
		graphics.fill(cx, fy, right, fy + 1, UiKit.argb(1.0F, LINE));
		int ty = this.done.getY() + 6;
		if (this.status != null && System.currentTimeMillis() < this.statusUntil) {
			// what a command just did, fading
			float a = (float) Mth.clamp((this.statusUntil - System.currentTimeMillis()) / 600.0, 0.0, 1.0);
			String s = this.font.plainSubstrByWidth(this.status.getString(), this.done.getX() - cx - 30);
			int w = this.font.width(s);
			UiKit.round(graphics, UiKit.FILL, cx, this.done.getY() + 1, w + 22, 18, 6, UiKit.argb(0.18F * a, 0x3FCF8E));
			UiKit.disc(graphics, cx + 8, this.done.getY() + 10, 2.5F, UiKit.argb(a, 0x3FCF8E));
			graphics.drawString(this.font, s, cx + 15, ty, UiKit.argb(a, 0x9FF0C8), false);
		} else {
			// how the settings are kept
			UiKit.disc(graphics, cx + 3, ty + 4, 2.0F, UiKit.argb(1.0F, 0x3FCF8E));
			graphics.drawString(this.font, this.font.plainSubstrByWidth(Component.translatable("options.ballisticmissiles.autosave").getString(),
				this.done.getX() - cx - 20), cx + 10, ty, UiKit.argb(1.0F, FAINT), false);
		}
		// done: filled in the page's colour
		boolean over = this.done.isMouseOver(mouseX, mouseY);
		int fill = over ? UiKit.lerpColor(accent, 0xFFFFFF, 0.15F) : accent;
		UiKit.round(graphics, UiKit.FILL, this.done.getX(), this.done.getY(), this.done.getWidth(), this.done.getHeight(), 6, UiKit.argb(1.0F, fill));
		Component d = this.done.getMessage().copy().withStyle(ChatFormatting.BOLD);
		graphics.drawString(this.font, d, this.done.getX() + (this.done.getWidth() - this.font.width(d)) / 2, ty, UiKit.argb(1.0F, inkOn(fill)), false);
	}

	/** How far a toggle's knob is over (0 off .. 1 on), easing towards where it is. */
	private float knob(AbstractWidget w, boolean on) {
		float k = this.knobs.getOrDefault(w, on ? 1.0F : 0.0F);
		k = UiKit.approach(k, on ? 1.0F : 0.0F, 0.25F);
		this.knobs.put(w, k);
		return k;
	}

	/** A widget's tooltip text, shown under the setting's name as its description. */
	private static Component describe(AbstractWidget w) {
		Tooltip t = ((de.rcm.ballistic.client.mixin.AbstractWidgetAccessor) (Object) w).ballisticmissiles$tooltip().get();
		return t == null ? null : ((de.rcm.ballistic.client.mixin.TooltipAccessor) (Object) t).ballisticmissiles$message();
	}

	/** A cycle button's name and value (its message is "name: value"). */
	private static Component[] nameAndValue(AbstractWidget w) {
		Component msg = w.getMessage();
		if (msg.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc && tc.getArgs().length == 2
			&& tc.getArgs()[0] instanceof Component n && tc.getArgs()[1] instanceof Component v) {
			return new Component[] {n, v};
		}
		return new Component[] {msg, Component.empty()};
	}

	/** A small up-and-down caret: the value cycles. */
	private static void caret(GuiGraphics g, int x, int cy, int argb) {
		for (int i = 0; i < 3; i++) {
			g.fill(x + 2 - i, cy - 4 + i, x + 3 + i, cy - 3 + i, argb);
			g.fill(x + 2 - i, cy + 3 - i, x + 3 + i, cy + 4 - i, argb);
		}
	}

	/** A button drawn in the panel's style; the widget itself still takes the clicks (and shows its tooltip), invisibly. */
	private void paintButton(GuiGraphics g, AbstractWidget b, boolean primary, int mouseX, int mouseY, float partialTick) {
		int x = b.getX();
		int y = b.getY();
		int w = b.getWidth();
		int h = b.getHeight();
		int accent = this.page.accent();
		boolean over = b.active && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h && this.list.isMouseOver(mouseX, mouseY);
		float a = b.active ? 1.0F : 0.45F;
		int fill = primary ? UiKit.lerpColor(CONTROL, accent, over ? 0.42F : 0.28F) : over ? 0x2A3039 : CONTROL;
		UiKit.round(g, UiKit.FILL, x, y, w, h, 6, UiKit.argb(a, fill));
		UiKit.round(g, UiKit.EDGE, x, y, w, h, 6, UiKit.argb(a * (primary ? 0.6F : 1.0F), primary ? accent : CONTROL_EDGE));
		String text = this.font.plainSubstrByWidth(b.getMessage().getString(), w - 10);
		g.drawString(this.font, text, x + (w - this.font.width(text)) / 2, y + (h - 8) / 2, UiKit.argb(a, TEXT), false);
		b.setAlpha(0.0F);
		b.render(g, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		ModConfig.save();
		this.minecraft.setScreen(this.parent);
	}

	// ---------------------------------------------------------------- the list

	/**
	 * The page: settings in cards, one per section - each setting a row with its name, what it does under it, and
	 * its control on the right (a switch, a value that cycles, a slider) - with the section titles, notes and
	 * buttons between them, scrolling.
	 */
	private final class Rows extends ContainerObjectSelectionList<Rows.Entry> {
		Rows(Minecraft minecraft, int width, int height, int y) {
			super(minecraft, width, height, y, 36);
			this.centerListVertically = false;
		}

		@Override
		public int getRowWidth() {
			return SettingsScreen.this.rowW;
		}

		@Override
		protected void renderListBackground(GuiGraphics graphics) {
		}

		@Override
		protected void renderListSeparators(GuiGraphics graphics) {
		}

		@Override
		protected int scrollBarX() {
			return this.getRowRight() + 4;
		}

		@Override
		protected void renderScrollbar(GuiGraphics graphics, int mouseX, int mouseY) {
			if (this.scrollbarVisible()) {
				int x = this.scrollBarX() + 2;
				UiKit.round(graphics, UiKit.FILL, x, this.getY() + 4, 3, this.getHeight() - 8, 1, UiKit.argb(1.0F, 0x181C22));
				UiKit.round(graphics, UiKit.FILL, x, this.scrollBarY(), 3, this.scrollerHeight(), 1,
					UiKit.argb(1.0F, UiKit.lerpColor(MUTED, SettingsScreen.this.page.accent(), 0.5F)));
			}
		}

		void clear() {
			this.clearEntries();
		}

		void header(String key, int colour) {
			this.addEntry(new Header(Component.translatable(key)), this.children().isEmpty() ? 24 : 36);
		}

		void note(String key) {
			var lines = SettingsScreen.this.font.split(Component.translatable(key), SettingsScreen.this.rowW - 38);
			this.addEntry(new Note(lines), lines.size() * 10 + 14 + 10);
		}

		void text(Component text) {
			this.addEntry(new Line(text), 18);
		}

		/** Settings each get a row of their own; buttons share one. */
		void row(AbstractWidget... widgets) {
			List<AbstractWidget> buttons = new ArrayList<>();
			for (AbstractWidget w : widgets) {
				if (w instanceof CycleButton<?> || w instanceof IntSlider) {
					Setting s = new Setting(w);
					this.addEntry(s, s.height);
				} else {
					buttons.add(w);
				}
			}
			if (!buttons.isEmpty()) {
				this.addEntry(new Buttons(buttons), 36);
			}
		}

		void grid(List<AbstractWidget> widgets, boolean first, boolean last) {
			this.addEntry(new Grid(widgets, first ? 8 : 1), ITEM + (first ? 8 : 0) + (last ? 8 : 0));
		}

		/** Which rows begin and end a card, and room at the end. */
		void finish() {
			var all = this.children();
			for (int i = 0; i < all.size(); i++) {
				if (all.get(i) instanceof Grouped g) {
					g.first = i == 0 || !(all.get(i - 1) instanceof Grouped);
					g.last = i == all.size() - 1 || !(all.get(i + 1) instanceof Grouped);
				}
			}
			this.addEntry(new Line(Component.empty()), 14);
		}

		abstract static class Entry extends ContainerObjectSelectionList.Entry<Entry> {
		}

		/** A row inside a card. */
		abstract class Grouped extends Entry {
			boolean first;
			boolean last;
			float hover;

			/** The card behind the row, lit while the mouse is on it, a hairline over it where it follows another. */
			void card(GuiGraphics g, boolean hovered, boolean lights) {
				this.hover = UiKit.approach(this.hover, hovered && lights ? 1.0F : 0.0F, 0.3F);
				int x = this.getX();
				int y = this.getY();
				int w = SettingsScreen.this.rowW;
				segment(g, x, y, w, this.getHeight(), this.first, this.last, UiKit.argb(1.0F, UiKit.lerpColor(CARD, CARD_HOVER, this.hover)));
				if (!this.first) {
					g.fill(x + 14, y, x + w - 14, y + 1, UiKit.argb(1.0F, LINE));
				}
			}
		}

		/** One setting: its name, what it does, its control. The whole row toggles or cycles it. */
		final class Setting extends Grouped {
			private final AbstractWidget widget;
			private final List<net.minecraft.util.FormattedCharSequence> description;
			/** The description is cut short: the tooltip keeps the rest. */
			private final boolean keepTooltip;
			final int height;

			Setting(AbstractWidget widget) {
				this.widget = widget;
				Component d = describe(widget);
				int textW = SettingsScreen.this.rowW - 28 - this.reserve() - 12;
				var lines = d == null ? List.<net.minecraft.util.FormattedCharSequence>of() : SettingsScreen.this.font.split(d, Math.max(60, textW));
				this.keepTooltip = lines.size() > 2;
				this.description = lines.size() > 2 ? lines.subList(0, 2) : lines;
				this.height = this.description.isEmpty() ? 32 : 26 + this.description.size() * 10;
			}

			private boolean isSwitch() {
				return this.widget instanceof CycleButton<?> c && c.getValue() instanceof Boolean && isOnOff(nameAndValue(this.widget)[1].getString());
			}

			/** How much of the row the control takes. */
			private int reserve() {
				int rowW = SettingsScreen.this.rowW;
				return this.isSwitch() ? 30 : this.widget instanceof IntSlider ? Math.min(200, rowW * 45 / 100) : Math.min(160, rowW * 2 / 5);
			}

			@Override
			public void renderContent(GuiGraphics g, int mouseX, int mouseY, boolean hovered, float partialTick) {
				var font = SettingsScreen.this.font;
				AbstractWidget w = this.widget;
				this.card(g, hovered, w.active);
				int x = this.getX();
				int y = this.getY();
				int rowW = SettingsScreen.this.rowW;
				int h = this.getHeight();
				int right = x + rowW - 14;
				int cy = y + h / 2;
				int accent = SettingsScreen.this.page.accent();
				float a = w.active ? 1.0F : 0.45F;
				String title;
				int titleMax = rowW - 28 - this.reserve() - 12;
				if (w instanceof IntSlider slider) {
					String text = w.getMessage().getString();
					int cut = text.lastIndexOf(": ");
					title = cut > 0 ? text.substring(0, cut) : text;
					String value = cut > 0 ? text.substring(cut + 2) : "";
					int area = this.reserve();
					int boxW = Math.max(40, font.width(value) + 14);
					int bx = right - boxW;
					UiKit.round(g, UiKit.FILL, bx, cy - 9, boxW, 18, 5, UiKit.argb(a, CONTROL));
					UiKit.round(g, UiKit.EDGE, bx, cy - 9, boxW, 18, 5, UiKit.argb(a, CONTROL_EDGE));
					g.drawString(font, value, bx + (boxW - font.width(value)) / 2, cy - 4, UiKit.argb(a, TEXT), false);
					int tw = Math.max(30, area - boxW - 16);
					int tx = bx - 14 - tw;
					float f = (float) slider.fraction();
					UiKit.round(g, UiKit.FILL, tx, cy - 2, tw, 4, 2, UiKit.argb(a, 0x2C323B));
					UiKit.round(g, UiKit.FILL, tx, cy - 2, Math.max(4, Math.round(tw * f)), 4, 2, UiKit.argb(a, accent));
					UiKit.disc(g, tx + tw * f, cy + 0.6F, 6.5F, UiKit.argb(0.45F * a, 0x000000));
					UiKit.disc(g, tx + tw * f, cy, 6.0F, UiKit.argb(a, 0xFFFFFF));
					// the slider itself lies over the track: where the mouse is along it is the value
					w.setPosition(tx - 4, y);
					w.setWidth(tw + 8);
					w.setHeight(h);
				} else {
					Component[] nv = nameAndValue(w);
					title = nv[0].getString();
					if (this.isSwitch()) {
						boolean on = (Boolean) ((CycleButton<?>) w).getValue();
						float k = SettingsScreen.this.knob(w, on);
						int sx = right - 28;
						UiKit.round(g, UiKit.FILL, sx, cy - 8, 28, 16, 8, UiKit.argb(a, UiKit.lerpColor(0x2C323B, accent, k)));
						UiKit.disc(g, sx + 8 + 12 * k, cy + 0.6F, 6.5F, UiKit.argb(0.35F * a, 0x000000));
						UiKit.disc(g, sx + 8 + 12 * k, cy, 6.0F, UiKit.argb(a, 0xFFFFFF));
					} else {
						Component value = nv[1];
						String vs = font.plainSubstrByWidth(value.getString(), this.reserve() - 30);
						int cw = font.width(vs) + 30;
						int chipX = right - cw;
						UiKit.round(g, UiKit.FILL, chipX, cy - 10, cw, 20, 6, UiKit.argb(a, UiKit.lerpColor(CONTROL, 0x2A3039, this.hover)));
						UiKit.round(g, UiKit.EDGE, chipX, cy - 10, cw, 20, 6, UiKit.argb(a, CONTROL_EDGE));
						var colour = value.getStyle().getColor();
						int ink = colour != null ? UiKit.lerpColor(colour.getValue(), 0xFFFFFF, 0.2F) : TEXT;
						g.drawString(font, vs, chipX + 10, cy - 4, UiKit.argb(a, ink), false);
						caret(g, chipX + cw - 15, cy, UiKit.argb(a, MUTED));
					}
					w.setPosition(x, y);
					w.setWidth(rowW);
					w.setHeight(h);
				}
				int ty = this.description.isEmpty() ? cy - 4 : y + 8;
				g.drawString(font, font.plainSubstrByWidth(title, titleMax), x + 14, ty, UiKit.argb(a, TEXT), false);
				int dy = y + 20;
				for (var line : this.description) {
					g.drawString(font, line, x + 14, dy, UiKit.argb(a, MUTED), false);
					dy += 10;
				}
				if (!this.keepTooltip && !this.description.isEmpty()) {
					w.setTooltip(null);
				}
				w.setAlpha(0.0F);
				w.render(g, mouseX, mouseY, partialTick);
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return List.of(this.widget);
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return List.of(this.widget);
			}
		}

		/** Buttons side by side across a row of the card; a single one is drawn in the page's colour. */
		final class Buttons extends Grouped {
			private final List<AbstractWidget> buttons;

			Buttons(List<AbstractWidget> buttons) {
				this.buttons = buttons;
			}

			@Override
			public void renderContent(GuiGraphics g, int mouseX, int mouseY, boolean hovered, float partialTick) {
				this.card(g, false, false);
				int n = this.buttons.size();
				int gap = 8;
				int bw = (SettingsScreen.this.rowW - 28 - gap * (n - 1)) / n;
				int x = this.getX() + 14;
				int y = this.getY() + (this.getHeight() - 20) / 2;
				for (AbstractWidget b : this.buttons) {
					b.setPosition(x, y);
					b.setWidth(bw);
					b.setHeight(20);
					SettingsScreen.this.paintButton(g, b, n == 1, mouseX, mouseY, partialTick);
					x += bw + gap;
				}
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return this.buttons;
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return this.buttons;
			}
		}

		/** A row of item tiles in the card. */
		final class Grid extends Grouped {
			private final List<AbstractWidget> items;
			private final int top;

			Grid(List<AbstractWidget> items, int top) {
				this.items = items;
				this.top = top;
			}

			@Override
			public void renderContent(GuiGraphics g, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int x = this.getX();
				int y = this.getY();
				segment(g, x, y, SettingsScreen.this.rowW, this.getHeight(), this.first, this.last, UiKit.argb(1.0F, CARD));
				x += 12;
				for (AbstractWidget item : this.items) {
					item.setPosition(x, y + this.top);
					item.render(g, mouseX, mouseY, partialTick);
					x += ITEM;
				}
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return this.items;
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return this.items;
			}
		}

		/** A section's title over its card. */
		final class Header extends Entry {
			private final Component text;

			Header(Component text) {
				this.text = text;
			}

			@Override
			public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int y = this.getY() + this.getHeight() - 15;
				int colour = UiKit.lerpColor(MUTED, SettingsScreen.this.page.accent(), 0.45F);
				graphics.drawString(SettingsScreen.this.font, this.text.getString().toUpperCase(Locale.ROOT), this.getX() + 4, y, UiKit.argb(1.0F, colour), false);
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return List.of();
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return List.of();
			}
		}

		/** A note: an "i" and a few lines, on a faint tint of the page's colour. */
		final class Note extends Entry {
			private final List<net.minecraft.util.FormattedCharSequence> lines;

			Note(List<net.minecraft.util.FormattedCharSequence> lines) {
				this.lines = lines;
			}

			@Override
			public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int accent = SettingsScreen.this.page.accent();
				int x = this.getX();
				int y = this.getY();
				int w = SettingsScreen.this.rowW;
				int h = this.lines.size() * 10 + 14;
				UiKit.round(graphics, UiKit.FILL, x, y, w, h, 8, UiKit.argb(1.0F, UiKit.lerpColor(PANEL, accent, 0.07F)));
				UiKit.round(graphics, UiKit.EDGE, x, y, w, h, 8, UiKit.argb(0.22F, accent));
				UiKit.disc(graphics, x + 15, y + 11, 5.5F, UiKit.argb(0.9F, accent));
				graphics.drawString(SettingsScreen.this.font, "i", x + 14, y + 7, UiKit.argb(1.0F, inkOn(accent)), false);
				int ty = y + 7;
				for (var line : this.lines) {
					graphics.drawString(SettingsScreen.this.font, line, x + 28, ty, UiKit.argb(1.0F, BODY), false);
					ty += 10;
				}
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return List.of();
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return List.of();
			}
		}

		/** A line of plain text (and, empty, room at the end of the page). */
		final class Line extends Entry {
			private final Component text;

			Line(Component text) {
				this.text = text;
			}

			@Override
			public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				graphics.drawString(SettingsScreen.this.font, this.text, this.getX() + 4, this.getY() + 4, UiKit.argb(1.0F, MUTED), false);
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return List.of();
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return List.of();
			}
		}
	}

	private static boolean isOnOff(String value) {
		return value.equals(CommonComponents.OPTION_ON.getString()) || value.equals(CommonComponents.OPTION_OFF.getString());
	}

	/** One of the mod's items: its icon, its name on hover; a click gives one, a shift-click a stack. */
	private static final class ItemButton extends AbstractButton {
		private final ItemStack stack;
		private final Consumer<Boolean> give;

		ItemButton(ItemStack stack, Identifier id, Consumer<Boolean> give) {
			super(0, 0, 20, 20, stack.getHoverName());
			this.stack = stack;
			this.give = give;
			this.setTooltip(Tooltip.create(stack.getHoverName().copy().append(Component.literal("\n" + id).withStyle(ChatFormatting.DARK_GRAY))));
		}

		@Override
		public void onPress(InputWithModifiers input) {
			this.give.accept(input.hasShiftDown());
		}

		@Override
		protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			int x = this.getX();
			int y = this.getY();
			boolean lit = this.active && this.isHoveredOrFocused();
			UiKit.round(graphics, UiKit.FILL, x, y, 20, 20, 5, lit ? 0xFF2A3039 : 0xFF20252D);
			if (lit) {
				UiKit.round(graphics, UiKit.EDGE, x, y, 20, 20, 5, 0xFFF0C04A);
			}
			graphics.renderItem(this.stack, x + 2, y + 2);
			if (!this.active) {
				graphics.fill(x, y, x + 20, y + 20, 0x80000000);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}
	}

	/** A whole-number slider from {@code min} to {@code max}. */
	private static final class IntSlider extends AbstractSliderButton {
		private final int min;
		private final int max;
		private final String key;
		private final IntConsumer setter;
		/** Hand the value on only when the slider is let go (for settings that cost something to change). */
		boolean commitOnRelease;
		private int committed;

		IntSlider(int min, int max, String key, int value, IntConsumer setter) {
			super(0, 0, 150, 20, Component.empty(), (Mth.clamp(value, min, max) - min) / (double) (max - min));
			this.min = min;
			this.max = max;
			this.key = key;
			this.setter = setter;
			this.committed = Mth.clamp(value, min, max);
			this.updateMessage();
		}

		private int current() {
			return Mth.clamp((int) Math.round(this.min + this.value * (this.max - this.min)), this.min, this.max);
		}

		double fraction() {
			return this.value;
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Component.translatable(this.key, this.current()));
		}

		@Override
		protected void applyValue() {
			if (!this.commitOnRelease) {
				this.setter.accept(this.current());
			}
		}

		@Override
		public void onRelease(net.minecraft.client.input.MouseButtonEvent event) {
			super.onRelease(event);
			this.commit();
		}

		@Override
		public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
			boolean used = super.keyPressed(event);
			this.commit();
			return used;
		}

		private void commit() {
			if (this.commitOnRelease && this.current() != this.committed) {
				this.committed = this.current();
				this.setter.accept(this.committed);
			}
		}
	}
}
