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

import grondag.canvas.pipeline.config.PassConfig;
import grondag.canvas.varia.GFX;

public class ClearPass extends Pass {
	ClearPass(PassConfig config) {
		super(config);
	}

	@Override
	public void run(int width, int height) {
		if (fbo != null) {
			fbo.bind();
			GFX.viewport(0, 0, width, height);
			fbo.clear();
		}
	}

	@Override
	public void close() {
		// NOOP
	}
}
