package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.bean.SmbItem;
import com.fongmi.android.tv.databinding.AdapterSmbBinding;
import com.fongmi.android.tv.ui.holder.SmbHolder;

public class SmbAdapter extends BaseDiffAdapter<SmbItem, SmbHolder> {

    private final OnClickListener listener;
    private final int[] size;

    public SmbAdapter(OnClickListener listener, int[] size) {
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
    public SmbHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new SmbHolder(AdapterSmbBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener).size(size);
    }

    @Override
    public void onBindViewHolder(@NonNull SmbHolder holder, int position) {
        holder.initView(getItem(position));
    }

    /** Releases the Glide request so a recycled cell cannot show a stale frame. */
    @Override
    public void onViewRecycled(@NonNull SmbHolder holder) {
        holder.unbind();
    }
}
