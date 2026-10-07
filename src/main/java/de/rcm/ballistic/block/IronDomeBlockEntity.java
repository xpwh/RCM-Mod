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
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Iron Dome battery: a 20-round vertical launcher made for saturation attacks. It covers a smaller
 * area than the Patriot-style battery, but fires every few ticks and engages many threats at once -
 * rockets, drones, cruise missiles, Hellfires - each with one Tamir interceptor (two for nukes).
 * Threats that will land outside its protected zone are ignored to save rounds.
 */
public class IronDomeBlockEntity extends BlockEntity implements DefenseSiteBlock.Site {
	public static final double RANGE = 170.0;
	public static final double CEILING = 260.0;
	public static final int MAGAZINE = 20;
	private static final int RELOAD_TICKS = 40;
	private static final int FIRE_GAP = 4;

	private int ammo = MAGAZINE;
	private int reload;
	private int cooldown;
	private int launches;
	private @Nullable UUID owner;

	public IronDomeBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.IRON_DOME_BE, pos, state);
	}

	@Override
	public void serverTick(ServerLevel level) {
		DefenseNetwork.register(level, this.worldPosition, DefenseNetwork.Kind.AIR_DEFENSE);
		if (this.ammo < MAGAZINE && ++this.reload >= RELOAD_TICKS) {
			this.reload = 0;
			this.ammo++;
			this.sync();
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
		// vertical launch, the interceptor turns over towards the target right after leaving the cell
		int cell = this.launches % 20;
		net.minecraft.core.Direction facing = this.getBlockState().hasProperty(DefenseSiteBlock.FACING)
			? this.getBlockState().getValue(DefenseSiteBlock.FACING) : net.minecraft.core.Direction.NORTH;
		Vec3 fwd = new Vec3(facing.getStepX(), 0, facing.getStepZ());
		Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
		Vec3 mouth = Vec3.atBottomCenterOf(this.worldPosition).add(fwd.scale(0.4 + (cell / 4) * 0.05)).add(right.scale((cell % 4 - 1.5) * 0.5)).add(0, 4.2 + (cell / 4) * 0.2, 0);
		float pk = Math.min(0.95F, best.killProbability() + 0.15F);
		if (InterceptorEntity.launch(level, this.worldPosition, mouth, best, pk, new Vec3(0, 1, 0)) != null) {
			best.setEngagements(best.getEngagements() + 1);
			this.ammo--;
			this.launches++;
			this.cooldown = FIRE_GAP;
			this.sync();
			level.playSound(null, mouth.x, mouth.y, mouth.z, ModRegistry.SAM_LAUNCH, SoundSource.BLOCKS, 9.0F, 1.15F + level.getRandom().nextFloat() * 0.15F);
			level.sendParticles(ParticleTypes.CLOUD, mouth.x, mouth.y, mouth.z, 20, 0.4, 0.3, 0.4, 0.06);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, here.x, here.y + 0.5, here.z, 10, 0.8, 0.2, 0.8, 0.04);
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
		return Component.translatable("message.ballisticmissiles.iron_dome_status", this.ammo, MAGAZINE, (int) RANGE, this.launches).withStyle(ChatFormatting.AQUA);
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
		output.putInt("Reload", this.reload);
		output.putInt("Launches", this.launches);
		if (this.owner != null) {
			output.store("Owner", UUIDUtil.CODEC, this.owner);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.ammo = input.getIntOr("Ammo", MAGAZINE);
		this.reload = input.getIntOr("Reload", 0);
		this.launches = input.getIntOr("Launches", 0);
		this.owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
	}
}
