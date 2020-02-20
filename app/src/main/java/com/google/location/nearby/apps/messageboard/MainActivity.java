package com.google.location.nearby.apps.messageboard;

import static java.nio.charset.StandardCharsets.UTF_8;

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
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate.Status;
import com.google.android.gms.nearby.connection.Strategy;

import java.util.ArrayList;

/*

TODO:

1) Have sample app show names of the connected devices in some sort of list view
2) automatically search and connect to other devices
  - follow my advertiser/discoverer strategy I wrote up
 */

/** Activity controlling the Message Board */
public class MainActivity extends AppCompatActivity {

  private static final String TAG = "MessageBoard";

  private static final String[] REQUIRED_PERMISSIONS =
      new String[] {
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
      };

  private static final int REQUEST_CODE_REQUIRED_PERMISSIONS = 1;

  private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;

  // Our handle to Nearby Connections
  private ConnectionsClient connectionsClient;

  // Our randomly generated name
  private final String codeName = CodenameGenerator.generate();

  private ArrayList<String> endpointIds = new ArrayList<>();
  private String discovererId = "";
  private String advertiserId = "";
  private int numInNetwork = 1;
  private Button sendMessageButton;

  private TextView numConnectedText;
  private TextView deviceNameText;
  private TextView lastMessage;
  private EditText sendMessageText;

  // Callbacks for receiving payloads
  private final PayloadCallback payloadCallback =
      new PayloadCallback() {
        @Override
        public void onPayloadReceived(String endpointId, Payload payload) {
            String msg = new String(payload.asBytes(), UTF_8);
            Log.d(TAG,msg);
            if (msg.startsWith("MSG 0: ")) {
              numInNetwork += Integer.parseInt(msg.substring(7));
              setNumInNetwork();
              msg = "MSG 1: " + numInNetwork;
              sendMessage(msg, "");
            }
            else if (msg.startsWith("MSG 1: ")){
              numInNetwork = Integer.parseInt(msg.substring(7));
              setNumInNetwork();
              sendMessage(msg, endpointId);
            }
            else if (msg.startsWith("MSG 2: ")) {
              int disEmpty = Integer.parseInt(msg.substring(7,8));
              int adEmpty = Integer.parseInt(msg.substring(8,9));
              if (disEmpty == 0 || !discovererId.equals("")) {
                advertiserId = endpointId;
                Log.d(TAG,"Stopping discovery");
                connectionsClient.stopDiscovery();
              }

              else if (adEmpty == 0 || !advertiserId.equals("")) {
                discovererId = endpointId;
                Log.d(TAG, "Stopping advertising");
                if (numInNetwork == 1) {
                  connectionsClient.stopDiscovery();
                  // TODO: this is a temporary hacky fix for preventing a cycle in my network
                  // I think this solution would work in theory only if one giant network was formed
                  // if two smaller networks are formed, they wouldn't be able to connect to each other
                  // Look into echoing a message and then disconnecting if necessary.
                }
                connectionsClient.stopAdvertising();
                sendNumInNetwork(endpointId);
              }
              else {
                String name = msg.substring(9);
                if (name.compareTo(codeName) < 0) {
                  advertiserId = endpointId;
                  Log.d(TAG,"Stopping discovery");
                  connectionsClient.stopDiscovery();
                }
                else {
                  discovererId = endpointId;
                  Log.d(TAG, "Stopping advertising");
                  if (numInNetwork == 1) {
                    connectionsClient.stopDiscovery();
                    // TODO: this is a temporary hacky fix for preventing a cycle in my network
                    // I think this solution would work in theory only if one giant network was formed
                    // if two smaller networks are formed, they wouldn't be able to connect to each other
                    // Look into echoing a message and then disconnecting if necessary.
                  }
                  connectionsClient.stopAdvertising();
                  sendNumInNetwork(endpointId);
                }
              }
            }
            else {
              sendMessage(msg, endpointId);
            }
        }

        @Override
        public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) {
          if (update.getStatus() == Status.SUCCESS) {
              Log.d(TAG, "Message successfully received.");
          }
        }
      };

  // Callbacks for finding other devices
  private final EndpointDiscoveryCallback endpointDiscoveryCallback =
      new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
          if (!info.getEndpointName().equals(codeName)) {
            Log.i(TAG, "onEndpointFound: endpoint found, connecting");
            connectionsClient.requestConnection(codeName, endpointId, connectionLifecycleCallback);
          }
          else {
            Log.d(TAG, "tried to connect to self...");
          }
        }

        @Override
        public void onEndpointLost(String endpointId) {}
      };

  // Callbacks for connections to other devices
  private final ConnectionLifecycleCallback connectionLifecycleCallback =
      new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo) {
              Log.i(TAG, "onConnectionInitiated: accepting connection");
              connectionsClient.acceptConnection(endpointId, payloadCallback);
          }

        @Override
        public void onConnectionResult(String endpointId, ConnectionResolution result) {
          if (result.getStatus().isSuccess()) {
            Log.i(TAG, "onConnectionResult: connection successful");

            sendConnectInfo(endpointId);


          } else {
            if (endpointId.equals(discovererId)) {
              discovererId = "";
            }
            if (endpointId.equals(advertiserId)) {
              discovererId = "";
            }
            Log.i(TAG, "onConnectionResult: connection failed");
          }
        }

        @Override
        public void onDisconnected(String endpointId) {
          if (endpointId.equals(discovererId)) {
            discovererId = "";
            startDiscovery();
          }
          if (endpointId.equals(advertiserId)) {
            advertiserId = "";
            startAdvertising();
          }
          Log.i(TAG, "onDisconnected: disconnected from network");
        }
      };
  private String foundAdvertiserId;

  @Override
  protected void onCreate(@Nullable Bundle bundle) {
    super.onCreate(bundle);
    setContentView(R.layout.activity_main);

    sendMessageButton = findViewById(R.id.sendMessageButton);
    deviceNameText = findViewById(R.id.deviceName);
    numConnectedText = findViewById(R.id.numConnectionsText);
    lastMessage = findViewById(R.id.lastMessage);
    sendMessageText = findViewById(R.id.editTextField);


    deviceNameText.setText(String.format("Device name: %s", codeName));

    sendMessageButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        sendMessage(String.format("%s: %s",codeName, sendMessageText.getText()), "");
      }
    });

    connectionsClient = Nearby.getConnectionsClient(this);
    startAdvertising();
    startDiscovery();
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

  /** Returns true if the app was granted all the permissions. Otherwise, returns false. */
  private static boolean hasPermissions(Context context, String... permissions) {
    for (String permission : permissions) {
      if (ContextCompat.checkSelfPermission(context, permission)
          != PackageManager.PERMISSION_GRANTED) {
        return false;
      }
    }
    return true;
  }

  /** Handles user acceptance (or denial) of our permission request. */
  @CallSuper
  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
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


  /** Disconnects from the opponent and reset the UI. */
  public void disconnect(View view) {
    for (String id: endpointIds) {
      connectionsClient.disconnectFromEndpoint(id);
    }
  }


  /** Starts looking for other players using Nearby Connections. */
  private void startDiscovery() {
    // Note: Discovery may fail. To keep this demo simple, we don't handle failures.
    connectionsClient.startDiscovery(
            getPackageName(), endpointDiscoveryCallback,
            new DiscoveryOptions.Builder().setStrategy(STRATEGY).build());
  }

  /** Broadcasts our presence using Nearby Connections so other players can find us. */
  private void startAdvertising() {
    // Note: Advertising may fail. To keep this demo simple, we don't handle failures.
    connectionsClient.startAdvertising(
            codeName, getPackageName(), connectionLifecycleCallback,
            new AdvertisingOptions.Builder().setStrategy(STRATEGY).build());
  }


  /** Sends the user's selection of rock, paper, or scissors to the opponent. */
  private void sendMessage(String message, String ignoreId) {
    Log.d(TAG,String.format("name: %s, discoverer id: %s, advertiser id: %s, ignore id: %s",codeName,discovererId,advertiserId,ignoreId));
     if (!advertiserId.equals("") && !advertiserId.equals(ignoreId)) {
       Log.d(TAG,"Sending message to the advertiser");
       connectionsClient.sendPayload(
               advertiserId, Payload.fromBytes(message.getBytes(UTF_8)));
     }
    if (!discovererId.equals("") && !discovererId.equals(ignoreId)) {
      Log.d(TAG,"Sending message to the discoverer");
      connectionsClient.sendPayload(
              discovererId, Payload.fromBytes(message.getBytes(UTF_8)));
    }

    if (!message.startsWith("MSG")) {
      Log.d(TAG,"setting message board");
      lastMessage.setText(message);
    }
  }

  private void setLastMessage(String message) {
    lastMessage.setText(message);
  }

  private void sendNumInNetwork(String endpointId) {
    Log.d(TAG,"Sending number of nodes in network...");
    String msg = "MSG 0: " + numInNetwork;
    connectionsClient.sendPayload(endpointId,Payload.fromBytes(msg.getBytes(UTF_8)));

  }

  private void sendConnectInfo(String endpointId) {
    Log.d(TAG,"Sending info about current endpoints...");
    String msg = String.format("MSG 2: %d%d%s",discovererId.isEmpty()? 1 : 0,advertiserId.isEmpty()? 1: 0,codeName);
    connectionsClient.sendPayload(endpointId,Payload.fromBytes(msg.getBytes(UTF_8)));

  }

  private void setNumInNetwork() {
      numConnectedText.setText(String.format("Devices in network: %d",numInNetwork));
  }
}
