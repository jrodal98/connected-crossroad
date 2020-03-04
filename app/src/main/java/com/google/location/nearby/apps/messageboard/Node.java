package com.google.location.nearby.apps.messageboard;

import java.util.HashSet;

public class Node {
    private String type;
    private String id;
    private HashSet<String> endpoints;

    public Node(String type) {
        this.type = type;
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

    public boolean mergeEndpoints(HashSet<String> ids) {
        return endpoints.addAll(ids);
    }

    public boolean isSameId(String id) {
        return this.id.equals(id);
    }

    public String getId() {
        return id;
    }

    public boolean isAssigned() {
        return !type.isEmpty();
    }



    public void clear() {
        type = "";
        id = "";
        endpoints = new HashSet<>();
    }
}
