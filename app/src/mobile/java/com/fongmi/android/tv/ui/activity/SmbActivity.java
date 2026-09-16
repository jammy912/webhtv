package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
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
import com.fongmi.android.tv.ui.adapter.SmbAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import android.net.Uri;

import java.io.File;
import java.util.List;

public class SmbActivity extends BaseActivity implements SmbAdapter.OnClickListener {

    private ActivitySmbBinding mBinding;
    private SmbViewModel mViewModel;
    private SmbAdapter mAdapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SmbActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySmbBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        setTitle("");
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        setRecyclerView();
        setViewModel();
        open();
    }

    private void setRecyclerView() {
        // 16:9 cells: these are video frames, not posters.
        Style style = new Style("rect", 16f / 9f);
        int column = Product.getColumn(this, style);
        int[] size = Product.getSpec(this, style);
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, Math.max(1, column)));
        mBinding.recycler.setAdapter(mAdapter = new SmbAdapter(this, size));
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
            mBinding.title.setText(getString(com.fongmi.android.tv.R.string.smb_empty));
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
            mAdapter.setItems(state.items);
            mBinding.recycler.scrollToPosition(0);
            mBinding.progressLayout.showContent(true, state.items.size());
        }
    }

    private void setTitle() {
        SmbServer server = mViewModel.getServer();
        if (server == null) {
            mBinding.title.setText(getString(com.fongmi.android.tv.R.string.smb_title));
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(com.fongmi.android.tv.R.menu.menu_smb, menu);
        SearchView search = (SearchView) menu.findItem(com.fongmi.android.tv.R.id.action_search).getActionView();
        if (search != null) setSearch(search);
        return true;
    }

    private void setSearch(SearchView search) {
        search.setQueryHint(getString(com.fongmi.android.tv.R.string.smb_search));
        search.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String text) {
                // Filters the loaded folder only; it never re-reads the share.
                mViewModel.setQuery(text);
                return true;
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        else if (item.getItemId() == com.fongmi.android.tv.R.id.action_sort) onSort();
        return super.onOptionsItemSelected(item);
    }

    private void onSort() {
        mViewModel.cycleSort();
        Notify.show(sortLabel(mViewModel.getSort()));
    }

    private String sortLabel(int sort) {
        int res = switch (sort) {
            case SmbViewModel.SORT_TIME -> com.fongmi.android.tv.R.string.smb_sort_time;
            case SmbViewModel.SORT_SIZE -> com.fongmi.android.tv.R.string.smb_sort_size;
            default -> com.fongmi.android.tv.R.string.smb_sort_name;
        };
        return ResUtil.getString(com.fongmi.android.tv.R.string.smb_sort) + ": " + ResUtil.getString(res);
    }

    @Override
    protected void onBackInvoked() {
        if (mViewModel == null || !mViewModel.canGoUp()) super.onBackInvoked();
        else mViewModel.up();
    }
}
