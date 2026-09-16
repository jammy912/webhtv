package com.fongmi.android.tv.model;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.SmbItem;
import com.fongmi.android.tv.bean.SmbServer;
import com.fongmi.android.tv.smb.SmbBrowser;
import com.fongmi.android.tv.smb.SmbClientPool;
import com.fongmi.android.tv.smb.SmbExecutors;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Drives one SMB browsing session: current folder, listing, and errors. */
public class SmbViewModel extends ViewModel {

    private static final String TAG = "smb-model";

    public static final int LOADING = 0;
    public static final int CONTENT = 1;
    public static final int ERROR = 2;

    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final AtomicInteger requestId = new AtomicInteger();

    private SmbServer server;
    private String path = "";

    public LiveData<State> getState() {
        return state;
    }

    public SmbServer getServer() {
        return server;
    }

    public String getPath() {
        return path;
    }

    /** True when back should leave the browser rather than go up a level. */
    public boolean isRoot() {
        return server == null;
    }

    /** Shows one tile per configured server. */
    public void showServers() {
        server = null;
        path = "";
        requestId.incrementAndGet();
        List<SmbServer> servers = Setting.getSmbServers();
        List<SmbItem> items = new ArrayList<>();
        for (SmbServer item : servers) items.add(SmbItem.server(item));
        state.setValue(State.content(items));
    }

    /**
     * Opens a server directly, skipping the list. Used when only one share is
     * configured, so the common case costs no extra keypress.
     */
    public void openFirst() {
        List<SmbServer> servers = Setting.getSmbServers();
        if (servers.size() == 1) open(servers.get(0), servers.get(0).getPath());
        else showServers();
    }

    public void open(SmbServer server, String path) {
        this.server = server;
        this.path = path == null ? "" : path;
        reload();
    }

    public void enter(SmbItem item) {
        if (item == null || !item.isDir()) return;
        if (item.isServerEntry()) {
            SmbServer target = Setting.getSmbServer(item.getServerId());
            if (target != null) open(target, target.getPath());
            return;
        }
        open(server, item.getRelPath());
    }

    /** Goes up a folder, then back out to the server list at the share root. */
    public void up() {
        if (server == null) return;
        if (path.equals(server.getPath())) {
            if (Setting.getSmbServers().size() > 1) showServers();
            return;
        }
        open(server, SmbServer.parent(path));
    }

    /** True when back at the share root should return to the server list. */
    public boolean canGoUp() {
        if (server == null) return false;
        if (!path.equals(server.getPath())) return true;
        return Setting.getSmbServers().size() > 1;
    }

    public void reload() {
        if (server == null) return;
        final int id = requestId.incrementAndGet();
        final SmbServer target = server;
        final String relPath = path;
        state.setValue(State.loading());
        SmbExecutors.io().execute(() -> {
            try {
                List<SmbItem> items = SmbBrowser.list(target, relPath);
                int dirs = 0;
                for (SmbItem item : items) if (item.isDir()) dirs++;
                SpiderDebug.log(TAG, "list path=%s dirs=%d files=%d", relPath, dirs, items.size() - dirs);
                post(id, State.content(items));
            } catch (Throwable e) {
                SpiderDebug.log(TAG, "list failed errorType=%s", e.getClass().getSimpleName());
                post(id, State.error(describe(e)));
            }
        });
    }

    /** Replaces one item in place, e.g. when its thumbnail finishes decoding. */
    public void update(SmbItem item) {
        State current = state.getValue();
        if (current == null || item == null) return;
        List<SmbItem> items = new ArrayList<>(current.items);
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isSameItem(item)) continue;
            items.set(i, item);
            state.setValue(State.content(items));
            return;
        }
    }

    /** Drops results from a folder the user has already navigated away from. */
    private void post(int id, State value) {
        if (id != requestId.get()) return;
        App.post(() -> {
            if (id == requestId.get()) state.setValue(value);
        });
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        if (message == null) return e.getClass().getSimpleName();
        if (message.contains("STATUS_LOGON_FAILURE")) return "帳號或密碼錯誤，請到管理頁修改";
        if (message.contains("STATUS_BAD_NETWORK_NAME")) return "找不到分享名稱";
        if (message.contains("STATUS_ACCESS_DENIED")) return "沒有存取權限";
        if (message.contains("STATUS_OBJECT_NAME_NOT_FOUND")) return "找不到資料夾";
        if (message.contains("STATUS_OBJECT_PATH_NOT_FOUND")) return "找不到資料夾";
        if (message.contains("STATUS_NOT_A_DIRECTORY")) return "路徑不是資料夾";
        // Otherwise surface the raw status: a bare class name is undiagnosable.
        return e.getClass().getSimpleName() + ": " + message;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        requestId.incrementAndGet();
        SmbExecutors.io().execute(SmbClientPool::closeAll);
    }

    public static class State {

        public final int status;
        public final List<SmbItem> items;
        public final String message;

        private State(int status, List<SmbItem> items, String message) {
            this.status = status;
            this.items = items == null ? new ArrayList<>() : items;
            this.message = message == null ? "" : message;
        }

        static State loading() {
            return new State(LOADING, null, null);
        }

        static State content(List<SmbItem> items) {
            return new State(CONTENT, items, null);
        }

        static State error(String message) {
            return new State(ERROR, null, message);
        }
    }
}
