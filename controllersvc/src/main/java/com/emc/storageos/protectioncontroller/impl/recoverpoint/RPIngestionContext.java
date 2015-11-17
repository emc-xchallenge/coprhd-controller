package com.emc.storageos.protectioncontroller.impl.recoverpoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume;
import com.emc.storageos.volumecontroller.IngestionContext;

public class RPIngestionContext implements IngestionContext {

    private Map<String, UnManagedVolume> processedUnManagedVolumeMap = new HashMap<String, UnManagedVolume>();
    private List<UnManagedVolume> unManagedVolumesToBeDeleted = new ArrayList<UnManagedVolume>();
    private Map<String, BlockObject> createdObjectMap = new HashMap<String, BlockObject>();
    private Map<String, List<DataObject>> updatedObjectMap = new HashMap<String, List<DataObject>>();
    private List<BlockObject> ingestedObjects = new ArrayList<BlockObject>();

    @Override
    public Map<String, UnManagedVolume> getProcessedUnManagedVolumeMap() {
        return processedUnManagedVolumeMap;
    }

    @Override
    public List<UnManagedVolume> getUnManagedVolumesToBeDeleted() {
        return unManagedVolumesToBeDeleted;
    }

    @Override
    public Map<String, BlockObject> getCreatedObjectMap() {
        return createdObjectMap;
    }

    @Override
    public Map<String, List<DataObject>> getUpdatedObjectMap() {
        return updatedObjectMap;
    }

    @Override
    public List<BlockObject> getIngestedObjects() {
        return ingestedObjects;
    }

    @Override
    public void commit() {

    }

    @Override
    public void rollback() {

    }

    @Override
    public String toStringDebug() {
        return null;
    }

}
