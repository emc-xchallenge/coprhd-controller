package com.emc.storageos.vplexcontroller;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringMap;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.StringSetMap;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume.SupportedVolumeCharacterstics;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume.SupportedVolumeInformation;
import com.emc.storageos.vplex.api.VPlexApiConstants;
import com.emc.storageos.vplex.api.VPlexApiException;
import com.emc.storageos.vplex.api.VPlexDeviceInfo;
import com.emc.storageos.vplex.api.VPlexDistributedDeviceInfo;
import com.emc.storageos.vplex.api.VPlexResourceInfo;
import com.emc.storageos.vplex.api.VPlexStorageVolumeInfo;

/**
 * A context object for holding all the data and functionality
 * required to discover and ingest a VPLEX virtual volume's
 * backend volumes and related replicas.
 */
public class VplexBackendIngestionContext {

    private static Logger _logger = LoggerFactory.getLogger(VplexBackendIngestionContext.class);

    public static final String VOLUME = "VOLUME";
    public static final String UNMANAGEDVOLUME = "UNMANAGEDVOLUME";
    public static final String INGESTION_METHOD_FULL = "Full";
    public static final String INGESTION_METHOD_VVOL_ONLY = "VirtualVolumeOnly";
    public static final String DISCOVERY_MODE = "controller_vplex_volume_discovery_mode";
    public static final String DISCOVERY_MODE_DISCOVERY_ONLY = "Only During Discovery";
    public static final String DISCOVERY_MODE_INGESTION_ONLY = "Only During Ingestion";
    public static final String DISCOVERY_MODE_HYBRID = "During Discovery and Ingestion";
    public static final String DISCOVERY_FILTER = "controller_vplex_volume_discovery_filter";
    public static final String DISCOVERY_KILL_SWITCH = "controller_vplex_volume_discovery_kill_switch";
    public static final String SLOT_0 = "0";
    public static final String SLOT_1 = "1";

    private DbClient _dbClient;
    private UnManagedVolume _unmanagedVirtualVolume;

    private boolean _discoveryInProgress = false;
    private boolean _ingestionInProgress = false;
    private boolean _inDiscoveryOnlyMode = false;
    private boolean _shouldCheckForMirrors = false;

    private VPlexResourceInfo topLevelDevice;
    private List<UnManagedVolume> unmanagedBackendVolumes;
    private List<UnManagedVolume> unmanagedSnapshots;
    private Map<UnManagedVolume, UnManagedVolume> unmanagedFullClones;
    private Map<UnManagedVolume, Set<UnManagedVolume>> unmanagedBackendOnlyClones;
    private Map<UnManagedVolume, String> unmanagedMirrors;
    private Map<String, Map<String, VPlexDeviceInfo>> mirrorMap;
    private Map<String, VPlexStorageVolumeInfo> backendVolumeWwnToInfoMap;

    private Project backendProject;
    private Project frontendProject;

    private Map<String, UnManagedVolume> processedUnManagedVolumeMap = new HashMap<String, UnManagedVolume>();
    private Map<String, BlockObject> createdObjectMap = new HashMap<String, BlockObject>();
    private Map<String, List<DataObject>> updatedObjectMap = new HashMap<String, List<DataObject>>();
    private List<BlockObject> ingestedObjects = new ArrayList<BlockObject>();

    private BackendDiscoveryPerformanceTracker _tracker;

    /**
     * Constructor taking the virtual volume's UnManagedVolume object
     * and a reference to the database client. This constructor will
     * also create an internal instance of the BackendDiscoveryPerformanceTracker. 
     * 
     * @param unManagedVolume the parent UnManagedVolume for the virtual volume
     * @param dbClient a reference to the database client
     */
    public VplexBackendIngestionContext(UnManagedVolume unManagedVolume, DbClient dbClient) {
        this._unmanagedVirtualVolume = unManagedVolume;
        this._dbClient = dbClient;
        this._tracker = new BackendDiscoveryPerformanceTracker();
    }

    /**
     * Calls any methods which would require contacting the VPLEX API
     * for further information during the VPLEX UnManagedVolume discovery process.
     */
    public void discover() {
        this.setDiscoveryInProgress(true);
        this.getUnmanagedBackendVolumes();
        this.getUnmanagedFullClones();
        this.getUnmanagedMirrors();
    }

    /**
     * Gets the parent virtual volume's UnManagedVolume object.
     * 
     * @return the parent virtual volume's UnManagedVolume object
     */
    public UnManagedVolume getUnmanagedVirtualVolume() {
        return this._unmanagedVirtualVolume;
    }

    /**
     * Collects and returns all the various backend resource UnManagedVolume objects
     * including those for the backend volumes and any related replicas.
     * 
     * @return a List of all the backend UnManagedVolumes for this context
     */
    public List<UnManagedVolume> getAllUnmanagedVolumes() {
        List<UnManagedVolume> allVolumes = new ArrayList<UnManagedVolume>();
        allVolumes.addAll(getUnmanagedBackendVolumes());
        allVolumes.addAll(getUnmanagedSnapshots());
        for (Set<UnManagedVolume> backendClones : getUnmanagedBackendOnlyClones().values()) {
            allVolumes.addAll(backendClones);
        }
        // only need the full clone virtual volumes
        // their backend vols will be ingested by a nested process
        allVolumes.addAll(getUnmanagedFullClones().values());
        allVolumes.addAll(getUnmanagedMirrors().keySet());
        _logger.info("collected all unmanaged volumes: " + allVolumes);
        return allVolumes;
    }

    /**
     * Gets URIs for all the backend resource UnManagedVolume objects.
     * 
     * @return a List of URIs for all the backend resource UnManagedVolume objects
     */
    public List<URI> getAllUnManagedVolumeUris() {
        List<URI> allUris = new ArrayList<URI>();
        for (UnManagedVolume vol : getAllUnmanagedVolumes()) {
            allUris.add(vol.getId());
        }
        return allUris;
    }

    /**
     * Returns a List of all the backend associated UnManagedVolume objects (i.e.,
     * the actual associated volumes, not replicas).
     * 
     * @return a List of the backend associated UnManagedVolume objects
     */
    public List<UnManagedVolume> getUnmanagedBackendVolumes() {
        if (null != unmanagedBackendVolumes) {
            return unmanagedBackendVolumes;
        }

        if (!isDiscoveryInProgress()) {
            // first check the database for this unmanaged volume's backend volumes
            StringSet dbBackendVolumes = extractValuesFromStringSet(
                    SupportedVolumeInformation.VPLEX_BACKEND_VOLUMES.toString(),
                    _unmanagedVirtualVolume.getVolumeInformation());
            if (null != dbBackendVolumes && !dbBackendVolumes.isEmpty()) {
                List<URI> umvUris = new ArrayList<URI>();
                for (String nativeId : dbBackendVolumes) {
                    _logger.info("\tfound unmanaged backend volume native id " + nativeId);
                    URIQueryResultList unManagedVolumeList = new URIQueryResultList();
                    _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                            .getVolumeInfoNativeIdConstraint(nativeId), unManagedVolumeList);
                    if (unManagedVolumeList.iterator().hasNext()) {
                        umvUris.add(unManagedVolumeList.iterator().next());
                    }
                }
                if (!umvUris.isEmpty()) {
                    boolean isLocal = isLocal();
                    if ((isLocal && umvUris.size() == 1) || (!isLocal && umvUris.size() == 2)) {
                        // only return vols from the database if we have the correct number
                        // of backend volumes for this type of unmanaged vplex virtual volume
                        unmanagedBackendVolumes = _dbClient.queryObject(UnManagedVolume.class, umvUris, true);
                        _logger.info("\treturning unmanaged backend volume objects: " + unmanagedBackendVolumes);
                        return unmanagedBackendVolumes;
                    }
                }
            }
        }

        // if they couldn't be found in the database,
        // we will query the VPLEX API for this information
        long start = System.currentTimeMillis();
        _logger.info("getting unmanaged backend volumes");
        unmanagedBackendVolumes = new ArrayList<UnManagedVolume>();

        // if we're in discovery only mode, don't check again during ingestion
        if (isIngestionInProgress() && isInDiscoveryOnlyMode()) {
            return unmanagedBackendVolumes;
        }

        List<URI> associatedVolumeUris = new ArrayList<URI>();

        if (!getBackendVolumeWwnToInfoMap().isEmpty()) {
            for (String backendWwn : getBackendVolumeWwnToInfoMap().keySet()) {
                _logger.info("attempting to find unmanaged backend volume by wwn {}",
                        backendWwn);

                URIQueryResultList results = new URIQueryResultList();
                _dbClient.queryByConstraint(AlternateIdConstraint.
                        Factory.getUnmanagedVolumeWwnConstraint(
                                BlockObject.normalizeWWN(backendWwn)), results);
                if (results.iterator() != null) {
                    for (URI uri : results) {
                        _logger.info("found backend volume " + uri);
                        associatedVolumeUris.add(uri);
                    }
                }
            }
        }

        unmanagedBackendVolumes = _dbClient.queryObject(UnManagedVolume.class, associatedVolumeUris);

        if (null == unmanagedBackendVolumes || unmanagedBackendVolumes.isEmpty()) {
            _logger.warn("no backend volumes were found for {}, have the "
                    + "backend storage arrays already been discovered?",
                    _unmanagedVirtualVolume.getLabel());
        } else {
            _logger.info("for VPLEX UnManagedVolume {}, found these associated volumes: " 
                    + unmanagedBackendVolumes, _unmanagedVirtualVolume.getLabel());
        }

        updateUnmanagedBackendVolumesInParent();
        _tracker.fetchBackendVolumes = System.currentTimeMillis() - start;

        return unmanagedBackendVolumes;
    }

    /**
     * Sets the VPLEX_BACKEND_VOLUMES information on the virtual UnManagedVolume
     * as well as the VPLEX_PARENT_VOLUME on each associated UnManagedVolume.
     */
    private void updateUnmanagedBackendVolumesInParent() {
        if (!getUnmanagedBackendVolumes().isEmpty()) {
            StringSet bvols = new StringSet();
            for (UnManagedVolume backendVol : unmanagedBackendVolumes) {
                bvols.add(backendVol.getNativeGuid());
                StringSet parentVol = new StringSet();
                parentVol.add(_unmanagedVirtualVolume.getNativeGuid());
                backendVol.putVolumeInfo(SupportedVolumeInformation.VPLEX_PARENT_VOLUME.name(), parentVol);
                _dbClient.persistObject(backendVol);
            }
            if (bvols != null && !bvols.isEmpty()) {
                _logger.info("setting VPLEX_BACKEND_VOLUMES: " + unmanagedBackendVolumes);
                _unmanagedVirtualVolume.putVolumeInfo(SupportedVolumeInformation.VPLEX_BACKEND_VOLUMES.name(), bvols);
            }
        }
    }

    /**
     * Queries the VPLEX API to get a Map of backend storage volume WWNs
     * to VPlexStorageVolumeInfo objects.
     * 
     * @return a Map of backend storage volume WWNs to VPlexStorageVolumeInfo objects
     */
    private Map<String, VPlexStorageVolumeInfo> getBackendVolumeWwnToInfoMap() {
        if (null != backendVolumeWwnToInfoMap) {
            return backendVolumeWwnToInfoMap;
        }

        _logger.info("getting backend volume wwn to api info map");
        boolean success = false;
        try {
            // first trying without checking for a top-level device mirror to save some time
            backendVolumeWwnToInfoMap =
                    VPlexControllerUtils.getStorageVolumeInfoForDevice(
                            getSupportingDeviceName(), getLocality(), getClusterName(), false,
                            _unmanagedVirtualVolume.getStorageSystemUri(), _dbClient);
            success = true;
        } catch (VPlexApiException ex) {
            _logger.warn("failed to find wwn to storage volume map on "
                    + "first try with no mirror map, will analyze mirrors and try again", ex);
            _shouldCheckForMirrors = true;
        }

        if (!success) {
            // we didn't succeed the first time, so try again and check for mirrors first
            boolean hasMirror = !getMirrorMap().isEmpty();
            
            if (hasMirror) {
                // the volume has a mirrored top-level device, so we need to 
                // send the hasMirror flag down so that the VPLEX client will
                // know to look one level deeper in the components tree for
                // the backend storage volumes
                try {
                    backendVolumeWwnToInfoMap =
                            VPlexControllerUtils.getStorageVolumeInfoForDevice(
                                    getSupportingDeviceName(), getLocality(), getClusterName(), hasMirror,
                                    _unmanagedVirtualVolume.getStorageSystemUri(), _dbClient);
                    success = true;
                } catch (VPlexApiException ex) {
                    String reason = "could not determine backend storage volumes for " 
                            + getSupportingDeviceName() + ": " + ex.getLocalizedMessage();
                    _logger.error(reason);
                    throw VPlexApiException.exceptions.backendIngestionContextLoadFailure(reason);
                }
            } else {
                String reason = "could not determine backend storage volumes for " 
                        + getSupportingDeviceName() 
                        + ": failed for both simple and RAID-1 top-level device configurations";
                _logger.error(reason);
                throw VPlexApiException.exceptions.backendIngestionContextLoadFailure(reason);
            }
        }

        _logger.info("backend volume wwn to api info map: " + backendVolumeWwnToInfoMap);
        return backendVolumeWwnToInfoMap;
    }

    /**
     * Gets a List of all the backend volume native GUIDs as
     * they would appear in a Volume object (not as in an
     * UnManagedVolume object. 
     * 
     * @return a List of all the backend volume native GUIDs
     */
    public List<String> getBackendVolumeGuids() {

        List<String> associatedVolumeGuids = new ArrayList<String>();

        for (UnManagedVolume vol : this.getUnmanagedBackendVolumes()) {
            associatedVolumeGuids.add(vol.getNativeGuid().replace(UNMANAGEDVOLUME, VOLUME));
        }

        _logger.info("associated volume guids: " + associatedVolumeGuids);
        return associatedVolumeGuids;
    }

    /**
     * Determines if a given UnManagedVolume is one of the backend
     * associated volumes of this context.
     * 
     * @param volumeToCheck the UnManagedVolume to check
     * @return true if the volume is a backend volume
     */
    public boolean isBackendVolume(UnManagedVolume volumeToCheck) {
        String id = volumeToCheck.getId().toString();
        for (UnManagedVolume vol : getUnmanagedBackendVolumes()) {
            if (id.equals(vol.getId().toString())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns a List of any block snapshots associated
     * with the backend volumes of this context's virtual volume.
     * 
     * @return a List of UnManagedVolume snaphot objects
     */
    public List<UnManagedVolume> getUnmanagedSnapshots() {
        if (null != unmanagedSnapshots) {
            return unmanagedSnapshots;
        }

        long start = System.currentTimeMillis();
        _logger.info("getting unmanaged snapshots");
        unmanagedSnapshots = new ArrayList<UnManagedVolume>();

        for (UnManagedVolume sourceVolume : this.getUnmanagedBackendVolumes()) {
            unmanagedSnapshots.addAll(getUnManagedSnaphots(sourceVolume));
        }

        _logger.info("found these associated snapshots: " + unmanagedSnapshots);
        _tracker.fetchSnapshots = System.currentTimeMillis() - start;

        return unmanagedSnapshots;
    }

    /**
     * Returns a Map of parent backend volume to child backend volumes 
     * for any clones (full copies) associated with the backend volumes 
     * of this context's virtual volume.
     * 
     * The term "backend-only clone" implies that the clone is only a copy 
     * of the backend volume and there is no virtual volume in front of it.
     * This is as-opposed to a "full clone" that has a virtual volume in
     * front of it.
     * 
     * @return a Map of UnManagedVolume parent objects to UnManagedVolume child objects
     */
    public Map<UnManagedVolume, Set<UnManagedVolume>> getUnmanagedBackendOnlyClones() {
        if (null != unmanagedBackendOnlyClones) {
            return unmanagedBackendOnlyClones;
        }

        long start = System.currentTimeMillis();
        _logger.info("getting unmanaged backend-only clones");
        unmanagedBackendOnlyClones = new HashMap<UnManagedVolume, Set<UnManagedVolume>>();

        for (UnManagedVolume sourceVolume : getUnmanagedBackendVolumes()) {
            Set<UnManagedVolume> backendClonesFound = new HashSet<UnManagedVolume>();
            backendClonesFound.addAll(getUnManagedClones(sourceVolume));
            if (!backendClonesFound.isEmpty()) {
                for (UnManagedVolume foundClone : backendClonesFound) {
                    boolean addIt = true;
                    // we need to check if this backend-only clone has a 
                    // virtual volume in front of it, and if so, it should
                    // be considered a "full clone" and be excluded from this
                    // backend-only clone collection
                    if (!getUnmanagedFullClones().isEmpty()) {
                        for (UnManagedVolume knownClone : getUnmanagedFullClones().keySet()) {
                            if (knownClone.getId().toString().equals(foundClone.getId().toString())) {
                                _logger.info("clone {} is already part of a full (virtual volume) clone, "
                                        + "excluding it from backend-only clones", knownClone.getLabel());
                                addIt = false;
                            }
                        }
                    }
                    if (addIt) {
                        unmanagedBackendOnlyClones.put(sourceVolume, backendClonesFound);
                    }
                }
            }
        }

        _logger.info("unmanaged backend-only clones found: " + unmanagedBackendOnlyClones);
        _tracker.fetchBackendOnlyClones = System.currentTimeMillis() - start;

        return unmanagedBackendOnlyClones;
    }

    /**
     * Returns a Map of clone backend volume to front-end virtual volume clone 
     * for any clones (full copies) associated with this context's virtual volume.
     * 
     * The term "full clone" implies that the clone is a backend volume clone with
     * a front-end virtual volume containing it.  This is as-opposed to a backend-only
     * clone, which is just a simple clone of a backend volume without a virtual
     * volume in front of it.
     * 
     * @return a Map of UnManagedVolume backend objects to UnManagedVolume front-end objects
     */
    public Map<UnManagedVolume, UnManagedVolume> getUnmanagedFullClones() {
        if (null != unmanagedFullClones) {
            return unmanagedFullClones;
        }

        if (!isDiscoveryInProgress()) {
            // first check the database for this unmanaged volume's backend full clones
            StringSet fullCloneMap = extractValuesFromStringSet(
                    SupportedVolumeInformation.VPLEX_FULL_CLONE_MAP.toString(),
                    _unmanagedVirtualVolume.getVolumeInformation());
            if (null != fullCloneMap && !fullCloneMap.isEmpty()) {
                _logger.info("checking the database for full clone map");
                for (String fullCloneEntry : fullCloneMap) {
                    
                    // extract 'n' parse the full clone info from the database
                    // pair[0] contains the id of the backend clone (backendClone)
                    // pair[1] contains the id of the front end virtual volume clone (vvolClone)
                    String[] pair = fullCloneEntry.split("=");
                    UnManagedVolume backendClone = null;
                    UnManagedVolume vvolClone = null;
                    
                    // fetch of the UnManagedVolume objects
                    URIQueryResultList unManagedVolumeList = new URIQueryResultList();
                    _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                            .getVolumeInfoNativeIdConstraint(pair[0]), unManagedVolumeList);
                    if (unManagedVolumeList.iterator().hasNext()) {
                        backendClone = _dbClient.queryObject(UnManagedVolume.class,
                                unManagedVolumeList.iterator().next());
                    }
                    unManagedVolumeList = new URIQueryResultList();
                    _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                            .getVolumeInfoNativeIdConstraint(pair[1]), unManagedVolumeList);
                    if (unManagedVolumeList.iterator().hasNext()) {
                        vvolClone = _dbClient.queryObject(UnManagedVolume.class,
                                unManagedVolumeList.iterator().next());
                    }
                    if (null == unmanagedFullClones) {
                        unmanagedFullClones = new HashMap<UnManagedVolume, UnManagedVolume>();
                    }
                    
                    // put in the clone map that will return from this method
                    unmanagedFullClones.put(backendClone, vvolClone);
                }
                if (null != unmanagedFullClones && !unmanagedFullClones.isEmpty()) {
                    _logger.info("found full clones: " + unmanagedFullClones);
                    
                    // TODO: this is temporary until we can support
                    // clones on both legs of distributed volumes
                    // still want to collect the data for testing, though
                    if (!this.isLocal()) {
                        throw VPlexApiException.exceptions.backendIngestionContextLoadFailure(
                                "currently can't ingest clones on distributed volumes, sorry");
                    }
                    
                    return unmanagedFullClones;
                }
            }
        }

        // if they couldn't be found in the database,
        // we will query the VPLEX API for this information
        long start = System.currentTimeMillis();
        _logger.info("getting unmanaged full clones");
        unmanagedFullClones = new HashMap<UnManagedVolume, UnManagedVolume>();

        // if we're in discovery only mode, don't check again during ingestion
        if (isIngestionInProgress() && isInDiscoveryOnlyMode()) {
            return unmanagedFullClones;
        }

        List<UnManagedVolume> backendClonesFound = new ArrayList<UnManagedVolume>();
        for (UnManagedVolume sourceVolume : getUnmanagedBackendVolumes()) {
            backendClonesFound.addAll(getUnManagedClones(sourceVolume));
        }

        if (backendClonesFound.isEmpty()) {
            _logger.info("no clones found for source volumes: " + getUnmanagedBackendVolumes());
        } else {

            Map<String, URI> deviceToUnManagedVolumeMap = getVplexDeviceToUnManagedVolumeMap();

            for (UnManagedVolume backendClone : backendClonesFound) {
                String volumeNativeId = extractValueFromStringSet(
                        SupportedVolumeInformation.NATIVE_ID.toString(),
                        backendClone.getVolumeInformation());

                StorageSystem backendSystem =
                        _dbClient.queryObject(StorageSystem.class, backendClone.getStorageSystemUri());

                String deviceName = VPlexControllerUtils.getDeviceNameForStorageVolume(
                        volumeNativeId, backendClone.getWwn(), backendSystem.getSerialNumber(),
                        _unmanagedVirtualVolume.getStorageSystemUri(), _dbClient);

                if (null != deviceName) {
                    _logger.info("found device name {} for native id {}", deviceName, volumeNativeId);
                    URI umvUri = deviceToUnManagedVolumeMap.get(deviceName);
                    if (null != umvUri) {
                        // virtualVolumeClone is the UnManagedVolume that is the front-end of this full clone
                        // backendClone is the UnManagedVolume that is the backend volume for virtualVolumeClone
                        UnManagedVolume virtualVolumeClone = _dbClient.queryObject(UnManagedVolume.class, umvUri);
                        if (null != virtualVolumeClone) {
                            
                            // 1. add a mapping for the backend to the frontend UnManagedVolumes of the full clone
                            _logger.info("adding mapping for backend clone {} to vvol clone {)",
                                        backendClone, virtualVolumeClone);
                            unmanagedFullClones.put(backendClone, virtualVolumeClone);
                            
                            // 2. remove the backend-clone from the backend-only clone collection
                            _logger.info("   because this clone has a virtual volume "
                                    + "in front of it, removing from backend only clone set");
                            Iterator<Entry<UnManagedVolume, Set<UnManagedVolume>>> backendOnlyCloneEntries =
                                    getUnmanagedBackendOnlyClones().entrySet().iterator();
                            while (backendOnlyCloneEntries.hasNext()) {
                                Entry<UnManagedVolume, Set<UnManagedVolume>> backendCloneEntry = 
                                        backendOnlyCloneEntries.next();
                                if (backendCloneEntry.getKey().getId().toString().equals(
                                        backendClone.getId().toString())) {
                                    getUnmanagedBackendOnlyClones().remove(backendCloneEntry.getKey());
                                }
                            }
                        }
                    }
                } else {
                    _logger.info("could not determine supporting device name for native id " + volumeNativeId);
                }
            }
        }

        _logger.info("unmanaged full clones found: " + unmanagedFullClones);
        _tracker.fetchFullClones = System.currentTimeMillis() - start;
        if (!unmanagedFullClones.isEmpty()) {
            
            // TODO: this is temporary until we can support
            // clones on both legs of distributed volumes
            // still want to collect the data for testing, though
            if (!this.isLocal()) {
                throw VPlexApiException.exceptions.backendIngestionContextLoadFailure(
                        "currently can't ingest clones on distributed volumes, sorry");
            }
            
            StringSet cloneEntries = new StringSet();
            for (Entry<UnManagedVolume, UnManagedVolume> cloneEntry : unmanagedFullClones.entrySet()) {
                cloneEntries.add(cloneEntry.getKey().getNativeGuid() + "=" + cloneEntry.getValue().getNativeGuid());
            }
            if (cloneEntries != null && !cloneEntries.isEmpty()) {
                _logger.info("setting VPLEX_FULL_CLONE_MAP: " + cloneEntries);
                _unmanagedVirtualVolume.putVolumeInfo(SupportedVolumeInformation.VPLEX_FULL_CLONE_MAP.name(), cloneEntries);
            }
        }

        return unmanagedFullClones;
    }

    /**
     * Returns a Map of UnManagedVolume objects that are parts
     * of a VplexMirror to their device context path from the 
     * VPLEX API.
     * 
     * @return a map of UnManagedVolume to device context paths
     */
    public Map<UnManagedVolume, String> getUnmanagedMirrors() {
        if (null != unmanagedMirrors) {
            return unmanagedMirrors;
        }

        if (!isDiscoveryInProgress()) {
            // first check the database for this unmanaged volume's backend mirrors
            StringSet mirrorMapFromTheDatabase = extractValuesFromStringSet(
                    SupportedVolumeInformation.VPLEX_MIRROR_MAP.toString(),
                    _unmanagedVirtualVolume.getVolumeInformation());
            if (null != mirrorMapFromTheDatabase && !mirrorMapFromTheDatabase.isEmpty()) {
                _logger.info("fetching mirror map from database");
                for (String mirrorEntry : mirrorMapFromTheDatabase) {
                    
                    // extract 'n' parse the mirror info from the database
                    // pair[0] is the native id of the mirror
                    // pair[1] is the device context path from the VPLEX API
                    String[] pair = mirrorEntry.split("=");
                    UnManagedVolume mirrorVolume = null;
                    String contextPath = pair[1];
                    
                    // find the mirror UnManagedVolume object
                    URIQueryResultList unManagedVolumeList = new URIQueryResultList();
                    _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                            .getVolumeInfoNativeIdConstraint(pair[0]), unManagedVolumeList);
                    if (unManagedVolumeList.iterator().hasNext()) {
                        mirrorVolume = _dbClient.queryObject(UnManagedVolume.class,
                                unManagedVolumeList.iterator().next());
                    }
                    
                    // add to the map that will be returned from this method
                    if (null != mirrorVolume && null != contextPath) {
                        if (null == unmanagedMirrors) {
                            unmanagedMirrors = new HashMap<UnManagedVolume, String>();
                        }
                        unmanagedMirrors.put(mirrorVolume, contextPath);
                        
                        // now remove the mirror from the list of regular backend volumes
                        // so that it won't be ingested that way
                        Iterator<UnManagedVolume> itr = getUnmanagedBackendVolumes().iterator();
                        while (itr.hasNext()) {
                            if (mirrorVolume.getId().toString().equals(itr.next().getId().toString())) {
                                itr.remove();
                            }
                        }
                    }
                }
                if (null != unmanagedMirrors && !unmanagedMirrors.isEmpty()) {
                    _logger.info("found mirror map: " + unmanagedMirrors);
                    return unmanagedMirrors;
                }
            }
        }

        unmanagedMirrors = new HashMap<UnManagedVolume, String>();

        // if a simple 1-1-1 device structure was found, no need
        // to check for native mirrors
        if (!_shouldCheckForMirrors) {
            return unmanagedMirrors;
        }

        // if we're in discovery only mode, don't check again during ingestion
        if (isIngestionInProgress() && isInDiscoveryOnlyMode()) {
            return unmanagedMirrors;
        }

        // if the mirror map couldn't be found in the database,
        // we will query the VPLEX API for this information
        long start = System.currentTimeMillis();
        _logger.info("getting unmanaged mirrors");
        if (!getMirrorMap().isEmpty()) {
            
            //
            // the mirrorMap is structured like: Map<ClusterName, Map<SlotNumber, VPlexDeviceInfo>>
            //
            
            for (Entry<String, Map<String, VPlexDeviceInfo>> mirrorMapEntry : getMirrorMap().entrySet()) {
                
                _logger.info("looking at mirrors for device leg on cluster " + mirrorMapEntry.getKey());
                Map<String, VPlexDeviceInfo> slotToDeviceMap = mirrorMapEntry.getValue();
                
                if (null != slotToDeviceMap && !slotToDeviceMap.isEmpty()) {
                    
                    // figure out the source and target (mirror) UnManagedVolumes for this leg
                    UnManagedVolume associatedVolumeSource = null;
                    UnManagedVolume associatedVolumeMirror = null;
                    
                    // source will be in slot-0, target/mirror will be in slot-1
                    for (Entry<String, VPlexDeviceInfo> entry : slotToDeviceMap.entrySet()) {
                        if (SLOT_0.equals(entry.getKey())) {
                            _logger.info("looking at slot-0");
                            associatedVolumeSource = getAssociatedVolumeForComponentDevice(entry.getValue());
                        }
                        if (SLOT_1.equals(entry.getKey())) {
                            _logger.info("looking at slot-1");
                            associatedVolumeMirror = getAssociatedVolumeForComponentDevice(entry.getValue());
                        }
                    }
                    
                    // once found, wire them together:
                    if (null != associatedVolumeMirror && null != associatedVolumeSource) {
                        // 1. remove the mirror volume from the general backend volumes
                        _logger.info("removing mirror volume {} from associated "
                                + "vols and adding to mirrors", associatedVolumeMirror.getLabel());
                        getUnmanagedBackendVolumes().remove(associatedVolumeMirror);

                        // 2. add the mirror the unmanagedMirrors map that will be returned by this method
                        unmanagedMirrors.put(associatedVolumeMirror, slotToDeviceMap.get("1").getPath());
                        
                        // 3. update the source volume with the target mirror information
                        StringSet set = extractValuesFromStringSet(SupportedVolumeInformation.MIRRORS.toString(), 
                                associatedVolumeSource.getVolumeInformation());
                        if (null == set) {
                            set = new StringSet();
                        }
                        set.add(associatedVolumeMirror.getNativeGuid());
                        _logger.info("adding mirror set {} to source unmanaged volume {}", 
                                set, associatedVolumeSource);
                        associatedVolumeSource.putVolumeInfo(SupportedVolumeInformation.MIRRORS.toString(), set);
                        associatedVolumeSource.putVolumeInfo(
                                SupportedVolumeInformation.VPLEX_NATIVE_MIRROR_TARGET_VOLUME.toString(), set);
                        
                        // 4. update the target volume with the source volume information
                        set = new StringSet();
                        set.add(associatedVolumeSource.getNativeGuid());
                        associatedVolumeMirror.putVolumeInfo(
                                SupportedVolumeInformation.VPLEX_NATIVE_MIRROR_SOURCE_VOLUME.toString(), set);
                        
                        // 5. need to go ahead and persist any changes to backend volume info
                        _dbClient.persistObject(associatedVolumeSource);
                        _dbClient.persistObject(associatedVolumeMirror);
                    } else {
                        String reason = "couldn't find all associated device components in mirror device: ";
                        reason += " associatedVolumeSource is " + associatedVolumeSource;
                        reason += " and associatedVolumeMirror is " + associatedVolumeMirror;
                        _logger.error(reason);
                        throw VPlexApiException.exceptions.backendIngestionContextLoadFailure(reason);
                    }
                }
            }
        }

        _logger.info("unmanaged mirrors found: " + unmanagedMirrors);
        _tracker.fetchMirrors = System.currentTimeMillis() - start;
        if (!unmanagedMirrors.isEmpty()) {

            StringSet mirrorEntries = new StringSet();
            for (Entry<UnManagedVolume, String> mirrorEntry : unmanagedMirrors.entrySet()) {
                mirrorEntries.add(mirrorEntry.getKey().getNativeGuid() + "=" + mirrorEntry.getValue());
            }
            if (mirrorEntries != null && !mirrorEntries.isEmpty()) {
                _logger.info("setting VPLEX_MIRROR_MAP: " + mirrorEntries);
                _unmanagedVirtualVolume.putVolumeInfo(SupportedVolumeInformation.VPLEX_MIRROR_MAP.name(), mirrorEntries);
            }
            // need to update the backend volumes because a mirror target shouldn't be considered a direct backend volume
            updateUnmanagedBackendVolumesInParent();
        }
        return unmanagedMirrors;
    }

    /**
     * Finds the backend associated UnManagedVolume for the given VPlexDeviceInfo
     * object. It does this by finding the overlap between the context paths
     * of the VPlexDeviceInfo and VPlexStorageVolumeInfo objects and then using
     * the WWN to locate the related UnManagedVolume.
     * 
     * @param device the VPlexDeviceInfo object to search for
     * @return an UnManagedVolume matching the VPlexDeviceInfo.
     */
    private UnManagedVolume getAssociatedVolumeForComponentDevice(VPlexDeviceInfo device) {
        String devicePath = device.getPath();
        _logger.info("associated volume device context path: " + devicePath);
        for (Entry<String, VPlexStorageVolumeInfo> entry : getBackendVolumeWwnToInfoMap().entrySet()) {
            String storageVolumePath = entry.getValue().getPath();
            _logger.info("\tstorage volume context path: " + storageVolumePath);
            // context paths should overlap if the device contains the storage volume...
            if (null != storageVolumePath && storageVolumePath.startsWith(devicePath)) {
                _logger.info("\t\tthis storage volume is a match, trying to find unmanaged backend volume");
                for (UnManagedVolume vol : getUnmanagedBackendVolumes()) {
                    _logger.info("\t\t\tlooking at " + vol.getNativeGuid());
                    if (vol.getWwn().equalsIgnoreCase(entry.getKey())) {
                        _logger.info("\t\t\t\tit's a match for " + vol.getWwn());
                        return vol;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Creates a Map of cluster name to sorted Map of slot numbers to VPlexDeviceInfos
     * for use in describing the layout of VPLEX native mirrors.
     * 
     * @return a Map of cluster id to sorted Map of slot numbers to VPlexDeviceInfos
     */
    private Map<String, Map<String, VPlexDeviceInfo>> getMirrorMap() {
        if (null != mirrorMap) {
            return mirrorMap;
        }

        // the mirror map is a mapping of:
        // 
        //    cluster id (e.g., cluster-1 and cluster-2) to: 
        //        a sorted map of device slot-number to:
        //            the VPlexDeviceInfo in that slot
        //    sort of like: Map<ClusterName, Map<SlotNumber, VPlexDeviceInfo>>
        //
        // if distributed, it assumes only one mirror set 
        // can be present on each side of the vplex

        _logger.info("assembling mirror map");
        mirrorMap = new HashMap<String, Map<String, VPlexDeviceInfo>>();
        VPlexResourceInfo device = getTopLevelDevice();
        if (isLocal() && (device instanceof VPlexDeviceInfo)) {
            VPlexDeviceInfo localDevice = ((VPlexDeviceInfo) device);
            if (VPlexApiConstants.ARG_GEOMETRY_RAID1.equals(
                    ((VPlexDeviceInfo) device).getGeometry())) {
                mirrorMap.put(localDevice.getCluster(), mapDevices(localDevice));
            }
        } else {
            for (VPlexDeviceInfo localDevice : ((VPlexDistributedDeviceInfo) device).getLocalDeviceInfo()) {
                if (VPlexApiConstants.ARG_GEOMETRY_RAID1.equals(
                        ((VPlexDistributedDeviceInfo) device).getGeometry())) {
                    mirrorMap.put(localDevice.getCluster(), mapDevices(localDevice));
                }
            }
        }

        _logger.info("mirror map is: " + mirrorMap);
        return mirrorMap;
    }

    /**
     * Creates a Map of slot numbers to VPlexDeviceInfo child objects of a
     * given top level device VPlexDeviceInfo, for use in creating the 
     * VPLEX native mirror map.
     * 
     * @param parentDevice the top level device of this virtual volume
     * 
     * @return a Map of slot numbers to VPlexDeviceInfo child objects
     */
    private Map<String, VPlexDeviceInfo> mapDevices(VPlexDeviceInfo parentDevice) {
        _logger.info("\tmapping device " + parentDevice.getName());
        Map<String, VPlexDeviceInfo> mirrorDevices = new TreeMap<String, VPlexDeviceInfo>();
        for (VPlexDeviceInfo dev : parentDevice.getChildDeviceInfo()) {
            mirrorDevices.put(dev.getSlotNumber(), dev);
        }
        return mirrorDevices;
    }

    /**
     * Queries the VPLEX API to find the VPlexResourceInfo object representing
     * the top-level device of this virtual volume.  Can be either a VPlexDistributedDeviceInfo
     * or VPlexDeviceInfo object.
     * 
     * @return a VPlexResourceInfo representing the top-level device of this virtual volume
     */
    public VPlexResourceInfo getTopLevelDevice() {
        if (null != topLevelDevice) {
            return topLevelDevice;
        }

        long start = System.currentTimeMillis();
        _logger.info("getting top level device");
        topLevelDevice = VPlexControllerUtils.getDeviceInfo(
                getSupportingDeviceName(), getLocality(),
                getUnmanagedVirtualVolume().getStorageSystemUri(),
                _dbClient);

        _logger.info("top level device is: " + topLevelDevice);
        _tracker.fetchTopLevelDevice = System.currentTimeMillis() - start;
        return topLevelDevice;
    }

    /**
     * Returns the supporting device name for this virtual volume.
     * 
     * @return the supporting device name for this virtual volume
     */
    public String getSupportingDeviceName() {
        String deviceName = extractValueFromStringSet(
                SupportedVolumeInformation.VPLEX_SUPPORTING_DEVICE_NAME.toString(),
                _unmanagedVirtualVolume.getVolumeInformation());
        return deviceName;
    }

    /**
     * Returns the locality for this virtual volume.
     * 
     * @return the locality for this virtual volume
     */
    public String getLocality() {
        String locality = extractValueFromStringSet(
                SupportedVolumeInformation.VPLEX_LOCALITY.toString(),
                _unmanagedVirtualVolume.getVolumeInformation());
        return locality;
    }

    /**
     * Returns the cluster for this virtual volume, if local. Or
     * will return just one if it's a distributed volume.
     * 
     * @return the cluster for this virtual volume
     */
    public String getClusterName() {
        String cluster = extractValueFromStringSet(
                SupportedVolumeInformation.VPLEX_CLUSTER_IDS.toString(),
                _unmanagedVirtualVolume.getVolumeInformation());
        // even if both clusters are listed, returning just one is fine
        return cluster;
    }

    /**
     * Returns true if the virtual volume is a local volume.
     * 
     * @return true if the virtual volume is a local volume
     */
    public boolean isLocal() {
        return VPlexApiConstants.LOCAL_VIRTUAL_VOLUME.equals(getLocality());
    }

    /**
     * Returns the Map of processed UnManagedVolumes, used 
     * by the general ingestion framework.
     * 
     * @return the processed UnManagedVolume Map
     */
    public Map<String, UnManagedVolume> getProcessedUnManagedVolumeMap() {
        return processedUnManagedVolumeMap;
    }

    /**
     * Returns the Map of created objects, used 
     * by the general ingestion framework.
     * 
     * @return the created object Map
     */
    public Map<String, BlockObject> getCreatedObjectMap() {
        return createdObjectMap;
    }

    /**
     * Returns the Map of updated objects, used 
     * by the general ingestion framework.
     * 
     * @return the updated object Map
     */
    public Map<String, List<DataObject>> getUpdatedObjectMap() {
        return updatedObjectMap;
    }

    /**
     * Returns the Map of ingested objects, used 
     * by the general ingestion framework.
     * 
     * @return the ingested objects Map
     */
    public List<BlockObject> getIngestedObjects() {
        return ingestedObjects;
    }

    /**
     * Returns the Project to be used for backend resources.
     * 
     * @return the backend Project
     */
    public Project getBackendProject() {
        return backendProject;
    }

    /**
     * Sets the Project to be used for backend resources.
     * 
     * @param backendProject the backend Project to set
     */
    public void setBackendProject(Project backendProject) {
        this.backendProject = backendProject;
    }

    /**
     * Returns the Project to be used for front-end resources.
     * 
     * @return the frontend Project
     */
    public Project getFrontendProject() {
        return frontendProject;
    }

    /**
     * Sets the Project to be used for front-end resources.
     * 
     * @param frontendProject the frontend Project to set
     */
    public void setFrontendProject(Project frontendProject) {
        this.frontendProject = frontendProject;
    }

    /**
     * Returns whether or not the context is in discovery mode.
     * If true, then the VPLEX API will be queried for new data
     * regardless of whether or not data is already present in
     * the database. 
     * 
     * @return true if in discovery mode
     */
    public boolean isDiscoveryInProgress() {
        return _discoveryInProgress;
    }

    /**
     * Set whether or not the context is in discovery mode.
     * If true, then the VPLEX API will be queried for new data
     * regardless of whether or not data is already present in
     * the database. 
     * 
     * @param set the discovery mode flag
     */
    public void setDiscoveryInProgress(boolean inDiscoveryInProgress) {
        this._discoveryInProgress = inDiscoveryInProgress;
    }

    /**
     * Returns whether or not the context is in Discovery-Only mode.
     * If true, then the VPLEX API will NOT be queried for new data
     * and only data present in the database can be used for ingestion.
     * 
     * @return whether or not the context is in discovery mode
     */
    public boolean isInDiscoveryOnlyMode() {
        return _inDiscoveryOnlyMode;
    }

    /**
     * Sets whether or not the context is in Discovery-Only mode.
     * If true, then the VPLEX API will NOT be queried for new data
     * and only data present in the database can be used for ingestion.
     * 
     * @param inDiscoveryOnlyMode the discovery flag to set
     */
    public void setInDiscoveryOnlyMode(boolean inDiscoveryOnlyMode) {
        this._inDiscoveryOnlyMode = inDiscoveryOnlyMode;
    }

    /**
     * Returns whether or not the context is being used for ingestion
     * as opposed to discovery.
     * 
     * @return true if the context is being used for ingestion
     */
    public boolean isIngestionInProgress() {
        return _ingestionInProgress;
    }

    /**
     * Sets whether or not the context is being used for ingestion
     * as opposed to discovery.
     * 
     * @param ingestionInProgress the ingestion state flag
     */
    public void setIngestionInProgress(boolean ingestionInProgress) {
        this._ingestionInProgress = ingestionInProgress;
    }

    /**
     * Copied from PropertySetterUtil, which is in apisvc and 
     * can't be accessed from controllersvc.
     */
    public static String extractValueFromStringSet(String key, StringSetMap volumeInformation) {
        try {
            StringSet availableValueSet = volumeInformation.get(key);
            if (null != availableValueSet) {
                for (String value : availableValueSet) {
                    return value;
                }
            }
        } catch (Exception e) {
            _logger.error(e.getMessage(), e);
        }
        return null;
    }

    /**
     * Copied from PropertySetterUtil, which is in apisvc and 
     * can't be accessed from controllersvc.
     */
    public static StringSet extractValuesFromStringSet(String key, StringSetMap volumeInformation) {
        try {
            StringSet returnSet = new StringSet();
            StringSet availableValueSet = volumeInformation.get(key);
            if (null != availableValueSet) {
                for (String value : availableValueSet) {
                    returnSet.add(value);
                }
            }
            return returnSet;
        } catch (Exception e) {
            _logger.error(e.getMessage(), e);
        }
        return null;
    }

    /**
     * Copied from VolumeIngestionUtil, which is in apisvc and 
     * can't be accessed from controllersvc.
     */
    public List<UnManagedVolume> getUnManagedSnaphots(UnManagedVolume unManagedVolume) {
        List<UnManagedVolume> snapshots = new ArrayList<UnManagedVolume>();
        _logger.info("checking for snapshots related to unmanaged volume " + unManagedVolume.getLabel());
        if (checkUnManagedVolumeHasReplicas(unManagedVolume)) {
            StringSet snapshotNativeIds = extractValuesFromStringSet(
                    SupportedVolumeInformation.SNAPSHOTS.toString(),
                    unManagedVolume.getVolumeInformation());
            List<URI> snapshotUris = new ArrayList<URI>();
            if (null != snapshotNativeIds && !snapshotNativeIds.isEmpty()) {
                for (String nativeId : snapshotNativeIds) {
                    _logger.info("   found snapshot native id " + nativeId);
                    URIQueryResultList unManagedVolumeList = new URIQueryResultList();
                    _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                            .getVolumeInfoNativeIdConstraint(nativeId), unManagedVolumeList);
                    if (unManagedVolumeList.iterator().hasNext()) {
                        snapshotUris.add(unManagedVolumeList.iterator().next());
                    }
                }
            }
            if (!snapshotUris.isEmpty()) {
                snapshots = _dbClient.queryObject(UnManagedVolume.class, snapshotUris, true);
                _logger.info("   returning snapshot objects: " + snapshots);
            }

        }

        return snapshots;
    }

    /**
     * Copied from VolumeIngestionUtil, which is in apisvc and 
     * can't be accessed from controllersvc.
     */
    public List<UnManagedVolume> getUnManagedClones(UnManagedVolume unManagedVolume) {
        List<UnManagedVolume> clones = new ArrayList<UnManagedVolume>();
        _logger.info("checking for clones (full copies) related to unmanaged volume " + unManagedVolume.getLabel());
        if (checkUnManagedVolumeHasReplicas(unManagedVolume)) {
            StringSet cloneNativeIds = extractValuesFromStringSet(
                    SupportedVolumeInformation.FULL_COPIES.toString(),
                    unManagedVolume.getVolumeInformation());
            List<URI> cloneUris = new ArrayList<URI>();
            if (null != cloneNativeIds && !cloneNativeIds.isEmpty()) {
                for (String nativeId : cloneNativeIds) {
                    _logger.info("\tfound clone native id " + nativeId);
                    URIQueryResultList unManagedVolumeList = new URIQueryResultList();
                    _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                            .getVolumeInfoNativeIdConstraint(nativeId), unManagedVolumeList);
                    if (unManagedVolumeList.iterator().hasNext()) {
                        cloneUris.add(unManagedVolumeList.iterator().next());
                    }
                }
            }
            if (!cloneUris.isEmpty()) {
                clones = _dbClient.queryObject(UnManagedVolume.class, cloneUris, true);
                _logger.info("\treturning clone objects: " + clones);
            }

        }

        return clones;
    }

    /**
     * Copied from VolumeIngestionUtil, which is in apisvc and 
     * can't be accessed from controllersvc.
     */
    public boolean checkUnManagedVolumeHasReplicas(UnManagedVolume unManagedVolume) {
        StringMap unManagedVolumeCharacteristics = unManagedVolume.getVolumeCharacterstics();
        String volumeHasReplicas = unManagedVolumeCharacteristics
                .get(SupportedVolumeCharacterstics.HAS_REPLICAS.toString());
        String volumeHasRemoteReplicas = unManagedVolumeCharacteristics
                .get(SupportedVolumeCharacterstics.REMOTE_MIRRORING.toString());
        if (null != volumeHasReplicas
                && Boolean.parseBoolean(volumeHasReplicas)
                || (null != volumeHasRemoteReplicas && Boolean
                        .parseBoolean(volumeHasRemoteReplicas))) {
            return true;
        }
        return false;
    }

    /**
     * Returns a Map of backend supporting device name
     * to the UnManagedVolume that contains it.  This is 
     * necessary because there is no way to query the values
     * in a StringSetMap in the database.  This is used for
     * Full clone (i.e. virtual volume clone) detection.
     * 
     * @return a Map of backend supporting device name to its UnManagedVolume
     */
    public Map<String, URI> getVplexDeviceToUnManagedVolumeMap() {
        URI vplexUri = _unmanagedVirtualVolume.getStorageSystemUri();
        Iterator<UnManagedVolume> allUnmanagedVolumes = null;
        long dingleTimer = new Date().getTime();
        Map<String, URI> deviceToUnManagedVolumeMap = new HashMap<String, URI>();
        List<URI> storageSystem = new ArrayList<URI>();
        storageSystem.add(vplexUri);
        try {
            List<URI> ids = _dbClient.queryByType(UnManagedVolume.class, true);
            List<String> fields = new ArrayList<String>();
            fields.add("storageDevice");
            fields.add("volumeInformation");
            allUnmanagedVolumes = _dbClient.queryIterativeObjectFields(UnManagedVolume.class, fields, ids);
        } catch (Exception e) {
            // have to do this because the database sometimes returns UnManagedVolume 
            // objects that no longer exist and are null
            _logger.warn("Exception caught:", e);
        }
        if (null != allUnmanagedVolumes) {
            while (allUnmanagedVolumes.hasNext()) {
                try {
                    UnManagedVolume vol = allUnmanagedVolumes.next();
                    if (vol.getStorageSystemUri().equals(vplexUri)) {
                        String supportingDeviceName =
                                extractValueFromStringSet(
                                        SupportedVolumeInformation.VPLEX_SUPPORTING_DEVICE_NAME.toString(),
                                        vol.getVolumeInformation());
                        if (null != supportingDeviceName) {
                            deviceToUnManagedVolumeMap.put(supportingDeviceName, vol.getId());
                        }
                    }
                } catch (NoSuchElementException ex) {
                    // have to do this because the database sometimes returns UnManagedVolume 
                    // objects that no longer exist and are null
                    _logger.warn("for some reason the database returned nonsense: "
                            + ex.getLocalizedMessage());
                }
            }
        } else {
            throw VPlexApiException.exceptions.backendIngestionContextLoadFailure(
                    "could not load deviceToUnManagedVolumeMap");
        }
        _logger.info("creating deviceToUnManagedVolumeMap took {} ms", new Date().getTime() - dingleTimer);
        return deviceToUnManagedVolumeMap;
    }

    /**
     * Returns the performance report string.
     * 
     * @return the performance report string
     */
    public String getPerformanceReport() {
        return _tracker.getPerformanceReport();
    }

    /**
     * Simple private inner class to hold performance data for this context.
     */
    private class BackendDiscoveryPerformanceTracker {

        public long startTime = new Date().getTime();
        public long fetchBackendVolumes = 0;
        public long fetchSnapshots = 0;
        public long fetchBackendOnlyClones = 0;
        public long fetchFullClones = 0;
        public long fetchMirrors = 0;
        public long fetchTopLevelDevice = 0;

        public String getPerformanceReport() {
            StringBuilder report = new StringBuilder("\n\nBackend Discovery Performance Report\n");
            
            report.append("\tvolume name: ").append(_unmanagedVirtualVolume.getLabel()).append("\n");
            report.append("\ttotal discovery time: ").append(System.currentTimeMillis() - startTime).append("ms\n");
            report.append("\tfetch backend volumes: ").append(fetchBackendVolumes).append("ms\n");
            report.append("\tfetch snapshots: ").append(fetchSnapshots).append("ms\n");
            report.append("\tfetch backend clones: ").append(fetchBackendOnlyClones).append("ms\n");
            report.append("\tfetch full clones: ").append(fetchFullClones).append("ms\n");
            report.append("\tfetch mirrors: ").append(fetchMirrors).append("ms\n");
            report.append("\tfetch top-level device: ").append(fetchTopLevelDevice).append("ms\n");

            return report.toString();
        }
    }

    /**
     * Returns a detailed report on the state of everything in this context,
     * useful for debugging.
     * 
     * @return a detailed report on the context
     */
    public String toStringDebug() {
        StringBuilder s = new StringBuilder("\n\nVplexBackendIngestionContext \n\t ");
        s.append("unmanaged virtual volume: ").append(this._unmanagedVirtualVolume).append(" \n\t ");
        s.append("unmanaged backend volume(s): ").append(this.getUnmanagedBackendVolumes()).append(" \n\t ");
        s.append("unmanaged snapshots: ").append(this.getUnmanagedSnapshots()).append(" \n\t ");
        s.append("unmanaged full clones: ").append(this.getUnmanagedFullClones()).append(" \n\t ");
        s.append("unmanaged backend only clones: ").append(this.getUnmanagedBackendOnlyClones()).append(" \n\t ");
        s.append("unmanaged mirrors: ").append(this.getUnmanagedMirrors()).append(" \n\t ");
        s.append("ingested objects: ").append(this.getIngestedObjects()).append(" \n\t ");
        s.append("created objects map: ").append(this.getCreatedObjectMap()).append(" \n\t ");
        s.append("updated objects map: ");
        for (Entry<String, List<DataObject>> e : this.getUpdatedObjectMap().entrySet()) {
            s.append(e.getKey()).append(": ");
            for (DataObject o : e.getValue()) {
                s.append(o.getLabel()).append("; ");
            }
        }
        s.append(" \n\t ");
        s.append("processed unmanaged volumes: ").append(this.getProcessedUnManagedVolumeMap()).append("\n");
        return s.toString();
    }

    @Override
    public String toString() {
        if (_logger.isDebugEnabled()) {
            return toStringDebug();
        } 

        return super.toString();
    }
}