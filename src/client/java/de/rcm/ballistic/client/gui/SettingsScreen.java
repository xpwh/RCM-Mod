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
	private static final int FOOTER = 38;
	private static final int SEARCH = 26;
	private static final int ITEM = 22;
	/** The sidebar with the pages, and the header over the content. */
	private static final int SIDE = 132;
	private static final int HEAD = 46;
	// the palette of the mod's other cards: near-black, off-white for what matters, each page its own colour
	private static final int BACKDROP = 0x07090C;
	private static final int PANEL = 0x12161C;
	private static final int SIDEBAR = 0x0D1015;
	private static final int CARD = 0x1A1F27;
	private static final int CARD_HOVER = 0x232A34;
	private static final int TEXT = 0xEDEDEF;
	private static final int BODY = 0xB4B4BB;
	private static final int MUTED = 0x8E8E95;
	private static final int FAINT = 0x5E5E66;
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
		this.rowW = Math.max(200, this.panelW() - SIDE - 44);
		this.list = new Rows(this.minecraft, this.width, this.height, 0);
		this.addRenderableWidget(this.list);
		this.search = new EditBox(this.font, 0, 0, 200, 18, Component.translatable("options.ballisticmissiles.search"));
		this.search.setHint(Component.translatable("options.ballisticmissiles.search").withStyle(ChatFormatting.DARK_GRAY));
		this.search.setValue(lastSearch);
		this.search.setResponder(text -> {
			lastSearch = text;
			if (this.page == Page.SPAWN) {
				this.build();
			}
		});
		this.addRenderableWidget(this.search);
		this.done = this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).width(110).build());
		this.build();
		this.repositionElements();
	}

	// ---------------------------------------------------------------- layout

	private int panelW() {
		return Math.min(this.width - 16, 620);
	}

	private int panelX() {
		return (this.width - this.panelW()) / 2;
	}

	private int panelY() {
		return 10;
	}

	private int panelH() {
		return this.height - 20;
	}

	private int contentX() {
		return this.panelX() + SIDE + 20;
	}

	/** The sidebar's page buttons: where the i-th one is. */
	private int sideItemY(int i) {
		return this.panelY() + 52 + i * 26;
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
			int x = this.panelX() + 8;
			for (Page p : Page.values()) {
				int y = this.sideItemY(p.ordinal());
				if (event.x() >= x && event.x() < x + SIDE - 16 && event.y() >= y && event.y() < y + 22) {
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
		int top = this.panelY() + HEAD + 8;
		boolean spawn = this.page == Page.SPAWN;
		this.search.visible = spawn;
		this.search.setWidth(Math.min(220, this.rowW));
		this.search.setPosition(cx, top + 2);
		int listTop = top + (spawn ? SEARCH : 0);
		int bottom = this.panelY() + this.panelH() - FOOTER;
		this.list.updateSizeAndPosition(this.rowW + 14, bottom - listTop, cx - 7, listTop);
		this.done.setWidth(110);
		this.done.setPosition(this.panelX() + this.panelW() - 110 - 14, bottom + 10);
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
		int perRow = Math.max(1, (this.rowW + 2) / ITEM);
		for (int i = 0; i < buttons.size(); i += perRow) {
			this.list.grid(buttons.subList(i, Math.min(buttons.size(), i + perRow)));
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

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		long now = net.minecraft.util.Util.getMillis();
		float dt = Math.min(0.1F, (now - this.lastFrame) / 1000.0F);
		this.lastFrame = now;
		float t = (now - this.opened) / 1000.0F;
		this.pageSwitch = UiKit.approach(this.pageSwitch, 1.0F, dt * 5.0F);
		int accent = this.page.accent();
		// the backdrop: the world (or the menu) sunk into the dark, the page's colour glowing faintly behind the panel
		g.fill(0, 0, this.width, this.height, UiKit.argb(this.minecraft.level == null ? 1.0F : 0.82F, BACKDROP));
		UiKit.orb(g, this.width * 0.5F - this.width * 0.6F, this.height * 0.5F - this.height * 0.8F, this.width * 1.2F, this.height * 1.6F, accent,
			0.16F + 0.04F * Mth.sin(t * 1.2F));
		int x0 = this.panelX();
		int y0 = this.panelY();
		int pw = this.panelW();
		int ph = this.panelH();
		UiKit.orb(g, x0 - 40, y0 - 24, pw + 80, ph + 60, 0x000000, 0.7F);
		UiKit.round(g, UiKit.FILL, x0, y0, pw, ph, 12, UiKit.argb(0.97F, PANEL));
		UiKit.round(g, UiKit.EDGE, x0, y0, pw, ph, 12, UiKit.argb(0.35F, accent));
		// the sidebar
		UiKit.round(g, UiKit.FILL, x0 + 1, y0 + 1, SIDE, ph - 2, 11, UiKit.argb(1.0F, SIDEBAR));
		g.fill(x0 + SIDE, y0 + 10, x0 + SIDE + 1, y0 + ph - 10, UiKit.argb(0.6F, 0x262B33));
		// the name, a badge of a missile in a red dot
		UiKit.disc(g, x0 + 22, y0 + 24, 9.0F, UiKit.argb(1.0F, 0xE5484D));
		g.renderItem(new ItemStack(de.rcm.ballistic.ModRegistry.missileItem(de.rcm.ballistic.entity.MissileType.NUCLEAR)), x0 + 14, y0 + 16);
		g.drawString(this.font, Component.literal("BALLISTIC").withStyle(ChatFormatting.BOLD), x0 + 36, y0 + 15, UiKit.argb(1.0F, TEXT), false);
		g.drawString(this.font, Component.literal("MISSILES").withStyle(ChatFormatting.BOLD), x0 + 36, y0 + 25, UiKit.argb(1.0F, 0xE5484D), false);
		for (Page p : Page.values()) {
			int i = p.ordinal();
			int y = this.sideItemY(i);
			int x = x0 + 8;
			int w = SIDE - 16;
			boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 22;
			this.sideHover[i] = UiKit.approach(this.sideHover[i], p == this.page ? 1.0F : over ? 0.5F : 0.0F, dt * 12.0F);
			float k = this.sideHover[i];
			if (k > 0.01F) {
				UiKit.round(g, UiKit.FILL, x, y, w, 22, 7, UiKit.argb(0.12F * k + (p == this.page ? 0.06F : 0.0F), p.accent()));
			}
			if (p == this.page) {
				UiKit.round(g, UiKit.FILL, x - 4, y + 5, 3, 12, 1, UiKit.argb(1.0F, p.accent()));
			}
			g.renderItem(p.icon(), x + 4, y + 3);
			int ink = p == this.page ? TEXT : UiKit.lerpColor(MUTED, BODY, k * 2.0F);
			g.drawString(this.font, p.title(), x + 26, y + 7, UiKit.argb(1.0F, ink), false);
		}
		// the page's header: its title, big, and what it is for
		int cx = this.contentX();
		float e = UiKit.easeOutExpo(this.pageSwitch);
		int slide = Math.round((1.0F - e) * 8.0F);
		g.pose().pushMatrix();
		g.pose().translate(cx + slide, y0 + 13);
		g.pose().scale(1.5F, 1.5F);
		g.drawString(this.font, this.page.title().copy().withStyle(ChatFormatting.BOLD), 0, 0, UiKit.argb(e, TEXT), false);
		g.pose().popMatrix();
		g.drawString(this.font, this.page.description(), cx + slide, y0 + 31, UiKit.argb(e, MUTED), false);
		g.fill(cx, y0 + HEAD + 2, x0 + pw - 16, y0 + HEAD + 3, UiKit.argb(0.5F, 0x262B33));
		UiKit.round(g, UiKit.FILL, cx, y0 + HEAD + 1, Math.round(60 * e), 3, 1, UiKit.argb(1.0F, accent));
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		this.done.setAlpha(0.0F);
		super.render(graphics, mouseX, mouseY, partialTick);
		int x0 = this.panelX();
		int pw = this.panelW();
		int fy = this.panelY() + this.panelH() - FOOTER;
		graphics.fill(this.contentX(), fy, x0 + pw - 16, fy + 1, UiKit.argb(0.5F, 0x262B33));
		String version = FabricLoader.getInstance().getModContainer(BallisticMissiles.MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		// the version, and the alpha note, bottom left under the sidebar
		Component v = Component.literal("v" + version);
		int vy = this.panelY() + this.panelH() - 30;
		UiKit.round(graphics, UiKit.FILL, x0 + 12, vy, this.font.width(v) + 10, 13, 6, UiKit.argb(1.0F, 0x1E242C));
		graphics.drawString(this.font, v, x0 + 17, vy + 3, UiKit.argb(1.0F, BODY), false);
		graphics.drawString(this.font, Component.translatable("options.ballisticmissiles.alpha"), x0 + 12, vy + 16, UiKit.argb(1.0F, 0xC08A30), false);
		// done: a solid light button
		boolean over = this.done.isMouseOver(mouseX, mouseY);
		UiKit.round(graphics, UiKit.FILL, this.done.getX(), this.done.getY(), this.done.getWidth(), this.done.getHeight(), 7,
			UiKit.argb(1.0F, over ? 0xFFFFFF : 0xE8E8EA));
		Component d = this.done.getMessage().copy().withStyle(ChatFormatting.BOLD);
		graphics.drawString(this.font, d, this.done.getX() + (this.done.getWidth() - this.font.width(d)) / 2, this.done.getY() + 6, UiKit.argb(1.0F, 0x111113),
			false);
		// what a command just did, fading
		if (this.status != null && System.currentTimeMillis() < this.statusUntil) {
			float a = (float) Mth.clamp((this.statusUntil - System.currentTimeMillis()) / 600.0, 0.0, 1.0);
			int w = this.font.width(this.status);
			int sx = this.done.getX() - 12 - w - 10;
			UiKit.round(graphics, UiKit.FILL, sx, this.done.getY() + 2, w + 10, 16, 7, UiKit.argb(0.25F * a, 0x3FCF8E));
			graphics.drawString(this.font, this.status, sx + 5, this.done.getY() + 6, UiKit.argb(a, 0x9FF0C8), false);
		}
	}

	/** How far a toggle's knob is over (0 off .. 1 on), easing towards where it is. */
	private float knob(AbstractWidget w, boolean on) {
		float k = this.knobs.getOrDefault(w, on ? 1.0F : 0.0F);
		k = UiKit.approach(k, on ? 1.0F : 0.0F, 0.25F);
		this.knobs.put(w, k);
		return k;
	}

	/**
	 * A control drawn in the panel's style - a dark card with its name on the left and its state on the right: a
	 * switch for on/off, a chip with the value for a choice, a thin track with a knob for a slider, a filled button
	 * for an action. The widget itself still handles the mouse and the keys (and its tooltip), drawn invisibly.
	 */
	private void paint(GuiGraphics g, AbstractWidget w, int mouseX, int mouseY, float partialTick) {
		int x = w.getX();
		int y = w.getY();
		int ww = w.getWidth();
		int h = w.getHeight();
		int accent = this.page.accent();
		boolean over = w.active && mouseX >= x && mouseX < x + ww && mouseY >= y && mouseY < y + h;
		float a = w.active ? 1.0F : 0.4F;
		var font = this.font;
		if (w instanceof Button) {
			UiKit.round(g, UiKit.FILL, x, y, ww, h, 6, UiKit.argb(a, over ? UiKit.lerpColor(CARD_HOVER, accent, 0.35F) : UiKit.lerpColor(CARD, accent, 0.18F)));
			UiKit.round(g, UiKit.EDGE, x, y, ww, h, 6, UiKit.argb(a * (over ? 0.9F : 0.45F), accent));
			Component m = w.getMessage();
			int tw = Math.min(font.width(m), ww - 8);
			g.drawString(font, font.substrByWidth(m, ww - 8).getString(), x + (ww - tw) / 2, y + (h - 8) / 2, UiKit.argb(a, TEXT), false);
			w.setAlpha(0.0F);
			w.render(g, mouseX, mouseY, partialTick);
			return;
		}
		UiKit.round(g, UiKit.FILL, x, y, ww, h, 6, UiKit.argb(a, over ? CARD_HOVER : CARD));
		if (w instanceof IntSlider slider) {
			String text = w.getMessage().getString();
			int cut = text.lastIndexOf(": ");
			String name = cut > 0 ? text.substring(0, cut) : text;
			String value = cut > 0 ? text.substring(cut + 2) : "";
			int vw = font.width(value);
			g.drawString(font, font.plainSubstrByWidth(name, ww - vw - 18), x + 7, y + 3, UiKit.argb(a, BODY), false);
			g.drawString(font, value, x + ww - 7 - vw, y + 3, UiKit.argb(a, accent), false);
			int tx = x + 7;
			int tw = ww - 14;
			int ty = y + h - 5;
			float f = (float) slider.fraction();
			UiKit.round(g, UiKit.FILL, tx, ty, tw, 2, 1, UiKit.argb(a, 0x343B46));
			UiKit.round(g, UiKit.FILL, tx, ty, Math.max(2, Math.round(tw * f)), 2, 1, UiKit.argb(a, accent));
			UiKit.disc(g, tx + tw * f, ty + 1.0F, over ? 3.5F : 3.0F, UiKit.argb(a, 0xFFFFFF));
		} else if (w instanceof CycleButton<?> cycle) {
			Component msg = w.getMessage();
			Component name = msg;
			Component value = Component.empty();
			if (msg.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc && tc.getArgs().length == 2
				&& tc.getArgs()[0] instanceof Component n && tc.getArgs()[1] instanceof Component v) {
				name = n;
				value = v;
			}
			String vs = value.getString();
			boolean onOff = cycle.getValue() instanceof Boolean
				&& (vs.equals(CommonComponents.OPTION_ON.getString()) || vs.equals(CommonComponents.OPTION_OFF.getString()));
			if (onOff) {
				boolean on = (Boolean) cycle.getValue();
				float k = this.knob(w, on);
				int sw = 22;
				int sx = x + ww - sw - 6;
				int sy = y + (h - 12) / 2;
				UiKit.round(g, UiKit.FILL, sx, sy, sw, 12, 6, UiKit.argb(a, UiKit.lerpColor(0x3A414C, accent, k)));
				UiKit.disc(g, sx + 6 + 10 * k, sy + 6, 4.5F, UiKit.argb(a, 0xFFFFFF));
				g.drawString(font, font.substrByWidth(name, ww - sw - 18).getString(), x + 7, y + (h - 8) / 2, UiKit.argb(a, on ? TEXT : BODY), false);
			} else {
				int vw = Math.min(font.width(value), ww / 2);
				int chipX = x + ww - vw - 22;
				UiKit.round(g, UiKit.FILL, chipX, y + 3, vw + 16, h - 6, 5, UiKit.argb(a, UiKit.lerpColor(0x262C35, accent, over ? 0.3F : 0.15F)));
				int ink = value.getStyle().getColor() != null ? value.getStyle().getColor().getValue() : TEXT;
				g.drawString(font, font.substrByWidth(value, ww / 2).getString(), chipX + 8, y + (h - 8) / 2, UiKit.argb(a, ink), false);
				g.drawString(font, "\u2039", chipX + 2, y + (h - 8) / 2, UiKit.argb(a * 0.6F, MUTED), false);
				g.drawString(font, "\u203A", chipX + vw + 10, y + (h - 8) / 2, UiKit.argb(a * 0.6F, MUTED), false);
				g.drawString(font, font.substrByWidth(name, chipX - x - 12).getString(), x + 7, y + (h - 8) / 2, UiKit.argb(a, BODY), false);
			}
		} else {
			w.render(g, mouseX, mouseY, partialTick);
			return;
		}
		// the widget itself, invisibly: hover, focus, tooltip
		w.setAlpha(0.0F);
		w.render(g, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		ModConfig.save();
		this.minecraft.setScreen(this.parent);
	}

	// ---------------------------------------------------------------- the list

	/** The page: rows of widgets, headers and notes, scrolling. */
	private final class Rows extends ContainerObjectSelectionList<Rows.Entry> {
		Rows(Minecraft minecraft, int width, int height, int y) {
			super(minecraft, width, height, y, 24);
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
				UiKit.round(graphics, UiKit.FILL, x, this.getY(), 2, this.getHeight(), 1, UiKit.argb(0.5F, 0x262B33));
				UiKit.round(graphics, UiKit.FILL, x, this.scrollBarY(), 2, this.scrollerHeight(), 1, UiKit.argb(0.8F, SettingsScreen.this.page.accent()));
			}
		}

		void clear() {
			this.clearEntries();
		}

		void header(String key, int colour) {
			this.addEntry(new Header(Component.translatable(key), colour), this.children().isEmpty() ? 18 : 26);
		}

		void note(String key) {
			var lines = SettingsScreen.this.font.split(Component.translatable(key), SettingsScreen.this.rowW - 16);
			this.addEntry(new Note(lines), lines.size() * 10 + 12);
		}

		void text(Component text) {
			this.addEntry(new Note(List.of(text.getVisualOrderText())), 14);
		}

		void row(AbstractWidget... widgets) {
			this.addEntry(new Widgets(List.of(widgets), 4));
		}

		void grid(List<AbstractWidget> widgets) {
			this.addEntry(new Widgets(widgets, ITEM - 20), ITEM);
		}

		abstract static class Entry extends ContainerObjectSelectionList.Entry<Entry> {
		}

		/** Widgets side by side, sharing the row (fixed-width ones, like the items, keep their size). */
		final class Widgets extends Entry {
			private final List<AbstractWidget> widgets;
			private final int gap;

			Widgets(List<AbstractWidget> widgets, int gap) {
				this.widgets = widgets;
				this.gap = gap;
			}

			@Override
			public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int x = this.getX();
				boolean stretch = !(this.widgets.get(0) instanceof ItemButton);
				int w = stretch ? (SettingsScreen.this.rowW - this.gap * (this.widgets.size() - 1)) / this.widgets.size() : 0;
				for (AbstractWidget widget : this.widgets) {
					if (stretch) {
						widget.setWidth(w);
					}
					widget.setPosition(x, this.getY());
					SettingsScreen.this.paint(graphics, widget, mouseX, mouseY, partialTick);
					x += widget.getWidth() + this.gap;
				}
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return this.widgets;
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return this.widgets;
			}
		}

		/** A section title, a line either side of it. */
		final class Header extends Entry {
			private final Component text;
			private final int colour;

			Header(Component text, int colour) {
				this.text = text;
				this.colour = colour;
			}

			@Override
			public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				// a section: a small dot in the page's colour, the title in capitals, a hairline running on
				var font = SettingsScreen.this.font;
				int y = this.getY() + this.getHeight() - 14;
				int colour = SettingsScreen.this.page.accent();
				Component title = Component.literal(this.text.getString().toUpperCase(Locale.ROOT)).withStyle(ChatFormatting.BOLD);
				UiKit.disc(graphics, this.getX() + 3, y + 4, 2.5F, UiKit.argb(1.0F, colour));
				graphics.drawString(font, title, this.getX() + 10, y, UiKit.argb(1.0F, UiKit.lerpColor(colour, 0xFFFFFF, 0.25F)), false);
				int lx = this.getX() + 16 + font.width(title);
				graphics.fill(lx, y + 4, this.getX() + SettingsScreen.this.rowW, y + 5, UiKit.argb(0.5F, 0x2A3039));
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

		/** A line or two of explanation. */
		final class Note extends Entry {
			private final List<net.minecraft.util.FormattedCharSequence> lines;

			Note(List<net.minecraft.util.FormattedCharSequence> lines) {
				this.lines = lines;
			}

			@Override
			public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int h = this.lines.size() * 10 + 6;
				UiKit.round(graphics, UiKit.FILL, this.getX(), this.getY(), SettingsScreen.this.rowW, h, 6, UiKit.argb(0.6F, 0x161B22));
				UiKit.round(graphics, UiKit.FILL, this.getX(), this.getY(), 2, h, 1, UiKit.argb(0.8F, SettingsScreen.this.page.accent()));
				int y = this.getY() + 4;
				for (var line : this.lines) {
					graphics.drawString(SettingsScreen.this.font, line, this.getX() + 8, y, UiKit.argb(1.0F, 0xA8ADB5), false);
					y += 10;
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
			UiKit.round(graphics, UiKit.FILL, x, y, 20, 20, 5, lit ? 0xFF2E3640 : 0xFF1A1F27);
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
