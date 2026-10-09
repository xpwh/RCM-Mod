package de.rcm.ballistic.client;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ClientHooks;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.effect.ClientEffects;
import de.rcm.ballistic.client.effect.JetClientTicker;
import de.rcm.ballistic.client.effect.MissileClientTicker;
import de.rcm.ballistic.client.particle.CloudParticle;
import de.rcm.ballistic.client.render.AerialBombRenderer;
import de.rcm.ballistic.client.render.AirDefenseRenderer;
import de.rcm.ballistic.client.render.BombletRenderer;
import de.rcm.ballistic.client.render.CiwsRenderer;
import de.rcm.ballistic.client.render.JammerRenderer;
import de.rcm.ballistic.client.render.JetRenderer;
import de.rcm.ballistic.client.render.LaserRenderer;
import de.rcm.ballistic.client.render.MeteorRenderer;
import de.rcm.ballistic.client.render.LaunchPadRenderer;
import de.rcm.ballistic.client.render.RadarRenderer;
import de.rcm.ballistic.client.render.SiloRenderer;
import de.rcm.ballistic.client.render.MissileRenderer;
import de.rcm.ballistic.client.render.MobileLauncherRenderer;
import de.rcm.ballistic.client.screen.RadarScreen;
import de.rcm.ballistic.client.render.ProjectileRenderer;
import de.rcm.ballistic.client.screen.TargetScreen;
import de.rcm.ballistic.entity.MissileType;
import de.rcm.ballistic.client.particle.CloudParticle;
import de.rcm.ballistic.network.ModNetworking.DetonationPayload;
import de.rcm.ballistic.network.ModNetworking.RadarDataPayload;
import de.rcm.ballistic.network.ModNetworking.SetTargetPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class BallisticMissilesClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		for (MissileType type : MissileType.values()) {
			EntityRendererRegistry.register(ModRegistry.missileEntity(type), MissileRenderer::new);
		}
		EntityRendererRegistry.register(ModRegistry.BOMBLET, BombletRenderer::new);
		EntityRendererRegistry.register(ModRegistry.REENTRY_VEHICLE, ctx -> new ProjectileRenderer<>(ctx, false));
		EntityRendererRegistry.register(ModRegistry.SPENT_STAGE, de.rcm.ballistic.client.render.SpentStageRenderer::new);
		EntityRendererRegistry.register(ModRegistry.DESTROYER, de.rcm.ballistic.client.render.DestroyerRenderer::new);
		EntityRendererRegistry.register(ModRegistry.INTERCEPTOR, ctx -> new ProjectileRenderer<>(ctx, true));
		EntityRendererRegistry.register(ModRegistry.MOBILE_LAUNCHER, MobileLauncherRenderer::new);
		EntityRendererRegistry.register(ModRegistry.JET, JetRenderer::new);
		EntityRendererRegistry.register(ModRegistry.AERIAL_BOMB, AerialBombRenderer::new);
		EntityRendererRegistry.register(ModRegistry.ROCKET, de.rcm.ballistic.client.render.RocketRenderer::new);
		EntityRendererRegistry.register(ModRegistry.METEOR, MeteorRenderer::new);
		EntityRendererRegistry.register(ModRegistry.EARTH_PENETRATOR, de.rcm.ballistic.client.render.B61Renderer::new);
		EntityRendererRegistry.register(ModRegistry.POSEIDON, de.rcm.ballistic.client.render.PoseidonRenderer::new);
		EntityRendererRegistry.register(ModRegistry.SUPPLY_ROCKET, de.rcm.ballistic.client.render.SupplyRocketRenderer::new);
		EntityRendererRegistry.register(ModRegistry.KINETIC_ROD, de.rcm.ballistic.client.render.KineticRodRenderer::new);
		BlockEntityRenderers.register(ModRegistry.ORBITAL_UPLINK_BE, de.rcm.ballistic.client.render.OrbitalUplinkRenderer::new);
		EntityRendererRegistry.register(ModRegistry.BULLET, de.rcm.ballistic.client.render.BulletRenderer::new);
		EntityRendererRegistry.register(ModRegistry.GRENADE_ENTITY, de.rcm.ballistic.client.render.GrenadeRenderers.EntityRendererImpl::new);
		EntityRendererRegistry.register(ModRegistry.RPG_GRENADE, de.rcm.ballistic.client.render.RpgGrenadeRenderer::new);
		EntityRendererRegistry.register(ModRegistry.FPV_DRONE, de.rcm.ballistic.client.render.FpvDroneRenderer::new);
		EntityRendererRegistry.register(ModRegistry.SOLDIER, de.rcm.ballistic.client.ai.SoldierRenderer::new);
		EntityRendererRegistry.register(ModRegistry.FIGHTER, de.rcm.ballistic.client.render.FighterRenderer::new);
		EntityRendererRegistry.register(ModRegistry.AIR_MISSILE, de.rcm.ballistic.client.render.AirMissileRenderer::new);
		de.rcm.ballistic.client.fighter.FighterClient.init();
		de.rcm.ballistic.client.ai.AiDebugClient.init();
		de.rcm.ballistic.client.drone.DroneClient.init();
		de.rcm.ballistic.client.item.RpgClient.init();
		de.rcm.ballistic.client.gun.AkClient.init();
		de.rcm.ballistic.client.render.Rpg7ItemRenderer.register();
		de.rcm.ballistic.client.render.AkItemRenderer.register();
		de.rcm.ballistic.client.render.GrenadeRenderers.register();
		de.rcm.ballistic.client.render.FpvDroneItemRenderer.register();
		de.rcm.ballistic.client.render.SmokeDebug.init();
		de.rcm.ballistic.client.render.BulletHoles.init();
		ModConfig.load();
		de.rcm.ballistic.client.effect.DynamicLights.init();
		de.rcm.ballistic.client.effect.VolumetricClouds.init();
		de.rcm.ballistic.client.effect.VolCloudCommand.init();
		de.rcm.ballistic.client.gui.SettingsCommand.init();
		BlockEntityRenderers.register(ModRegistry.CIWS_BE, CiwsRenderer::new);
		BlockEntityRenderers.register(ModRegistry.LAUNCH_PAD_BE, LaunchPadRenderer::new);
		BlockEntityRenderers.register(ModRegistry.MISSILE_SILO_BE, SiloRenderer::new);
		BlockEntityRenderers.register(ModRegistry.RADAR_BE, RadarRenderer::new);
		BlockEntityRenderers.register(ModRegistry.AIR_DEFENSE_BE, AirDefenseRenderer::new);
		BlockEntityRenderers.register(ModRegistry.SEA_MINE_BE, de.rcm.ballistic.client.render.SeaMineRenderer::new);
		BlockEntityRenderers.register(ModRegistry.LASER_DEFENSE_BE, LaserRenderer::new);
		BlockEntityRenderers.register(ModRegistry.JAMMER_BE, JammerRenderer::new);
		BlockEntityRenderers.register(ModRegistry.IRON_DOME_BE, de.rcm.ballistic.client.render.IronDomeRenderer::new);
		BlockEntityRenderers.register(ModRegistry.DECOY_LAUNCHER_BE, de.rcm.ballistic.client.render.DecoyLauncherRenderer::new);

		ParticleFactoryRegistry.getInstance().register(ModRegistry.SMOKE, CloudParticle.SmokeProvider::new);
		ParticleFactoryRegistry.getInstance().register(ModRegistry.FIRE, CloudParticle.FireProvider::new);
		ParticleFactoryRegistry.getInstance().register(ModRegistry.BURST, CloudParticle.BurstProvider::new);
		ParticleFactoryRegistry.getInstance().register(ModRegistry.DUST, CloudParticle.DustProvider::new);
		ParticleFactoryRegistry.getInstance().register(ModRegistry.SPARK, de.rcm.ballistic.client.particle.SparkParticle.Provider::new);

		ClientPlayNetworking.registerGlobalReceiver(DetonationPayload.TYPE, (payload, context) ->
			ClientEffects.detonation(payload.warhead(), new Vec3(payload.x(), payload.y(), payload.z()))
		);
		ClientPlayNetworking.registerGlobalReceiver(de.rcm.ballistic.network.ModNetworking.ServerConfigPayload.TYPE, (payload, context) ->
			ModConfig.serverMisfireChance = payload.misfireChance()); // only a mirror for the screen: never the server's own value
		ClientPlayNetworking.registerGlobalReceiver(de.rcm.ballistic.network.ModNetworking.JammerPayload.TYPE, (payload, context) ->
			context.client().setScreen(new de.rcm.ballistic.client.screen.JammerScreen(payload)));
		ClientPlayNetworking.registerGlobalReceiver(RadarDataPayload.TYPE, (payload, context) -> {
			Minecraft mc = context.client();
			if (mc.screen instanceof RadarScreen scope && scope.radarPos().equals(payload.radar())) {
				scope.update(payload);
			} else if (payload.open()) {
				mc.setScreen(new RadarScreen(payload));
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(de.rcm.ballistic.network.ModNetworking.CommandDataPayload.TYPE, (payload, context) -> {
			Minecraft mc = context.client();
			if (mc.screen instanceof de.rcm.ballistic.client.screen.CommandScreen screen && screen.centerPos().equals(payload.center())) {
				screen.update(payload);
			} else if (payload.open()) {
				mc.setScreen(new de.rcm.ballistic.client.screen.CommandScreen(payload));
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(de.rcm.ballistic.network.ModNetworking.FarTrackPayload.TYPE,
			(payload, context) -> de.rcm.ballistic.client.effect.FarTrackClient.receive(payload));
		ClientTickEvents.END_CLIENT_TICK.register(de.rcm.ballistic.client.effect.FarTrackClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> de.rcm.ballistic.client.effect.FarTrackClient.clear());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientEffects.clear());
		ClientTickEvents.END_CLIENT_TICK.register(ClientEffects::tick);
		ClientTickEvents.END_CLIENT_TICK.register(de.rcm.ballistic.client.effect.Contrails::tick);
		ClientTickEvents.END_CLIENT_TICK.register(de.rcm.ballistic.client.effect.SmokeField::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> de.rcm.ballistic.client.effect.SmokeField.clear());
		net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.BEFORE_ENTITIES.register(de.rcm.ballistic.client.effect.SmokeField::render);
		ClientPlayNetworking.registerGlobalReceiver(de.rcm.ballistic.network.ModNetworking.FalloutPayload.TYPE,
			(payload, context) -> de.rcm.ballistic.client.effect.FalloutClient.receive(payload));
		ClientTickEvents.END_CLIENT_TICK.register(de.rcm.ballistic.client.effect.FalloutClient::tick);
		ClientPlayNetworking.registerGlobalReceiver(de.rcm.ballistic.network.ModNetworking.WinterPayload.TYPE,
			(payload, context) -> de.rcm.ballistic.client.effect.WinterClient.receive(payload.soot()));
		ClientTickEvents.END_CLIENT_TICK.register(de.rcm.ballistic.client.effect.WinterClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> de.rcm.ballistic.client.effect.WinterClient.clear());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> de.rcm.ballistic.client.effect.FalloutClient.clear());
		ClientTickEvents.END_CLIENT_TICK.register(BallisticMissilesClient::debrisTrails);

		HudElementRegistry.addLast(BallisticMissiles.id("flash"), (graphics, tickCounter) -> {
			float alpha = ClientEffects.flashAlpha(tickCounter.getGameTimeDeltaPartialTick(false));
			if (alpha > 0.003F) {
				int a = (int) (Math.min(1.0F, alpha) * 255.0F);
				graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), a << 24 | ClientEffects.flashColor());
			}
		});

		ClientHooks.missileClientTick = MissileClientTicker::tick;
		ClientHooks.projectileClientTick = MissileClientTicker::projectileTick;
		ClientHooks.jetClientTick = JetClientTicker::tick;
		ClientHooks.smokeTrail = (key, at, width, strength) ->
			de.rcm.ballistic.client.effect.SmokeField.trail(key * 4 + 2, at, de.rcm.ballistic.client.effect.SmokeField.Style.smallRocket(width), strength);
		ClientHooks.smokeCloud = (at, drift, puffs, size) ->
			de.rcm.ballistic.client.effect.SmokeField.burst(at, puffs, size * 0.8, drift, 0.35, 1500, size * 0.5F, size * 3.2F, 0xE4E0DA, 0.8F, 0.0015F);
		ClientHooks.bombClientTick = bomb -> {
			// every third bomb of a stick (and always the MOAB) whistles on the way down
			if (bomb.tickCount == 1 && (bomb.isMoab() || bomb.getId() % 3 == 0)) {
				float pitch = bomb.isMoab() ? 0.55F : 0.9F + (bomb.getId() % 5) * 0.05F;
				Minecraft.getInstance().getSoundManager().play(new de.rcm.ballistic.client.sound.EntityFollowSound(bomb, ModRegistry.BOMB_WHISTLE, pitch, bomb.isMoab() ? 400.0 : 220.0));
			}
		};
		ClientHooks.openTargetScreen = hand -> Minecraft.getInstance().setScreen(new TargetScreen(hand));
		ClientHooks.designateLookedAtBlock = BallisticMissilesClient::designate;
	}

	/**
	 * Locks the block in the crosshair. Past the render distance the line of sight is extended until it
	 * meets sea level, so far-away targets can be designated by just looking at the horizon.
	 */
	private static void designate(InteractionHand hand) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		double range = mc.options.getEffectiveRenderDistance() * 16.0;
		HitResult hit = mc.player.pick(range, 1.0F, false);
		boolean offhand = hand == InteractionHand.OFF_HAND;
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			ClientPlayNetworking.send(new SetTargetPayload(offhand, blockHit.getBlockPos(), false));
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		Vec3 look = mc.player.getViewVector(1.0F);
		double seaLevel = mc.level.getSeaLevel();
		if (look.y < -0.003 && eye.y > seaLevel) {
			double t = Math.min((eye.y - seaLevel) / -look.y, 30000.0);
			Vec3 p = eye.add(look.scale(t));
			ClientPlayNetworking.send(new SetTargetPayload(offhand, net.minecraft.core.BlockPos.containing(p.x, seaLevel, p.z), true));
			mc.player.displayClientMessage(Component.translatable("message.ballisticmissiles.long_range", (int) Math.hypot(p.x - eye.x, p.z - eye.z)).withStyle(ChatFormatting.GOLD), false);
		} else {
			mc.player.displayClientMessage(Component.translatable("message.ballisticmissiles.no_block").withStyle(ChatFormatting.YELLOW), true);
		}
	}

	/** Dust trails behind blocks hurled away by explosions. */
	private static void debrisTrails(Minecraft mc) {
		if (mc.level == null || mc.isPaused()) {
			return;
		}
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof FallingBlockEntity block && block.getDeltaMovement().lengthSqr() > 0.36) {
				CloudParticle p = ClientEffects.cloud(false, e.getX(), e.getY() + 0.5, e.getZ(), 0, 0, 0);
				if (p != null) {
					p.configure(30 + (int) (ClientEffects.rand() * 30), 0.6F, 2.2F, 0x8A7C6A, 0x6A645C, 0.55F).physics(0.9F, 0.002F);
				}
			}
		}
	}
}
