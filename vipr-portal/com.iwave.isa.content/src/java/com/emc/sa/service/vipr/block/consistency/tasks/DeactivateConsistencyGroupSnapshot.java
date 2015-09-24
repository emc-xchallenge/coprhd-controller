/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.sa.service.vipr.block.consistency.tasks;

import java.net.URI;

import com.emc.sa.service.vipr.tasks.WaitForTasks;
import com.emc.storageos.model.NamedRelatedResourceRep;
import com.emc.storageos.model.block.BlockConsistencyGroupRestRep;
import com.emc.vipr.client.Tasks;

public class DeactivateConsistencyGroupSnapshot extends
        WaitForTasks<BlockConsistencyGroupRestRep> {

    private URI consistencyGroup;

    public DeactivateConsistencyGroupSnapshot(URI consistencyGroup) {
        this.consistencyGroup = consistencyGroup;
    }

    @Override
    protected Tasks<BlockConsistencyGroupRestRep> doExecute() throws Exception {
        NamedRelatedResourceRep item = getClient().blockConsistencyGroups().getSnapshots(consistencyGroup).get(0);
        return getClient().blockConsistencyGroups().deactivateSnapshot(consistencyGroup, item.getId());
    }
}