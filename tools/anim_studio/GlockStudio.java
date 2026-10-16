import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.client.gun.GlockMotion;
import de.rcm.ballistic.client.render.BoxMesh;
import de.rcm.ballistic.client.render.GlockItemRenderer;
import de.rcm.ballistic.gun.GlockItem;
import java.io.*;
import java.lang.reflect.*;
import javax.imageio.ImageIO;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Offline studio for the Glock: scripted scenes through GlockMotion, out as camera-space quads per frame. */
public class GlockStudio {
	static BoxMesh FRAME, SLIDE, BARREL, MAG, MAG_EXT, ROUND, CASE;

	public static void main(String[] a) throws Exception {
		Studio.atlas = ImageIO.read(new File(a[0]));
		Class<?> r = Class.forName("de.rcm.ballistic.client.render.GlockItemRenderer");
		FRAME = Studio.mesh(r, "FRAME"); SLIDE = Studio.mesh(r, "SLIDE"); BARREL = Studio.mesh(r, "BARREL"); MAG = Studio.mesh(r, "MAG");
		MAG_EXT = Studio.mesh(r, "MAG_EXT"); ROUND = Studio.mesh(r, "ROUND"); CASE = Studio.mesh(r, "CASE");
		String dir = a[1];
		String[] scenes = a.length > 2 ? a[2].split(",") : new String[] {"ready", "aim_shoot", "hip_shoot", "last_round", "reload_tac", "reload_empty", "draw",
			"inspect", "sprint", "walk", "dry"};
		for (String scene : scenes) {
			try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(dir + "/g_" + scene + ".bin")))) {
				Studio.out = o;
				run(scene);
			}
		}
	}

	static float smooth(float x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }

	static void run(String scene) throws IOException {
		GlockMotion m = new GlockMotion();
		float aim = 0, prevAim = 0, sprint = 0, prevSprint = 0, walk = 0, prevWalk = 0, stride = 0;
		float lastShot = -100, dryTick = -100, equipTick = -100, reloadStart = -1, inspectStart = -1000;
		boolean locked = false, empty = false;
		int rounds = 15, kind = 0, newKind = 0;
		int length = switch (scene) {
			case "reload_tac" -> 36; case "reload_empty" -> 46; case "inspect" -> 70; case "draw" -> 24; case "sprint", "walk" -> 40; case "ready" -> 30;
			default -> 36;
		};
		float fps = 3.0F;
		int frames = (int) (length * fps);
		Studio.out.writeInt(frames);
		float prevT = 0;
		for (int i = 0; i < frames; i++) {
			float t = i / fps;
			int tick = (int) Math.floor(t);
			if (i == 0 || (int) Math.floor(prevT) != tick) {
				prevAim = aim; prevSprint = sprint; prevWalk = walk;
				boolean wantAim = scene.equals("aim_shoot") && tick >= 3 || scene.equals("last_round") && tick >= 2;
				aim = wantAim ? Math.min(1, aim + 0.25F) : Math.max(0, aim - 0.25F);
				float wantRun = scene.equals("sprint") && tick >= 3 ? 1 : 0;
				sprint += (wantRun - sprint) * 0.25F;
				float wantWalk = scene.equals("walk") && tick >= 3 ? 1 : 0;
				walk += (wantWalk - walk) * 0.2F;
				boolean shoot = scene.equals("aim_shoot") && (tick == 12 || tick == 19 || tick == 26)
					|| scene.equals("hip_shoot") && (tick == 5 || tick == 8 || tick == 11 || tick == 14 || tick == 17)
					|| scene.equals("last_round") && tick == 14;
				if (shoot && lastShot != tick) {
					lastShot = tick;
					if (scene.equals("last_round")) { rounds = 0; locked = true; }
					m.kick(1 - 0.35F * smooth(aim), (float) Math.sin(tick * 1.7) * 0.8F, (float) (0.5 + 0.5 * Math.sin(tick * 3.1)));
				}
				if (scene.equals("dry") && tick == 5) dryTick = 5;
				if (scene.equals("draw") && tick == 2) equipTick = 2;
				if (scene.equals("inspect") && tick == 3) inspectStart = 3;
				if (scene.startsWith("reload") && tick == 3 && reloadStart < 0) {
					reloadStart = 3;
					empty = scene.equals("reload_empty");
					locked = empty;
					newKind = empty ? 1 : 0;
				}
			}
			float dt = i == 0 ? 0 : t - prevT;
			prevT = t;
			float partial = t - (float) Math.floor(t);
			float aimE = smooth(prevAim + (aim - prevAim) * partial);
			m.step(dt, 0, 0, aimE);
			if (scene.equals("sprint") || scene.equals("walk")) stride += dt * 0.65F * (scene.equals("sprint") ? 1.3F : 1.0F);
			float reload = reloadStart >= 0 && t >= reloadStart ? t - reloadStart : -1;
			if (reload >= (empty ? GlockItem.RELOAD_EMPTY : GlockItem.RELOAD_TACTICAL)) { reload = -1; locked = false; kind = newKind; }
			float inspect = t - inspectStart >= 0 && t - inspectStart < GlockMotion.INSPECT ? t - inspectStart : -1;
			GlockMotion.Frame f = new GlockMotion.Frame(t, aimE, t - lastShot, locked && reload < 0, reload, empty, true, kind, newKind, t - dryTick, inspect,
				0, t - equipTick, smooth(prevSprint + (sprint - prevSprint) * partial), stride, prevWalk + (walk - prevWalk) * partial, 0, 100, 0.8F, 0, 0, 0);
			frame(m, f, reload, kind, newKind, rounds > 0);
		}
	}

	static void frame(GlockMotion m, GlockMotion.Frame f, float reload, int kind, int newKind, boolean chambered) throws IOException {
		Studio.buf = new ByteArrayOutputStream();
		DataOutputStream b = new DataOutputStream(Studio.buf);
		Studio.quads = 0;
		PoseStack ps = new PoseStack();
		ps.translate(0.56F, -0.52F, -0.72F);
		Matrix4f base = new Matrix4f(ps.last().pose());
		m.apply(ps, f);
		ps.translate(-3.2F / 16, 2.4F / 16, 0.5F / 16);
		ps.mulPose(Axis.YP.rotationDegrees(3.0F));
		Matrix4f item = new Matrix4f(ps.last().pose());
		Studio.emit(b, item, FRAME, null);
		Studio.emit(b, item, BARREL, null);
		float slide = GlockMotion.slide(f);
		float back = slide * GlockItemRenderer.SLIDE_TRAVEL;
		boolean ch = chambered && !(reload >= 0 && f.empty() && reload < GlockItem.T_RACK_FORWARD);
		if (ch && back > 0.008F) Studio.emit(b, new Matrix4f(item).translate(0, GlockItemRenderer.BORE_Y, -0.011F + 0.002F), ROUND, null);
		Studio.emit(b, new Matrix4f(item).translate(0, 0, back), SLIDE, null);
		boolean magIn = reload < 0 || reload < GlockItem.T_RELEASE || reload >= GlockItem.T_IN;
		int k = reload >= GlockItem.T_IN ? newKind : kind;
		if (magIn) mag(b, item, null, k);
		else { Matrix4f nm = GlockMotion.newMag(f); if (nm != null) mag(b, item, nm, newKind); }
		Matrix4f om = GlockMotion.oldMag(f);
		if (om != null) mag(b, item, om, kind);
		float e = f.shot() - 0.3F;
		if (e >= 0 && e < 6) {
			Matrix4f c = new Matrix4f(item).translate(0.0128F + 0.09F * e, GlockItemRenderer.BORE_Y + 0.045F * e - 0.011F * e * e, -0.03F + 0.01F * e)
				.rotate(Axis.YP.rotationDegrees(-70 * e)).rotate(Axis.XP.rotationDegrees(85 * e));
			Studio.emit(b, c, CASE, null);
		}
		if (System.getenv("NOARMS") != null) { Studio.out.writeInt(Studio.quads); Studio.out.write(Studio.buf.toByteArray()); return; }
		GlockMotion.Hands h = GlockMotion.hands(f);
		Matrix4f toItem = new Matrix4f(item).invert().mul(base);
		float scale = toItem.transformDirection(new Vector3f(1, 0, 0)).length();
		Vector3f rs = toItem.transformPosition(h.rightShoulder(), new Vector3f()), ls = toItem.transformPosition(h.leftShoulder(), new Vector3f());
		float fo = GlockMotion.FOREARM, th = GlockMotion.ARM_THICK;
		Vector3f re = Studio.elbow(rs, h.right(), Studio.RIGHT_UPPER * scale, Studio.FOREARM * fo * scale, toItem.transformDirection(new Vector3f(h.rightPole())));
		Vector3f le = Studio.elbow(ls, h.left(), Studio.LEFT_UPPER * scale, Studio.FOREARM * fo * scale, toItem.transformDirection(new Vector3f(h.leftPole())));
		arm(b, item, h.right(), re, -1, fo, false, th);
		arm(b, item, h.left(), le, 1, fo, false, th);
		arm(b, item, re, rs, -1, re.distance(rs) / 0.75F, true, th);
		arm(b, item, le, ls, 1, le.distance(ls) / 0.75F, true, th);
		Studio.out.writeInt(Studio.quads);
		Studio.out.write(Studio.buf.toByteArray());
	}

	static void arm(DataOutputStream b, Matrix4f item, Vector3f from, Vector3f to, float fistX, float length, boolean reversed, float thick) throws IOException {
		Vector3f down = reversed ? new Vector3f(to).sub(from) : new Vector3f(from).sub(to);
		if (down.lengthSquared() < 1e-6F) return;
		down.normalize();
		Vector3f side = new Vector3f(-1, 0, 0);
		side.sub(new Vector3f(down).mul(side.dot(down)));
		if (side.lengthSquared() < 1e-4F) side.set(0, 0, 1).sub(new Vector3f(down).mul(down.z));
		side.normalize();
		Vector3f back = new Vector3f(side).cross(down);
		org.joml.Quaternionf rot = new org.joml.Quaternionf().setFromNormalized(new org.joml.Matrix3f(side, down, back));
		Matrix4f mm = new Matrix4f(item).translate(from).rotate(rot).scale(thick, Math.max(0.05F, length), thick);
		if (reversed) mm.translate(-fistX / 16, 1 / 16F, 0); else mm.translate(-fistX / 16, -8.5F / 16, 0);
		float x0 = (fistX < 0 ? -3 : -1) / 16F, x1 = x0 + 4 / 16F;
		Studio.box(b, mm, x0, -2 / 16F, -2 / 16F, x1, 6 / 16F, 2 / 16F, 0x2E5C8A);
		Studio.box(b, mm, x0, 6 / 16F, -2 / 16F, x1, 10 / 16F, 2 / 16F, 0xC6926B);
	}

	static void mag(DataOutputStream b, Matrix4f item, Matrix4f from, int kind) throws IOException {
		Matrix4f mm = new Matrix4f(item).translate(GlockItemRenderer.MAG_SEAT);
		if (from != null) mm.mul(from);
		mm.rotate(Axis.XP.rotationDegrees(-GlockItemRenderer.GRIP_ANGLE));
		Studio.emit(b, mm, kind == 1 ? MAG_EXT : MAG, null);
	}
}
