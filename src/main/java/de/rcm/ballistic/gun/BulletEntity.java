package de.rcm.ballistic.gun;

import de.rcm.ballistic.ModRegistry;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * A 7.62x39 mm bullet in flight: leaves the muzzle at 715 m/s, slows in the air and drops with
 * gravity; hits people (harder in the head), punches through glass, kicks up dust, chips and sparks
 * where it strikes, and glancing off rock or metal it can ricochet, whining away. Tracer rounds burn
 * for the first 800 metres.
 */
public class BulletEntity extends Entity {
	private static final EntityDataAccessor<Boolean> DATA_TRACER = SynchedEntityData.defineId(BulletEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> DATA_SHOOTER = SynchedEntityData.defineId(BulletEntity.class, EntityDataSerializers.INT);
	private static final double GRAVITY = 0.0245;
	private static final double DRAG = 0.988;
	private static final float DAMAGE = 9.0F;
	/** The tracer compound burns out after about 800 m. */
	public static final int TRACER_BURN = 25;

	private @Nullable Entity shooter;
	private int ricochets;
	/** What is left of the round's punch after going through things (1 = straight from the muzzle). */
	private float power = 1.0F;
	/** Client: the near-miss crack has been played. */
	public boolean crackPlayed;
	public Vec3 clientPrev = Vec3.ZERO;

	public BulletEntity(EntityType<? extends BulletEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	public static void fire(ServerLevel level, Entity shooter, Vec3 from, Vec3 velocity, boolean tracer) {
		BulletEntity b = ModRegistry.BULLET.create(level, EntitySpawnReason.TRIGGERED);
		if (b == null) {
			return;
		}
		b.shooter = shooter;
		b.entityData.set(DATA_TRACER, tracer);
		b.entityData.set(DATA_SHOOTER, shooter.getId());
		b.setPos(from);
		b.setDeltaMovement(velocity);
		level.addFreshEntity(b);
		// fly the first tenth of a second (some 70 m) at once, in the same tick as the shot: what is
		// that close is hit now, not a tick or two later
		for (int i = 0; i < 2 && !b.isRemoved(); i++) {
			b.flyServer(level);
		}
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_TRACER, false);
		builder.define(DATA_SHOOTER, -1);
	}

	public boolean isTracer() {
		return this.entityData.get(DATA_TRACER);
	}

	public int shooterId() {
		return this.entityData.get(DATA_SHOOTER);
	}

	public boolean tracerBurning() {
		return this.isTracer() && this.tickCount < TRACER_BURN;
	}

	@Override
	public void tick() {
		super.tick();
		Vec3 pos = this.position();
		Vec3 vel = this.getDeltaMovement();
		Vec3 next = pos.add(vel);
		Level level = this.level();
		if (level.isClientSide()) {
			this.clientPrev = pos;
			de.rcm.ballistic.ClientHooks.bulletClientTick.accept(this);
			this.setDeltaMovement(vel.scale(DRAG).add(0, -GRAVITY, 0));
			this.setPos(next);
			return;
		}
		this.flyServer((ServerLevel) level);
	}

	/** One tick of flight on the server: hits on the way, then the move. */
	private void flyServer(ServerLevel server) {
		Vec3 pos = this.position();
		Vec3 vel = this.getDeltaMovement();
		Vec3 next = pos.add(vel);
		BlockHitResult hit = server.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.WATER, CollisionContext.empty()));
		Vec3 end = hit.getType() == HitResult.Type.MISS ? next : hit.getLocation();
		Entity struck = this.firstHit(server, pos, end);
		if (struck != null) {
			this.hitEntity(server, struck, pos, end);
			return;
		}
		if (hit.getType() == HitResult.Type.BLOCK) {
			if (this.hitBlock(server, hit, vel)) {
				return;
			}
		} else {
			this.setDeltaMovement(vel.scale(DRAG).add(0, -GRAVITY, 0));
			this.setPos(next);
		}
		if (this.tickCount > 80 || this.getY() < server.getMinY() - 16 || vel.lengthSqr() < 1.0) {
			this.discard();
		}
	}

	private @Nullable Entity firstHit(ServerLevel level, Vec3 from, Vec3 to) {
		List<Entity> list = level.getEntities(this, new AABB(from, to).inflate(0.4),
			e -> e.isPickable() && e.isAlive() && !e.isSpectator() && (e != this.shooter || this.tickCount > 3) && !(e instanceof BulletEntity));
		Entity best = null;
		double bestDist = Double.MAX_VALUE;
		for (Entity e : list) {
			var clip = e.getBoundingBox().inflate(0.2).clip(from, to);
			if (clip.isPresent()) {
				double d = clip.get().distanceToSqr(from);
				if (d < bestDist) {
					bestDist = d;
					best = e;
				}
			}
		}
		return best;
	}

	private void hitEntity(ServerLevel level, Entity e, Vec3 from, Vec3 to) {
		Vec3 at = e.getBoundingBox().inflate(0.2).clip(from, to).orElse(e.position());
		float speed = (float) this.getDeltaMovement().length();
		float damage = DAMAGE * Math.min(1.0F, 0.35F + speed / (float) AkItem.MUZZLE_VELOCITY) * (0.25F + 0.75F * this.power);
		boolean head = e instanceof LivingEntity living && at.y > living.getEyeY() - 0.22;
		if (head) {
			damage *= 2.0F;
		}
		DamageSource source = level.damageSources().thrown(this, this.shooter);
		e.invulnerableTime = 0; // rounds of a burst all land
		if (this.shooter instanceof net.minecraft.server.level.ServerPlayer shooterPlayer) {
			// the shooter hears the hit land
			shooterPlayer.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
				net.minecraft.core.Holder.direct(net.minecraft.sounds.SoundEvents.ARROW_HIT_PLAYER), SoundSource.PLAYERS,
				shooterPlayer.getX(), shooterPlayer.getEyeY(), shooterPlayer.getZ(), head ? 0.5F : 0.3F, head ? 1.6F : 1.25F, this.random.nextLong()));
		}
		if (e.hurtServer(level, source, damage)) {
			Vec3 push = this.getDeltaMovement().normalize().scale(0.12);
			e.push(push.x, 0.02, push.z);
		}
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState()), at.x, at.y, at.z, 6, 0.08, 0.08, 0.08, 0.15);
		level.playSound(null, at.x, at.y, at.z, ModRegistry.BULLET_IMPACT_FLESH, SoundSource.PLAYERS, 1.0F, 0.9F + this.random.nextFloat() * 0.2F);
		this.discard();
	}

	/** @return true when the bullet is spent */
	private boolean hitBlock(ServerLevel level, BlockHitResult hit, Vec3 vel) {
		Vec3 at = hit.getLocation();
		BlockState state = level.getBlockState(hit.getBlockPos());
		if (!state.getFluidState().isEmpty()) {
			// into water: a small spout, and it is slowed to nothing within a metre or two
			level.sendParticles(ParticleTypes.SPLASH, at.x, at.y + 0.1, at.z, 10, 0.1, 0.05, 0.1, 0.2);
			level.playSound(null, at.x, at.y, at.z, ModRegistry.BULLET_IMPACT_DIRT, SoundSource.PLAYERS, 0.6F, 1.5F);
			this.discard();
			return true;
		}
		if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE) || state.is(BlockTags.IMPERMEABLE) && !state.is(Blocks.TINTED_GLASS)
			|| state.getBlock() instanceof net.minecraft.world.level.block.StainedGlassPaneBlock) {
			level.destroyBlock(hit.getBlockPos(), false);
			this.setDeltaMovement(vel.scale(0.85));
			this.setPos(at.add(vel.normalize().scale(0.3)));
			return false; // straight through
		}
		Direction face = hit.getDirection();
		Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
		Vec3 dir = vel.normalize();
		// through it: leaves, wool, wood, thin walls and doors - losing punch each time
		float resist = resistance(level, hit.getBlockPos(), state);
		if (resist >= 0.0F && this.power - resist > 0.15F) {
			BlockPos inside = hit.getBlockPos();
			Vec3 exit = at.add(dir.scale(1.8));
			for (int k = 1; k <= 30; k++) {
				Vec3 p = at.add(dir.scale(k * 0.06));
				if (!BlockPos.containing(p).equals(inside) || state.getCollisionShape(level, inside).isEmpty()) {
					exit = p;
					break;
				}
				var shape = state.getCollisionShape(level, inside);
				Vec3 local = p.subtract(inside.getX(), inside.getY(), inside.getZ());
				boolean in = false;
				for (var box : shape.toAabbs()) {
					in |= box.contains(local);
				}
				if (!in) {
					exit = p;
					break;
				}
			}
			this.power -= resist;
			if (resist > 0.06F) {
				int kind = holeKind(state);
				hole(level, at, face, kind, dir);
				Direction out = Direction.getApproximateNearest(dir.x, dir.y, dir.z);
				hole(level, exit.subtract(dir.scale(0.03)), out, kind, dir.scale(-1.0));
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), exit.x, exit.y, exit.z, 6, 0.04, 0.04, 0.04, 0.2);
				level.playSound(null, at.x, at.y, at.z, kind == 1 ? ModRegistry.BULLET_IMPACT_WOOD : ModRegistry.BULLET_IMPACT_DIRT, SoundSource.PLAYERS, 0.8F,
					1.0F + this.random.nextFloat() * 0.2F);
			}
			this.setDeltaMovement(vel.scale(1.0 - resist * 0.5).add(0, -GRAVITY, 0));
			this.setPos(exit.add(dir.scale(0.02)));
			return false;
		}
		double incidence = -dir.dot(n); // 1 = head on, 0 = grazing
		SoundType sound = state.getSoundType();
		boolean hard = sound == SoundType.STONE || sound == SoundType.METAL || sound == SoundType.DEEPSLATE || sound == SoundType.DEEPSLATE_BRICKS
			|| sound == SoundType.ANVIL || sound == SoundType.COPPER || sound == SoundType.NETHERITE_BLOCK || state.getBlock().getExplosionResistance() >= 6.0F;
		boolean metal = sound == SoundType.METAL || sound == SoundType.ANVIL || sound == SoundType.COPPER || sound == SoundType.NETHERITE_BLOCK;
		// the impact: chips and dust of what it hit, sparks off metal and rock
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), at.x, at.y, at.z, 8, 0.05, 0.05, 0.05, 0.25);
		level.sendParticles(ParticleTypes.SMOKE, at.x + n.x * 0.1, at.y + n.y * 0.1, at.z + n.z * 0.1, 2, 0.05, 0.05, 0.05, 0.02);
		if (hard) {
			level.sendParticles(ParticleTypes.CRIT, at.x + n.x * 0.05, at.y + n.y * 0.05, at.z + n.z * 0.05, metal ? 6 : 3, 0.02, 0.02, 0.02, 0.4);
		}
		SoundEvent impact = metal ? ModRegistry.BULLET_IMPACT_METAL
			: hard ? ModRegistry.BULLET_IMPACT_STONE
			: sound == SoundType.WOOD || sound == SoundType.NETHER_WOOD || sound == SoundType.BAMBOO_WOOD || sound == SoundType.CHERRY_WOOD
				? ModRegistry.BULLET_IMPACT_WOOD : ModRegistry.BULLET_IMPACT_DIRT;
		level.playSound(null, at.x, at.y, at.z, impact, SoundSource.PLAYERS, 1.2F, 0.9F + this.random.nextFloat() * 0.2F);
		hole(level, at, face, holeKind(state), dir);
		// glancing off something hard it skips away, tumbling and whining
		// (tracers, lighter at the base and spinning hard, skip off almost anything at a flat enough angle)
		if ((hard && incidence < 0.35 || this.isTracer() && incidence < 0.2) && this.ricochets < 3 && this.random.nextFloat() < 0.7F) {
			this.ricochets++;
			Vec3 out = dir.subtract(n.scale(2.0 * dir.dot(n)));
			out = out.add(this.random.nextGaussian() * 0.15, Math.abs(this.random.nextGaussian()) * 0.1, this.random.nextGaussian() * 0.15).normalize();
			this.setDeltaMovement(out.scale(vel.length() * 0.45));
			this.setPos(at.add(n.scale(0.05)));
			level.playSound(null, at.x, at.y, at.z, ModRegistry.BULLET_RICOCHET, SoundSource.PLAYERS, 1.6F, 0.85F + this.random.nextFloat() * 0.3F);
			return false;
		}
		this.discard();
		return true;
	}

	/**
	 * How much of its punch a 7.62x39 round loses going through this block, or -1 if it stops it:
	 * leaves next to nothing, wool and snow little, thin wooden things (doors, trapdoors, fences,
	 * slabs) some, a whole wooden block or log most of it, thin sheet metal nearly all; stone, earth
	 * and solid metal stop it.
	 */
	private static float resistance(ServerLevel level, BlockPos pos, BlockState state) {
		if (state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
			return 0.04F;
		}
		SoundType sound = state.getSoundType();
		boolean full = state.isCollisionShapeFullBlock(level, pos);
		if (sound == SoundType.WOOL) {
			return 0.12F;
		}
		if (sound == SoundType.SNOW || sound == SoundType.POWDER_SNOW) {
			return 0.2F;
		}
		if (state.is(Blocks.HAY_BLOCK)) {
			return 0.3F;
		}
		if (state.is(Blocks.IRON_BARS)) {
			return 0.15F;
		}
		boolean wood = sound == SoundType.WOOD || sound == SoundType.NETHER_WOOD || sound == SoundType.BAMBOO_WOOD || sound == SoundType.CHERRY_WOOD
			|| sound == SoundType.BAMBOO || sound == SoundType.SCAFFOLDING || sound == SoundType.LADDER;
		if (wood) {
			return full ? 0.6F : 0.25F;
		}
		if (!full && (state.is(Blocks.IRON_DOOR) || state.is(Blocks.IRON_TRAPDOOR))) {
			return 0.75F;
		}
		return -1.0F;
	}

	/** Which bullet hole the block shows: 0 rock/concrete, 1 wood, 2 metal, 3 earth. */
	public static int holeKind(BlockState state) {
		SoundType sound = state.getSoundType();
		if (sound == SoundType.WOOD || sound == SoundType.NETHER_WOOD || sound == SoundType.BAMBOO_WOOD || sound == SoundType.CHERRY_WOOD
			|| sound == SoundType.BAMBOO || sound == SoundType.SCAFFOLDING || sound == SoundType.LADDER) {
			return 1;
		}
		if (sound == SoundType.METAL || sound == SoundType.ANVIL || sound == SoundType.COPPER || sound == SoundType.NETHERITE_BLOCK
			|| sound == SoundType.IRON) {
			return 2;
		}
		if (sound == SoundType.GRAVEL || sound == SoundType.SAND || sound == SoundType.GRASS || sound == SoundType.ROOTED_DIRT || sound == SoundType.MUD
			|| sound == SoundType.SNOW || sound == SoundType.WOOL || sound == SoundType.SOUL_SAND || sound == SoundType.SOUL_SOIL) {
			return 3;
		}
		return 0;
	}

	/** A bullet hole on that face, for everyone near enough to see it. */
	private static void hole(ServerLevel level, Vec3 at, Direction face, int kind, Vec3 dir) {
		var payload = new de.rcm.ballistic.network.ModNetworking.BulletHolePayload(at.x, at.y, at.z, face.get3DDataValue(), kind,
			(float) dir.x, (float) dir.y, (float) dir.z);
		for (var player : level.players()) {
			if (player.position().distanceToSqr(at) < 96.0 * 96.0) {
				net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
			}
		}
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 768 * 768;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
	}
}
