package com.fongmi.android.tv.sync;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.dialog.ChoiceDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import java.util.List;

/**
 * UI-facing entry point. Full account profiles (password/redisUrl/accessToken/userGuid)
 * are never cached to disk - every actual sync fetches and decrypts them fresh. Only
 * the account *label list* is cached (AccountLabelCache) so the picker can render
 * instantly instead of blocking on a network round-trip every time it's opened; a fresh
 * fetch still runs in the background to keep that cache current for next time.
 */
public final class KVideoAccountSwitcher {

    private KVideoAccountSwitcher() {
    }

    public static void open(FragmentActivity activity) {
        if (!KVideoAccountStore.hasListSource()) {
            Notify.show(R.string.kvideo_account_source_missing);
            return;
        }
        List<AccountLabelCache.Entry> cached = AccountLabelCache.get();
        if (!cached.isEmpty()) showPickerFromCache(activity, cached);
        refreshInBackground(activity, cached.isEmpty());
    }

    private static void refreshInBackground(FragmentActivity activity, boolean showOnArrival) {
        Task.execute(() -> {
            try {
                List<AccountProfile> profiles = KVideoSyncEngine.get().fetchAccountList();
                List<AccountProfile> usable = profiles.stream().filter(AccountProfile::isUsable).toList();
                AccountLabelCache.save(usable);
                if (showOnArrival) App.post(() -> showPicker(activity, usable));
            } catch (Exception e) {
                if (showOnArrival) App.post(() -> Notify.show(R.string.kvideo_account_fetch_failed));
            }
        });
    }

    private static void showPickerFromCache(FragmentActivity activity, List<AccountLabelCache.Entry> cached) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        CharSequence[] labels = cached.stream().map(e -> (CharSequence) e.label).toArray(CharSequence[]::new);
        int selected = indexOfActiveInCache(cached);
        ChoiceDialog.showSingle(activity, R.string.kvideo_account_switch_title, labels, selected,
                which -> selectByUsername(cached.get(which).username, cached.get(which).label));
    }

    private static void showPicker(FragmentActivity activity, List<AccountProfile> profiles) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (profiles.isEmpty()) {
            Notify.show(R.string.kvideo_account_list_empty);
            return;
        }
        CharSequence[] labels = profiles.stream().map(AccountProfile::getLabel).toArray(CharSequence[]::new);
        int selected = indexOfActive(profiles);
        ChoiceDialog.showSingle(activity, R.string.kvideo_account_switch_title, labels, selected, which -> select(profiles.get(which)));
    }

    private static int indexOfActive(List<AccountProfile> profiles) {
        String active = KVideoAccountStore.getActiveUsername();
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).getUsername().equals(active)) return i;
        return -1;
    }

    private static int indexOfActiveInCache(List<AccountLabelCache.Entry> cached) {
        String active = KVideoAccountStore.getActiveUsername();
        for (int i = 0; i < cached.size(); i++) if (cached.get(i).username.equals(active)) return i;
        return -1;
    }

    /** Picked from the cached label list - re-fetches the full profile (with
     *  credentials) fresh before syncing, since the cache never carries them. */
    private static void selectByUsername(String username, String label) {
        KVideoAccountStore.setActiveUsername(username);
        Notify.show(App.get().getString(R.string.kvideo_account_switched, label));
        Task.execute(() -> {
            try {
                int count = KVideoSyncEngine.get().pull();
                App.post(() -> Notify.show(count > 0
                        ? App.get().getString(R.string.kvideo_account_pull_done, count)
                        : App.get().getString(R.string.kvideo_account_pull_empty)));
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                App.post(() -> Notify.show(App.get().getString(R.string.kvideo_account_pull_failed_detail, message)));
            }
        });
    }

    private static void select(AccountProfile profile) {
        selectByUsername(profile.getUsername(), profile.getLabel());
    }
}
