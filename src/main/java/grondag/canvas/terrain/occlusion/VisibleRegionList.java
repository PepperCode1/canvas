/*
 *  Copyright 2019, 2020 grondag
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *  use this file except in compliance with the License.  You may obtain a copy
 *  of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 *  License for the specific language governing permissions and limitations under
 *  the License.
 */

package grondag.canvas.terrain.occlusion;

import java.util.Arrays;

import net.minecraft.client.MinecraftClient;

import grondag.canvas.terrain.region.BuiltRenderRegion;

public final class VisibleRegionList {
	private static final int MAX_REGION_COUNT = (32 * 2 + 1) * (32 * 2 + 1) * 24;
	private final BuiltRenderRegion[] visibleRegions = new BuiltRenderRegion[MAX_REGION_COUNT];
	private volatile int visibleRegionCount = 0;
	private int lastSortPositionVersion;

	public void clear() {
		visibleRegionCount = 0;
		Arrays.fill(visibleRegions, null);
	}

	public void add(BuiltRenderRegion builtRegion) {
		visibleRegions[visibleRegionCount++] = builtRegion;
	}

	public void copyFrom(VisibleRegionList source) {
		final int count = source.visibleRegionCount;
		visibleRegionCount = count;
		System.arraycopy(source.visibleRegions, 0, visibleRegions, 0, count);
	}

	public int size() {
		return visibleRegionCount;
	}

	public BuiltRenderRegion get(int index) {
		return visibleRegions[index];
	}

	public int getActiveCount() {
		int result = 0;
		final int limit = visibleRegionCount;

		for (int i = 0; i < limit; i++) {
			final BuiltRenderRegion region = visibleRegions[i];

			if (!region.solidDrawable().isClosed() || !region.translucentDrawable().isClosed()) {
				++result;
			}
		}

		return result;
	}

	public void scheduleResort(int positionVersion) {
		if (lastSortPositionVersion != positionVersion) {
			final MinecraftClient mc = MinecraftClient.getInstance();
			mc.getProfiler().push("translucent_sort");
			final int limit = visibleRegionCount;

			for (int i = 0; i < limit; i++) {
				visibleRegions[i].scheduleSort();
			}

			lastSortPositionVersion = positionVersion;
			mc.getProfiler().pop();
		}
	}
}
