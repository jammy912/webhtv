package com.fongmi.android.tv.sync;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.dialog.ChoiceDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import java.util.List;

/**
 * UI-facing entry point. fetchAccountList() decrypts the on-disk cached ciphertext
 * (AccountListStore) when present, so opening the switcher is a local decrypt, not a
 * network round-trip - see the class-level note there on why persisting the full
 * account list (credentials included) was judged an acceptable tradeoff.
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
                List<AccountProfile> usable = profiles.stream().filter(AccountProfile::isUsable).toList();
                App.post(() -> showPicker(activity, usable));
            } catch (Exception e) {
                App.post(() -> Notify.show(R.string.kvideo_account_fetch_failed));
            }
        });
    }

    private static void showPicker(FragmentActivity activity, List<AccountProfile> profiles) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (profiles.isEmpty()) {
            Notify.show(R.string.kvideo_account_list_empty);
            return;
        }
        CharSequence[] labels = profiles.stream().map(AccountProfile::getLabel).toArray(CharSequence[]::new);
        String[] icons = profiles.stream().map(AccountProfile::getLogo).toArray(String[]::new);
        int selected = indexOfActive(profiles);
        ChoiceDialog.showSingle(activity, R.string.kvideo_account_switch_title, labels, icons, selected, which -> select(profiles.get(which)));
    }

    private static int indexOfActive(List<AccountProfile> profiles) {
        String active = KVideoAccountStore.getActiveUsername();
        for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).getUsername().equals(active)) return i;
        return -1;
    }

    private static void select(AccountProfile profile) {
        Notify.show(App.get().getString(R.string.kvideo_account_switched, profile.getLabel()));
        Task.execute(() -> {
            try {
                int count = KVideoSyncEngine.get().switchAccount(profile.getUsername());
                KVideoAccountStore.setActiveLogo(profile.getLogo());
                com.fongmi.android.tv.event.RefreshEvent.logo();
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
