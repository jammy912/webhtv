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

    public boolean isRoot() {
        return server == null || path.equals(server.getPath());
    }

    public void open(SmbServer server, String path) {
        this.server = server;
        this.path = path == null ? "" : path;
        reload();
    }

    public void enter(SmbItem item) {
        if (item == null || !item.isDir()) return;
        open(server, item.getRelPath());
    }

    /** Navigates to the parent folder; stops at the configured start path. */
    public void up() {
        if (server == null || isRoot()) return;
        open(server, SmbServer.parent(path));
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
