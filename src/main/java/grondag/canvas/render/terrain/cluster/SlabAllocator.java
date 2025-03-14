/*
 * Copyright © Contributing Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Additional copyright and licensing notices may apply for content that was
 * included from other projects. For more information, see ATTRIBUTION.md.
 */

package grondag.canvas.render.terrain.cluster;

import com.mojang.blaze3d.systems.RenderSystem;

import grondag.canvas.render.terrain.TerrainFormat;

public class SlabAllocator {
	private static int slabCount = 0;
	private static long usedBytes = 0;
	private static long capacityBytes = 0;

	static void addToVertexCount(int vertexCount) {
		usedBytes += vertexCount * BYTES_PER_SLAB_VERTEX;
	}

	static void notifyShutdown(Slab slab) {
		assert slab.usedVertexCount() == 0;
		--slabCount;
		capacityBytes -= slab.capacityBytes();
	}

	public static final int SLAB_QUAD_VERTEX_COUNT_INCREMENT = 0x1000;
	public static final int BYTES_PER_SLAB_VERTEX = 28;
	static final int SLAB_BYTES_INCREMENT = SLAB_QUAD_VERTEX_COUNT_INCREMENT * BYTES_PER_SLAB_VERTEX;

	static {
		// Want IDE to show actual numbers above, so check here at run time that nothing changed and got missed.
		assert BYTES_PER_SLAB_VERTEX == TerrainFormat.TERRAIN_MATERIAL.vertexStrideBytes : "Slab vertex size doesn't match vertex format";
	}

	static Slab claim(int minCapacityBytes) {
		assert RenderSystem.isOnRenderThread();
		++slabCount;
		final var result = new Slab((minCapacityBytes + SLAB_BYTES_INCREMENT - 1) / SLAB_BYTES_INCREMENT * SLAB_BYTES_INCREMENT);
		capacityBytes += result.capacityBytes();
		return result;
	}

	public static String debugSummary() {
		return String.format("%d slabs %dMb occ:%d",
				slabCount,
				capacityBytes / 0x100000L,
				capacityBytes > 0 ? usedBytes * 100L / capacityBytes : 0);
	}
}
