package com.fongmi.android.tv.sync;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.dialog.ChoiceDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import java.util.List;

/**
 * UI-facing entry point: fetches+decrypts the account list fresh (never cached to
 * disk), shows only account labels for selection (no password/token ever rendered),
 * and on selection stores the chosen username then triggers one pull() to bring in
 * that account's KVideo history immediately.
 */
public final class KVideoAccountSwitcher {

    private KVideoAccountSwitcher() {
    }

    public static void open(FragmentActivity activity) {
        if (!KVideoAccountStore.hasListSource()) {
            Notify.show(R.string.kvideo_account_source_missing);
            return;
        }
        Task.execute(() -> {
            try {
                List<AccountProfile> profiles = KVideoSyncEngine.get().fetchAccountList();
                App.post(() -> showPicker(activity, profiles));
            } catch (Exception e) {
                App.post(() -> Notify.show(R.string.kvideo_account_fetch_failed));
            }
        });
    }

    private static void showPicker(FragmentActivity activity, List<AccountProfile> profiles) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        List<AccountProfile> usable = profiles.stream().filter(AccountProfile::isUsable).toList();
        if (usable.isEmpty()) {
            Notify.show(R.string.kvideo_account_list_empty);
            return;
        }
        CharSequence[] labels = usable.stream().map(AccountProfile::getLabel).toArray(CharSequence[]::new);
        int selected = indexOfActive(usable);
        ChoiceDialog.showSingle(activity, R.string.kvideo_account_switch_title, labels, selected, which -> select(usable.get(which)));
    }

    private static int indexOfActive(List<AccountProfile> profiles) {
        String active = KVideoAccountStore.getActiveUsername();
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).getUsername().equals(active)) return i;
        return -1;
    }

    private static void select(AccountProfile profile) {
        KVideoAccountStore.setActiveUsername(profile.getUsername());
        Notify.show(App.get().getString(R.string.kvideo_account_switched, profile.getLabel()));
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
}
