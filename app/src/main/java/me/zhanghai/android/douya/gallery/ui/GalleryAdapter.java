/*
 * Copyright (c) 2017 Zhang Hai <Dreaming.in.Code.ZH@Gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.douya.gallery.ui;

import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.support.annotation.Nullable;
import android.support.v4.util.SparseArrayCompat;
import android.support.v4.view.PagerAdapter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.davemorrissey.labs.subscaleview.ImageSource;
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;
import com.github.chrisbanes.photoview.OnPhotoTapListener;
import com.github.chrisbanes.photoview.PhotoView;

import java.io.File;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import me.zhanghai.android.douya.R;
import me.zhanghai.android.douya.glide.GlideApp;
import me.zhanghai.android.douya.glide.progress.ProgressListener;
import me.zhanghai.android.douya.ui.ProgressBarCompat;
import me.zhanghai.android.douya.util.ImageUtils;
import me.zhanghai.android.douya.util.ViewUtils;

public class GalleryAdapter extends PagerAdapter {

    private List<String> mImageList;
    private Listener mListener;
    private SparseArrayCompat<File> mFileMap = new SparseArrayCompat<>();

    public GalleryAdapter(List<String> imageList, Listener listener) {
        mImageList = imageList;
        mListener = listener;
    }

    @Override
    public int getCount() {
        return mImageList.size();
    }

    @Override
    public boolean isViewFromObject(View view, Object object) {
        return view == object;
    }

    @Override
    public View instantiateItem(ViewGroup container, final int position) {
        View layout = ViewUtils.inflate(R.layout.gallery_item, container);
        final ViewHolder holder = new ViewHolder(layout);
        layout.setTag(holder);
        holder.image.setOnPhotoTapListener(new OnPhotoTapListener() {
            @Override
            public void onPhotoTap(ImageView view, float x, float y) {
                if (mListener != null) {
                    mListener.onTap();
                }
            }
        });
        holder.largeImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mListener != null) {
                    mListener.onTap();
                }
            }
        });
        ViewUtils.fadeIn(holder.progress);
        GlideApp.with(holder.image.getContext())
                .downloadOnlyDefaultPriority()
                .load(mImageList.get(position))
                .progressListener(new ProgressListener() {
                    @Override
                    public void onProgress(long bytesRead, long contentLength, boolean done) {
                        int progress = Math.round((float) bytesRead / contentLength
                                * holder.progress.getMax());
                        ProgressBarCompat.setProgress(holder.progress, progress, true);
                    }
                })
                .listener(new RequestListener<File>() {
                    @Override
                    public boolean onResourceReady(File resource, Object model, Target<File> target,
                                                   DataSource dataSource, boolean isFirstResource) {
                        return false;
                    }
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                Target<File> target, boolean isFirstResource) {
                        (e != null ? e : new NullPointerException()).printStackTrace();
                        holder.errorText.setText(R.string.gallery_network_error);
                        ViewUtils.crossfade(holder.progress, holder.errorText);
                        return false;
                    }
                })
                .into(new SimpleTarget<File>() {
                    @Override
                    public void onResourceReady(File resource,
                                                Transition<? super File> transition) {
                        mFileMap.put(position, resource);
                        if (mListener != null) {
                            mListener.onFileDownloaded();
                        }
                        if (false) {
                            ImageUtils.loadImageFile(holder.image, resource,
                                    new RequestListener<Drawable>() {
                                        @Override
                                        public boolean onResourceReady(Drawable resource,
                                                                       Object model,
                                                                       Target<Drawable> target,
                                                                       DataSource dataSource,
                                                                       boolean isFirstResource) {
                                            ViewUtils.fadeOut(holder.progress);
                                            ViewUtils.setVisibleOrGone(holder.image, true);
                                            return false;
                                        }
                                        @Override
                                        public boolean onLoadFailed(@Nullable GlideException e,
                                                                    Object model,
                                                                    Target<Drawable> target,
                                                                    boolean isFirstResource) {
                                            (e != null ? e : new NullPointerException())
                                                    .printStackTrace();
                                            holder.errorText.setText(R.string.gallery_load_error);
                                            ViewUtils.crossfade(holder.progress, holder.errorText);
                                            return false;
                                        }
                                    });
                        } else {
                            holder.largeImage.setDoubleTapZoomDuration(250);
                            holder.largeImage.setDoubleTapZoomStyle(
                                    SubsamplingScaleImageView.ZOOM_FOCUS_CENTER);
                            // Otherwise OnImageEventListener.onReady() is never called.
                            ViewUtils.setVisibleOrGone(holder.largeImage, true);
                            holder.largeImage.setAlpha(0);
                            holder.largeImage.setOnImageEventListener(
                                    new SubsamplingScaleImageView.DefaultOnImageEventListener() {
                                        @Override
                                        public void onReady() {
                                            ViewUtils.crossfade(holder.progress, holder.largeImage);
                                        }
                                        @Override
                                        public void onImageLoadError(Exception e) {
                                            e.printStackTrace();
                                        }
                                    });
                            holder.largeImage.setImage(ImageSource.uri(Uri.fromFile(resource)));
                        }
                    }
                });
        container.addView(layout);
        return layout;
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        View view = (View) object;
        ViewHolder holder = (ViewHolder) view.getTag();
        GlideApp.with(holder.image).clear(holder.image);
        container.removeView(view);
    }

    public File getFile(int position) {
        return mFileMap.get(position);
    }

    public interface Listener {
        void onTap();
        void onFileDownloaded();
    }

    static class ViewHolder {

        @BindView(R.id.image)
        public PhotoView image;
        @BindView(R.id.largeImage)
        public SubsamplingScaleImageView largeImage;
        @BindView(R.id.error)
        public TextView errorText;
        @BindView(R.id.progress)
        public ProgressBar progress;

        public ViewHolder(View view) {
            ButterKnife.bind(this, view);
        }
    }
}
