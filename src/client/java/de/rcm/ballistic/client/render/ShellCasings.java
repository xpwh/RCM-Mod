package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.AK_BLUED;
import static de.rcm.ballistic.client.render.StructureKit.BRASS;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.gun.GunAudio;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

/**
 * Spent cases, thrown out of the ejection port: they tumble through the air, bounce and clink on
 * whatever they land on, roll a little and lie there glinting for a while before they are gone.
 * Purely client-side, like particles.
 */
public final class ShellCasings {
	private static final int CAP = 96;
	private static final int LIFE = 400;
	private static final BoxMesh CASE = mesh();
	private static final List<Shell> SHELLS = new ArrayList<>();

	private ShellCasings() {
	}

	private static final class Shell {
		double x, y, z, px, py, pz;
		double vx, vy, vz;
		float yaw, pitch, spin, pyaw, ppitch;
		int age;
		int bounces;
		boolean resting;
	}

	/** The 7.62x39 case: bottle-necked, extractor groove, rim (a little bigger than life so it reads). */
	private static BoxMesh mesh() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float k = 1.5F;
		b.revolve(BRASS, new Vector3f(0, 0, 0), new Vector3f(0, 0, 1),
			new float[][] {{0.0F, 0.0056F * k}, {0.0015F * k, 0.0056F * k}, {0.002F * k, 0.0046F * k}, {0.0035F * k, 0.0046F * k},
				{0.004F * k, 0.0056F * k}, {0.029F * k, 0.0052F * k}, {0.032F * k, 0.0043F * k}, {0.039F * k, 0.0043F * k}}, 8);
		b.revolve(AK_BLUED, new Vector3f(0, 0, -0.0002F), new Vector3f(0, 0, 1), new float[][] {{0.0F, 0.0F}, {0.0004F, 0.0022F * k}}, 6); // primer
		return b.build();
	}

	/** Throw a case from {@code at} with velocity {@code v} (blocks per tick). */
	public static void eject(Vec3 at, Vec3 v) {
		if (SHELLS.size() >= CAP) {
			SHELLS.remove(0);
		}
		Shell s = new Shell();
		s.x = s.px = at.x;
		s.y = s.py = at.y;
		s.z = s.pz = at.z;
		s.vx = v.x;
		s.vy = v.y;
		s.vz = v.z;
		s.yaw = s.pyaw = (float) Math.toDegrees(Math.atan2(v.x, v.z));
		s.spin = 40.0F + (float) Math.random() * 50.0F;
		SHELLS.add(s);
	}

	public static void tick(Minecraft mc) {
		Level level = mc.level;
		if (level == null) {
			SHELLS.clear();
			return;
		}
		for (int i = SHELLS.size() - 1; i >= 0; i--) {
			Shell s = SHELLS.get(i);
			s.px = s.x;
			s.py = s.y;
			s.pz = s.z;
			s.pyaw = s.yaw;
			s.ppitch = s.pitch;
			if (++s.age > LIFE) {
				SHELLS.remove(i);
				continue;
			}
			if (s.resting) {
				continue;
			}
			s.vy -= 0.045;
			s.vx *= 0.985;
			s.vy *= 0.985;
			s.vz *= 0.985;
			s.pitch += s.spin;
			s.yaw += s.spin * 0.15F;
			// move axis by axis so a case can bounce off a wall as well as the floor
			double nx = s.x + s.vx;
			if (solid(level, nx, s.y, s.z)) {
				s.vx *= -0.3;
				nx = s.x;
				hit(level, s);
			}
			s.x = nx;
			double nz = s.z + s.vz;
			if (solid(level, s.x, s.y, nz)) {
				s.vz *= -0.3;
				nz = s.z;
				hit(level, s);
			}
			s.z = nz;
			double ny = s.y + s.vy;
			if (solid(level, s.x, ny, s.z)) {
				if (s.vy < 0.0) {
					ny = floor(level, s.x, ny, s.z) + 0.009;
					boolean loud = Math.abs(s.vy) > 0.12;
					s.vy *= -0.32;
					s.vx *= 0.55;
					s.vz *= 0.55;
					s.spin *= 0.45F;
					if (loud || s.bounces == 0) {
						hit(level, s);
					}
					if (Math.abs(s.vy) < 0.04 && s.vx * s.vx + s.vz * s.vz < 0.0016) {
						// comes to rest on its side
						s.resting = true;
						s.pitch = 0.0F;
						s.vy = 0.0;
					}
				} else {
					s.vy = 0.0;
					ny = s.y;
				}
			}
			s.y = ny;
		}
	}

	private static void hit(Level level, Shell s) {
		s.bounces++;
		if (s.bounces <= 3) {
			float volume = s.bounces == 1 ? 0.35F : 0.18F;
			GunAudio.play(ModRegistry.SHELL_DROP, new Vec3(s.x, s.y, s.z), volume, 0.9F + (float) Math.random() * 0.35F);
		}
	}

	private static boolean solid(Level level, double x, double y, double z) {
		BlockPos pos = BlockPos.containing(x, y, z);
		VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
		if (shape.isEmpty()) {
			return false;
		}
		double lx = x - pos.getX();
		double ly = y - pos.getY();
		double lz = z - pos.getZ();
		for (var box : shape.toAabbs()) {
			if (lx >= box.minX && lx <= box.maxX && ly >= box.minY && ly <= box.maxY && lz >= box.minZ && lz <= box.maxZ) {
				return true;
			}
		}
		return false;
	}

	/** Top of the collision shape under the point. */
	private static double floor(Level level, double x, double y, double z) {
		BlockPos pos = BlockPos.containing(x, y, z);
		VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
		return pos.getY() + (shape.isEmpty() ? 0.0 : shape.max(net.minecraft.core.Direction.Axis.Y));
	}

	public static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (SHELLS.isEmpty() || mc.level == null) {
			return;
		}
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		PoseStack poseStack = context.matrices();
		for (Shell s : SHELLS) {
			double x = Mth.lerp(partial, s.px, s.x);
			double y = Mth.lerp(partial, s.py, s.y);
			double z = Mth.lerp(partial, s.pz, s.z);
			if ((x - cam.x) * (x - cam.x) + (y - cam.y) * (y - cam.y) + (z - cam.z) * (z - cam.z) > 48 * 48) {
				continue;
			}
			int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(x, y + 0.05, z));
			poseStack.pushPose();
			poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
			poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partial, s.pyaw, s.yaw)));
			poseStack.mulPose(Axis.XP.rotationDegrees(s.resting ? 0.0F : Mth.lerp(partial, s.ppitch, s.pitch)));
			poseStack.translate(0.0F, 0.0F, -0.03F); // turn about the middle of the case
			context.commandQueue().submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> CASE.emit(pose, consumer, light));
			poseStack.popPose();
		}
	}
}
