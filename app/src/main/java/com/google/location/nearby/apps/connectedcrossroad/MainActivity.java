package com.google.location.nearby.apps.connectedcrossroad;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.ConnectionsClient;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Activity controlling the Message Board
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "connectedcrossroad";
    //private static final String LATENCY = "latency_tag";

    private static final String[] REQUIRED_PERMISSIONS =
            new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
            };

    private static final int REQUEST_CODE_REQUIRED_PERMISSIONS = 1;

    // Our handle to Nearby Connections
    private ConnectionsClient connectionsClient;

    // Our randomly generated device name
    private static final String defaultName = "0";

    //private Network network;
    private AODVNetwork network;

    private Button sendMessageButton;
    private Button setAddressButton;

    private TextView numConnectedText;
    private TextView deviceNameText;
    private TextView lastMessageTx;
    private TextView lastMessageRx;

    private EditText sendMessageText;
    private EditText sendAddressText;
    private EditText setAddressText;

    @Override
    protected void onCreate(@Nullable Bundle bundle) {

        super.onCreate(bundle);
        setContentView(R.layout.activity_main);

        sendMessageButton = findViewById(R.id.sendMessageButton);
        setAddressButton = findViewById(R.id.setAddressButton);

        deviceNameText = findViewById(R.id.deviceName);
        numConnectedText = findViewById(R.id.numConnectionsText);
        lastMessageTx = findViewById(R.id.lastMessageTx);
        lastMessageRx = findViewById(R.id.lastMessageRx);

        sendMessageText = findViewById(R.id.editTextField);
        sendAddressText = findViewById(R.id.editAddressField);
        setAddressText = findViewById(R.id.setAddressField);

        deviceNameText.setText(String.format("Device name: %s", defaultName));

        sendMessageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    sendMessage(sendAddressText.getText().toString(), sendMessageText.getText().toString());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        connectionsClient = Nearby.getConnectionsClient(this);
        network = new AODVNetwork(defaultName, connectionsClient, numConnectedText, lastMessageRx);
        //network = new Network(codeName, connectionsClient, endpointDiscoveryCallback, connectionLifecycleCallback);

        setAddressButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String address = setAddressText.getText().toString();
                network.setName(address);
                deviceNameText.setText(String.format("Device name: %s", address));
                //disable the button so it can't be set again
                setAddressButton.setEnabled(false);
                //start network operations once address is set
                network.start();
            }
        });

    }

    @Override
    protected void onStart() {

        super.onStart();

        if (!hasPermissions(this, REQUIRED_PERMISSIONS)) {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_REQUIRED_PERMISSIONS);
        }

    }

    @Override
    protected void onStop() {

        connectionsClient.stopAllEndpoints();
        super.onStop();

    }

    /**
     * Returns true if the app was granted all the permissions. Otherwise, returns false.
     */
    private static boolean hasPermissions(Context context, String... permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) !=
                    PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Handles user acceptance (or denial) of our permission request.
     */
    @CallSuper
    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQUEST_CODE_REQUIRED_PERMISSIONS) {
            return;
        }

        for (int grantResult : grantResults) {
            if (grantResult == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(this, R.string.error_missing_permissions, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        recreate();
    }

    private void sendMessage(String id, String msg) throws IOException {
        network.sendMessage(id, msg);
        Log.d(TAG, "sendMessage: Sent message");
        lastMessageTx.setText(String.format("%s: %s", id, msg));
    }

}
