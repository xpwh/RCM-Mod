package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.DefenseNetwork;
import de.rcm.ballistic.defense.EmpManager;
import de.rcm.ballistic.entity.MissileEntity;
import de.rcm.ballistic.entity.MissileType;
import de.rcm.ballistic.item.TargetData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Underground launch silo. Holds one missile of any size. On launch the hatch opens and the missile
 * is ejected cold by a gas generator; its engine only lights once it is clear of the shaft.
 */
public class MissileSiloBlockEntity extends BlockEntity {
	private static final int HATCH_OPEN_BEFORE = 70;

	private @Nullable MissileType missile;
	private boolean counting;
	private int age;
	private @Nullable TargetData target;
	private int hatchTimer;

	public MissileSiloBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.MISSILE_SILO_BE, pos, state);
	}

	public @Nullable MissileType getMissile() {
		return this.missile;
	}

	public boolean isCounting() {
		return this.counting;
	}

	public boolean load(MissileType type) {
		if (this.missile != null || this.counting) {
			return false;
		}
		this.missile = type;
		this.setChanged();
		return true;
	}

	public @Nullable MissileType unload() {
		if (this.counting) {
			return null;
		}
		MissileType type = this.missile;
		this.missile = null;
		this.setChanged();
		return type;
	}

	/** Arms the silo; returns a message describing the outcome. */
	public Component arm(@Nullable ServerPlayer player, TargetData target) {
		if (this.missile == null) {
			return Component.translatable("message.ballisticmissiles.silo_empty").withStyle(ChatFormatting.YELLOW);
		}
		if (this.counting) {
			return Component.translatable("message.ballisticmissiles.remote_busy").withStyle(ChatFormatting.YELLOW);
		}
		if (this.level != null && EmpManager.isJammed(this.level, this.worldPosition)) {
			return Component.translatable("message.ballisticmissiles.jammed", EmpManager.jammedTicks(this.level, this.worldPosition) / 20)
				.withStyle(ChatFormatting.DARK_PURPLE);
		}
		BlockPos t = target.pos();
		double dx = t.getX() - this.worldPosition.getX();
		double dz = t.getZ() - this.worldPosition.getZ();
		if (dx * dx + dz * dz < MissileEntity.MIN_RANGE * MissileEntity.MIN_RANGE) {
			return Component.translatable("message.ballisticmissiles.too_close", MissileEntity.MIN_RANGE).withStyle(ChatFormatting.RED);
		}
		this.target = target;
		this.counting = true;
		this.age = 0;
		this.setChanged();
		return Component.translatable("message.ballisticmissiles.armed", target.describe(), (int) Math.sqrt(dx * dx + dz * dz))
			.withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
	}

	public boolean abort() {
		if (!this.counting) {
			return false;
		}
		this.counting = false;
		this.age = 0;
		this.setHatch(false);
		this.setChanged();
		return true;
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, MissileSiloBlockEntity silo) {
		if (!(level instanceof ServerLevel server)) {
			return;
		}
		DefenseNetwork.register(level, pos, DefenseNetwork.Kind.SILO);
		if (silo.hatchTimer > 0 && --silo.hatchTimer == 0 && !silo.counting) {
			silo.setHatch(false);
		}
		if (!silo.counting || silo.missile == null || silo.target == null) {
			return;
		}
		server.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(pos), 2);
		if (EmpManager.isJammed(level, pos)) {
			silo.abort();
			silo.broadcast(server, Component.translatable("message.ballisticmissiles.jammed", EmpManager.jammedTicks(level, pos) / 20)
				.withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD));
			return;
		}
		silo.target = MissileEntity.resolveSurface(server, silo.target);

		int total = silo.missile.countdownTicks;
		int remaining = total - silo.age;
		Vec3 top = Vec3.atBottomCenterOf(pos.above());
		if (silo.age % 20 == 0) {
			level.playSound(null, top.x, top.y, top.z, ModRegistry.COUNTDOWN_BEEP, SoundSource.BLOCKS, 3.0F, remaining <= 60 ? 1.4F : 1.0F);
			int seconds = Mth.ceil(remaining / 20.0F);
			silo.broadcast(server, Component.literal(silo.missile.isNuclear() ? "☢ " : "⚠ ")
				.append(Component.translatable("message.ballisticmissiles.countdown", seconds))
				.withStyle(seconds <= 3 ? ChatFormatting.RED : ChatFormatting.GOLD, ChatFormatting.BOLD));
		}
		if (silo.age % 60 == 0) {
			level.playSound(null, top.x, top.y, top.z, ModRegistry.SIREN, SoundSource.BLOCKS, 10.0F, 0.9F);
		}
		if (remaining == HATCH_OPEN_BEFORE) {
			silo.setHatch(true);
			level.playSound(null, top.x, top.y, top.z, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 4.0F, 0.5F);
			level.playSound(null, top.x, top.y, top.z, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 4.0F, 0.4F);
		}
		if (++silo.age >= total) {
			silo.launch(server, top);
		}
	}

	private void launch(ServerLevel level, Vec3 top) {
		MissileType type = this.missile;
		TargetData t = this.target;
		this.counting = false;
		this.age = 0;
		this.missile = null;
		this.target = null;
		this.hatchTimer = 160;
		this.setHatch(true);
		this.setChanged();
		MissileEntity entity = ModRegistry.missileEntity(type).create(level, EntitySpawnReason.TRIGGERED);
		if (entity == null || t == null) {
			return;
		}
		entity.setPos(top.x, top.y - type.length - 0.3, top.z);
		entity.startSiloLaunch(t, top);
		level.addFreshEntity(entity);
	}

	private void setHatch(boolean open) {
		if (this.level == null) {
			return;
		}
		BlockState state = this.level.getBlockState(this.worldPosition);
		if (state.hasProperty(MissileSiloBlock.OPEN) && state.getValue(MissileSiloBlock.OPEN) != open) {
			this.level.setBlock(this.worldPosition, state.setValue(MissileSiloBlock.OPEN, open), 3);
		}
	}

	private void broadcast(ServerLevel level, Component msg) {
		Vec3 here = Vec3.atCenterOf(this.worldPosition);
		for (ServerPlayer player : level.players()) {
			if (player.position().distanceToSqr(here) < 96 * 96) {
				player.displayClientMessage(msg, true);
			}
		}
	}

	public Component status() {
		if (this.level != null && EmpManager.isJammed(this.level, this.worldPosition)) {
			return Component.translatable("message.ballisticmissiles.jammed", EmpManager.jammedTicks(this.level, this.worldPosition) / 20)
				.withStyle(ChatFormatting.DARK_PURPLE);
		}
		if (this.missile == null) {
			return Component.translatable("message.ballisticmissiles.silo_empty").withStyle(ChatFormatting.GRAY);
		}
		return Component.translatable(
				this.counting ? "message.ballisticmissiles.silo_counting" : "message.ballisticmissiles.silo_loaded",
				Component.translatable("item.ballisticmissiles." + this.missile.id)
			)
			.withStyle(this.counting ? ChatFormatting.RED : ChatFormatting.AQUA);
	}

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		if (this.level != null && this.missile != null) {
			Containers.dropItemStack(this.level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, new ItemStack(ModRegistry.missileItem(this.missile)));
			this.missile = null;
		}
		super.preRemoveSideEffects(pos, state);
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
		if (this.missile != null) {
			output.putString("Missile", this.missile.id);
		}
		output.putBoolean("Counting", this.counting);
		output.putInt("Age", this.age);
		output.putInt("Hatch", this.hatchTimer);
		if (this.target != null) {
			output.store("Target", TargetData.CODEC, this.target);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.missile = input.getString("Missile").map(MissileType::byId).orElse(null);
		this.counting = input.getBooleanOr("Counting", false);
		this.age = input.getIntOr("Age", 0);
		this.hatchTimer = input.getIntOr("Hatch", 0);
		this.target = input.read("Target", TargetData.CODEC).orElse(null);
	}
}
