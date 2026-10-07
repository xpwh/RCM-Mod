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

	private final Set<UUID> viewers = new HashSet<>();
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
				(float) v.x, (float) v.z, (float) impact.x, (float) impact.z, 0.0F, t.etaTicks(), hostile, 0));
			if (tracks.size() >= 200) {
				break;
			}
		}
		List<SiteInfo> sites = new ArrayList<>();
		for (DefenseNetwork.Kind kind : DefenseNetwork.Kind.values()) {
			for (BlockPos p : DefenseNetwork.find(level, kind, here, RANGE)) {
				sites.add(new SiteInfo(p, kind.ordinal()));
			}
		}
		return new CommandDataPayload(this.worldPosition, open, RANGE, tracks, sites, links(viewer).size(), Mth.ceil(this.cooldown / 20.0F));
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
