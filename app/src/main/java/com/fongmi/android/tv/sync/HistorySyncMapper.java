package com.fongmi.android.tv.sync;

import android.text.TextUtils;

import androidx.media3.common.C;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.utils.Util;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Converts between webhtv's History entity and KVideo's VideoHistoryItem JSON shape
 * (lib/types/index.ts), so the two apps can read/write the same encrypted payload.
 * Matching key is showIdentifier = "title:" + lowercased/trimmed title, same rule
 * KVideo itself uses (History.findByName() already matches by vodName on webhtv's side).
 *
 * Episode numbering is not directly compatible: webhtv keeps a free-form label
 * (vodRemarks, e.g. "第08集"/"正片"/"HD") while KVideo keeps a 0-based numeric
 * episodeIndex into its own episodes[] list. Round-tripping therefore prefers
 * matching by episode *name* against the KVideo item's episodes[] (when supplied)
 * and only falls back to webhtv's own Util.getNumber() digit heuristic when no
 * name match is found.
 */
public class HistorySyncMapper {

    private HistorySyncMapper() {
    }

    public static JsonObject toKVideoItem(History history) {
        return toKVideoItem(history, Collections.emptyList());
    }

    /** episodeNames, when supplied (in playback order), lets the full episode list ride
     *  along so the other side (or a later pull back into webhtv) can match by episode
     *  name, not just index. Pass the current Flag's episode names, or empty if unknown. */
    public static JsonObject toKVideoItem(History history, List<String> episodeNames) {
        JsonObject item = new JsonObject();
        item.addProperty("videoId", history.getKey());
        item.addProperty("title", history.getVodName());
        item.addProperty("url", history.getEpisodeUrl());
        item.addProperty("episodeIndex", resolveEpisodeIndex(history.getVodRemarks(), episodeNames));
        item.addProperty("source", history.getSiteName());
        item.addProperty("timestamp", history.getUpdateTime() > 0 ? history.getUpdateTime() : history.getCreateTime());
        item.addProperty("playbackPosition", toSeconds(history.getPosition()));
        item.addProperty("duration", toSeconds(history.getDuration()));
        if (!TextUtils.isEmpty(history.getVodPic())) item.addProperty("poster", history.getVodPic());
        item.add("episodes", episodesArray(episodeNames));
        item.addProperty("showIdentifier", identifierFor(history.getVodName()));
        JsonObject sourceMap = new JsonObject();
        sourceMap.addProperty(history.getSiteKey(), history.getKey());
        item.add("sourceMap", sourceMap);
        return item;
    }

    public static History toHistoryUpdate(JsonObject kvideoItem, History existing) {
        History history = existing != null ? existing.copy() : new History();
        if (existing == null) history.setKey(requireVideoId(kvideoItem));
        history.setVodName(getString(kvideoItem, "title", history.getVodName()));
        String pic = getString(kvideoItem, "poster", null);
        if (!TextUtils.isEmpty(pic)) history.setVodPic(pic);
        history.setEpisodeUrl(getString(kvideoItem, "url", history.getEpisodeUrl()));
        history.setVodRemarks(resolveEpisodeLabel(kvideoItem, existing));
        history.setPosition(toMillis(getLong(kvideoItem, "playbackPosition", -1)));
        history.setDuration(toMillis(getLong(kvideoItem, "duration", -1)));
        return history;
    }

    public static String identifierFor(String title) {
        return "title:" + (title == null ? "" : title.toLowerCase(Locale.ROOT).trim());
    }

    /** Builds the {history:[...],favorites:[...]} plaintext body KVideo expects. */
    public static JsonObject buildPayload(List<History> histories, JsonArray existingFavorites) {
        JsonArray items = new JsonArray();
        for (History history : histories) items.add(toKVideoItem(history));
        JsonObject payload = new JsonObject();
        payload.add("history", items);
        payload.add("favorites", existingFavorites != null ? existingFavorites : new JsonArray());
        return payload;
    }

    public static List<JsonObject> readHistoryItems(JsonObject payload) {
        List<JsonObject> items = new ArrayList<>();
        JsonElement historyEl = payload.get("history");
        if (historyEl == null || !historyEl.isJsonArray()) return items;
        for (JsonElement element : historyEl.getAsJsonArray()) {
            if (element.isJsonObject()) items.add(element.getAsJsonObject());
        }
        return items;
    }

    private static JsonArray episodesArray(List<String> episodeNames) {
        JsonArray episodes = new JsonArray();
        for (String name : episodeNames) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", name);
            episodes.add(entry);
        }
        return episodes;
    }

    /** Prefers the position of a name match in episodeNames; falls back to webhtv's own
     *  digit-extraction heuristic when there is no list or no match. */
    private static int resolveEpisodeIndex(String vodRemarks, List<String> episodeNames) {
        for (int i = 0; i < episodeNames.size(); i++) {
            if (TextUtils.equals(episodeNames.get(i), vodRemarks)) return i;
        }
        int number = Util.getNumber(vodRemarks);
        return number > 0 ? number - 1 : 0;
    }

    /** Prefers matching kvideoItem's episodeIndex against its own episodes[] to recover
     *  the original episode *name*; falls back to a bare 1-based numeric label. */
    private static String resolveEpisodeLabel(JsonObject kvideoItem, History existing) {
        long episodeIndex = getLong(kvideoItem, "episodeIndex", -1);
        JsonElement episodesEl = kvideoItem.get("episodes");
        if (episodesEl != null && episodesEl.isJsonArray()) {
            JsonArray episodes = episodesEl.getAsJsonArray();
            if (episodeIndex >= 0 && episodeIndex < episodes.size()) {
                JsonElement entry = episodes.get((int) episodeIndex);
                if (entry.isJsonObject() && entry.getAsJsonObject().has("name")) {
                    return entry.getAsJsonObject().get("name").getAsString();
                }
            }
        }
        if (episodeIndex < 0 && existing != null) return existing.getVodRemarks();
        return episodeIndex < 0 ? "" : String.valueOf(episodeIndex + 1);
    }

    private static String requireVideoId(JsonObject item) {
        String videoId = getString(item, "videoId", null);
        if (TextUtils.isEmpty(videoId)) throw new IllegalArgumentException("KVideo item missing videoId");
        return videoId;
    }

    private static String getString(JsonObject object, String field, String fallback) {
        JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static long getLong(JsonObject object, String field, long fallback) {
        JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? fallback : element.getAsLong();
    }

    private static long toSeconds(long millis) {
        return millis <= 0 || millis == C.TIME_UNSET ? 0 : millis / 1000;
    }

    private static long toMillis(long seconds) {
        return seconds <= 0 ? C.TIME_UNSET : seconds * 1000;
    }
}
