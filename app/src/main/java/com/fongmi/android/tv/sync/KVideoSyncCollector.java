package com.fongmi.android.tv.sync;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Task;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Second listener alongside PlaybackEventCollector/PlaybackWebhookSender: mirrors
 * playback-quiescence events (pause/stop/ended - never the high-frequency progress
 * tick) into a KVideo-compatible Upstash sync, debounced the same way KVideo's own
 * AutoSync.tsx does (5s trailing debounce after the last store change), rather than
 * on every position update.
 *
 * Not wired into PlaybackWebhookSender's JSON+X-WebHTV-* protocol: Upstash's REST API
 * (Bearer auth, /set/<key> path, raw value body) is a different wire format entirely.
 */
public final class KVideoSyncCollector {

    private static final long DEBOUNCE_MS = TimeUnit.SECONDS.toMillis(5);
    private static final KVideoSyncCollector INSTANCE = new KVideoSyncCollector();

    private final AtomicReference<Runnable> pendingPush = new AtomicReference<>();

    private KVideoSyncCollector() {
    }

    public static KVideoSyncCollector get() {
        return INSTANCE;
    }

    public boolean isEnabled() {
        return !Setting.isIncognito() && KVideoAccountStore.hasActiveAccount();
    }

    /** episodeNames, when known (current Flag's episode names in order), lets the full
     *  episode list ride along for accurate cross-system episode matching (see
     *  HistorySyncMapper). Pass empty when unavailable - never hold onto a Flag/Episode
     *  object across the debounce window since those aren't persisted between calls. */
    public void onQuiescent(@Nullable History history, List<String> episodeNames) {
        if (!isEnabled() || history == null || !history.canSave()) return;
        History snapshot = history.copy();
        List<String> namesSnapshot = episodeNames == null ? Collections.emptyList() : episodeNames;
        schedulePush(snapshot, namesSnapshot);
    }

    private void schedulePush(History history, List<String> episodeNames) {
        Runnable task = () -> Task.execute(() -> KVideoSyncEngine.get().pushSingle(history, episodeNames));
        Runnable previous = pendingPush.getAndSet(task);
        if (previous != null) App.removeCallbacks(previous);
        App.post(task, DEBOUNCE_MS);
    }
}
