/*
 * HDSL-web
 * Copyright (C) 2026  HDSL-web contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.web.crypto;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.jackhuang.hmcl.web.http.Json;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/// Encrypts the JSON body of `/api/chat/*` when the browser presents a chat
/// channel. Requests without the header pass through unchanged, which keeps
/// curl, the legacy plain surface and tests working.
///
/// The whole chat JSON is enveloped (not individual fields), so the browser
/// can decrypt once and keep the existing `api.chat.*` shapes. Multipart
/// attachment uploads are deliberately not handled here.
@NotNullByDefault
public final class ChatCryptoFilter implements Filter {

    public static final String HEADER = "X-HDSL-Chat";

    private final ChatCryptoService crypto;

    public ChatCryptoFilter(ChatCryptoService crypto) {
        this.crypto = crypto;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http) || !(response instanceof HttpServletResponse out)) {
            chain.doFilter(request, response);
            return;
        }
        String channelId = http.getHeader(HEADER);
        if (channelId == null || channelId.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        ChatCryptoService.Channel channel = crypto.channel(channelId);
        if (channel == null) {
            Json.error(out, HttpServletResponse.SC_UNAUTHORIZED, "chat_key_required");
            return;
        }

        HttpServletRequest requestToUse = http;
        String contentType = http.getContentType();
        if (contentType != null && contentType.contains("application/json")
                && !"GET".equalsIgnoreCase(http.getMethod())) {
            byte[] body = http.getInputStream().readAllBytes();
            if (body.length > 0) {
                try {
                    JsonObject envelope = JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    byte[] plain = crypto.decrypt(channel, true, envelope);
                    requestToUse = new CachedRequest(http, plain);
                } catch (RuntimeException e) {
                    Json.error(out, HttpServletResponse.SC_BAD_REQUEST, "encrypted chat body is invalid");
                    return;
                }
            }
        }

        BufferedResponse buffered = new BufferedResponse(out);
        chain.doFilter(requestToUse, buffered);
        byte[] body = buffered.body();
        if (body.length == 0 || buffered.getStatus() == HttpServletResponse.SC_NO_CONTENT
                || contentTypeOf(buffered) == null || !contentTypeOf(buffered).contains("application/json")) {
            buffered.copyTo(out);
            return;
        }
        JsonObject envelope = crypto.encrypt(channel, false, body);
        out.setStatus(buffered.getStatus());
        out.setContentType(Json.CONTENT_TYPE);
        out.setHeader("Content-Length", null);
        out.getWriter().write(Json.GSON.toJson(envelope));
    }

    private static String contentTypeOf(HttpServletResponse response) {
        return response.getContentType();
    }

    private static final class CachedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new jakarta.servlet.ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener readListener) {
                    // Buffered body; the servlet reads synchronously.
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    private static final class BufferedResponse extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final ServletOutputStream output = new ServletOutputStream() {
            @Override
            public void write(int b) {
                buffer.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                buffer.write(b, off, len);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
                // Synchronous buffer.
            }
        };
        private final PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(buffer, StandardCharsets.UTF_8), true);

        BufferedResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return output;
        }

        @Override
        public PrintWriter getWriter() {
            return writer;
        }

        @Override
        public void flushBuffer() {
            writer.flush();
        }

        @Override
        public void resetBuffer() {
            buffer.reset();
        }

        byte[] body() {
            writer.flush();
            return buffer.toByteArray();
        }

        void copyTo(HttpServletResponse response) throws IOException {
            byte[] bytes = body();
            response.setStatus(getStatus());
            if (getContentType() != null) {
                response.setContentType(getContentType());
            }
            response.setHeader("Content-Length", Integer.toString(bytes.length));
            response.getOutputStream().write(bytes);
        }
    }
}
