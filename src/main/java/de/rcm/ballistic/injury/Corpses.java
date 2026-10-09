package de.rcm.ballistic.injury;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.mixin.MannequinAccessor;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import com.mojang.serialization.Codec;

/**
 * A player who dies leaves their body behind for a while: a mannequin wearing their skin, lying where they
 * fell - on its face or its back, as it went down - with every wound they had (the limbs shot off, the
 * head). It cannot be moved or hurt, and after {@link #LIFETIME} it is gone.
 */
public final class Corpses {
	/** How long a body lies there (ticks): three minutes. */
	public static final int LIFETIME = 3600;
	/** More bodies than this in the world, and the oldest goes. */
	private static final int MAX = 24;
	/** On a body: the game time it disappears. */
	public static final AttachmentType<Long> UNTIL = AttachmentRegistry.create(BallisticMissiles.id("corpse_until"), b -> b.persistent(Codec.LONG));

	private static final List<Mannequin> BODIES = new ArrayList<>();

	private Corpses() {
	}

	public static void init() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Mannequin m && m.hasAttached(UNTIL) && !BODIES.contains(m)) {
				BODIES.add(m);
			}
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> BODIES.remove(entity));
		ServerTickEvents.END_SERVER_TICK.register(Corpses::tick);
	}

	public static void clear() {
		BODIES.clear();
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) {
			return;
		}
		BODIES.removeIf(m -> {
			if (m.isRemoved()) {
				return true;
			}
			Long until = m.getAttached(UNTIL);
			if (until == null || m.level().getGameTime() >= until) {
				m.discard();
				return true;
			}
			return false;
		});
	}

	/** The player has died: leave their body. */
	public static void leave(ServerPlayer p) {
		if (p.isSpectator()) {
			return;
		}
		ServerLevel level = p.level();
		Mannequin m = EntityType.MANNEQUIN.create(level, EntitySpawnReason.TRIGGERED);
		if (m == null) {
			return;
		}
		// on the ground below where they died
		BlockPos.MutableBlockPos pos = p.blockPosition().mutable();
		double y = p.getY();
		for (int i = 0; i < 24 && level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty() && pos.getY() > level.getMinY(); i++) {
			pos.move(0, -1, 0);
			y = pos.getY();
		}
		m.snapTo(p.getX(), y, p.getZ(), p.getYRot(), 0.0F);
		m.setYBodyRot(p.yBodyRot);
		m.setYHeadRot(p.yBodyRot);
		MannequinAccessor acc = (MannequinAccessor) m;
		acc.ballisticmissiles$setProfile(ResolvableProfile.createResolved(p.getGameProfile()));
		acc.ballisticmissiles$setImmovable(true);
		acc.ballisticmissiles$setHideDescription(true);
		m.setInvulnerable(true);
		m.setSilent(true);
		m.setPose(Pose.STANDING);
		// the wounds go with the body; it lies as it fell: crawling, on its face; shot, as the collapse went; else either way
		Wounds w = Injuries.get(p);
		int fall = w.fall() > 0 ? w.fall() : w.down() ? Wounds.FORWARD : p.getRandom().nextBoolean() ? Wounds.FORWARD : Wounds.BACKWARD;
		Wounds body = new Wounds(w.leg(), w.arm(), 0, w.lost(), 0, w.armsLost(), w.head(), 0, fall);
		m.setAttached(Injuries.WOUNDS, body);
		m.setAttached(UNTIL, level.getGameTime() + LIFETIME);
		level.addFreshEntity(m);
		BODIES.add(m);
		while (BODIES.size() > MAX) {
			BODIES.remove(0).discard();
		}
	}
}
