package de.rcm.ballistic.injury;

import de.rcm.ballistic.network.ModNetworking.BodyHeatPayload;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Heat and cold. The air round you has a temperature - the biome's, warmer by day and in the sun, colder at night,
 * in the rain, up a mountain, soaking wet; scorching in the Nether, chill in the End; a fire, a campfire, lava
 * close by warm you - and your body slowly follows it. Too cold and you shiver (your aim shakes), then the frost
 * creeps in and at last starts to hurt; too hot and you sweat, tire and grow hungry fast, and in the worst of it
 * you slow down. Leather keeps you warm; heavy armour cooks you in the heat.
 */
public final class BodyHeat {
	/** Per player: the body's state, -1 (freezing) .. 0 (fine) .. 1 (heat stroke), and the air's temperature (deg C). */
	private static final Map<UUID, float[]> STATE = new HashMap<>();

	private BodyHeat() {
	}

	public static void tick(MinecraftServer server) {
		boolean second = server.getTickCount() % 20 == 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			float[] s = STATE.computeIfAbsent(p.getUUID(), k -> new float[2]);
			if (p.isCreative() || p.isSpectator() || !p.isAlive() || !de.rcm.ballistic.config.ServerConfig.nature(de.rcm.ballistic.config.ServerConfig.BODY_HEAT)) {
				s[0] = 0.0F;
				if (second) {
					ServerPlayNetworking.send(p, new BodyHeatPayload(0.0F, 15.0F));
				}
				continue;
			}
			if (second) {
				ServerLevel level = p.level();
				float air = airAt(level, p);
				s[1] = air;
				// how the body is pulled: comfortable between 8 and 28 degrees
				float target = air < 8.0F ? Mth.clamp((air - 8.0F) / 24.0F, -1.0F, 0.0F) : air > 28.0F ? Mth.clamp((air - 28.0F) / 22.0F, 0.0F, 1.0F) : 0.0F;
				// a body takes a few minutes to cool down or heat up, and comes back sooner
				float rate = Math.abs(target) > Math.abs(s[0]) ? 0.012F : 0.03F;
				s[0] += Mth.clamp(target - s[0], -rate, rate);
				effects(p, s[0]);
				ServerPlayNetworking.send(p, new BodyHeatPayload(s[0], air));
			}
			// the frost: kept up every tick (Minecraft thaws you two ticks a tick otherwise)
			if (s[0] < -0.75F && !p.hasEffect(MobEffects.FIRE_RESISTANCE)) {
				int want = Math.round(Mth.clamp((-s[0] - 0.75F) / 0.2F, 0.0F, 1.0F) * (p.getTicksRequiredToFreeze() + 20));
				if (p.getTicksFrozen() < want) {
					p.setTicksFrozen(Math.min(want, p.getTicksFrozen() + 3));
				}
			}
		}
	}

	private static void effects(ServerPlayer p, float body) {
		if (body > 0.45F) {
			// sweating it out: thirsty, hungry, tired
			p.causeFoodExhaustion(0.25F + 0.75F * (body - 0.45F));
			if (body > 0.8F) {
				p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0, true, false, true));
				p.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 60, 0, true, false, true));
			}
		} else if (body < -0.5F) {
			// stiff with cold: slower, clumsy
			p.causeFoodExhaustion(0.15F);
			if (body < -0.7F) {
				p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0, true, false, true));
			}
		}
	}

	/** The temperature of the air round the player, deg C. */
	public static float airAt(ServerLevel level, ServerPlayer p) {
		BlockPos at = p.blockPosition();
		float t;
		if (level.dimension() == Level.NETHER) {
			t = 40.0F;
		} else if (level.dimension() == Level.END) {
			t = -6.0F;
		} else {
			Biome biome = level.getBiome(at).value();
			// colder up high (as Minecraft has it: from y 80 up)
			float b = biome.getBaseTemperature() - Math.max(0, at.getY() - 80) * 0.00125F;
			// plains 18, a snowy plain -10, a desert 30 on average
			t = b < 0.8F ? 18.0F + (b - 0.8F) * 22.0F : 18.0F + (b - 0.8F) * 10.0F;
			long time = level.getDayTime() % 24000L;
			float sun = Mth.sin((time / 24000.0F) * Mth.TWO_PI); // 1 at noon, -1 at midnight
			// deserts swing hardest: scorching by day, cold at night
			float swing = b > 1.5F ? 10.0F : 5.0F;
			t += sun * swing;
			boolean sky = level.canSeeSky(at.above());
			if (sky && sun > 0.0F && !level.isRaining()) {
				t += 4.0F * sun; // standing in the sun
			}
			if (level.isRaining() && sky) {
				t -= b < 0.15F ? 6.0F : 4.0F;
			}
			// underground the air is mild, whatever it is outside
			if (!sky && at.getY() < level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()) - 8) {
				t = Mth.lerp(0.7F, t, 12.0F);
			}
		}
		// wet through: water carries the heat away
		if (p.isInWater()) {
			t = Math.min(t, t - 8.0F);
		} else if (p.isInWaterOrRain()) {
			t -= 3.0F;
		}
		t += sources(level, at);
		t += clothing(p, t);
		return t;
	}

	/** Fires, campfires, lava, magma round you: their warmth (deg C). */
	private static float sources(ServerLevel level, BlockPos at) {
		float heat = 0.0F;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -4; dx <= 4; dx++) {
			for (int dy = -2; dy <= 3; dy++) {
				for (int dz = -4; dz <= 4; dz++) {
					m.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
					BlockState s = level.getBlockState(m);
					float w = 0.0F;
					if (s.is(Blocks.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE)) {
						w = 14.0F;
					} else if (s.getBlock() instanceof CampfireBlock && s.getValue(CampfireBlock.LIT)) {
						w = 16.0F;
					} else if (s.is(Blocks.MAGMA_BLOCK)) {
						w = 6.0F;
					} else if ((s.is(Blocks.FURNACE) || s.is(Blocks.BLAST_FURNACE) || s.is(Blocks.SMOKER))
						&& s.getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT)) {
						w = 8.0F;
					} else if (s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.LANTERN)) {
						w = 1.5F;
					}
					if (w > 0.0F) {
						float d = Mth.sqrt(dx * dx + dy * dy + dz * dz);
						heat += w / (1.0F + d * 0.6F);
					}
				}
			}
		}
		return Math.min(heat, 30.0F);
	}

	/** What you wear: leather (and a turtle shell, wool) keeps the cold out; metal plate is an oven in the heat. */
	private static float clothing(ServerPlayer p, float air) {
		float warm = 0.0F;
		float heavy = 0.0F;
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack s = p.getItemBySlot(slot);
			if (s.isEmpty()) {
				continue;
			}
			if (s.is(Items.LEATHER_HELMET) || s.is(Items.LEATHER_CHESTPLATE) || s.is(Items.LEATHER_LEGGINGS) || s.is(Items.LEATHER_BOOTS)) {
				warm += 4.0F;
			} else if (s.is(ItemTags.WOOL)) {
				warm += 3.0F;
			} else {
				warm += 1.0F;
				heavy += 1.5F;
			}
		}
		// in the cold clothes help; in the heat they hurt (leather a little, plate a lot)
		return air < 18.0F ? warm : (air > 26.0F ? heavy + warm * 0.25F : 0.0F);
	}

	/** The body's state for the player, -1 freezing .. 1 heat stroke. */
	public static float body(ServerPlayer p) {
		float[] s = STATE.get(p.getUUID());
		return s == null ? 0.0F : s[0];
	}

	public static void forget(ServerPlayer p) {
		STATE.remove(p.getUUID());
	}

	public static void clear() {
		STATE.clear();
	}
}
