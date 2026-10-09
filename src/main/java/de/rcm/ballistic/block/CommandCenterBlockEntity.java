package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.defense.DefenseNetwork;
import de.rcm.ballistic.defense.DefenseOwner;
import de.rcm.ballistic.defense.ThreatTracker;
import de.rcm.ballistic.item.AirstrikeRadioItem;
import de.rcm.ballistic.item.LauncherLink;
import de.rcm.ballistic.item.TargetData;
import de.rcm.ballistic.launch.RemoteLaunch;
import de.rcm.ballistic.network.ModNetworking.CommandActionPayload;
import de.rcm.ballistic.network.ModNetworking.CommandDataPayload;
import de.rcm.ballistic.network.ModNetworking.SiteInfo;
import de.rcm.ballistic.network.ModNetworking.TrackInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Command center: the situation map of everything within {@value #RANGE} blocks - every radar, air
 * defense, silo and jammer site, every missile, rocket and aircraft in the air with its predicted
 * impact - and the place to give orders: fire the launchers linked to your target designator or call
 * in any air strike / fire mission on a point you click on the map.
 */
public class CommandCenterBlockEntity extends BlockEntity implements DefenseSiteBlock.Site {
	public static final int RANGE = 600;
	private static final double USE_DISTANCE = 12.0;
	private static final int STRIKE_COOLDOWN = 200;

	/** Hostile missiles predicted to come down this close raise the alarm. */
	public static final double ALARM_RADIUS = 300.0;
	/** Length of the siren recording, ticks: it is sounded again when it has wound down. */
	private static final int SIREN_TICKS = 280;
	/** How long a destroyed site stays on the map as destroyed. */
	private static final long DESTROYED_SHOWN = 6000L;

	private final Set<UUID> viewers = new HashSet<>();
	/** Sites seen in range, and when the ones that vanished were found destroyed. */
	private final java.util.Map<BlockPos, DefenseNetwork.Kind> known = new java.util.HashMap<>();
	private final java.util.Map<BlockPos, Long> destroyed = new java.util.HashMap<>();
	private boolean alarm;
	private long lastSiren = -100000L;
	private int cooldown;
	private @Nullable UUID owner;

	public CommandCenterBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.COMMAND_CENTER_BE, pos, state);
	}

	@Override
	public void serverTick(ServerLevel level) {
		if (this.cooldown > 0) {
			this.cooldown--;
		}
		if (level.getGameTime() % 20 == 0) {
			this.alarmTick(level);
		}
		if (this.viewers.isEmpty() || level.getGameTime() % 10 != 0) {
			return;
		}
		Vec3 here = Vec3.atCenterOf(this.worldPosition);
		Iterator<UUID> it = this.viewers.iterator();
		while (it.hasNext()) {
			ServerPlayer player = level.getServer().getPlayerList().getPlayer(it.next());
			if (player == null || player.level() != level || player.position().distanceTo(here) > USE_DISTANCE * 2) {
				it.remove();
				continue;
			}
			ServerPlayNetworking.send(player, this.snapshot(level, player, false));
		}
	}

	/**
	 * Air-raid warning: a hostile missile, rocket or drone predicted to come down within
	 * {@value #ALARM_RADIUS} blocks sounds the siren (again and again until the sky is clear) and
	 * warns everyone around.
	 */
	private void alarmTick(ServerLevel level) {
		Vec3 here = Vec3.atCenterOf(this.worldPosition);
		AirThreat worst = null;
		for (AirThreat t : ThreatTracker.threats(level)) {
			if (DefenseOwner.isFriendly(this.owner, t) || t.threatClass() == AirThreat.ThreatClass.AIRCRAFT) {
				continue;
			}
			Vec3 impact = t.predictedImpact();
			if (Math.hypot(impact.x - here.x, impact.z - here.z) < ALARM_RADIUS && (worst == null || t.etaTicks() < worst.etaTicks())) {
				worst = t;
			}
		}
		boolean was = this.alarm;
		this.alarm = worst != null;
		long now = level.getGameTime();
		if (this.alarm && now - this.lastSiren >= SIREN_TICKS) {
			this.lastSiren = now;
			level.playSound(null, here.x, here.y + 3, here.z, ModRegistry.AIR_RAID, SoundSource.BLOCKS, 12.0F, 1.0F);
		}
		if (this.alarm && (!was || now % 100 == 0)) {
			Component msg = Component.literal("⚠ ").append(Component.translatable("message.ballisticmissiles.air_raid",
				Component.translatable(worst.nameKey()), Math.max(0, worst.etaTicks() / 20))).withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
			for (ServerPlayer player : level.players()) {
				if (player.position().distanceTo(here) < ALARM_RADIUS + 100) {
					player.displayClientMessage(msg, true);
				}
			}
		}
	}

	public boolean isAlarm() {
		return this.alarm;
	}

	public void open(ServerPlayer player) {
		this.viewers.add(player.getUUID());
		ServerPlayNetworking.send(player, this.snapshot((ServerLevel) this.level, player, true));
	}

	private CommandDataPayload snapshot(ServerLevel level, ServerPlayer viewer, boolean open) {
		Vec3 here = Vec3.atCenterOf(this.worldPosition);
		List<TrackInfo> tracks = new ArrayList<>();
		for (AirThreat t : ThreatTracker.threats(level)) {
			Vec3 p = t.asEntity().position();
			if (Math.hypot(p.x - here.x, p.z - here.z) > RANGE) {
				continue;
			}
			Vec3 v = t.threatVelocity();
			Vec3 impact = t.predictedImpact();
			boolean hostile = !DefenseOwner.isFriendly(viewer.getUUID(), t);
			tracks.add(new TrackInfo(t.asEntity().getId() % 100, t.threatClass().ordinal(), t.nameKey(), (float) p.x, (float) p.y, (float) p.z,
				(float) v.x, (float) v.z, (float) impact.x, (float) impact.z, 0.0F, t.etaTicks(), hostile, 0, t.asEntity().getId()));
			if (tracks.size() >= 200) {
				break;
			}
		}
		List<SiteInfo> sites = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		long now = level.getGameTime();
		for (DefenseNetwork.Kind kind : DefenseNetwork.Kind.values()) {
			for (BlockPos p : DefenseNetwork.find(level, kind, here, RANGE)) {
				sites.add(siteState(level, p, kind));
				seen.add(p);
				this.known.put(p, kind);
				this.destroyed.remove(p);
			}
		}
		// sites that have dropped off the network: destroyed, or just out of reach (unloaded)
		for (Iterator<java.util.Map.Entry<BlockPos, DefenseNetwork.Kind>> it = this.known.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			BlockPos p = e.getKey();
			if (seen.contains(p)) {
				continue;
			}
			if (level.isLoaded(p) && !(level.getBlockEntity(p) instanceof DefenseSiteBlock.Site) && !isDefenseEntity(level.getBlockEntity(p))) {
				Long when = this.destroyed.computeIfAbsent(p, k -> now);
				if (now - when > DESTROYED_SHOWN) {
					it.remove();
					this.destroyed.remove(p);
					continue;
				}
				sites.add(new SiteInfo(p, e.getValue().ordinal(), typeOf(e.getValue(), null), -1, -1, -1, SiteInfo.DESTROYED));
			} else if (!level.isLoaded(p)) {
				sites.add(new SiteInfo(p, e.getValue().ordinal(), typeOf(e.getValue(), null), -1, -1, -1, SiteInfo.OFFLINE));
			}
		}
		return new CommandDataPayload(this.worldPosition, open, RANGE, tracks, sites, links(viewer).size(), Mth.ceil(this.cooldown / 20.0F), this.alarm);
	}

	private static boolean isDefenseEntity(@Nullable BlockEntity be) {
		return be instanceof AirDefenseBlockEntity || be instanceof CiwsBlockEntity || be instanceof LaserDefenseBlockEntity
			|| be instanceof MissileSiloBlockEntity || be instanceof RadarBlockEntity || be instanceof JammerBlockEntity;
	}

	private static int typeOf(DefenseNetwork.Kind kind, @Nullable BlockEntity be) {
		if (be instanceof AirDefenseBlockEntity) {
			return SiteInfo.TYPE_PATRIOT;
		} else if (be instanceof CiwsBlockEntity) {
			return SiteInfo.TYPE_CIWS;
		} else if (be instanceof LaserDefenseBlockEntity) {
			return SiteInfo.TYPE_LASER;
		} else if (be instanceof IronDomeBlockEntity) {
			return SiteInfo.TYPE_IRON_DOME;
		} else if (be instanceof DecoyLauncherBlockEntity) {
			return SiteInfo.TYPE_DECOY;
		}
		return switch (kind) {
			case RADAR -> SiteInfo.TYPE_RADAR;
			case SILO -> SiteInfo.TYPE_SILO;
			case JAMMER -> SiteInfo.TYPE_JAMMER;
			default -> SiteInfo.TYPE_OTHER;
		};
	}

	/** What the command center knows of a site: its rounds, whether it is reloading, jammed, without power... */
	private static SiteInfo siteState(ServerLevel level, BlockPos pos, DefenseNetwork.Kind kind) {
		BlockEntity be = level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
		int flags = de.rcm.ballistic.defense.EmpManager.jammedTicks(level, pos) > 0 ? SiteInfo.JAMMED : 0;
		int ammo = -1;
		int magazine = -1;
		int spares = -1;
		if (be instanceof IronDomeBlockEntity dome) {
			ammo = dome.siteAmmo();
			magazine = dome.siteMagazine();
			spares = dome.siteSpares();
			flags |= dome.siteReloading() ? SiteInfo.RELOADING : 0;
		} else if (be instanceof AirDefenseBlockEntity patriot) {
			ammo = patriot.getAmmo();
			magazine = AirDefenseBlockEntity.MAGAZINE;
			flags |= ammo < magazine ? SiteInfo.RELOADING : 0;
		} else if (be instanceof CiwsBlockEntity ciws) {
			ammo = ciws.getAmmo();
			magazine = CiwsBlockEntity.MAGAZINE;
			flags |= ciws.isFiring() ? SiteInfo.ACTIVE : 0;
		} else if (be instanceof DecoyLauncherBlockEntity decoy) {
			ammo = decoy.getAmmo();
			magazine = DecoyLauncherBlockEntity.MAGAZINE;
			flags |= ammo < magazine ? SiteInfo.RELOADING : 0;
		} else if (be instanceof LaserDefenseBlockEntity laser) {
			flags |= laser.isPowered() ? 0 : SiteInfo.UNPOWERED;
		} else if (be instanceof JammerBlockEntity jammer) {
			flags |= jammer.isJamming() ? SiteInfo.ACTIVE : 0;
		} else if (be instanceof MissileSiloBlockEntity silo) {
			flags |= silo.isCounting() ? SiteInfo.ACTIVE : 0;
		}
		return new SiteInfo(pos, kind.ordinal(), typeOf(kind, be), ammo, magazine, spares, flags);
	}

	/** The launcher links of the first target designator the player carries. */
	private static List<LauncherLink> links(ServerPlayer player) {
		for (ItemStack stack : player.getInventory()) {
			if (stack.is(ModRegistry.TARGET_DESIGNATOR)) {
				List<LauncherLink> links = stack.getOrDefault(ModRegistry.LINKS, List.of());
				if (!links.isEmpty()) {
					return links;
				}
			}
		}
		return List.of();
	}

	public void handleAction(ServerPlayer player, CommandActionPayload action) {
		ServerLevel level = (ServerLevel) this.level;
		if (level == null || player.position().distanceTo(Vec3.atCenterOf(this.worldPosition)) > USE_DISTANCE * 2) {
			return;
		}
		if (action.action() == CommandActionPayload.CLOSE) {
			this.viewers.remove(player.getUUID());
			return;
		}
		int x = action.x();
		int z = action.z();
		if (Math.hypot(x - this.worldPosition.getX(), z - this.worldPosition.getZ()) > RANGE * 3) {
			return;
		}
		boolean known = level.hasChunk(x >> 4, z >> 4);
		int y = known ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : level.getSeaLevel();
		TargetData target = new TargetData(new BlockPos(x, y, z), !known);
		switch (action.action()) {
			case CommandActionPayload.FIRE_LINKED -> {
				List<LauncherLink> links = links(player);
				if (links.isEmpty()) {
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.command_no_links").withStyle(ChatFormatting.YELLOW), false);
					return;
				}
				RemoteLaunch.fire(player, links, target);
				level.playSound(null, this.worldPosition, SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.BLOCKS, 1.0F, 0.6F);
			}
			case CommandActionPayload.ABORT_LINKED -> RemoteLaunch.abort(player, links(player));
			case CommandActionPayload.AIRSTRIKE -> {
				if (this.cooldown > 0) {
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.command_cooldown", Mth.ceil(this.cooldown / 20.0F))
						.withStyle(ChatFormatting.YELLOW), false);
					return;
				}
				AirstrikeRadioItem.Mode[] modes = AirstrikeRadioItem.Mode.values();
				AirstrikeRadioItem.Mode mode = modes[Mth.clamp(action.mode(), 0, modes.length - 1)];
				if (AirstrikeRadioItem.callMission(level, player, mode, Vec3.atBottomCenterOf(target.pos())) == net.minecraft.world.InteractionResult.SUCCESS) {
					this.cooldown = STRIKE_COOLDOWN;
				}
			}
			default -> {
			}
		}
	}

	@Override
	public Component status() {
		return Component.translatable("block.ballisticmissiles.command_center");
	}

	@Override
	public void setOwner(@Nullable UUID owner) {
		this.owner = owner;
		this.setChanged();
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (this.owner != null) {
			output.store("Owner", UUIDUtil.CODEC, this.owner);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
	}
}
