package com.google.location.nearby.apps.messageboard;

import java.util.HashSet;

public class Node {
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

