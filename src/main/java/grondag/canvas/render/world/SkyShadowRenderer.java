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

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;

import grondag.canvas.buffer.util.DrawableStream;
import grondag.canvas.pipeline.Pipeline;
import grondag.canvas.pipeline.PipelineManager;
import grondag.canvas.shader.data.ShadowMatrixData;
import grondag.canvas.varia.GFX;

public class SkyShadowRenderer {
	private static boolean active = false;
	private static boolean renderEntityShadows = false;
	private static int cascade;

	private static void begin() {
		assert !active;
		active = true;
		final int size = Pipeline.skyShadowSize;
		RenderSystem.viewport(0, 0, size, size);
	}

	private static void end() {
		assert active;
		active = false;
		RenderSystem.viewport(0, 0, PipelineManager.width(), PipelineManager.height());
	}

	public static boolean isActive() {
		return active;
	}

	public static void render(CanvasWorldRenderer canvasWorldRenderer, DrawableStream entityBuffer, DrawableStream shadowExtrasBuffer) {
		if (Pipeline.shadowsEnabled()) {
			begin();

			for (cascade = 0; cascade < ShadowMatrixData.CASCADE_COUNT; ++cascade) {
				Pipeline.skyShadowFbo.bind();
				GFX.framebufferTextureLayer(GFX.GL_FRAMEBUFFER, GFX.GL_DEPTH_ATTACHMENT, Pipeline.shadowMapDepth, 0, cascade);
				renderInner(canvasWorldRenderer, entityBuffer, shadowExtrasBuffer);
			}

			Pipeline.defaultFbo.bind();

			end();
		}
	}

	private static void renderInner(CanvasWorldRenderer canvasWorldRenderer, DrawableStream entityBuffer, DrawableStream shadowExtrasBuffer) {
		Pipeline.skyShadowFbo.clear();

		canvasWorldRenderer.worldRenderState.renderShadowLayer(cascade);

		if (Pipeline.config().skyShadow.allowEntities && Minecraft.getInstance().options.entityShadows) {
			entityBuffer.draw(true);
			shadowExtrasBuffer.draw(true);
		}
	}

	/** Preserves entityShadows option state, overwriting it temporarily if needed to prevent vanilla from rendering shadows. */
	public static void suppressEntityShadows(Minecraft mc) {
		if (Pipeline.shadowsEnabled()) {
			renderEntityShadows = mc.options.entityShadows;
			mc.options.entityShadows = false;
		}
	}

	/** Restores entityShadows option state. */
	public static void restoreEntityShadows(Minecraft mc) {
		if (Pipeline.shadowsEnabled()) {
			mc.options.entityShadows = renderEntityShadows;
		}
	}

	public static int cascade() {
		return cascade;
	}
}
