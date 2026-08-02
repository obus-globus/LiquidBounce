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

package net.ccbluex.liquidbounce.utils.client;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okio.Okio;
import okio.Sink;
import org.jspecify.annotations.NullMarked;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

/**
 * Factories for okhttp types that resolve on hosts bundling an okhttp older than ours.
 *
 * <p>okhttp moved its static factories onto Kotlin {@code Companion} objects in 4.0. Kotlin call
 * sites therefore read a {@code Companion} field, which a pre-4.0 okhttp does not declare, and the
 * call fails with {@link NoSuchFieldError}. The same factories are also emitted as static methods on
 * the class itself, and those have existed since 3.x, so calling them from Java binds to a member
 * both versions declare.
 */
@NullMarked
public final class OkHttpCompat {

    private OkHttpCompat() {
    }

    /**
     * The media type {@code value} describes.
     *
     * @throws IllegalArgumentException if {@code value} is not a media type
     */
    public static MediaType mediaType(String value) {
        return MediaType.get(value);
    }

    /**
     * The URL {@code value} describes.
     *
     * @throws IllegalArgumentException if {@code value} is not an http or https URL
     */
    public static HttpUrl httpUrl(String value) {
        return HttpUrl.get(value);
    }

    /** A request body sending {@code content} as {@code contentType}. */
    public static RequestBody requestBody(MediaType contentType, String content) {
        return RequestBody.create(contentType, content);
    }

    /** A request body sending {@code file} as {@code contentType}. */
    public static RequestBody requestBody(MediaType contentType, File file) {
        return RequestBody.create(contentType, file);
    }

    /** A multipart part carrying {@code body} as the named form field. */
    public static MultipartBody.Part formDataPart(String name, String filename, RequestBody body) {
        return MultipartBody.Part.createFormData(name, filename, body);
    }

    /**
     * A sink writing to {@code file}.
     *
     * <p>Goes through the stream overload because okio only grew {@code sink(File, boolean)} after
     * the version some hosts bundle, which offers {@code sink(File)} instead.
     */
    public static Sink sink(File file) throws FileNotFoundException {
        return Okio.sink(new FileOutputStream(file));
    }
}
