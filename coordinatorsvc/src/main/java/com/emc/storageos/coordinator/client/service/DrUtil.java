/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.coordinator.client.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.coordinator.client.model.Constants;
import com.emc.storageos.coordinator.client.model.Site;
import com.emc.storageos.coordinator.client.model.SiteInfo;
import com.emc.storageos.coordinator.client.model.SiteState;
import com.emc.storageos.coordinator.client.service.impl.CoordinatorClientImpl;
import com.emc.storageos.coordinator.client.service.impl.CoordinatorClientInetAddressMap;
import com.emc.storageos.coordinator.common.Configuration;
import com.emc.storageos.coordinator.common.Service;
import com.emc.storageos.coordinator.exceptions.CoordinatorException;
import com.emc.storageos.coordinator.exceptions.RetryableCoordinatorException;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.emc.storageos.svcs.errorhandling.resources.ServiceCode;
import com.emc.vipr.model.sys.ClusterInfo;

/**
 * Common utility functions for Disaster Recovery
 */
public class DrUtil {
    private static final Logger log = LoggerFactory.getLogger(DrUtil.class);
    
    private static final int COORDINATOR_PORT = 2181;
    public static final String ZOOKEEPER_MODE_OBSERVER = "observer";
    public static final String ZOOKEEPER_MODE_READONLY = "read-only";
    
    private CoordinatorClient coordinator;

    public DrUtil(CoordinatorClient coordinator) {
        this.coordinator = coordinator;
    }
    
    public CoordinatorClient getCoordinator() {
        return coordinator;
    }

    public void setCoordinator(CoordinatorClient coordinator) {
        this.coordinator = coordinator;
    }
    
    /**
     * Check if current site is primary
     * 
     * @return true for primary. otherwise false
     */
    public boolean isPrimary() {
        return getPrimarySiteId().equals(coordinator.getSiteId());
    }
    
    /**
     * Check if current site is a standby site
     * 
     * @return true for standby site. otherwise false
     */
    public boolean isStandby() {
        return !isPrimary();
    }
    
    /**
     * Get primary site in current vdc
     * 
     * @return
     */
    public String getPrimarySiteId() {
        return getPrimarySiteId(getLocalVdcShortId());
    }

    /**
     * Get primary site in a specific vdc
     *
     * @param vdcShortId short id of the vdc
     * @return uuid of the primary site
     */
    public String getPrimarySiteId(String vdcShortId) {
        Configuration config = coordinator.queryConfiguration(Constants.CONFIG_DR_PRIMARY_KIND, vdcShortId);
        return config.getConfig(Constants.CONFIG_DR_PRIMARY_SITEID);
    }

    /**
     * Get local site configuration
     *
     * @return local site configuration
     */
    public Site getLocalSite() {
        return getSiteFromLocalVdc(coordinator.getSiteId());
    }

    /**
     * Load site information from local vdc
     * 
     * @param siteId
     * @return
     */
    public Site getSiteFromLocalVdc(String siteId) {
        String siteKind = String.format("%s/%s", Site.CONFIG_KIND, getLocalVdcShortId());
        Configuration config = coordinator.queryConfiguration(siteKind, siteId);
        if (config != null) {
            return new Site(config);
        }
        throw CoordinatorException.retryables.cannotFindSite(siteId);
    }
    
    
    /**
     * List all standby sites in current vdc
     * 
     * @return list of standby sites
     */
    public List<Site> listStandbySites() {
        String primaryId = this.getPrimarySiteId();
        List<Site> result = new ArrayList<>();
        for(Site site : listSites()) {
            if (!site.getUuid().equals(primaryId)) {
                result.add(site);
            }
        }
        return result;
    }

    /**
     * Get a map of all sites of all vdcs.
     * The keys are VDC short ids, the values are lists of sites within each vdc
     *
     * @return map of vdc -> list of sites
     */
    public Map<String, List<Site>> getVdcSiteMap() {
        Map<String, List<Site>> vdcSiteMap = new HashMap<>();
        for(Configuration vdcConfig : coordinator.queryAllConfiguration(Site.CONFIG_KIND)) {
            String siteKind = String.format("%s/%s", Site.CONFIG_KIND, vdcConfig.getId());
            List<Site> sites = new ArrayList<>();
            for (Configuration siteConfig : coordinator.queryAllConfiguration(siteKind)) {
                sites.add(new Site(siteConfig));
            }
            vdcSiteMap.put(vdcConfig.getId(), sites);
        }
        return vdcSiteMap;
    }

    /**
     * List all sites in current vdc
     * 
     * @return list of all sites
     */
    public List<Site> listSites() {
        List<Site> result = new ArrayList<>();
        String siteKind = String.format("%s/%s", Site.CONFIG_KIND, getLocalVdcShortId());
        for (Configuration siteConfig : coordinator.queryAllConfiguration(siteKind)) {
            result.add(new Site(siteConfig));
        }
        return result;
    }
    
    /**
     * Get number of running services in given site
     * 
     * @return number to indicate servers 
     */
    public int getNumberOfLiveServices(String siteUuid, String svcName, String svcVersion) {
        try {
            List<Service> svcs = coordinator.locateAllServices(siteUuid, svcName, svcVersion, null, null);
            return svcs.size();
        } catch (RetryableCoordinatorException ex) {
            if (ex.getServiceCode() == ServiceCode.COORDINATOR_SVC_NOT_FOUND) {
                return 0;
            }
            throw ex;
        }
    }
    
    /**
     * Check if site is up and running
     * 
     * @param siteId
     * @return true if any syssvc is running on this site
     */
    public boolean isSiteUp(String siteId) {
        // Get service beacons for given site - - assume syssvc on all sites share same service name in beacon
        try {
            String syssvcName = ((CoordinatorClientImpl)coordinator).getSysSvcName();
            String syssvcVersion = ((CoordinatorClientImpl)coordinator).getSysSvcVersion();
            List<Service> svcs = coordinator.locateAllServices(siteId, syssvcName, syssvcVersion, null, null);

            List<String> nodeList = new ArrayList<>();
            for(Service svc : svcs) {
                nodeList.add(svc.getNodeId());
            }
            log.info("Site {} is up. active nodes {}", siteId, StringUtils.join(nodeList, ","));
            return true;
        } catch (CoordinatorException ex) {
            if (ex.getServiceCode() == ServiceCode.COORDINATOR_SVC_NOT_FOUND) {
                return false; // no service beacon found for given site
            }
            log.error("Unexpected error when checking site service becons", ex);
            return true;
        }
    }
    
    /**
     * Update SiteInfo's action and version for specified site id 
     * @param siteId site UUID
     * @param action action to take
     */
    public void updateVdcTargetVersion(String siteId, String action) throws Exception {
        SiteInfo siteInfo;
        SiteInfo currentSiteInfo = coordinator.getTargetInfo(siteId, SiteInfo.class);
        if (currentSiteInfo != null) {
            siteInfo = new SiteInfo(System.currentTimeMillis(), action, currentSiteInfo.getTargetDataRevision());
        } else {
            siteInfo = new SiteInfo(System.currentTimeMillis(), action);
        }
        coordinator.setTargetInfo(siteId, siteInfo);
        log.info("VDC target version updated to {} for site {}", siteInfo.getVdcConfigVersion(), siteId);
    }

    /**
     * Check if a specific site is the local site
     * @param site
     * @return true if the specified site is the local site
     */
    public boolean isLocalSite(Site site) {
        return site.getUuid().equals(coordinator.getSiteId());
    }
    
    /**
     * Generate Cassandra data center name for given site.
     * 
     * @param site
     * @return
     */
    public String getCassandraDcId(Site site) {
        String dcId = null;
        if (StringUtils.isEmpty(site.getStandbyShortId()) || site.getVdcShortId().equals(site.getStandbyShortId())) {
            dcId = site.getVdcShortId();
        } else {
            dcId = site.getUuid();
        }

        log.info("Cassandra DC Name is {}", dcId);
        return dcId;
    }

    /**
     * Get the short id of local VDC
     */
    public String getLocalVdcShortId() {
        Configuration localVdc = coordinator.queryConfiguration(Constants.CONFIG_GEO_LOCAL_VDC_KIND,
                Constants.CONFIG_GEO_LOCAL_VDC_ID);
        return localVdc.getConfig(Constants.CONFIG_GEO_LOCAL_VDC_SHORT_ID);
    }
    
    /**
     * Use Zookeeper 4 letter command to check status of local coordinatorsvc. The return value could 
     * be one of the following - follower, leader, observer, read-only
     * 
     * @return zookeeper mode
     */
    public String getLocalCoordinatorMode(String nodeId) {
        Socket sock = null;
        try {
            log.info("get local coordinator mode from {}:{}", nodeId, COORDINATOR_PORT);
            sock = new Socket(nodeId, COORDINATOR_PORT);
            OutputStream output = sock.getOutputStream();
            output.write("mntr".getBytes());
            sock.shutdownOutput();
            
            BufferedReader input =
                new BufferedReader(new InputStreamReader(sock.getInputStream()));
            String answer;
            while ((answer = input.readLine()) != null) {
                if (answer.startsWith("zk_server_state")){
                    String state = StringUtils.trim(answer.substring("zk_server_state".length()));
                    log.info("Get current zookeeper mode {}", state);
                    return state;
                }
            }
            input.close();
        } catch(IOException ex) {
            log.warn("Unexpected IO errors when checking local coordinator state {}", ex.toString());
        } finally {
            try {
                if (sock != null) sock.close();
            } catch (Exception ex) {}
        }
        return null;
    }
    
    /*
     * Internal method to check whether failover to standby is allowed
     */
    public void precheckForFailover() {
        Site standby = getLocalSite();
        String standbyUuid = standby.getUuid();
        
        // show be only standby
        if (isPrimary()) {
            throw APIException.internalServerErrors.failoverPrecheckFailed(standbyUuid, "Failover can't be executed in primary site");
        }

        // should be SYNCED
        if (standby.getState() != SiteState.STANDBY_SYNCED) {
            throw APIException.internalServerErrors.failoverPrecheckFailed(standbyUuid, "Standby site is not fully synced");
        }

        // Current site is stable
        ClusterInfo.ClusterState state = coordinator.getControlNodesState(standbyUuid, standby.getNodeCount());
        if (state != ClusterInfo.ClusterState.STABLE) {
            log.info("Site {} is not stable {}", standbyUuid, state);
            throw APIException.internalServerErrors.failoverPrecheckFailed(standbyUuid,
                    String.format("Site %s is not stable", standby.getName()));
        }
        
        // this is standby site and NOT in ZK read-only or observer mode,
        // it means primary is down and local ZK has been reconfig to participant
        CoordinatorClientInetAddressMap addrLookupMap = coordinator.getInetAddessLookupMap();
        String myNodeId = addrLookupMap.getNodeId();
        String coordinatorMode = getLocalCoordinatorMode(myNodeId);
        log.info("Local coordinator mode is {}", coordinatorMode);
        if (DrUtil.ZOOKEEPER_MODE_OBSERVER.equals(coordinatorMode) || DrUtil.ZOOKEEPER_MODE_READONLY.equals(coordinatorMode)) {
            log.info("Primary is available now, can't do failover");
            throw APIException.internalServerErrors.failoverPrecheckFailed(standbyUuid, "Primary is available now, can't do failover");
        }
    }
}
