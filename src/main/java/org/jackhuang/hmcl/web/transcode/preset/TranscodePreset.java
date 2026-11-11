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
package org.jackhuang.hmcl.web.transcode.preset;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

/// One transcoding preset, as stored in `<data>/transcode/presets/<id>.json`.
///
/// The field names are the public JSON contract; the frontend's generic form and the
/// command builder read the same object. New fields must keep a sane default so an old
/// preset file still loads (Gson leaves missing fields at the initializer).
///
/// This is deliberately a small, typed core rather than a copy of 3FUI's WinForms
/// fields: the panel owns its own schema, and the broad parameter panel is built on
/// top of [PresetSchema].
@NotNullByDefault
public final class TranscodePreset {

    public String name = "";
    public String description = "";

    // ---- output -----------------------------------------------------------
    public String container = "mkv";
    /// `suffix`, `timestamp` or `none`.
    public String outputNaming = "suffix";
    public String outputSuffix = "_transcoded";
    /// Empty means "beside the input"; otherwise a directory under the media root.
    public String outputDir = "";

    // ---- video ------------------------------------------------------------
    /// `copy`, `none` or an ffmpeg encoder name.
    public String videoEncoder = "libx264";
    /// `crf`, `cqp` or `bitrate`.
    public String qualityMode = "crf";
    public String qualityValue = "20";
    public String preset = "medium";
    public String profile = "";
    public String level = "";
    public String tune = "";
    public String pixelFormat = "";
    public boolean tenBit = false;
    public String gop = "";
    public String threads = "";
    public String videoMap = "0:v:0?";

    // ---- frame / colour ---------------------------------------------------
    public String width = "";
    public String height = "";
    public String fps = "";
    public String crop = "";
    /// `90`, `180`, `270`; empty means no rotation.
    public String rotate = "";
    /// `yadif` or empty.
    public String deinterlace = "";
    public String colorPrimaries = "";
    public String colorTransfer = "";
    public String colorMatrix = "";
    public String colorRange = "";

    // ---- audio ------------------------------------------------------------
    /// `copy`, `encode` or `none`.
    public String audioMode = "copy";
    public String audioEncoder = "aac";
    public String audioBitrate = "192k";
    public String audioChannels = "";
    public String audioSampleRate = "";
    public String audioMap = "0:a?";

    // ---- subtitle ---------------------------------------------------------
    /// `copy`, `burn` or `none`.
    public String subtitleMode = "copy";
    public String subtitleFile = "";
    public String subtitleFont = "";
    public String subtitleFontSize = "";
    /// Extra `force_style` entries, e.g. `Bold=1,Outline=1`.
    public String subtitleStyle = "";
    public String subtitleMap = "0:s?";

    // ---- metadata / chapters / attachments -------------------------------
    public String metadataTitle = "";
    public String metadataArtist = "";
    public String metadataAlbum = "";
    public String metadataComment = "";
    public String metadataDate = "";
    public String metadataGenre = "";
    public boolean copyAttachments = true;
    /// Relative to the media root; empty means "use the input's chapters".
    public String chaptersFile = "";

    // ---- trim / video enhancement / audio filters -------------------------
    public String trimStart = "";
    public String trimEnd = "";
    public String denoise = "";
    public String denoiseParams = "";
    public String sharpen = "";
    public String sharpenParams = "";
    public boolean deband = false;
    public String debandParams = "";
    public String eqBrightness = "";
    public String eqContrast = "";
    public String eqSaturation = "";
    public String eqGamma = "";
    public String audioVolume = "";
    public boolean loudnormEnabled = false;
    public String loudnormI = "-16";
    public String loudnormTP = "-1.5";
    public String loudnormLRA = "11";
    public String metadataExtra = "";

    // ---- filters / custom args -------------------------------------------
    public String filterGraph = "";
    public boolean copyMetadata = true;
    public boolean copyChapters = true;
    public List<String> inputArgs = new ArrayList<>();
    public List<String> videoArgs = new ArrayList<>();
    public List<String> audioArgs = new ArrayList<>();
    public List<String> subtitleArgs = new ArrayList<>();
    public List<String> outputArgs = new ArrayList<>();

    /// A deep copy, so editing a preset never mutates the built-in instance.
    public TranscodePreset copy() {
        String json = org.jackhuang.hmcl.web.http.Json.GSON.toJson(this);
        return org.jackhuang.hmcl.web.http.Json.GSON.fromJson(json, TranscodePreset.class);
    }

    /// Rejects values the command builder cannot honour. The message is shown to the
    /// user, so it names the field rather than dumping the object.
    public void validate() {
        require(container, "container");
        require(videoEncoder, "videoEncoder");
        require(qualityMode, "qualityMode");
        require(qualityValue, "qualityValue");
        if (!List.of("copy", "none").contains(videoEncoder) && !qualityMode.equals("bitrate")) {
            // CRF/CQP are encoder-specific; an unknown value is still passed through and
            // ffmpeg will explain. Only the shape is checked here.
        }
        if (!List.of("copy", "encode", "none").contains(audioMode)) {
            throw new IllegalArgumentException("audioMode must be copy, encode or none");
        }
        if (!List.of("copy", "burn", "none").contains(subtitleMode)) {
            throw new IllegalArgumentException("subtitleMode must be copy, burn or none");
        }
        if (!List.of("suffix", "timestamp", "none").contains(outputNaming)) {
            throw new IllegalArgumentException("outputNaming must be suffix, timestamp or none");
        }
        if (tenBit && pixelFormat.isBlank()) {
            pixelFormat = "yuv420p10le";
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}