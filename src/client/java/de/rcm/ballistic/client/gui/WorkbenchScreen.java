package de.rcm.ballistic.client.gui;

import de.rcm.ballistic.gun.Workbench;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

/**
 * The weapons workbench's menu: three drawers (attachments, ammunition, grenades), each a list of what can be
 * made with what it takes - green when you carry enough, red when not - and a button to make it. While a job is
 * on the bench its row fills with a progress bar.
 */
public final class WorkbenchScreen extends Screen {
	private static final int W = 360;
	private static final int H = 280;
	private static final int ROW = 34;
	private static final String[] TABS = {"attachments", "ammo", "grenades"};
	private final BlockPos pos;
	private int tab;
	private final List<Integer> shown = new ArrayList<>();
	private final List<Button> makes = new ArrayList<>();
	/** The row being made and since when (ms), for the progress bar. */
	private int busy = -1;
	private long busySince;

	public WorkbenchScreen(BlockPos pos) {
		super(Component.translatable("screen.ballisticmissiles.workbench.title"));
		this.pos = pos;
	}

	private int left() {
		return (this.width - W) / 2;
	}

	private int top() {
		return (this.height - H) / 2;
	}

	@Override
	protected void init() {
		this.shown.clear();
		this.makes.clear();
		List<Workbench.Recipe> all = Workbench.recipes();
		for (int i = 0; i < all.size(); i++) {
			if (all.get(i).category() == this.tab) {
				this.shown.add(i);
			}
		}
		int x = this.left();
		int y = this.top();
		for (int t = 0; t < TABS.length; t++) {
			int k = t;
			Button b = Button.builder(Component.translatable("screen.ballisticmissiles.workbench.tab." + TABS[t]), btn -> {
				this.tab = k;
				this.rebuildWidgets();
			}).bounds(x + 10 + t * 114, y + 26, 110, 18).build();
			b.active = t != this.tab;
			this.addRenderableWidget(b);
		}
		for (int r = 0; r < this.shown.size(); r++) {
			int index = this.shown.get(r);
			Button b = Button.builder(Component.translatable("screen.ballisticmissiles.workbench.make"), btn -> this.make(index))
				.bounds(x + W - 84, y + 54 + r * ROW + 7, 72, 18).build();
			this.makes.add(b);
			this.addRenderableWidget(b);
		}
	}

	private void make(int index) {
		if (this.busy >= 0) {
			return;
		}
		ClientPlayNetworking.send(new Workbench.MakePayload(this.pos, index));
		this.busy = index;
		this.busySince = Util.getMillis();
	}

	@Override
	public void tick() {
		if (this.minecraft == null || this.minecraft.player == null) {
			return;
		}
		if (this.minecraft.player.distanceToSqr(this.pos.getCenter()) > 49.0) {
			this.onClose();
			return;
		}
		if (this.busy >= 0 && Util.getMillis() - this.busySince > 1700L) {
			this.busy = -1;
		}
		List<Workbench.Recipe> all = Workbench.recipes();
		for (int r = 0; r < this.makes.size(); r++) {
			this.makes.get(r).active = this.busy < 0 && Workbench.canMake(this.minecraft.player, all.get(this.shown.get(r)));
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		int x = this.left();
		int y = this.top();
		// the bench's steel drawer: a dark panel, a stripe of olive at the top
		UiKit.round(g, UiKit.FILL, x - 2, y - 2, W + 4, H + 4, 8, 0xC0000000);
		UiKit.round(g, UiKit.FILL, x, y, W, H, 7, 0xF01A1D1A);
		UiKit.round(g, UiKit.FILL, x, y, W, 22, 7, 0xFF3C4630);
		g.fill(x, y + 14, x + W, y + 22, 0xFF3C4630);
		g.drawString(this.font, this.title, x + 10, y + 7, 0xFFE8E4D0, false);
		super.render(g, mouseX, mouseY, partialTick);
		List<Workbench.Recipe> all = Workbench.recipes();
		var player = this.minecraft.player;
		for (int r = 0; r < this.shown.size(); r++) {
			int index = this.shown.get(r);
			Workbench.Recipe recipe = all.get(index);
			int ry = y + 54 + r * ROW;
			boolean can = player != null && Workbench.canMake(player, recipe);
			UiKit.round(g, UiKit.FILL, x + 8, ry, W - 16, ROW - 3, 4, can ? 0x40FFFFFF : 0x20FFFFFF);
			if (this.busy == index) {
				float f = Mth.clamp((Util.getMillis() - this.busySince) / 1600.0F, 0.0F, 1.0F);
				g.fill(x + 10, ry + ROW - 7, x + 10 + (int) ((W - 20) * UiKit.ease(f)), ry + ROW - 5, 0xFF9BC060);
			}
			// what comes out
			ItemStack out = recipe.output();
			g.renderItem(out, x + 14, ry + 7);
			g.renderItemDecorations(this.font, out, x + 14, ry + 7);
			g.drawString(this.font, out.getHoverName(), x + 36, ry + 5, can ? 0xFFF0EEE0 : 0xFFA0A098, false);
			// what it takes
			int ix = x + 36;
			for (Workbench.Ingredient in : recipe.ingredients()) {
				ItemStack need = new ItemStack(in.item().get());
				int have = player == null ? 0 : Workbench.carried(player.getInventory(), in.item().get());
				g.pose().pushMatrix();
				g.pose().translate(ix, ry + 16);
				g.pose().scale(0.75F, 0.75F);
				g.renderItem(need, 0, 0);
				g.pose().popMatrix();
				String count = in.count() + "";
				g.drawString(this.font, count, ix + 13, ry + 20, have >= in.count() || player.getAbilities().instabuild ? 0xFF8CD070 : 0xFFE06050, true);
				if (mouseX >= ix && mouseX < ix + 12 && mouseY >= ry + 16 && mouseY < ry + 28) {
					g.setTooltipForNextFrame(this.font, need.getHoverName(), mouseX, mouseY);
				}
				ix += 13 + this.font.width(count) + 8;
			}
		}
		g.drawString(this.font, Component.translatable("screen.ballisticmissiles.workbench.hint"), x + 10, y + H - 14, 0xFF8A8A80, false);
	}
}
