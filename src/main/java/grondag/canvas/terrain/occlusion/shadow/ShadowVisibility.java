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

package grondag.canvas.terrain.occlusion.shadow;

import net.minecraft.core.BlockPos;

import grondag.canvas.render.frustum.TerrainFrustum;
import grondag.canvas.render.world.WorldRenderState;
import grondag.canvas.shader.data.ShaderDataManager;
import grondag.canvas.terrain.occlusion.base.AbstractVisbility;
import grondag.canvas.terrain.region.RegionPosition;
import grondag.canvas.terrain.region.RenderRegion;

public class ShadowVisibility extends AbstractVisbility<ShadowVisibility, ShadowRegionVisibility, ShadowPotentiallyVisibleRegionSet, ShadowOccluder> {
	public final ShadowOccluder targetOccluder = new ShadowOccluder("canvas_shadow_target_occlusion_raster.png");

	public ShadowVisibility(WorldRenderState worldRenderState) {
		super(worldRenderState, new ShadowPotentiallyVisibleRegionSet(), new ShadowOccluder("canvas_shadow_occlusion_raster.png"));
	}

	public int cascade(RegionPosition regionPosition) {
		return occluder.cascade(regionPosition);
	}

	public int distanceRank(RenderRegion owner) {
		return pvrs.distanceRank(owner.shadowVisibility);
	}

	@Override
	public ShadowRegionVisibility createRegionState(RenderRegion region) {
		return new ShadowRegionVisibility(this, region);
	}

	@Override
	public void updateView(TerrainFrustum frustum, long cameraRegionOrigin) {
		if (lastCameraRegionOrigin != cameraRegionOrigin) {
			pvrs.setCameraChunkOriginAndClear(BlockPos.getX(cameraRegionOrigin), BlockPos.getZ(cameraRegionOrigin));
		}

		pvrs.setLightVectorAndRestart(ShaderDataManager.skyLightVector);

		occluder.copyState(frustum);
		occluder.setLightVector(ShaderDataManager.skyLightVector);
		targetOccluder.copyState(frustum);
		targetOccluder.setLightVector(ShaderDataManager.skyLightVector);

		super.updateView(frustum, cameraRegionOrigin);
	}

	@Override
	public void outputRaster() {
		super.outputRaster();
		targetOccluder.outputRaster();
	}

	@Override
	protected void invalidateOccluder() {
		super.invalidateOccluder();
		targetOccluder.invalidate();
	}

	@Override
	protected boolean prepareOccluder() {
		return super.prepareOccluder();
	}

	@Override
	public void prepareRegion(RegionPosition origin) {
		occluder.prepareRegion(origin);
		targetOccluder.prepareRegion(origin);
	}

	@Override
	public boolean isBoxVisible(int packedBox, int fuzz) {
		// If can't shadow any terrain then consider it invisible
		return targetOccluder.isBoxOccluded(packedBox) && occluder.isBoxVisible(packedBox, fuzz);
	}

	@Override
	public void occlude(int[] occlusionData) {
		occluder.occlude(occlusionData);
	}
}
