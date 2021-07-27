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

package grondag.canvas.render.terrain.cluster.drawlist;

import java.nio.IntBuffer;
import java.util.IdentityHashMap;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.lwjgl.PointerBuffer;

import grondag.canvas.buffer.util.BufferSynchronizer;
import grondag.canvas.buffer.util.BufferSynchronizer.SynchronizedBuffer;
import grondag.canvas.buffer.util.DirectBufferAllocator;
import grondag.canvas.buffer.util.DirectBufferAllocator.DirectBufferReference;
import grondag.canvas.render.terrain.cluster.ClusteredDrawableStorage;
import grondag.canvas.render.terrain.cluster.Slab;
import grondag.canvas.render.terrain.cluster.VertexCluster;
import grondag.canvas.render.terrain.cluster.VertexCluster.RegionAllocation.SlabAllocation;
import grondag.canvas.render.terrain.cluster.VertexClusterRealm;
import grondag.canvas.varia.GFX;

public class ClusterDrawList {
	record DrawSpec(Slab slab, DirectBufferReference triVertexCount, DirectBufferReference baseIndices, PointerBuffer indexPointers) implements SynchronizedBuffer {
		void release() {
			triVertexCount.release();
			baseIndices.release();
		}

		@Override
		public void onBufferSync() {
			release();
		}
	}

	final ObjectArrayList<ClusteredDrawableStorage> regions = new ObjectArrayList<>();
	final VertexCluster cluster;
	final RealmDrawList realmList;
	private final ObjectArrayList<DrawSpec> drawSpecs = new ObjectArrayList<>();

	ClusterDrawList(VertexCluster cluster, RealmDrawList owner) {
		this.cluster = cluster;
		realmList = owner;
	}

	void release() {
		for (var spec : drawSpecs) {
			BufferSynchronizer.accept(spec);
		}
	}

	void build() {
		assert drawSpecs.isEmpty();

		if (cluster.realm == VertexClusterRealm.TRANSLUCENT) {
			buildTranslucent();
		} else {
			buildSolid();
		}
	}

	/** Minimizes binds/calls. */
	private void buildSolid() {
		final IdentityHashMap<Slab, SolidSpecList> map = new IdentityHashMap<>();

		// first group regions by slab
		for (var region : regions) {
			for (var alloc : region.allocation().allocations()) {
				var list = map.get(alloc.slab);

				if (list == null) {
					list = new SolidSpecList();
					map.put(alloc.slab, list);
				}

				list.add(alloc);
				list.specQuadVertexCount += alloc.quadVertexCount;
			}
		}

		for (var list: map.values()) {
			assert list.specQuadVertexCount <= IndexSlab.MAX_INDEX_SLAB_QUAD_VERTEX_COUNT;
			addSpec(list, list.specQuadVertexCount);
		}
	}

	/** Maintains region sort order at the cost of extra binds/calls if needed. */
	private void buildTranslucent() {
		Slab lastSlab = null;
		final ObjectArrayList<SlabAllocation> specAllocations = new ObjectArrayList<>();
		int specQuadVertexCount = 0;

		for (var region : regions) {
			for (var alloc : region.allocation().allocations()) {
				if (alloc.slab != lastSlab) {
					if (lastSlab != null) {
						addSpec(specAllocations, specQuadVertexCount);
					}

					specQuadVertexCount = 0;
					lastSlab = alloc.slab;
				}

				specAllocations.add(alloc);
				specQuadVertexCount += alloc.quadVertexCount;
			}
		}

		addSpec(specAllocations, specQuadVertexCount);
	}

	/**
	 * Returns the index slab that should be used for next call.
	 * Does nothing if region list is empty and clears region list when done.
	 */
	private void addSpec(ObjectArrayList<SlabAllocation> specAllocations, int specQuadVertexCount) {
		assert specQuadVertexCount >= 0;
		assert !specAllocations.isEmpty() : "Vertex count is non-zero but region list is empty.";

		final var slab = specAllocations.get(0).slab;
		final var limit = specAllocations.size();

		final DirectBufferReference vcountBuff = DirectBufferAllocator.claim(limit * 4);
		final IntBuffer vCount = vcountBuff.asIntBuffer();
		final DirectBufferReference bIndexBuff = DirectBufferAllocator.claim(limit * 4);
		final IntBuffer bIndex = bIndexBuff.asIntBuffer();
		final var pBuff = PointerBuffer.allocateDirect(limit);

		for (int i = 0; i < limit; ++i) {
			final var alloc = specAllocations.get(i);
			assert alloc.slab == slab;
			vCount.put(i, alloc.triVertexCount());
			bIndex.put(i, alloc.baseQuadVertexIndex);
			pBuff.put(i, 0L);
		}

		drawSpecs.add(new DrawSpec(slab, vcountBuff, bIndexBuff, pBuff));
		specAllocations.clear();
	}

	private static class SolidSpecList extends ObjectArrayList<SlabAllocation> {
		private int specQuadVertexCount;
	}

	public void draw() {
		drawNew();
	}

	public void drawNew() {
		final int limit = drawSpecs.size();

		for (int i = 0; i < limit; ++i) {
			var spec = drawSpecs.get(i);

			spec.slab.bind();
			IndexSlab.fullSlabIndex().bind();
			GFX.glMultiDrawElementsBaseVertex(GFX.GL_TRIANGLES, spec.triVertexCount.asIntBuffer(), GFX.GL_UNSIGNED_SHORT, spec.indexPointers, spec.baseIndices.asIntBuffer());
		}

		IndexSlab.fullSlabIndex().unbind();
	}

	// WIP: use a version of this for new lists and gradually compact?
	public void drawOld() {
		final int limit = regions.size();

		Slab lastSlab = null;

		for (int regionIndex = 0; regionIndex < limit; ++regionIndex) {
			ClusteredDrawableStorage store = regions.get(regionIndex);

			for (var alloc : store.allocation().allocations()) {
				Slab slab = alloc.slab;

				if (slab != lastSlab) {
					slab.bind();
					IndexSlab.fullSlabIndex().bind();
					lastSlab = slab;
				}

				// NB offset is baseQuadVertexIndex * 3 because the offset is in bytes
				// six tri vertices per four quad vertices at 2 bytes each gives 6 / 4 * 2 = 3
				GFX.drawElements(GFX.GL_TRIANGLES, alloc.triVertexCount(), GFX.GL_UNSIGNED_SHORT, alloc.baseQuadVertexIndex * IndexSlab.INDEX_QUAD_VERTEX_TO_TRIANGLE_BYTES_MULTIPLIER);
			}
		}

		IndexSlab.fullSlabIndex().unbind();
	}

	public void add(ClusteredDrawableStorage storage) {
		assert storage.allocation().cluster() == cluster;
		regions.add(storage);
	}

	public void invalidate() {
		realmList.invalidate();
	}
}
