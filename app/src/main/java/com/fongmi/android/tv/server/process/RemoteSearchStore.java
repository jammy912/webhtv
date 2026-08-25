package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class RemoteSearchStore {

    private static final RemoteSearchStore INSTANCE = new RemoteSearchStore();

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> details = new ConcurrentHashMap<>();

    static RemoteSearchStore get() {
        return INSTANCE;
    }

    Session startSearch(String keyword, int total) {
        Session session = new Session(keyword, total);
        sessions.put(keyword, session);
        return session;
    }

    Session getSession(String keyword) {
        return sessions.get(keyword);
    }

    String detailKey(String siteKey, String vodId) {
        return siteKey + "::" + vodId;
    }

    void putDetail(String siteKey, String vodId, JsonObject data) {
        details.put(detailKey(siteKey, vodId), data);
    }

    JsonObject getDetail(String siteKey, String vodId) {
        return details.get(detailKey(siteKey, vodId));
    }

    static JsonObject vodToJson(Vod vod) {
        JsonObject item = new JsonObject();
        item.addProperty("siteKey", vod.getSiteKey());
        item.addProperty("siteName", vod.getSiteName());
        item.addProperty("vodId", vod.getId());
        item.addProperty("name", vod.getName());
        item.addProperty("pic", vod.getPic());
        item.addProperty("remarks", vod.getRemarks());
        item.addProperty("year", vod.getYear());
        item.addProperty("area", vod.getArea());
        return item;
    }

    static JsonObject detailToJson(Site site, Vod vod) {
        JsonObject data = new JsonObject();
        data.addProperty("siteKey", site.getKey());
        data.addProperty("vodId", vod.getId());
        data.addProperty("name", vod.getName());
        data.addProperty("pic", vod.getPic());
        JsonArray flags = new JsonArray();
        for (Flag flag : vod.setFlags().getFlags()) {
            JsonObject flagObj = new JsonObject();
            flagObj.addProperty("show", flag.getShow());
            JsonArray episodes = new JsonArray();
            for (Episode episode : flag.getEpisodes()) {
                JsonObject epObj = new JsonObject();
                epObj.addProperty("name", episode.getName());
                episodes.add(epObj);
            }
            flagObj.add("episodes", episodes);
            flags.add(flagObj);
        }
        data.add("flags", flags);
        return data;
    }

    static class Session {

        private final String keyword;
        private final AtomicInteger total = new AtomicInteger();
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final List<JsonObject> items = new java.util.concurrent.CopyOnWriteArrayList<>();

        Session(String keyword, int total) {
            this.keyword = keyword;
            this.total.set(total);
        }

        void addResult(Result result) {
            if (cancelled.get()) return;
            for (Vod vod : result.getList()) items.add(vodToJson(vod));
            completed.incrementAndGet();
        }

        void markSiteDone() {
            completed.incrementAndGet();
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("keyword", keyword);
            object.addProperty("total", total.get());
            object.addProperty("completed", Math.min(completed.get(), total.get()));
            object.addProperty("done", completed.get() >= total.get());
            JsonArray array = new JsonArray();
            for (JsonObject item : items) array.add(item);
            object.add("items", array);
            return object;
        }
    }
}
