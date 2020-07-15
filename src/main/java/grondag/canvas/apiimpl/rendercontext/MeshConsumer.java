/*******************************************************************************
 * Copyright 2019, 2020 grondag
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/

package grondag.canvas.apiimpl.rendercontext;

import java.util.function.Consumer;

import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;

import grondag.canvas.apiimpl.Canvas;
import grondag.canvas.apiimpl.RenderMaterialImpl;
import grondag.canvas.apiimpl.RenderMaterialImpl.CompositeMaterial;
import grondag.canvas.apiimpl.mesh.MeshEncodingHelper;
import grondag.canvas.apiimpl.mesh.MeshImpl;
import grondag.canvas.apiimpl.mesh.MutableQuadViewImpl;
import grondag.canvas.buffer.encoding.VertexEncoders;

/**
 * Consumer for pre-baked meshes.  Works by copying the mesh data to a
 * "editor" quad held in the instance, where all transformations are applied before buffering.
 */
public class MeshConsumer implements Consumer<Mesh> {
	private final AbstractRenderContext context;

	protected MeshConsumer(AbstractRenderContext context) {
		this.context = context;
	}

	/**
	 * Where we handle all pre-buffer coloring, lighting, transformation, etc.
	 * Reused for all mesh quads. Fixed baking array sized to hold largest possible mesh quad.
	 */
	private class Maker extends MutableQuadViewImpl implements QuadEmitter {
		{
			data = new int[MeshEncodingHelper.MAX_QUAD_STRIDE];
			material(Canvas.MATERIAL_STANDARD);
		}

		// only used via RenderContext.getEmitter()
		@Override
		public Maker emit() {
			complete();
			renderQuad(this);
			clear();
			return this;
		}
	}

	private final Maker editorQuad = new Maker();

	@Override
	public void accept(Mesh mesh) {
		final MeshImpl m = (MeshImpl) mesh;
		final int[] data = m.data();
		final int limit = data.length;
		int index = 0;

		while (index < limit) {
			final int stride = MeshEncodingHelper.stride(RenderMaterialImpl.byIndex(data[index]).spriteDepth());
			System.arraycopy(data, index, editorQuad.data(), 0, stride);
			editorQuad.load();
			index += stride;
			renderQuad(editorQuad);
		}
	}

	public QuadEmitter getEmitter() {
		editorQuad.clear();
		return editorQuad;
	}

	private void renderQuad(MutableQuadViewImpl quad) {
		context.mapMaterials(editorQuad);

		if (!context.transform(quad)) {
			return;
		}

		if (!context.cullTest(quad.cullFace())) {
			return;
		}

		final CompositeMaterial mat = quad.material().forBlendMode(context.defaultBlendModeIndex());
		quad.material(mat);
		VertexEncoders.get(context.materialContext(), mat).encodeQuad(quad, context);
	}
}
