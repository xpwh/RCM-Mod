package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.defense.DefenseNetwork;
import de.rcm.ballistic.defense.DefenseOwner;
import de.rcm.ballistic.defense.EmpManager;
import de.rcm.ballistic.defense.ThreatTracker;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
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
import net.minecraft.world.entity.Entity;
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
 * High-energy laser air defense (Iron Beam style). Needs a redstone signal as its power supply. The
 * beam director locks onto the nearest drone, missile or hostile aircraft heading into its zone and
 * holds the beam on it until the airframe fails; how long that takes depends on the target (a drone
 * burns through in a second and a half, a hypersonic glide body takes over five). Unlimited magazine,
 * but rain and fog scatter the beam: shorter range, longer dwell.
 */
public class LaserDefenseBlockEntity extends BlockEntity {
	public static final double RANGE = 150.0;
	private static final double RAIN_RANGE = 100.0;
	private static final double DEFENDED_RADIUS = 180.0;
	/** Beam director (elevation axis) height above the block origin. */
	public static final double EMITTER_Y = 3.35;
	private static final float SLEW = 0.35F;

	private @Nullable UUID owner;
	private int kills;
	private int soundTimer;
	private int syncTimer;
	private boolean syncedActive;

	// synced to clients
	private int targetId = -1;
	private float heat;
	private float yaw;
	private float pitch = 0.3F;
	private boolean powered;

	// client-side animation
	public float clientYaw;
	public float clientYawO;
	public float clientPitch = 0.3F;
	public float clientPitchO = 0.3F;

	public LaserDefenseBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.LASER_DEFENSE_BE, pos, state);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, LaserDefenseBlockEntity laser) {
		if (!(level instanceof ServerLevel server)) {
			return;
		}
		DefenseNetwork.register(level, pos, DefenseNetwork.Kind.AIR_DEFENSE);
		laser.powered = level.hasNeighborSignal(pos);
		if (!laser.powered || EmpManager.isJammed(level, pos)) {
			laser.dropTarget();
			laser.finishTick(server);
			return;
		}
		boolean rain = level.isRainingAt(pos.above(3));
		double range = rain ? RAIN_RANGE : RANGE;
		Vec3 emitter = laser.emitter();

		AirThreat target = laser.currentTarget(server, emitter, range);
		if (target == null) {
			laser.dropTarget();
			if (level.getGameTime() % 3 == 0) {
				target = laser.acquire(server, emitter, range);
				if (target != null) {
					laser.targetId = target.asEntity().getId();
				}
			}
		}
		if (target == null) {
			laser.pitch = laser.pitch + Mth.clamp(0.3F - laser.pitch, -0.05F, 0.05F);
			laser.finishTick(server);
			return;
		}

		Vec3 p = target.aimPoint(0);
		Vec3 d = p.subtract(emitter);
		float wantYaw = (float) Mth.atan2(d.x, d.z);
		float wantPitch = (float) Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z));
		laser.yaw = approachAngle(laser.yaw, wantYaw, SLEW);
		laser.pitch = laser.pitch + Mth.clamp(wantPitch - laser.pitch, -SLEW, SLEW);
		float error = Math.abs(Mth.wrapDegrees((laser.yaw - wantYaw) * Mth.RAD_TO_DEG)) + Math.abs(laser.pitch - wantPitch) * Mth.RAD_TO_DEG;
		if (error < 3.0F) {
			laser.heat += 1.0F / dwellTicks(target) / (rain ? 1.6F : 1.0F);
			if (laser.soundTimer-- <= 0) {
				laser.soundTimer = 18;
				level.playSound(null, pos, ModRegistry.LASER_BEAM, SoundSource.BLOCKS, 6.0F, 0.9F + laser.heat * 0.3F);
			}
			if (level.getGameTime() % 2 == 0) {
				server.sendParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 2, 0.2, 0.2, 0.2, 0.01);
			}
			if (laser.heat >= 1.0F) {
				Component name = Component.translatable(target.nameKey());
				target.destroyByInterceptor(server);
				laser.kills++;
				laser.dropTarget();
				laser.setChanged();
				Component msg = Component.literal("✔ ").append(Component.translatable("message.ballisticmissiles.laser_kill", name))
					.withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
				for (ServerPlayer player : server.players()) {
					if (player.position().distanceToSqr(emitter) < 200 * 200) {
						player.displayClientMessage(msg, true);
					}
				}
			}
		}
		laser.finishTick(server);
	}

	/** Beam time needed to burn through the target, in ticks. */
	private static float dwellTicks(AirThreat threat) {
		return switch (threat.threatClass()) {
			case CRUISE -> threat.radarCrossSection() < 0.1 ? 24.0F : 34.0F; // drones go fastest
			case REENTRY -> 45.0F;
			case HYPERSONIC -> 110.0F;
			case AIRCRAFT -> 70.0F;
			default -> threat.radarCrossSection() >= 1.0 ? 80.0F : 55.0F;
		};
	}

	private void dropTarget() {
		this.targetId = -1;
		this.heat = 0.0F;
		this.soundTimer = 0;
	}

	private void finishTick(ServerLevel level) {
		boolean active = this.targetId >= 0;
		if (active && ++this.syncTimer >= 2 || active != this.syncedActive || level.getGameTime() % 40 == 0) {
			this.syncTimer = 0;
			this.syncedActive = active;
			level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	private @Nullable AirThreat currentTarget(ServerLevel level, Vec3 emitter, double range) {
		if (this.targetId >= 0 && level.getEntity(this.targetId) instanceof AirThreat threat && threat.isActiveThreat()
			&& this.engageable(level, threat, emitter, range)) {
			return threat;
		}
		return null;
	}

	private @Nullable AirThreat acquire(ServerLevel level, Vec3 emitter, double range) {
		AirThreat best = null;
		double bestDistance = Double.MAX_VALUE;
		for (AirThreat threat : ThreatTracker.threats(level)) {
			if (!this.engageable(level, threat, emitter, range)) {
				continue;
			}
			double dist = threat.aimPoint(0).distanceTo(emitter);
			if (dist < bestDistance) {
				bestDistance = dist;
				best = threat;
			}
		}
		return best;
	}

	private boolean engageable(ServerLevel level, AirThreat threat, Vec3 emitter, double range) {
		if (DefenseOwner.isFriendly(this.owner, threat)) {
			return false;
		}
		Vec3 p = threat.aimPoint(0);
		double dist = p.distanceTo(emitter);
		if (dist > range || p.y < emitter.y + 2.0) {
			return false;
		}
		Vec3 impact = threat.predictedImpact();
		if (Math.hypot(impact.x - emitter.x, impact.z - emitter.z) > DEFENDED_RADIUS && dist > 50.0) {
			return false;
		}
		var hit = level.clip(new ClipContext(emitter.add(p.subtract(emitter).normalize().scale(1.5)), p, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
			CollisionContext.empty()));
		return hit.getType() == HitResult.Type.MISS;
	}

	public Vec3 emitter() {
		return Vec3.atBottomCenterOf(this.worldPosition).add(0, EMITTER_Y, 0);
	}

	public static void clientTick(Level level, BlockPos pos, BlockState state, LaserDefenseBlockEntity laser) {
		laser.clientYawO = laser.clientYaw;
		laser.clientPitchO = laser.clientPitch;
		laser.clientYaw = approachAngle(laser.clientYaw, laser.yaw, SLEW);
		laser.clientPitch += Mth.clamp(laser.pitch - laser.clientPitch, -SLEW, SLEW);
		Entity target = laser.getTarget();
		if (target != null && laser.heat > 0.0F) {
			// the hot spot on the target throws sparks and molten droplets, then the skin burns through:
			// smoke and flames trail behind it
			Vec3 p = target.position().add(0, target.getBbHeight() * 0.5, 0);
			Vec3 v = target.getDeltaMovement();
			var random = level.getRandom();
			for (int i = 0; i < 2 + (int) (laser.heat * 3); i++) {
				level.addParticle(ModRegistry.SPARK, p.x, p.y, p.z, random.nextGaussian() * 0.2, random.nextGaussian() * 0.2, random.nextGaussian() * 0.2);
			}
			if (random.nextFloat() < laser.heat) {
				level.addParticle(ParticleTypes.LAVA, p.x, p.y, p.z, 0, 0, 0);
			}
			if (laser.heat > 0.3F) {
				level.addParticle(ParticleTypes.LARGE_SMOKE, p.x - v.x * 0.5, p.y - v.y * 0.5, p.z - v.z * 0.5, 0, 0.02, 0);
			}
			if (laser.heat > 0.6F && random.nextFloat() < laser.heat) {
				level.addParticle(ParticleTypes.FLAME, p.x, p.y, p.z, -v.x * 0.2, -v.y * 0.2, -v.z * 0.2);
			}
			// heat shimmer and vapour boiling off the exit window
			Vec3 e = laser.emitter();
			if (random.nextInt(3) == 0) {
				level.addParticle(ParticleTypes.WHITE_ASH, e.x + random.nextGaussian() * 0.3, e.y + 0.4, e.z + random.nextGaussian() * 0.3, 0, 0.02, 0);
			}
		}
	}

	/** Client: the entity the beam is on, if any. */
	public @Nullable Entity getTarget() {
		return this.level == null || this.targetId < 0 ? null : this.level.getEntity(this.targetId);
	}

	public float getHeat() {
		return this.heat;
	}

	public boolean isPowered() {
		return this.powered;
	}

	public void setOwner(@Nullable UUID owner) {
		this.owner = owner;
		this.setChanged();
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
			if (!this.level.hasNeighborSignal(this.worldPosition)) {
				return Component.translatable("message.ballisticmissiles.laser_no_power").withStyle(ChatFormatting.RED);
			}
		}
		return Component.translatable("message.ballisticmissiles.laser_status", (int) RANGE, this.kills).withStyle(ChatFormatting.AQUA);
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
		output.putInt("Kills", this.kills);
		output.putInt("Target", this.targetId);
		output.putFloat("Heat", this.heat);
		output.putFloat("Yaw", this.yaw);
		output.putFloat("Pitch", this.pitch);
		output.putBoolean("Powered", this.powered);
		if (this.owner != null) {
			output.store("Owner", UUIDUtil.CODEC, this.owner);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.kills = input.getIntOr("Kills", 0);
		this.targetId = input.getIntOr("Target", -1);
		this.heat = input.getFloatOr("Heat", 0.0F);
		this.yaw = input.getFloatOr("Yaw", 0.0F);
		this.pitch = input.getFloatOr("Pitch", 0.3F);
		this.powered = input.getBooleanOr("Powered", false);
		this.owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
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
