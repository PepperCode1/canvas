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

package grondag.canvas.terrain.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import grondag.bitraster.PackedBox;
import grondag.canvas.pipeline.Pipeline;
import grondag.canvas.render.frustum.TerrainFrustum.RegionVisibilityTest;
import grondag.canvas.render.terrain.drawlist.DrawListCullingHelper;
import grondag.canvas.render.world.WorldRenderState;
import grondag.canvas.terrain.occlusion.TerrainIterator;
import grondag.canvas.terrain.occlusion.camera.CameraVisibility;

public class RegionPosition extends BlockPos {
	/** Region that holds this position as its origin. Provides access to world render state. */
	private final RenderRegion owner;
	private final WorldRenderState worldRenderState;
	private final TerrainIterator terrainIterator;
	private final DrawListCullingHelper cullingHelper;
	private final RegionVisibilityTest cameraFrustumTest;

	private final long packed;

	/**
	 * The y coordinate of this position in chunks (16 blocks each), relative to world Y = 0. (Can be negative.)
	 * Sole purpose is to make camera chunk distance computation slightly faster.
	 */
	private final int chunkY;

	private long cameraRegionOrigin = -1;

	/** Tracks the version of the camera occluder view transform to know when we must recompute dependent values. */
	private int cameraFrustumViewVersion = -1;

	/**
	 * Tracks the version of the camera occluder position to know when we must recompute dependent values.
	 * The occlusion position is much more sensitive and changes more frequently than {@link #chunkDistVersion}.
	 *
	 * <p>Position cannot change without view also changing, so there is no need to check this unless
	 * {@link #cameraFrustumViewVersion} has changed.
	 */
	private int cameraFrustumPositionVersion = -1;

	/** See {@link #occlusionRange()}. */
	private int occlusionRange;

	/** See {@link #squaredCameraChunkDistance()}. */
	private int squaredCameraChunkDistance;

	/** See {@link #isNear()}. */
	private boolean isNear;

	/** See {@link #fuzz()}. */
	private int fuzz;

	/** See {@link #isInsideRenderDistance()}. */
	private boolean isInsideRenderDistance;

	/** Used by frustum tests. Will be current only if region is within render distance. */
	private float cameraRelativeCenterX;
	private float cameraRelativeCenterY;
	private float cameraRelativeCenterZ;

	private boolean isPotentiallyVisibleFromCamera;

	/** See {@link #checkAndUpdateSortNeeded(int)}. */
	private int sortPositionVersion = -1;

	/** The smallest cascade on which this region can potentially cast a shadow. */
	private int shadowCascade;

	private int shadowDistanceRank;

	private int visibleFaceFlags;

	public RegionPosition(long packedPos, RenderRegion owner) {
		super(getX(packedPos), getY(packedPos), getZ(packedPos));
		this.owner = owner;
		worldRenderState = owner.worldRenderState;
		terrainIterator = worldRenderState.terrainIterator;
		cullingHelper = worldRenderState.drawListCullingHlper;
		cameraFrustumTest = worldRenderState.terrainFrustum.visibilityTest;

		chunkY = getY() >> 4;
		packed = packedPos;
	}

	@Override
	public long asLong() {
		return packed;
	}

	public void update() {
		computeRegionDependentValues();
		computeViewDependentValues();

		if (owner.worldRenderState.shadowsEnabled()) {
			if (isInsideRenderDistance) {
				// PERF: pointer chase hell
				shadowCascade = terrainIterator.shadowVisibility.cascade(this);
				shadowDistanceRank = shadowCascade == -1 ? -1 : terrainIterator.shadowVisibility.distanceRank(owner);
			} else {
				shadowCascade = -1;
				shadowDistanceRank = -1;
			}
		} else {
			shadowCascade = -1;
		}
	}

	private void computeRegionDependentValues() {
		final long cameraRegionOrigin = terrainIterator.cameraRegionOrigin();

		if (this.cameraRegionOrigin != cameraRegionOrigin) {
			this.cameraRegionOrigin = cameraRegionOrigin;
			final int cy = (BlockPos.getY(cameraRegionOrigin) >> 4) - chunkY;
			squaredCameraChunkDistance = owner.renderChunk.horizontalSquaredDistance + cy * cy;
			isInsideRenderDistance = squaredCameraChunkDistance <= worldRenderState.maxSquaredChunkRenderDistance();
			isNear = squaredCameraChunkDistance <= 3;
			// Based on trial-and-error
			fuzz = squaredCameraChunkDistance >= 7 * 7 ? 1 : 0;
			occlusionRange = PackedBox.rangeFromSquareChunkDist(squaredCameraChunkDistance);
		}
	}

	private void computeViewDependentValues() {
		final CameraVisibility cameraPVS = terrainIterator.cameraVisibility;
		final int frustumViewVersion = cameraPVS.frustumViewVersion();

		if (cameraFrustumViewVersion != frustumViewVersion) {
			cameraFrustumViewVersion = frustumViewVersion;
			visibleFaceFlags = cullingHelper.computeVisibleFaceFlags(packed);

			final int frustumPositionVersion = cameraPVS.frustumPositionVersion();

			// These checks depend on the camera occluder position version,
			// which may not necessarily change when view version change.
			if (cameraFrustumPositionVersion != frustumPositionVersion) {
				cameraFrustumPositionVersion = frustumPositionVersion;

				// These are needed by frustum tests, which happen below, after this update.
				// not needed at all if outside of render distance
				if (isInsideRenderDistance) {
					final Vec3 cameraPos = cameraPVS.frustumCameraPos();
					cameraRelativeCenterX = (float) (getX() + 8 - cameraPos.x);
					cameraRelativeCenterY = (float) (getY() + 8 - cameraPos.y);
					cameraRelativeCenterZ = (float) (getZ() + 8 - cameraPos.z);
				}
			}

			//  PERF: implement hierarchical tests with propagation of per-plane inside test results
			isPotentiallyVisibleFromCamera = isInsideRenderDistance && cameraFrustumTest.isVisible(this);
		}
	}

	/** Flag 6 (unassigned) will always be set. */
	public int visibleFaceFlags() {
		return visibleFaceFlags;
	}

	/** Flag 6 (unassigned) will always be set. */
	public int shadowVisibleFaceFlags() {
		return cullingHelper.shadowVisibleFaceFlags();
	}

	public void close() {
		isInsideRenderDistance = false;
		isNear = false;
		cameraFrustumPositionVersion = -1;
		cameraFrustumViewVersion = -1;
		cameraRegionOrigin = -1;
		isPotentiallyVisibleFromCamera = false;
	}

	/**
	 * Square of distance of this region from the camera region measured in chunks. (16, blocks each.)
	 */
	public int squaredCameraChunkDistance() {
		return squaredCameraChunkDistance;
	}

	/**
	 * Our logic for this is a little different than vanilla, which checks for squared distance
	 * to chunk center from camera < 768.0.  Ours will always return true for all 26 chunks adjacent
	 * (including diagonal) to the achunk containing the camera.
	 *
	 * <p>This logic is in {@link #updateCameraDistanceAndVisibilityInfo(TerrainVisibilityState)}.
	 */
	public boolean isNear() {
		return isNear;
	}

	/**
	 * Means what the name suggests.  Note that retention distance is longer.
	 * Does not mean region is visible or within the view frustum.
	 */
	public boolean isInsideRenderDistance() {
		return isInsideRenderDistance;
	}

	/**
	 * True when region is within render distance and also within the camera frustum.
	 *
	 * <p>NB: tried a crude hierarchical scheme of checking chunk columns first
	 * but didn't pay off.  Would probably  need to propagate per-plane results
	 * over a more efficient region but that might not even help. Is already
	 * quite fast and typically only one or a few regions per chunk must be tested.
	 */
	public boolean isPotentiallyVisibleFromCamera() {
		return isPotentiallyVisibleFromCamera;
	}

	/**
	 * Called for camera region because frustum checks on near plane appear to be a little wobbly.
	 */
	public void forceCameraPotentialVisibility() {
		isPotentiallyVisibleFromCamera = true;
	}

	/**
	 * Classifies this region with one of the {@link PackedBox} constants for region ranges,
	 * based on distance from the camera. Used by the occluder to select level of detail used.
	 */
	public int occlusionRange() {
		return occlusionRange;
	}

	/**
	 * Non-zero if visibility tests should include "fuzz" in perspective view because region is so
	 * distant it may not be visible within the precision of the rasterizer.
	 * @return Blocks of padding region should have for visibility testing in perspective.
	 */
	public int fuzz() {
		return fuzz;
	}

	public float cameraRelativeCenterX() {
		return cameraRelativeCenterX;
	}

	public float cameraRelativeCenterY() {
		return cameraRelativeCenterY;
	}

	public float cameraRelativeCenterZ() {
		return cameraRelativeCenterZ;
	}

	/**
	 * Tracks the given sort counter and returns true when the input value was different.
	 * Used to identify regions that require a translucency resort.
	 * The sort version is incremented elsewhere based on camera movement.
	 *
	 * <p>Here because it is nominally related to position even if not related
	 * to other feature of this class. (It has to live somewhere.) Future optimizations
	 * might make more use of region-specific position information.
	 */
	public boolean checkAndUpdateSortNeeded(int sortPositionVersion) {
		if (this.sortPositionVersion == sortPositionVersion) {
			return false;
		} else {
			this.sortPositionVersion = sortPositionVersion;
			return true;
		}
	}

	/** For debugging. */
	public boolean sharesOriginWith(int blockX, int blockY, int blockZ) {
		return getX() >> 4 == blockX >> 4 && getY() >> 4 == blockY >> 4 && getZ() >> 4 == blockZ >> 4;
	}

	public int shadowCascade() {
		return shadowCascade;
	}

	public int shadowDistanceRank() {
		return shadowDistanceRank;
	}

	public boolean isPotentiallyVisibleFromSkylight() {
		return owner.origin.isInsideRenderDistance() & shadowCascade != -1;
	}

	/**
	 * If this region's distance is less than the input distance this region will not be added.
	 * Also tracks which faces were used to "enter" into this regions for later outbound propagation.
	 *
	 * <p>This prevents the addition of invisible regions that "backtrack" during camera iteration.
	 * We know such regions must be invisible because camera terrain iteration always proceeds in
	 * near-to-far order and if the region was visible from a nearer region, then that region
	 * would have already been added and checked.
	 *
	 * <p>If we are going backwards, then this region is not visible from a nearer region,
	 * which means all nearer regions must fully occlude it, and we are "wrapping around"
	 * from a more distance region.
	 *
	 * @param entryFaceFlags the face(s) of this region from which this region was reached
	 * @param fromSquaredDistance the squared chunk distance of the region from which this region was reached
	 */
	public boolean isFrontFacing(int entryFaceFlags, int fromSquaredDistance) {
		assert !Pipeline.advancedTerrainCulling();
		return (squaredCameraChunkDistance >= fromSquaredDistance && (visibleFaceFlags & entryFaceFlags) != 0) || isNear;
	}

	public boolean isFrontFacing(int fromSquaredDistance) {
		assert Pipeline.advancedTerrainCulling();
		return isPotentiallyVisibleFromCamera && squaredCameraChunkDistance >= fromSquaredDistance || isNear;
	}
}
