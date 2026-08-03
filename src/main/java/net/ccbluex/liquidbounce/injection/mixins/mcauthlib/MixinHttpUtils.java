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

import net.ccbluex.liquidbounce.authlib.utils.HttpUtils;
import net.ccbluex.liquidbounce.utils.client.OkHttpCompat;
import okhttp3.Headers;
import okhttp3.MediaType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Builds the bundled authlib's static http constants without okhttp's Kotlin factories.
 *
 * <p>The class initialiser reads {@code Headers.Companion} and {@code MediaType.Companion}, which an
 * okhttp older than 4.0 does not declare, so it throws before authlib can serve a single request.
 * Cancel it and assign the same values through members every version declares.
 */
@Pseudo
@Mixin(targets = "net.ccbluex.liquidbounce.authlib.utils.HttpUtils", remap = false)
public abstract class MixinHttpUtils {

    @Shadow
    @Final
    @Mutable
    private static HttpUtils INSTANCE;

    @Shadow
    @Final
    @Mutable
    private static Headers HEADERS_JSON;

    @Shadow
    @Final
    @Mutable
    private static Headers HEADERS_FORM;

    @Shadow
    @Final
    @Mutable
    private static Headers HEADERS_JSON_RESPONSE;

    @Shadow
    @Final
    @Mutable
    private static MediaType MEDIA_TYPE_JSON;

    @Invoker("<init>")
    private static HttpUtils liquid_bounce$create() {
        throw new AssertionError();
    }

    @Inject(method = "<clinit>", at = @At("HEAD"), cancellable = true)
    private static void liquid_bounce$initWithoutCompanions(CallbackInfo ci) {
        INSTANCE = liquid_bounce$create();
        HEADERS_JSON = new Headers.Builder()
            .add("Content-Type", "application/json")
            .build();
        HEADERS_FORM = new Headers.Builder()
            .add("Content-Type", "application/x-www-form-urlencoded")
            .build();
        HEADERS_JSON_RESPONSE = new Headers.Builder()
            .add("Accept", "application/json")
            .build();
        MEDIA_TYPE_JSON = OkHttpCompat.mediaType("application/json; charset=utf-8");
        ci.cancel();
    }

    /**
     * The empty headers the argument defaults read. {@code Headers.EMPTY} only exists from okhttp 4.0.
     */
    @Redirect(
        method = {
            "get$default(Lnet/ccbluex/liquidbounce/authlib/utils/HttpUtils;Ljava/lang/String;"
                + "Lokhttp3/Headers;ILjava/lang/Object;)Lkotlin/Pair;",
            "post$default(Lnet/ccbluex/liquidbounce/authlib/utils/HttpUtils;Ljava/lang/String;"
                + "Ljava/lang/String;Lokhttp3/Headers;ILjava/lang/Object;)Lkotlin/Pair;",
            "post$default(Lnet/ccbluex/liquidbounce/authlib/utils/HttpUtils;Ljava/lang/String;"
                + "Lokhttp3/RequestBody;Lokhttp3/Headers;ILjava/lang/Object;)Lkotlin/Pair;",
        },
        at = @At(value = "FIELD", target = "Lokhttp3/Headers;EMPTY:Lokhttp3/Headers;", opcode = Opcodes.GETSTATIC)
    )
    private static Headers liquid_bounce$emptyHeaders() {
        return new Headers.Builder().build();
    }
}
