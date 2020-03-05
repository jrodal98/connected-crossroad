package com.google.location.nearby.apps.messageboard;

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

import java.io.IOException;
import java.util.HashSet;

/*

TODO: keep different sets of the endpoints connected to the discoverer and the endpoints connected to the advertiser
When you disconnect from one, you know what is no longer in the network and the new size of the network

rename advertiser to discovered
rename discoverer to advertised

More intuitive
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

  private Node advertisedTo;
  private Node discoveredFrom;
  private Button sendMessageButton;

  private TextView numConnectedText;
  private TextView deviceNameText;
  private TextView lastMessage;
  private EditText sendMessageText;


  private void setDiscoveredFrom(String endpointId) throws IOException {
    discoveredFrom.setId(endpointId);
    sendNodesInNetwork(advertisedTo, discoveredFrom);
    Log.d(TAG,"Stopping discovery");
    connectionsClient.stopDiscovery();
  }

  private void setAdvertisedTo(String endpointId) throws IOException {
    advertisedTo.setId(endpointId);
    sendNodesInNetwork(discoveredFrom, advertisedTo);
    Log.d(TAG, "Stopping advertising");
    connectionsClient.stopAdvertising();
  }
  /*
  Handles connecting two networks together
   */
  private void handleMsg2(String msg, String endpointId) throws IOException {
    boolean otherNodeAdAssigned = Integer.parseInt(msg.substring(7,8)) == 1;
    boolean otherNodeDisAssigned = Integer.parseInt(msg.substring(8,9)) == 1;
    // if we have not discoverered and the other node has discovered and hasn't advertised
    if (!discoveredFrom.isAssigned() && otherNodeDisAssigned && !otherNodeAdAssigned) {
        setDiscoveredFrom(endpointId);
    }
    // if we have not advertised and the other node has advertised and hasn't discoverered
    else if (!advertisedTo.isAssigned() && otherNodeAdAssigned && !otherNodeDisAssigned){
      setAdvertisedTo(endpointId);
    }
    else if ((!otherNodeDisAssigned && !discoveredFrom.isAssigned()) && (!otherNodeAdAssigned && !advertisedTo.isAssigned())) {
      Log.d(TAG, "handleMsg2: Both devices can register as advertisers or discoverers");
      String name = msg.substring(9);
      if (name.compareTo(codeName) < 0) {
        setDiscoveredFrom(endpointId);
      }
      else {
        setAdvertisedTo(endpointId);
      }
    }
    else {
      Log.d(TAG, "handleMsg2: Couldn't properly handle who should become an advertiser and who should become a discoverer :(");
    }
  }

  // Callbacks for receiving payloads
  private final PayloadCallback payloadCallback =
          new PayloadCallback() {
            @Override
            public void onPayloadReceived(String endpointId, Payload payload) {
              try {
                Object deserialized = SerializationHelper.deserialize(payload.asBytes());
                if (deserialized instanceof String) {
                  String msg = (String) deserialized;
                  Log.d(TAG, msg);
                  if (msg.startsWith("MSG 2: ")) {
                    handleMsg2(msg, endpointId);
                  } else {
                      if (msg.startsWith(codeName)) {
                        Log.d(TAG, "onPayloadReceived: CYCLE DETECTED :(");
                      }
                      else {
                        sendMessage(msg, endpointId);
                      }
                  }
                } else {
                  HashSet<String> ids = (HashSet<String>) deserialized;
                  if (advertisedTo.isSameId(endpointId)) {
                    advertisedTo.setEndpoints(ids);
                    setNumInNetwork();
                    if (discoveredFrom.isAssigned()) {
                      sendNodesInNetwork(advertisedTo, discoveredFrom);
                    }

                  }
                  else if (discoveredFrom.isSameId(endpointId)) {
                      discoveredFrom.setEndpoints(ids);
                      setNumInNetwork();
                      if (advertisedTo.isAssigned()) {
                        sendNodesInNetwork(discoveredFrom, advertisedTo);
                      }
                  }
                }
              } catch (IOException | ClassNotFoundException e) {
                e.printStackTrace();
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
          if (!(advertisedTo.contains(endpointId) || discoveredFrom.contains(endpointId) || info.getEndpointName().equals(codeName))) {
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

            try {
              sendConnectInfo(endpointId);
            } catch (IOException e) {
              e.printStackTrace();
            }


          } else {
            if (advertisedTo.isSameId(endpointId)) {
              advertisedTo.clear();
            }
            if (discoveredFrom.isSameId(endpointId)) {
              discoveredFrom.clear();
            }
            Log.i(TAG, "onConnectionResult: connection failed");
          }
        }

        // still needs to be implemented properly! this is just boiler plate that 100% doesn't work
        @Override
        public void onDisconnected(String endpointId) {
          if (advertisedTo.isSameId(endpointId)) {
            advertisedTo.clear();
            try {
              setNumInNetwork();
              sendNodesInNetwork(advertisedTo, discoveredFrom);
            } catch (IOException e) {
              e.printStackTrace();
            }
            startDiscovery();
          }
          if (discoveredFrom.isSameId(endpointId)) {
            discoveredFrom.clear();
            try {
              setNumInNetwork();
              sendNodesInNetwork(advertisedTo, discoveredFrom);
            } catch (IOException e) {
              e.printStackTrace();
            }
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

    advertisedTo = new Node("discoverer");
    discoveredFrom = new Node("advertiser");

    sendMessageButton = findViewById(R.id.sendMessageButton);
    deviceNameText = findViewById(R.id.deviceName);
    numConnectedText = findViewById(R.id.numConnectionsText);
    lastMessage = findViewById(R.id.lastMessage);
    sendMessageText = findViewById(R.id.editTextField);


    deviceNameText.setText(String.format("Device name: %s", codeName));

    sendMessageButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        try {
          sendMessage(String.format("%s: %s",codeName, sendMessageText.getText()), "");
        } catch (IOException e) {
          e.printStackTrace();
        }
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


  /** Sends the message through the network*/
  private void sendMessage(String message, String ignoreId) throws IOException {
    Log.d(TAG,String.format("name: %s, discoverer id: %s, advertiser id: %s, ignore id: %s",codeName, advertisedTo.getId(), discoveredFrom.getId(),ignoreId));
     if (discoveredFrom.isAssigned() && !discoveredFrom.isSameId(ignoreId)) {
       Log.d(TAG,"Sending message to the advertiser");
       connectionsClient.sendPayload(
               discoveredFrom.getId(), Payload.fromBytes(SerializationHelper.serialize(message)));
     }
    if (advertisedTo.isAssigned() && !advertisedTo.isSameId(ignoreId)) {
      Log.d(TAG,"Sending message to the discoverer");
      connectionsClient.sendPayload(
              advertisedTo.getId(), Payload.fromBytes(SerializationHelper.serialize(message)));
    }

    if (!message.startsWith("MSG")) {
      Log.d(TAG,"setting message board");
      lastMessage.setText(message);
    }
  }

  private void sendNodesInNetwork(Node nodeFrom, Node nodeTo) throws IOException {
    Log.d(TAG,String.format("Sending nodes connected from %s to %s", nodeFrom.getType(), nodeTo.getType()));
    connectionsClient.sendPayload(nodeTo.getId(),Payload.fromBytes(SerializationHelper.serialize(nodeFrom.getEndpoints())));

  }

  // need to do some sort of wait such that only one makes the decision
  // don't send the information one the second one until the first one makes its decision
  private void sendConnectInfo(String endpointId) throws IOException {
    Log.d(TAG,"Sending info about current endpoints...");
    String msg = String.format("MSG 2: %d%d%s", advertisedTo.isAssigned()? 1 : 0, discoveredFrom.isAssigned()? 1: 0,codeName);
    connectionsClient.sendPayload(endpointId,Payload.fromBytes(SerializationHelper.serialize(msg)));

  }

  private void setNumInNetwork() {
    int numInNetwork = discoveredFrom.getSize() + advertisedTo.getSize() + 1;
      numConnectedText.setText(String.format("Devices in network: %d",numInNetwork));
  }
}
