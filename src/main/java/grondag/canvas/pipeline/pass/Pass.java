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

package grondag.canvas.pipeline.pass;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import grondag.canvas.pipeline.Pipeline;
import grondag.canvas.pipeline.PipelineFramebuffer;
import grondag.canvas.pipeline.config.PassConfig;

public abstract class Pass {
	final PassConfig config;
	PipelineFramebuffer fbo;

	Pass(PassConfig config) {
		this.config = config;
		fbo = Pipeline.getFramebuffer(config.framebuffer.name);
	}

	public String getName() {
		return this.config.name;
	}

	public final boolean isEnabled() {
		return !config.toggleConfig.isValid() || config.toggleConfig.value().value();
	}

	public abstract void run(int width, int height);

	public abstract void close();

	public static Pass create(PassConfig config) {
		if (config.program.name.equals(PassConfig.CLEAR_NAME)) {
			return new ClearPass(config);
		} else {
			return new ProgramPass(config);
		}
	}

	static Pass[] create(String logName, PassConfig[] configs) {
		if (configs == null || configs.length == 0) {
			return new Pass[0];
		}

		final ObjectArrayList<Pass> passes = new ObjectArrayList<>();

		for (final PassConfig c : configs) {
			final Pass p = create(c);
			passes.add(p);
		}

		return passes.toArray(new Pass[passes.size()]);
	}
}
