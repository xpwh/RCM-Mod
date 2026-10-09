package de.rcm.ballistic;

import de.rcm.ballistic.entity.JetEntity;
import de.rcm.ballistic.entity.MissileEntity;
import java.util.function.Consumer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

/**
 * Bridges from common code into client-only code. The client entrypoint replaces these
 * no-op defaults; on a dedicated server they stay no-ops.
 */
public final class ClientHooks {
	public static Consumer<InteractionHand> openTargetScreen = hand -> {};
	public static Consumer<InteractionHand> designateLookedAtBlock = hand -> {};
	public static Consumer<MissileEntity> missileClientTick = missile -> {};
	public static Consumer<Entity> projectileClientTick = entity -> {};
	public static Consumer<JetEntity> jetClientTick = jet -> {};
	public static Consumer<de.rcm.ballistic.entity.FpvDroneEntity> droneClientTick = drone -> {};

	/** The camera's view while flying a drone: the pilot's look (null = the drone's own rotation). */
	public interface DroneView {
		@org.jspecify.annotations.Nullable Float view(de.rcm.ballistic.entity.FpvDroneEntity drone, float partialTick, boolean pitch);
	}

	public static DroneView droneView = (drone, partialTick, pitch) -> null;
	public static Consumer<de.rcm.ballistic.entity.AerialBombEntity> bombClientTick = bomb -> {};

	/** A long-lived smoke trail continued to {@code at} (key: usually the entity id). */
	public interface SmokeTrail {
		void emit(int key, net.minecraft.world.phys.Vec3 at, float width, float strength);
	}

	/** A lingering cloud of smoke at {@code at}, drifting along {@code drift}. */
	public interface SmokeCloud {
		void emit(net.minecraft.world.phys.Vec3 at, net.minecraft.world.phys.Vec3 drift, int puffs, float size);
	}

	public static Consumer<de.rcm.ballistic.gun.BulletEntity> bulletClientTick = bullet -> {};
	public static SmokeTrail smokeTrail = (key, at, width, strength) -> {};
	public static SmokeCloud smokeCloud = (at, drift, puffs, size) -> {};

	private ClientHooks() {
	}
}
