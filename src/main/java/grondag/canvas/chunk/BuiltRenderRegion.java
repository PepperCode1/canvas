package grondag.canvas.chunk;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import io.netty.util.internal.shaded.org.jctools.queues.MessagePassingQueue.Consumer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.chunk.BlockBufferBuilderStorage;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkStatus;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.renderer.v1.model.ModelHelper;

import grondag.canvas.CanvasMod;
import grondag.canvas.apiimpl.rendercontext.TerrainRenderContext;
import grondag.canvas.chunk.DrawableChunk.Solid;
import grondag.canvas.chunk.DrawableChunk.Translucent;

@Environment(EnvType.CLIENT)
public class BuiltRenderRegion {
	private final RenderRegionBuilder renderRegionBuilder;
	private final AtomicReference<RegionData> renderData;
	private final AtomicReference<RegionData> buildData;
	private final ObjectOpenHashSet<BlockEntity> localNoCullingBlockEntities = new ObjectOpenHashSet<>();
	private final Map<RenderLayer, VertexBuffer> buffers;
	public Box boundingBox;
	private int frameIndex;
	private boolean needsRebuild;
	private final BlockPos.Mutable origin;
	private boolean needsImportantRebuild;
	private volatile RegionBuildState buildState = new RegionBuildState();
	private final Consumer<TerrainRenderContext> buildTask = this::rebuildOnWorkerThread;
	private final int[] neighborIndices = new int[6];

	int squaredCameraDistance;

	public BuiltRenderRegion(RenderRegionBuilder renderRegionBuilder) {
		this.renderRegionBuilder = renderRegionBuilder;
		buildData = new AtomicReference<>(RegionData.EMPTY);
		renderData = new AtomicReference<>(RegionData.EMPTY);
		buffers = RenderLayer.getBlockLayers().stream().collect(Collectors.toMap((renderLayer) -> {
			return renderLayer;
		}, (renderLayer) -> {
			return new VertexBuffer(VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);
		}));
		frameIndex = -1;
		needsRebuild = true;
		origin = new BlockPos.Mutable(-1, -1, -1);
	}

	public boolean shouldBuild() {
		if (squaredCameraDistance <= 576.0D) {
			return true;
		} else {
			final int chunkX = origin.getX() >> 4;
		final int chunkZ = origin.getZ() >> 4;
		final ClientWorld world = renderRegionBuilder.world;

		return world.getChunk(chunkX - 1, chunkZ - 1, ChunkStatus.FULL, false) != null
				&& world.getChunk(chunkX - 1, chunkZ + 1, ChunkStatus.FULL, false) != null
				&& world.getChunk(chunkX + 1, chunkZ - 1, ChunkStatus.FULL, false) != null
				&& world.getChunk(chunkX + 1, chunkZ + 1, ChunkStatus.FULL, false) != null;

		}
	}

	public VertexBuffer getBuffer(RenderLayer renderLayer) {
		return buffers.get(renderLayer);
	}

	public void setOrigin(int x, int y, int z, RenderRegionStorage storage) {
		if (x != origin.getX() || y != origin.getY() || z != origin.getZ()) {
			clear();
			origin.set(x, y, z);
			boundingBox = new Box(x, y, z, x + 16, y + 16, z + 16);

			final int[] neighborIndices = this.neighborIndices;

			for(int i = 0; i < 6; ++i) {
				final Direction face = ModelHelper.faceFromIndex(i);

				neighborIndices[i] = storage.getRegionIndexSafely(x + face.getOffsetX() * 16, y + face.getOffsetY() * 16, z + face.getOffsetZ() * 16);
			}
		}
	}

	void updateCameraDistance(int cameraX, int cameraY, int cameraZ) {
		final BlockPos.Mutable origin = this.origin;
		final int dx = origin.getX() + 8 - cameraX;
		final int dy = origin.getY() + 8 - cameraY;
		final int dz = origin.getZ() + 8 - cameraZ;
		squaredCameraDistance = dx * dx + dy * dy + dz * dz;
	}

	private static <E extends BlockEntity> void addBlockEntity(List<BlockEntity> chunkEntities, Set<BlockEntity> globalEntities, E blockEntity) {
		final BlockEntityRenderer<E> blockEntityRenderer = BlockEntityRenderDispatcher.INSTANCE.get(blockEntity);

		if (blockEntityRenderer != null) {
			chunkEntities.add(blockEntity);

			if (blockEntityRenderer.rendersOutsideBoundingBox(blockEntity)) {
				globalEntities.add(blockEntity);
			}
		}
	}

	private void clear() {
		cancel();
		buildData.set(RegionData.EMPTY);
		renderData.set(RegionData.EMPTY);
		needsRebuild = true;
	}

	public void delete() {
		clear();
		buffers.values().forEach(VertexBuffer::close);
	}

	public BlockPos getOrigin() {
		return origin;
	}

	public void scheduleRebuild(boolean isImportant) {
		final boolean neededRebuild = needsRebuild;
		needsRebuild = true;
		needsImportantRebuild = isImportant | (neededRebuild && needsImportantRebuild);
	}

	public void cancelRebuild() {
		needsRebuild = false;
		needsImportantRebuild = false;
	}

	public boolean needsRebuild() {
		return needsRebuild;
	}

	public boolean needsImportantRebuild() {
		return needsRebuild && needsImportantRebuild;
	}

	public void enqueuRebuild() {
		final ProtoRenderRegion region = ProtoRenderRegion.claim(renderRegionBuilder.world, origin);

		// null region is signal to reschedule
		if(buildState.protoRegion.getAndSet(region) == ProtoRenderRegion.IDLE) {
			renderRegionBuilder.executor.execute(buildTask, squaredCameraDistance);
		}
	}

	public boolean enqueueSort(RenderLayer renderLayer) {
		final RegionData regionData = buildData.get();

		if (!regionData.initializedLayers.contains(renderLayer)) {
			return false;
		} else {
			if (buildState.protoRegion.compareAndSet(ProtoRenderRegion.IDLE,  ProtoRenderRegion.RESORT_ONLY)) {
				// null means need to reschedule, otherwise was already scheduled for either
				// resort or rebuild, or is invalid, not ready to be built.
				renderRegionBuilder.executor.execute(buildTask, squaredCameraDistance);
			}

			return true;
		}
	}

	protected void cancel() {
		buildState.protoRegion.set(ProtoRenderRegion.INVALID);
		buildState = new RegionBuildState();
	}

	private void rebuildOnWorkerThread(TerrainRenderContext context) {
		final RegionBuildState runningState = buildState;
		final ProtoRenderRegion region = runningState.protoRegion.getAndSet(ProtoRenderRegion.IDLE);

		if (region == null || region == ProtoRenderRegion.INVALID) {
			return;
		}

		if (region == ProtoRenderRegion.EMPTY) {
			final RegionData chunkData = new RegionData();
			chunkData.setOcclusionGraph(OcclusionRegion.ALL_OPEN);
			buildData.set(chunkData);
			renderData.set(chunkData);
			return;
		}

		// check loaded neighbors and camera distance, abort rebuild and restore needsRebuild if out of view/not ready
		if (!shouldBuild()) {
			scheduleRebuild(false);
			region.release();
			return;
		}

		if(region == ProtoRenderRegion.RESORT_ONLY) {
			final RegionData regionData = buildData.get();
			final BufferBuilder.State state = regionData.bufferState;

			if (state != null && regionData.nonEmptyLayers.contains(RenderLayer.getTranslucent())) {
				final Vec3d cameraPos = renderRegionBuilder.getCameraPosition();
				final BlockBufferBuilderStorage buffers = claimBuilderBuffers();
				final BufferBuilder bufferBuilder = buffers.get(RenderLayer.getTranslucent());

				bufferBuilder.begin(7, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);
				bufferBuilder.restoreState(state);
				bufferBuilder.sortQuads((float)cameraPos.x - origin.getX(), (float)cameraPos.y - origin.getY(), (float)cameraPos.z - origin.getZ());
				regionData.bufferState = bufferBuilder.popState();
				bufferBuilder.end();

				if(runningState.protoRegion.get() == ProtoRenderRegion.INVALID) {
					renderRegionBuilder.workerBufferStorage.offer(buffers);
				} else {
					renderRegionBuilder.scheduleUpload(() -> {
						getBuffer(RenderLayer.getTranslucent()).upload(bufferBuilder);
						bufferBuilder.reset();
						renderRegionBuilder.workerBufferStorage.offer(buffers);
					});
				}
			}
		} else {
			context.prepareRegion(region);
			final RegionData chunkData = buildRegionData(context);
			buildData.set(chunkData);

			final BlockBufferBuilderStorage buffers = claimBuilderBuffers();

			if (runningState.protoRegion.get() == ProtoRenderRegion.INVALID) {
				renderRegionBuilder.workerBufferStorage.offer(buffers);
				region.release();
				context.release();
				return;
			}

			if (buffers != null) {
				buildTerrain(context, chunkData, buffers);
			}

			if(runningState.protoRegion.get() == ProtoRenderRegion.INVALID) {
				renderRegionBuilder.workerBufferStorage.offer(buffers);
			} else {
				renderRegionBuilder.scheduleUpload(() -> {
					chunkData.initializedLayers.forEach((renderLayer) -> {
						final BufferBuilder builder = buffers.get(renderLayer);
						getBuffer(renderLayer).upload(builder);
						builder.reset();
					});

					renderData.set(chunkData);
					renderRegionBuilder.workerBufferStorage.offer(buffers);
				});
			}

			region.release();
			context.release();
		}
	}

	private RegionData buildRegionData(TerrainRenderContext context) {
		final RegionData regionData = new RegionData();
		regionData.setOcclusionGraph(context.region.occlusion.build());
		handleBlockEntities(regionData, context);
		buildData.set(regionData);
		return regionData;
	}

	private void buildTerrain(TerrainRenderContext context, RegionData regionData, BlockBufferBuilderStorage buffers) {
		context.prepareChunk(regionData, buffers, origin);
		final BlockPos.Mutable searchPos = context.searchPos;
		final int xOrigin = origin.getX();
		final int yOrigin = origin.getY();
		final int zOrigin = origin.getZ();
		final FastRenderRegion region = context.region;
		final Vec3d cameraPos = renderRegionBuilder.getCameraPosition();
		final MatrixStack matrixStack = new MatrixStack();
		final BlockRenderManager blockRenderManager = MinecraftClient.getInstance().getBlockRenderManager();
		final OcclusionRegion occlusionRegion = region.occlusion;

		for (int i = 0; i < RenderRegionAddressHelper.INTERIOR_CACHE_SIZE; i++) {
			if(occlusionRegion.shouldRender(i)) {
				final BlockState blockState = region.getLocalBlockState(i);
				final FluidState fluidState = blockState.getFluidState();
				final int x = i & 0xF;
				final int y = (i >> 4) & 0xF;
				final int z = (i >> 8) & 0xF;
				searchPos.set(xOrigin + x, yOrigin + y, zOrigin + z);

				if (!fluidState.isEmpty()) {
					final RenderLayer fluidLayer = RenderLayers.getFluidLayer(fluidState);
					final BufferBuilder fluidBuffer = buffers.get(fluidLayer);

					if (regionData.markInitialized(fluidLayer)) {
						fluidBuffer.begin(7, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);
					}

					if (blockRenderManager.renderFluid(searchPos, region, fluidBuffer, fluidState)) {
						regionData.markPopulated(fluidLayer);
					}
				}

				if (blockState.getRenderType() != BlockRenderType.INVISIBLE) {
					matrixStack.push();
					matrixStack.translate(x, y, z);

					if (blockState.getBlock().getOffsetType() != Block.OffsetType.NONE) {
						final Vec3d vec3d = blockState.getOffsetPos(region, searchPos);

						if (vec3d != Vec3d.ZERO) {
							matrixStack.translate(vec3d.x, vec3d.y, vec3d.z);
						}
					}

					context.tesselateBlock(blockState, searchPos, blockRenderManager.getModel(blockState), matrixStack);

					matrixStack.pop();
				}
			}
		}

		regionData.endBuffering((float) (cameraPos.x - xOrigin), (float) (cameraPos.y - yOrigin), (float) (cameraPos.z - zOrigin), buffers);
	}

	private BlockBufferBuilderStorage claimBuilderBuffers() {
		BlockBufferBuilderStorage buffers = null;
		int retryCount = 0;

		while (buffers == null && retryCount < 10) {
			try {
				buffers = renderRegionBuilder.workerBufferStorage.take();
			} catch (final InterruptedException e) {
				++retryCount;
			}
		}

		if (buffers == null) {
			CanvasMod.LOG.warn("Unable to retrieve block buffer on worker thread. Chunk was not rebuilt.");
		}

		return buffers;
	}

	private void handleBlockEntities(RegionData regionData, TerrainRenderContext context) {
		final ObjectOpenHashSet<BlockEntity> nonCullBlockEntities = context.nonCullBlockEntities;
		final ObjectArrayList<BlockEntity> regionDataBlockEntities = regionData.blockEntities;

		for(final BlockEntity blockEntity : context.region.blockEntities) {
			if (blockEntity != null) {
				addBlockEntity(regionDataBlockEntities, nonCullBlockEntities, blockEntity);
			}
		}

		final ObjectOpenHashSet<BlockEntity> addedBlockEntities = context.addedBlockEntities;
		final ObjectOpenHashSet<BlockEntity> removedBlockEntities = context.removedBlockEntities;

		if (!localNoCullingBlockEntities.isEmpty()) {
			final ObjectIterator<BlockEntity> it = localNoCullingBlockEntities.iterator();

			while (it.hasNext()) {
				final BlockEntity be = it.next();

				if (!nonCullBlockEntities.contains(be)) {
					it.remove();
					removedBlockEntities.add(be);
				}
			}
		}

		if (!nonCullBlockEntities.isEmpty()) {
			final ObjectIterator<BlockEntity> it = nonCullBlockEntities.iterator();

			while (it.hasNext()) {
				final BlockEntity be = it.next();

				if (localNoCullingBlockEntities.add(be)) {
					addedBlockEntities.add(be);
				}
			}
		}

		renderRegionBuilder.worldRenderer.updateNoCullingBlockEntities(removedBlockEntities, addedBlockEntities);
	}

	public void rebuildOnMainThread() {
		final ProtoRenderRegion region = ProtoRenderRegion.claim(renderRegionBuilder.world, origin);

		if (region == ProtoRenderRegion.EMPTY) {
			final RegionData regionData = new RegionData();
			regionData.setOcclusionGraph(OcclusionRegion.ALL_OPEN);
			buildData.set(regionData);
			return;
		}

		final TerrainRenderContext context = renderRegionBuilder.mainThreadContext.prepareRegion(region);
		final RegionData regionData = buildRegionData(context);
		buildData.set(regionData);

		final BlockBufferBuilderStorage buffers = renderRegionBuilder.mainThreadBufferStorage;

		buildTerrain(context, regionData, buffers);

		regionData.initializedLayers.forEach((renderLayer) -> {
			final BufferBuilder builder = buffers.get(renderLayer);
			getBuffer(renderLayer).upload(builder);
			builder.reset();
		});

		renderData.set(regionData);
		region.release();
		context.release();
	}

	public int getFrameIndex() {
		return frameIndex;
	}

	public void setFrameIndex(int frameIndex) {
		this.frameIndex = frameIndex;
	}

	public int[] getNeighborIndices() {
		return neighborIndices;
	}

	public RegionData getBuildData() {
		return buildData.get();
	}

	public RegionData getRenderData() {
		return renderData.get();
	}

	public Translucent translucentDrawable() {
		// TODO Auto-generated method stub
		return null;
	}

	public Solid solidDrawable() {
		// TODO Auto-generated method stub
		return null;
	}
}