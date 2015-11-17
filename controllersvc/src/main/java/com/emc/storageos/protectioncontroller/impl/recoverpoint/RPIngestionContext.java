package com.emc.storageos.protectioncontroller.impl.recoverpoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedProtectionSet;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume;
import com.emc.storageos.db.client.util.CustomQueryUtility;
import com.emc.storageos.volumecontroller.IngestionContext;

public class RPIngestionContext implements IngestionContext {

    private static final Logger _logger = LoggerFactory.getLogger(RPIngestionContext.class);
    
    private UnManagedVolume unManagedVolume;
    private UnManagedProtectionSet unManagedProtectionSet;
    private Volume ingestedVolume;
    private DbClient dbClient;

    private Map<String, UnManagedVolume> processedUnManagedVolumeMap = new HashMap<String, UnManagedVolume>();
    private List<UnManagedVolume> unManagedVolumesToBeDeleted = new ArrayList<UnManagedVolume>();
    private Map<String, BlockObject> createdObjectMap = new HashMap<String, BlockObject>();
    private Map<String, List<DataObject>> updatedObjectMap = new HashMap<String, List<DataObject>>();
    private List<BlockObject> ingestedObjects = new ArrayList<BlockObject>();

    public RPIngestionContext(UnManagedVolume unManagedVolume, DbClient dbClient) {
        this.unManagedVolume = unManagedVolume;
        this.dbClient = dbClient;
    }
    
    /**
     * @return the ingestedVolume
     */
    public Volume getIngestedVolume() {
        return ingestedVolume;
    }

    /**
     * @param ingestedVolume the ingestedVolume to set
     */
    public void setIngestedVolume(Volume ingestedVolume) {
        this.ingestedVolume = ingestedVolume;
    }

    /**
     * @return the unManagedVolume
     */
    public UnManagedVolume getUnManagedVolume() {
        return unManagedVolume;
    }

    /**
     * @return the unManagedProtectionSet
     */
    public UnManagedProtectionSet getUnManagedProtectionSet() {
        
        // Find the UnManagedProtectionSet associated with this unmanaged volume
        List<UnManagedProtectionSet> umpsets = 
                CustomQueryUtility.getUnManagedProtectionSetByUnManagedVolumeId(dbClient, unManagedVolume.getId().toString());
        Iterator<UnManagedProtectionSet> umpsetsItr = umpsets.iterator();
        if (!umpsetsItr.hasNext()) {
            _logger.error("Unable to find unmanaged protection set associated with volume: " + unManagedVolume.getId());
            // caller will throw exception
            return null;
        }
        
        unManagedProtectionSet = umpsetsItr.next();

        return unManagedProtectionSet;
    }

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

    public List<DataObject> getObjectsToUpdate() {
        return getObjectsToUpdateForGuid(unManagedVolume.getNativeGuid());
    }

    public List<DataObject> getObjectsToUpdateForGuid(String nativeGuid) {
        List<DataObject> objectsToUpdate = getUpdatedObjectMap().get(nativeGuid);
        if (objectsToUpdate == null) {
            objectsToUpdate = new ArrayList<DataObject>();
            getUpdatedObjectMap().put(nativeGuid, objectsToUpdate);
        }
        return objectsToUpdate;
    }
    
    @Override
    public List<BlockObject> getIngestedObjects() {
        return ingestedObjects;
    }

    @Override
    public void validate() {
        // validate the unmanaged volume, protection set, etc...
    }

    @Override
    public void commit() {
        // save everything to the database
        dbClient.markForDeletion(getUnManagedVolumesToBeDeleted());
        dbClient.createObject(getCreatedObjectMap().values());
        // dbClient.updateObject(getUpdatedObjectMap());
        
        // if everything ingested, mark umpset for deletion
        
        // etc etc
    }

    @Override
    public void rollback() {
        // remove / rollback any changes to the data objects that were actually
        
        // if exportGroupWasCreated, delete ExportGroup
        
        // etc etc
        
    }

    @Override
    public String toStringDebug() {
        return null;
    }

}
