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

package grondag.canvas.terrain.occlusion.base;

import org.jetbrains.annotations.Nullable;

import grondag.canvas.render.frustum.TerrainFrustum;
import grondag.canvas.render.world.WorldRenderState;
import grondag.canvas.terrain.region.RegionPosition;
import grondag.canvas.terrain.region.RenderRegion;

/**
 * Manages the various components involved with region visibility testing.
 */
public abstract class AbstractVisbility<T extends AbstractVisbility<T, U, V, W>, U extends AbstractRegionVisibility<T, U>, V extends PotentiallyVisibleRegionSet<V, U>, W extends AbstractOccluder> {
	/** Handles iteration and sorting. */
	protected final V pvrs;

	/** Does the actual testing using software rasterizer. */
	protected final W occluder;

	/** For access to external state. */
	protected final WorldRenderState worldRenderState;

	private int version;
	private int pvrsVersion;
	protected long lastCameraRegionOrigin;

	protected boolean shouldInvalidateNextPass = false;

	public AbstractVisbility(WorldRenderState worldRenderState, V pvrs, W occluder) {
		this.worldRenderState = worldRenderState;
		this.pvrs = pvrs;
		this.occluder = occluder;
	}

	/**
	 * Increments when {@link #prepareForIteration()} is called
	 * and changes in state are detected that force iteration
	 * to be cleared and restarted.
	 *
	 * <p>Used to know if a region has already been visited
	 * and/or tested in this version.
	 */
	public final int version() {
		return version;
	}

	/**
	 * Call if one or more inputs have changed to
	 * force a version increment the next time
	 * {@link #prepareForIteration()} is called.
	 *
	 * <p>No immediate effect on version or other state
	 * used for iteration because it may be called while
	 * iteration is running.
	 */
	public final void invalidate() {
		shouldInvalidateNextPass = true;
	}

	/**
	 * If true, will start a new version on next iteration.
	 * Becomes true mostly due to occlusion data changes in
	 * individual regions.
	 *
	 * <p>A false result does NOT imply the next iteration
	 * won't advance the version because changes to the view
	 * frustum aren't checked until then.
	 */
	public final boolean isInvalid() {
		return shouldInvalidateNextPass;
	}

	/**
	 * Will force occluder to redraw next time it
	 * is prepared.
	 *
	 * <p>Here to allow sub-types to have extra steps
	 * while keeping {@link #prepareForIteration()} final.
	 */
	protected void invalidateOccluder() {
		occluder.invalidate();
	}

	/**
	 * Will return true if occluder redraw, and thus
	 * a version increment, is needed.
	 *
	 * <p>Here to allow sub-types to have extra steps
	 * while keeping {@link #prepareForIteration()} final.
	 */
	protected boolean prepareOccluder() {
		return occluder.prepareScene();
	}

	/**
	 * Call before iteration. If true, all prior
	 * results are invalid and visible regions should be cleared.
	 * In any case, iteration is set to beginning.
	 */
	public final boolean prepareForIteration() {
		boolean result = shouldInvalidateNextPass;
		shouldInvalidateNextPass = false;

		if (pvrsVersion != pvrs.version()) {
			result = true;
		}

		if (result) {
			invalidateOccluder();
		}

		if (prepareOccluder()) {
			result = true;
		}

		if (result) {
			++version;
			pvrs.clear();
		} else {
			pvrs.returnToStart();
		}

		pvrsVersion = pvrs.version();

		return result;
	}

	public abstract U createRegionState(RenderRegion region);

	/**
	 * Call before {@link #prepareForIteration()}. Separate
	 * from that method because some view information
	 * (in the shadow sub-type) must be current during region
	 * origin updates.
	 */
	public void updateView(TerrainFrustum frustum, long cameraRegionOrigin) {
		if (lastCameraRegionOrigin != cameraRegionOrigin) {
			lastCameraRegionOrigin = cameraRegionOrigin;
			shouldInvalidateNextPass = true;
		}
	}

	public void add(U pvsRegionState) {
		pvrs.add(pvsRegionState);
	}

	/**
	 * Returns next region in sorted iteration and advances for next call.
	 * Returns null if at end or if set is empty.
	 */
	public @Nullable U next() {
		return pvrs.next();
	}

	public void outputRaster() {
		occluder.outputRaster();
	}

	/**
	 * Same purpose as {@link AbstractOccluder#isEmptyRegionVisible(net.minecraft.core.BlockPos)}
	 * but may have extra steps for state management or shadow maps.
	 */
	public boolean isEmptyRegionVisible(RegionPosition origin, int fuzz) {
		return occluder.isEmptyRegionVisible(origin, fuzz);
	}

	/**
	 * Same purpose as {@link AbstractOccluder#occlude(int[])}
	 * but may have extra steps for state management or shadow maps.
	 */
	public abstract void occlude(int[] occlusionData);

	/**
	 * Same purpose as {@link AbstractOccluder#prepareRegion(RegionPosition)}
	 * but may have extra steps for state management or shadow maps.
	 */
	public abstract void prepareRegion(RegionPosition origin);

	/**
	 * Same purpose as {@link AbstractOccluder#isBoxVisible(int)}
	 * but may have extra steps for state management or shadow maps.
	 */
	public abstract boolean isBoxVisible(int i, int fuzz);
}
