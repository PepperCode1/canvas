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

package grondag.canvas.render.world;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import com.mojang.math.Matrix4f;

import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Option;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import io.vram.frex.api.config.FlawlessFrames;
import io.vram.frex.api.renderloop.BlockOutlineListener;
import io.vram.frex.api.renderloop.BlockOutlinePreListener;
import io.vram.frex.api.renderloop.DebugRenderListener;
import io.vram.frex.api.renderloop.EntityRenderPostListener;
import io.vram.frex.api.renderloop.EntityRenderPreListener;
import io.vram.frex.api.renderloop.FrustumSetupListener;
import io.vram.frex.api.renderloop.TranslucentPostListener;
import io.vram.frex.api.renderloop.WorldRenderContextBase;
import io.vram.frex.api.renderloop.WorldRenderLastListener;
import io.vram.frex.api.renderloop.WorldRenderPostListener;
import io.vram.frex.api.renderloop.WorldRenderStartListener;

import grondag.canvas.CanvasMod;
import grondag.canvas.apiimpl.MaterialConditionImpl;
import grondag.canvas.apiimpl.rendercontext.BlockRenderContext;
import grondag.canvas.apiimpl.rendercontext.EntityBlockRenderContext;
import grondag.canvas.buffer.input.CanvasImmediate;
import grondag.canvas.buffer.render.StreamBufferAllocator;
import grondag.canvas.buffer.render.TransferBuffers;
import grondag.canvas.buffer.util.BufferSynchronizer;
import grondag.canvas.buffer.util.DirectBufferAllocator;
import grondag.canvas.buffer.util.DrawableStream;
import grondag.canvas.compat.FirstPersonModelHolder;
import grondag.canvas.config.Configurator;
import grondag.canvas.config.FlawlessFramesController;
import grondag.canvas.material.property.TargetRenderState;
import grondag.canvas.material.state.RenderContextState;
import grondag.canvas.material.state.RenderState;
import grondag.canvas.mixinterface.LevelRendererExt;
import grondag.canvas.mixinterface.RenderBuffersExt;
import grondag.canvas.perf.Timekeeper;
import grondag.canvas.perf.Timekeeper.ProfilerGroup;
import grondag.canvas.pipeline.Pipeline;
import grondag.canvas.pipeline.PipelineManager;
import grondag.canvas.render.frustum.RegionCullingFrustum;
import grondag.canvas.render.terrain.cluster.ClusterTaskManager;
import grondag.canvas.shader.GlProgram;
import grondag.canvas.shader.GlProgramManager;
import grondag.canvas.shader.data.MatrixData;
import grondag.canvas.shader.data.MatrixState;
import grondag.canvas.shader.data.ScreenRenderState;
import grondag.canvas.shader.data.ShaderDataManager;
import grondag.canvas.terrain.occlusion.SortableVisibleRegionList;
import grondag.canvas.terrain.occlusion.TerrainIterator;
import grondag.canvas.terrain.region.RegionRebuildManager;
import grondag.canvas.terrain.region.RenderRegionStorage;
import grondag.canvas.terrain.util.TerrainExecutor;
import grondag.canvas.varia.GFX;

public class CanvasWorldRenderer extends LevelRenderer {
	private static CanvasWorldRenderer instance;

	public final WorldRenderState worldRenderState = new WorldRenderState(this);

	private final RegionCullingFrustum entityCullingFrustum = new RegionCullingFrustum(worldRenderState);
	private final RenderContextState contextState = new RenderContextState();
	private final CanvasImmediate worldRenderImmediate = new CanvasImmediate(new BufferBuilder(256), CanvasImmediate.entityBuilders(), contextState);
	/** Contains the player model output for First Person Model, separate to draw in material pass only. */
	private final CanvasImmediate materialExtrasImmediate = new CanvasImmediate(new BufferBuilder(256), new Object2ObjectLinkedOpenHashMap<>(), contextState);
	/** Contains the player model output when not in 3rd-person view, separate to draw in shadow render only. */
	private final CanvasImmediate shadowExtrasImmediate = new CanvasImmediate(new BufferBuilder(256), new Object2ObjectLinkedOpenHashMap<>(), contextState);
	private final CanvasParticleRenderer particleRenderer = new CanvasParticleRenderer(entityCullingFrustum);
	private final WorldRenderContextBase eventContext = new WorldRenderContextBase();

	/** Used to avoid camera rotation in managed draws.  Kept to avoid reallocation every frame. */
	private final PoseStack identityStack = new PoseStack();

	private final LevelRendererExt vanillaWorldRenderer;

	public CanvasWorldRenderer(Minecraft client, RenderBuffers bufferBuilders) {
		super(client, bufferBuilders);

		if (Configurator.enableLifeCycleDebug) {
			CanvasMod.LOG.info("Lifecycle Event: CanvasWorldRenderer init");
		}

		vanillaWorldRenderer = (LevelRendererExt) this;
		instance = this;
		worldRenderState.computeDistances();
	}

	public static CanvasWorldRenderer instance() {
		return instance;
	}

	public void updateProjection(Camera camera, float tickDelta, double fov) {
		worldRenderState.terrainFrustum.updateProjection(camera, tickDelta, fov);
	}

	@Override
	public void setLevel(@Nullable ClientLevel clientWorld) {
		worldRenderState.setWorld(clientWorld);

		// we don't want to use our collector unless we are in a world
		((RenderBuffersExt) vanillaWorldRenderer.canvas_bufferBuilders()).canvas_setEntityConsumers(clientWorld == null ? null : worldRenderImmediate);
		// Mixins mostly disable what this does
		super.setLevel(clientWorld);
	}

	/**
	 * Terrain rebuild is partly lazy/incremental
	 * The occluder has a thread-safe version indicating visibility test validity.
	 * The raster must be redrawn whenever the frustum view changes but prior visibility
	 * checks remain valid until the player location changes more than 1 block
	 * (regions are fuzzed one block to allow this) or a region that was already drawn into
	 * the raster is updated with different visibility information.  New occluders can
	 * also  be added to the existing raster.
	 * or
	 */
	public void setupTerrain(Camera camera, int frameCounter, boolean shouldCullChunks) {
		final int renderDistance = worldRenderState.chunkRenderDistance();
		final RenderRegionStorage regionStorage = worldRenderState.renderRegionStorage;
		final TerrainIterator terrainIterator = worldRenderState.terrainIterator;
		final Minecraft mc = Minecraft.getInstance();
		final RegionRebuildManager regionRebuildManager = worldRenderState.regionRebuildManager;

		regionStorage.closeRegionsOnRenderThread();

		mc.getProfiler().push("camera");
		MaterialConditionImpl.update();
		GlProgramManager.INSTANCE.onRenderTick();

		regionRebuildManager.processExternalBuildRequests();

		Entity.setViewScale(Mth.clamp(mc.options.renderDistance / 8.0D, 1.0D, 2.5D));

		mc.getProfiler().popPush("update");

		if (Configurator.terrainSetupOffThread && !FlawlessFrames.isActive()) {
			int state = terrainIterator.state();

			if (state == TerrainIterator.COMPLETE) {
				worldRenderState.copyVisibleRegionsFromIterator();
				regionRebuildManager.scheduleOrBuild(terrainIterator.updateRegions);
				terrainIterator.idle();
				state = TerrainIterator.IDLE;
			}

			if (state == TerrainIterator.IDLE) {
				if (terrainIterator.prepare(camera, worldRenderState.terrainFrustum, renderDistance, shouldCullChunks)) {
					TerrainExecutor.INSTANCE.execute(terrainIterator);
				}
			} else {
				// If we kicked off a new iteration this will happen automatically, but if we are waiting
				// for completion we want near regions to be updated right away.
				terrainIterator.buildNearIfNeeded();
			}
		} else {
			// Run iteration on main thread
			if (terrainIterator.prepare(camera, worldRenderState.terrainFrustum, renderDistance, shouldCullChunks)) {
				terrainIterator.run(null);

				assert terrainIterator.state() == TerrainIterator.COMPLETE : "Iteration cancelled on main thread.";
				worldRenderState.copyVisibleRegionsFromIterator();
				regionRebuildManager.scheduleOrBuild(terrainIterator.updateRegions);
				terrainIterator.idle();
			}
		}

		mc.getProfiler().pop();
	}

	private boolean shouldCullChunks(BlockPos pos) {
		final Minecraft mc = Minecraft.getInstance();
		boolean result = mc.smartCull;
		final ClientLevel world = worldRenderState.getWorld();

		if (mc.player.isSpectator() && !world.isOutsideBuildHeight(pos.getY()) && world.getBlockState(pos).isSolidRender(world, pos)) {
			result = false;
		}

		return result;
	}

	public void renderWorld(PoseStack viewMatrixStack, float tickDelta, long frameStartNanos, boolean blockOutlines, Camera camera, GameRenderer gameRenderer, LightTexture lightmapTextureManager, Matrix4f projectionMatrix) {
		final LevelRendererExt wr = vanillaWorldRenderer;
		final Minecraft mc = Minecraft.getInstance();
		final LevelRenderer mcwr = mc.levelRenderer;
		final RenderTarget mcfb = mc.getMainRenderTarget();
		final BlockRenderContext blockContext = BlockRenderContext.get();
		final EntityBlockRenderContext entityBlockContext = EntityBlockRenderContext.get();
		final ClientLevel world = worldRenderState.getWorld();
		final RenderBuffers bufferBuilders = wr.canvas_bufferBuilders();
		final EntityRenderDispatcher entityRenderDispatcher = wr.canvas_entityRenderDispatcher();
		final boolean advancedTranslucency = Pipeline.isFabulous();
		final Vec3 cameraVec3d = camera.getPosition();
		final double frameCameraX = cameraVec3d.x();
		final double frameCameraY = cameraVec3d.y();
		final double frameCameraZ = cameraVec3d.z();
		final PoseStack identityStack = this.identityStack;

		RenderSystem.setShaderGameTime(world.getGameTime(), tickDelta);
		Minecraft.getInstance().getBlockEntityRenderDispatcher().prepare(world, camera, mc.hitResult);
		entityRenderDispatcher.prepare(world, camera, mc.crosshairPickEntity);
		final ProfilerFiller profiler = world.getProfiler();

		WorldRenderDraws.profileSwap(profiler, ProfilerGroup.StartWorld, "light_updates");
		mc.level.getChunkSource().getLightEngine().runUpdates(Integer.MAX_VALUE, true, true);

		WorldRenderDraws.profileSwap(profiler, ProfilerGroup.StartWorld, "clear");
		Pipeline.defaultFbo.bind();

		// This does not actually render anything - what it does do is set the current clear color
		// Color is captured via a mixin for use in shaders
		FogRenderer.setupColor(camera, tickDelta, mc.level, mc.options.renderDistance, gameRenderer.getDarkenWorldAmount(tickDelta));
		// We don't depend on this but call it here for compatibility
		FogRenderer.levelFogColor();

		if (Pipeline.config().runVanillaClear) {
			RenderSystem.clear(16640, Minecraft.ON_OSX);
		}

		final float viewDistance = gameRenderer.getRenderDistance();
		final boolean thickFog = mc.level.effects().isFoggyAt(Mth.floor(frameCameraX), Mth.floor(frameCameraY)) || mc.gui.getBossOverlay().shouldCreateWorldFog();

		if (mc.options.renderDistance >= 4) {
			// We call applyFog here to do some state capture - otherwise has no effect
			FogRenderer.setupFog(camera, FogRenderer.FogMode.FOG_SKY, viewDistance, thickFog);
			ShaderDataManager.captureFogDistances();
			WorldRenderDraws.profileSwap(profiler, ProfilerGroup.StartWorld, "sky");
			// NB: fog / sky renderer normalcy get viewMatrixStack but we apply camera rotation in VertexBuffer mixin
			RenderSystem.setShader(GameRenderer::getPositionShader);

			// Mojang passes applyFog as a lambda here because they sometimes call it twice.
			renderSky(viewMatrixStack, projectionMatrix, tickDelta, () -> {
				FogRenderer.setupFog(camera, FogRenderer.FogMode.FOG_SKY, viewDistance, thickFog);
			});
		}

		WorldRenderDraws.profileSwap(profiler, ProfilerGroup.StartWorld, "fog");
		FogRenderer.setupFog(camera, FogRenderer.FogMode.FOG_TERRAIN, Math.max(viewDistance - 16.0F, 32.0F), thickFog);
		ShaderDataManager.captureFogDistances();

		WorldRenderDraws.profileSwap(profiler, ProfilerGroup.StartWorld, "terrain_setup");
		setupTerrain(camera, wr.canvas_getAndIncrementFrameIndex(), shouldCullChunks(camera.getBlockPosition()));
		eventContext.setFrustum(worldRenderState.terrainFrustum);

		WorldRenderDraws.profileSwap(profiler, ProfilerGroup.StartWorld, "after_setup_event");
		FrustumSetupListener.invoke(eventContext);

		WorldRenderDraws.profileSwap(profiler, ProfilerGroup.StartWorld, "updatechunks");
		final int maxFps = mc.options.framerateLimit;
		long maxFpsLimit;

		if (maxFps == Option.FRAMERATE_LIMIT.getMaxValue()) {
			maxFpsLimit = 0L;
		} else {
			maxFpsLimit = 1000000000 / maxFps;
		}

		final long nowTime = Util.getNanos();
		final long usedTime = nowTime - frameStartNanos;

		// No idea what the 3/2 is for - looks like a hack
		final long updateBudget = wr.canvas_chunkUpdateSmoother().registerValueAndGetMean(usedTime) * 3L / 2L;
		final long clampedBudget = Mth.clamp(updateBudget, maxFpsLimit, 33333333L);

		worldRenderState.regionBuilder().upload();
		worldRenderState.regionRebuildManager.processScheduledRegions(frameStartNanos + clampedBudget);

		// WIP: need a way to set the deadline appropriately based on steady frame rate and time already elapsed.
		// Method must ensure we don't have starvation - task queue can't grow indefinitely.
		ClusterTaskManager.run(System.nanoTime() + 2000000);
		worldRenderState.rebuidDrawListsIfNeeded();

		// Note these don't have an effect when canvas pipeline is active - lighting happens in the shader
		// but they are left intact to handle any fix-function renders we don't catch
		if (world.effects().constantAmbientLight()) {
			// True for nether - yarn names here are not great
			// Causes lower face to be lit like top face
			Lighting.setupNetherLevel(MatrixData.viewMatrix);
		} else {
			Lighting.setupLevel(MatrixData.viewMatrix);
		}

		WorldRenderDraws.profileSwap(profiler, ProfilerGroup.StartWorld, "before_entities_event");
		EntityRenderPreListener.invoke(eventContext);

		WorldRenderDraws.profileSwap(profiler, ProfilerGroup.StartWorld, "entities");
		int entityCount = 0;
		int blockEntityCount = 0;

		if (advancedTranslucency) {
			final RenderTarget entityFramebuffer = mcwr.getItemEntityTarget();
			entityFramebuffer.copyDepthFrom(mcfb);
		}

		final boolean canDrawEntityOutlines = wr.canvas_canDrawEntityOutlines();

		if (canDrawEntityOutlines) {
			wr.canvas_entityOutlinesFramebuffer().clear(Minecraft.ON_OSX);
		}

		Pipeline.defaultFbo.bind();

		boolean didRenderOutlines = false;
		final CanvasImmediate immediate = worldRenderImmediate;
		final Iterator<Entity> entities = world.entitiesForRendering().iterator();
		final PostChain entityOutlineShader = wr.canvas_entityOutlineShader();
		final SortableVisibleRegionList visibleRegions = worldRenderState.cameraVisibleRegions;
		entityBlockContext.tickDelta(tickDelta);
		entityBlockContext.collectors = immediate.collectors;
		blockContext.collectors = immediate.collectors;
		SkyShadowRenderer.suppressEntityShadows(mc);

		// Because we are passing identity stack to entity renders we need to
		// apply the view transform to vanilla renders.
		final PoseStack renderSystemModelViewStack = RenderSystem.getModelViewStack();
		renderSystemModelViewStack.pushPose();
		renderSystemModelViewStack.mulPoseMatrix(viewMatrixStack.last().pose());
		RenderSystem.applyModelViewMatrix();

		entityCullingFrustum.enableRegionCulling = Configurator.cullEntityRender;

		while (entities.hasNext()) {
			final Entity entity = entities.next();

			if (!entityRenderDispatcher.shouldRender(entity, entityCullingFrustum, frameCameraX, frameCameraY, frameCameraZ) && !entity.hasIndirectPassenger(mc.player)) {
				continue;
			}

			if (entity instanceof LocalPlayer && camera.getEntity() != entity) {
				continue;
			}

			final boolean isFirstPersonPlayer = entity == camera.getEntity() && !camera.isDetached()
					&& (!(camera.getEntity() instanceof LivingEntity) || !((LivingEntity) camera.getEntity()).isSleeping());
			final boolean renderFirstPersonPlayer = isFirstPersonPlayer && FirstPersonModelHolder.cameraHandler.renderFirstPersonPlayer();

			if (isFirstPersonPlayer && !renderFirstPersonPlayer && !worldRenderState.shadowsEnabled()) {
				continue;
			}

			++entityCount;

			contextState.setCurrentEntity(entity);

			if (entity.tickCount == 0) {
				entity.xOld = entity.getX();
				entity.yOld = entity.getY();
				entity.zOld = entity.getZ();
			}

			MultiBufferSource renderProvider;

			if (isFirstPersonPlayer) {
				if (renderFirstPersonPlayer) {
					// TODO: utilize castShadow in entityFunc instead of using separate buffer
					renderProvider = materialExtrasImmediate;
				} else {
					// only render as shadow
					renderProvider = shadowExtrasImmediate;
				}
			} else if (canDrawEntityOutlines && mc.shouldEntityAppearGlowing(entity)) {
				didRenderOutlines = true;
				final OutlineBufferSource outlineVertexConsumerProvider = bufferBuilders.outlineBufferSource();
				renderProvider = outlineVertexConsumerProvider;
				final int teamColor = entity.getTeamColor();
				final int red = (teamColor >> 16 & 255);
				final int green = (teamColor >> 8 & 255);
				final int blue = teamColor & 255;
				outlineVertexConsumerProvider.setColor(red, green, blue, 255);
			} else {
				renderProvider = immediate;
			}

			entityBlockContext.setPosAndWorldFromEntity(entity);

			FirstPersonModelHolder.renderHandler.setIsRenderingPlayer(renderFirstPersonPlayer);

			// Item entity translucent typically gets drawn here in vanilla because there's no dedicated buffer for it
			wr.canvas_renderEntity(entity, frameCameraX, frameCameraY, frameCameraZ, tickDelta, identityStack, renderProvider);

			FirstPersonModelHolder.renderHandler.setIsRenderingPlayer(false);

			if (renderFirstPersonPlayer && worldRenderState.shadowsEnabled()) {
				// render unmodified player model as shadow
				wr.canvas_renderEntity(entity, frameCameraX, frameCameraY, frameCameraZ, tickDelta, identityStack, shadowExtrasImmediate);
			}
		}

		contextState.setCurrentEntity(null);
		SkyShadowRenderer.restoreEntityShadows(mc);

		WorldRenderDraws.profileSwap(profiler, ProfilerGroup.StartWorld, "blockentities");
		final int visibleRegionCount = visibleRegions.size();
		final Set<BlockEntity> noCullingBlockEntities = wr.canvas_noCullingBlockEntities();

		for (int regionIndex = 0; regionIndex < visibleRegionCount; ++regionIndex) {
			final List<BlockEntity> list = visibleRegions.get(regionIndex).getBuildState().getBlockEntities();

			final Iterator<BlockEntity> itBER = list.iterator();

			while (itBER.hasNext()) {
				final BlockEntity blockEntity = itBER.next();
				final BlockPos blockPos = blockEntity.getBlockPos();
				final SortedSet<BlockDestructionProgress> sortedSet = wr.canvas_blockBreakingProgressions().get(blockPos.asLong());
				int stage = -1;

				if (sortedSet != null && !sortedSet.isEmpty()) {
					stage = sortedSet.last().getProgress();
				}

				if (stage >= 0 && noCullingBlockEntities.contains(blockEntity)) {
					// no need to render global BERs twice if no block breaking overlay
					continue;
				}

				MultiBufferSource outputConsumer = immediate;
				contextState.setCurrentBlockEntity(blockEntity);

				identityStack.pushPose();
				identityStack.translate(blockPos.getX() - frameCameraX, blockPos.getY() - frameCameraY, blockPos.getZ() - frameCameraZ);

				if (stage >= 0) {
					final PoseStack.Pose xform = identityStack.last();
					final VertexConsumer overlayConsumer = new SheetedDecalTextureGenerator(bufferBuilders.crumblingBufferSource().getBuffer(ModelBakery.DESTROY_TYPES.get(stage)), xform.pose(), xform.normal());

					outputConsumer = (renderLayer) -> {
						final VertexConsumer baseConsumer = immediate.getBuffer(renderLayer);
						return renderLayer.affectsCrumbling() ? VertexMultiConsumer.create(overlayConsumer, baseConsumer) : baseConsumer;
					};
				}

				++blockEntityCount;
				WorldRenderDraws.renderBlockEntitySafely(blockEntity, tickDelta, identityStack, outputConsumer);
				identityStack.popPose();
			}
		}

		synchronized (noCullingBlockEntities) {
			final Iterator<BlockEntity> globalBERs = noCullingBlockEntities.iterator();

			while (globalBERs.hasNext()) {
				final BlockEntity blockEntity2 = globalBERs.next();
				final BlockPos blockPos2 = blockEntity2.getBlockPos();
				contextState.setCurrentBlockEntity(blockEntity2);
				identityStack.pushPose();
				identityStack.translate(blockPos2.getX() - frameCameraX, blockPos2.getY() - frameCameraY, blockPos2.getZ() - frameCameraZ);
				++blockEntityCount;
				WorldRenderDraws.renderBlockEntitySafely(blockEntity2, tickDelta, identityStack, immediate);
				identityStack.popPose();
			}
		}

		contextState.setCurrentBlockEntity(null);

		RenderState.disable();

		try (DrawableStream entityBuffer = immediate.prepareDrawable(TargetRenderState.MAIN);
			DrawableStream materialExtrasBuffer = materialExtrasImmediate.prepareDrawable(TargetRenderState.MAIN);
			DrawableStream shadowExtrasBuffer = shadowExtrasImmediate.prepareDrawable(TargetRenderState.MAIN)
		) {
			WorldRenderDraws.profileSwap(profiler, ProfilerGroup.ShadowMap, "shadow_map");
			SkyShadowRenderer.render(this, entityBuffer, shadowExtrasBuffer);
			shadowExtrasBuffer.close();

			WorldRenderDraws.profileSwap(profiler, ProfilerGroup.EndWorld, "terrain_solid");
			worldRenderState.renderSolidTerrain();

			WorldRenderDraws.profileSwap(profiler, ProfilerGroup.EndWorld, "entity_draw_solid");
			entityBuffer.draw(false);
			entityBuffer.close();

			materialExtrasBuffer.draw(false);
			materialExtrasBuffer.close();
		}

		WorldRenderDraws.profileSwap(profiler, ProfilerGroup.EndWorld, "after_entities_event");

		// Stuff here should probably expect identity matrix. If not, move matrix operations to relevant compat holders.
		eventContext.matrixStack().pushPose();
		eventContext.matrixStack().setIdentity();
		EntityRenderPostListener.invoke(eventContext);
		eventContext.matrixStack().popPose();

		bufferBuilders.outlineBufferSource().endOutlineBatch();

		if (didRenderOutlines) {
			entityOutlineShader.process(tickDelta);
			Pipeline.defaultFbo.bind();
		}

		WorldRenderDraws.profileSwap(profiler, ProfilerGroup.EndWorld, "destroyProgress");

		// honor damage render layer irrespective of model material
		blockContext.collectors = null;

		final ObjectIterator<Entry<SortedSet<BlockDestructionProgress>>> breakings = wr.canvas_blockBreakingProgressions().long2ObjectEntrySet().iterator();

		while (breakings.hasNext()) {
			final Entry<SortedSet<BlockDestructionProgress>> entry = breakings.next();
			final BlockPos breakPos = BlockPos.of(entry.getLongKey());
			final double y = breakPos.getX() - frameCameraX;
			final double z = breakPos.getY() - frameCameraY;
			final double aa = breakPos.getZ() - frameCameraZ;

			if (y * y + z * z + aa * aa <= 1024.0D) {
				final SortedSet<BlockDestructionProgress> breakSet = entry.getValue();

				if (breakSet != null && !breakSet.isEmpty()) {
					final int stage = breakSet.last().getProgress();
					identityStack.pushPose();
					identityStack.translate(breakPos.getX() - frameCameraX, breakPos.getY() - frameCameraY, breakPos.getZ() - frameCameraZ);
					final PoseStack.Pose xform = identityStack.last();
					final VertexConsumer vertexConsumer2 = new SheetedDecalTextureGenerator(bufferBuilders.crumblingBufferSource().getBuffer(ModelBakery.DESTROY_TYPES.get(stage)), xform.pose(), xform.normal());
					mc.getBlockRenderer().renderBreakingTexture(world.getBlockState(breakPos), breakPos, world, identityStack, vertexConsumer2);
					identityStack.popPose();
				}
			}
		}

		blockContext.collectors = immediate.collectors;

		WorldRenderDraws.profileSwap(profiler, ProfilerGroup.EndWorld, "outline");
		final HitResult hitResult = mc.hitResult;

		if (BlockOutlinePreListener.invoke(eventContext, hitResult)) {
			if (blockOutlines && hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
				final BlockPos blockOutlinePos = ((BlockHitResult) hitResult).getBlockPos();
				final BlockState blockOutlineState = world.getBlockState(blockOutlinePos);

				if (!blockOutlineState.isAir() && world.getWorldBorder().isWithinBounds(blockOutlinePos)) {
					// THIS IS WHEN LIGHTENING RENDERS IN VANILLA
					final VertexConsumer blockOutlineConumer = immediate.getBuffer(RenderType.lines());

					// Lines need to be drawn with identity matrix because transforms will be pre-applied at render time
					eventContext.matrixStack().pushPose();
					eventContext.matrixStack().setIdentity();
					eventContext.prepareBlockOutline(camera.getEntity(), frameCameraX, frameCameraY, frameCameraZ, blockOutlinePos, blockOutlineState);

					if (BlockOutlineListener.invoke(eventContext, eventContext)) {
						wr.canvas_drawBlockOutline(identityStack, blockOutlineConumer, camera.getEntity(), frameCameraX, frameCameraY, frameCameraZ, blockOutlinePos, blockOutlineState);
					}

					eventContext.matrixStack().popPose();
				}
			}
		}

		RenderState.disable();

		// NB: view matrix is already applied to GL state before renderWorld is called

		WorldRenderDraws.profileSwap(profiler, ProfilerGroup.EndWorld, "before_debug_event");

		if (Configurator.debugOcclusionBoxes) {
			WorldRenderDraws.renderCullBoxes(worldRenderState.renderRegionStorage, frameCameraX, frameCameraY, frameCameraZ, tickDelta);
		}

		DebugRenderListener.invoke(eventContext);

		// We still pass in the transformed stack because that is what debug renderer normally gets
		mc.debugRenderer.render(viewMatrixStack, immediate, frameCameraX, frameCameraY, frameCameraZ);

		WorldRenderDraws.profileSwap(profiler, ProfilerGroup.EndWorld, "draw_solid");

		// Should generally not have anything here but draw in case content injected in hooks
		immediate.drawCollectors(TargetRenderState.MAIN);

		// These should be empty and probably won't work, but prevent them from accumulating if somehow used.
		immediate.endBatch(RenderType.armorGlint());
		immediate.endBatch(RenderType.armorEntityGlint());
		immediate.endBatch(RenderType.glint());
		immediate.endBatch(RenderType.glintDirect());
		immediate.endBatch(RenderType.glintTranslucent());
		immediate.endBatch(RenderType.entityGlint());
		immediate.endBatch(RenderType.entityGlintDirect());

		// draw order is important and our sorting mechanism doesn't cover
		immediate.endBatch(RenderType.waterMask());

		bufferBuilders.crumblingBufferSource().endBatch();

		visibleRegions.scheduleResort(cameraVec3d);

		if (advancedTranslucency) {
			WorldRenderDraws.profileSwap(profiler, ProfilerGroup.EndWorld, "translucent");

			Pipeline.translucentTerrainFbo.copyDepthFrom(Pipeline.defaultFbo);
			Pipeline.translucentTerrainFbo.bind();

			// in fabulous mode, the only thing that renders to terrain translucency
			// is terrain itself - so everything else can be rendered first

			// Lines draw to entity (item) target
			immediate.endBatch(RenderType.lines());

			// PERF: Why is this here? Should be empty
			immediate.drawCollectors(TargetRenderState.TRANSLUCENT);

			// This catches entity layer and any remaining non-main layers
			immediate.endBatch();

			worldRenderState.renderTranslucentTerrain();

			// NB: vanilla renders tripwire here but we combine into translucent

			Pipeline.translucentParticlesFbo.copyDepthFrom(Pipeline.defaultFbo);
			Pipeline.translucentParticlesFbo.bind();

			WorldRenderDraws.profileSwap(profiler, ProfilerGroup.EndWorld, "particles");
			particleRenderer.renderParticles(mc.particleEngine, identityStack, immediate.collectors, lightmapTextureManager, camera, tickDelta);

			Pipeline.defaultFbo.bind();
		} else {
			WorldRenderDraws.profileSwap(profiler, ProfilerGroup.EndWorld, "translucent");
			worldRenderState.renderTranslucentTerrain();

			// without fabulous transparency important that lines
			// and other translucent elements get drawn on top of terrain
			immediate.endBatch(RenderType.lines());

			// PERF: how is this needed? - would either have been drawn above or will be drawn below
			immediate.drawCollectors(TargetRenderState.TRANSLUCENT);

			// This catches entity layer and any remaining non-main layers
			immediate.endBatch();

			WorldRenderDraws.profileSwap(profiler, ProfilerGroup.EndWorld, "particles");
			particleRenderer.renderParticles(mc.particleEngine, identityStack, immediate.collectors, lightmapTextureManager, camera, tickDelta);
		}

		RenderState.disable();

		WorldRenderDraws.profileSwap(profiler, ProfilerGroup.EndWorld, "after_translucent_event");

		// Stuff here would usually want the render system matrix stack to have the view matrix applied.
		TranslucentPostListener.invoke(eventContext);

		// FEAT: need a new event here for weather/cloud targets that has matrix applies to render state
		// TODO: move the Mallib world last to the new event when fabulous is on

		renderSystemModelViewStack.popPose();
		RenderSystem.applyModelViewMatrix();

		RenderState.disable();
		GlProgram.deactivate();

		renderClouds(mc, profiler, viewMatrixStack, projectionMatrix, tickDelta, frameCameraX, frameCameraY, frameCameraZ);

		// WIP: need to properly target the designated buffer here in both clouds and weather
		// also need to ensure works with non-fabulous pipelines
		WorldRenderDraws.profileSwap(profiler, ProfilerGroup.EndWorld, "weather");

		// Apply view transform locally for vanilla weather and world border rendering
		renderSystemModelViewStack.pushPose();
		renderSystemModelViewStack.mulPoseMatrix(viewMatrixStack.last().pose());
		RenderSystem.applyModelViewMatrix();

		if (advancedTranslucency) {
			RenderStateShard.WEATHER_TARGET.setupRenderState();
			wr.canvas_renderWeather(lightmapTextureManager, tickDelta, frameCameraX, frameCameraY, frameCameraZ);
			wr.canvas_renderWorldBorder(camera);
			RenderStateShard.WEATHER_TARGET.clearRenderState();
			PipelineManager.beFabulous();

			Pipeline.defaultFbo.bind();
		} else {
			GFX.depthMask(false);
			wr.canvas_renderWeather(lightmapTextureManager, tickDelta, frameCameraX, frameCameraY, frameCameraZ);
			wr.canvas_renderWorldBorder(camera);
			GFX.depthMask(true);
		}

		// doesn't make any sense with our chunk culling scheme
		// this.renderChunkDebugInfo(camera);
		WorldRenderDraws.profileSwap(profiler, ProfilerGroup.AfterFabulous, "render_last_event");

		// Stuff here would usually want the render system matrix stack to have the view matrix applied.
		WorldRenderLastListener.invoke(eventContext);

		// Move these up if otherwise.
		renderSystemModelViewStack.popPose();
		RenderSystem.applyModelViewMatrix();

		GFX.depthMask(true);
		GFX.disableBlend();
		FogRenderer.setupNoFog();
		entityBlockContext.collectors = null;
		blockContext.collectors = null;

		wr.canvas_setEntityCounts(entityCount, blockEntityCount);

		//RenderState.enablePrint = true;

		Timekeeper.instance.swap(ProfilerGroup.AfterFabulous, "after world");
	}

	private void renderClouds(Minecraft mc, ProfilerFiller profiler, PoseStack identityStack, Matrix4f projectionMatrix, float tickDelta, double cameraX, double cameraY, double cameraZ) {
		if (mc.options.getCloudsType() != CloudStatus.OFF) {
			WorldRenderDraws.profileSwap(profiler, ProfilerGroup.EndWorld, "clouds");

			if (Pipeline.fabCloudsFbo > 0) {
				GFX.bindFramebuffer(GFX.GL_FRAMEBUFFER, Pipeline.fabCloudsFbo);
			}

			// NB: cloud renderer normally gets stack with view rotation but we apply that in VertexBuffer mixin
			renderClouds(identityStack, projectionMatrix, tickDelta, cameraX, cameraY, cameraZ);

			if (Pipeline.fabCloudsFbo > 0) {
				Pipeline.defaultFbo.bind();
			}
		}
	}

	public void updateNoCullingBlockEntities(ObjectOpenHashSet<BlockEntity> removedBlockEntities, ObjectOpenHashSet<BlockEntity> addedBlockEntities) {
		((LevelRenderer) vanillaWorldRenderer).updateGlobalBlockEntities(removedBlockEntities, addedBlockEntities);
	}

	public void scheduleRegionRender(int x, int y, int z, boolean urgent) {
		worldRenderState.renderRegionStorage.scheduleRebuild(x << 4, y << 4, z << 4, urgent);
	}

	@Override
	public void renderLevel(PoseStack viewMatrixStack, float tickDelta, long frameStartNanos, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightmapTextureManager, Matrix4f projectionMatrix) {
		final Minecraft mc = Minecraft.getInstance();
		final boolean wasFabulous = Pipeline.isFabulous();

		BufferSynchronizer.checkPoint();
		DirectBufferAllocator.update();
		TransferBuffers.update();
		PipelineManager.reloadIfNeeded(false);
		FlawlessFramesController.handleToggle();

		if (wasFabulous != Pipeline.isFabulous()) {
			vanillaWorldRenderer.canvas_setupFabulousBuffers();
		}

		if (mc.options.renderDistance != worldRenderState.chunkRenderDistance()) {
			allChanged();
		}

		mc.getProfiler().popPush("dynamic_lighting");

		// All managed draws - including anything targeting vertex consumer - will have camera rotation applied
		// in shader - this gives better consistency with terrain rendering and may be more intuitive for lighting.
		// Unmanaged draws that do direct drawing will expect the matrix stack to have camera rotation in it and may
		// use it either to transform the render state or to transform vertices.
		// For this reason we have two different stacks.
		identityStack.last().pose().setIdentity();
		identityStack.last().normal().setIdentity();

		final Matrix4f viewMatrix = viewMatrixStack.last().pose();
		worldRenderState.terrainFrustum.prepare(viewMatrix, tickDelta, camera, worldRenderState.terrainIterator.cameraVisibility.hasNearOccluders());
		entityCullingFrustum.prepare(viewMatrix, tickDelta, camera, projectionMatrix);
		ShaderDataManager.update(viewMatrixStack.last(), projectionMatrix, camera);
		MatrixState.set(MatrixState.CAMERA);

		eventContext.prepare(this, viewMatrixStack, tickDelta, frameStartNanos, renderBlockOutline, camera, gameRenderer, lightmapTextureManager, projectionMatrix, worldRenderImmediate, mc.getProfiler(), Minecraft.useShaderTransparency(), worldRenderState.getWorld());

		WorldRenderStartListener.invoke(eventContext);
		PipelineManager.beforeWorldRender();
		renderWorld(viewMatrixStack, tickDelta, frameStartNanos, renderBlockOutline, camera, gameRenderer, lightmapTextureManager, projectionMatrix);
		WorldRenderPostListener.invoke(eventContext);

		RenderSystem.applyModelViewMatrix();
		MatrixState.set(MatrixState.SCREEN);
		ScreenRenderState.setRenderingHand(true);
		BufferSynchronizer.checkPoint();
	}

	@Override
	public void allChanged() {
		PipelineManager.reloadIfNeeded(true);

		// cause injections to fire but disable all other vanilla logic
		// by setting world to null temporarily
		final ClientLevel swapWorld = vanillaWorldRenderer.canvas_world();
		vanillaWorldRenderer.canvas_setWorldNoSideEffects(null);
		super.allChanged();
		vanillaWorldRenderer.canvas_setWorldNoSideEffects(swapWorld);

		// has the logic from super.reload() that requires private access
		vanillaWorldRenderer.canvas_reload();

		worldRenderState.clear();
		TransferBuffers.forceReload();
		StreamBufferAllocator.forceReload();
		//ClassInspector.inspect();
	}

	@Override
	public boolean hasRenderedAllChunks() {
		return worldRenderState.regionRebuildManager.isEmpty() && worldRenderState.regionBuilder().isEmpty() && !worldRenderState.terrainIterator.hasWork();
	}

	@Override
	public int countRenderedChunks() {
		return worldRenderState.cameraVisibleRegions.getActiveCount();
	}

	@Override
	@SuppressWarnings("resource")
	public String getChunkStatistics() {
		final int len = worldRenderState.renderRegionStorage.loadedRegionCount();
		final int count = countRenderedChunks();
		String shadowRegionString = "";

		if (worldRenderState.shadowsEnabled()) {
			final int shadowCount = worldRenderState.shadowVisibleRegions[0].getActiveCount()
					+ worldRenderState.shadowVisibleRegions[1].getActiveCount()
					+ worldRenderState.shadowVisibleRegions[2].getActiveCount()
					+ worldRenderState.shadowVisibleRegions[3].getActiveCount();

			shadowRegionString = String.format("S: %d,", shadowCount);
		}

		return String.format("C: %d/%d %sD: %d, %s", count, len, Minecraft.getInstance().smartCull ? "(s) " : "", worldRenderState.chunkRenderDistance(), shadowRegionString);
	}
}
