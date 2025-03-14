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

package grondag.canvas.shader.data;

/**
 * Governs how often shader uniform initializers are called.
 *
 * <p>In all cases, initializers will only be called if a shader using the uniform
 * is activated and values are only uploaded if they have changed.
 */
public enum UniformRefreshFrequency {
	/**
	 * Uniform initializer only called 1X a time of program load or reload.
	 */
	ON_LOAD,

	/**
	 * Uniform initializer called 1X per game tick. (20X per second)
	 */
	PER_TICK,

	/**
	 * Uniform initializer called 1X per render frame. (Variable frequency.)
	 */
	PER_FRAME
}
