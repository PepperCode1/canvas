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

package grondag.canvas.pipeline;

import net.minecraft.client.Minecraft;

import grondag.canvas.CanvasMod;
import grondag.canvas.pipeline.config.AttachmentConfig;
import grondag.canvas.pipeline.config.FramebufferConfig;
import grondag.canvas.varia.GFX;

// FEAT: handle clear masks
public class PipelineFramebuffer {
	final FramebufferConfig config;
	final float[][] clearColor;
	final int[] attachmentPoints;
	final int colorClearFlags;
	final int clearMask;

	private int fboGlId = -1;

	private static final int R = 0;
	private static final int G = 1;
	private static final int B = 2;
	private static final int A = 3;

	PipelineFramebuffer(FramebufferConfig config, int width, int height) {
		this.config = config;

		final int count = config.colorAttachments.length;

		clearColor = new float[count][4];
		attachmentPoints = new int[count];
		int attachmentPoint = GFX.GL_COLOR_ATTACHMENT0;
		int clearFlags = 0;

		for (int i = 0; i < count; ++i) {
			if (config.colorAttachments[i].clear) {
				clearFlags |= (1 << i);
				final int color = config.colorAttachments[i].clearColor;
				clearColor[i][A] = ((color >> 24) & 0xFF) / 255f;
				clearColor[i][R] = ((color >> 16) & 0xFF) / 255f;
				clearColor[i][G] = ((color >> 8) & 0xFF) / 255f;
				clearColor[i][B] = (color & 0xFF) / 255f;
			}

			attachmentPoints[i] = attachmentPoint++;
		}

		colorClearFlags = clearFlags;

		clearMask = (colorClearFlags != 0) ? 1 : 0;

		open(width, height);
	}

	public int glId() {
		return fboGlId;
	}

	void open(int width, int height) {
		fboGlId = GFX.genFramebuffer();

		GFX.bindFramebuffer(GFX.GL_FRAMEBUFFER, fboGlId);
		GFX.objectLabel(GFX.GL_FRAMEBUFFER, fboGlId, "FBO " + config.name);

		if (config.colorAttachments.length == 0) {
			GFX.glDrawBuffer(GFX.GL_NONE);
			GFX.glReadBuffer(GFX.GL_NONE);
		} else {
			GFX.glDrawBuffers(attachmentPoints);
		}

		// FEAT: needs better handling of arrays, 3D and other target type
		// and attachments need a way to specify level

		for (int i = 0; i < config.colorAttachments.length; ++i) {
			final AttachmentConfig ac = config.colorAttachments[i];
			final Image img = Pipeline.getImage(ac.image.name);

			if (img == null) {
				CanvasMod.LOG.warn(String.format("Framebuffer %s cannot be completely configured because color attachment %s was not found",
						config.name, ac.image.name));
			} else if (img.config.target == GFX.GL_TEXTURE_2D) {
				GFX.glFramebufferTexture2D(GFX.GL_FRAMEBUFFER, GFX.GL_COLOR_ATTACHMENT0 + i, img.config.target, img.glId(), ac.lod);
			} else if (img.config.target == GFX.GL_TEXTURE_2D_ARRAY || img.config.target == GFX.GL_TEXTURE_3D) {
				GFX.glFramebufferTextureLayer(GFX.GL_FRAMEBUFFER, GFX.GL_COLOR_ATTACHMENT0 + i, img.glId(), ac.lod, 0);
			}
		}

		if (config.depthAttachment != null) {
			final Image img = Pipeline.getImage(config.depthAttachment.image.name);

			if (img == null) {
				CanvasMod.LOG.warn(String.format("Framebuffer %s cannot be completely configured because depth attachment %s was not found",
						config.name, config.depthAttachment.image.name));
			} else if (img.config.target == GFX.GL_TEXTURE_2D) {
				GFX.glFramebufferTexture2D(GFX.GL_FRAMEBUFFER, GFX.GL_DEPTH_ATTACHMENT, img.config.target, img.glId(), 0);
			} else if (img.config.target == GFX.GL_TEXTURE_2D_ARRAY || img.config.target == GFX.GL_TEXTURE_3D) {
				GFX.glFramebufferTextureLayer(GFX.GL_FRAMEBUFFER, GFX.GL_DEPTH_ATTACHMENT, img.glId(), 0, 0);
			}
		}

		final int check = GFX.checkFramebufferStatus(GFX.GL_FRAMEBUFFER);

		if (check != GFX.GL_FRAMEBUFFER_COMPLETE) {
			CanvasMod.LOG.warn("Framebuffer " + config.name + " has invalid status " + check + " " + GlSymbolLookup.reverseLookup(check));
		}
	}

	public void clear() {
		GFX.bindFramebuffer(GFX.GL_FRAMEBUFFER, fboGlId);
		GFX.colorMask(true, true, true, true);
		GFX.depthMask(true);

		if (colorClearFlags == 1) {
			// Try for combined depth/color clear if have single color
			int mask = GFX.GL_COLOR_BUFFER_BIT;

			if (config.depthAttachment != null && config.depthAttachment.clear) {
				mask |= GFX.GL_DEPTH_BUFFER_BIT;
				GFX.clearDepth(config.depthAttachment.clearDepth);
			}

			GFX.clearColor(clearColor[0][R], clearColor[0][G], clearColor[0][B], clearColor[0][A]);
			GFX.clear(mask, Minecraft.ON_OSX);
		} else {
			// Clears happen separately in other cases
			if (config.depthAttachment != null && config.depthAttachment.clear) {
				GFX.clearDepth(config.depthAttachment.clearDepth);
				GFX.clear(GFX.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
			}

			if (colorClearFlags != 0) {
				final int count = clearColor.length;

				for (int i = 0; i < count; ++i) {
					if ((colorClearFlags & (1 << i)) != 0) {
						GFX.glDrawBuffer(GFX.GL_COLOR_ATTACHMENT0 + i);
						GFX.clearColor(clearColor[i][R], clearColor[i][G], clearColor[i][B], clearColor[i][A]);
						GFX.clear(GFX.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
					}
				}

				GFX.glDrawBuffers(attachmentPoints);
			}
		}
	}

	public void bind() {
		GFX.glBindFramebuffer(GFX.GL_FRAMEBUFFER, fboGlId);
	}

	void close() {
		if (fboGlId != -1) {
			GFX.deleteFramebuffer(fboGlId);
			fboGlId = -1;
		}
	}

	public void copyDepthFrom(PipelineFramebuffer source) {
		final Image srcImg = Pipeline.getImage(source.config.depthAttachment.image.name);
		final Image myImg = Pipeline.getImage(config.depthAttachment.image.name);
		GFX.bindFramebuffer(GFX.GL_READ_FRAMEBUFFER, source.fboGlId);
		GFX.bindFramebuffer(GFX.GL_DRAW_FRAMEBUFFER, fboGlId);
		GFX.blitFramebuffer(0, 0, srcImg.width, srcImg.height, 0, 0, myImg.width, myImg.height, GFX.GL_DEPTH_BUFFER_BIT, GFX.GL_NEAREST);
		GFX.bindFramebuffer(GFX.GL_FRAMEBUFFER, 0);
	}
}
