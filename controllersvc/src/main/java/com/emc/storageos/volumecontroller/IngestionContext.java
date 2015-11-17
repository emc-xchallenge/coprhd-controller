package com.emc.storageos.volumecontroller;

import java.util.List;
import java.util.Map;

import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume;

public interface IngestionContext {

    /**
     * Returns the Map of UnManagedVolume nativeGuids to UnManagedVolume objects 
     * that have been successfully processed.
     * 
     * @return the processed UnManagedVolume Map
     */
    public abstract Map<String, UnManagedVolume> getProcessedUnManagedVolumeMap();

    /**
     * Returns the Map of UnManagedVolumes that can be safely deleted
     * if this ingestion process completes successfully.
     * 
     * @return the processed UnManagedVolume Map
     */
    public abstract List<UnManagedVolume> getUnManagedVolumesToBeDeleted();

    /**
     * Returns the Map of native GUIDs to created BlockObjects.
     * 
     * @return the created object Map
     */
    public abstract Map<String, BlockObject> getCreatedObjectMap();

    /**
     * Returns the Map of UnManagedVolume nativeGuid to related 
     * DataObjects that have been updated for it.
     * 
     * @return the updated object Map
     */
    public abstract Map<String, List<DataObject>> getUpdatedObjectMap();

    /**
     * Returns the Map of ingested objects, used 
     * by the general ingestion framework.
     * 
     * @return the ingested objects Map
     */
    public abstract List<BlockObject> getIngestedObjects();

    /**
     * Validates this context for ingestion.
     */
    public abstract void validate();

    /**
     * Commits any changes within this context.
     */
    public abstract void commit();

    /**
     * Rolls back any changes within this context idempotently.
     */
    public abstract void rollback();

    /**
     * Returns a detailed report on the state of everything in this context,
     * useful for debugging.
     * 
     * @return a detailed report on the context
     */
    public abstract String toStringDebug();

}