package de.rcm.ballistic.entity;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.defense.DefenseOwner;
import de.rcm.ballistic.defense.ThreatTracker;
import de.rcm.ballistic.item.TargetData;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Guided-missile destroyer (Arleigh Burke style), about 34 blocks long. It floats on the sea and
 * sails: give it a waypoint on the water with the target designator (sneak + right-click), and it
 * steams there at up to about 30 knots, turning like a ship, stopping before it runs aground.
 * Right-click with a designator holding a target on land and it fires a Tomahawk salvo out of its
 * vertical launch cells. Its own Phalanx guards it against anything coming at it. Sixteen cells,
 * reloaded slowly at sea. A heavy hit (or several) sinks it.
 */
public class DestroyerEntity extends Entity {
	public static final int CELLS = 16;
	private static final double MAX_SPEED = 0.7;
	private static final float TURN_RATE = 0.012F;
	private static final double CIWS_RANGE = 110.0;
	private static final int RELOAD_TICKS = 600;
	/** Half the hull length, for keeping the bow and stern in water. */
	public static final double HALF_LENGTH = 16.0;

	private static final EntityDataAccessor<Float> DATA_YAW = SynchedEntityData.defineId(DestroyerEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_SPEED = SynchedEntityData.defineId(DestroyerEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Integer> DATA_CELLS = SynchedEntityData.defineId(DestroyerEntity.class, EntityDataSerializers.INT);
	/** Entity the Phalanx is shooting at, or -1. */
	private static final EntityDataAccessor<Integer> DATA_CIWS = SynchedEntityData.defineId(DestroyerEntity.class, EntityDataSerializers.INT);

	private @Nullable Vec3 waypoint;
	private @Nullable UUID owner;
	private float health = 400.0F;
	private int reload;
	private int salvoLeft;
	private @Nullable BlockPos salvoTarget;
	private int salvoTimer;

	// client
	public float clientYawO;

	public DestroyerEntity(EntityType<? extends DestroyerEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_YAW, 0.0F);
		builder.define(DATA_SPEED, 0.0F);
		builder.define(DATA_CELLS, CELLS);
		builder.define(DATA_CIWS, -1);
	}

	/** Heading in radians (0 = +Z, like the renderers' rotationY(atan2(x, z))). */
	public float getHeading() {
		return this.entityData.get(DATA_YAW);
	}

	public void setHeading(float yaw) {
		this.entityData.set(DATA_YAW, yaw);
	}

	public float getSpeed() {
		return this.entityData.get(DATA_SPEED);
	}

	public int getCells() {
		return this.entityData.get(DATA_CELLS);
	}

	public int getCiwsTarget() {
		return this.entityData.get(DATA_CIWS);
	}

	public void setOwner(@Nullable UUID owner) {
		this.owner = owner;
	}

	private Vec3 forward() {
		float yaw = this.getHeading();
		return new Vec3(Mth.sin(yaw), 0, Mth.cos(yaw));
	}

	/** Water surface height at a column, or NaN if there is no open water there. */
	private double surface(Level level, double x, double z) {
		int bx = Mth.floor(x);
		int bz = Mth.floor(z);
		if (!level.hasChunk(bx >> 4, bz >> 4)) {
			return Double.NaN;
		}
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) - 1;
		FluidState fluid = level.getFluidState(new BlockPos(bx, top, bz));
		if (fluid.isEmpty() || !level.getFluidState(new BlockPos(bx, top - 2, bz)).isSource()) {
			return Double.NaN; // land, or water too shallow for the keel
		}
		return top + fluid.getHeight(level, new BlockPos(bx, top, bz));
	}

	@Override
	public void tick() {
		super.tick();
		this.clientYawO = this.getHeading();
		Level level = this.level();
		if (!(level instanceof ServerLevel server)) {
			this.clientEffects();
			return;
		}
		server.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(this.blockPosition()), 2);
		this.steer(server);
		this.ciws(server);
		this.salvo(server);
		if (this.getCells() < CELLS && ++this.reload >= RELOAD_TICKS) {
			this.reload = 0;
			this.entityData.set(DATA_CELLS, this.getCells() + 1);
		}
	}

	private void steer(ServerLevel level) {
		float speed = this.getSpeed();
		float yaw = this.getHeading();
		double want = 0.0;
		if (this.waypoint != null) {
			Vec3 to = this.waypoint.subtract(this.position());
			double dist = Math.hypot(to.x, to.z);
			if (dist < 6.0) {
				this.waypoint = null;
			} else {
				float target = (float) Mth.atan2(to.x, to.z);
				float diff = Mth.wrapDegrees((target - yaw) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD;
				// a ship turns only while it makes way, and slows down for a hard turn
				yaw += Mth.clamp(diff, -TURN_RATE, TURN_RATE) * Mth.clamp(speed / 0.25F, 0.15F, 1.0F);
				want = Math.min(MAX_SPEED, dist / 60.0) * (Math.abs(diff) > 1.2F ? 0.5 : 1.0);
			}
		}
		// keep the bow in deep water: look ahead and stop before running aground
		Vec3 ahead = this.position().add(this.forward().scale(HALF_LENGTH + 4.0 + speed * 30.0));
		if (want > 0 && Double.isNaN(this.surface(level, ahead.x, ahead.z))) {
			want = 0.0;
			this.waypoint = null;
		}
		speed += Mth.clamp((float) want - speed, -0.004F, 0.006F); // thousands of tonnes: slow to speed up, slower to stop
		this.entityData.set(DATA_SPEED, speed);
		this.setHeading(yaw);
		Vec3 next = this.position().add(this.forward().scale(speed));
		double y = this.surface(level, next.x, next.z);
		if (Double.isNaN(y)) {
			y = this.getY();
			next = this.position();
		}
		this.setPos(next.x, y - 0.6, next.z); // the waterline sits a little below the deck edge
	}

	/** The Phalanx: engages anything about to hit near the ship. */
	private void ciws(ServerLevel level) {
		Vec3 mount = this.position().add(0, 7.0, 0);
		AirThreat best = null;
		double bestDist = CIWS_RANGE;
		for (AirThreat threat : ThreatTracker.threats(level)) {
			Entity e = threat.asEntity();
			if (!threat.isActiveThreat() || DefenseOwner.isFriendly(this.owner, threat)) {
				continue;
			}
			double d = e.position().distanceTo(mount);
			if (d < bestDist && threat.predictedImpact().distanceTo(this.position()) < 45.0) {
				bestDist = d;
				best = threat;
			}
		}
		int id = best == null ? -1 : best.asEntity().getId();
		if (id != this.getCiwsTarget()) {
			this.entityData.set(DATA_CIWS, id);
		}
		if (best == null) {
			return;
		}
		if (level.getGameTime() % 4 == 0) {
			level.playSound(null, mount.x, mount.y, mount.z, ModRegistry.CIWS_FIRE, SoundSource.BLOCKS, 10.0F, 0.95F + level.getRandom().nextFloat() * 0.1F);
		}
		// every tick of fire has a chance to tear it apart, better the closer it gets
		float chance = (float) (0.04 + 0.16 * (1.0 - bestDist / CIWS_RANGE)) * best.killProbability();
		if (level.getRandom().nextFloat() < chance) {
			best.destroyByInterceptor(level);
			this.entityData.set(DATA_CIWS, -1);
		}
	}

	/** Fires a Tomahawk salvo at {@code target}: four rounds, a few seconds apart. */
	public boolean fireSalvo(BlockPos target) {
		if (this.getCells() <= 0) {
			return false;
		}
		this.salvoTarget = target;
		this.salvoLeft = Math.min(4, this.getCells());
		this.salvoTimer = 0;
		return true;
	}

	private void salvo(ServerLevel level) {
		if (this.salvoLeft <= 0 || this.salvoTarget == null || --this.salvoTimer > 0) {
			return;
		}
		this.salvoTimer = 30;
		int fired = CELLS - this.getCells();
		Vec3 cell = this.cellPosition(fired);
		MissileEntity missile = ModRegistry.missileEntity(MissileType.CRUISE).create(level, EntitySpawnReason.TRIGGERED);
		if (missile == null) {
			return;
		}
		missile.setPos(cell);
		level.addFreshEntity(missile);
		var random = level.getRandom();
		BlockPos aim = this.salvoTarget.offset(random.nextInt(7) - 3, 0, random.nextInt(7) - 3);
		missile.launchNow(new TargetData(aim, false));
		this.entityData.set(DATA_CELLS, this.getCells() - 1);
		this.salvoLeft--;
		// hot launch: the booster's blast vents up the uptake beside the cell
		level.sendParticles(ParticleTypes.LARGE_SMOKE, cell.x, cell.y + 1, cell.z, 40, 0.6, 1.0, 0.6, 0.08);
		level.sendParticles(ParticleTypes.FLAME, cell.x, cell.y + 0.5, cell.z, 20, 0.3, 0.3, 0.3, 0.05);
		level.playSound(null, cell.x, cell.y, cell.z, ModRegistry.SAM_LAUNCH, SoundSource.BLOCKS, 10.0F, 0.8F);
	}

	/** World position of a VLS cell's hatch (cells 0-7 forward, 8-15 aft), in launch order. */
	public Vec3 cellPosition(int cell) {
		double along = cell < 8 ? 8.6 + (cell % 4) * 0.7 : -8.6 - (cell % 4) * 0.7;
		double side = (cell % 8 < 4 ? -0.4 : 0.4);
		Vec3 f = this.forward();
		Vec3 r = new Vec3(f.z, 0, -f.x);
		return this.position().add(f.scale(along)).add(r.scale(side)).add(0, 3.0, 0);
	}

	private void clientEffects() {
		float speed = this.getSpeed();
		if (speed < 0.05F) {
			return;
		}
		Vec3 f = this.forward();
		Vec3 r = new Vec3(f.z, 0, -f.x);
		Vec3 bow = this.position().add(f.scale(HALF_LENGTH + 1.0)).add(0, 0.8, 0);
		Vec3 stern = this.position().add(f.scale(-HALF_LENGTH)).add(0, 0.8, 0);
		var random = this.random;
		// bow wave curling off both sides, the churned wake astern
		for (int i = 0; i < 2 + (int) (speed * 6); i++) {
			double s = random.nextBoolean() ? 1 : -1;
			Vec3 p = bow.add(r.scale(s * (0.8 + random.nextDouble())));
			this.level().addParticle(ParticleTypes.SPLASH, p.x, p.y, p.z, r.x * s * 0.3, 0.2, r.z * s * 0.3);
		}
		for (int i = 0; i < 3 + (int) (speed * 8); i++) {
			Vec3 p = stern.add(r.scale((random.nextDouble() - 0.5) * 3.0));
			this.level().addParticle(ParticleTypes.BUBBLE_POP, p.x, p.y, p.z, -f.x * 0.1, 0.05, -f.z * 0.1);
			if (random.nextInt(3) == 0) {
				this.level().addParticle(ParticleTypes.CLOUD, p.x, p.y, p.z, -f.x * 0.05, 0.01, -f.z * 0.05);
			}
		}
		if (random.nextInt(3) == 0) {
			// gas-turbine exhaust from the funnels
			Vec3 funnel = this.position().add(f.scale(random.nextBoolean() ? 1.5 : -3.5)).add(0, 11.5, 0);
			this.level().addParticle(ParticleTypes.SMOKE, funnel.x, funnel.y, funnel.z, 0, 0.05, 0);
		}
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand) {
		if (!(this.level() instanceof ServerLevel)) {
			return InteractionResult.SUCCESS;
		}
		ItemStack stack = player.getItemInHand(hand);
		if (stack.is(ModRegistry.TARGET_DESIGNATOR)) {
			TargetData target = stack.get(ModRegistry.TARGET);
			if (target == null) {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.no_target").withStyle(ChatFormatting.YELLOW), true);
				return InteractionResult.SUCCESS;
			}
			Vec3 at = Vec3.atBottomCenterOf(target.pos());
			if (player.isShiftKeyDown()) {
				this.waypoint = at;
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.ship_course", (int) at.distanceTo(this.position()))
					.withStyle(ChatFormatting.AQUA), true);
			} else if (this.fireSalvo(target.pos())) {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.ship_salvo", Math.min(4, this.getCells()))
					.withStyle(ChatFormatting.RED, ChatFormatting.BOLD), true);
			} else {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.ship_empty").withStyle(ChatFormatting.YELLOW), true);
			}
			return InteractionResult.SUCCESS;
		}
		if (stack.isEmpty()) {
			if (player.isShiftKeyDown()) {
				this.waypoint = null;
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.ship_stop").withStyle(ChatFormatting.GRAY), true);
			} else {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.ship_status", this.getCells(), CELLS,
					Math.round(this.getSpeed() * 20 * 1.94F), (int) this.health).withStyle(ChatFormatting.AQUA), true);
			}
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (!source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION) || amount < 4.0F) {
			return false;
		}
		this.health -= amount;
		if (this.health <= 0.0F) {
			// magazine fire and a broken back: the ship goes down
			Vec3 p = this.position();
			de.rcm.ballistic.explosion.Blasts.explode(level, this, p.x, p.y + 3, p.z, 6.0F, true, Level.ExplosionInteraction.NONE);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, p.x, p.y + 4, p.z, 200, 6.0, 3.0, 6.0, 0.05);
			level.playSound(null, p.x, p.y, p.z, ModRegistry.EXPLOSION_NEAR, SoundSource.BLOCKS, 10.0F, 0.85F);
			this.discard();
		}
		return true;
	}

	@Override
	public boolean isPickable() {
		return true;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 512 * 512;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putFloat("Heading", this.getHeading());
		output.putInt("Cells", this.getCells());
		output.putFloat("Health", this.health);
		if (this.owner != null) {
			output.store("Owner", UUIDUtil.CODEC, this.owner);
		}
		if (this.waypoint != null) {
			output.putDouble("WX", this.waypoint.x);
			output.putDouble("WZ", this.waypoint.z);
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		this.setHeading(input.getFloatOr("Heading", 0.0F));
		this.entityData.set(DATA_CELLS, input.getIntOr("Cells", CELLS));
		this.health = input.getFloatOr("Health", 400.0F);
		this.owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
		if (input.getDoubleOr("WX", Double.NaN) == input.getDoubleOr("WX", Double.NaN)) {
			this.waypoint = new Vec3(input.getDoubleOr("WX", 0), this.getY(), input.getDoubleOr("WZ", 0));
		}
	}
}
