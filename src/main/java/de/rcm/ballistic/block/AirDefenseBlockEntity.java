package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.defense.DefenseNetwork;
import de.rcm.ballistic.defense.EmpManager;
import de.rcm.ballistic.defense.ThreatTracker;
import de.rcm.ballistic.entity.InterceptorEntity;
import de.rcm.ballistic.entity.MissileEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Surface-to-air missile battery. It defends the area around itself: every missile or re-entry
 * vehicle whose predicted impact point lies inside its engagement zone is a threat, no matter who
 * launched it. For each threat the fire-control computer looks for a launch solution (a point on the
 * predicted flight path the interceptor can reach in time, inside range and altitude limits) and
 * fires as soon as one exists. Misses are re-engaged (shoot-look-shoot); nuclear and hypersonic
 * threats get a two-round salvo.
 * <p>
 * On its own the battery only sees what its small fire-control radar sees. Linked to an early-warning
 * radar within {@value #RADAR_LINK_RANGE} blocks it engages much further out and hits more reliably.
 */
public class AirDefenseBlockEntity extends BlockEntity {
	public static final double OWN_RANGE = 200.0;
	public static final double LINKED_RANGE = 380.0;
	public static final double RADAR_LINK_RANGE = 96.0;
	public static final double CEILING = 420.0;
	public static final int MAGAZINE = 8;
	private static final int RELOAD_TICKS = 100;
	private static final int SALVO_GAP = 5;

	/** Launcher geometry, shared with the renderer: pivot height above the block and canister length. */
	public static final double PIVOT_HEIGHT = 2.05;
	public static final double CANISTER_LENGTH = 5.4;
	public static final float FIRING_ELEVATION = 38.0F * Mth.DEG_TO_RAD;
	private static final float TRAIN_RATE = 0.09F;
	private static final float ELEVATE_RATE = 0.02F;
	private static final long STOW_AFTER = 400;

	private int ammo = MAGAZINE;
	private int reload;
	private int cooldown;
	private int kills;
	private int misses;
	private boolean linked;
	private float targetYaw;
	private long lastTrack = -100000L;
	private boolean wasMoving;

	// synced to clients
	private float yaw;
	private float elevation;

	// client-side animation
	public float clientYaw;
	public float clientYawO;
	public float clientElevation;
	public float clientElevationO;

	public AirDefenseBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.AIR_DEFENSE_BE, pos, state);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, AirDefenseBlockEntity battery) {
		if (!(level instanceof ServerLevel server)) {
			return;
		}
		DefenseNetwork.register(level, pos, DefenseNetwork.Kind.AIR_DEFENSE);
		if (battery.ammo < MAGAZINE && ++battery.reload >= RELOAD_TICKS) {
			battery.reload = 0;
			battery.ammo++;
			battery.sync();
		}
		if (battery.cooldown > 0) {
			battery.cooldown--;
		}
		battery.slew(server);
		if (level.getGameTime() % 2 != 0 || EmpManager.isJammed(level, pos)) {
			return;
		}
		Vec3 here = Vec3.atCenterOf(pos);
		battery.linked = DefenseNetwork.find(level, DefenseNetwork.Kind.RADAR, here, RADAR_LINK_RANGE)
			.stream()
			.anyMatch(r -> !EmpManager.isJammed(level, r));

		double range = battery.range();
		Vec3 launch = battery.pivot();
		AirThreat best = null;
		int bestEta = Integer.MAX_VALUE;
		for (AirThreat threat : ThreatTracker.threats(server)) {
			Vec3 impact = threat.predictedImpact();
			if (Math.hypot(impact.x - here.x, impact.z - here.z) > range + 16) {
				continue; // not heading into our zone
			}
			if (threat.getEngagements() >= salvoSize(threat)) {
				continue;
			}
			if (!hasLaunchSolution(threat, launch, range)) {
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
		// train the launcher onto the threat's bearing; it fires once it points roughly there
		Vec3 aim = InterceptorEntity.solveIntercept(launch, InterceptorEntity.START_SPEED, best);
		battery.targetYaw = (float) Mth.atan2(aim.x - here.x, aim.z - here.z);
		battery.lastTrack = level.getGameTime();
		if (battery.cooldown > 0 || battery.ammo <= 0 || !battery.readyToFire()) {
			return;
		}

		float pk = Mth.clamp(best.killProbability() + (battery.linked ? 0.1F : 0.0F), 0.05F, 0.97F);
		Vec3 axis = battery.launcherAxis();
		// alternate between the four canisters
		int canister = battery.ammo % 4;
		Vec3 side = new Vec3(Mth.cos(battery.yaw), 0, -Mth.sin(battery.yaw)).scale(canister % 2 == 0 ? -0.5 : 0.5);
		Vec3 mouth = launch.add(axis.scale(CANISTER_LENGTH + 0.3)).add(side).add(0, canister < 2 ? 0.2 : 0.9, 0);
		if (InterceptorEntity.launch(server, pos, mouth, best, pk, axis) != null) {
			best.setEngagements(best.getEngagements() + 1);
			battery.ammo--;
			battery.cooldown = SALVO_GAP;
			battery.sync();
			level.playSound(null, mouth.x, mouth.y, mouth.z, ModRegistry.SAM_LAUNCH, SoundSource.BLOCKS, 10.0F, 0.95F + server.getRandom().nextFloat() * 0.1F);
			server.sendParticles(ParticleTypes.CLOUD, mouth.x, mouth.y, mouth.z, 30, 0.6, 0.6, 0.6, 0.08);
			Vec3 back = launch.subtract(axis.scale(0.5));
			server.sendParticles(ParticleTypes.LARGE_SMOKE, back.x, back.y, back.z, 20, 0.5, 0.3, 0.5, 0.05);
			Component msg = Component.literal("⇧ ")
				.append(Component.translatable("message.ballisticmissiles.sam_launch", Component.translatable(best.nameKey()), battery.ammo, MAGAZINE))
				.withStyle(ChatFormatting.AQUA);
			for (ServerPlayer player : server.players()) {
				if (player.position().distanceToSqr(here) < 128 * 128) {
					player.displayClientMessage(msg, true);
				}
			}
		}
	}

	/**
	 * Drives the launcher every tick: trains towards the last target bearing and raises to firing
	 * elevation while there is something to shoot at, stows after a quiet spell. Clients get the
	 * state while it moves.
	 */
	private void slew(ServerLevel level) {
		boolean active = level.getGameTime() - this.lastTrack < STOW_AFTER;
		float wantElevation = active ? FIRING_ELEVATION : 0.0F;
		float wantYaw = active ? this.targetYaw : this.restYaw();
		float oldYaw = this.yaw;
		float oldElevation = this.elevation;
		this.yaw = approachAngle(this.yaw, wantYaw, TRAIN_RATE);
		this.elevation = this.elevation + Mth.clamp(wantElevation - this.elevation, -ELEVATE_RATE, ELEVATE_RATE);
		boolean moving = Math.abs(this.yaw - oldYaw) > 1.0E-4F || Math.abs(this.elevation - oldElevation) > 1.0E-4F;
		if (moving && level.getGameTime() % 3 == 0 || moving != this.wasMoving) {
			this.sync();
		}
		this.wasMoving = moving;
	}

	private boolean readyToFire() {
		float error = Math.abs(Mth.wrapDegrees((this.yaw - this.targetYaw) * Mth.RAD_TO_DEG));
		return error < 12.0F && this.elevation > FIRING_ELEVATION - 0.05F;
	}

	/** Rest bearing: straight ahead over the trailer's front. */
	private float restYaw() {
		BlockState state = this.getBlockState();
		Direction facing = state.hasProperty(AirDefenseBlock.FACING) ? state.getValue(AirDefenseBlock.FACING) : Direction.NORTH;
		return (float) Mth.atan2(facing.getStepX(), facing.getStepZ());
	}

	/** Elevation pivot of the launcher, in world space. */
	public Vec3 pivot() {
		return Vec3.atBottomCenterOf(this.worldPosition).add(0, PIVOT_HEIGHT, 0);
	}

	/** Unit vector along the canisters. */
	public Vec3 launcherAxis() {
		return new Vec3(Mth.sin(this.yaw) * Mth.cos(this.elevation), Mth.sin(this.elevation), Mth.cos(this.yaw) * Mth.cos(this.elevation));
	}

	private static float approachAngle(float from, float to, float step) {
		float diff = Mth.wrapDegrees((to - from) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD;
		return from + Mth.clamp(diff, -step, step);
	}

	private void sync() {
		this.setChanged();
		if (this.level != null && !this.level.isClientSide()) {
			this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	public static void clientTick(Level level, BlockPos pos, BlockState state, AirDefenseBlockEntity battery) {
		battery.clientYawO = battery.clientYaw;
		battery.clientElevationO = battery.clientElevation;
		battery.clientYaw = approachAngle(battery.clientYaw, battery.yaw, TRAIN_RATE);
		battery.clientElevation += Mth.clamp(battery.elevation - battery.clientElevation, -ELEVATE_RATE, ELEVATE_RATE);
	}

	public int getAmmo() {
		return this.ammo;
	}

	private static int salvoSize(AirThreat threat) {
		if (threat.threatClass() == AirThreat.ThreatClass.HYPERSONIC) {
			return 2;
		}
		return threat.asEntity() instanceof MissileEntity m && m.getMissileType().isNuclear() ? 2 : 1;
	}

	/** Is there a point on the threat's path the interceptor reaches in time, inside our envelope? */
	private static boolean hasLaunchSolution(AirThreat threat, Vec3 launch, double range) {
		double minAlt = threat.threatClass() == AirThreat.ThreatClass.CRUISE ? 4.0 : 14.0;
		double reach = 0.0;
		double speed = InterceptorEntity.START_SPEED;
		for (int k = 1; k <= InterceptorEntity.MAX_LOOKAHEAD; k++) {
			speed = Math.min(InterceptorEntity.MAX_SPEED, speed + InterceptorEntity.ACCEL);
			reach += speed;
			Vec3 p = threat.aimPoint(k);
			double alt = p.y - launch.y;
			if (alt < minAlt || alt > CEILING || Math.hypot(p.x - launch.x, p.z - launch.z) > range) {
				continue;
			}
			if (reach * 0.9 >= p.distanceTo(launch) + 4.0) { // margin for the pitch-over after launch
				return true;
			}
		}
		return false;
	}

	public double range() {
		return this.linked ? LINKED_RANGE : OWN_RANGE;
	}

	public void onEngagementResult(boolean kill) {
		if (kill) {
			this.kills++;
		} else {
			this.misses++;
		}
		this.setChanged();
	}

	public Component status() {
		if (this.level != null) {
			int jammed = EmpManager.jammedTicks(this.level, this.worldPosition);
			if (jammed > 0) {
				return Component.translatable("message.ballisticmissiles.jammed", jammed / 20).withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD);
			}
		}
		Component mode = Component.translatable(this.linked ? "message.ballisticmissiles.ad_linked" : "message.ballisticmissiles.ad_autonomous");
		return Component.translatable("message.ballisticmissiles.ad_status", this.ammo, MAGAZINE, mode, (int) this.range(), this.kills, this.misses)
			.withStyle(ChatFormatting.AQUA);
	}

	@Override
	public void setRemoved() {
		if (this.level != null && !this.level.isClientSide()) {
			DefenseNetwork.unregister(this.level, this.worldPosition);
		}
		super.setRemoved();
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putInt("Ammo", this.ammo);
		output.putInt("Reload", this.reload);
		output.putInt("Kills", this.kills);
		output.putInt("Misses", this.misses);
		output.putFloat("Yaw", this.yaw);
		output.putFloat("Elevation", this.elevation);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.ammo = input.getIntOr("Ammo", MAGAZINE);
		this.reload = input.getIntOr("Reload", 0);
		this.kills = input.getIntOr("Kills", 0);
		this.misses = input.getIntOr("Misses", 0);
		this.yaw = input.getFloatOr("Yaw", 0.0F);
		this.elevation = input.getFloatOr("Elevation", 0.0F);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return this.saveCustomOnly(registries);
	}
}
