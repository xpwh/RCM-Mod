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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
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

	private int ammo = MAGAZINE;
	private int reload;
	private int cooldown;
	private int kills;
	private int misses;
	private boolean linked;

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
			battery.setChanged();
		}
		if (battery.cooldown > 0) {
			battery.cooldown--;
		}
		if (level.getGameTime() % 2 != 0 || EmpManager.isJammed(level, pos)) {
			return;
		}
		Vec3 here = Vec3.atCenterOf(pos);
		battery.linked = DefenseNetwork.find(level, DefenseNetwork.Kind.RADAR, here, RADAR_LINK_RANGE)
			.stream()
			.anyMatch(r -> !EmpManager.isJammed(level, r));
		if (battery.cooldown > 0 || battery.ammo <= 0) {
			return;
		}

		double range = battery.range();
		Vec3 launch = here.add(0, 0.9, 0);
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

		float pk = Mth.clamp(best.killProbability() + (battery.linked ? 0.1F : 0.0F), 0.05F, 0.97F);
		if (InterceptorEntity.launch(server, pos, launch, best, pk) != null) {
			best.setEngagements(best.getEngagements() + 1);
			battery.ammo--;
			battery.cooldown = SALVO_GAP;
			battery.setChanged();
			level.playSound(null, pos, ModRegistry.SAM_LAUNCH, SoundSource.BLOCKS, 10.0F, 0.95F + server.getRandom().nextFloat() * 0.1F);
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
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.ammo = input.getIntOr("Ammo", MAGAZINE);
		this.reload = input.getIntOr("Reload", 0);
		this.kills = input.getIntOr("Kills", 0);
		this.misses = input.getIntOr("Misses", 0);
	}
}
