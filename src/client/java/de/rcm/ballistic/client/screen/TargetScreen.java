package de.rcm.ballistic.client.screen;

import com.mojang.blaze3d.platform.NativeImage;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.item.LauncherLink;
import de.rcm.ballistic.item.SavedTarget;
import de.rcm.ballistic.item.TargetData;
import de.rcm.ballistic.network.ModNetworking.DesignatorActionPayload;
import de.rcm.ballistic.network.ModNetworking.SetTargetPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;

/**
 * Targeting computer of the designator: pick targets on a map of the loaded terrain (click anywhere,
 * even outside the loaded area), along your line of sight, from the target memory or on another
 * player, and launch linked silos, pads and trucks from here.
 */
public class TargetScreen extends Screen {
	private static final Identifier MAP_ID = BallisticMissiles.id("dynamic/target_map");
	private static final int TEX = 256;
	private static final int[] ZOOMS = {1, 2, 4, 8, 16, 32};
	private static final int TAB_TARGET = 0;
	private static final int TAB_MEMORY = 1;
	private static final int TAB_PLAYERS = 2;
	private static final int TAB_LAUNCH = 3;
	private static final int LIMIT = 29_999_000;

	private final InteractionHand hand;
	private int tab = TAB_TARGET;
	private int page;

	private EditBox xBox;
	private EditBox yBox;
	private EditBox zBox;
	private EditBox nameBox;
	private Component status = Component.empty();
	private int statusColor = 0xFF9AA0A6;

	// map
	private DynamicTexture texture;
	private double mapX;
	private double mapZ;
	private int zoom = 1;
	private boolean mapDirty = true;
	private int rebuildCooldown;
	private boolean dragging;
	private double dragDistance;
	private int mapLeft;
	private int mapTop;
	private int mapSize;

	// launch tab
	private int selectedMask = -1;
	private long confirmUntil;
	private int playerIndex;
	private int knownLinks = -1;
	private int knownSaved = -1;

	public TargetScreen(InteractionHand hand) {
		super(Component.translatable("screen.ballisticmissiles.target"));
		this.hand = hand;
	}

	private ItemStack stack() {
		return this.minecraft.player.getItemInHand(this.hand);
	}

	private List<SavedTarget> saved() {
		return this.stack().getOrDefault(ModRegistry.SAVED_TARGETS, List.of());
	}

	private List<LauncherLink> links() {
		return this.stack().getOrDefault(ModRegistry.LINKS, List.of());
	}

	// ------------------------------------------------------------------ layout

	@Override
	protected void init() {
		if (this.texture == null) {
			this.texture = new DynamicTexture(() -> "ballisticmissiles target map", TEX, TEX, false);
			this.minecraft.getTextureManager().register(MAP_ID, this.texture);
			TargetData current = this.stack().get(ModRegistry.TARGET);
			BlockPos p = this.minecraft.player.blockPosition();
			this.mapX = p.getX();
			this.mapZ = p.getZ();
			if (current != null && Math.hypot(current.pos().getX() - p.getX(), current.pos().getZ() - p.getZ()) < 600) {
				this.mapX = (p.getX() + current.pos().getX()) / 2.0;
				this.mapZ = (p.getZ() + current.pos().getZ()) / 2.0;
			}
		}
		String keepX = this.xBox != null ? this.xBox.getValue() : null;
		String keepY = this.yBox != null ? this.yBox.getValue() : null;
		String keepZ = this.zBox != null ? this.zBox.getValue() : null;
		String keepName = this.nameBox != null ? this.nameBox.getValue() : "";
		this.clearWidgets();

		this.mapSize = Mth.clamp(Math.min(this.height - 48, (int) (this.width * 0.52)), 120, 384);
		this.mapLeft = 10;
		this.mapTop = 30;
		int px = this.mapLeft + this.mapSize + 10;
		int pw = Math.max(170, this.width - px - 10);
		int y = this.mapTop;

		// tabs
		String[] tabs = {"screen.ballisticmissiles.tab_target", "screen.ballisticmissiles.tab_memory", "screen.ballisticmissiles.tab_players",
			"screen.ballisticmissiles.tab_launch"};
		int tw = pw / 4;
		for (int i = 0; i < tabs.length; i++) {
			int index = i;
			Button b = Button.builder(Component.translatable(tabs[i]), btn -> {
					this.tab = index;
					this.page = 0;
					this.rebuildWidgets();
				})
				.bounds(px + i * tw, y, tw - 2, 18)
				.build();
			b.active = this.tab != i;
			this.addRenderableWidget(b);
		}
		y += 26;

		// coordinates are always there
		int bw = (pw - 70) / 3;
		this.xBox = this.coordBox(px, y + 10, bw, "X");
		this.yBox = this.coordBox(px + bw + 4, y + 10, bw, "Y");
		this.zBox = this.coordBox(px + (bw + 4) * 2, y + 10, bw, "Z");
		this.yBox.setHint(Component.translatable("screen.ballisticmissiles.surface"));
		if (keepX != null) {
			this.xBox.setValue(keepX);
			this.yBox.setValue(keepY);
			this.zBox.setValue(keepZ);
		} else {
			TargetData current = this.stack().get(ModRegistry.TARGET);
			if (current != null) {
				this.setCoords(current.pos().getX(), current.surface() ? null : current.pos().getY(), current.pos().getZ());
			}
		}
		this.addRenderableWidget(Button.builder(Component.translatable("screen.ballisticmissiles.confirm"), b -> this.sendTarget(true))
			.bounds(px + pw - 62, y + 9, 62, 20).build());
		y += 36;

		switch (this.tab) {
			case TAB_TARGET -> this.initTargetTab(px, y, pw);
			case TAB_MEMORY -> this.initMemoryTab(px, y, pw, keepName);
			case TAB_PLAYERS -> this.initPlayersTab(px, y, pw);
			default -> this.initLaunchTab(px, y, pw);
		}

		// zoom buttons on the map
		this.addRenderableWidget(Button.builder(Component.literal("+"), b -> this.zoomBy(-1, this.mapX, this.mapZ))
			.bounds(this.mapLeft + this.mapSize - 42, this.mapTop + 4, 18, 18).build());
		this.addRenderableWidget(Button.builder(Component.literal("−"), b -> this.zoomBy(1, this.mapX, this.mapZ))
			.bounds(this.mapLeft + this.mapSize - 22, this.mapTop + 4, 18, 18).build());
		this.addRenderableWidget(Button.builder(Component.literal("⌂"), b -> {
				this.mapX = this.minecraft.player.getX();
				this.mapZ = this.minecraft.player.getZ();
				this.mapDirty = true;
			})
			.bounds(this.mapLeft + this.mapSize - 62, this.mapTop + 4, 18, 18).build());

		this.knownLinks = this.links().size();
		this.knownSaved = this.saved().size();
	}

	private void initTargetTab(int px, int y, int pw) {
		int w = (pw - 8) / 3;
		this.addRenderableWidget(Button.builder(Component.translatable("screen.ballisticmissiles.here"), b -> {
				BlockPos p = this.minecraft.player.blockPosition();
				this.setCoords(p.getX(), null, p.getZ());
			})
			.bounds(px, y, w, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("screen.ballisticmissiles.map_center"), b -> this.setCoords(Mth.floor(this.mapX), null, Mth.floor(this.mapZ)))
			.bounds(px + w + 4, y, w, 20).build());
		Button death = Button.builder(Component.translatable("screen.ballisticmissiles.last_death"), b -> {
				Optional<GlobalPos> d = this.minecraft.player.getLastDeathLocation();
				d.ifPresent(g -> this.setCoords(g.pos().getX(), g.pos().getY(), g.pos().getZ()));
			})
			.bounds(px + (w + 4) * 2, y, w, 20).build();
		death.active = this.minecraft.player.getLastDeathLocation().filter(g -> g.dimension() == this.minecraft.level.dimension()).isPresent();
		this.addRenderableWidget(death);
		y += 34;
		int[] distances = {250, 500, 1000, 2500, 5000};
		int dw = (pw - 4 * 4) / distances.length;
		for (int i = 0; i < distances.length; i++) {
			int dist = distances[i];
			this.addRenderableWidget(Button.builder(Component.literal(dist >= 1000 ? dist / 1000 + "k" : String.valueOf(dist)), b -> this.lookAhead(dist))
				.bounds(px + i * (dw + 4), y + 10, dw, 20).build());
		}
	}

	private void initMemoryTab(int px, int y, int pw, String keepName) {
		this.nameBox = new EditBox(this.font, px, y, pw - 70, 18, Component.translatable("screen.ballisticmissiles.name"));
		this.nameBox.setMaxLength(24);
		this.nameBox.setHint(Component.translatable("screen.ballisticmissiles.name"));
		this.nameBox.setValue(keepName);
		this.addRenderableWidget(this.nameBox);
		this.addRenderableWidget(Button.builder(Component.translatable("screen.ballisticmissiles.save"), b -> {
				if (this.sendTarget(false)) {
					this.action(DesignatorActionPayload.SAVE_TARGET, 0, this.nameBox.getValue());
					this.nameBox.setValue("");
				}
			})
			.bounds(px + pw - 66, y - 1, 66, 20).build());
		y += 24;
		List<SavedTarget> saved = this.saved();
		int rows = this.rows(y);
		int start = this.pageStart(saved.size(), rows);
		for (int i = start; i < Math.min(saved.size(), start + rows); i++) {
			SavedTarget t = saved.get(i);
			int index = i;
			String label = t.name() + "  " + t.pos().getX() + " / " + t.pos().getZ();
			this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
					this.setCoords(t.pos().getX(), t.surface() ? null : t.pos().getY(), t.pos().getZ());
					this.centerMap(t.pos().getX(), t.pos().getZ());
					this.sendTarget(true);
				})
				.bounds(px, y, pw - 24, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("✕"), b -> this.action(DesignatorActionPayload.DELETE_SAVED, index, ""))
				.bounds(px + pw - 20, y, 20, 20).build());
			y += 22;
		}
		if (saved.isEmpty()) {
			this.setStatus(Component.translatable("screen.ballisticmissiles.memory_empty"), 0xFF9AA0A6);
		}
		this.pager(px, y, pw, saved.size(), rows);
	}

	private void initPlayersTab(int px, int y, int pw) {
		List<String> names = this.playerNames();
		if (names.isEmpty()) {
			this.setStatus(Component.translatable("screen.ballisticmissiles.no_players"), 0xFF9AA0A6);
			return;
		}
		this.playerIndex = Math.floorMod(this.playerIndex, names.size());
		this.addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
				this.playerIndex--;
				this.rebuildWidgets();
			})
			.bounds(px, y, 20, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal(names.get(this.playerIndex)), b -> {
			}).bounds(px + 22, y, pw - 44, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
				this.playerIndex++;
				this.rebuildWidgets();
			})
			.bounds(px + pw - 20, y, 20, 20).build());
		y += 26;
		String name = names.get(this.playerIndex);
		this.addRenderableWidget(Button.builder(Component.translatable("screen.ballisticmissiles.target_player"), b -> {
				this.action(DesignatorActionPayload.TARGET_PLAYER, 0, name);
				this.setStatus(Component.translatable("screen.ballisticmissiles.player_targeted", name), 0xFFFF6060);
			})
			.bounds(px, y, pw, 20).build());
	}

	private void initLaunchTab(int px, int y, int pw) {
		List<LauncherLink> links = this.links();
		if (links.isEmpty()) {
			this.setStatus(Component.translatable("screen.ballisticmissiles.no_links"), 0xFF9AA0A6);
			return;
		}
		int rows = this.rows(y + 26);
		int start = this.pageStart(links.size(), rows);
		Vec3 me = this.minecraft.player.position();
		for (int i = start; i < Math.min(links.size(), start + rows); i++) {
			LauncherLink link = links.get(i);
			int index = i;
			boolean on = (this.selectedMask & 1 << i) != 0;
			int dist = (int) Math.hypot(link.pos().getX() - me.x, link.pos().getZ() - me.z);
			Component label = Component.literal(on ? "■ " : "□ ").append(link.describe()).append(Component.literal("  " + dist + "m"));
			this.addRenderableWidget(Button.builder(label, b -> {
					this.selectedMask ^= 1 << index;
					this.confirmUntil = 0;
					this.rebuildWidgets();
				})
				.bounds(px, y, pw - 24, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("✕"), b -> this.action(DesignatorActionPayload.UNLINK, index, ""))
				.bounds(px + pw - 20, y, 20, 20).build());
			y += 22;
		}
		y = this.pager(px, y, pw, links.size(), rows);
		int mask = this.selectedMask & ((1 << links.size()) - 1);
		int count = Integer.bitCount(mask);
		boolean confirming = System.currentTimeMillis() < this.confirmUntil;
		Button fire = Button.builder(
				Component.translatable(confirming ? "screen.ballisticmissiles.fire_confirm" : "screen.ballisticmissiles.fire", count), b -> {
					if (System.currentTimeMillis() < this.confirmUntil) {
						if (this.sendTarget(false)) {
							this.action(DesignatorActionPayload.FIRE, mask, "");
							this.setStatus(Component.translatable("screen.ballisticmissiles.fired", count), 0xFFFF5050);
						}
						this.confirmUntil = 0;
					} else {
						this.confirmUntil = System.currentTimeMillis() + 3000;
					}
					this.rebuildWidgets();
				}
			)
			.bounds(px, y + 2, pw / 2 + 20, 20)
			.build();
		fire.active = count > 0;
		this.addRenderableWidget(fire);
		Button abort = Button.builder(Component.translatable("screen.ballisticmissiles.abort"), b -> this.action(DesignatorActionPayload.ABORT, mask, ""))
			.bounds(px + pw / 2 + 24, y + 2, pw / 2 - 24, 20)
			.build();
		abort.active = count > 0;
		this.addRenderableWidget(abort);
	}

	private int rows(int y) {
		return Math.max(1, (this.height - 40 - y - 22) / 22);
	}

	private int pageStart(int size, int rows) {
		int pages = Math.max(1, (size + rows - 1) / rows);
		this.page = Mth.clamp(this.page, 0, pages - 1);
		return this.page * rows;
	}

	private int pager(int px, int y, int pw, int size, int rows) {
		if (size <= rows) {
			return y;
		}
		this.addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
				this.page--;
				this.rebuildWidgets();
			})
			.bounds(px, y, 40, 18).build());
		this.addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
				this.page++;
				this.rebuildWidgets();
			})
			.bounds(px + pw - 40, y, 40, 18).build());
		return y + 22;
	}

	private List<String> playerNames() {
		List<String> names = new ArrayList<>();
		if (this.minecraft.getConnection() != null) {
			for (PlayerInfo info : this.minecraft.getConnection().getOnlinePlayers()) {
				String n = info.getProfile().name();
				if (!n.equals(this.minecraft.player.getGameProfile().name())) {
					names.add(n);
				}
			}
		}
		names.sort(String::compareToIgnoreCase);
		return names;
	}

	private EditBox coordBox(int x, int y, int w, String name) {
		EditBox box = new EditBox(this.font, x, y, w, 18, Component.literal(name));
		box.setMaxLength(9);
		box.setFilter(s -> s.isEmpty() || s.matches("-?\\d*"));
		this.addRenderableWidget(box);
		return box;
	}

	// ------------------------------------------------------------------ actions

	private void setCoords(int x, Integer y, int z) {
		this.xBox.setValue(String.valueOf(Mth.clamp(x, -LIMIT, LIMIT)));
		this.yBox.setValue(y == null ? "" : String.valueOf(y));
		this.zBox.setValue(String.valueOf(Mth.clamp(z, -LIMIT, LIMIT)));
	}

	private void lookAhead(int distance) {
		Vec3 look = this.minecraft.player.getViewVector(1.0F);
		Vec3 flat = new Vec3(look.x, 0, look.z);
		if (flat.lengthSqr() < 1.0E-4) {
			return;
		}
		flat = flat.normalize().scale(distance);
		Vec3 p = this.minecraft.player.position().add(flat);
		this.setCoords(Mth.floor(p.x), null, Mth.floor(p.z));
		this.centerMap(p.x, p.z);
		this.sendTarget(true);
	}

	private void centerMap(double x, double z) {
		double half = TEX * ZOOMS[this.zoom] / 2.0;
		if (Math.abs(x - this.mapX) > half * 0.8 || Math.abs(z - this.mapZ) > half * 0.8) {
			this.mapX = x;
			this.mapZ = z;
			this.mapDirty = true;
		}
	}

	/** Sends the coordinates in the boxes as the designator's target. */
	private boolean sendTarget(boolean close) {
		try {
			int x = Integer.parseInt(this.xBox.getValue().trim());
			int z = Integer.parseInt(this.zBox.getValue().trim());
			String yText = this.yBox.getValue().trim();
			boolean surface = yText.isEmpty() || yText.equals("-");
			int y = surface ? 64 : Integer.parseInt(yText);
			ClientPlayNetworking.send(new SetTargetPayload(this.hand == InteractionHand.OFF_HAND, new BlockPos(x, y, z), surface));
			this.setStatus(Component.translatable("screen.ballisticmissiles.target_sent", x, surface ? "~" : String.valueOf(y), z), 0xFFFF7070);
			return true;
		} catch (NumberFormatException e) {
			this.setStatus(Component.translatable("screen.ballisticmissiles.invalid"), 0xFFFF5555);
			return false;
		}
	}

	private void action(int action, int index, String text) {
		ClientPlayNetworking.send(new DesignatorActionPayload(this.hand == InteractionHand.OFF_HAND, action, index, text));
	}

	private void setStatus(Component text, int color) {
		this.status = text;
		this.statusColor = color;
	}

	@Override
	public void tick() {
		super.tick();
		if (!this.stack().is(ModRegistry.TARGET_DESIGNATOR)) {
			this.onClose();
			return;
		}
		// the server answered an action: refresh the lists
		if (this.links().size() != this.knownLinks || this.saved().size() != this.knownSaved) {
			this.rebuildWidgets();
		}
		if (this.confirmUntil != 0 && System.currentTimeMillis() > this.confirmUntil) {
			this.confirmUntil = 0;
			this.rebuildWidgets();
		}
	}

	@Override
	public void removed() {
		this.minecraft.getTextureManager().release(MAP_ID);
		this.texture = null;
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------ map

	private boolean overMap(double mx, double my) {
		return mx >= this.mapLeft && my >= this.mapTop && mx < this.mapLeft + this.mapSize && my < this.mapTop + this.mapSize;
	}

	private double worldX(double mx) {
		return this.mapX + (mx - this.mapLeft - this.mapSize / 2.0) / this.mapSize * TEX * ZOOMS[this.zoom];
	}

	private double worldZ(double my) {
		return this.mapZ + (my - this.mapTop - this.mapSize / 2.0) / this.mapSize * TEX * ZOOMS[this.zoom];
	}

	private float screenX(double wx) {
		return (float) (this.mapLeft + this.mapSize / 2.0 + (wx - this.mapX) / (TEX * ZOOMS[this.zoom]) * this.mapSize);
	}

	private float screenY(double wz) {
		return (float) (this.mapTop + this.mapSize / 2.0 + (wz - this.mapZ) / (TEX * ZOOMS[this.zoom]) * this.mapSize);
	}

	private void zoomBy(int step, double keepX, double keepZ) {
		int z = Mth.clamp(this.zoom + step, 0, ZOOMS.length - 1);
		if (z == this.zoom) {
			return;
		}
		double f = (double) ZOOMS[z] / ZOOMS[this.zoom];
		this.mapX = keepX + (this.mapX - keepX) * f;
		this.mapZ = keepZ + (this.mapZ - keepZ) * f;
		this.zoom = z;
		this.mapDirty = true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (this.overMap(event.x(), event.y())) {
			this.dragging = true;
			this.dragDistance = 0;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (this.dragging) {
			this.dragDistance += Math.abs(dx) + Math.abs(dy);
			if (this.dragDistance > 3) {
				double k = (double) TEX * ZOOMS[this.zoom] / this.mapSize;
				this.mapX -= dx * k;
				this.mapZ -= dy * k;
				this.mapX = Mth.clamp(this.mapX, -LIMIT, LIMIT);
				this.mapZ = Mth.clamp(this.mapZ, -LIMIT, LIMIT);
				this.mapDirty = true;
			}
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (this.dragging) {
			this.dragging = false;
			if (this.dragDistance <= 3 && this.overMap(event.x(), event.y())) {
				// click on the map: that's the new target
				this.setCoords(Mth.floor(this.worldX(event.x())), null, Mth.floor(this.worldZ(event.y())));
				this.sendTarget(false);
			}
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if (this.overMap(mx, my) && scrollY != 0) {
			this.zoomBy(scrollY > 0 ? -1 : 1, this.worldX(mx), this.worldZ(my));
			return true;
		}
		return super.mouseScrolled(mx, my, scrollX, scrollY);
	}

	/** Paints the loaded terrain the way a map item does; unloaded chunks stay a dark grid. */
	private void rebuildMap() {
		NativeImage pixels = this.texture.getPixels();
		ClientLevel level = this.minecraft.level;
		if (pixels == null || level == null) {
			return;
		}
		int step = ZOOMS[this.zoom];
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int x0 = Mth.floor(this.mapX) - TEX / 2 * step;
		int z0 = Mth.floor(this.mapZ) - TEX / 2 * step;
		int[] prevHeights = new int[TEX];
		for (int py = -1; py < TEX; py++) {
			int wz = z0 + py * step;
			for (int px = 0; px < TEX; px++) {
				int wx = x0 + px * step;
				int color;
				int h = Integer.MIN_VALUE;
				if (level.hasChunk(wx >> 4, wz >> 4)) {
					h = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
					m.set(wx, h - 1, wz);
					BlockState state = level.getBlockState(m);
					MapColor mc = state.getMapColor(level, m);
					if (mc == MapColor.NONE) {
						m.move(0, -1, 0);
						mc = level.getBlockState(m).getMapColor(level, m);
					}
					int prev = prevHeights[px];
					MapColor.Brightness b = prev == Integer.MIN_VALUE || h == prev ? MapColor.Brightness.NORMAL : h > prev ? MapColor.Brightness.HIGH : MapColor.Brightness.LOW;
					if (mc == MapColor.WATER) {
						b = MapColor.Brightness.NORMAL;
					}
					color = mc == MapColor.NONE ? 0xFF202020 : mc.calculateARGBColor(b);
				} else {
					boolean odd = ((wx >> 4) + (wz >> 4) & 1) == 0;
					color = odd ? 0xFF1B1E21 : 0xFF202428;
				}
				prevHeights[px] = h;
				if (py >= 0) {
					pixels.setPixel(px, py, color);
				}
			}
		}
		this.texture.upload();
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		if (this.mapDirty && this.rebuildCooldown-- <= 0 && this.texture != null) {
			this.rebuildMap();
			this.mapDirty = false;
			this.rebuildCooldown = 2;
		}
		int px = this.mapLeft + this.mapSize + 10;
		int pw = Math.max(170, this.width - px - 10);
		g.fill(4, 4, this.width - 4, this.height - 4, 0xE80E1012);
		for (int i = 4; i < this.width - 4; i += 12) {
			g.fill(i, 4, Math.min(i + 6, this.width - 4), 7, 0xFFE8B010);
		}
		g.drawString(this.font, this.title, 10, 12, 0xFFFF4040);

		// map
		g.fill(this.mapLeft - 1, this.mapTop - 1, this.mapLeft + this.mapSize + 1, this.mapTop + this.mapSize + 1, 0xFF606A70);
		if (this.texture != null) {
			g.blit(RenderPipelines.GUI_TEXTURED, MAP_ID, this.mapLeft, this.mapTop, 0, 0, this.mapSize, this.mapSize, TEX, TEX, TEX, TEX);
		}
		g.enableScissor(this.mapLeft, this.mapTop, this.mapLeft + this.mapSize, this.mapTop + this.mapSize);
		this.drawMapOverlay(g);
		g.disableScissor();
		if (this.overMap(mouseX, mouseY)) {
			int wx = Mth.floor(this.worldX(mouseX));
			int wz = Mth.floor(this.worldZ(mouseY));
			int dist = (int) Math.hypot(wx - this.minecraft.player.getX(), wz - this.minecraft.player.getZ());
			g.drawString(this.font, String.format("X %d  Z %d  (%d m)", wx, wz, dist), this.mapLeft + 4, this.mapTop + this.mapSize - 11, 0xFFFFFFFF);
			g.fill(mouseX - 4, mouseY, mouseX + 5, mouseY + 1, 0xC0FFFFFF);
			g.fill(mouseX, mouseY - 4, mouseX + 1, mouseY + 5, 0xC0FFFFFF);
		}
		int span = TEX * ZOOMS[this.zoom];
		g.drawString(this.font, Component.translatable("screen.ballisticmissiles.map_scale", span), this.mapLeft + 4, this.mapTop + 5, 0xFFE0E0E0);

		super.render(g, mouseX, mouseY, partialTick);

		g.drawString(this.font, "X", px, this.mapTop + 26, 0xFFB0B0B0);
		int bw = (pw - 70) / 3;
		g.drawString(this.font, "Y", px + bw + 4, this.mapTop + 26, 0xFFB0B0B0);
		g.drawString(this.font, "Z", px + (bw + 4) * 2, this.mapTop + 26, 0xFFB0B0B0);
		if (this.tab == TAB_TARGET) {
			g.drawString(this.font, Component.translatable("screen.ballisticmissiles.look_ahead"), px, this.mapTop + 26 + 36 + 34, 0xFFB0B0B0);
		}
		if (this.tab == TAB_LAUNCH && !this.links().isEmpty()) {
			g.drawString(this.font, Component.translatable("screen.ballisticmissiles.launch_hint"), px, this.height - 30, 0xFF7A8088);
		}
		TargetData current = this.stack().get(ModRegistry.TARGET);
		Component cur = current == null
			? Component.translatable("tooltip.ballisticmissiles.no_target")
			: Component.translatable("tooltip.ballisticmissiles.target", current.describe());
		g.drawString(this.font, cur, px, this.height - 20, current == null ? 0xFF808080 : 0xFFFF6060);
		if (!this.status.getString().isEmpty()) {
			g.drawString(this.font, this.status, 10, this.height - 14, this.statusColor);
		}
	}

	private void drawMapOverlay(GuiGraphics g) {
		// linked launchers
		for (LauncherLink link : this.links()) {
			int x = (int) this.screenX(link.pos().getX() + 0.5);
			int y = (int) this.screenY(link.pos().getZ() + 0.5);
			g.fill(x - 3, y - 3, x + 3, y + 3, 0xFF000000);
			g.fill(x - 2, y - 2, x + 2, y + 2, 0xFF40E0FF);
		}
		// target memory
		for (SavedTarget t : this.saved()) {
			int x = (int) this.screenX(t.pos().getX() + 0.5);
			int y = (int) this.screenY(t.pos().getZ() + 0.5);
			g.fill(x - 2, y - 2, x + 2, y + 2, 0xFFFFE040);
			g.drawString(this.font, t.name(), x + 4, y - 4, 0xFFFFE040);
		}
		// other players
		if (this.minecraft.level != null) {
			for (var p : this.minecraft.level.players()) {
				if (p == this.minecraft.player) {
					continue;
				}
				int x = (int) this.screenX(p.getX());
				int y = (int) this.screenY(p.getZ());
				g.fill(x - 2, y - 2, x + 2, y + 2, 0xFFFF9030);
			}
		}
		// me, with a view direction tick
		double mx = this.minecraft.player.getX();
		double mz = this.minecraft.player.getZ();
		int x = (int) this.screenX(mx);
		int y = (int) this.screenY(mz);
		Vec3 look = this.minecraft.player.getViewVector(1.0F);
		for (int i = 0; i < 9; i++) {
			int lx = x + (int) Math.round(look.x / Math.max(1.0E-3, Math.hypot(look.x, look.z)) * i);
			int ly = y + (int) Math.round(look.z / Math.max(1.0E-3, Math.hypot(look.x, look.z)) * i);
			g.fill(lx, ly, lx + 1, ly + 1, 0xFFFFFFFF);
		}
		g.fill(x - 2, y - 2, x + 3, y + 3, 0xFF000000);
		g.fill(x - 1, y - 1, x + 2, y + 2, 0xFFFFFFFF);
		// current target and its distance line
		TargetData current = this.stack().get(ModRegistry.TARGET);
		if (current != null) {
			int tx = (int) this.screenX(current.pos().getX() + 0.5);
			int ty = (int) this.screenY(current.pos().getZ() + 0.5);
			int steps = Math.max(Math.abs(tx - x), Math.abs(ty - y));
			for (int i = 0; i < steps; i += 3) {
				float f = (float) i / steps;
				int lx = Math.round(Mth.lerp(f, x, tx));
				int ly = Math.round(Mth.lerp(f, y, ty));
				g.fill(lx, ly, lx + 1, ly + 1, 0x90FF4040);
			}
			g.fill(tx - 6, ty, tx + 7, ty + 1, 0xFFFF3030);
			g.fill(tx, ty - 6, tx + 1, ty + 7, 0xFFFF3030);
			for (int i = 0; i < 24; i++) {
				double a = Mth.TWO_PI * i / 24;
				int cx = tx + (int) Math.round(Math.cos(a) * 4);
				int cy = ty + (int) Math.round(Math.sin(a) * 4);
				g.fill(cx, cy, cx + 1, cy + 1, 0xFFFF3030);
			}
		}
	}
}
