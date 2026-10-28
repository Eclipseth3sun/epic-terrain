package com.epicterrain.earth;

import java.util.ArrayList;
import java.util.List;

import com.epicterrain.earth.Rivers.River;

/** The four places the river previews look at: a long wide river near its end and halfway, a medium river, and a confluence. */
final class RiverSites {
	record Site(double x, double z, String label, River river, int node) {
	}

	private RiverSites() {
	}

	static List<Site> pick(List<River> rivers) {
		River widest = null;
		River longest = null;
		for (River r : rivers) {
			if (r.joins < 0 && (widest == null || r.mouthWidth() > widest.mouthWidth())) {
				widest = r;
			}
			if (longest == null || r.length > longest.length) {
				longest = r;
			}
		}
		// the widest river is often only the short outlet between two lakes: show the widest one with a reach of at least 3 km
		River showpiece = widest;
		for (River r : rivers) {
			if (r.length >= 3_000.0 && r.mouthWidth() > (showpiece.length >= 3_000.0 ? showpiece.mouthWidth() : 0.0)) {
				showpiece = r;
			}
		}
		List<Site> sites = new ArrayList<>();
		int last = showpiece.line.size() - 1;
		int near = Math.max(0, last - (int) (3_000.0 / Rivers.SPACING));
		sites.add(new Site(showpiece.line.get(near)[0], showpiece.line.get(near)[1],
			String.format("A: the widest long river, 3 km above its end (%.0f blocks wide)", showpiece.width[near]), showpiece, near));
		int half = last / 2;
		sites.add(new Site(showpiece.line.get(half)[0], showpiece.line.get(half)[1], String.format("B: the same river, halfway (%.0f blocks wide)", showpiece.width[half]), showpiece, half));
		River medium = null;
		for (River r : rivers) {
			if (r.mouthWidth() >= 10.0 && r.mouthWidth() <= 25.0 && r.length >= 1_500.0 && (medium == null || r.length > medium.length)) {
				medium = r;
			}
		}
		if (medium == null) {
			medium = longest;
		}
		int mh = medium.line.size() / 2;
		sites.add(new Site(medium.line.get(mh)[0], medium.line.get(mh)[1], String.format("C: a medium river, halfway (%.0f blocks wide)", medium.width[mh]), medium, mh));
		River tributary = null;
		for (River r : rivers) {
			if (r.joins >= 0 && r.mouthWidth() >= 8.0 && (tributary == null || r.mouthWidth() > tributary.mouthWidth())) {
				tributary = r;
			}
		}
		if (tributary == null) {
			tributary = longest;
		}
		int te = tributary.line.size() - 1;
		sites.add(new Site(tributary.line.get(te)[0], tributary.line.get(te)[1],
			String.format("D: a confluence (%.0f blocks wide joining %.0f)", tributary.mouthWidth(), tributary.joins >= 0 ? rivers.get(tributary.joins).width[0] : 0.0), tributary, te));
		return sites;
	}
}
