package com.emc.vipr.model.sys.recovery;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.List;

@XmlRootElement(name = "recovery_status")
public class HistoryRecoveryStatus {
    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    private String clusterId ;

    public List<NodeRecoveryStatus> getRecoveryStatuses() {
        return recoveryStatuses;
    }

    @XmlElement(name = "statuses")
        public void setRecoveryStatuses(List<NodeRecoveryStatus> recoveryStatuses) {
            this.recoveryStatuses = recoveryStatuses;
        }

    private List<NodeRecoveryStatus> recoveryStatuses;
}

