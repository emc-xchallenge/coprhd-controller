package com.emc.storageos.api.service.impl.resource.utils;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.model.dr.SiteErrorResponse;
import com.emc.storageos.security.helpers.BaseServiceClient;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

public class InternalDRServiceClient extends BaseServiceClient {

    final private Logger log = LoggerFactory.getLogger(InternalDRServiceClient.class);
    
    public InternalDRServiceClient() {
    }

    public InternalDRServiceClient(String server) {
        setServer(server);
    }
    
    @Override
    public void setServer(String server) {
        setServiceURI(URI.create("https://" + server + ":4443"));
    }
    
    
    public SiteErrorResponse failoverPrecheck(String standbyUUID) {
        String getVdcPath = String.format("/site/internal/failoverprecheck");
        WebResource rRoot = createRequest(getVdcPath);
        ClientResponse resp = null;
        try {
            resp = addSignature(rRoot).post(ClientResponse.class);
        } catch (Exception e) {
            log.error("Fail to send request to precheck failover", e);
            throw APIException.internalServerErrors.failoverPrecheckFailed(standbyUUID, String.format("Can't connect to standby to do precheck for failover, %s", e.getMessage()));
        }
        
        SiteErrorResponse errorResponse = resp.getEntity(SiteErrorResponse.class);
        
        if (SiteErrorResponse.isErrorResponse(errorResponse)) {
            throw APIException.internalServerErrors.failoverPrecheckFailed(standbyUUID, errorResponse.getErrorMessage());
        }
        
        return SiteErrorResponse.noError();
    }
    
    public void failover(String newPrimaryUUid) {
        String getVdcPath = String.format("/site/internal/failover?newPrimaryUUid=%s", newPrimaryUUid);
        WebResource rRoot = createRequest(getVdcPath);
        
        try {
            addSignature(rRoot).post(ClientResponse.class);
        } catch (Exception e) {
            log.error("Fail to send request to failover", e);
        }
        
    }
}