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
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;


class AODVNetwork {

    private static final String TAG = "connectedcrossroad";
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;

    private static final int MAX_ROUTES = 6;
    private static final int MAX_NEIGHBORS = 3;

    private static final long HELLO_INTERVAL = 5000;
    private static final long QUEUE_LIFETIME = 5000;
    private static final long QUEUE_WAIT_TIME = 5000;

    //self routing info
    private final AODVRoute self;

    //key is address, value is endpointId
    private final HashMap<String, String> addressToEndpointId;

    //key is endpointId, value is route (with address field)
    private final HashMap<String, AODVRoute> routeTable;
    private final HashMap<String, AODVRoute> neighborsTable;

    //queue to store data while waiting for RREP
    private final BlockingQueue<AODVMessage> dataQueue;

    private final ConnectionsClient connectionsClient;

    //Text view to display messages received to user
    private final TextView lastMessageRx;
    //Text view to display number of connected nodes to user
    private final TextView numConnectedText;

    //Thread to periodically send hello messages to neighbors to update information
    private final Thread helloTxThread;
    //Thread to pull from queue and send data, waiting for RREPs if necessary
    private final Thread dataTxThread;

    private boolean searching = false;

    AODVNetwork(String name, ConnectionsClient connectionsClient, TextView numConnectedText, TextView lastMessageRx) {

        this.self = new AODVRoute(null);
        this.self.address = name;

        this.routeTable = new HashMap<>(MAX_ROUTES);
        this.neighborsTable = new HashMap<>(MAX_NEIGHBORS);
        this.addressToEndpointId = new HashMap<>(MAX_NEIGHBORS + MAX_ROUTES);

        this.dataQueue = new LinkedBlockingQueue<>();

        this.lastMessageRx = lastMessageRx;
        this.numConnectedText = numConnectedText;

        this.helloTxThread = new Thread(helloTxRunnable);
        this.dataTxThread = new Thread(dataTxRunnable);

        this.connectionsClient = connectionsClient;

    }

    void start() {
        startAdvertising();
        startDiscovery();

        helloTxThread.start();
        dataTxThread.start();
    }

    //Give device human readable address (one-time at startup before advertising)
    void setAddress(String address) {
        this.self.address = address;
        Log.d(TAG, "setName: set address to " + address);
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
        //Advertising may fail. To keep this demo simple, we don't handle failures.
        connectionsClient.startAdvertising(
                self.address, TAG, connectionLifecycleCallback,
                new AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        );
        Log.d(TAG, "startAdvertising: started advertising");
    }

    void sendMessage(String address, String data) {
        AODVMessage userMessage = initDATA(address, data);
        AODVRoute route = getRouteByAddress(address);
        if (route != null) {
            Log.d(TAG, "sendMessage: Sending AODV DATA to: " + route.address);
            userMessage.header.nextId = route.nextHopId;
            userMessage.header.hopCnt = route.hopCnt;
            userMessage.header.destSeqNum = route.seqNum;
            sendMessage(userMessage);
        } else {
            Log.d(TAG, "sendMessage: Initiating RREQ for route to " + address);
            userMessage.lifetime = System.currentTimeMillis() + QUEUE_LIFETIME;
            //add to queue to be processed when route is available
            dataQueue.add(userMessage);
            AODVMessage rreq = initRREQ(address);
            broadcastMessage(rreq);
        }
    }

    void sendMessage(AODVMessage msg) {
        byte[] bytes = new byte[0];
        try {
            bytes = SerializationHelper.serialize(msg);
        } catch (IOException e) {
            Log.e(TAG, "ERROR! SERIALIZING DATA FAILED");
            e.printStackTrace();
        }
        Payload payload = Payload.fromBytes(bytes);
        connectionsClient.sendPayload(msg.header.nextId, payload);
        Log.d(TAG, "sendMessage: Sent AODV message");
    }

    void broadcastMessage(AODVMessage msg) {
        for (AODVRoute neighbor : neighborsTable.values()) {
            //msg.header.nextAddr = neighbor.address;
            msg.header.nextId = neighbor.nextHopId;
            sendMessage(msg);
        }
        Log.d(TAG, "broadcastMessage: sent AODV broadcast message");
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

    private void handleHELLO(AODVMessage msg) {
        String sendAddr = msg.header.sendAddr;
        String sendId = msg.header.sendId;
        Log.d(TAG, "handleHELLO: Received AODV HELLO from: " + sendAddr);
        if (neighborsTable.containsKey(sendId)) {
            AODVRoute neighbor = neighborsTable.get(sendId);
            //we can only know neighbor Addr from hello messages
            if (neighbor != null) {
                if (!addressToEndpointId.containsKey(sendAddr)) {
                    addressToEndpointId.put(sendAddr, sendId);
                }
                neighbor.seqNum = msg.header.srcSeqNum;
                neighbor.bcastSeqNum = msg.header.bcastSeqNum;
            }
        } else {
            Log.d(TAG, "handleHELLO: Invalid Id for HELLO message");
        }
    }

    private void handleDATA(AODVMessage msg) {
        Log.d(TAG, "handleData: Received AODV DATA message");
        String destAddr = msg.header.destAddr;
        if (destAddr.equals(self.address)) {
            Log.d(TAG, "handleData: DATA reached destination: " + destAddr);
            //do whatever with data, in our case post it to the text view
            updateLastMessageRx(msg.header.srcAddr, msg.payload);
        } else {
            Log.d(TAG, "handleData: DATA in transit to: " + destAddr);
            AODVRoute route = getRouteByAddress(destAddr);
            if (route != null) {
                msg.header.nextId = route.nextHopId;
                sendMessage(msg);
            } else {
                Log.d(TAG, "handleData: DATA error for: " + destAddr);
                String srcAddr = msg.header.srcAddr;
                AODVMessage rerr = initRERR(srcAddr);
                if (rerr != null) {
                    self.seqNum++; //increase seq num for rerr?
                    sendMessage(rerr);
                } else {
                    //nothing can be done, drop message
                    Log.d(TAG, "handleData: dropping message");
                }
            }
        }
    }

    private void handleRREQ(AODVMessage msg) {
        String srcAddr = msg.header.srcAddr;
        String destAddr = msg.header.destAddr;
        Log.d(TAG, "handleRREQ: Received AODV RREQ from: " + srcAddr);
        if (srcAddr.equals(self.address)) {
            return;
        }
        //set up route to src
        AODVRoute srcRoute = getRouteByAddress(srcAddr);
        if (srcRoute == null) {
            srcRoute.address = srcAddr;
            srcRoute.nextHopId = msg.header.sendId;
            srcRoute.nextHopAddr = msg.header.sendAddr;
        }

        AODVRoute destRoute = getRouteByAddress(destAddr);
        //check bcast seq num for route freshness and to prevent loops
        if (self.address.equals(destAddr) || (destRoute != null &&
                msg.header.bcastSeqNum <= destRoute.bcastSeqNum &&
                msg.header.destSeqNum <= destRoute.seqNum)) {
            self.seqNum++; //inc seq num?
            AODVMessage rrep = initRREP(srcAddr);
            if (rrep != null) {
                Log.d(TAG, "handleRREQ: sending RREP to: " + srcAddr);
                sendMessage(rrep);
            } else {
                Log.d(TAG, "handleRREQ: dropping RREP to: " + srcAddr);
            }
        } else {
            //make sure no loops are happening with this
            Log.d(TAG, "handleRREQ: forwarding RREQ to: " + destAddr);
            broadcastMessage(msg);
        }
    }

    private void handleRREP(AODVMessage msg) {
        String srcAddr = msg.header.srcAddr;
        String destAddr = msg.header.destAddr;
        Log.d(TAG, "handleRREP: Received AODV RREP message");
        AODVRoute destRoute = getRouteByAddress(destAddr);
        if (self.address.equals(destAddr)) {
            Log.d(TAG, "handleRREP: RREP reached destination");
        } //check seq nums to only forward one rrep
        else if (destRoute != null && msg.header.srcSeqNum <= destRoute.seqNum) {
            //create route to src from here?
            Log.d(TAG, "handleRREP: Forwarding RREP to next hop");
            msg.header.nextId = destRoute.nextHopId;
            msg.header.hopCnt--;
            sendMessage(msg);
        } else {
            Log.d(TAG, String.format("handleRREP: ERROR in RREP from %s to %s", srcAddr, destAddr));
            AODVMessage rerr = initRERR(srcAddr);
            if (rerr != null) {
                sendMessage(rerr);
            } else {
                Log.d(TAG, "handleRREP: dropping RERR to: " + srcAddr);
            }
        }
    }

    private void handleRERR(AODVMessage msg) {
        String srcAddr = msg.header.srcAddr;
        String destAddr = msg.header.destAddr;
        String sendAddr = msg.header.sendAddr;
        Log.d(TAG, "handleRERR: Received AODV RERR message from: " + sendAddr);
        removeRouteByAddress(srcAddr);
        AODVRoute route = getRouteByAddress(destAddr);
        if (destAddr.equals(self.address)) {
            Log.d(TAG, "handleRERR: RERR reached destination");
        } else if (route != null) {
            Log.d(TAG, "handleRERR: Forwarding RERR to next hop");
            msg.header.nextId = route.nextHopId;
            //msg.header.sendAddr = self.address;
            sendMessage(msg);
        } else {
            Log.d(TAG, "handleRERR: Dropping RERR");
        }
    }

    //always broadcast these to all neighbors
    private AODVMessage initHELLO() {
        Log.d(TAG, "initHELLO: initiating HELLO message");
        AODVMessage msg = new AODVMessage();
        msg.type = AODVMessageType.HELO;
        msg.header.srcAddr = self.address;
        msg.header.srcSeqNum = self.seqNum;
        msg.header.bcastSeqNum = self.bcastSeqNum;
        msg.header.sendAddr = self.address;
        msg.header.hopCnt = 0;
        return msg;
    }

    //ids need to get set somewhere else;
    private AODVMessage initDATA(String destAddr, String data) {
        Log.d(TAG, "initDATA: initiating DATA message to: " + destAddr);
        AODVMessage msg = new AODVMessage();
        msg.type = AODVMessageType.DATA;
        msg.header.srcAddr = self.address;
        msg.header.srcSeqNum = self.seqNum;
        msg.header.destAddr = destAddr;
        msg.header.sendAddr = self.address;
        msg.payload = data;
        return msg;
    }

    //always broadcast these to all neighbors
    private AODVMessage initRREQ(String destAddr) {
        Log.d(TAG, "initRREQ: initiating RREQ message for: " + destAddr);
        AODVMessage msg = new AODVMessage();
        msg.type = AODVMessageType.RREQ;
        msg.header.srcAddr = self.address;
        msg.header.srcSeqNum = self.seqNum;
        msg.header.bcastSeqNum = ++self.bcastSeqNum; //inc on each rreq
        msg.header.destAddr = destAddr;
        msg.header.sendAddr = self.address;
        //may still have active route but need updated information
        AODVRoute route = getRouteByAddress(destAddr);
        if (route != null) {
            msg.header.destSeqNum = route.seqNum;
            msg.header.hopCnt = route.hopCnt;
        } else {
            msg.header.destSeqNum = 0;
            msg.header.hopCnt = 0;
        }
        return msg;
    }

    private AODVMessage initRREP(String destAddr) {
        Log.d(TAG, "initRREP: initiating RREP message for: " + destAddr);
        AODVMessage msg = null;
        AODVRoute route = getRouteByAddress(destAddr);
        if (route != null) {
            msg = new AODVMessage();
            msg.type = AODVMessageType.RREP;
            msg.header.srcAddr = self.address;
            msg.header.srcSeqNum = self.seqNum;
            msg.header.destAddr = route.address;
            msg.header.destSeqNum = route.seqNum;
            msg.header.nextId = route.nextHopId;
            msg.header.hopCnt = route.hopCnt;
        }
        return msg;
    }

    private AODVMessage initRERR(String destAddr) {
        Log.d(TAG, "initRERR: initiating RERR message for: " + destAddr);
        AODVMessage msg = null;
        //need an active route to dest;
        AODVRoute route = getRouteByAddress(destAddr);
        if (route != null) {
            msg = new AODVMessage();
            msg.type = AODVMessageType.RERR;
            msg.header.srcAddr = self.address;
            msg.header.srcSeqNum = self.seqNum;
            msg.header.destAddr = route.address;
            msg.header.destSeqNum = route.seqNum;
            msg.header.nextId = route.nextHopId;
            msg.header.hopCnt = Byte.MAX_VALUE;
        }
        return msg;
    }

    private AODVRoute getRouteByAddress(String address) {
        AODVRoute route = null;
        String endpointId = addressToEndpointId.get(address);
        if (routeTable.containsKey(endpointId)) {
            route = routeTable.get(endpointId);
        } else if (neighborsTable.containsKey(endpointId)) {
            route = neighborsTable.get(endpointId);
        }
        return route;
    }

    private void removeRouteByAddress(String address) {
        String endpointId = addressToEndpointId.get(address);
        if (routeTable.containsKey(endpointId)) {
            routeTable.remove(endpointId);
        } else if (neighborsTable.containsKey(endpointId)) {
            neighborsTable.remove(endpointId);
        }
        addressToEndpointId.remove(address);
    }

    public int getLocalSize() {
        return 1 + neighborsTable.size();
    }

    //numbers don't really mean anything, for compatibility with C version
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

        String srcAddr; //origination of packet
        String destAddr; //final destination of packet
        String nextAddr;
        String nextId; //endpointId for routing
        String sendAddr;
        String sendId; //endpointId for routing, this will be set in onReceivedPayload;
        int srcSeqNum;
        int bcastSeqNum;
        int destSeqNum;
        byte hopCnt;

        AODVHeader() {
            this.srcAddr = null;
            this.destAddr = null;
            this.nextAddr = null;
            this.nextId = null;
            this.sendAddr = null;
            this.sendId = null;
            this.srcSeqNum = 0;
            this.bcastSeqNum = 0;
            this.destSeqNum = 0;
            this.hopCnt = 0;
        }

    }

    private static class AODVMessage implements Serializable {

        AODVMessageType type;
        AODVHeader header;
        String payload;
        long lifetime;

        AODVMessage() {
            type = AODVMessageType.NONE;
            header = new AODVHeader();
            payload = null;
            lifetime = 0L;
        }

    }

    private static class AODVRoute {

        String address; //final destination of route
        String id;
        String nextHopAddr;
        String nextHopId; //for routing control
        int seqNum;
        int bcastSeqNum;
        byte hopCnt;
        long timeout;

        AODVRoute(String id) {
            this.address = null;
            this.id = id;
            this.nextHopAddr = null;
            this.nextHopId = null;
            this.seqNum = 0;
            this.bcastSeqNum = 0;
            this.hopCnt = 0;
            this.timeout = 0L;
        }

    }

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

    private final Runnable helloTxRunnable = new Runnable() {
        @Override
        public void run() {
            while (!Thread.interrupted()) {
                AODVMessage helloMsg = initHELLO();
                try {
                    broadcastMessage(helloMsg);
                    Thread.sleep(HELLO_INTERVAL);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    };

    private final Runnable dataTxRunnable = new Runnable() {
        @Override
        public void run() {
            while(!Thread.interrupted()) {
                AODVMessage msg = null;
                try {
                    Log.d(TAG, "dataTxRunnable: pulling message from queue");
                    msg = dataQueue.poll(QUEUE_WAIT_TIME, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (msg != null) {
                    String endpointId = addressToEndpointId.get(msg.header.destAddr);
                    if (endpointId != null) {
                        msg.header.nextId = endpointId;
                        if (neighborsTable.containsKey(endpointId)) {
                            Log.d(TAG, "dataTxRunnable: sending data to neighbor from queue");
                            sendMessage(msg);
                        } else if (routeTable.containsKey(endpointId)) {
                            msg.header.nextId = Objects.requireNonNull(routeTable.get(endpointId)).nextHopId;
                            Log.d(TAG, "dataTxRunnable: sending data to route from queue");
                            sendMessage(msg);
                        } else if (msg.lifetime < System.currentTimeMillis()) {
                            //add message back to queue because it has not expired and rrep may not have returned
                            dataQueue.add(msg);
                        }
                        //else drop message because time expired and route could not be found
                    }
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
            if (!neighborsTable.containsKey(endpointId)) {
                Log.d(TAG, "onConnectionInitiated: accepting connection");
                connectionsClient.acceptConnection(endpointId, payloadCallback);
                //connectionInfo.getEndpointName(); //this could reduce need for some address fields / hello messages
            } else {
                Log.d(TAG, "onConnectionInitiated: invalid endpoint");
            }
        }

        /**
         * Called after a connection request is accepted. If it was successful, verify again
         * that the device isn't already in the network. If it's already in the network, disconnect
         * from it. Else, officially add it as a node in the network
         * @param endpointId endpoint (device) that we just connected to
         * @param result contains status codes (e.g. success)
         */
        @Override
        public void onConnectionResult(@NonNull String endpointId, ConnectionResolution result) {
            //startDiscovery(); //restart discovery
            if (result.getStatus().isSuccess()) {
                Log.i(TAG, "onConnectionResult: Connection successful");
                if (neighborsTable.containsKey(endpointId)) {
                    Log.i(TAG, "onConnectionResult: Neighbor already connected: " + endpointId);
                    //connectionsClient.disconnectFromEndpoint(endpointId);
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
                    Log.d(TAG, "onConnectionResult: Too many neighbors: " + endpointId);
                }
            } else {
                Log.i(TAG, "onConnectionResult: Connection failed, retrying");
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
                    Log.d(TAG, "onPayloadReceived: Received AODV message type " + msg.type.getValue());
                    msg.header.sendId = endpointId;
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

}
