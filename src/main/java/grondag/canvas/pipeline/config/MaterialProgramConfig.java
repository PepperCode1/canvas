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

import blue.endless.jankson.JsonArray;
import blue.endless.jankson.JsonObject;

import grondag.canvas.CanvasMod;
import grondag.canvas.pipeline.config.util.ConfigContext;
import grondag.canvas.pipeline.config.util.NamedDependency;

public class MaterialProgramConfig extends ProgramConfig {
	public final NamedDependency<ImageConfig>[] samplerImages;

	@SuppressWarnings("unchecked")
	public MaterialProgramConfig(ConfigContext ctx, JsonObject config) {
		super(ctx, config, "materialProgram");

		if (!config.containsKey("samplerImages")) {
			samplerImages = new NamedDependency[0];
		} else {
			final JsonArray names = config.get(JsonArray.class, "samplerImages");
			final int limit = names.size();
			samplerImages = new NamedDependency[limit];

			for (int i = 0; i < limit; ++i) {
				samplerImages[i] = ctx.images.dependOn(names.get(i));
			}
		}
	}

	@SuppressWarnings("unchecked")
	public MaterialProgramConfig(ConfigContext ctx) {
		super(ctx, "materialProgram", "canvas:shaders/pipeline/standard.vert", "canvas:shaders/pipeline/standard.vert");
		samplerImages = new NamedDependency[0];
	}

	@Override
	public boolean validate() {
		if (samplerImages.length != samplerNames.length) {
			CanvasMod.LOG.warn(String.format("Material program is invalid because it expects %d samplers but the pass binds %d.",
				samplerNames.length, samplerImages.length));
			return false;
		}

		return super.validate();
	}
}
