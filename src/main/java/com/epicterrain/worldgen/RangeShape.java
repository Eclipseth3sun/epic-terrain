package com.epicterrain.worldgen;

/**
 * One epic range as geometry: the line, arc or bowl its spine follows, in the range's own space.
 *
 * <p>Ranges are placed in a gently warped copy of the world (see {@link RangeField}), so a spine point returned here is a
 * position in that warped space; {@link RangeCatalog#toWorld} converts it to a real block position.
 *
 * @param type      line, arc, or bowl (an arc that nearly closes)
 * @param wall      true for a Teton-style wall range, false for a range with foothills
 * @param length    spine length of a line, in blocks (0 for arcs and bowls)
 * @param radius    radius of an arc or bowl, in blocks (0 for lines)
 * @param sweep     angle an arc or bowl covers, in radians (0 for lines)
 * @param taper     fraction of the spine at each end over which the range fades out
 */
public record RangeShape(
	int cellX, int cellZ, int slot, Type type, boolean wall, double centerX, double centerZ,
	double angle, double length, double radius, double sweep, double heightRoll, double taper
) {
	public enum Type {
		LINE, ARC, BOWL
	}

	/** A stable name for the range, e.g. {@code 3,-2#1}. */
	public String id() {
		return this.cellX + "," + this.cellZ + "#" + this.slot;
	}

	/** Length of the spine in blocks. */
	public double spineLength() {
		return this.type == Type.LINE ? this.length : this.radius * this.sweep;
	}

	/** The spine at fraction {@code t} (0 to 1) as {@code {x, z, tangentX, tangentZ}}, in the range's own space. */
	public double[] spine(double t) {
		if (this.type == Type.LINE) {
			double ux = Math.cos(this.angle);
			double uz = Math.sin(this.angle);
			double along = (t - 0.5) * this.length;
			return new double[] { this.centerX + ux * along, this.centerZ + uz * along, ux, uz };
		}
		double a = this.angle + t * this.sweep;
		return new double[] { this.centerX + this.radius * Math.cos(a), this.centerZ + this.radius * Math.sin(a), -Math.sin(a), Math.cos(a) };
	}
}
