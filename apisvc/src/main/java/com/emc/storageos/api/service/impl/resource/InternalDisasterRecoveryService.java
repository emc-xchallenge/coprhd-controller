package com.emc.storageos.api.service.impl.resource;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.api.service.impl.resource.utils.InternalDRServiceClient;
import com.emc.storageos.coordinator.client.model.Site;
import com.emc.storageos.coordinator.client.model.SiteInfo;
import com.emc.storageos.coordinator.client.model.SiteState;
import com.emc.storageos.coordinator.client.service.DrUtil;
import com.emc.storageos.security.audit.AuditLogManager;
import com.emc.storageos.services.OperationTypeEnum;
import com.emc.storageos.svcs.errorhandling.resources.APIException;

/**
 * Internal API for disaster recovery service
 */
@Path("/internal/site")
public class InternalDisasterRecoveryService extends DisasterRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(InternalDisasterRecoveryService.class);
    
    @POST
    @Path("/failoverprecheck")
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public void failoverPrecheck() {
        log.info("Precheck for failover internally");
        precheckForFailover();
    }
    
    @POST
    @Path("/failover")
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public void failover(@QueryParam("newPrimaryUUid") String newPrimaryUUid) {
        log.info("Begin to failover internally with newPrimaryUUid {}", newPrimaryUUid);
        
        Site currentSite = drUtil.getLocalSite();
        String uuid = currentSite.getUuid();
        
        try {
            //set state
            Site oldPrimarySite = drUtil.getSiteFromLocalVdc(drUtil.getPrimarySiteId());
            oldPrimarySite.setState(SiteState.PRIMARY_FAILING_OVER);
            coordinator.persistServiceConfiguration(oldPrimarySite.toConfiguration());
            
            
            //set new primary uuid
            coordinator.setPrimarySite(newPrimaryUUid);
            
            //reconfig this site itself
            drUtil.updateVdcTargetVersion(currentSite.getUuid(), SiteInfo.RECONFIG_RESTART);
            
            auditDisasterRecoveryOps(OperationTypeEnum.FAILOVER, AuditLogManager.AUDITLOG_SUCCESS, null, uuid, currentSite.getVip(), currentSite.getName());
        } catch (Exception e) {
            log.error("Error happened when failover at site %s", uuid, e);
            auditDisasterRecoveryOps(OperationTypeEnum.FAILOVER, AuditLogManager.AUDITLOG_FAILURE, null, uuid, currentSite.getVip(), currentSite.getName());
            throw APIException.internalServerErrors.failoverFailed(uuid, e.getMessage());
        }
    }
}
