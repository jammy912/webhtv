package com.fongmi.android.tv.ui.holder;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.SmbItem;
import com.fongmi.android.tv.databinding.AdapterSmbBinding;
import com.fongmi.android.tv.smb.ThumbLoader;
import com.fongmi.android.tv.ui.presenter.SmbPresenter;

import java.io.File;
import java.util.Locale;

public class SmbHolder extends Presenter.ViewHolder {

    private final SmbPresenter.OnClickListener listener;
    private final AdapterSmbBinding binding;
    private SmbItem item;

    public SmbHolder(@NonNull AdapterSmbBinding binding, SmbPresenter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
    }

    public SmbHolder size(int[] size) {
        binding.image.getLayoutParams().width = size[0];
        binding.image.getLayoutParams().height = size[1];
        return this;
    }

    public void initView(SmbItem item) {
        this.item = item;
        binding.name.setText(item.getName());
        binding.remark.setText(item.isDir() ? "" : format(item.getSize()));
        binding.remark.setVisibility(item.isDir() ? View.GONE : View.VISIBLE);
        binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
        setImage(item);
        if (item.getThumbState() == SmbItem.THUMB_NONE) listener.onThumbNeeded(item, size());
    }

    private int[] size() {
        return new int[]{binding.image.getLayoutParams().width, binding.image.getLayoutParams().height};
    }

    private void setImage(SmbItem item) {
        String thumb = item.getThumb();
        // Glide only ever sees a local file; the SMB read happened in the
        // thumbnail pipeline, never on the bind thread.
        if (!item.isDir() && !TextUtils.isEmpty(thumb) && new File(thumb).exists()) {
            binding.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Glide.with(binding.image).load(new File(thumb)).centerCrop().into(binding.image);
        } else {
            Glide.with(binding.image).clear(binding.image);
            binding.image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            binding.image.setImageResource(item.isDir() ? R.drawable.ic_folder : R.drawable.ic_file);
        }
    }

    public void unbind() {
        Glide.with(binding.image).clear(binding.image);
        if (item != null) ThumbLoader.cancel(item);
    }

    private static String format(long size) {
        if (size <= 0) return "";
        if (size < 1024) return size + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = size;
        int index = -1;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[index]);
    }
}
