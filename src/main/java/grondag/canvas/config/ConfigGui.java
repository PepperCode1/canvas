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

package grondag.canvas.config;

import static grondag.canvas.config.ConfigManager.DEFAULTS;
import static grondag.canvas.config.ConfigManager.parse;
import static grondag.canvas.config.Configurator.advancedTerrainCulling;
import static grondag.canvas.config.Configurator.blendFluidColors;
import static grondag.canvas.config.Configurator.clampExteriorVertices;
import static grondag.canvas.config.Configurator.conciseErrors;
import static grondag.canvas.config.Configurator.cullBackfacingTerrain;
import static grondag.canvas.config.Configurator.cullEntityRender;
import static grondag.canvas.config.Configurator.cullParticles;
import static grondag.canvas.config.Configurator.debugNativeMemoryAllocation;
import static grondag.canvas.config.Configurator.debugOcclusionBoxes;
import static grondag.canvas.config.Configurator.debugOcclusionRaster;
import static grondag.canvas.config.Configurator.debugSpriteAtlas;
import static grondag.canvas.config.Configurator.disableUnseenSpriteAnimation;
import static grondag.canvas.config.Configurator.disableVignette;
import static grondag.canvas.config.Configurator.displayRenderProfiler;
import static grondag.canvas.config.Configurator.dynamicFrustumPadding;
import static grondag.canvas.config.Configurator.enableBufferDebug;
import static grondag.canvas.config.Configurator.enableLifeCycleDebug;
import static grondag.canvas.config.Configurator.enableNearOccluders;
import static grondag.canvas.config.Configurator.fixLuminousBlockShading;
import static grondag.canvas.config.Configurator.forceJmxModelLoading;
import static grondag.canvas.config.Configurator.greedyRenderThread;
import static grondag.canvas.config.Configurator.groupAnimatedSprites;
import static grondag.canvas.config.Configurator.logGlStateChanges;
import static grondag.canvas.config.Configurator.logMachineInfo;
import static grondag.canvas.config.Configurator.logMaterials;
import static grondag.canvas.config.Configurator.logMissingUniforms;
import static grondag.canvas.config.Configurator.logRenderLagSpikes;
import static grondag.canvas.config.Configurator.pipelineId;
import static grondag.canvas.config.Configurator.preprocessShaderSource;
import static grondag.canvas.config.Configurator.preventDepthFighting;
import static grondag.canvas.config.Configurator.profilerDetailLevel;
import static grondag.canvas.config.Configurator.profilerDisplayMode;
import static grondag.canvas.config.Configurator.profilerOverlayScale;
import static grondag.canvas.config.Configurator.reduceResolutionOnMac;
import static grondag.canvas.config.Configurator.reload;
import static grondag.canvas.config.Configurator.renderLagSpikeFps;
import static grondag.canvas.config.Configurator.renderWhiteGlassAsOccluder;
import static grondag.canvas.config.Configurator.safeNativeMemoryAllocation;
import static grondag.canvas.config.Configurator.semiFlatLighting;
import static grondag.canvas.config.Configurator.shaderDebug;
import static grondag.canvas.config.Configurator.staticFrustumPadding;
import static grondag.canvas.config.Configurator.steadyDebugScreen;
import static grondag.canvas.config.Configurator.terrainSetupOffThread;
import static grondag.canvas.config.Configurator.traceOcclusionEdgeCases;
import static grondag.canvas.config.Configurator.traceTextureLoad;
import static grondag.canvas.config.Configurator.transferBufferMode;
import static grondag.canvas.config.Configurator.useCombinedThreadPool;
import static grondag.canvas.config.Configurator.wavyGrass;

import java.lang.ref.WeakReference;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.resources.ResourceLocation;

import grondag.canvas.buffer.render.TransferBuffers;
import grondag.canvas.perf.Timekeeper;
import grondag.canvas.pipeline.config.PipelineConfig;
import grondag.canvas.pipeline.config.PipelineLoader;

public class ConfigGui {
	static final ConfigEntryBuilder ENTRY_BUILDER = ConfigEntryBuilder.create();

	/**
	 * Use to stash parent screen during display.
	 */
	private static WeakReference<Screen> configScreen;
	private static PipelineSelectorEntry pipeline;

	static ResourceLocation pipeline() {
		return pipeline == null ? PipelineConfig.DEFAULT_ID : pipeline.getValue().id;
	}

	static Screen current() {
		return configScreen.get();
	}

	public static Screen display(Screen parent) {
		reload = false;

		final ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(new TranslatableComponent("config.canvas.title"))
				.setSavingRunnable(ConfigManager::saveUserInput)
				.setAlwaysShowTabs(false)
				.setShouldListSmoothScroll(true)
				.setShouldListSmoothScroll(true);

		builder.setGlobalized(true);
		builder.setGlobalizedExpanded(false);

		// FEATURES
		final ConfigCategory features = builder.getOrCreateCategory(new TranslatableComponent("config.canvas.category.features"));

		pipeline = new PipelineSelectorEntry(PipelineLoader.get(pipelineId));

		features.addEntry(pipeline);

		features.addEntry(new PipelineOptionsEntry());

		features.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.blend_fluid_colors"), blendFluidColors)
				.setDefaultValue(DEFAULTS.blendFluidColors)
				.setTooltip(parse("config.canvas.help.blend_fluid_colors"))
				.setSaveConsumer(b -> {
					reload |= blendFluidColors != b;
					blendFluidColors = b;
				})
				.build());

		features.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.wavy_grass"), wavyGrass)
				.setDefaultValue(DEFAULTS.wavyGrass)
				.setTooltip(parse("config.canvas.help.wavy_grass"))
				.setSaveConsumer(b -> {
					reload |= wavyGrass != b;
					wavyGrass = b;
				})
				.build());

		features.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.disable_vignette"), disableVignette)
				.setDefaultValue(DEFAULTS.disableVignette)
				.setTooltip(parse("config.canvas.help.disable_vignette"))
				.setSaveConsumer(b -> disableVignette = b)
				.build());

		// LIGHTING
		//final ConfigCategory lighting = builder.getOrCreateCategory(new TranslatableText("config.canvas.category.lighting"));

		//		lighting.addEntry(ENTRY_BUILDER
		//				.startBooleanToggle(new TranslatableText("config.canvas.value.hd_lightmaps"), hdLightmaps)
		//				.setDefaultValue(DEFAULTS.hdLightmaps)
		//				.setTooltip(parse("config.canvas.help.hd_lightmaps"))
		//				.setSaveConsumer(b -> {
		//					reload |= hdLightmaps != b;
		//					hdLightmaps = b;
		//				})
		//				.build());

		//		lighting.addEntry(ENTRY_BUILDER
		//				.startBooleanToggle(new TranslatableText("config.canvas.value.more_lightmap"), moreLightmap)
		//				.setDefaultValue(DEFAULTS.moreLightmap)
		//				.setTooltip(parse("config.canvas.help.more_lightmap"))
		//				.setSaveConsumer(b -> moreLightmap = b)
		//				.build());

		//		lighting.addEntry(ENTRY_BUILDER
		//				.startBooleanToggle(new TranslatableText("config.canvas.value.lightmap_noise"), lightmapNoise)
		//				.setDefaultValue(DEFAULTS.lightmapNoise)
		//				.setTooltip(parse("config.canvas.help.lightmap_noise"))
		//				.setSaveConsumer(b -> {
		//					reload |= lightmapNoise != b;
		//					lightmapNoise = b;
		//				})
		//				.build());

		//		lighting.addEntry(ENTRY_BUILDER
		//				.startIntSlider(new TranslatableText("config.canvas.value.lightmap_delay_frames"), maxLightmapDelayFrames, 0, 20)
		//				.setDefaultValue(DEFAULTS.maxLightmapDelayFrames)
		//				.setTooltip(parse("config.canvas.help.lightmap_delay_frames"))
		//				.setSaveConsumer(b -> maxLightmapDelayFrames = b)
		//				.build());

		features.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.semi_flat_lighting"), semiFlatLighting)
				.setDefaultValue(DEFAULTS.semiFlatLighting)
				.setTooltip(parse("config.canvas.help.semi_flat_lighting"))
				.setSaveConsumer(b -> {
					reload |= semiFlatLighting != b;
					semiFlatLighting = b;
				})
				.build());

		// TWEAKS
		final ConfigCategory tweaks = builder.getOrCreateCategory(new TranslatableComponent("config.canvas.category.tweaks"));

		//        tweaks.addOption(new BooleanListEntry("config.canvas.value.vanilla_chunk_matrix", disableVanillaChunkMatrix, "config.canvas.reset",
		//                () -> DEFAULTS.disableVanillaChunkMatrix, b -> disableVanillaChunkMatrix = b,
		//                () -> Optional.of(parse("config.canvas.help.vanilla_chunk_matrix"))));

		tweaks.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.adjust_vanilla_geometry"), preventDepthFighting)
				.setDefaultValue(DEFAULTS.preventDepthFighting)
				.setTooltip(parse("config.canvas.help.adjust_vanilla_geometry"))
				.setSaveConsumer(b -> {
					reload |= preventDepthFighting != b;
					preventDepthFighting = b;
				})
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.clamp_exterior_vertices"), clampExteriorVertices)
				.setDefaultValue(DEFAULTS.clampExteriorVertices)
				.setTooltip(parse("config.canvas.help.clamp_exterior_vertices"))
				.setSaveConsumer(b -> {
					reload |= clampExteriorVertices != b;
					clampExteriorVertices = b;
				})
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.fix_luminous_block_shade"), fixLuminousBlockShading)
				.setDefaultValue(DEFAULTS.fixLuminousBlockShading)
				.setTooltip(parse("config.canvas.help.fix_luminous_block_shade"))
				.setSaveConsumer(b -> {
					reload |= fixLuminousBlockShading != b;
					fixLuminousBlockShading = b;
				})
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.advanced_terrain_culling"), advancedTerrainCulling)
				.setDefaultValue(DEFAULTS.advancedTerrainCulling)
				.setTooltip(parse("config.canvas.help.advanced_terrain_culling"))
				.setSaveConsumer(b -> {
					reload |= advancedTerrainCulling != b;
					advancedTerrainCulling = b;
				})
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.terrain_setup_off_thread"), terrainSetupOffThread)
				.setDefaultValue(DEFAULTS.terrainSetupOffThread)
				.setTooltip(parse("config.canvas.help.terrain_setup_off_thread"))
				.setSaveConsumer(b -> {
					reload |= terrainSetupOffThread != b;
					terrainSetupOffThread = b;
				})
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.safe_native_allocation"), safeNativeMemoryAllocation)
				.setDefaultValue(DEFAULTS.safeNativeMemoryAllocation)
				.setTooltip(parse("config.canvas.help.safe_native_allocation"))
				.setSaveConsumer(b -> safeNativeMemoryAllocation = b)
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.cull_entity_render"), cullEntityRender)
				.setDefaultValue(DEFAULTS.cullEntityRender)
				.setTooltip(parse("config.canvas.help.cull_entity_render"))
				.setSaveConsumer(b -> {
					cullEntityRender = b;
				})
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.greedy_render_thread"), greedyRenderThread)
				.setDefaultValue(DEFAULTS.greedyRenderThread)
				.setTooltip(parse("config.canvas.help.greedy_render_thread"))
				.setSaveConsumer(b -> {
					greedyRenderThread = b;
				})
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.force_jmx_loading"), forceJmxModelLoading)
				.setDefaultValue(DEFAULTS.forceJmxModelLoading)
				.setTooltip(parse("config.canvas.help.force_jmx_loading"))
				.setSaveConsumer(b -> {
					forceJmxModelLoading = b;
				})
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.reduce_resolution_on_mac"), reduceResolutionOnMac)
				.setDefaultValue(DEFAULTS.reduceResolutionOnMac)
				.setTooltip(parse("config.canvas.help.reduce_resolution_on_mac"))
				.setSaveConsumer(b -> {
					reduceResolutionOnMac = b;
				})
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startIntSlider(new TranslatableComponent("config.canvas.value.static_frustum_padding"), staticFrustumPadding, 0, 20)
				.setDefaultValue(DEFAULTS.staticFrustumPadding)
				.setTooltip(parse("config.canvas.help.static_frustum_padding"))
				.setSaveConsumer(b -> {
					staticFrustumPadding = b;
				})
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startIntSlider(new TranslatableComponent("config.canvas.value.dynamic_frustum_padding"), dynamicFrustumPadding, 0, 30)
				.setDefaultValue(DEFAULTS.dynamicFrustumPadding)
				.setTooltip(parse("config.canvas.help.dynamic_frustum_padding"))
				.setSaveConsumer(b -> {
					dynamicFrustumPadding = b;
				})
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.cull_particles"), cullParticles)
				.setDefaultValue(DEFAULTS.cullParticles)
				.setTooltip(parse("config.canvas.help.cull_particles"))
				.setSaveConsumer(b -> {
					cullParticles = b;
				})
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.enable_near_occluders"), enableNearOccluders)
				.setDefaultValue(DEFAULTS.enableNearOccluders)
				.setTooltip(parse("config.canvas.help.enable_near_occluders"))
				.setSaveConsumer(b -> {
					enableNearOccluders = b;
				})
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.use_combined_thread_pool"), useCombinedThreadPool)
				.setDefaultValue(DEFAULTS.useCombinedThreadPool)
				.setTooltip(parse("config.canvas.help.use_combined_thread_pool"))
				.requireRestart()
				.setSaveConsumer(b -> {
					useCombinedThreadPool = b;
				})
				.build());

		tweaks.addEntry(ENTRY_BUILDER.startEnumSelector(new TranslatableComponent("config.canvas.value.transfer_buffer_mode"),
				TransferBuffers.Config.class,
				transferBufferMode)
				.setDefaultValue(DEFAULTS.transferBufferMode)
				.setSaveConsumer(b -> {
					reload |= transferBufferMode != b;
					transferBufferMode = b;
				})
				.setEnumNameProvider(a -> new TextComponent(a.toString()))
				.setTooltip(parse("config.canvas.help.transfer_buffer_mode"))
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.steady_debug_screen"), steadyDebugScreen)
				.setDefaultValue(DEFAULTS.steadyDebugScreen)
				.setTooltip(parse("config.canvas.help.steady_debug_screen"))
				.setSaveConsumer(b -> {
					steadyDebugScreen = b;
				})
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.disable_unseen_sprite_animation"), disableUnseenSpriteAnimation)
				.setDefaultValue(DEFAULTS.disableUnseenSpriteAnimation)
				.setTooltip(parse("config.canvas.help.disable_unseen_sprite_animation"))
				.setSaveConsumer(b -> {
					reload |= disableUnseenSpriteAnimation != b;
					disableUnseenSpriteAnimation = b;
				})
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.group_animated_sprites"), groupAnimatedSprites)
				.setDefaultValue(DEFAULTS.groupAnimatedSprites)
				.setTooltip(parse("config.canvas.help.group_animated_sprites"))
				.setSaveConsumer(b -> groupAnimatedSprites = b)
				.build());

		tweaks.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.cull_backfacing_terrain"), cullBackfacingTerrain)
				.setDefaultValue(DEFAULTS.cullBackfacingTerrain)
				.setTooltip(parse("config.canvas.help.cull_backfacing_terrain"))
				.setSaveConsumer(b -> {
					reload |= cullBackfacingTerrain != b;
					cullBackfacingTerrain = b;
				})
				.build());

		// DEBUG
		final ConfigCategory debug = builder.getOrCreateCategory(new TranslatableComponent("config.canvas.category.debug"));

		debug.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.shader_debug"), shaderDebug)
				.setDefaultValue(DEFAULTS.shaderDebug)
				.setTooltip(parse("config.canvas.help.shader_debug"))
				.setSaveConsumer(b -> shaderDebug = b)
				.build());

		debug.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.preprocess_shader_source"), preprocessShaderSource)
				.setDefaultValue(DEFAULTS.preprocessShaderSource)
				.setTooltip(parse("config.canvas.help.preprocess_shader_source"))
				.setSaveConsumer(b -> {
					reload |= preprocessShaderSource != b;
					preprocessShaderSource = b;
				})
				.build());

		//		debug.addEntry(ENTRY_BUILDER
		//				.startBooleanToggle(new TranslatableText("config.canvas.value.shader_debug_lightmap"), lightmapDebug)
		//				.setDefaultValue(DEFAULTS.lightmapDebug)
		//				.setTooltip(parse("config.canvas.help.shader_debug_lightmap"))
		//				.setSaveConsumer(b -> lightmapDebug = b)
		//				.build());

		debug.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.concise_errors"), conciseErrors)
				.setDefaultValue(DEFAULTS.conciseErrors)
				.setTooltip(parse("config.canvas.help.concise_errors"))
				.setSaveConsumer(b -> conciseErrors = b)
				.build());

		debug.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.log_machine_info"), logMachineInfo)
				.setDefaultValue(DEFAULTS.logMachineInfo)
				.setTooltip(parse("config.canvas.help.log_machine_info"))
				.setSaveConsumer(b -> logMachineInfo = b)
				.build());

		debug.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.log_gl_state_changes"), logGlStateChanges)
				.setDefaultValue(DEFAULTS.logGlStateChanges)
				.setTooltip(parse("config.canvas.help.log_gl_state_changes"))
				.setSaveConsumer(b -> logGlStateChanges = b)
				.build());

		debug.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.debug_native_allocation"), debugNativeMemoryAllocation)
				.setDefaultValue(DEFAULTS.debugNativeMemoryAllocation)
				.setTooltip(parse("config.canvas.help.debug_native_allocation"))
				.setSaveConsumer(b -> debugNativeMemoryAllocation = b)
				.build());

		debug.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.debug_occlusion_raster"), debugOcclusionRaster)
				.setDefaultValue(DEFAULTS.debugOcclusionRaster)
				.setTooltip(parse("config.canvas.help.debug_occlusion_raster"))
				.setSaveConsumer(b -> debugOcclusionRaster = b)
				.build());

		debug.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.debug_occlusion_boxes"), debugOcclusionBoxes)
				.setDefaultValue(DEFAULTS.debugOcclusionBoxes)
				.setTooltip(parse("config.canvas.help.debug_occlusion_boxes"))
				.setSaveConsumer(b -> debugOcclusionBoxes = b)
				.build());

		debug.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.white_glass_occludes_terrain"), renderWhiteGlassAsOccluder)
				.setDefaultValue(DEFAULTS.renderWhiteGlassAsOccluder)
				.setTooltip(parse("config.canvas.help.white_glass_occludes_terrain"))
				.setSaveConsumer(b -> {
					reload |= renderWhiteGlassAsOccluder != b;
					renderWhiteGlassAsOccluder = b;
				})
				.build());

		debug.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.trace_occlusion_edge_cases"), traceOcclusionEdgeCases)
				.setDefaultValue(DEFAULTS.traceOcclusionEdgeCases)
				.setTooltip(parse("config.canvas.help.trace_occlusion_edge_cases"))
				.setSaveConsumer(b -> traceOcclusionEdgeCases = b)
				.build());

		debug.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.buffer_debug"), enableBufferDebug)
				.setDefaultValue(DEFAULTS.enableBufferDebug)
				.setTooltip(parse("config.canvas.help.buffer_debug"))
				.setSaveConsumer(b -> enableBufferDebug = b)
				.build());

		debug.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.lifecycle_debug"), enableLifeCycleDebug)
				.setDefaultValue(DEFAULTS.enableLifeCycleDebug)
				.setTooltip(parse("config.canvas.help.lifecycle_debug"))
				.setSaveConsumer(b -> enableLifeCycleDebug = b)
				.build());

		debug.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.log_missing_uniforms"), logMissingUniforms)
				.setDefaultValue(DEFAULTS.logMissingUniforms)
				.setTooltip(parse("config.canvas.help.log_missing_uniforms"))
				.setSaveConsumer(b -> logMissingUniforms = b)
				.build());

		debug.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.log_materials"), logMaterials)
				.setDefaultValue(DEFAULTS.logMaterials)
				.setTooltip(parse("config.canvas.help.log_materials"))
				.setSaveConsumer(b -> logMaterials = b)
				.build());

		debug.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.log_render_lag_spikes"), logRenderLagSpikes)
				.setDefaultValue(DEFAULTS.logRenderLagSpikes)
				.setTooltip(parse("config.canvas.help.log_render_lag_spikes"))
				.setSaveConsumer(b -> {
					logRenderLagSpikes = b;
					Timekeeper.configOrPipelineReload();
				})
				.build());

		debug.addEntry(ENTRY_BUILDER
				.startIntSlider(new TranslatableComponent("config.canvas.value.render_lag_spike_fps"), renderLagSpikeFps, 30, 120)
				.setDefaultValue(DEFAULTS.renderLagSpikeFps)
				.setTooltip(parse("config.canvas.help.render_lag_spike_fps"))
				.setSaveConsumer(b -> renderLagSpikeFps = b)
				.build());

		debug.addEntry(ENTRY_BUILDER
			.startBooleanToggle(new TranslatableComponent("config.canvas.value.display_render_profiler"), displayRenderProfiler)
			.setDefaultValue(DEFAULTS.displayRenderProfiler)
			.setTooltip(parse("config.canvas.help.display_render_profiler"))
			.setSaveConsumer(b -> {
				displayRenderProfiler = b;
				Timekeeper.configOrPipelineReload();
			})
			.build());

		debug.addEntry(ENTRY_BUILDER
			.startEnumSelector(new TranslatableComponent("config.canvas.value.profiler_display_mode"),
			Timekeeper.Mode.class,
			profilerDisplayMode)
			.setDefaultValue(DEFAULTS.profilerDisplayMode)
			.setTooltip(parse("config.canvas.help.profiler_display_mode"))
			.setSaveConsumer(b -> {
				profilerDisplayMode = b;
				Timekeeper.configOrPipelineReload();
			})
			.build());

		debug.addEntry(ENTRY_BUILDER
			.startIntSlider(new TranslatableComponent("config.canvas.value.profiler_detail_level"), profilerDetailLevel, 0, 2)
			.setDefaultValue(DEFAULTS.profilerDetailLevel)
			.setTooltip(parse("config.canvas.help.profiler_detail_level"))
			.setSaveConsumer(b -> profilerDetailLevel = b)
			.build());

		debug.addEntry(ENTRY_BUILDER
			.startFloatField(new TranslatableComponent("config.canvas.value.profiler_overlay_scale"), profilerOverlayScale)
			.setDefaultValue(DEFAULTS.profilerOverlayScale)
			.setTooltip(parse("config.canvas.help.profiler_overlay_scale"))
			.setSaveConsumer(b -> profilerOverlayScale = b)
			.build());

		debug.addEntry(ENTRY_BUILDER
			.startBooleanToggle(new TranslatableComponent("config.canvas.value.debug_sprite_atlas"), debugSpriteAtlas)
			.setDefaultValue(DEFAULTS.debugSpriteAtlas)
			.setTooltip(parse("config.canvas.help.debug_sprite_atlas"))
			.setSaveConsumer(b -> debugSpriteAtlas = b)
			.build());

		debug.addEntry(ENTRY_BUILDER
				.startBooleanToggle(new TranslatableComponent("config.canvas.value.trace_texture_load"), traceTextureLoad)
				.setDefaultValue(DEFAULTS.traceTextureLoad)
				.setTooltip(parse("config.canvas.help.trace_texture_load"))
				.setSaveConsumer(b -> traceTextureLoad = b)
				.build());

		builder.setAlwaysShowTabs(false).setDoesConfirmSave(false);

		final Screen result = builder.build();
		configScreen = new WeakReference<>(result);
		return result;
	}
}
