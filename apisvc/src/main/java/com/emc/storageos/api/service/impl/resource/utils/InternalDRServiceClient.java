package com.emc.storageos.api.service.impl.resource.utils;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.security.helpers.BaseServiceClient;
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
    
    
    public void failoverPrecheck() {
        String getVdcPath = String.format("/site/internal/failoverprecheck");
        WebResource rRoot = createRequest(getVdcPath);
        try {
            addSignature(rRoot).post(ClientResponse.class);
        } catch (Exception e) {
            log.warn("Fail to send request to precheck failover", e);
        }
        
    }
    
    public void failover(String newPrimaryUUid) {
        String getVdcPath = String.format("/site/internal/failover?newPrimaryUUid=%s", newPrimaryUUid);
        WebResource rRoot = createRequest(getVdcPath);
        try {
            addSignature(rRoot).post(ClientResponse.class);
        } catch (Exception e) {
            log.warn("Fail to send request to precheck failover", e);
        }
    }
}
