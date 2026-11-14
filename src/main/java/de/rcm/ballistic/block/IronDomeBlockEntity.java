package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.defense.DefenseNetwork;
import de.rcm.ballistic.defense.DefenseOwner;
import de.rcm.ballistic.defense.EmpManager;
import de.rcm.ballistic.defense.ThreatTracker;
import de.rcm.ballistic.entity.InterceptorEntity;
import de.rcm.ballistic.entity.MissileEntity;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Iron Dome battery: a 20-round vertical launcher made for saturation attacks. It covers a smaller
 * area than the Patriot-style battery, but fires every few ticks and engages many threats at once -
 * rockets, drones, cruise missiles, Hellfires - each with one Tamir interceptor (two for nukes).
 * Threats that will land outside its protected zone are ignored to save rounds.
 * <p>
 * Reloading is done as in the real thing, by swapping the whole 20-round pod: Tamir pods are
 * handed to the battery (right-click with one) or stocked in a chest or barrel near it. When the
 * launcher is empty - or half empty and nothing has come in for a while - its reload truck drives
 * up, the launcher is lowered, the truck's crane lifts the spent pod off and sets a fresh one in,
 * and the truck drives away again. Without pods it stays empty.
 */
public class IronDomeBlockEntity extends BlockEntity implements DefenseSiteBlock.Site {
	public static final double RANGE = 170.0;
	public static final double CEILING = 260.0;
	public static final int MAGAZINE = 20;
	private static final int FIRE_GAP = 4;
	/** The pod swap, start to finish, and the moment the fresh pod is seated. */
	public static final int RELOAD_TIME = 320;
	public static final int RELOAD_SEATED = 215;
	/** Spare pods the battery itself can hold. */
	public static final int POD_STORE = 4;
	/** A half-empty launcher is topped up once nothing has been engaged for this long. */
	private static final int IDLE_TOP_UP = 600;

	private int ammo = MAGAZINE;
	private int pods;
	/** Game time the current pod swap began, or -1. */
	private long reloadStart = -1L;
	private long lastEngagement;
	private int cooldown;
	private int launches;
	private @Nullable UUID owner;

	public IronDomeBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.IRON_DOME_BE, pos, state);
	}

	@Override
	public void serverTick(ServerLevel level) {
		DefenseNetwork.register(level, this.worldPosition, DefenseNetwork.Kind.AIR_DEFENSE);
		if (this.reloadTick(level)) {
			return; // no launches while the pod is being changed
		}
		if (this.cooldown > 0) {
			this.cooldown--;
			return;
		}
		if (this.ammo <= 0 || EmpManager.isJammed(level, this.worldPosition)) {
			return;
		}
		Vec3 here = Vec3.atCenterOf(this.worldPosition);
		AirThreat best = null;
		int bestEta = Integer.MAX_VALUE;
		for (AirThreat threat : ThreatTracker.threats(level)) {
			if (DefenseOwner.isFriendly(this.owner, threat) || threat.threatClass() == AirThreat.ThreatClass.AIRCRAFT) {
				continue;
			}
			Vec3 impact = threat.predictedImpact();
			if (Math.hypot(impact.x - here.x, impact.z - here.z) > RANGE * 0.75) {
				continue; // not going to land in the protected zone
			}
			int salvo = threat.asEntity() instanceof MissileEntity m && m.getMissileType().isNuclear() ? 2 : 1;
			if (threat.getEngagements() >= salvo) {
				continue;
			}
			Vec3 p = threat.aimPoint(20);
			if (p.distanceTo(here) > RANGE || p.y - here.y > CEILING || p.y - here.y < 6.0) {
				continue;
			}
			int eta = threat.etaTicks();
			if (eta < bestEta) {
				bestEta = eta;
				best = threat;
			}
		}
		if (best == null) {
			return;
		}
		// out of the next loaded cell along the launcher's 55-degree axis; the Tamir turns over towards
		// the target right after leaving it
		int cell = MAGAZINE - this.ammo;
		Vec3 mouth = this.toWorld(cellLocal(cell, PACK_LENGTH + 0.3F));
		Vec3 axis = this.toWorld(new float[] {0.0F, PIVOT_Y + Mth.sin(ELEVATION), PIVOT_Z + Mth.cos(ELEVATION)}).subtract(this.toWorld(new float[] {0.0F, PIVOT_Y, PIVOT_Z}));
		float pk = Math.min(0.95F, best.killProbability() + 0.15F);
		if (InterceptorEntity.launch(level, this.worldPosition, mouth, best, pk, axis) != null) {
			best.setEngagements(best.getEngagements() + 1);
			this.ammo--;
			this.launches++;
			this.lastEngagement = level.getGameTime();
			this.cooldown = FIRE_GAP;
			this.sync();
			level.playSound(null, mouth.x, mouth.y, mouth.z, ModRegistry.SAM_LAUNCH, SoundSource.BLOCKS, 9.0F, 1.15F + level.getRandom().nextFloat() * 0.15F);
			level.sendParticles(ParticleTypes.CLOUD, mouth.x, mouth.y, mouth.z, 20, 0.4, 0.3, 0.4, 0.06);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, here.x, here.y + 0.5, here.z, 10, 0.8, 0.2, 0.8, 0.04);
		}
	}

	// ------------------------------------------------------------------ pod swap

	/** @return true while a reload is under way */
	private boolean reloadTick(ServerLevel level) {
		long now = level.getGameTime();
		if (this.reloadStart < 0L) {
			boolean want = this.ammo == 0 || this.ammo <= MAGAZINE / 2 && now - this.lastEngagement > IDLE_TOP_UP;
			if (!want || now % 20 != 0) {
				return false;
			}
			if (this.pods <= 0 && !this.takePodFromStorage(level)) {
				return false;
			}
			this.pods--;
			this.reloadStart = now;
			this.sync();
			return true;
		}
		int t = (int) (now - this.reloadStart);
		Vec3 here = Vec3.atCenterOf(this.worldPosition);
		var random = level.getRandom();
		// the sounds of it: the truck's diesel and reversing beeper, the rams, the crane, the pod clanking home
		if (t == 20 || t == 245) {
			level.playSound(null, here.x - 3, here.y, here.z, ModRegistry.TRUCK_DIESEL, SoundSource.BLOCKS, 2.5F, 1.0F);
		}
		if (t >= 30 && t < 80 && t % 10 == 0) {
			level.playSound(null, here.x - 3, here.y, here.z, ModRegistry.COUNTDOWN_BEEP, SoundSource.BLOCKS, 0.8F, 1.6F);
		}
		if (t == 2 || t == 285) {
			level.playSound(null, here.x, here.y + 1, here.z, ModRegistry.HYDRAULIC_EXTEND, SoundSource.BLOCKS, 1.8F, 1.0F);
		}
		if (t == 95 || t == 160) {
			level.playSound(null, here.x - 1.5, here.y + 2, here.z, ModRegistry.CHAIN_RATTLE, SoundSource.BLOCKS, 1.5F, 0.9F);
		}
		if (t == 150 || t == RELOAD_SEATED) {
			level.playSound(null, here.x, here.y + 1.5, here.z, ModRegistry.METAL_THUD, SoundSource.BLOCKS, 1.2F, 0.95F + random.nextFloat() * 0.1F);
		}
		if (t == RELOAD_SEATED) {
			this.ammo = MAGAZINE;
			this.sync();
		}
		if (t >= RELOAD_TIME) {
			this.reloadStart = -1L;
			this.lastEngagement = now;
			this.sync();
			return false;
		}
		return true;
	}

	/** A Tamir pod out of a chest or barrel within a few blocks of the battery. */
	private boolean takePodFromStorage(ServerLevel level) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dy = -1; dy <= 2; dy++) {
			for (int dx = -4; dx <= 4; dx++) {
				for (int dz = -4; dz <= 4; dz++) {
					m.setWithOffset(this.worldPosition, dx, dy, dz);
					if (level.getBlockEntity(m) instanceof net.minecraft.world.Container box) {
						for (int i = 0; i < box.getContainerSize(); i++) {
							if (box.getItem(i).is(ModRegistry.TAMIR_POD)) {
								box.removeItem(i, 1);
								box.setChanged();
								this.pods++;
								return true;
							}
						}
					}
				}
			}
		}
		return false;
	}

	/** Right-click with Tamir pods: they go into the battery's store. */
	public boolean deliver(net.minecraft.world.entity.player.Player player, net.minecraft.world.item.ItemStack stack) {
		int room = POD_STORE - this.pods;
		if (room <= 0) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.iron_dome_store_full", POD_STORE).withStyle(ChatFormatting.YELLOW), true);
			return false;
		}
		int n = Math.min(room, stack.getCount());
		this.pods += n;
		if (!player.getAbilities().instabuild) {
			stack.shrink(n);
		}
		this.sync();
		player.displayClientMessage(Component.translatable("message.ballisticmissiles.iron_dome_pods", this.pods, POD_STORE).withStyle(ChatFormatting.AQUA), true);
		return true;
	}

	public boolean isReloading() {
		return this.reloadStart >= 0L;
	}

	/** Client: ticks into the pod swap, or -1. */
	public float reloadAge(float partialTick) {
		if (this.reloadStart < 0L || this.level == null) {
			return -1.0F;
		}
		float t = this.level.getGameTime() - this.reloadStart + partialTick;
		return t >= 0.0F && t < RELOAD_TIME ? t : -1.0F;
	}

	public int getPods() {
		return this.pods;
	}

	@Override
	public int siteAmmo() {
		return this.ammo;
	}

	@Override
	public int siteMagazine() {
		return MAGAZINE;
	}

	@Override
	public boolean siteReloading() {
		return this.isReloading();
	}

	@Override
	public int siteSpares() {
		return this.pods;
	}

	// ------------------------------------------------------------------ launcher geometry (shared with the renderer)

	/** Pack pivot (trailer space), fixed elevation and tube length. */
	public static final float PIVOT_Y = 1.25F;
	public static final float PIVOT_Z = -1.6F;
	public static final float ELEVATION = 55.0F * Mth.DEG_TO_RAD;
	public static final float PACK_LENGTH = 3.4F;

	/** Centre of a cell (0 = top left, row by row) in pack space: {x, y}. */
	public static float[] cellCentre(int cell) {
		return new float[] {-0.75F + (cell % 4) * 0.5F, 1.3F - (cell / 4) * 0.3F};
	}

	/** A point on a cell's axis, {@code along} blocks from the pack's rear, in trailer space. */
	private static float[] cellLocal(int cell, float along) {
		float[] c = cellCentre(cell);
		float sin = Mth.sin(ELEVATION);
		float cos = Mth.cos(ELEVATION);
		return new float[] {c[0], PIVOT_Y + c[1] * cos + along * sin, PIVOT_Z - c[1] * sin + along * cos};
	}

	/** Trailer space (+Z = facing) to world space. */
	private Vec3 toWorld(float[] p) {
		net.minecraft.core.Direction facing = this.getBlockState().hasProperty(DefenseSiteBlock.FACING)
			? this.getBlockState().getValue(DefenseSiteBlock.FACING) : net.minecraft.core.Direction.NORTH;
		double fx = facing.getStepX();
		double fz = facing.getStepZ();
		// local +Z = facing, local +X = facing rotated like the renderer's rotationY(atan2(fx, fz))
		return Vec3.atBottomCenterOf(this.worldPosition).add(p[0] * fz + p[2] * fx, p[1], -p[0] * fx + p[2] * fz);
	}

	// ------------------------------------------------------------------ client animation

	/** Client: ticks since each cell's cover was blown off (-1 = not flying). */
	public final int[] popAge = new int[MAGAZINE];
	private int clientLastAmmo = -1;

	{
		java.util.Arrays.fill(this.popAge, -1);
	}

	@Override
	public void clientTick() {
		if (this.clientLastAmmo >= 0 && this.ammo < this.clientLastAmmo) {
			for (int a = this.clientLastAmmo; a > this.ammo; a--) {
				int cell = MAGAZINE - a;
				if (cell >= 0 && cell < MAGAZINE) {
					this.popAge[cell] = 0;
				}
			}
		}
		this.clientLastAmmo = this.ammo;
		for (int i = 0; i < this.popAge.length; i++) {
			if (this.popAge[i] >= 0 && ++this.popAge[i] > 50) {
				this.popAge[i] = -1;
			}
		}
	}

	@Override
	public Component status() {
		if (this.level != null) {
			int jammed = EmpManager.jammedTicks(this.level, this.worldPosition);
			if (jammed > 0) {
				return Component.translatable("message.ballisticmissiles.jammed", jammed / 20).withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD);
			}
		}
		if (this.isReloading()) {
			return Component.translatable("message.ballisticmissiles.iron_dome_reloading", this.pods).withStyle(ChatFormatting.YELLOW);
		}
		if (this.ammo == 0 && this.pods == 0) {
			return Component.translatable("message.ballisticmissiles.iron_dome_empty").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
		}
		return Component.translatable("message.ballisticmissiles.iron_dome_status2", this.ammo, MAGAZINE, this.pods, (int) RANGE, this.launches).withStyle(ChatFormatting.AQUA);
	}

	@Override
	public void setOwner(@Nullable UUID owner) {
		this.owner = owner;
		this.setChanged();
	}

	@Override
	public void setRemoved() {
		if (this.level != null && !this.level.isClientSide()) {
			DefenseNetwork.unregister(this.level, this.worldPosition);
		}
		super.setRemoved();
	}

	public int getAmmo() {
		return this.ammo;
	}

	private void sync() {
		this.setChanged();
		if (this.level != null && !this.level.isClientSide()) {
			this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
		}
	}

	@Override
	public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
		return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
		return this.saveCustomOnly(registries);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putInt("Ammo", this.ammo);
		output.putInt("Pods", this.pods);
		output.putLong("ReloadStart", this.reloadStart);
		output.putLong("LastEngagement", this.lastEngagement);
		output.putInt("Launches", this.launches);
		if (this.owner != null) {
			output.store("Owner", UUIDUtil.CODEC, this.owner);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.ammo = input.getIntOr("Ammo", MAGAZINE);
		this.pods = input.getIntOr("Pods", 0);
		this.reloadStart = input.getLongOr("ReloadStart", -1L);
		this.lastEngagement = input.getLongOr("LastEngagement", 0L);
		this.launches = input.getIntOr("Launches", 0);
		this.owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
	}
}
