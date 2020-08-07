package com.google.location.nearby.apps.connectedcrossroad;

import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;

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
import com.google.android.gms.nearby.connection.Strategy;
import com.google.android.gms.tasks.OnFailureListener;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.Callable;


class AODVNetwork {

    private static final String TAG = "connectedcrossroad";
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;

    private static final int MAX_ROUTES = 6;
    private static final int MAX_NEIGHBORS = 3;

    private static final long HELLO_INTERVAL = 5000;

    //self routing info
    private final AODVRoute self;

    //key is address, value is endpointId
    private final HashMap<String, String> addressToEndpointId;

    //key is endpointId, value is route (with address field)
    private final HashMap<String, AODVRoute> routeTable;
    private final HashMap<String, AODVRoute> neighborsTable;

    private final ConnectionsClient connectionsClient;
    //private final EndpointDiscoveryCallback discoveryCallback;
    //private final ConnectionLifecycleCallback lifecycleCallback;

    private final TextView lastMessageRx;
    private final TextView numConnectedText;

    private final Thread helloTxThread;

    private boolean searching = false;

    AODVNetwork(String name, ConnectionsClient connectionsClient, TextView numConnectedText, TextView lastMessageRx) {

        this.self = new AODVRoute(null);
        this.self.address = name;

        this.routeTable = new HashMap<>(MAX_ROUTES);
        this.neighborsTable = new HashMap<>(MAX_NEIGHBORS);
        this.addressToEndpointId = new HashMap<>();

        this.lastMessageRx = lastMessageRx;
        this.numConnectedText = numConnectedText;

        helloTxThread = new Thread(helloTxRunnable);

        this.connectionsClient = connectionsClient;
        //this.discoveryCallback = endpointDiscoveryCallback;
        //this.lifecycleCallback = connectionLifecycleCallback;

    }

    void start() {
        startAdvertising();
        startDiscovery();

        //periodically send own information to neighbors
        helloTxThread.start();
    }

    void setName(String name) {
        this.self.address = name;
        Log.d(TAG, "setName: set name to " + name);
    }

    void startDiscovery() {
        if (!searching) {
            searching = true;
            connectionsClient.startDiscovery(
                    TAG, endpointDiscoveryCallback,
                    new DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
            );
            Log.d(TAG, "startDiscovery: started discovery");
        }
    }

    void stopDiscovery() {
        if (searching) {
            connectionsClient.stopDiscovery();
            searching = false;
        }
        Log.d(TAG, "stopDiscovery: stopped discovery");
    }

    void startAdvertising() {
        // Note: Advertising may fail. To keep this demo simple, we don't handle failures.
        connectionsClient.startAdvertising(
                self.address, TAG, connectionLifecycleCallback,
                new AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        );
        Log.d(TAG, "startAdvertising: started advertising");
    }

    void sendMessage(String address, String data) throws IOException {
        AODVMessage userMessage = new AODVMessage();
        userMessage.type = AODVMessageType.DATA;
        userMessage.header.srcAddr = self.address;
        userMessage.payload = data;
        if (addressToEndpointId.containsKey(address)) {
            String endpointId = addressToEndpointId.get(address);
            if (neighborsTable.containsKey(endpointId)) {
                AODVRoute route = neighborsTable.get(endpointId);
                if (route != null) {
                    userMessage.header.destId = endpointId;
                    Log.d(TAG, "sendMessage: Sending AODV data to neighbor: " + endpointId + ": " + route.address);
                    sendMessage(userMessage);
                } else {
                    Log.d(TAG, "sendMessage: route is null");
                }
            } else if (routeTable.containsKey(endpointId)) {
                AODVRoute route = routeTable.get(endpointId);
                if (route != null) {
                    String destId = route.nextHopId;
                    userMessage.header.destId = destId;
                    if (neighborsTable.containsKey(destId)) {
                        Log.d(TAG, "sendMessage: Sending to route AODV data to: " + endpointId);
                        sendMessage(userMessage);
                    } else {
                        Log.d(TAG, "sendMessage: No neighbor for route next hop: " + destId);
                    }
                }
                else {
                    Log.d(TAG, "sendMessage: Route is null to: " + endpointId);
                }
            }
        } else {
            Log.i(TAG, "sendMessage: Initiating RREQ for route to " + address);
            //initiate RREQ
            //put data in a queue to wait for RREP?
        }
    }

    void sendMessage(AODVMessage msg) throws IOException {
        byte[] bytes = SerializationHelper.serialize(msg);
        Payload payload = Payload.fromBytes(bytes);
        connectionsClient.sendPayload(msg.header.destId, payload);
        Log.d(TAG, "sendMessage: Sent AODV message");
    }

    private void handleAODVMessage(AODVMessage msg) {
        switch (msg.type) {
            case HELO:
                handleHELLO(msg);
                break;
            case DATA:
                handleDATA(msg);
                break;
            case RREQ:
                handleRREQ(msg);
                break;
            case RREP:
                handleRREP(msg);
                break;
            case RERR:
                handleRERR(msg);
                break;
            default:
                Log.d(TAG, "handleAODVMessage: unknown type");
        }
    }

    private void handleHELLO(AODVMessage helloMsg) {
        String recvAddr = helloMsg.header.srcAddr;
        String recvId = helloMsg.header.srcId;
        Log.d(TAG, "handleHELLO: Received AODV HELLO message from: " + recvAddr + ": " + recvId);
        if (neighborsTable.containsKey(recvId)) {
            AODVRoute neighbor = neighborsTable.get(recvId);
            if (!addressToEndpointId.containsKey(recvAddr)) {
                addressToEndpointId.put(recvAddr, recvId);
            }
            if (neighbor != null) {
                neighbor.destSeqNum = helloMsg.header.srcSeqNum;
                neighbor.bcastSeqNum = helloMsg.header.bcastSeqNum;
            }
        } else {
            Log.d(TAG, "handleHELLO: Invalid Id for HELLO message");
        }
    }

    private void handleDATA(AODVMessage dataMsg) {
        Log.d(TAG, "handleData: Received AODV DATA message");
        updateLastMessageRx(dataMsg.header.srcAddr, dataMsg.payload);
    }

    private void handleRREQ(AODVMessage rreqMsg) {
        Log.d(TAG, "handleRREQ: Received AODV RREQ message");
    }

    private void handleRREP(AODVMessage rrepMsg) {
        Log.d(TAG, "handleRREP: Received AODV RREP message");
    }

    private void handleRERR(AODVMessage rerrMsg) {
        Log.d(TAG, "handleRERR: Received AODV RERR message");
    }

    public int getLocalSize() {
        return 1 + neighborsTable.size();
    }

    //numbers don't really mean anything
    private enum AODVMessageType implements Serializable {
        NONE(0),
        RREQ(120),
        RREP(121),
        RERR(122),
        HELO(123),
        DATA(124);

        private final int id;
        AODVMessageType(int id) { this.id = id; }
        public int getValue() { return id; }
    }

    private static class AODVHeader implements Serializable {

        String srcAddr;
        String srcId;
        String destId;
        int srcSeqNum;
        int destSeqNum;
        int bcastSeqNum;
        short hopCnt;

        AODVHeader() {
            srcAddr = null;
            srcId = null;
            destId = null;
            srcSeqNum = 0;
            destSeqNum = 0;
            bcastSeqNum = 0;
            hopCnt = 0;
        }

    }

    private static class AODVMessage implements Serializable {

        AODVMessageType type;
        AODVHeader header;
        String payload;

        AODVMessage() {
            type = AODVMessageType.NONE;
            header = new AODVHeader();
            payload = null;
        }

    }

    private static class AODVRoute {

        String address;
        String destId;
        String nextHopId;
        int destSeqNum;
        int bcastSeqNum;
        short hopCnt;
        long timeout;

        AODVRoute(String destId) {
            this.address = null;
            this.destId = destId;
            this.nextHopId = null;
            this.destSeqNum = 0;
            this.bcastSeqNum = 0;
            this.hopCnt = 0;
            this.timeout = 0L;
        }

    }

    private final Runnable helloTxRunnable = new Runnable() {
        @Override
        public void run() {
            while (!Thread.interrupted()) {
                AODVMessage helloMsg = new AODVMessage();
                helloMsg.type = AODVMessageType.HELO;
                helloMsg.header.srcAddr = self.address;
                helloMsg.header.srcSeqNum = self.destSeqNum;
                helloMsg.header.bcastSeqNum = self.bcastSeqNum;
                for (AODVRoute neighbor : neighborsTable.values()) {
                    helloMsg.header.destId = neighbor.destId;
                    try {
                        Log.i(TAG, "helloTxRunnable: Sending HELLO message to " + neighbor.destId);
                        sendMessage(helloMsg);
                    } catch (IOException e) {
                        Log.i(TAG, "helloTxRunnable: Send HELLO message failed");
                        e.printStackTrace();
                    }
                }
                try {
                    Thread.sleep(HELLO_INTERVAL);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    };

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback()
    {
        /**
         * Called when an endpoint is found. If the endpoint (device) isn't already in our
         * network, we temporarily stop discovery and send a connection request to the device.
         * Sometimes, this will fail if device A sends device B a request at the same time
         * device B sends device A a request (simultaneous connection clash).
         * If this is the case, one device will send the other device a connection request
         * again.
         * @param endpointId endpoint (device) that has been discovered
         * @param info some information about the device, such as name
         *
         * Add node to routing table
         * Forward node to neighbors if necessary
         */
        @Override
        public void onEndpointFound(@NonNull final String endpointId, @NonNull final DiscoveredEndpointInfo info) {
            //if not already a neighbor and not self
            if (!neighborsTable.containsKey(endpointId)) {
                //stopDiscovery();
                //remove from route table if exists and successful
                Log.d(TAG, "onEndpointFound: connecting to " + endpointId);
                connectionsClient.requestConnection(self.address,
                                                    endpointId,
                                                    connectionLifecycleCallback
                ).addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        //ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR;
                        Log.d(TAG, "onEndpointFound: Connection request failure " + e.getMessage());

                        // request connection again on one of the devices
                        // 8012: STATUS_ENDPOINT_IO_ERROR is the simultaneous connection request error
                        if (e.getMessage().startsWith("8012") && self.address.compareTo(info.getEndpointName()) < 0) {
                            Log.d(TAG, "onEndpointFound: Sending another connection request.");
                            connectionsClient.requestConnection(self.address, endpointId, connectionLifecycleCallback);
                        }
                    }
                });
            } else {
                Log.d(TAG, "onEndpointFound: Endpoint is already a neighbor");
            }
        }

        /**
         * Shouldn't need this?
         */
        @Override
        public void onEndpointLost(@NonNull String endpointId) {
            Log.d(TAG, "onEndpointLost: " + endpointId);
        }
    };

    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback()
    {
        /**
         * Called when a connection request has been received. Reject the request if the
         * device is in the network, accept otherwise.
         * @param endpointId endpoint (device) that sent the request
         * @param connectionInfo some info about the device (e.g. name)
         */
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo connectionInfo) {
            if (neighborsTable.containsKey(endpointId)) {
                Log.i(TAG, "onConnectionInitiated: invalid endpoint");
            } else {
                Log.i(TAG, "onConnectionInitiated: accepting connection");
                connectionsClient.acceptConnection(endpointId, payloadCallback);
            }
        }

        /**
         * Called after a connection request is accepted. If it was successful, verify again
         * that the device isn't already in the network. If it's already in the network, disconnect
         * from it. Else, officially add it as a node in the network
         * @param endpointId endpoint (device) that we just connected to
         * @param result contains status codes (e.g. success)
         *
         *
         *
         * UPDATE ROUTING TABLE HERE
         *
         */
        @Override
        public void onConnectionResult(@NonNull String endpointId, ConnectionResolution result) {
            //startDiscovery(); // restart discovery
            if (result.getStatus().isSuccess()) {
                Log.i(TAG, "onConnectionResult: connection successful");
                try {
                    if (neighborsTable.containsKey(endpointId)) {
                        Log.i(TAG, "onConnectionResult: neighbor already connected: " + endpointId);
                        connectionsClient.disconnectFromEndpoint(endpointId);
                    } else if (neighborsTable.size() < MAX_NEIGHBORS) {
                        AODVRoute newNeighbor = routeTable.remove(endpointId); //remove route if now neighbor
                        if (newNeighbor == null) {
                            newNeighbor = new AODVRoute(endpointId);
                        }
                        neighborsTable.put(endpointId, newNeighbor);
                        Log.d(TAG, String.format("onConnectionResult: %s added to network", endpointId));
                        try {
                            updateDevicesConnected();
                        } catch (Exception e) {
                            Log.d(TAG, "onConnectionResult: Error updating UI from callable");
                        }
                    } else {
                        Log.d(TAG, "onConnectionResult: too many neighbors: " + endpointId);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                Log.i(TAG, "onConnectionResult: connection failed");
                connectionsClient.requestConnection(self.address, endpointId, connectionLifecycleCallback);
            }
        }

        /**
         * Remove from routing table
         */
        @Override
        public void onDisconnected(@NonNull String endpointId) {
            Log.i(TAG, "onDisconnected: disconnected from " + endpointId);
            neighborsTable.remove(endpointId);
            try {
                updateDevicesConnected();
            } catch (Exception e) {
                Log.d(TAG, "onDisconnected: Error updating UI from callable");
            }
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback()
    {
        /**
         * Handle incoming payloads. First, we deserialize the payload and determine
         * whether it is a set containing endpoint ids (new devices in the network)
         * or if it is a message from another device. Once we process the message/endpoints,
         * we forward the data to the rest of the network
         * @param endpointId The device who sent us the payload
         * @param payload the payload, which contains either a message or endpoints
         *
         *
         *
         * Deserialize payload
         * determine type of message (control, data) (enum?)
         * handle control message or forward data
         * display data if destination
         */
        @Override
        public void onPayloadReceived(@NonNull String endpointId, Payload payload) {
            try {
                Object deserialized = SerializationHelper.deserialize(payload.asBytes());
                if (deserialized instanceof AODVMessage) {
                    AODVMessage msg = (AODVMessage) deserialized;
                    Log.d(TAG, "onPayloadReceived: Received AODV message of type " + msg.type.getValue());
                    msg.header.srcId = endpointId;
                    handleAODVMessage(msg);
                } else {
                    Log.d(TAG, "onPayloadReceived: Type of payload unknown");
                }
            } catch (IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        }

        /**
         * Shouldn't need to do anything here
         */
        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, PayloadTransferUpdate update) {
            if (update.getStatus() == PayloadTransferUpdate.Status.SUCCESS) {
                Log.d(TAG, "onPayloadTransferUpdate: Message received successfully");
            }
        }
    };

    void updateDevicesConnected() {
        int localSize = getLocalSize();
        String display = String.format(Locale.US, "Devices in local network: %d", localSize);
        numConnectedText.setText(display);
        Log.d(TAG, "updateDevicesConnected: " + display);
    }

    void updateLastMessageRx(String address, String message) {
        String display = String.format("%s: %s", address, message);
        lastMessageRx.setText(display);
        Log.d(TAG, "updateLastMessageRx: " + display);
    }

}
