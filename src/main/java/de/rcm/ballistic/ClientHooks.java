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
	public static Consumer<de.rcm.ballistic.entity.AerialBombEntity> bombClientTick = bomb -> {};

	private ClientHooks() {
	}
}
