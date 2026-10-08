package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.defense.DefenseOwner;
import de.rcm.ballistic.defense.EmpManager;
import de.rcm.ballistic.defense.ThreatTracker;
import de.rcm.ballistic.entity.MissileEntity;
import de.rcm.ballistic.entity.RocketEntity;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Decoy launcher: when a guided weapon (cruise missile, drone, anti-radar missile, Hellfire) comes in
 * on a target close to it, it fires a salvo of chaff, flares and an infrared/radar decoy that drifts
 * away - and the seeker often follows the decoy instead. Ballistic warheads are too fast and dumb
 * to be fooled.
 */
public class DecoyLauncherBlockEntity extends BlockEntity implements DefenseSiteBlock.Site {
	public static final double PROTECT_RADIUS = 60.0;
	public static final int MAGAZINE = 6;
	private static final int RELOAD_TICKS = 200;

	private int salvos = MAGAZINE;
	private int reload;
	private int cooldown;
	private int fooled;
	private final Set<Integer> tried = new HashSet<>();
	private @Nullable UUID owner;

	public DecoyLauncherBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.DECOY_LAUNCHER_BE, pos, state);
	}

	@Override
	public void serverTick(ServerLevel level) {
		if (this.salvos < MAGAZINE && ++this.reload >= RELOAD_TICKS) {
			this.reload = 0;
			this.salvos++;
			this.sync();
		}
		if (this.cooldown > 0) {
			this.cooldown--;
			return;
		}
		if (this.salvos <= 0 || level.getGameTime() % 4 != 0 || EmpManager.isJammed(level, this.worldPosition)) {
			return;
		}
		Vec3 here = Vec3.atCenterOf(this.worldPosition);
		for (AirThreat threat : ThreatTracker.threats(level)) {
			int id = threat.asEntity().getId();
			if (this.tried.contains(id) || DefenseOwner.isFriendly(this.owner, threat)) {
				continue;
			}
			boolean guided = threat instanceof RocketEntity r && r.getKind() == RocketEntity.Kind.HELLFIRE
				|| threat instanceof MissileEntity m && m.getMissileType().isCruise();
			if (!guided) {
				continue;
			}
			Vec3 impact = threat.predictedImpact();
			double d = threat.asEntity().position().distanceTo(here);
			if (Math.hypot(impact.x - here.x, impact.z - here.z) > PROTECT_RADIUS || d > 110.0 || d < 12.0) {
				continue;
			}
			this.fire(level, here, threat);
			return;
		}
	}

	private void fire(ServerLevel level, Vec3 here, AirThreat threat) {
		RandomSource random = level.getRandom();
		this.tried.add(threat.asEntity().getId());
		if (this.tried.size() > 64) {
			this.tried.clear();
		}
		this.salvos--;
		this.cooldown = 30;
		this.sync();
		// the decoy is thrown off to one side; chaff and flares bloom above the launcher
		double a = random.nextDouble() * Mth.TWO_PI;
		double r = 30.0 + random.nextDouble() * 18.0;
		int x = Mth.floor(here.x + Math.cos(a) * r);
		int z = Mth.floor(here.z + Math.sin(a) * r);
		int y = level.hasChunk(x >> 4, z >> 4) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : (int) here.y;
		Vec3 decoy = new Vec3(x + 0.5, y, z + 0.5);
		Vec3 burst = here.add(0, 14, 0);
		level.sendParticles(ParticleTypes.FIREWORK, burst.x, burst.y, burst.z, 60, 4.0, 3.0, 4.0, 0.15);
		level.sendParticles(ParticleTypes.FLAME, burst.x, burst.y, burst.z, 40, 3.0, 2.0, 3.0, 0.05);
		level.sendParticles(ParticleTypes.WHITE_ASH, burst.x, burst.y, burst.z, 200, 6.0, 4.0, 6.0, 0.02);
		level.sendParticles(ParticleTypes.CLOUD, here.x, here.y + 1, here.z, 25, 0.6, 0.4, 0.6, 0.1);
		level.playSound(null, here.x, here.y, here.z, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.BLOCKS, 6.0F, 0.6F);
		level.playSound(null, here.x, here.y, here.z, ModRegistry.SAM_LAUNCH, SoundSource.BLOCKS, 5.0F, 1.9F);
		float chance = threat instanceof RocketEntity ? 0.4F : 0.6F;
		if (random.nextFloat() < chance) {
			boolean took = threat instanceof MissileEntity m ? m.decoy(decoy) : threat instanceof RocketEntity rocket && rocket.decoy(decoy);
			if (took) {
				this.fooled++;
				Component msg = Component.literal("✦ ").append(Component.translatable("message.ballisticmissiles.decoy_success", Component.translatable(threat.nameKey())))
					.withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
				for (ServerPlayer player : level.players()) {
					if (player.position().distanceToSqr(here) < 160 * 160) {
						player.displayClientMessage(msg, true);
					}
				}
			}
		}
	}

	@Override
	public Component status() {
		return Component.translatable("message.ballisticmissiles.decoy_status", this.salvos, MAGAZINE, (int) PROTECT_RADIUS, this.fooled).withStyle(ChatFormatting.AQUA);
	}

	@Override
	public void setOwner(@Nullable UUID owner) {
		this.owner = owner;
		this.setChanged();
	}

	// ------------------------------------------------------------------ tube geometry (shared with the renderer)

	public static final float TUBE_LENGTH = 1.5F;
	public static final float TUBE_RADIUS = 0.14F;
	/** Tube {yaw, elevation} in degrees, in firing order. */
	public static final float[][] TUBES = {{-28, 45}, {28, 45}, {-28, 55}, {28, 55}, {-28, 65}, {28, 65}};

	/** Unit direction of a tube in launcher space (+Z = facing). */
	public static Vec3 tubeAxis(int i) {
		float yaw = TUBES[i][0] * Mth.DEG_TO_RAD;
		float el = TUBES[i][1] * Mth.DEG_TO_RAD;
		return new Vec3(Mth.sin(yaw) * Mth.cos(el), Mth.sin(el), Mth.cos(yaw) * Mth.cos(el));
	}

	/** Breech of a tube in launcher space. */
	public static Vec3 tubeBreech(int i) {
		float[] t = TUBES[i];
		return new Vec3(Math.signum(t[0]) * 0.32F, 0.95F + (t[1] - 45.0F) * 0.006F, -0.15F + (t[1] - 45.0F) * 0.012F);
	}

	private Vec3 toWorld(Vec3 p) {
		net.minecraft.core.Direction facing = this.getBlockState().hasProperty(DefenseSiteBlock.FACING)
			? this.getBlockState().getValue(DefenseSiteBlock.FACING) : net.minecraft.core.Direction.NORTH;
		double fx = facing.getStepX();
		double fz = facing.getStepZ();
		return new Vec3(p.x * fz + p.z * fx, p.y, -p.x * fx + p.z * fz);
	}

	/** Client: ticks since each tube's cap was blown off (-1 = not flying). */
	public final int[] popAge = {-1, -1, -1, -1, -1, -1};
	private int clientLastSalvos = -1;

	/**
	 * Client: a salvo leaves its tube - the cap is blown off, a flash and a puff of smoke at the
	 * muzzle, and the round arcs away trailing sparks before it bursts into chaff and flares.
	 */
	@Override
	public void clientTick() {
		if (this.level == null) {
			return;
		}
		if (this.clientLastSalvos >= 0 && this.salvos < this.clientLastSalvos) {
			for (int a = this.clientLastSalvos; a > this.salvos; a--) {
				int tube = MAGAZINE - a;
				if (tube < 0 || tube >= MAGAZINE) {
					continue;
				}
				this.popAge[tube] = 0;
				Vec3 axis = this.toWorld(tubeAxis(tube));
				Vec3 muzzle = Vec3.atBottomCenterOf(this.worldPosition).add(this.toWorld(tubeBreech(tube).add(tubeAxis(tube).scale(TUBE_LENGTH))));
				RandomSource random = this.level.getRandom();
				for (int i = 0; i < 8; i++) {
					this.level.addParticle(ParticleTypes.FLAME, muzzle.x, muzzle.y, muzzle.z, axis.x * 0.3 + random.nextGaussian() * 0.05, axis.y * 0.3, axis.z * 0.3 + random.nextGaussian() * 0.05);
					this.level.addParticle(ParticleTypes.LARGE_SMOKE, muzzle.x, muzzle.y, muzzle.z, axis.x * 0.15, axis.y * 0.15, axis.z * 0.15);
				}
				for (int i = 0; i < 24; i++) {
					// the round's spark trail along its arc
					double s = 0.6 + i * 0.05;
					this.level.addParticle(ParticleTypes.FIREWORK, muzzle.x, muzzle.y, muzzle.z, axis.x * s, axis.y * s, axis.z * s);
				}
			}
		}
		this.clientLastSalvos = this.salvos;
		for (int i = 0; i < this.popAge.length; i++) {
			if (this.popAge[i] >= 0 && ++this.popAge[i] > 40) {
				this.popAge[i] = -1;
			}
		}
	}

	public int getAmmo() {
		return this.salvos;
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
		output.putInt("Salvos", this.salvos);
		output.putInt("Reload", this.reload);
		output.putInt("Fooled", this.fooled);
		if (this.owner != null) {
			output.store("Owner", UUIDUtil.CODEC, this.owner);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.salvos = input.getIntOr("Salvos", MAGAZINE);
		this.reload = input.getIntOr("Reload", 0);
		this.fooled = input.getIntOr("Fooled", 0);
		this.owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
	}
}
