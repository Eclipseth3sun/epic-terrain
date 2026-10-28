package com.epicterrain.earth;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import com.epicterrain.earth.Tectonics.Boundary;

/** Draws maps of a simulated continent: shaded relief, plates, uplift, climate, biomes, drainage and a cross-section. */
final class EarthImages {
	private EarthImages() {
	}

	private static int rgb(double r, double g, double b) {
		return (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
	}

	private static int clamp255(double v) {
		return (int) Math.max(0, Math.min(255, Math.round(v)));
	}

	static int mix(int a, int b, double t) {
		int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}

	/** Colour of land by height in blocks above sea. */
	static int landColour(double blocks) {
		int[] colours = { 0x4F8A3C, 0x7FA64A, 0xB5B35A, 0xA88B5A, 0x8A7560, 0xB0AAA0, 0xF4F4F4 };
		double[] stops = { 0, 25, 70, 130, 220, 320, 420 };
		if (blocks <= stops[0]) {
			return colours[0];
		}
		for (int i = 1; i < stops.length; i++) {
			if (blocks <= stops[i]) {
				return mix(colours[i - 1], colours[i], (blocks - stops[i - 1]) / (stops[i] - stops[i - 1]));
			}
		}
		return colours[colours.length - 1];
	}

	static double shade(Continent c, int x, int z) {
		int xl = Math.max(0, x - 1);
		int xr = Math.min(c.nx - 1, x + 1);
		int zu = Math.max(0, z - 1);
		int zd = Math.min(c.nz - 1, z + 1);
		double dzdx = (c.height[z * c.nx + xr] - c.height[z * c.nx + xl]) / EarthConfig.METRES_PER_BLOCK_UP / ((xr - xl) * c.cell);
		double dzdz = (c.height[zd * c.nx + x] - c.height[zu * c.nx + x]) / EarthConfig.METRES_PER_BLOCK_UP / ((zd - zu) * c.cell);
		dzdx *= 2.2;
		dzdz *= 2.2;
		// light from the north-west, 45 degrees up
		double lx = -0.5;
		double lz = -0.5;
		double ly = 0.7071;
		double nxv = -dzdx;
		double nzv = -dzdz;
		double len = Math.sqrt(nxv * nxv + nzv * nzv + 1.0);
		double dot = (nxv * lx + nzv * lz + ly) / len;
		return Math.max(0.0, dot) / 0.7071;
	}

	static BufferedImage relief(Continent c) {
		BufferedImage img = new BufferedImage(c.nx, c.nz, BufferedImage.TYPE_INT_RGB);
		for (int z = 0; z < c.nz; z++) {
			for (int x = 0; x < c.nx; x++) {
				int i = z * c.nx + x;
				double h = c.height[i];
				int colour;
				if (h < 0.0) {
					double depth = Math.min(1.0, -h / 3500.0);
					colour = mix(0x4FA0D8, 0x0B2A5A, Math.sqrt(depth));
					colour = mix(colour, rgb(255, 255, 255), 0.15 * shade(c, x, z));
				} else if (c.lakeDepth[i] > 0.05f) {
					colour = 0x5FB0E8;
				} else {
					double blocks = h / EarthConfig.METRES_PER_BLOCK_UP;
					int base = landColour(blocks);
					double s = shade(c, x, z);
					double k = 0.35 + 0.8 * s;
					colour = rgb(((base >> 16) & 255) * k, ((base >> 8) & 255) * k, (base & 255) * k);
					if (c.river[i]) {
						double strength = Math.min(1.0, 0.45 + 0.12 * Math.log10(Math.max(1.0, c.discharge[i] / 1.0e9)));
						colour = mix(colour, 0x2E7FD0, strength);
					}
				}
				img.setRGB(x, z, colour);
			}
		}
		return img;
	}

	private static int boundaryColour(int kind) {
		return switch (Boundary.values()[kind - 1]) {
			case COLLISION -> 0xE03030;
			case SUBDUCTION -> 0xF08020;
			case ISLAND_ARC -> 0xD030C0;
			case RIFT -> 0xF0D020;
			case PASSIVE -> 0x30C0D0;
			case SPREADING -> 0x3060E0;
			case TRANSFORM -> 0x40C040;
		};
	}

	static BufferedImage plates(Continent c) {
		BufferedImage img = new BufferedImage(c.nx, c.nz, BufferedImage.TYPE_INT_RGB);
		for (int z = 0; z < c.nz; z++) {
			for (int x = 0; x < c.nx; x++) {
				int i = z * c.nx + x;
				int key = c.plateKey[i] * 0x9E3779B1;
				double hue = ((key >>> 8) & 0xFFFF) / 65535.0;
				Color tint = Color.getHSBColor((float) hue, 0.25f, c.continental[i] != 0 ? 0.95f : 0.55f);
				int colour = tint.getRGB() & 0xFFFFFF;
				if (c.old[i] > 0.3f) {
					colour = mix(colour, 0x7A4FA0, 0.55);
				}
				if (c.young[i] > 0.3f) {
					colour = mix(colour, 0xC03030, 0.55);
				}
				if (c.distance[i] < 450.0f) {
					colour = boundaryColour(c.kind[i]);
				}
				img.setRGB(x, z, colour);
			}
		}
		return img;
	}

	static BufferedImage uplift(Continent c) {
		BufferedImage img = new BufferedImage(c.nx, c.nz, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < c.nx * c.nz; i++) {
			double y = Math.min(1.0, c.young[i]);
			double o = Math.min(1.0, c.old[i]);
			img.setRGB(i % c.nx, i / c.nx, rgb(40 + 215 * y, 40 + 60 * Math.max(y, o) * 0.4, 40 + 215 * o));
		}
		return img;
	}

	static BufferedImage rain(Continent c) {
		BufferedImage img = new BufferedImage(c.nx, c.nz, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < c.nx * c.nz; i++) {
			double t = Math.min(1.0, c.rain[i] / 2600.0);
			int colour = mix(0xF2E6C8, 0x103C8C, Math.sqrt(t));
			if (c.height[i] < 0.0f) {
				colour = mix(colour, 0x203050, 0.6);
			}
			img.setRGB(i % c.nx, i / c.nx, colour);
		}
		return img;
	}

	static BufferedImage biomes(Continent c) {
		int[] palette = new int[14];
		palette[Climate.OCEAN] = 0x1E4F8F;
		palette[Climate.ICE] = 0xF2F6FA;
		palette[Climate.TUNDRA] = 0xB8C4B0;
		palette[Climate.TAIGA] = 0x3E6B55;
		palette[Climate.COLD_STEPPE] = 0xC8C28A;
		palette[Climate.FOREST] = 0x3F8F3F;
		palette[Climate.GRASSLAND] = 0x9CC24F;
		palette[Climate.DESERT] = 0xE4C97A;
		palette[Climate.SAVANNA] = 0xC9A83C;
		palette[Climate.RAINFOREST] = 0x0F6B2F;
		palette[Climate.SEASONAL_FOREST] = 0x5E9B34;
		palette[Climate.SHRUBLAND] = 0xB59C6A;
		palette[Climate.ALPINE] = 0x9A9A9A;
		palette[Climate.LAKE] = 0x5FB0E8;
		BufferedImage img = new BufferedImage(c.nx, c.nz, BufferedImage.TYPE_INT_RGB);
		for (int z = 0; z < c.nz; z++) {
			for (int x = 0; x < c.nx; x++) {
				int colour = palette[c.biome[z * c.nx + x]];
				double s = c.height[z * c.nx + x] >= 0.0f ? 0.65 + 0.45 * shade(c, x, z) : 1.0;
				img.setRGB(x, z, rgb(((colour >> 16) & 255) * s, ((colour >> 8) & 255) * s, (colour & 255) * s));
			}
		}
		return img;
	}

	static BufferedImage drainage(Continent c) {
		BufferedImage img = new BufferedImage(c.nx, c.nz, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < c.nx * c.nz; i++) {
			int colour;
			if (c.height[i] < 0.0f) {
				colour = 0x101828;
			} else {
				double t = Math.max(0.0, Math.min(1.0, (Math.log10(Math.max(1.0, c.discharge[i])) - 11.5) / 5.0));
				colour = mix(0x2A2A2A, 0x30B0FF, Math.pow(t, 0.8));
				if (c.lakeDepth[i] > 0.05f) {
					colour = 0xE0F0FF;
				}
			}
			img.setRGB(i % c.nx, i / c.nx, colour);
		}
		return img;
	}

	/** Elevation along three west-east rows, in game blocks (y), with sea level. */
	static BufferedImage section(Continent c) {
		int w = 1100;
		int h = 520;
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, w, h);
		int top = 20;
		int bottom = h - 30;
		double yMin = -40;
		double yMax = 500;
		double scaleY = (bottom - top) / (yMax - yMin);
		g.setColor(new Color(0xBFE0F5));
		g.fillRect(0, (int) (bottom - (EarthConfig.SEA_LEVEL_Y - yMin) * scaleY), w, (int) ((EarthConfig.SEA_LEVEL_Y - yMin) * scaleY));
		g.setColor(Color.GRAY);
		g.setFont(new Font("SansSerif", Font.PLAIN, 11));
		for (int y = 0; y <= 500; y += 100) {
			int py = (int) (bottom - (y - yMin) * scaleY);
			g.drawLine(0, py, w, py);
			g.drawString("y " + y, 4, py - 2);
		}
		Color[] lines = { new Color(0xB03030), new Color(0x2050B0), new Color(0x208040) };
		double[] rows = { 0.30, 0.50, 0.70 };
		for (int r = 0; r < rows.length; r++) {
			int z = (int) (rows[r] * c.nz);
			g.setColor(lines[r]);
			g.setStroke(new BasicStroke(1.4f));
			int px0 = 0;
			int py0 = 0;
			for (int px = 0; px < w; px++) {
				int x = Math.min(c.nx - 1, (int) ((double) px / w * c.nx));
				double y = c.yBlocks(z * c.nx + x);
				int py = (int) (bottom - (y - yMin) * scaleY);
				if (px > 0) {
					g.drawLine(px0, py0, px, py);
				}
				px0 = px;
				py0 = py;
			}
			g.drawString(String.format("row at %.0f%% of the window", rows[r] * 100), 150 + r * 220, h - 8);
		}
		g.dispose();
		return img;
	}

	/** The relief around the highest point, magnified, to judge the valleys. */
	static BufferedImage crop(Continent c, BufferedImage relief, int half, int zoom) {
		int best = 0;
		for (int i = 0; i < c.nx * c.nz; i++) {
			if (c.height[i] > c.height[best]) {
				best = i;
			}
		}
		int cx = Math.max(half, Math.min(c.nx - half, best % c.nx));
		int cz = Math.max(half, Math.min(c.nz - half, best / c.nx));
		BufferedImage out = new BufferedImage(2 * half * zoom, 2 * half * zoom, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(relief.getSubimage(cx - half, cz - half, 2 * half, 2 * half), 0, 0, 2 * half * zoom, 2 * half * zoom, null);
		g.dispose();
		return out;
	}

	/** Lays several images out in a grid, each scaled to the given size. */
	static BufferedImage sheet(BufferedImage[] images, int columns, int size) {
		int rows = (images.length + columns - 1) / columns;
		BufferedImage out = new BufferedImage(columns * size, rows * size, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, out.getWidth(), out.getHeight());
		for (int k = 0; k < images.length; k++) {
			g.drawImage(images[k], (k % columns) * size, (k / columns) * size, size, size, null);
		}
		g.dispose();
		return out;
	}
}
