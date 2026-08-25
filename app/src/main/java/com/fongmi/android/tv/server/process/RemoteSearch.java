package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.model.SearchTask;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.utils.Task;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public class RemoteSearch implements Process {

    private static final long SEARCH_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15);

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/s/");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        try {
            return switch (url) {
                case "/s/query" -> query(session.getParms());
                case "/s/result" -> result(session.getParms());
                case "/s/detail" -> detail(session.getParms());
                case "/s/play" -> play(session.getParms());
                default -> Nano.error(Status.NOT_FOUND, "Not found");
            };
        } catch (Exception e) {
            return Nano.error(e.getMessage());
        }
    }

    private Response query(Map<String, String> params) {
        String keyword = params.getOrDefault("word", "").trim();
        if (TextUtils.isEmpty(keyword)) return Nano.error(Status.BAD_REQUEST, "Missing word");
        List<Site> sites = VodConfig.get().getSites().stream().filter(Site::isSearchable).toList();
        RemoteSearchStore.Session session = RemoteSearchStore.get().startSearch(keyword, sites.size());
        for (Site site : sites) {
            Task.execute(() -> {
                try {
                    Result result = SearchTask.create(site, keyword, false).call();
                    session.addResult(result);
                } catch (Exception e) {
                    session.markSiteDone();
                }
            });
        }
        JsonObject data = new JsonObject();
        data.addProperty("keyword", keyword);
        return Nano.ok(data.toString());
    }

    private Response result(Map<String, String> params) {
        String keyword = params.getOrDefault("word", "").trim();
        RemoteSearchStore.Session session = RemoteSearchStore.get().getSession(keyword);
        if (session == null) return Nano.error(Status.NOT_FOUND, "No such search");
        return Nano.ok(session.toJson().toString());
    }

    private Response detail(Map<String, String> params) throws Exception {
        String siteKey = params.getOrDefault("site", "").trim();
        String vodId = params.getOrDefault("id", "").trim();
        if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(vodId)) return Nano.error(Status.BAD_REQUEST, "Missing site or id");
        JsonObject cached = RemoteSearchStore.get().getDetail(siteKey, vodId);
        if (cached != null) return Nano.ok(cached.toString());
        Site site = VodConfig.get().getSite(siteKey);
        if (site == null || TextUtils.isEmpty(site.getKey())) return Nano.error(Status.NOT_FOUND, "Site not found");
        Result result = SiteApi.detailContent(siteKey, vodId);
        List<Vod> list = result.getList();
        if (list.isEmpty()) return Nano.error(Status.NOT_FOUND, "Vod not found");
        JsonObject data = RemoteSearchStore.detailToJson(site, list.get(0));
        RemoteSearchStore.get().putDetail(siteKey, vodId, data);
        return Nano.ok(data.toString());
    }

    private Response play(Map<String, String> params) {
        String siteKey = params.getOrDefault("site", "").trim();
        String vodId = params.getOrDefault("id", "").trim();
        String mark = params.getOrDefault("mark", "").trim();
        if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(vodId)) return Nano.error(Status.BAD_REQUEST, "Missing site or id");
        ServerEvent.play(siteKey, vodId, mark);
        return Nano.ok();
    }
}
