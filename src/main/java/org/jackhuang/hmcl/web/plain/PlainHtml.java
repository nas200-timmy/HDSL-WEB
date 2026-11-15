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
package org.jackhuang.hmcl.web.plain;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/// The markup helpers behind the no-JavaScript surface.
///
/// Everything here is plain HTML 4-era markup: tables, forms, `<pre>`, links.
/// No script, no flex, no custom elements, so a text browser (w3m, lynx) and an
/// old graphical browser render the same page. The only visual sugar is a small
/// inline stylesheet, which those browsers ignore.
@NotNullByDefault
final class PlainHtml {

    /// `MM-dd HH:mm` in the server's timezone; text browsers do not localise.
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);

    private static final String STYLE = """
            body{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;max-width:60rem;
                 margin:0 auto;padding:1rem;line-height:1.5;color:#111}
            a{color:#0b57d0}
            table{border-collapse:collapse;width:100%}
            th,td{border-bottom:1px solid #ddd;padding:.35rem .5rem;text-align:left;
                  vertical-align:top}
            pre.msg{white-space:pre-wrap;word-wrap:break-word;background:#f6f6f6;
                    border:1px solid #ddd;padding:.75rem;margin:.25rem 0 .75rem}
            textarea{width:100%;min-height:6rem;font:inherit}
            .muted{color:#666}
            .error{color:#a00;font-weight:600}
            .row{display:block;margin:.5rem 0}
            nav a{margin-right:1rem}
            """;

    private PlainHtml() {
    }

    /// Escapes text for element content and attribute values alike.
    ///
    /// Escapes the five characters that matter in both contexts, so a caller
    /// never has to decide which one it is in.
    ///
    /// @param text the text, or `null`
    /// @return the escaped text, empty when `null`
    static String escape(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }

    /// Formats a stored millisecond timestamp for the message list.
    ///
    /// @param millis epoch milliseconds
    /// @return `MM-dd HH:mm`
    static String time(long millis) {
        return TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    /// A whole page.
    ///
    /// @param title     the document title, escaped here
    /// @param headExtra extra `<head>` markup (a meta refresh), or `null`
    /// @param body      already-rendered body markup
    /// @return the document
    static String page(String title, @Nullable String headExtra, String body) {
        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + escape(title) + "</title>\n"
                + (headExtra == null ? "" : headExtra + "\n")
                + "<style>" + STYLE + "</style>\n"
                + "</head><body>\n"
                + body
                + "\n</body></html>\n";
    }
}