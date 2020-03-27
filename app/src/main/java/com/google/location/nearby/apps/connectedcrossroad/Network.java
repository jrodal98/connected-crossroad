package com.google.location.nearby.apps.connectedcrossroad;

import android.util.Log;

import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.Payload;

import java.io.IOException;
import java.util.HashSet;


/*
Class that operates on and connects node objects.

Consider the following example, where O is the device the code is running on, X's are the devices
the current device is directly connected to, and Y's are other devices in the network:

Y-Y-X-O-X-Y-Y-Y

This Network class enables us to communicate with the X nodes, and then the X devices will pass on
messages to the Y nodes.
 */
public class Network {
    private static final String TAG = "connectedcrossroad";
    private static final String LATENCY = "latency_tag";
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

    /*
    Adds a node to the network by endpoint ID. Returns false if the device is already connected to
    two other devices, true otherwise.

    Additionally, this method calls the sendNodesInNetwork method once a new node is added to the network.
    This is done so that the other devices in the network (X and Y nodes) can be informed of the new
    nodes in the network so that cycles aren't created and network size is properly displayed.
     */
    public boolean addNode(String id) throws IOException {
        if (!n1.isAssigned()) {
            n1.setId(id);
            sendNodesInNetwork(n2,n1);
        }
        else if (!n2.isAssigned()) {
            n2.setId(id);
            connectionsClient.stopAdvertising();
            connectionsClient.stopDiscovery();
            Log.d(TAG, "Stopping advertising and discovery");
            sendNodesInNetwork(n1,n2);
        }
        else {
            return false;
        }
        return true;
    }

    /*
    Returns true if an endpoint ID (and by extension, node) is contained within the network, false otherwise.
     */
    public boolean contains(String id) {
        return n1.contains(id) || n2.contains(id);
    }


    /*
    TODO: implement disconnection
     */
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


    /*
    Sets the endpoints for some node specified by its endpoint id. This ends up being called whenever
    some node in the network calls sendNodesInNetwork. This method will update the set of nodes that are
    connected to the node specified by id and then forward this new information to the other nodes
    in the network.

    Returns false if the device isn't in the network, true otherwise.
     */
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
    /*
    Sends the nodes connected to the "from" node to the "to" node. This is done to help prevent cycles,
    to aid in disconnection, and the enable correct counting of the number of devices in the network.
     */
    private void sendNodesInNetwork(Node from, Node to) throws IOException {
        if (to.isAssigned()) {
            Log.d(TAG,String.format("Sending nodes connected from %s to %s", from.getId(), to.getId()));
            connectionsClient.sendPayload(to.getId(), Payload.fromBytes(SerializationHelper.serialize(from.getEndpoints())));
        }

    }

    /*
    Sends a message to the nodes directly connected to the running device, ignoring any device matching
    "ignoreId."
     */
    public void sendMessage(String message, String ignoreId) throws IOException {
        Log.d(TAG,String.format("name: %s, id1: %s, id2: %s, ignore id: %s",name, n1.getId(), n2.getId(),ignoreId));
        if (n1.isAssigned() && !n1.is(ignoreId)) {
            Log.d(TAG,"Sending message to n1");
            byte[] bytes = SerializationHelper.serialize(message);
            Payload pl = Payload.fromBytes(bytes);
            Log.i(LATENCY, String.format("%d %d %d", pl.getId(), System.currentTimeMillis(),bytes.length));
            connectionsClient.sendPayload(
                    n1.getId(), pl);
        }
        if (n2.isAssigned() && !n2.is(ignoreId)) {
            Log.d(TAG,"Sending message to n2");
            byte[] bytes = SerializationHelper.serialize(message);
            Payload pl = Payload.fromBytes(bytes);
            Log.i(LATENCY, String.format("%d %d %d", pl.getId(), System.currentTimeMillis(),bytes.length));
            connectionsClient.sendPayload(
                    n2.getId(), pl);
        }

    }

}
/*
A class for organizing device nodes.
 */
class Node {
    private String id;
    private HashSet<String> endpoints;

    public Node() {
        id = "";
        endpoints = new HashSet<>();
    }

    public int getSize() {
        return endpoints.size();
    }

    public void setId(String id) {
        this.id = id;
        endpoints.add(id);
    }

    public void setEndpoints(HashSet<String> ids) {
        this.endpoints = ids;
        ids.add(this.id);
    }

    public String getId() {
        return id;
    }

    public boolean is(String id) {
        return this.id.equals(id);
    }

    public boolean isAssigned() {
        return !id.isEmpty();
    }

    public boolean contains(String id) {
        return endpoints.contains(id);
    }

    public HashSet<String> getEndpoints() {
        return endpoints;
    }

    public void clear() {
        id = "";
        endpoints = new HashSet<>();
    }
}
