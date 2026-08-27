package com.fongmi.android.tv.sync;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Task;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Second listener alongside PlaybackEventCollector/PlaybackWebhookSender: mirrors
 * playback-quiescence events (pause/stop/ended - never the high-frequency progress
 * tick) into a KVideo-compatible Upstash sync, debounced the same way KVideo's own
 * AutoSync.tsx does (5s trailing debounce after the last store change), rather than
 * on every position update.
 *
 * Also mirrors progress *while playing* (onPlaying()), throttled to once every 60s -
 * not the debounce-on-quiescence path above - so other devices/KVideo's web UI can see
 * roughly-live progress instead of only updating on pause/stop. 60s chosen over
 * KVideo's own 5s cadence to keep Upstash's free-tier request budget sane (60s is a
 * deliberate ~12x reduction from a naive 5s interval).
 *
 * Not wired into PlaybackWebhookSender's JSON+X-WebHTV-* protocol: Upstash's REST API
 * (Bearer auth, /set/<key> path, raw value body) is a different wire format entirely.
 */
public final class KVideoSyncCollector {

    private static final long DEBOUNCE_MS = TimeUnit.SECONDS.toMillis(5);
    private static final long PLAYING_PUSH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(60);
    private static final KVideoSyncCollector INSTANCE = new KVideoSyncCollector();

    private final AtomicReference<Runnable> pendingPush = new AtomicReference<>();
    private final AtomicLong lastPlayingPushAtMs = new AtomicLong(0);

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

    /** Call from PlaybackEventCollector.onProgress() (or similar high-frequency path)
     *  while actively playing. Throttled independently of the pause/stop debounce above
     *  - only pushes if at least PLAYING_PUSH_INTERVAL_MS has passed since the last
     *  push from either this method or onQuiescent(), so a periodic tick right after a
     *  quiescent push doesn't immediately push again. */
    public void onPlaying(@Nullable History history, List<String> episodeNames) {
        if (!isEnabled() || history == null || !history.canSave()) return;
        long now = System.currentTimeMillis();
        long last = lastPlayingPushAtMs.get();
        if (now - last < PLAYING_PUSH_INTERVAL_MS) return;
        if (!lastPlayingPushAtMs.compareAndSet(last, now)) return;
        History snapshot = history.copy();
        List<String> namesSnapshot = episodeNames == null ? Collections.emptyList() : episodeNames;
        Task.execute(() -> KVideoSyncEngine.get().pushSingle(snapshot, namesSnapshot));
    }

    /** Favorites toggling is low-frequency (a deliberate user tap), unlike playback
     *  progress - push immediately rather than debouncing, so the round-trip to
     *  Upstash starts right away instead of waiting out DEBOUNCE_MS for no reason. */
    public void onFavoriteAdded(@Nullable Keep keep) {
        if (!isEnabled() || keep == null) return;
        Task.execute(() -> KVideoSyncEngine.get().pushFavoriteAdd(keep));
    }

    public void onFavoriteRemoved(@Nullable Keep keep) {
        if (!isEnabled() || keep == null) return;
        Task.execute(() -> KVideoSyncEngine.get().pushFavoriteRemove(keep));
    }

    private void schedulePush(History history, List<String> episodeNames) {
        Runnable task = () -> Task.execute(() -> KVideoSyncEngine.get().pushSingle(history, episodeNames));
        Runnable previous = pendingPush.getAndSet(task);
        if (previous != null) App.removeCallbacks(previous);
        App.post(task, DEBOUNCE_MS);
    }
}
