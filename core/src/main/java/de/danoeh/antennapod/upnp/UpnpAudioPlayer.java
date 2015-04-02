package de.danoeh.antennapod.upnp;

import android.content.Context;
import android.util.Log;
import android.view.SurfaceHolder;

import com.aocate.media.MediaPlayer;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.model.ModelUtil;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.support.avtransport.callback.GetPositionInfo;
import org.fourthline.cling.support.avtransport.callback.GetTransportInfo;
import org.fourthline.cling.support.avtransport.callback.Pause;
import org.fourthline.cling.support.avtransport.callback.Play;
import org.fourthline.cling.support.avtransport.callback.Seek;
import org.fourthline.cling.support.avtransport.callback.SetAVTransportURI;
import org.fourthline.cling.support.avtransport.callback.Stop;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.SeekMode;
import org.fourthline.cling.support.model.TransportInfo;
import org.fourthline.cling.support.model.TransportState;
import org.fourthline.cling.support.renderingcontrol.callback.GetVolume;
import org.fourthline.cling.support.renderingcontrol.callback.SetMute;
import org.fourthline.cling.support.renderingcontrol.callback.SetVolume;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import de.danoeh.antennapod.core.util.playback.IPlayer;
import de.danoeh.antennapod.core.util.playback.Playable;

public class UpnpAudioPlayer implements IPlayer {
    private static final String TAG = "UpnpAudioPlayer";

    private Context context;
    private ReentrantLock lock = new ReentrantLock();
    private AndroidUpnpService upnpService;
    private MediaRenderer upnpMediaRenderer;
    private volatile int position;
    private int duration;
    private int volume;
    private boolean hasAbsoluteTime = false;
    private boolean hasRelativeTime = false;
    private MediaPlayer.OnSeekCompleteListener onSeekCompleteListener;
    private MediaPlayer.OnCompletionListener onCompletionListener;
    private PlaybackServiceUpnpMediaPlayer.OnUpnpErrorListener onErrorListener;

    private Lock mediaPlayerStateLock = new ReentrantLock();
    private volatile MediaPlayer.State mediaPlayerState = MediaPlayer.State.INITIALIZED;
    private boolean firstGetPositionInfoCall = true;
    private String dataSource = null;

    final static int MAX_ATTEMPTS = 7;
    final static int WAIT_TIME_IN_MILLISECONDS = 2000;

    private static class SuccessfulFlag {
        volatile boolean isSuccessful = false;
    }

    public UpnpAudioPlayer(Context context, AndroidUpnpService upnpService,
                           MediaRenderer upnpMediaRenderer) {
        this.context = context;
        this.upnpService = upnpService;
        this.upnpMediaRenderer = upnpMediaRenderer;
        getVolume();
        getTransportInfo(false);
    }

    protected void getTransportInfo(boolean waitForResult) {
        final SuccessfulFlag flag = new SuccessfulFlag();
        final Lock lock = new ReentrantLock();
        final Condition condition = lock.newCondition();
        lock.lock();
        try {
            for (int i = 0; i < MAX_ATTEMPTS; i++) {
                ActionCallback getTransportInfoAction =
                        new GetTransportInfo(upnpMediaRenderer.getAvTransportService()) {
                            @Override
                            public void received(ActionInvocation invocation, TransportInfo transportInfo) {
                                mediaPlayerStateLock.lock();
                                try {
                                    TransportState transportState = transportInfo.getCurrentTransportState();
                                    switch (transportState) {
                                        case PLAYING:
                                            mediaPlayerState = MediaPlayer.State.STARTED;
                                            break;
                                        case PAUSED_PLAYBACK:
                                            mediaPlayerState = MediaPlayer.State.PAUSED;
                                            break;
                                        case STOPPED:
                                            mediaPlayerState = MediaPlayer.State.STOPPED;
                                            break;
                                        case NO_MEDIA_PRESENT:
                                            mediaPlayerState = MediaPlayer.State.IDLE;
                                            break;
                                        case TRANSITIONING:
                                            mediaPlayerState = MediaPlayer.State.PREPARING;
                                            break;
                                        default:
                                            mediaPlayerState = MediaPlayer.State.ERROR;
                                    }
                                } finally {
                                    mediaPlayerStateLock.unlock();
                                }

                                lock.lock();
                                try {
                                    flag.isSuccessful = true;
                                    condition.signal();
                                } finally {
                                    lock.unlock();
                                }
                            }

                            @Override
                            public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                                Log.e(TAG, "Action GetTransportInfo failed on " + upnpMediaRenderer.toString() + ": " + defaultMsg);
                            }
                        };

                upnpService.getControlPoint().execute(getTransportInfoAction);

                if (!waitForResult) {
                    break;
                }

                condition.await(WAIT_TIME_IN_MILLISECONDS, TimeUnit.MILLISECONDS);

                if (flag.isSuccessful) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean canSetPitch() {
        return false;
    }

    @Override
    public boolean canSetSpeed() {
        return false;
    }

    @Override
    public float getCurrentPitchStepsAdjustment() {
        return 0;
    }

    protected void getPositionInfo() {
        Log.v(TAG, upnpMediaRenderer.toString() + " : GetPositionInfo");
        ActionCallback getPositionInfoAction =
                new GetPositionInfo(upnpMediaRenderer.getAvTransportService()) {
                    @Override
                    public void received(ActionInvocation invocation, PositionInfo positionInfo) {
                        if (firstGetPositionInfoCall) {
                            hasRelativeTime = !(positionInfo.getRelTime() == null || positionInfo.getRelTime().equals("NOT_IMPLEMENTED"));
                            hasAbsoluteTime = !(positionInfo.getAbsTime() == null || positionInfo.getAbsTime().equals("NOT_IMPLEMENTED"));
                            String trackDuration = positionInfo.getTrackDuration();
                            int duration = (int) ModelUtil.fromTimeString(trackDuration);
                            if (duration > 0) {
                                duration *= 1000;
                                UpnpAudioPlayer.this.duration = duration;
                            }
                            firstGetPositionInfoCall = false;
                        }
                        //	if (hasRelativeTime) {
                        position = 1000 * (int) ModelUtil.fromTimeString(positionInfo.getRelTime());

                        // there is no possibility to get notified by a media renderer on playback completion
                        // use this here as a workaround. the media player as argument is begin ignored by the
                        // current implementation and can thus be set to null
                        if (duration > 0 && duration - position < 500) {
                            onCompletionListener.onCompletion(null);
                        }
                        //}
                    }

                    @Override
                    public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                        Log.e(TAG, "Action GetPositionInfo failed on " + upnpMediaRenderer.toString() + ": " + defaultMsg);
                    }
                };

        upnpService.getControlPoint().execute(getPositionInfoAction);
    }

    @Override
    public int getCurrentPosition() {
        getPositionInfo();
        // this will yield the previous result and may thus be behind the current position
        // but this ist good enough as approximate
        Log.v(TAG, "Action getCurrentPosition " + position);
        return position;
    }

    @Override
    public float getCurrentSpeedMultiplier() {
        return 0;
    }

    @Override
    public int getDuration() {
        return duration;
    }

    @Override
    public float getMaxSpeedMultiplier() {
        return 0;
    }

    @Override
    public float getMinSpeedMultiplier() {
        return 0;
    }

    @Override
    public boolean isLooping() {
        return false;
    }

    @Override
    public boolean isPlaying() {
        mediaPlayerStateLock.lock();
        try {
            return mediaPlayerState == MediaPlayer.State.STARTED;
        } finally {
            mediaPlayerStateLock.unlock();
        }
    }

    @Override
    public void pause() {
        if (!upnpMediaRenderer.canPause()) {
            stop();
            return;
        }

        Log.v(TAG, upnpMediaRenderer.toString() + " : Pause");
        final SuccessfulFlag flag = new SuccessfulFlag();
        final Lock lock = new ReentrantLock();
        final Condition condition = lock.newCondition();
        lock.lock();
        try {
            for (int i = 0; i < MAX_ATTEMPTS; i++) {
                ActionCallback pauseAction =
                        new Pause(upnpMediaRenderer.getAvTransportService()) {
                            @Override
                            public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
							/*	Log.e(TAG, "Action Pause failed on " + upnpMediaRenderer.toString() + ": " + defaultMsg);
								lock.lock();
								try {
									condition.signal();
								} finally {
									lock.unlock();
								}*/
                            }

                            @Override
                            public void success(ActionInvocation invocation) {
								/*super.success(invocation);
								getTransportInfo(false);

								lock.lock();
								try {
									flag.isSuccessful = true;
									condition.signal();
								} finally {
									lock.unlock();
								}*/
                            }
                        };
                upnpService.getControlPoint().execute(pauseAction);

                condition.await(WAIT_TIME_IN_MILLISECONDS, TimeUnit.MILLISECONDS);

                if (flag.isSuccessful) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void prepare() throws IllegalStateException, IOException {
        prepareAsync();
    }

    @Override
    public void prepareAsync() {
        // there is no action to make a upnp media renderer load the media item
        // here this is circumvented by starting and immediately pausing playback while muting
        // works only if pause action is supported by media renderer
        if (!upnpMediaRenderer.canPause()) {
            return;
        }

        setMute(true);
        start();
        MediaPlayer.State state;
        do {
            getTransportInfo(true);
            mediaPlayerStateLock.lock();
            try {
                state = mediaPlayerState;
            } finally {
                mediaPlayerStateLock.unlock();
            }
        } while (state != MediaPlayer.State.STARTED && state != MediaPlayer.State.PAUSED);
        pause();
        setMute(false);
    }

    @Override
    public void release() {
        stop();
        lock.lock();
        try {
            Log.v(TAG, "Releasing MediaPlayer");

            this.mediaPlayerState = MediaPlayer.State.END;
            this.onCompletionListener = null;
            this.onErrorListener = null;
			/*	this.onInfoListener = null;
				this.preparedListener = null;*/
            this.onSeekCompleteListener = null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void reset() {
		/*stop();
		position = 0;*/
        Log.e(TAG, "reset()");
    }

    @Override
    public void seekTo(int msec) {
        String position = ModelUtil.toTimeString(msec / 1000);
        SeekMode mode = SeekMode.REL_TIME;
        // use absolute time as fallback if media renderer does not support relative time
        if (!hasRelativeTime && hasAbsoluteTime) {
            mode = SeekMode.ABS_TIME;
        }
        Log.v(TAG, upnpMediaRenderer.toString() + " : Seek " + position + " " + msec);
        ActionCallback seek =
                new Seek(upnpMediaRenderer.getAvTransportService(), mode, position) {
                    @Override
                    public void success(ActionInvocation invocation) {
                        if (onSeekCompleteListener != null) {
                            onSeekCompleteListener.onSeekComplete(null);
                        }
                    }

                    @Override
                    public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                        Log.e(TAG, "Action Seek failed on " + upnpMediaRenderer.toString() + ": " + defaultMsg);
                    }
                };

        upnpService.getControlPoint().execute(seek);
    }

    @Override
    public void setAudioStreamType(int streamtype) {

    }

    // not all media renderer return a reasonable duration in GetPositionInfo
    // allow for manual setting
    public void setDataSource(Playable media) throws IllegalStateException, IOException, IllegalArgumentException, SecurityException {
        setDataSource(media.getStreamUrl());
        duration = media.getDuration();
    }

    @Override
    public void setDataSource(String path) throws IllegalStateException, IOException, IllegalArgumentException, SecurityException {
        stop();

        Log.v(TAG, upnpMediaRenderer.toString() + " : SetAVTransportURI " + path);

        // not all media renderers can cope with a redirect
        // we execute the redirect for them and get the real media url
        OkHttpClient client = new OkHttpClient();
        client.setFollowRedirects(false);
        Request request = new Request.Builder()
                .url(path)
                .build();
        Response response = client.newCall(request).execute();
        if (response.isRedirect()) {
            path = response.header("Location");
            Log.v(TAG, upnpMediaRenderer.toString() + " : redirect " + path);
        }

        dataSource = path;
        firstGetPositionInfoCall = true;

        final SuccessfulFlag flag = new SuccessfulFlag();
        final Lock lock = new ReentrantLock();
        final Condition condition = lock.newCondition();
        lock.lock();
        try {
            for (int i = 0; i < MAX_ATTEMPTS; i++) {
                ActionCallback setAVTransportUriAction =
                        new SetAVTransportURI(upnpMediaRenderer.getAvTransportService(), path) {
                            @Override
                            public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                                Log.e(TAG, "Action SetAVTransportUri failed on " + upnpMediaRenderer.toString() + ": " + defaultMsg);
                                lock.lock();
                                try {
                                    condition.signal();
                                } finally {
                                    lock.unlock();
                                }
                            }

                            @Override
                            public void success(ActionInvocation invocation) {
                                super.success(invocation);
                                getTransportInfo(false);

                                lock.lock();
                                try {
                                    flag.isSuccessful = true;
                                    condition.signal();
                                } finally {
                                    lock.unlock();
                                }
                            }
                        };
                upnpService.getControlPoint().execute(setAVTransportUriAction);

                condition.await(WAIT_TIME_IN_MILLISECONDS, TimeUnit.MILLISECONDS);

                if (flag.isSuccessful) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }

        // operation was not successful after several attempts; cannot continue
        Log.e(TAG, "Action SetAVTransportURI ERROR");
        onErrorListener.onError(UpnpAudioPlayer.this, MediaPlayer.MEDIA_ERROR_UNKNOWN, 1);

		/*DIDLContent didlecontent = new DIDLContent();
		Container container = new Container();
		container.addItem(new AudioItem("AudioID", container, "Ich heisse Lindemann", "Erwin Frowein", null));*/
        //Log.e(TAG, upnpMediaRenderer.toString() + " : SetAVTransportURI" + container.toString());
        //	ActionCallback setAVTransportURIAction =
        //			new SetAVTransportURI(new UnsignedIntegerFourBytes(0),upnpMediaRenderer.getAvTransportService(), path, "NOT_IMPLEMENTED"/*"NO METADATA"*//* container.toString()*/) {
	/*				@Override
					public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
						position = 0;
						Log.e(TAG, "Action SetAVTransportURI failed on "+ upnpMediaRenderer.toString() +": "+defaultMsg);
					}

					@Override
					public void success(ActionInvocation invocation) {
						super.success(invocation);
						position = 0;
						Log.e(TAG, "Action SetAVTransportURI success");
					}
				};

		upnpService.getControlPoint().execute(setAVTransportURIAction);*/
    }

    @Override
    public void setEnableSpeedAdjustment(boolean enableSpeedAdjustment) {
    }

    @Override
    public void setLooping(boolean looping) {
    }

    @Override
    public void setPitchStepsAdjustment(float pitchSteps) {
    }

    @Override
    public void setPlaybackPitch(float f) {
    }

    @Override
    public void setPlaybackSpeed(float f) {
    }

    public void setMute(boolean mute) {
        Log.v(TAG, upnpMediaRenderer.toString() + " : SetMute");
        final SuccessfulFlag flag = new SuccessfulFlag();
        final Lock lock = new ReentrantLock();
        final Condition condition = lock.newCondition();
        lock.lock();
        try {
            for (int i = 0; i < MAX_ATTEMPTS; i++) {
                ActionCallback setMuteAction =
                        new SetMute(upnpMediaRenderer.getRenderingControlService(), mute) {
                            @Override
                            public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                                Log.e(TAG, "Action setMute failed on " + upnpMediaRenderer.toString() + ": " + defaultMsg);
                                lock.lock();
                                try {
                                    condition.signal();
                                } finally {
                                    lock.unlock();
                                }
                            }

                            @Override
                            public void success(ActionInvocation invocation) {
                                super.success(invocation);
                                flag.isSuccessful = true;

                                lock.lock();
                                try {
                                    condition.signal();
                                } finally {
                                    lock.unlock();
                                }
                            }
                        };
                upnpService.getControlPoint().execute(setMuteAction);

                condition.await(WAIT_TIME_IN_MILLISECONDS, TimeUnit.MILLISECONDS);

                if (flag.isSuccessful) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void setVolume(float left, float right) {
        Log.v(TAG, upnpMediaRenderer.toString() + " : SetVolume");
        ActionCallback setVolume =
                new SetVolume(upnpMediaRenderer.getRenderingControlService(), (long) left) {
                    @Override
                    public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                        Log.e(TAG, "Action SetVolume failed on " + upnpMediaRenderer.toString() + ": " + defaultMsg);
                    }
                };

        upnpService.getControlPoint().execute(setVolume);
    }

    public int getVolume() {
        Log.v(TAG, upnpMediaRenderer.toString() + " : GetVolume");
        ActionCallback getVolume =
                new GetVolume(upnpMediaRenderer.getRenderingControlService()) {
                    @Override
                    public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                        Log.e(TAG, "Action GetVolume failed on " + upnpMediaRenderer.toString() + ": " + defaultMsg);
                    }

                    @Override
                    public void received(ActionInvocation actionInvocation, int currentVolume) {
                        volume = currentVolume;
                    }
                };

        upnpService.getControlPoint().execute(getVolume);
        return volume;
    }

    @Override
    public void start() {
        Log.v(TAG, upnpMediaRenderer.toString() + " : Play");
        final SuccessfulFlag flag = new SuccessfulFlag();
        final Lock lock = new ReentrantLock();
        final Condition condition = lock.newCondition();
        lock.lock();
        try {
            for (int i = 0; i < MAX_ATTEMPTS; i++) {
                ActionCallback playAction =
                        new Play(upnpMediaRenderer.getAvTransportService()) {
                            @Override
                            public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                                Log.e(TAG, "Action Play failed on " + upnpMediaRenderer.toString() + ": " + defaultMsg);
                                lock.lock();
                                try {
                                    condition.signal();
                                } finally {
                                    lock.unlock();
                                }
                            }

                            @Override
                            public void success(ActionInvocation invocation) {
                                super.success(invocation);
                                getTransportInfo(false);
                                flag.isSuccessful = true;

                                lock.lock();
                                try {
                                    condition.signal();
                                } finally {
                                    lock.unlock();
                                }
                            }
                        };
                upnpService.getControlPoint().execute(playAction);

                condition.await(WAIT_TIME_IN_MILLISECONDS, TimeUnit.MILLISECONDS);

                if (flag.isSuccessful) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void stop() {
        Log.v(TAG, upnpMediaRenderer.toString() + " : Stop");
        final SuccessfulFlag flag = new SuccessfulFlag();
        final Lock lock = new ReentrantLock();
        final Condition condition = lock.newCondition();
        lock.lock();
        try {
            for (int i = 0; i < MAX_ATTEMPTS; i++) {
                ActionCallback stopAction =
                        new Stop(upnpMediaRenderer.getAvTransportService()) {
                            @Override
                            public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                                Log.e(TAG, "Action Stop failed on " + upnpMediaRenderer.toString() + ": " + defaultMsg);
                                lock.lock();
                                try {
                                    condition.signal();
                                } finally {
                                    lock.unlock();
                                }
                            }

                            @Override
                            public void success(ActionInvocation invocation) {
                                super.success(invocation);
                                getTransportInfo(false);
                                lock.lock();
                                try {
                                    flag.isSuccessful = true;
                                    condition.signal();
                                } finally {
                                    lock.unlock();
                                }
                            }
                        };
                upnpService.getControlPoint().execute(stopAction);

                condition.await(WAIT_TIME_IN_MILLISECONDS, TimeUnit.MILLISECONDS);

                if (flag.isSuccessful) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void setWakeMode(Context context, int mode) {
    }

    @Override
    public void setScreenOnWhilePlaying(boolean screenOn) {
        Log.e(TAG, "Setting screen on while playing not supported in Audio Player");
        throw new UnsupportedOperationException("Setting screen on while playing not supported in Audio Player");

    }

    @Override
    public void setDisplay(SurfaceHolder sh) {
        if (sh != null) {
            Log.e(TAG, "Setting display not supported in Audio Player");
            throw new UnsupportedOperationException("Setting display not supported in Audio Player");
        }
    }

    @Override
    public void setVideoScalingMode(int mode) {
        throw new UnsupportedOperationException("Setting scaling mode is not supported in Audio Player");
    }

    public void setOnCompletionListener(MediaPlayer.OnCompletionListener listener) {
        lock.lock();
        try {
            onCompletionListener = listener;
        } finally {
            lock.unlock();
        }
    }

    public void setOnSeekCompleteListener(MediaPlayer.OnSeekCompleteListener listener) {
        lock.lock();
        try {
            onSeekCompleteListener = listener;
        } finally {
            lock.unlock();
        }
    }

    public void setOnErrorListener(PlaybackServiceUpnpMediaPlayer.OnUpnpErrorListener listener) {
        lock.lock();
        try {
            onErrorListener = listener;
        } finally {
            lock.unlock();
        }
    }

    public void setOnBufferingUpdateListener(MediaPlayer.OnBufferingUpdateListener listener) {
    }

    public void setOnInfoListener(MediaPlayer.OnInfoListener listener) {
    }
}
