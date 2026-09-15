package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.bean.SmbItem;
import com.fongmi.android.tv.databinding.AdapterSmbBinding;
import com.fongmi.android.tv.ui.holder.SmbHolder;

public class SmbPresenter extends Presenter {

    private final OnClickListener listener;
    private final int[] size;

    public SmbPresenter(OnClickListener listener, int[] size) {
        this.listener = listener;
        this.size = size;
    }

    public interface OnClickListener {

        void onItemClick(SmbItem item);

        /** Raised on bind when a cell still has no thumbnail. */
        void onThumbNeeded(SmbItem item, int[] size);
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        return new SmbHolder(AdapterSmbBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener).size(size);
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        ((SmbHolder) viewHolder).initView((SmbItem) object);
    }

    /** Releases the Glide request so a recycled cell cannot show a stale frame. */
    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
        ((SmbHolder) viewHolder).unbind();
    }
}
