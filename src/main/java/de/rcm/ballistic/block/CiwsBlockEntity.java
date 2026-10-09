package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.defense.DefenseNetwork;
import de.rcm.ballistic.defense.DefenseOwner;
import de.rcm.ballistic.defense.EmpManager;
import de.rcm.ballistic.defense.ThreatTracker;
import net.minecraft.ChatFormatting;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * Phalanx-style close-in weapon system. Its own search-and-track radar picks the nearest missile,
 * re-entry vehicle or drone that is about to hit inside its defended zone, the mount slews onto a
 * lead point computed from the target's predicted path and the six-barrel 20 mm gun fires ~75
 * rounds per second. Every tick of fire has a chance to tear the target apart; the chance grows
 * the closer the target gets and depends on how hard the target is to hit. A last line of defense:
 * short range, but no reload delay between engagements and nothing gets past it cheaply.
 */
public class CiwsBlockEntity extends BlockEntity {
	public static final double RANGE = 110.0;
	public static final double DEFENDED_RADIUS = 140.0;
	public static final int MAGAZINE = 1550;
	private static final int ROUNDS_PER_TICK = 4;
	private static final int RELOAD_PER_TICK = 3;
	private static final double BULLET_SPEED = 50.0;
	private static final float SLEW_YAW = 0.22F;
	private static final float SLEW_PITCH = 0.16F;
	private static final float FIRE_CONE = 0.07F;
	/** Muzzle height above the block origin. */
	public static final double MUZZLE_Y = 2.35;
	/** Distance from the pivot to the muzzles along the barrels. */
	public static final double BARREL_LENGTH = 2.45;

	private int ammo = MAGAZINE;
	private int kills;
	private int targetId = -1;
	private int soundTimer;
	private int syncTimer;
	private boolean syncedActive;
	private @Nullable UUID owner;

	// synced to clients
	private float yaw;
	private float pitch = 0.15F;
	private boolean firing;
	private float targetDistance;

	// client-side animation
	public float clientYaw;
	public float clientYawO;
	public float clientPitch = 0.15F;
	public float clientPitchO = 0.15F;
	public float barrelSpin;
	public float barrelSpinO;
	public float spinSpeed;

	public CiwsBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.CIWS_BE, pos, state);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, CiwsBlockEntity gun) {
		if (!(level instanceof ServerLevel server)) {
			return;
		}
		DefenseNetwork.register(level, pos, DefenseNetwork.Kind.AIR_DEFENSE);
		gun.firing = false;
		if (EmpManager.isJammed(level, pos)) {
			gun.targetId = -1;
			gun.finishTick(server);
			return;
		}

		Vec3 muzzle = Vec3.atBottomCenterOf(pos).add(0, MUZZLE_Y, 0);
		AirThreat target = gun.currentTarget(server, muzzle);
		if (target == null && level.getGameTime() % 4 == 0) {
			target = gun.acquire(server, muzzle);
		}
		if (target == null) {
			gun.targetId = -1;
			if (gun.ammo < MAGAZINE) {
				gun.ammo = Math.min(MAGAZINE, gun.ammo + RELOAD_PER_TICK);
			}
			// park the barrels pointing up-range
			gun.pitch = approach(gun.pitch, 0.15F, SLEW_PITCH * 0.3F);
			gun.finishTick(server);
			return;
		}
		gun.targetId = target.asEntity().getId();

		// lead the target: aim where it will be when the rounds arrive
		Vec3 now = target.aimPoint(0);
		int flight = (int) Math.ceil(now.distanceTo(muzzle) / BULLET_SPEED);
		Vec3 lead = target.aimPoint(flight);
		Vec3 d = lead.subtract(muzzle);
		float wantYaw = (float) Mth.atan2(d.x, d.z);
		float wantPitch = (float) Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z));
		gun.yaw = approachAngle(gun.yaw, wantYaw, SLEW_YAW);
		gun.pitch = approach(gun.pitch, wantPitch, SLEW_PITCH);
		gun.targetDistance = (float) now.distanceTo(muzzle);

		float error = Math.abs(Mth.wrapDegrees((gun.yaw - wantYaw) * Mth.RAD_TO_DEG)) * Mth.DEG_TO_RAD + Math.abs(gun.pitch - wantPitch);
		if (error < FIRE_CONE && gun.ammo >= ROUNDS_PER_TICK && gun.targetDistance < RANGE) {
			gun.firing = true;
			gun.ammo -= ROUNDS_PER_TICK;
			if (gun.soundTimer-- <= 0) {
				gun.soundTimer = 16;
				level.playSound(null, pos, ModRegistry.CIWS_FIRE, SoundSource.BLOCKS, 10.0F, 0.95F + server.getRandom().nextFloat() * 0.1F);
			}
			float rangeFactor = Mth.clamp(1.25F - gun.targetDistance / (float) RANGE, 0.25F, 1.0F);
			float pk = 0.07F * (target.killProbability() / 0.8F) * rangeFactor;
			if (server.getRandom().nextFloat() < pk) {
				Component name = Component.translatable(target.nameKey());
				target.destroyByInterceptor(server);
				gun.kills++;
				gun.targetId = -1;
				Component msg = Component.literal("✔ ").append(Component.translatable("message.ballisticmissiles.ciws_kill", name))
					.withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
				for (ServerPlayer player : server.players()) {
					if (player.position().distanceToSqr(muzzle) < 160 * 160) {
						player.displayClientMessage(msg, true);
					}
				}
			}
		} else {
			gun.soundTimer = 0;
		}
		gun.setChanged();
		gun.finishTick(server);
	}

	/** Pushes the turret state to clients: every other tick while it tracks, once when it goes quiet. */
	private void finishTick(ServerLevel level) {
		boolean active = this.targetId >= 0;
		if (active && ++this.syncTimer >= 2 || active != this.syncedActive) {
			this.syncTimer = 0;
			this.syncedActive = active;
			level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	private AirThreat currentTarget(ServerLevel level, Vec3 muzzle) {
		if (this.targetId < 0) {
			return null;
		}
		if (level.getEntity(this.targetId) instanceof AirThreat threat && threat.isActiveThreat() && this.engageable(level, threat, muzzle)) {
			return threat;
		}
		return null;
	}

	private AirThreat acquire(ServerLevel level, Vec3 muzzle) {
		AirThreat best = null;
		double bestDistance = Double.MAX_VALUE;
		for (AirThreat threat : ThreatTracker.threats(level)) {
			if (!this.engageable(level, threat, muzzle)) {
				continue;
			}
			double dist = threat.aimPoint(0).distanceTo(muzzle);
			if (dist < bestDistance) {
				bestDistance = dist;
				best = threat;
			}
		}
		return best;
	}

	/** In range, above the horizon, coming down inside our zone and not hidden behind terrain. */
	private boolean engageable(ServerLevel level, AirThreat threat, Vec3 muzzle) {
		if (DefenseOwner.isFriendly(this.owner, threat)) {
			return false;
		}
		Vec3 p = threat.aimPoint(0);
		double dist = p.distanceTo(muzzle);
		if (dist > RANGE * 1.15 || p.y < muzzle.y + 2.0) {
			return false;
		}
		Vec3 impact = threat.predictedImpact();
		if (Math.hypot(impact.x - muzzle.x, impact.z - muzzle.z) > DEFENDED_RADIUS && dist > 40.0) {
			return false; // just passing by
		}
		var hit = level.clip(new ClipContext(muzzle.add(p.subtract(muzzle).normalize().scale(1.5)), p, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
			CollisionContext.empty()));
		return hit.getType() == HitResult.Type.MISS;
	}

	public static void clientTick(Level level, BlockPos pos, BlockState state, CiwsBlockEntity gun) {
		gun.clientYawO = gun.clientYaw;
		gun.clientPitchO = gun.clientPitch;
		gun.barrelSpinO = gun.barrelSpin;
		gun.clientYaw = approachAngle(gun.clientYaw, gun.yaw, SLEW_YAW);
		gun.clientPitch = approach(gun.clientPitch, gun.pitch, SLEW_PITCH);
		gun.spinSpeed = gun.firing ? Math.min(1.4F, gun.spinSpeed + 0.35F) : gun.spinSpeed * 0.9F;
		gun.barrelSpin += gun.spinSpeed;
		if (gun.firing) {
			Vec3 dir = gun.aimDirection(1.0F);
			Vec3 muzzle = Vec3.atBottomCenterOf(pos).add(0, MUZZLE_Y, 0).add(dir.scale(BARREL_LENGTH));
			var random = level.getRandom();
			// muzzle flash flickering at the restrainer and a growing cloud of propellant smoke
			level.addParticle(ParticleTypes.FLAME, muzzle.x, muzzle.y, muzzle.z, dir.x * 0.25, dir.y * 0.25, dir.z * 0.25);
			level.addParticle(ParticleTypes.SMALL_FLAME, muzzle.x + dir.x * 0.3, muzzle.y + dir.y * 0.3, muzzle.z + dir.z * 0.3, dir.x * 0.4, dir.y * 0.4, dir.z * 0.4);
			level.addParticle(ParticleTypes.SMOKE, muzzle.x, muzzle.y, muzzle.z, dir.x * 0.1, dir.y * 0.1 + 0.02, dir.z * 0.1);
			if (random.nextInt(3) == 0) {
				level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, muzzle.x, muzzle.y, muzzle.z, dir.x * 0.05, 0.03, dir.z * 0.05);
			}
			// spent brass streaming out below the mount
			Vec3 side = new Vec3(-dir.z, 0, dir.x).normalize();
			Vec3 eject = Vec3.atBottomCenterOf(pos).add(0, MUZZLE_Y - 0.4, 0).add(side.scale(0.4));
			for (int i = 0; i < 2; i++) {
				level.addParticle(ParticleTypes.CRIT, eject.x, eject.y, eject.z, side.x * 0.15 + random.nextGaussian() * 0.03, 0.1, side.z * 0.15 + random.nextGaussian() * 0.03);
			}
		}
	}

	/** Barrel direction on the client, interpolated. */
	public Vec3 aimDirection(float partialTick) {
		float y = Mth.rotLerpRad(partialTick, this.clientYawO, this.clientYaw);
		float p = Mth.lerp(partialTick, this.clientPitchO, this.clientPitch);
		return new Vec3(Mth.cos(p) * Mth.sin(y), Mth.sin(p), Mth.cos(p) * Mth.cos(y));
	}

	/** The player who built this defense; their own aircraft are never engaged. */
	public void setOwner(@Nullable UUID owner) {
		this.owner = owner;
		this.setChanged();
	}

	public int getAmmo() {
		return this.ammo;
	}

	public boolean isFiring() {
		return this.firing;
	}

	public float getTargetDistance() {
		return this.targetDistance;
	}

	private static float approach(float from, float to, float step) {
		return from + Mth.clamp(to - from, -step, step);
	}

	private static float approachAngle(float from, float to, float step) {
		float diff = Mth.wrapDegrees((to - from) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD;
		return from + Mth.clamp(diff, -step, step);
	}

	public Component status() {
		if (this.level != null) {
			int jammed = EmpManager.jammedTicks(this.level, this.worldPosition);
			if (jammed > 0) {
				return Component.translatable("message.ballisticmissiles.jammed", jammed / 20).withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD);
			}
		}
		return Component.translatable("message.ballisticmissiles.ciws_status", this.ammo, MAGAZINE, (int) RANGE, this.kills).withStyle(ChatFormatting.AQUA);
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
		output.putInt("Kills", this.kills);
		if (this.owner != null) {
			output.store("Owner", UUIDUtil.CODEC, this.owner);
		}
		output.putFloat("Yaw", this.yaw);
		output.putFloat("Pitch", this.pitch);
		output.putBoolean("Firing", this.firing);
		output.putFloat("TargetDistance", this.targetDistance);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.ammo = input.getIntOr("Ammo", MAGAZINE);
		this.kills = input.getIntOr("Kills", 0);
		this.owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
		this.yaw = input.getFloatOr("Yaw", 0.0F);
		this.pitch = input.getFloatOr("Pitch", 0.15F);
		this.firing = input.getBooleanOr("Firing", false);
		this.targetDistance = input.getFloatOr("TargetDistance", 0.0F);
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
