package de.danoeh.antennapod.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;

import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.upnp.MediaRenderer;
import de.danoeh.antennapod.upnp.PlaybackServiceUpnpMediaPlayer;

abstract public class SelectMediaRendererDialog extends Dialog {
    private static final String TAG = "SelectMedaiRenderer";

    private static final int DEFAULT_SPINNER_POSITION = 0;

    private Context context;
    private Spinner spMediaRenderer;
    private Button butConfirm;
    private Button butCancel;
    private PlaybackServiceUpnpMediaPlayer.PSMPDeviceListCallback callback;
    private ArrayAdapter<MediaRenderer> spinnerAdapter;
    private List<MediaRenderer> mediaRendererList;
    private int initialSpinnerPosition;

    public SelectMediaRendererDialog(Context context,
                                     List<MediaRenderer> availableUpnpMediaRenderers,
                                     MediaRenderer currentMediaRenderer) {
        super(context);
        this.context = context;
        mediaRendererList = availableUpnpMediaRenderers;
        callback = new PlaybackServiceUpnpMediaPlayer.PSMPDeviceListCallback() {
            @Override
            public void deviceListUpdated() {
                spMediaRenderer.post(new Runnable() {
                    @Override
                    public void run() {
                        spinnerAdapter.notifyDataSetChanged();
                    }
                });
            }
        };
        registerCallback(callback);
        initialSpinnerPosition = availableUpnpMediaRenderers.indexOf(currentMediaRenderer);
        if (initialSpinnerPosition == -1) {
            initialSpinnerPosition = DEFAULT_SPINNER_POSITION;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        setContentView(R.layout.select_media_renderer);
        spMediaRenderer = (Spinner) findViewById(R.id.spMediaRenderer);
        butConfirm = (Button) findViewById(R.id.butConfirm);
        butCancel = (Button) findViewById(R.id.butCancel);

        butConfirm.setText(R.string.confirm_label);
        butCancel.setText(R.string.cancel_label);
        setTitle("Select media renderer");
        spinnerAdapter = new ArrayAdapter<MediaRenderer>(
                context, android.R.layout.simple_spinner_item,
                mediaRendererList);
        spinnerAdapter
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMediaRenderer.setAdapter(spinnerAdapter);

        spMediaRenderer.setSelection(initialSpinnerPosition);

        butCancel.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                onMediaRendererNotSelected();
                dismiss();
            }
        });
        butConfirm.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                onMediaRendererSelected((MediaRenderer) spMediaRenderer.getSelectedItem());
                dismiss();
            }
        });

    }

    @Override
    public void onStop() {
        super.onStop();
        Log.d(TAG, "onStop");
    }

    abstract public void onMediaRendererSelected(MediaRenderer mediaRenderer);

    abstract public void onMediaRendererNotSelected();

    abstract public void registerCallback(PlaybackServiceUpnpMediaPlayer.PSMPDeviceListCallback callback);
}
