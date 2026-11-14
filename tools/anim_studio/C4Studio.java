import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.client.gun.C4Client;
import de.rcm.ballistic.client.gun.C4Motion;
import de.rcm.ballistic.client.render.BoxMesh;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.util.Map;
import javax.imageio.ImageIO;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Offline studio for C4 and its firing device: scripted scenes through C4Motion, out as camera-space quads per frame. */
public class C4Studio {
	static BoxMesh CHARGE, BODY, COVER, BUTTON, SWITCH;
	static BufferedImage TC, TD;
	static final Vector3f HINGE = new Vector3f(0.012F, 0.0265F, -0.0165F), BTN = new Vector3f(0.012F, 0.016F, -0.03F), PIVOT = new Vector3f(-0.015F, 0.02F, -0.004F);

	/** The mesh file (structkit export): parts as quads, 8 floats a corner. */
	static Map<String, BoxMesh> load(String p) throws Exception {
		Map<String, BoxMesh> out = new java.util.HashMap<>();
		Constructor<BoxMesh> k = BoxMesh.class.getDeclaredConstructor(float[].class);
		k.setAccessible(true);
		try (DataInputStream in = new DataInputStream(new java.util.zip.GZIPInputStream(new FileInputStream("/home/user/RCM-Mod/src/client/resources" + p)))) {
			int parts = in.readInt();
			for (int i = 0; i < parts; i++) {
				byte[] nm = new byte[in.readUnsignedShort()];
				in.readFully(nm);
				float[] f = new float[in.readInt()];
				for (int j = 0; j < f.length; j++) f[j] = in.readFloat();
				out.put(new String(nm), k.newInstance((Object) f));
			}
		}
		return out;
	}

	public static void main(String[] a) throws Exception {
		Map<String, BoxMesh> c = load("/assets/ballisticmissiles/meshes/c4_charge.mesh.gz");
		Map<String, BoxMesh> d = load("/assets/ballisticmissiles/meshes/c4_detonator.mesh.gz");
		CHARGE = c.get("charge"); BODY = d.get("body"); COVER = d.get("cover"); BUTTON = d.get("button"); SWITCH = d.get("switch");
		String tex = "/home/user/RCM-Mod/src/main/resources/assets/ballisticmissiles/textures/entity/";
		TC = ImageIO.read(new File(tex + "c4_charge.png")); TD = ImageIO.read(new File(tex + "c4_detonator.png"));
		String dir = a[0];
		String[] scenes = a.length > 1 ? a[1].split(",") : new String[] {"charge_idle", "charge_place", "device_idle", "device_fire", "device_select"};
		for (String s : scenes) {
			try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(dir + "/c_" + s + ".bin")))) {
				Studio.out = o;
				run(s);
			}
		}
	}

	static void run(String scene) throws IOException {
		int length = switch (scene) { case "charge_place" -> 44; case "device_fire" -> 54; case "device_select" -> 20; default -> 30; };
		float fps = 3.0F;
		int frames = (int) (length * fps);
		Studio.out.writeInt(frames);
		for (int i = 0; i < frames; i++) {
			float t = i / fps;
			Studio.buf = new ByteArrayOutputStream();
			DataOutputStream b = new DataOutputStream(Studio.buf);
			Studio.quads = 0;
			PoseStack ps = new PoseStack();
			ps.translate(0.56F, -0.52F, -0.72F);
			Matrix4f base = new Matrix4f(ps.last().pose());
			C4Motion.Hands h;
			if (scene.startsWith("charge")) {
				float use = scene.equals("charge_place") && t >= 3 && t < 3 + C4Motion.PLACE ? t - 3 : -1;
				float draw = scene.equals("charge_idle") ? t - 2 : (scene.equals("charge_place") && t >= 3 + C4Motion.PLACE ? t - 3 - C4Motion.PLACE : 100);
				ps.mulPose(C4Motion.chargeHold(t, draw, use));
				ps.translate(0, 2 / 16F, 0);
				Matrix4f item = new Matrix4f(ps.last().pose());
				emit(b, new Matrix4f(item).mul(C4Motion.chargeModel(use)), CHARGE, TC);
				h = C4Motion.chargeHands(use);
				arms(b, base, item, h);
			} else {
				float fire = scene.equals("device_fire") && t >= 3 ? t - 3 : -1;
				float sel = scene.equals("device_select") && t >= 3 ? t - 3 : -1;
				float draw = scene.equals("device_idle") ? t - 2 : 100;
				ps.mulPose(C4Motion.deviceHold(t, draw, fire, sel));
				ps.translate(0, 2 / 16F, 0);
				Matrix4f item = new Matrix4f(ps.last().pose());
				Matrix4f model = new Matrix4f(item).mul(C4Motion.deviceModel());
				C4Client.DevicePose p = C4Motion.devicePose(fire);
				emit(b, model, BODY, TD);
				emit(b, new Matrix4f(model).translate(HINGE).rotateX((float) Math.toRadians(p.cover())), COVER, TD);
				emit(b, new Matrix4f(model).translate(BTN.x, BTN.y - p.press(), BTN.z), BUTTON, TD);
				emit(b, new Matrix4f(model).translate(PIVOT).rotateX((float) Math.toRadians(p.armed() ? -50 : 0)), SWITCH, TD);
				h = C4Motion.deviceHands(fire);
				arms(b, base, item, h);
			}
			Studio.out.writeInt(Studio.quads);
			Studio.out.write(Studio.buf.toByteArray());
		}
	}

	static void arms(DataOutputStream b, Matrix4f base, Matrix4f item, C4Motion.Hands h) throws IOException {
		if (System.getenv("NOARMS") != null) return;
		Matrix4f toItem = new Matrix4f(item).invert().mul(base);
		float scale = toItem.transformDirection(new Vector3f(1, 0, 0)).length();
		Vector3f rs = toItem.transformPosition(new Vector3f(Studio.RIGHT_SHOULDER), new Vector3f()), ls = toItem.transformPosition(new Vector3f(Studio.LEFT_SHOULDER), new Vector3f());
		Vector3f re = Studio.elbow(rs, h.right(), Studio.RIGHT_UPPER * scale, Studio.FOREARM * scale, toItem.transformDirection(new Vector3f(Studio.RIGHT_POLE)));
		Vector3f le = Studio.elbow(ls, h.left(), Studio.LEFT_UPPER * scale, Studio.FOREARM * scale, toItem.transformDirection(new Vector3f(Studio.LEFT_POLE)));
		Studio.arm(b, item, h.right(), re, -1, 1, false);
		Studio.arm(b, item, h.left(), le, 1, 1, false);
		Studio.arm(b, item, re, rs, -1, re.distance(rs) / 0.75F, true);
		Studio.arm(b, item, le, ls, 1, le.distance(ls) / 0.75F, true);
	}

	static void emit(DataOutputStream b, Matrix4f m, BoxMesh mesh, BufferedImage tex) throws IOException {
		float[] d;
		try { Method vm = BoxMesh.class.getDeclaredMethod("vertices"); vm.setAccessible(true); d = (float[]) vm.invoke(mesh); } catch (Exception e) { throw new RuntimeException(e); }
		for (int i = 0; i + 32 <= d.length; i += 32) {
			Vector3f[] v = new Vector3f[4];
			float u = 0, w = 0;
			for (int k = 0; k < 4; k++) { int j = i + k * 8; v[k] = m.transformPosition(new Vector3f(d[j], d[j + 1], d[j + 2])); u += d[j + 3]; w += d[j + 4]; }
			int px = Math.min(tex.getWidth() - 1, Math.max(0, (int) (u / 4 * tex.getWidth()))), py = Math.min(tex.getHeight() - 1, Math.max(0, (int) (w / 4 * tex.getHeight())));
			Studio.quad(b, v, tex.getRGB(px, py) & 0xFFFFFF);
		}
	}
}
