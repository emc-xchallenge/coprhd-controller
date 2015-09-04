/*
 * Copyright (c) 2008-2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller.impl.plugins;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.model.DiscoveredDataObject;
import com.emc.storageos.db.client.model.DiscoveredDataObject.CompatibilityStatus;
import com.emc.storageos.db.client.model.DiscoveredDataObject.DiscoveryStatus;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StoragePool.PoolServiceType;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.exceptions.DatabaseException;
import com.emc.storageos.ecs.api.ECSApi;
import com.emc.storageos.ecs.api.ECSApiFactory;
import com.emc.storageos.ecs.api.ECSException;
import com.emc.storageos.ecs.api.ECSStoragePool;
import com.emc.storageos.plugins.AccessProfile;
import com.emc.storageos.plugins.BaseCollectionException;
import com.emc.storageos.plugins.metering.smis.SMIPluginException;
import com.emc.storageos.volumecontroller.impl.NativeGUIDGenerator;

/**
 * Class for ECS discovery object storage device
 */
public class ECSCommunicationInterface extends ExtendedCommunicationInterfaceImpl {
	URI storageSystemURI = null;
	private static final Logger _logger = LoggerFactory
            .getLogger(ECSCommunicationInterface.class);
	
	private ECSApiFactory ecsApiFactory;
	
	
    /**
     * @param ecsApiFactory the ecsApiFactory to set
     */
    public void setecsApiFactory(ECSApiFactory ecsApiFactory) {
    	_logger.info("ECSCommunicationInterface:setecsApiFactory");
        this.ecsApiFactory = ecsApiFactory;
    }
	
	@Override
	public void collectStatisticsInformation(AccessProfile accessProfile)
			throws BaseCollectionException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void scan(AccessProfile accessProfile)
			throws BaseCollectionException {
		// TODO Auto-generated method stub
		 _logger.info("ECSCommunicationInterface:scan scan Access Profile Details :" + accessProfile.toString());
	}

	@Override
	public void discover(AccessProfile accessProfile)
			throws BaseCollectionException {
        URI storageSystemId = null;
        StorageSystem storageSystem = null;
        String detailedStatusMessage = "Unknown Status";
        long startTime = System.currentTimeMillis();
        StoragePool storagePool;

        _logger.info("ECSCommunicationInterface:discover Access Profile Details :" + accessProfile.toString());
		try {
            storageSystemId = accessProfile.getSystemId();
            storageSystem = _dbClient.queryObject(StorageSystem.class, storageSystemId);

            // try to connect to the ECS
            ECSApi ecsApi = getECSDevice(storageSystem);
            String authToken = ecsApi.getAuthToken();
            
            //Make sure user is system admin before proceeding to discovery
            
            String nativeGuid = NativeGUIDGenerator.generateNativeGuid(DiscoveredDataObject.Type.ecs.toString(),
            		authToken);
            storageSystem.setNativeGuid(nativeGuid);
            storageSystem.setReachableStatus(true);
            _dbClient.persistObject(storageSystem);
            
            //Get storage pools
            List<StoragePool> pools = new ArrayList<StoragePool>();
            List<ECSStoragePool> ecsStoragePools = ecsApi.getStoragePools();
            for (ECSStoragePool ecsPool : ecsStoragePools)  {
            	storagePool = new StoragePool();
            	storagePool.setNativeGuid(nativeGuid);
            	storagePool.setStorageDevice(storageSystem.getId());
            	storagePool.setId(URIUtil.createId(StoragePool.class));
            	storagePool.setOperationalStatus(StoragePool.PoolOperationalStatus.READY.toString());
            	storagePool.setPoolServiceType(PoolServiceType.object.toString());
            	storagePool.setRegistrationStatus(DiscoveredDataObject.RegistrationStatus.REGISTERED.toString());
            	StringSet protocols = new StringSet();
            	protocols.add("S3");
            	protocols.add("Swift");
            	protocols.add("Atmos");
            	storagePool.setSupportedResourceTypes(StoragePool.SupportedResourceTypes.THIN_AND_THICK.toString());
            	storagePool.setFreeCapacity((1024L*1024L*1024L)); // 1TB
            	storagePool.setTotalCapacity((1024L*1024L*1024L));  // 1TB
            	storagePool.setLabel(ecsPool.getName());
            	
            	storagePool.setPoolName(ecsPool.getName());
            	storagePool.setCompatibilityStatus(CompatibilityStatus.COMPATIBLE.name());
            	storagePool.setDiscoveryStatus(DiscoveryStatus.VISIBLE.name());
            	storagePool.setNativeId(nativeGuid);
            	pools.add(storagePool);
            }
            
            _dbClient.createObject(pools);
            
		}  catch (Exception e) {
            detailedStatusMessage = String.format("Discovery failed for Storage System ECS Test: because %s",
                    e.getMessage());
            _logger.error(detailedStatusMessage, e);
            //throw new SMIPluginException(detailedStatusMessage);
		}finally {
            if (storageSystem != null) {
                try {
                    // set detailed message
                    storageSystem.setLastDiscoveryStatusMessage(detailedStatusMessage);
                    _dbClient.persistObject(storageSystem);
                } catch (DatabaseException ex) {
                    _logger.error("Error while persisting object to DB", ex);
                }
            }
            //releaseResources();
            long totalTime = System.currentTimeMillis() - startTime;
            _logger.info(String.format("Discovery of Storage System %s took %f seconds", "ECS Test", (double) totalTime
                    / (double) 1000));
        }
	}
	
    /**
     * Get ecs device represented by the StorageDevice
     *
     * @param ecsCluster  StorageDevice object
     * @return ECSApi object
     * @throws ECSException
     * @throws URISyntaxException
     */
    private ECSApi getECSDevice(StorageSystem ecsSystem) throws ECSException, URISyntaxException {
    	URI deviceURI = new URI("https", null, ecsSystem.getIpAddress(), ecsSystem.getPortNumber(), "/", null, null);

        return ecsApiFactory
                .getRESTClient(deviceURI, ecsSystem.getUsername(), ecsSystem.getPassword());
    }

}
