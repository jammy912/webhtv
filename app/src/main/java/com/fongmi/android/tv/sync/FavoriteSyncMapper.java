package com.fongmi.android.tv.sync;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.db.AppDatabase;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts between webhtv's Keep entity and KVideo's FavoriteItem JSON shape
 * (lib/types/index.ts:114-125). Unlike HistorySyncMapper, the match key here is the
 * exact pair source+videoId (KVideo's own generateFavoriteId() = `${source}:${videoId}`,
 * favorites-store.ts:31-36) - not a fuzzy title match - since KVideo confirmed favorites
 * has no showIdentifier-equivalent field baked into the JSON at all. No simplified/
 * traditional normalization needed here (favorites never compares by title), but the
 * same source-key-naming-mismatch risk noted for history applies equally: if webhtv and
 * KVideo disagree on what string names the same site, this key won't match either.
 */
public class FavoriteSyncMapper {

    private FavoriteSyncMapper() {
    }

    public static JsonObject toKVideoItem(Keep keep) {
        JsonObject item = new JsonObject();
        item.addProperty("videoId", keep.getVodId());
        item.addProperty("title", keep.getVodName());
        if (!TextUtils.isEmpty(keep.getVodPic())) item.addProperty("poster", keep.getVodPic());
        item.addProperty("source", keep.getSiteKey());
        if (!TextUtils.isEmpty(keep.getSiteName())) item.addProperty("sourceName", keep.getSiteName());
        // addedAt is milliseconds (KVideo: favorites-store.ts:59 - "addedAt: Date.now()",
        // no /1000 conversion) - confirmed against KVideo's actual source, not guessed.
        item.addProperty("addedAt", keep.getCreateTime() > 0 ? keep.getCreateTime() : System.currentTimeMillis());
        return item;
    }

    /** Builds the matching key KVideo uses to dedupe: `${source}:${videoId}` verbatim
     *  (favorites-store.ts:31-36's generateFavoriteId), computed from the fields
     *  actually serialized into the JSON - not stored as its own field like history's
     *  showIdentifier. */
    public static String identifierFor(JsonObject item) {
        String source = getString(item, "source", "");
        String videoId = getString(item, "videoId", "");
        return source + ":" + videoId;
    }

    public static String identifierFor(Keep keep) {
        return keep.getSiteKey() + ":" + keep.getVodId();
    }

    /** Recomposes a real webhtv Keep.key (siteKey@@@vodId) from KVideo's source+videoId,
     *  mirroring HistorySyncMapper.resolveKey() - same site-list-must-be-loaded caveat
     *  applies (call sites must ensure VodConfig is loaded before this runs). Returns
     *  null when source doesn't name a configured Site, since unlike history there is no
     *  legacy fallback shape to fall back to (Keep.key has always been siteKey@@@vodId,
     *  never a bare videoId). */
    public static String resolveKey(JsonObject item) {
        String videoId = getString(item, "videoId", null);
        String source = getString(item, "source", null);
        if (TextUtils.isEmpty(videoId) || TextUtils.isEmpty(source)) return null;
        Site site = VodConfig.get().getSite(source);
        if (TextUtils.isEmpty(site.getKey())) return null;
        return source + AppDatabase.SYMBOL + videoId;
    }

    public static Keep toKeep(JsonObject item, Keep existing) {
        String key = resolveKey(item);
        if (key == null) return null;
        Keep keep = existing != null ? copy(existing) : new Keep();
        keep.setKey(key);
        keep.setVodName(getString(item, "title", keep.getVodName()));
        String pic = getString(item, "poster", null);
        if (!TextUtils.isEmpty(pic)) keep.setVodPic(pic);
        String sourceName = getString(item, "sourceName", null);
        keep.setSiteName(!TextUtils.isEmpty(sourceName) ? sourceName : VodConfig.get().getSite(keep.getSiteKey()).getName());
        long addedAt = getLong(item, "addedAt", -1);
        keep.setCreateTime(addedAt > 0 ? addedAt : System.currentTimeMillis());
        return keep;
    }

    public static List<JsonObject> readFavoriteItems(JsonObject payload) {
        List<JsonObject> items = new ArrayList<>();
        JsonElement favoritesEl = payload.get("favorites");
        if (favoritesEl == null || !favoritesEl.isJsonArray()) return items;
        for (JsonElement element : favoritesEl.getAsJsonArray()) {
            if (element.isJsonObject()) items.add(element.getAsJsonObject());
        }
        return items;
    }

    private static Keep copy(Keep source) {
        Keep keep = new Keep();
        keep.setKey(source.getKey());
        keep.setSiteName(source.getSiteName());
        keep.setVodName(source.getVodName());
        keep.setVodPic(source.getVodPic());
        keep.setCreateTime(source.getCreateTime());
        keep.setType(source.getType());
        keep.setCid(source.getCid());
        return keep;
    }

    private static String getString(JsonObject object, String field, String fallback) {
        JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static long getLong(JsonObject object, String field, long fallback) {
        JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? fallback : element.getAsLong();
    }
}
