package com.emc.storageos.api.service.impl.resource;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal API for disaster recovery service
 */
@Path("/internal/site")
public class InternalDisasterRecoveryService extends DisasterRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(InternalDisasterRecoveryService.class);
    
    @POST
    @Path("/failovercheck")
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public Response failoverPrecheck() {
        log.info("Precheck for failover internally");
        
        precheckForFailover();
        
        return Response.status(Response.Status.ACCEPTED).build();
    }
}
