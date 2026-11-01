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
	private static final int ROW = 310;
	private static final int FOOTER = 36;
	private static final int SEARCH = 28;
	private static final int ITEM = 22;
	private static Page lastPage = Page.GRAPHICS;
	/** The soldier spawner's choices, kept while the game runs. */
	private static int soldierCount = 3;
	private static boolean soldierFriendly;
	private static String lastSearch = "";
	private static int craterRadius = 64;
	/** Who the gore tests are done on: "@s" (yourself), "@a" (everyone) or a player's name. */
	private static String goreTarget = "@s";

	private final Screen parent;
	private final TabManager tabs = new TabManager(this::addRenderableWidget, this::removeWidget, this::selected, t -> {
	});
	private TabNavigationBar bar;
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
	}

	private record PageTab(Page page) implements Tab {
		@Override
		public Component getTabTitle() {
			return this.page.title();
		}

		@Override
		public Component getTabExtraNarration() {
			return Component.empty();
		}

		@Override
		public void visitChildren(Consumer<AbstractWidget> consumer) {
		}

		@Override
		public void doLayout(ScreenRectangle area) {
		}
	}

	@Override
	protected void init() {
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
		this.done = this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).width(200).build());
		this.bar = TabNavigationBar.builder(this.tabs, this.width)
			.addTabs(Arrays.stream(Page.values()).map(PageTab::new).toArray(Tab[]::new))
			.build();
		this.addRenderableWidget(this.bar);
		this.bar.selectTab(this.page.ordinal(), false);
		// built here too: after a resize the same tab is selected again, which selects nothing new
		this.build();
		this.repositionElements();
	}

	private void selected(Tab tab) {
		if (tab instanceof PageTab(Page p)) {
			this.page = p;
			lastPage = p;
			if (this.list != null && this.bar != null) {
				this.build();
				this.repositionElements();
			}
		}
	}

	@Override
	public void repositionElements() {
		if (this.bar == null) {
			return;
		}
		this.bar.setWidth(this.width);
		this.bar.arrangeElements();
		int top = this.bar.getRectangle().bottom();
		boolean spawn = this.page == Page.SPAWN;
		this.search.visible = spawn;
		this.search.setPosition(this.width / 2 - 100, top + 6);
		int listTop = top + (spawn ? SEARCH : 0);
		this.list.updateSizeAndPosition(this.width, this.height - FOOTER - listTop, listTop);
		this.done.setPosition(this.width / 2 - 100, this.height - FOOTER + 8);
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
		int perRow = ROW / ITEM;
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
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.blit(RenderPipelines.GUI_TEXTURED, this.minecraft.level == null ? Screen.FOOTER_SEPARATOR : Screen.INWORLD_FOOTER_SEPARATOR, 0,
			this.height - FOOTER - 2, 0.0F, 0.0F, this.width, 2, 32, 2);
		String version = FabricLoader.getInstance().getModContainer(BallisticMissiles.MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		graphics.drawString(this.font, Component.literal("Ballistic Missiles " + version).withStyle(ChatFormatting.GRAY), 8, this.height - FOOTER + 9, 0xFFFFFFFF);
		graphics.drawString(this.font, Component.translatable("options.ballisticmissiles.alpha").withStyle(ChatFormatting.GOLD), 8, this.height - FOOTER + 20,
			0xFFFFFFFF);
		if (this.status != null && System.currentTimeMillis() < this.statusUntil) {
			int w = this.font.width(this.status);
			int x = this.width - 8 - w;
			int alpha = (int) Mth.clamp((this.statusUntil - System.currentTimeMillis()) / 600.0 * 255.0, 0.0, 255.0);
			graphics.drawString(this.font, this.status, Math.max(this.width / 2 + 106, x), this.height - FOOTER + 14, (alpha << 24) | 0x80FF80);
		}
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
			return ROW;
		}

		void clear() {
			this.clearEntries();
		}

		void header(String key, int colour) {
			this.addEntry(new Header(Component.translatable(key), colour), this.children().isEmpty() ? 18 : 26);
		}

		void note(String key) {
			var lines = SettingsScreen.this.font.split(Component.translatable(key).withStyle(ChatFormatting.GRAY), ROW);
			this.addEntry(new Note(lines), lines.size() * 10 + 4);
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
				int w = stretch ? (ROW - this.gap * (this.widgets.size() - 1)) / this.widgets.size() : 0;
				for (AbstractWidget widget : this.widgets) {
					if (stretch) {
						widget.setWidth(w);
					}
					widget.setPosition(x, this.getY());
					widget.render(graphics, mouseX, mouseY, partialTick);
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
				var font = SettingsScreen.this.font;
				int y = this.getY() + this.getHeight() - 13;
				int mid = this.getX() + ROW / 2;
				int half = font.width(this.text) / 2;
				graphics.drawCenteredString(font, this.text, mid, y, this.colour);
				int line = y + 4;
				int dim = (this.colour & 0x00FFFFFF) | 0x50000000;
				graphics.fill(this.getX(), line, mid - half - 6, line + 1, dim);
				graphics.fill(mid + half + 6, line, this.getX() + ROW, line + 1, dim);
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
				int y = this.getY() + 1;
				for (var line : this.lines) {
					graphics.drawString(SettingsScreen.this.font, line, this.getX(), y, 0xFFA0A0A0);
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
			graphics.fill(x, y, x + 20, y + 20, lit ? 0x60FFFFFF : 0x40000000);
			if (lit) {
				graphics.renderOutline(x, y, 20, 20, 0xFFFFFFFF);
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
