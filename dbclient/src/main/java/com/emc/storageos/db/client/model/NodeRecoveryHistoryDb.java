package com.emc.storageos.db.client.model;

public class NodeRecoveryHistoryDb extends BlockObject implements ProjectResource {
    @Override
    public NamedURI getProject() {
        return null;
    }

    @Override
    public NamedURI getTenant() {
        return null;
    }
}

