// Ported from SpeechNotes' com.wellsrc.speechkeys.a — the Bluetooth SCO headset manager.
// Logic identical to the original; only names were made readable.
package helium314.keyboard.voice;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.os.CountDownTimer;
import android.util.Log;
import java.util.List;

/** Manages Bluetooth SCO audio routing for voice input (was com.wellsrc.speechkeys.a). */
public abstract class BluetoothScoManager {

    private Context context;
    private BluetoothHeadset headset;
    private BluetoothDevice device;
    private AudioManager audioManager;
    private boolean connecting;
    private boolean scoStarting;
    private boolean scoConnected;
    private boolean started;
    private BroadcastReceiver aclReceiver = new AclReceiver();
    private CountDownTimer scoConnectTimer = new ScoConnectTimer(10000, 1000);
    private BluetoothProfile.ServiceListener serviceListener = new ProfileServiceListener();
    private BroadcastReceiver profileReceiver = new ProfileReceiver();
    private CountDownTimer voiceRecognitionTimer = new VoiceRecognitionTimer(10000, 1000);
    private BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

    /** Delivers SCO readiness once; both legacy and profile broadcasts may report it. */
    private void notifyScoConnectedOnce() {
        if (this.scoConnected) {
            return;
        }
        this.scoConnected = true;
        this.onScoConnected();
    }

    class AclReceiver extends BroadcastReceiver {
        AclReceiver() {
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String str;
            int deviceClass;
            String action = intent.getAction();
            if (action.equals("android.bluetooth.device.action.ACL_CONNECTED")) {
                BluetoothScoManager.this.device = (BluetoothDevice) intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE");
                BluetoothClass bluetoothClass = BluetoothScoManager.this.device.getBluetoothClass();
                if (bluetoothClass != null && ((deviceClass = bluetoothClass.getDeviceClass()) == 1032 || deviceClass == 1028)) {
                    BluetoothScoManager.this.audioManager.setMode(2);
                    BluetoothScoManager.this.connecting = true;
                    BluetoothScoManager.this.scoConnectTimer.start();
                    BluetoothScoManager.this.onHeadsetConnected();
                }
                str = BluetoothScoManager.this.device.getName() + " connected";
            } else {
                if (action.equals("android.bluetooth.device.action.ACL_DISCONNECTED")) {
                    Log.d("BluetoothHeadsetUtils", "Headset disconnected");
                    if (BluetoothScoManager.this.connecting) {
                        BluetoothScoManager.this.connecting = false;
                        BluetoothScoManager.this.scoConnectTimer.cancel();
                    }
                    BluetoothScoManager.this.audioManager.setMode(0);
                    BluetoothScoManager.this.onHeadsetDisconnected();
                    return;
                }
                if (!action.equals("android.media.SCO_AUDIO_STATE_CHANGED")) {
                    return;
                }
                int intExtra = intent.getIntExtra("android.media.extra.SCO_AUDIO_STATE", -1);
                if (intExtra != 1) {
                    if (intExtra == 0) {
                        Log.d("BluetoothHeadsetUtils", "Sco disconnected");
                        if (BluetoothScoManager.this.scoStarting) {
                            return;
                        }
                        BluetoothScoManager.this.scoConnected = false;
                        BluetoothScoManager.this.audioManager.stopBluetoothSco();
                        BluetoothScoManager.this.onScoDisconnected();
                        return;
                    }
                    return;
                }
                boolean wasScoConnected = BluetoothScoManager.this.scoConnected;
                if (BluetoothScoManager.this.scoStarting) {
                    BluetoothScoManager.this.scoStarting = false;
                    BluetoothScoManager.this.onHeadsetConnected();
                }
                if (BluetoothScoManager.this.connecting) {
                    BluetoothScoManager.this.connecting = false;
                    BluetoothScoManager.this.scoConnectTimer.cancel();
                }
                if (!wasScoConnected) {
                    BluetoothScoManager.this.notifyScoConnectedOnce();
                }
                str = "Sco connected";
            }
            Log.d("BluetoothHeadsetUtils", str);
        }
    }

    class ScoConnectTimer extends CountDownTimer {
        ScoConnectTimer(long j, long j2) {
            super(j, j2);
        }

        @Override
        public void onFinish() {
            BluetoothScoManager.this.connecting = false;
            BluetoothScoManager.this.audioManager.setMode(0);
            Log.d("BluetoothHeadsetUtils", "\nonFinish fail to connect to headset audio");
        }

        @Override
        public void onTick(long j) {
            BluetoothScoManager.this.audioManager.startBluetoothSco();
            Log.d("BluetoothHeadsetUtils", "\nonTick start bluetooth Sco");
        }
    }

    class ProfileServiceListener implements BluetoothProfile.ServiceListener {
        ProfileServiceListener() {
        }

        @Override
        @TargetApi(11)
        public void onServiceConnected(int i, BluetoothProfile bluetoothProfile) {
            Log.d("BluetoothHeadsetUtils", "Profile listener onServiceConnected");
            BluetoothScoManager.this.headset = (BluetoothHeadset) bluetoothProfile;
            List<BluetoothDevice> connectedDevices = BluetoothScoManager.this.headset.getConnectedDevices();
            if (connectedDevices.size() > 0) {
                BluetoothScoManager.this.device = connectedDevices.get(0);
                BluetoothScoManager.this.onHeadsetConnected();
                BluetoothScoManager.this.connecting = true;
                BluetoothScoManager.this.voiceRecognitionTimer.start();
                Log.d("BluetoothHeadsetUtils", "Start count down");
            }
            BluetoothScoManager.this.context.registerReceiver(BluetoothScoManager.this.profileReceiver, new IntentFilter("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED"));
            BluetoothScoManager.this.context.registerReceiver(BluetoothScoManager.this.profileReceiver, new IntentFilter("android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED"));
        }

        @Override
        public void onServiceDisconnected(int i) {
            Log.d("BluetoothHeadsetUtils", "Profile listener onServiceDisconnected");
            BluetoothScoManager.this.stopBluetooth11();
        }
    }

    class ProfileReceiver extends BroadcastReceiver {
        ProfileReceiver() {
        }

        @Override
        @TargetApi(11)
        public void onReceive(Context context, Intent intent) {
            String str;
            String action = intent.getAction();
            if (action.equals("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")) {
                int intExtra = intent.getIntExtra("android.bluetooth.profile.extra.STATE", 0);
                Log.d("BluetoothHeadsetUtils", "\nAction = " + action + "\nState = " + intExtra);
                if (intExtra == 2) {
                    BluetoothScoManager.this.device = (BluetoothDevice) intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE");
                    BluetoothScoManager.this.connecting = true;
                    BluetoothScoManager.this.voiceRecognitionTimer.start();
                    BluetoothScoManager.this.onHeadsetConnected();
                    str = "Start count down";
                } else {
                    if (intExtra != 0) {
                        return;
                    }
                    if (BluetoothScoManager.this.connecting) {
                        BluetoothScoManager.this.connecting = false;
                        BluetoothScoManager.this.voiceRecognitionTimer.cancel();
                    }
                    BluetoothScoManager.this.device = null;
                    BluetoothScoManager.this.onHeadsetDisconnected();
                    str = "Headset disconnected";
                }
            } else {
                int intExtra2 = intent.getIntExtra("android.bluetooth.profile.extra.STATE", 10);
                Log.d("BluetoothHeadsetUtils", "\nAction = " + action + "\nState = " + intExtra2);
                if (intExtra2 == 12) {
                    Log.d("BluetoothHeadsetUtils", "\nHeadset audio connected");
                    boolean wasScoConnected = BluetoothScoManager.this.scoConnected;
                    if (BluetoothScoManager.this.connecting) {
                        BluetoothScoManager.this.connecting = false;
                        BluetoothScoManager.this.voiceRecognitionTimer.cancel();
                    }
                    if (!wasScoConnected) {
                        BluetoothScoManager.this.notifyScoConnectedOnce();
                    }
                    return;
                }
                if (intExtra2 != 10) {
                    return;
                }
                BluetoothScoManager.this.scoConnected = false;
                BluetoothScoManager.this.headset.stopVoiceRecognition(BluetoothScoManager.this.device);
                BluetoothScoManager.this.onScoDisconnected();
                str = "Headset audio disconnected";
            }
            Log.d("BluetoothHeadsetUtils", str);
        }
    }

    class VoiceRecognitionTimer extends CountDownTimer {
        VoiceRecognitionTimer(long j, long j2) {
            super(j, j2);
        }

        @Override
        public void onFinish() {
            BluetoothScoManager.this.connecting = false;
            Log.d("BluetoothHeadsetUtils", "\nonFinish fail to connect to headset audio");
        }

        @Override
        @TargetApi(11)
        public void onTick(long j) {
            BluetoothScoManager.this.headset.startVoiceRecognition(BluetoothScoManager.this.device);
            Log.d("BluetoothHeadsetUtils", "onTick startVoiceRecognition");
        }
    }

    public BluetoothScoManager(Context context) {
        this.context = context;
        this.audioManager = (AudioManager) this.context.getSystemService("audio");
    }

    private boolean startBluetoothLegacy() {
        Log.d("BluetoothHeadsetUtils", "startBluetooth");
        if (this.bluetoothAdapter == null || !this.audioManager.isBluetoothScoAvailableOffCall()) {
            return false;
        }
        this.context.registerReceiver(this.aclReceiver, new IntentFilter("android.bluetooth.device.action.ACL_CONNECTED"));
        this.context.registerReceiver(this.aclReceiver, new IntentFilter("android.bluetooth.device.action.ACL_DISCONNECTED"));
        this.context.registerReceiver(this.aclReceiver, new IntentFilter("android.media.SCO_AUDIO_STATE_CHANGED"));
        this.audioManager.setMode(2);
        this.connecting = true;
        this.scoConnectTimer.start();
        this.scoStarting = true;
        return true;
    }

    @TargetApi(11)
    private boolean startBluetooth11() {
        Log.d("BluetoothHeadsetUtils", "startBluetooth11");
        return this.bluetoothAdapter != null && this.audioManager.isBluetoothScoAvailableOffCall() && this.bluetoothAdapter.getProfileProxy(this.context, this.serviceListener, 1);
    }

    private void stopBluetoothLegacy() {
        Log.d("BluetoothHeadsetUtils", "stopBluetooth");
        if (this.connecting) {
            this.connecting = false;
            this.scoConnectTimer.cancel();
        }
        this.context.unregisterReceiver(this.aclReceiver);
        this.audioManager.stopBluetoothSco();
        this.audioManager.setMode(0);
    }

    /** Hook: headset became available / SCO starting. (was o()) */
    public abstract void onHeadsetConnected();

    /** Hook: headset disconnected. (was p()) */
    public abstract void onHeadsetDisconnected();

    /** Hook: SCO audio connected — start voice. (was q()) */
    public abstract void onScoConnected();

    /** Hook: SCO audio disconnected — stop voice. (was r()) */
    public abstract void onScoDisconnected();

    /** Start the BT SCO manager. (was s()) */
    public boolean start() {
        if (!this.started) {
            this.started = true;
            this.started = Build.VERSION.SDK_INT < 11 ? startBluetoothLegacy() : startBluetooth11();
        }
        return this.started;
    }

    /** Stop the BT SCO manager. (was v()) */
    public void stop() {
        if (this.started) {
            this.started = false;
            if (Build.VERSION.SDK_INT < 11) {
                stopBluetoothLegacy();
            } else {
                stopBluetooth11();
            }
        }
    }

    @TargetApi(11)
    protected void stopBluetooth11() {
        Log.d("BluetoothHeadsetUtils", "stopBluetooth11");
        if (this.connecting) {
            this.connecting = false;
            this.voiceRecognitionTimer.cancel();
        }
        BluetoothHeadset bluetoothHeadset = this.headset;
        if (bluetoothHeadset != null) {
            bluetoothHeadset.stopVoiceRecognition(this.device);
            this.context.unregisterReceiver(this.profileReceiver);
            this.bluetoothAdapter.closeProfileProxy(1, this.headset);
            this.headset = null;
        }
    }
}
