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

package grondag.canvas.shader;

import net.minecraft.resources.ResourceLocation;

public interface Shader {
	/**
	 * Forces the shader to be reloaded the next time it is attached.
	 */
	void forceReload();

	/**
	 * Binds this shader.
	 *
	 * @param program The program object to which this shader object will be attached
	 * @return Was this successful
	 */
	boolean attach(int program);

	/**
	 * @param type Uniform type
	 * @param name Uniform name
	 * @return Does this shader contain the provided uniform
	 */
	boolean containsUniformSpec(String type, String name);

	/**
	 * @return The shader source location, typically for debugging
	 */
	ResourceLocation getShaderSourceId();
}
