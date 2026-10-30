package de.rcm.ballistic.client.gun;

import de.rcm.ballistic.gun.GlockMagazineItem;
import java.util.Map;

/**
 * The Glock's animations, keyframed in Blender (tools/ak47_blender/glock_anim.py) and sampled four times a tick
 * in the AK's layout ({@link AkClips}): the pistol's pose in the hands, the magazine coming out and the one
 * going in, both hands, the slide and which magazine shows - for the speed reload (with rounds left or from
 * slide lock, a flush or an extended magazine going in), looking it over with the press check, the dry click,
 * the draw and the idle look.
 */
final class GlockClips {
	private static final Map<String, AkClips.Clip> CLIPS = AkClips.load("/assets/ballisticmissiles/meshes/glock19.anim.gz");

	private GlockClips() {
	}

	static AkClips.Clip get(String name) {
		return CLIPS.get(name);
	}

	/** @param seed picks the take (the reload's start tick) */
	static AkClips.Clip reload(boolean empty, int newKind, int variant, long seed) {
		String name = "reload_" + (empty ? "empty" : "tac") + "_" + (newKind == GlockMagazineItem.EXTENDED ? "ext" : "std");
		if (!empty && variant == de.rcm.ballistic.gun.GunState.FUMBLE) {
			name += "_fumble";
		} else if (!empty && variant == de.rcm.ballistic.gun.GunState.DROPPED) {
			name += "_drop";
		}
		return AkClips.take(CLIPS, name, seed);
	}
}
