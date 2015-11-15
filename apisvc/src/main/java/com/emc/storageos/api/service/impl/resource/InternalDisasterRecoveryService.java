package com.emc.storageos.api.service.impl.resource;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.coordinator.client.model.Site;
import com.emc.storageos.coordinator.client.model.SiteState;
import com.emc.storageos.coordinator.client.service.DrUtil;
import com.emc.storageos.coordinator.client.service.impl.CoordinatorClientInetAddressMap;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.emc.vipr.model.sys.ClusterInfo;

/**
 * Internal API for disaster recovery service
 */
@Path("/internal/site")
public class InternalDisasterRecoveryService extends ResourceService {
    private static final Logger log = LoggerFactory.getLogger(InternalDisasterRecoveryService.class);
    
    @POST
    @Path("/failovercheck")
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public Response failoverPrecheck() {
        log.info("Precheck for failover internally");
        DrUtil drUtil = new DrUtil(this._coordinator);
        drUtil.precheckForFailover();
        
        return Response.status(Response.Status.ACCEPTED).build();
    }
}
