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

package grondag.canvas.pipeline.config;

import java.io.IOException;

import blue.endless.jankson.JsonArray;
import blue.endless.jankson.JsonObject;
import blue.endless.jankson.api.SyntaxError;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import grondag.canvas.CanvasMod;
import grondag.canvas.config.ConfigManager;
import grondag.canvas.pipeline.config.option.OptionConfig;
import grondag.canvas.pipeline.config.util.AbstractConfig;
import grondag.canvas.pipeline.config.util.ConfigContext;
import grondag.canvas.pipeline.config.util.JanksonHelper;
import grondag.canvas.pipeline.config.util.LoadHelper;
import grondag.canvas.pipeline.config.util.NamedDependency;

public class PipelineConfigBuilder {
	public final ConfigContext context = new ConfigContext();
	public final ObjectArrayList<ImageConfig> images = new ObjectArrayList<>();
	public final ObjectArrayList<ProgramConfig> programs = new ObjectArrayList<>();
	public final ObjectArrayList<FramebufferConfig> framebuffers = new ObjectArrayList<>();
	public final ObjectArrayList<OptionConfig> options = new ObjectArrayList<>();

	public final ObjectArrayList<PassConfig> onWorldStart = new ObjectArrayList<>();
	public final ObjectArrayList<PassConfig> afterRenderHand = new ObjectArrayList<>();
	public final ObjectArrayList<PassConfig> fabulous = new ObjectArrayList<>();

	@Nullable public FabulousConfig fabulosity;
	@Nullable public DrawTargetsConfig drawTargets;
	@Nullable public SkyShadowConfig skyShadow;
	@Nullable public SkyConfig sky;

	public boolean smoothBrightnessBidirectionaly = false;
	public int brightnessSmoothingFrames = 20;
	public int rainSmoothingFrames = 500;
	public boolean runVanillaClear = true;
	public int glslVersion = 330;
	public boolean enablePBR = false;

	public NamedDependency<FramebufferConfig> defaultFramebuffer;

	public MaterialProgramConfig materialProgram;

	public void load(JsonObject configJson) {
		smoothBrightnessBidirectionaly = configJson.getBoolean("smoothBrightnessBidirectionaly", smoothBrightnessBidirectionaly);
		runVanillaClear = configJson.getBoolean("runVanillaClear", runVanillaClear);
		brightnessSmoothingFrames = configJson.getInt("brightnessSmoothingFrames", brightnessSmoothingFrames);
		rainSmoothingFrames = configJson.getInt("rainSmoothingFrames", rainSmoothingFrames);
		glslVersion = configJson.getInt("glslVersion", glslVersion);
		enablePBR = configJson.getBoolean("enablePBR", enablePBR);

		if (configJson.containsKey("materialProgram")) {
			if (materialProgram == null) {
				materialProgram = LoadHelper.loadObject(context, configJson, "materialProgram", MaterialProgramConfig::new);
			} else {
				CanvasMod.LOG.warn("Invalid pipeline config - duplicate 'materialProgram' ignored.");
			}
		}

		if (configJson.containsKey("defaultFramebuffer")) {
			if (defaultFramebuffer == null) {
				defaultFramebuffer = context.frameBuffers.dependOn(configJson, "defaultFramebuffer");
			} else {
				CanvasMod.LOG.warn("Invalid pipeline config - duplicate 'defaultFramebuffer' ignored.");
			}
		}

		if (configJson.containsKey("fabulousTargets")) {
			if (fabulosity == null) {
				fabulosity = LoadHelper.loadObject(context, configJson, "fabulousTargets", FabulousConfig::new);
			} else {
				CanvasMod.LOG.warn("Invalid pipeline config - duplicate 'fabulousTargets' ignored.");
			}
		}

		if (configJson.containsKey("skyShadows")) {
			if (skyShadow == null) {
				skyShadow = LoadHelper.loadObject(context, configJson, "skyShadows", SkyShadowConfig::new);
			} else {
				CanvasMod.LOG.warn("Invalid pipeline config - duplicate 'skyShadows' ignored.");
			}
		}

		if (configJson.containsKey("sky")) {
			if (sky == null) {
				sky = LoadHelper.loadObject(context, configJson, "sky", SkyConfig::new);
			} else {
				CanvasMod.LOG.warn("Invalid pipeline config - duplicate 'sky' ignored.");
			}
		}

		if (configJson.containsKey("materialVertexShader")) {
			CanvasMod.LOG.warn("Invalid pipeline config - obsolete 'materialVertexShader' attribute found - use 'materialProgram' instead.");
		}

		if (configJson.containsKey("materialFragmentShader")) {
			CanvasMod.LOG.warn("Invalid pipeline config - obsolete 'materialFragmentShader' attribute found - use 'materialProgram' instead.");
		}

		if (configJson.containsKey("drawTargets")) {
			if (drawTargets == null) {
				drawTargets = LoadHelper.loadObject(context, configJson, "drawTargets", DrawTargetsConfig::new);
			} else {
				CanvasMod.LOG.warn("Invalid pipeline config - duplicate 'drawTargets' ignored.");
			}
		}

		LoadHelper.loadSubList(context, configJson, "fabulous", "passes", fabulous, PassConfig::new);
		LoadHelper.loadSubList(context, configJson, "beforeWorldRender", "passes", onWorldStart, PassConfig::new);
		LoadHelper.loadSubList(context, configJson, "afterRenderHand", "passes", afterRenderHand, PassConfig::new);

		LoadHelper.loadList(context, configJson, "images", images, ImageConfig::new);
		LoadHelper.loadList(context, configJson, "programs", programs, ProgramConfig::new);
		LoadHelper.loadList(context, configJson, "framebuffers", framebuffers, FramebufferConfig::new);
		LoadHelper.loadList(context, configJson, "options", options, OptionConfig::new);
	}

	public boolean validate() {
		boolean valid = true;

		valid &= AbstractConfig.assertAndWarn(drawTargets != null && drawTargets.validate(), "Invalid pipeline config - missing or invalid drawTargets config.");

		valid &= AbstractConfig.assertAndWarn(materialProgram != null && materialProgram.validate(), "Invalid pipeline config - missing or invalid materialProgram.");

		valid &= (fabulosity == null || fabulosity.validate());
		valid &= (skyShadow == null || skyShadow.validate());

		valid &= defaultFramebuffer != null && defaultFramebuffer.validate("Invalid pipeline config - missing or invalid defaultFramebuffer.");

		for (final FramebufferConfig fb : framebuffers) {
			valid &= fb.validate();
		}

		for (final ImageConfig img : images) {
			valid &= img.validate();
		}

		for (final ProgramConfig prog : programs) {
			valid &= prog.validate();
		}

		for (final OptionConfig opt : options) {
			valid &= opt.validate();
		}

		return valid;
	}

	private static @Nullable PipelineConfigBuilder load(ResourceLocation id) {
		final ResourceManager rm = Minecraft.getInstance().getResourceManager();

		if (!PipelineLoader.areResourcesAvailable() || rm == null) {
			return null;
		}

		final PipelineConfigBuilder result = new PipelineConfigBuilder();
		final ObjectOpenHashSet<ResourceLocation> included = new ObjectOpenHashSet<>();
		final ObjectArrayFIFOQueue<ResourceLocation> queue = new ObjectArrayFIFOQueue<>();

		queue.enqueue(id);
		included.add(id);

		while (!queue.isEmpty()) {
			ResourceLocation target = queue.dequeue();

			// Allow flexibility on JSON vs JSON5 extensions
			if (!rm.hasResource(target)) {
				if (target.getPath().endsWith("json5")) {
					final var candidate = new ResourceLocation(target.getNamespace(), target.getPath().substring(0, target.getPath().length() - 1));

					if (rm.hasResource(candidate)) {
						target = candidate;
					}
				} else if (target.getPath().endsWith("json")) {
					final var candidate = new ResourceLocation(target.getNamespace(), target.getPath() + "5");

					if (rm.hasResource(candidate)) {
						target = candidate;
					}
				}
			}

			try (Resource res = rm.getResource(target)) {
				final JsonObject configJson = ConfigManager.JANKSON.load(res.getInputStream());
				result.load(configJson);
				getIncludes(configJson, included, queue);
			} catch (final IOException e) {
				CanvasMod.LOG.warn(String.format("Unable to load pipeline config resource %s due to IOException: %s", target.toString(), e.getLocalizedMessage()));
			} catch (final SyntaxError e) {
				CanvasMod.LOG.warn(String.format("Unable to load pipeline config resource %s due to Syntax Error: %s", target.toString(), e.getLocalizedMessage()));
			}
		}

		if (result.validate()) {
			return result;
		} else {
			// fallback to minimal renderable pipeline if not valid
			return null;
		}
	}

	private static void getIncludes(JsonObject configJson, ObjectOpenHashSet<ResourceLocation> included, ObjectArrayFIFOQueue<ResourceLocation> queue) {
		if (configJson == null || !configJson.containsKey("include")) {
			return;
		}

		final JsonArray array = JanksonHelper.getJsonArrayOrNull(configJson, "include", "Pipeline config error: 'include' must be an array.");
		final int limit = array.size();

		for (int i = 0; i < limit; ++i) {
			final String idString = JanksonHelper.asString(array.get(i));

			if (idString != null && !idString.isEmpty()) {
				final ResourceLocation id = new ResourceLocation(idString);

				if (included.add(id)) {
					queue.enqueue(id);
				}
			}
		}
	}

	public static PipelineConfig build(ResourceLocation identifier) {
		final PipelineConfigBuilder builder = load(identifier);
		return builder == null ? PipelineConfig.minimalConfig() : new PipelineConfig(builder);
	}
}
