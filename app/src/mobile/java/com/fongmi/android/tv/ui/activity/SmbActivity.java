package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import com.fongmi.android.tv.ui.adapter.SmbAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;

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
        SmbServer server = servers.get(0);
        mViewModel.open(server, server.getPath());
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
        if (server == null) return;
        String path = mViewModel.getPath();
        mBinding.title.setText(TextUtils.isEmpty(path) ? server.getName() : server.getName() + "/" + path);
    }

    @Override
    public void onItemClick(SmbItem item) {
        if (item.isDir()) {
            mViewModel.enter(item);
        } else {
            // push_agent plays the id verbatim, so the smb:// URL goes straight through.
            VideoActivity.start(this, SiteApi.PUSH, item.getUrl(), item.getName(), item.getThumb());
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onBackInvoked() {
        if (mViewModel == null || mViewModel.isRoot()) super.onBackInvoked();
        else mViewModel.up();
    }
}
