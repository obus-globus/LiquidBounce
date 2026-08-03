/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

package net.ccbluex.liquidbounce.injection.mixins.mcauthlib;

import okio.Buffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Copies the buffer the bundled authlib writes a json body from, through a method every okio
 * declares. {@code Buffer.copy} only exists from okio 2.0, while {@code clone} exists in both.
 */
@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.authlib.utils.HttpUtilsKt$asRequestBody$1", remap = false)
public abstract class MixinAuthlibRequestBody {

    @Redirect(
        method = "writeTo(Lokio/BufferedSink;)V",
        at = @At(value = "INVOKE", target = "Lokio/Buffer;copy()Lokio/Buffer;")
    )
    private Buffer liquid_bounce$copyBuffer(Buffer buffer) {
        return buffer.clone();
    }
}
