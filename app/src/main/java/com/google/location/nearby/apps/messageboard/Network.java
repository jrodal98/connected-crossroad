package com.google.location.nearby.apps.messageboard;

import android.util.Log;

import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.Payload;

import java.io.IOException;
import java.util.HashSet;

public class Network {
    private static final String TAG = "MessageBoard";
    private Node n1;
    private Node n2;
    private String name;
    private ConnectionsClient connectionsClient;

    public Network(String name, ConnectionsClient connectionsClient) {
        n1 = new Node();
        n2 = new Node();
        this.name = name;
        this.connectionsClient = connectionsClient;
    }

    public int getSize() {
        return n1.getSize() + n2.getSize() + 1;
    }

    public boolean addNode(String id) throws IOException {
        if (!n1.isAssigned()) {
            n1.setId(id);
            sendNodesInNetwork(n1,n2);
        }
        else if (!n2.isAssigned()) {
            n2.setId(id);
            connectionsClient.stopAdvertising();
            connectionsClient.stopDiscovery();
            Log.d(TAG, "Stopping advertising and discovery");
            sendNodesInNetwork(n2,n1);
        }
        else {
            return false;
        }
        return true;
    }

    public boolean contains(String id) {
        return n1.contains(id) || n2.contains(id);
    }


    public boolean remove(String id) {
        if (n1.is(id)) {
            n1.clear();
        }
        else if (n2.is(id)) {
            n2.clear();
        }
        else {
            return false;
        }
        return true;
    }

    public boolean isComplete() {
        return n1.isAssigned() && n2.isAssigned();
    }

    public String getId1() {
       return n1.getId();
    }

    public String getId2() {
        return n2.getId();
    }

//    String otherId = network.setEndpoints(endpointId, ids);
//    setNumInNetwork();
//                  if (!otherId.isEmpty()) {
//        sendNodesInNetwork(endpointId, otherId);
//    }
    public boolean setEndpoints(String id, HashSet<String> ids) throws IOException {
        Log.d(TAG, String.format("Setting and sending endpoints for %s", id));
        if (n1.is(id)) {
            n1.setEndpoints(ids);
            sendNodesInNetwork(n1,n2);
        }
        else if (n2.is(id)) {
            n2.setEndpoints(ids);
            sendNodesInNetwork(n2,n1);
        }
        else {
            return false;
        }
        return true;
    }
    private void sendNodesInNetwork(Node from, Node to) throws IOException {
        if (to.isAssigned()) {
            Log.d(TAG,String.format("Sending nodes connected from %s to %s", from.getId(), to.getId()));
            connectionsClient.sendPayload(to.getId(), Payload.fromBytes(SerializationHelper.serialize(from.getEndpoints())));
        }

    }

    public void sendMessage(String message, String ignoreId) throws IOException {
        Log.d(TAG,String.format("name: %s, id1: %s, id2: %s, ignore id: %s",name, n1.getId(), n2.getId(),ignoreId));
        if (n1.isAssigned() && !n1.is(ignoreId)) {
            Log.d(TAG,"Sending message to n1");
            connectionsClient.sendPayload(
                    n1.getId(), Payload.fromBytes(SerializationHelper.serialize(message)));
        }
        if (n2.isAssigned() && !n2.is(ignoreId)) {
            Log.d(TAG,"Sending message to the n2");
            connectionsClient.sendPayload(
                    n2.getId(), Payload.fromBytes(SerializationHelper.serialize(message)));
        }

    }

}
