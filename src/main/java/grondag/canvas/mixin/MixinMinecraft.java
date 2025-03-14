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

package grondag.canvas.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.pipeline.MainTarget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;

import grondag.canvas.config.Configurator;
import grondag.canvas.render.PrimaryFrameBuffer;
import grondag.canvas.render.world.CanvasWorldRenderer;
import grondag.canvas.varia.CanvasGlHelper;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft extends ReentrantBlockableEventLoop<Runnable> {
	protected MixinMinecraft(String dummy) {
		super(dummy);
	}

	@Inject(at = @At("RETURN"), method = "<init>*")
	private void hookInit(CallbackInfo info) {
		CanvasGlHelper.init();
	}

	@Redirect(at = @At(value = "INVOKE", target = "Ljava/lang/Thread;yield()V"), method = "runTick", require = 1, allow = 1)
	private void onYield() {
		if (!Configurator.greedyRenderThread) {
			Thread.yield();
		}
	}

	@Redirect(method = "<init>*", at = @At(value = "NEW", target = "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/renderer/RenderBuffers;)Lnet/minecraft/client/renderer/LevelRenderer;"))
	private LevelRenderer onWorldRendererNew(Minecraft client, RenderBuffers bufferBuilders) {
		return new CanvasWorldRenderer(client, bufferBuilders);
	}

	@Redirect(method = "<init>*", at = @At(value = "NEW", target = "(II)Lcom/mojang/blaze3d/pipeline/MainTarget;"))
	private MainTarget onFrameBufferNew(int width, int height) {
		return new PrimaryFrameBuffer(width, height);
	}
}
