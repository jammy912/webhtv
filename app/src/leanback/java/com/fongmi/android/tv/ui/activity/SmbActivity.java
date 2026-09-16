package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.text.TextUtils;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.SmbItem;
import com.fongmi.android.tv.bean.SmbServer;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.databinding.ActivitySmbBinding;
import com.fongmi.android.tv.model.SmbViewModel;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.smb.SmbExecutors;
import com.fongmi.android.tv.smb.ThumbCache;
import com.fongmi.android.tv.smb.ThumbLoader;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.presenter.SmbPresenter;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.io.File;
import java.util.List;

public class SmbActivity extends BaseActivity implements SmbPresenter.OnClickListener {

    private ActivitySmbBinding mBinding;
    private SmbViewModel mViewModel;
    private ArrayObjectAdapter mAdapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SmbActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySmbBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        setViewModel();
        open();
    }

    private void setRecyclerView() {
        // 16:9 cells: these are video frames, not posters.
        Style style = new Style("rect", 16f / 9f);
        int column = Math.max(1, Product.getColumn(style));
        mAdapter = new ArrayObjectAdapter(new SmbPresenter(this, Product.getSpec(style)));
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setNumColumns(column);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SmbViewModel.class);
        mViewModel.getState().observe(this, this::onState);
        ThumbLoader.reset();
        SmbExecutors.io().execute(ThumbCache::trim);
    }

    /**
     * Opens the first configured server. Servers are added in the web management
     * page, so an empty list is a setup prompt rather than an error.
     */
    private void open() {
        List<SmbServer> servers = Setting.getSmbServers();
        if (servers.isEmpty()) {
            mBinding.title.setText(getString(R.string.smb_empty));
            mBinding.progressLayout.showEmpty();
            return;
        }
        // One share opens straight away; several show a picker first.
        mViewModel.openFirst();
    }

    private void onState(SmbViewModel.State state) {
        setTitle();
        if (state.status == SmbViewModel.LOADING) {
            mBinding.progressLayout.showProgress();
        } else if (state.status == SmbViewModel.ERROR) {
            mBinding.progressLayout.showEmpty();
            Notify.show(state.message);
        } else {
            mAdapter.setItems(state.items, null);
            mBinding.recycler.setSelectedPosition(0);
            mBinding.progressLayout.showContent(true, state.items.size());
        }
    }

    private void setTitle() {
        SmbServer server = mViewModel.getServer();
        if (server == null) {
            mBinding.title.setText(getString(R.string.smb_title));
            return;
        }
        String path = mViewModel.getPath();
        mBinding.title.setText(TextUtils.isEmpty(path) ? server.getName() : server.getName() + "/" + path);
    }

    @Override
    public void onItemClick(SmbItem item) {
        if (item.isDir()) {
            mViewModel.enter(item);
        } else {
            // push_agent plays the id verbatim, so the smb:// URL goes straight through.
            VideoActivity.start(this, SiteApi.PUSH, item.getUrl(), item.getName(), thumbUri(item));
        }
    }

    /** VideoActivity preloads this through Glide, which needs a URI not a path. */
    private String thumbUri(SmbItem item) {
        String thumb = item.getThumb();
        return TextUtils.isEmpty(thumb) ? "" : Uri.fromFile(new File(thumb)).toString();
    }

    @Override
    public void onThumbNeeded(SmbItem item, int[] size) {
        item.setThumbState(SmbItem.THUMB_LOADING);
        ThumbLoader.request(item, size[0], size[1], (target, path, ok) ->
                mViewModel.update(target.withThumb(path, ok ? SmbItem.THUMB_READY : SmbItem.THUMB_FAILED)));
    }

    /**
     * MENU cycles the sort order. Typing a filter with a D-pad is not worth the
     * friction, so search stays a phone feature.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            mViewModel.cycleSort();
            Notify.show(sortLabel(mViewModel.getSort()));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private String sortLabel(int sort) {
        int res = switch (sort) {
            case SmbViewModel.SORT_TIME -> R.string.smb_sort_time;
            case SmbViewModel.SORT_SIZE -> R.string.smb_sort_size;
            default -> R.string.smb_sort_name;
        };
        return ResUtil.getString(R.string.smb_sort) + ": " + ResUtil.getString(res);
    }

    @Override
    protected void onBackInvoked() {
        if (mViewModel == null || !mViewModel.canGoUp()) super.onBackInvoked();
        else mViewModel.up();
    }
}
