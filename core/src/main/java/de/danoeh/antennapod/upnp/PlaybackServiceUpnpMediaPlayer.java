package de.danoeh.antennapod.upnp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.util.Pair;

import org.apache.commons.lang3.Validate;
import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.android.AndroidUpnpServiceImpl;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.registry.DefaultRegistryListener;
import org.fourthline.cling.registry.Registry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.core.BuildConfig;
import de.danoeh.antennapod.core.feed.MediaType;
import de.danoeh.antennapod.core.service.playback.PlaybackServiceMediaPlayer;
import de.danoeh.antennapod.core.service.playback.PlayerStatus;
import de.danoeh.antennapod.core.util.playback.AudioPlayer;
import de.danoeh.antennapod.core.util.playback.IPlayer;
import de.danoeh.antennapod.core.util.playback.Playable;
import de.danoeh.antennapod.core.util.playback.VideoPlayer;

/**
 * Manages the MediaPlayer object of the PlaybackService.
 */
public class PlaybackServiceUpnpMediaPlayer extends PlaybackServiceMediaPlayer {
    public static final String TAG = "PlaybackServiceUpnpMediaPlayer";

    /**
     * Return value of some PSMP methods if the method call failed.
     */
    public static final int NO_UPNP_PLAYER = -1;
    public static final int VOLUME_STEP = 5;

    private AndroidUpnpService upnpService;
    private MediaRenderer upnpMediaRenderer;
    private ArrayList<MediaRenderer> availableRenderDevices;

    private PSMPDeviceListCallback deviceListCallback;

    @Override
    protected void finalize() throws Throwable {
        context.unbindService(serviceConnection);
        super.finalize();
    }

    public PlaybackServiceUpnpMediaPlayer(Context context, PSMPCallback callback) {
        super(context, callback);

        context.bindService(new Intent(context, AndroidUpnpServiceImpl.class), serviceConnection,
                Context.BIND_AUTO_CREATE);

        availableRenderDevices = new ArrayList<MediaRenderer>();
        // add local dummy Device
        availableRenderDevices.add(new MediaRenderer(null));
    }

    /**
     * Internal implementation of playMediaObject. This method has an additional parameter that allows the caller to force a media player reset even if
     * the given playable parameter is the same object as the currently playing media.
     * <p>
     * This method requires the playerLock and is executed on the caller's thread.
     *
     * @see #playMediaObject(Playable, boolean, boolean, boolean)
     */
    private void playMediaObject(final Playable playable, final boolean forceReset, final boolean stream, final boolean startWhenPrepared, final boolean prepareImmediately) {
        Validate.notNull(playable);
        if (!playerLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("method requires playerLock");
        }

        if (media != null) {
            if (!forceReset && media.getIdentifier().equals(playable.getIdentifier())
                    && playerStatus == PlayerStatus.PLAYING) {
                // episode is already playing -> ignore method call
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Method call to playMediaObject was ignored: media file already playing.");
                }
                return;
            }
            else {
                // stop playback of this episode
                if (playerStatus == PlayerStatus.PAUSED || playerStatus == PlayerStatus.PLAYING || playerStatus == PlayerStatus.PREPARED) {
                    mediaPlayer.stop();
                }
                // set temporarily to pause in order to update list with current position
                if (playerStatus == PlayerStatus.PLAYING) {
                    setPlayerStatus(PlayerStatus.PAUSED, media);
                }
                setPlayerStatus(PlayerStatus.INDETERMINATE, null);
            }
        }

        this.media = playable;
        this.stream = stream;
        this.mediaType = media.getMediaType();
        this.videoSize = null;
        createMediaPlayer();
        PlaybackServiceUpnpMediaPlayer.this.startWhenPrepared.set(startWhenPrepared);
        setPlayerStatus(PlayerStatus.INITIALIZING, media);
        try {
            media.loadMetadata();
            mediaSession.setMetadata(getMediaSessionMetadata(media));
            if (upnpMediaRenderer != null) {
                ((UpnpAudioPlayer) mediaPlayer).setDataSource(media);
            }
            else if (stream) {
                mediaPlayer.setDataSource(media.getStreamUrl());
            }
            else {
                mediaPlayer.setDataSource(media.getLocalMediaUrl());
            }
            setPlayerStatus(PlayerStatus.INITIALIZED, media);

            if (mediaType == MediaType.VIDEO) {
                VideoPlayer vp = (VideoPlayer) mediaPlayer;
                vp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            }

            if (prepareImmediately) {
                setPlayerStatus(PlayerStatus.PREPARING, media);
                mediaPlayer.prepare();
                onPrepared(startWhenPrepared);
            }

        } catch (Playable.PlayableException e) {
            e.printStackTrace();
            setPlayerStatus(PlayerStatus.ERROR, null);
        } catch (IOException e) {
            e.printStackTrace();
            setPlayerStatus(PlayerStatus.ERROR, null);
        } catch (IllegalStateException e) {
            e.printStackTrace();
            setPlayerStatus(PlayerStatus.ERROR, null);
        }
    }

    /**
     * Starts or prepares playback of the specified Playable object. If another Playable object is already being played, the currently playing
     * episode will be stopped and replaced with the new Playable object. If the Playable object is already being played, the method will
     * not do anything.
     * Whether playback starts immediately depends on the given parameters. See below for more details.
     * <p>
     * States:
     * During execution of the method, the object will be in the INITIALIZING state. The end state depends on the given parameters.
     * <p>
     * If 'prepareImmediately' is set to true, the method will go into PREPARING state and after that into PREPARED state. If
     * 'startWhenPrepared' is set to true, the method will additionally go into PLAYING state.
     * <p>
     * If an unexpected error occurs while loading the Playable's metadata or while setting the MediaPlayers data source, the object
     * will enter the ERROR state.
     * <p>
     * This method is executed on an internal executor service.
     *
     * @param playable           The Playable object that is supposed to be played. This parameter must not be null.
     * @param stream             The type of playback. If false, the Playable object MUST provide access to a locally available file via
     *                           getLocalMediaUrl. If true, the Playable object MUST provide access to a resource that can be streamed by
     *                           the Android MediaPlayer via getStreamUrl.
     * @param startWhenPrepared  Sets the 'startWhenPrepared' flag. This flag determines whether playback will start immediately after the
     *                           episode has been prepared for playback. Setting this flag to true does NOT mean that the episode will be prepared
     *                           for playback immediately (see 'prepareImmediately' parameter for more details)
     * @param prepareImmediately Set to true if the method should also prepare the episode for playback.
     */
    public void playMediaObject(final Playable playable, final boolean stream, final boolean startWhenPrepared, final boolean prepareImmediately) {
        Validate.notNull(playable);

        if (BuildConfig.DEBUG) Log.d(TAG, "Play media object.");
        executor.submit(new Runnable() {
            @Override
            public void run() {
                playerLock.lock();
                try {
                    playMediaObject(playable, false, stream, startWhenPrepared, prepareImmediately);
                } catch (RuntimeException e) {
                    e.printStackTrace();
                    throw e;
                } finally {
                    playerLock.unlock();
                }
            }
        });
    }


    /**
     * Called after media player has been prepared. This method is executed on the caller's thread.
     */
    void onPrepared(final boolean startWhenPrepared) {
        playerLock.lock();

        if (playerStatus != PlayerStatus.PREPARING && upnpMediaRenderer == null) {
            playerLock.unlock();
            throw new IllegalStateException("Player is not in PREPARING state");
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Resource prepared");
        }

        if (mediaType == MediaType.VIDEO) {
            VideoPlayer vp = (VideoPlayer) mediaPlayer;
            videoSize = new Pair<Integer, Integer>(vp.getVideoWidth(), vp.getVideoHeight());
        }

        if (media.getPosition() > 0) {
            mediaPlayer.seekTo(media.getPosition());
        }

        if (media.getDuration() == 0) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Setting duration of media");
            }
            media.setDuration(mediaPlayer.getDuration());
        }

        setPlayerStatus(PlayerStatus.PREPARED, media);

        if (startWhenPrepared && upnpMediaRenderer == null) {
            resumeSync();
        }

        playerLock.unlock();
    }


    /*
     * Sets the player status of the PSMP object. PlayerStatus and media attributes have to be set at the same time
     * so that getPSMPInfo can't return an invalid state (e.g. status is PLAYING, but media is null).
     * <p/>
     * This method will notify the callback about the change of the player status (even if the new status is the same
     * as the old one).
     *
     * @param newStatus The new PlayerStatus. This must not be null.
     * @param newMedia  The new playable object of the PSMP object. This can be null.
     */
  /*  private synchronized void setPlayerStatus(PlayerStatus newStatus, Playable newMedia) {
        Validate.notNull(newStatus);

        if (BuildConfig.DEBUG) Log.d(TAG, "Setting player status to " + newStatus);

        this.playerStatus = newStatus;
        this.media = newMedia;

        PlaybackStateCompat.Builder sessionState = new PlaybackStateCompat.Builder();

        int state;
        if (playerStatus != null) {
            switch (playerStatus) {
                case PLAYING:
                    state = PlaybackStateCompat.STATE_PLAYING;
                    break;
                case PREPARED:
                case PAUSED:
                    state = PlaybackStateCompat.STATE_PAUSED;
                    break;
                case STOPPED:
                    state = PlaybackStateCompat.STATE_STOPPED;
                    break;
                case SEEKING:
                    state = PlaybackStateCompat.STATE_FAST_FORWARDING;
                    break;
                case PREPARING:
                case INITIALIZING:
                    state = PlaybackStateCompat.STATE_CONNECTING;
                    break;
                case INITIALIZED:
                case INDETERMINATE:
                    state = PlaybackStateCompat.STATE_NONE;
                    break;
                case ERROR:
                    state = PlaybackStateCompat.STATE_ERROR;
                    break;
                default:
                    state = PlaybackStateCompat.STATE_NONE;
                    break;
            }
        } else {
            state = PlaybackStateCompat.STATE_NONE;
        }
        sessionState.setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, getPlaybackSpeed());

        callback.statusChanged(new PSMPInfo(playerStatus, media));
    }*/

    private IPlayer createMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        if (media == null || media.getMediaType() == MediaType.VIDEO) {
            mediaPlayer = new VideoPlayer();
        }
        else if (upnpMediaRenderer != null) {
            mediaPlayer = new UpnpAudioPlayer(context, upnpService, upnpMediaRenderer);
        }
        else {
            mediaPlayer = new AudioPlayer(context);
        }
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mediaPlayer.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
        return setMediaPlayerListeners(mediaPlayer);
    }

    public void endPlayback() {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                playerLock.lock();
                releaseWifiLockIfNecessary();

                if (playerStatus != PlayerStatus.INDETERMINATE) {
                    setPlayerStatus(PlayerStatus.INDETERMINATE, media);
                }
                if (mediaPlayer != null) {
                    mediaPlayer.reset();

                }
                audioManager.abandonAudioFocus(audioFocusChangeListener);
                callback.endPlayback(true);

                playerLock.unlock();
            }
        });
    }

    public boolean canSetSpeed() {
        if (upnpMediaRenderer != null) {
            return false;
        }
        return super.canSetSpeed();
    }

    public int setUpnpVolumeUp() {
        playerLock.lock();
        try {
            if (upnpMediaRenderer == null) {
                return NO_UPNP_PLAYER;
            }

            UpnpAudioPlayer upnpPlayer = (UpnpAudioPlayer) mediaPlayer;
            int volume = upnpPlayer.getVolume() + VOLUME_STEP;
            upnpPlayer.setVolume(volume, volume);
            return volume;
        } finally {
            playerLock.unlock();
        }
    }

    public int setUpnpVolumeDown() {
        playerLock.lock();
        try {
            if (upnpMediaRenderer == null) {
                return NO_UPNP_PLAYER;
            }

            UpnpAudioPlayer upnpPlayer = (UpnpAudioPlayer) mediaPlayer;
            int volume = Math.max(upnpPlayer.getVolume() - VOLUME_STEP, 0);
            upnpPlayer.setVolume(volume, volume);
            return volume;
        } finally {
            playerLock.unlock();
        }
    }

    public void setUPnPMediaRenderer(final MediaRenderer device) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                setUPnPMediaRendererInner(device);
            }
        });
    }


    public void setUPnPMediaRendererInner(MediaRenderer device) {
        playerLock.lock();
        try {
            if (upnpMediaRenderer == device) {
                return;
            }

            if (device.getDevice() != null) {
                upnpMediaRenderer = device;
            }
            else {
                upnpMediaRenderer = null;
            }

            boolean restart = false;
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                setPlayerStatus(PlayerStatus.PAUSED, media);
                restart = true;
            }

            setPlayerStatus(PlayerStatus.STOPPED, media);
            createMediaPlayer();

            PlaybackServiceUpnpMediaPlayer.this.startWhenPrepared.set(restart);
            setPlayerStatus(PlayerStatus.INITIALIZING, media);
            try {
                mediaSession.setMetadata(getMediaSessionMetadata(media));
                if (upnpMediaRenderer != null) {
                    ((UpnpAudioPlayer) mediaPlayer).setDataSource(media);
                }
                else if (stream) {
                    mediaPlayer.setDataSource(media.getStreamUrl());
                }
                else {
                    mediaPlayer.setDataSource(media.getLocalMediaUrl());
                }

                if (upnpMediaRenderer == null) {
                    // this eventually changes the play button
                    // do not set status here in case of upnp media renderers
                    // since we're not done with preparation yet
                    setPlayerStatus(PlayerStatus.INITIALIZED, media);
                }

                if (mediaType == MediaType.VIDEO) {
                    VideoPlayer vp = (VideoPlayer) mediaPlayer;
                    vp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                }

                if (true) {
                    if (upnpMediaRenderer == null) {
                        setPlayerStatus(PlayerStatus.PREPARING, media);
                    }
                    mediaPlayer.prepare();
                    onPrepared(restart);
                }

                if (upnpMediaRenderer != null) {
                    // finally the upnp media renderer is initialized
                    setPlayerStatus(PlayerStatus.INITIALIZED, media);
                }

            } catch (IOException e) {
                e.printStackTrace();
                setPlayerStatus(PlayerStatus.ERROR, null);
            } catch (IllegalStateException e) {
                e.printStackTrace();
                setPlayerStatus(PlayerStatus.ERROR, null);
            }
        } finally {
            playerLock.unlock();
        }
    }

    public MediaRenderer getUPnPMediaRenderer() {
        return upnpMediaRenderer;
    }

    public List<MediaRenderer> getAvailableUPnPMediaRenderers() {
        return availableRenderDevices;
    }

    public void registerCallback(PSMPDeviceListCallback callback) {
        deviceListCallback = callback;
    }

    public void unregisterCallback() {
        deviceListCallback = null;
    }

    private RegistryListener registryListener = new RegistryListener();

    protected class RegistryListener extends DefaultRegistryListener {

        @Override
        public void deviceRemoved(Registry registry, Device device) {
            deviceRemoved(device);
        }

        @Override
        public void deviceAdded(Registry registry, Device device) {
            deviceAdded(device);
        }

        @Override
        public void remoteDeviceDiscoveryStarted(Registry registry, RemoteDevice device) {
            deviceAdded(device);
        }

        @Override
        public void remoteDeviceAdded(Registry registry, RemoteDevice device) {
            deviceAdded(device);
        }

        @Override
        public void remoteDeviceRemoved(Registry registry, RemoteDevice device) {
            deviceRemoved(device);
        }

        @Override
        public void localDeviceAdded(Registry registry, LocalDevice device) {
            deviceAdded(device);
        }

        @Override
        public void localDeviceRemoved(Registry registry, LocalDevice device) {
            deviceRemoved(device);
        }

        public void deviceAdded(final Device device) {
            for (Service service : device.getServices()) {
                if (service.getServiceId().getId().contains("AVTransport")) {
                    if (device.isFullyHydrated()) {
                        MediaRenderer addedMediaRenderer = new MediaRenderer(device);
                        availableRenderDevices.add(addedMediaRenderer);
                        if (deviceListCallback != null) {
                            deviceListCallback.deviceListUpdated();
                        }
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Discovered new media renderer: " + addedMediaRenderer);
                        }
                    }
                }

            }
        }

        public void deviceRemoved(final Device device) {
            for (Service service : device.getServices()) {
                if (service.getServiceId().getId().contains("AVTransport")) {
                    MediaRenderer removedMediaRenderer = new MediaRenderer(device);
                    availableRenderDevices.remove(removedMediaRenderer);
                    if (deviceListCallback != null) {
                        deviceListCallback.deviceListUpdated();
                    }
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Removed media renderer: " + removedMediaRenderer);
                    }
                    playerLock.lock();
                    try {
                        if (upnpMediaRenderer == removedMediaRenderer) {
                            // current player was removed, resort to local
                            setUPnPMediaRendererInner(null);
                        }
                    } finally {
                        playerLock.unlock();
                    }
                }
            }
        }
    }


    private ServiceConnection serviceConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder service) {
            upnpService = (AndroidUpnpService) service;
            upnpService.getRegistry().addListener(registryListener);
            upnpService.getControlPoint().search();
        }

        public void onServiceDisconnected(ComponentName className) {
            upnpService = null;
        }
    };

    public interface PSMPDeviceListCallback {
        void deviceListUpdated();
    }

    private IPlayer setMediaPlayerListeners(IPlayer mp) {
        if (mp != null && media != null) {
            if (upnpMediaRenderer != null && media.getMediaType() == MediaType.AUDIO) {
                ((UpnpAudioPlayer) mp)
                        .setOnCompletionListener(audioCompletionListener);
                ((UpnpAudioPlayer) mp)
                        .setOnSeekCompleteListener(audioSeekCompleteListener);
                ((UpnpAudioPlayer) mp).setOnErrorListener(upnpErrorListener);
                ((UpnpAudioPlayer) mp).setOnInfoListener(audioInfoListener);
            }
            else if (media.getMediaType() == MediaType.AUDIO) {
                ((AudioPlayer) mp)
                        .setOnCompletionListener(audioCompletionListener);
                ((AudioPlayer) mp)
                        .setOnSeekCompleteListener(audioSeekCompleteListener);
                ((AudioPlayer) mp).setOnErrorListener(audioErrorListener);
                ((AudioPlayer) mp)
                        .setOnBufferingUpdateListener(audioBufferingUpdateListener);
                ((AudioPlayer) mp).setOnInfoListener(audioInfoListener);
            }
            else {
                ((VideoPlayer) mp)
                        .setOnCompletionListener(videoCompletionListener);
                ((VideoPlayer) mp)
                        .setOnSeekCompleteListener(videoSeekCompleteListener);
                ((VideoPlayer) mp).setOnErrorListener(videoErrorListener);
                ((VideoPlayer) mp)
                        .setOnBufferingUpdateListener(videoBufferingUpdateListener);
                ((VideoPlayer) mp).setOnInfoListener(videoInfoListener);
            }
        }
        return mp;
    }

    private final com.aocate.media.MediaPlayer.OnInfoListener audioInfoListener = new com.aocate.media.MediaPlayer.OnInfoListener() {
        @Override
        public boolean onInfo(com.aocate.media.MediaPlayer mp, int what,
                              int extra) {
            return genericInfoListener(what);
        }
    };

    private final android.media.MediaPlayer.OnInfoListener videoInfoListener = new android.media.MediaPlayer.OnInfoListener() {
        @Override
        public boolean onInfo(android.media.MediaPlayer mp, int what, int extra) {
            return genericInfoListener(what);
        }
    };

    public interface OnUpnpErrorListener {
        boolean onError(UpnpAudioPlayer arg0, int what, int extra);
    }

    private boolean genericInfoListener(int what) {
        return callback.onMediaPlayerInfo(what);
    }

    private final OnUpnpErrorListener upnpErrorListener = new OnUpnpErrorListener() {
        @Override
        public boolean onError(UpnpAudioPlayer mp, int what, int extra) {
            return genericOnError(mp, what, extra);
        }
    };


}
