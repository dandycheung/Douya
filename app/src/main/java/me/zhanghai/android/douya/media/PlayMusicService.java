/*
 * Copyright (c) 2018 Zhang Hai <Dreaming.in.Code.ZH@Gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.douya.media;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.RatingCompat;
import android.text.TextUtils;
import android.util.TypedValue;

import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.exoplayer2.source.MediaSource;

import java.util.ArrayList;
import java.util.List;

import me.zhanghai.android.douya.R;
import me.zhanghai.android.douya.app.Notifications;
import me.zhanghai.android.douya.functional.Functional;
import me.zhanghai.android.douya.glide.GlideApp;
import me.zhanghai.android.douya.item.ui.MusicActivity;
import me.zhanghai.android.douya.network.api.info.frodo.Music;
import me.zhanghai.android.douya.util.CollectionUtils;
import me.zhanghai.android.douya.util.LogUtils;
import me.zhanghai.android.douya.util.StringCompat;

public class PlayMusicService extends Service {

    private static final String KEY_PREFIX = PlayMusicService.class.getName() + '.';

    private static final String EXTRA_MUSIC = KEY_PREFIX + "music";
    private static final String EXTRA_TRACK_INDEX = KEY_PREFIX + "track_index";

    private int mMediaDisplayIconMaxSize;
    private int mMediaArtMaxSize;

    private OkHttpMediaSourceFactory mMediaSourceFactory;
    private MediaPlayback mMediaPlayback;
    private MediaNotification mMediaNotification;

    private long mMusicId;

    private boolean mStopped;

    public static void start(Music music, int trackIndex, Context context) {
        Intent intent = new Intent(context, PlayMusicService.class)
                .putExtra(EXTRA_MUSIC, music)
                .putExtra(EXTRA_TRACK_INDEX, trackIndex);
        context.startService(intent);
    }

    public static void start(Music music, Context context) {
        start(music, 0, context);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Resources resources = getResources();
        mMediaDisplayIconMaxSize = resources.getDimensionPixelSize(
                R.dimen.media_display_icon_max_size);
        // This can actually be 1 smaller than the following:
        //mMediaArtMaxSize = resources.getDimensionPixelSize(R.dimen.media_art_max_size);
        // @see MediaSessionCompat
        mMediaArtMaxSize = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 320,
                resources.getDisplayMetrics());

        mMediaSourceFactory = new OkHttpMediaSourceFactory();
        mMediaPlayback = new MediaPlayback(this::createMediaSourceFromMediaDescription, this::stop,
                this);
        MediaButtonReceiver.setMediaSessionHost(() -> mMediaPlayback.getMediaSession());
        mMediaNotification = new MediaNotification(this, mMediaPlayback.getMediaSession(),
                () -> mMediaPlayback.isPlaying(), Notifications.Channels.PLAY_MUSIC.ID,
                Notifications.Channels.PLAY_MUSIC.NAME_RES,
                Notifications.Channels.PLAY_MUSIC.DESCRIPTION_RES,
                Notifications.Channels.PLAY_MUSIC.IMPORTANCE, Notifications.Ids.PLAYING_MUSIC,
                R.drawable.notification_icon, R.color.douya_primary);
    }

    private MediaSource createMediaSourceFromMediaDescription(
            MediaMetadataCompat mediaMetadata) {
        Uri uri = Uri.parse(mediaMetadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI));
        return mMediaSourceFactory.create(uri);
    }

    private void stop() {
        performStop();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Just in case.
        performStop();
    }

    private void performStop() {
        if (mStopped) {
            return;
        }
        mMediaNotification.stop();
        MediaButtonReceiver.setMediaSessionHost(null);
        mMediaPlayback.release();
        mStopped = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            LogUtils.e("Intent is null in onStartCommand()");
            return START_NOT_STICKY;
        }
        onHandleIntent(intent);
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void onHandleIntent(Intent intent) {

        Music music = intent.getParcelableExtra(EXTRA_MUSIC);
        int trackIndex = intent.getIntExtra(EXTRA_TRACK_INDEX, 0);

        // TODO: Wake lock, wifi lock.
        // TODO: Better Metadata.

        boolean musicChanged = music.id != mMusicId;
        mMusicId = music.id;
        if (musicChanged) {
            mMediaPlayback.stop();
            // TODO: Use dedicated session activity.
            PendingIntent sessionActivity = PendingIntent.getActivity(this, 0,
                    MusicActivity.makeIntent(music, this), PendingIntent.FLAG_UPDATE_CURRENT);
            mMediaPlayback.setSessionActivity(sessionActivity);
            List<MediaMetadataCompat> mediaMetadatas = Functional.map(music.tracks,
                    (track, index) -> makeMediaMetadata(music, track, index), new ArrayList<>());
            mMediaPlayback.setMediaMetadatas(mediaMetadatas);
            loadMediaMetadataAlbumArt(music);
            mMediaPlayback.start();
        }

        mMediaPlayback.skipToQueueItem(trackIndex);
        mMediaPlayback.play();
        mMediaNotification.start();
    }

    private MediaMetadataCompat makeMediaMetadata(Music music, Music.Track track, int index) {
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, track.title);
        if (!music.artists.isEmpty()) {
            String artists = StringCompat.join(getString(R.string.item_information_delimiter_slash),
                    music.getArtistNames());
            builder
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artists)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, artists)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artists);
        }
        if (track.duration > 0) {
            int duration = track.duration * 1000;
            builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
        }
        builder
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, music.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, music.title);
        String date = music.getReleaseDate();
        if (!TextUtils.isEmpty(date)) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_DATE, date);
            if (date.length() > 4) {
                try {
                    long year = Long.parseLong(date.substring(0, 4));
                    builder.putLong(MediaMetadataCompat.METADATA_KEY_YEAR, year);
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                }
            }
        }
        String genre = CollectionUtils.firstOrNull(music.genres);
        if (!TextUtils.isEmpty(genre)) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_GENRE, genre);
        }
        builder
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, index)
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, music.tracks.size());
        String albumArtUri = music.cover.getLargeUrl();
        builder
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, albumArtUri)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, albumArtUri);
        if (music.rating != null) {
            float starRating = music.rating.value / music.rating.max * 5;
            starRating = Math.max(0, Math.min(5, starRating));
            RatingCompat rating = RatingCompat.newStarRating(RatingCompat.RATING_5_STARS,
                    starRating);
            builder.putRating(MediaMetadataCompat.METADATA_KEY_RATING, rating);
        }
        String mediaId = music.id + "#" + index;
        builder
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, track.previewUrl);
        return builder.build();
    }

    private void loadMediaMetadataAlbumArt(Music music) {
        String albumArtUrl = music.cover.getLargeUrl();
        GlideApp.with(this)
                .asBitmap()
                .dontTransform()
                .downsample(DownsampleStrategy.CENTER_INSIDE)
                .override(mMediaDisplayIconMaxSize)
                .load(albumArtUrl)
                .into(new SimpleTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(Bitmap displayIcon,
                                                Transition<? super Bitmap> transition) {
                        if (mMusicId != music.id) {
                            return;
                        }
                        updateMediaMetadataAlbumArt(displayIcon, null);
                        GlideApp.with(PlayMusicService.this)
                                .asBitmap()
                                .dontTransform()
                                .downsample(DownsampleStrategy.CENTER_INSIDE)
                                .override(mMediaArtMaxSize)
                                .load(albumArtUrl)
                                .into(new SimpleTarget<Bitmap>() {
                                    @Override
                                    public void onResourceReady(
                                            Bitmap albumArt,
                                            Transition<? super Bitmap> transition) {
                                        if (mMusicId != music.id) {
                                            return;
                                        }
                                        updateMediaMetadataAlbumArt(displayIcon, albumArt);
                                    }
                                });
                    }
                });
    }

    private void updateMediaMetadataAlbumArt(Bitmap displayIcon, Bitmap albumArt) {
        List<MediaMetadataCompat> mediaMetadatas = mMediaPlayback.getMediaMetadatas();
        mediaMetadatas = Functional.map(mediaMetadatas, mediaMetadata -> {
            MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder(mediaMetadata)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, displayIcon);
            if (albumArt != null) {
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt);
            }
            return builder.build();
        }, new ArrayList<>());
        mMediaPlayback.setMediaMetadatas(mediaMetadatas);
    }
}
