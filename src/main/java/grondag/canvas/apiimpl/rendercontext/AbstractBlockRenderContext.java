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

package grondag.canvas.apiimpl.rendercontext;

import static grondag.canvas.buffer.format.EncoderUtils.applyBlockLighting;
import static grondag.canvas.buffer.format.EncoderUtils.bufferQuad;
import static grondag.canvas.buffer.format.EncoderUtils.colorizeQuad;

import java.util.Random;
import java.util.function.Supplier;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.vram.frex.api.material.MaterialMap;
import io.vram.frex.api.mesh.FrexBufferSource;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import net.fabricmc.fabric.api.renderer.v1.model.ModelHelper;

import grondag.canvas.apiimpl.mesh.MeshEncodingHelper;
import grondag.canvas.apiimpl.mesh.QuadEditorImpl;
import grondag.canvas.apiimpl.util.GeometryHelper;
import grondag.canvas.buffer.format.QuadEncoders;
import grondag.canvas.mixinterface.RenderTypeExt;

public abstract class AbstractBlockRenderContext<T extends BlockAndTintGetter> extends AbstractRenderContext {
	/**
	 * For use by chunk builder - avoids another threadlocal.
	 */
	public final BlockPos.MutableBlockPos searchPos = new BlockPos.MutableBlockPos();
	public final Random random = new Random();

	/**
	 * For internal use.
	 */
	protected final BlockPos.MutableBlockPos internalSearchPos = new BlockPos.MutableBlockPos();

	@Nullable protected VertexConsumer defaultConsumer;

	private final BlockColors blockColorMap = Minecraft.getInstance().getBlockColors();
	public T region;
	public BlockPos blockPos;
	public BlockState blockState;
	public long seed;
	public boolean defaultAo;
	protected boolean needsRandomRefresh = true;
	public final Supplier<Random> randomSupplier = () -> {
		final Random result = random;

		if (needsRandomRefresh) {
			needsRandomRefresh = false;
			long seed = this.seed;

			if (seed == -1L) {
				seed = blockState.getSeed(blockPos);
				this.seed = seed;
			}

			result.setSeed(seed);
		}

		return result;
	};
	protected int lastColorIndex = -1;
	protected int blockColor = -1;
	protected int fullCubeCache = 0;

	protected AbstractBlockRenderContext(String name) {
		super(name);
	}

	/**
	 * @param blockState
	 * @param blockPos
	 * @param modelAO
	 * @param seed       pass -1 for default behavior
	 */
	public void prepareForBlock(BlockState blockState, BlockPos blockPos, boolean modelAO, long seed) {
		this.blockPos = blockPos;
		this.blockState = blockState;
		materialMap = isFluidModel ? MaterialMap.get(blockState.getFluidState()) : MaterialMap.get(blockState);
		lastColorIndex = -1;
		needsRandomRefresh = true;
		fullCubeCache = 0;
		this.seed = seed;
		defaultAo = modelAO && Minecraft.useAmbientOcclusion() && blockState.getLightEmission() == 0;

		// FEAT: support additional blend modes on terrain blocks?
		defaultBlendMode = isFluidModel
			? ((RenderTypeExt) ItemBlockRenderTypes.getRenderLayer(blockState.getFluidState())).canvas_preset()
			: ((RenderTypeExt) ItemBlockRenderTypes.getChunkRenderType(blockState)).canvas_preset();
	}

	@Override
	public final int indexedColor(int colorIndex) {
		if (colorIndex == -1) {
			return -1;
		} else if (lastColorIndex == colorIndex) {
			return blockColor;
		} else {
			lastColorIndex = colorIndex;
			final int result = 0xFF000000 | blockColorMap.getColor(blockState, region, blockPos, colorIndex);
			blockColor = result;
			return result;
		}
	}

	public boolean isFullCube() {
		if (fullCubeCache == 0) {
			fullCubeCache = Block.isShapeFullBlock(blockState.getCollisionShape(region, blockPos)) ? 1 : -1;
		}

		return fullCubeCache == 1;
	}

	@Override
	public final Random random() {
		return randomSupplier.get();
	}

	@Override
	public final boolean defaultAo() {
		return defaultAo;
	}

	@Override
	public final BlockState blockState() {
		return blockState;
	}

	@Override
	public final int flatBrightness(QuadEditorImpl quad) {
		/**
		 * Handles geometry-based check for using self brightness or neighbor brightness.
		 * That logic only applies in flat lighting.
		 */
		if (blockState.emissiveRendering(region, blockPos)) {
			return MeshEncodingHelper.FULL_BRIGHTNESS;
		}

		internalSearchPos.set(blockPos);

		// To mirror Vanilla's behavior, if the face has a cull-face, always sample the light value
		// offset in that direction. See net.minecraft.client.render.block.BlockModelRenderer.renderFlat
		// for reference.
		if (quad.cullFaceId() != ModelHelper.NULL_FACE_ID) {
			internalSearchPos.move(quad.cullFace());
		} else if ((quad.geometryFlags() & GeometryHelper.LIGHT_FACE_FLAG) != 0 || isFullCube()) {
			internalSearchPos.move(quad.lightFace());
		}

		return fastBrightness(blockState, internalSearchPos);
	}

	protected abstract int fastBrightness(BlockState blockState, BlockPos pos);

	@Override
	protected void encodeQuad(QuadEditorImpl quad) {
		// needs to happen before offsets are applied
		applyBlockLighting(quad, this);
		colorizeQuad(quad, this);

		if (collectors == null) {
			bufferQuad(quad, this, defaultConsumer);
		} else {
			QuadEncoders.STANDARD_ENCODER.encode(quad, this, collectors.get(quad.material()));
		}
	}

	@Override
	public FrexBufferSource vertexConsumers() {
		// WIP implement
		return null;
	}

	@Override
	public PoseStack matrixStack() {
		// WIP implement
		return null;
	}
}
