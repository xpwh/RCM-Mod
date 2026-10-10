package de.rcm.ballistic.explosion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Matter-antimatter annihilation: everything inside a perfect sphere is converted to light. The
 * sphere grows shell by shell over about a second (so the server keeps up), and the edge is razor
 * sharp - no crater rim, no debris, no fire, no fallout. Bedrock-like blocks survive.
 */
public class AntimatterDetonation {
	public static final int RADIUS = 30;
	private static final int SHELL_PER_TICK = 2;

	private final ServerLevel level;
	private final Vec3 center;
	private final BlockPos origin;
	private final @Nullable Entity source;
	private int done = -1;
	private int age;

	public AntimatterDetonation(ServerLevel level, Vec3 center, @Nullable Entity source) {
		this.level = level;
		this.center = center;
		this.origin = BlockPos.containing(center);
		this.source = source;
	}

	public ServerLevel level() {
		return this.level;
	}

	/** @return true when finished */
	public boolean tick() {
		if (this.age++ % 20 == 0) {
			this.level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, new ChunkPos(this.origin), Mth.ceil(RADIUS / 16.0) + 1);
		}
		int from = this.done + 1;
		int to = Math.min(RADIUS, this.done + SHELL_PER_TICK);
		this.annihilate(from, to);
		this.done = to;
		return this.done >= RADIUS;
	}

	/** Removes all blocks and kills everything whose distance from the centre lies in (from-1, to]. */
	private void annihilate(int from, int to) {
		double inner = Math.max(0, from - 1);
		double outer = to + 0.5;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
		for (int dx = -to; dx <= to; dx++) {
			for (int dy = -to; dy <= to; dy++) {
				for (int dz = -to; dz <= to; dz++) {
					double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
					if (d <= inner || d > outer) {
						continue;
					}
					pos.set(this.origin.getX() + dx, this.origin.getY() + dy, this.origin.getZ() + dz);
					if (pos.getY() < this.level.getMinY() || pos.getY() > this.level.getMaxY() || !this.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
						continue;
					}
					BlockState state = this.level.getBlockState(pos);
					if (state.isAir() || state.getDestroySpeed(this.level, pos) < 0.0F) {
						continue;
					}
					// water and lava inside the sphere vanish too; what flows back in afterwards is fine
					this.level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
				}
			}
		}
		AABB box = new AABB(this.center, this.center).inflate(outer);
		for (Entity e : this.level.getEntities((Entity) null, box, e -> e.position().distanceTo(this.center) <= outer)) {
			if (e instanceof Player p && (p.isCreative() || p.isSpectator())) {
				continue;
			}
			if (e instanceof LivingEntity living) {
				living.hurtServer(this.level, this.level.damageSources().explosion(this.source, null), 100000.0F);
			} else if (e != this.source) {
				e.discard();
			}
		}
	}
}
