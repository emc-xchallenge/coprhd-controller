/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller.impl.block.taskcompleter;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.BlockMirror;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.services.OperationTypeEnum;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;
import com.google.common.base.Joiner;

public class BlockMirrorDeactivateCompleter extends BlockMirrorTaskCompleter {
    private static final Logger _log = LoggerFactory.getLogger(BlockMirrorDeactivateCompleter.class);
    public static final String MIRROR_DEACTIVATED_MSG = "Mirror %s deactivated for volume %s";
    public static final String MIRROR_DEACTIVATE_FAILED_MSG = "Failed to deactivate mirror %s for volume %s";
    private List<URI> _promotees = null;

    public BlockMirrorDeactivateCompleter(URI mirror, String opId) {
        super(BlockMirror.class, mirror, opId);
    }

    public BlockMirrorDeactivateCompleter(List<URI> mirrorList, List<URI> promotees, String opId) {
        super(BlockMirror.class, mirrorList, opId);
        this._promotees = promotees;
    }

    public List<URI> getPromotees() {
        return this._promotees;
    }

    @Override
    protected void complete(DbClient dbClient, Operation.Status status, ServiceCoded coded) throws DeviceControllerException {
        try {
            super.complete(dbClient, status, coded);
            List<BlockMirror> mirrorList = dbClient.queryObject(BlockMirror.class, getIds());
            for (BlockMirror mirror : mirrorList) {
                Volume volume = dbClient.queryObject(Volume.class, mirror.getSource());
                switch (status) {
                    case error:
                        dbClient.error(BlockMirror.class, mirror.getId(), getOpId(), coded);
                        dbClient.error(Volume.class, volume.getId(), getOpId(), coded);
                        break;
                    default:
                        dbClient.ready(BlockMirror.class, mirror.getId(), getOpId());
                        dbClient.ready(Volume.class, volume.getId(), getOpId());
                }
                recordBlockMirrorOperation(dbClient, OperationTypeEnum.DEACTIVATE_VOLUME_MIRROR,
                        status, eventMessage(status, volume, mirror), mirror, volume);
            }

            List<URI> promotees = getPromotees();
            if (promotees != null && !promotees.isEmpty()) {
                List<Volume> promotedVolumes = dbClient.queryObject(Volume.class, promotees);
                List<Volume> volumesToUpdate = new ArrayList<Volume>();
                for (Volume volume : promotedVolumes) {
                    switch (status) {
                        case error:
                            dbClient.error(Volume.class, volume.getId(), getOpId(), coded);
                            volume.setInactive(true);
                            volumesToUpdate.add(volume);
                            break;
                        default:
                            dbClient.ready(Volume.class, volume.getId(), getOpId());
                    }
                }

                if (!volumesToUpdate.isEmpty()) {
                    dbClient.persistObject(volumesToUpdate);
                }
            }
        } catch (Exception e) {
            _log.error("Failed updating status. BlockMirrorDeactivate {}, for task " + getOpId(), Joiner.on("\t").join(getIds()), e);
        }
    }

    private String eventMessage(Operation.Status status, Volume volume, BlockMirror mirror) {
        return (Operation.Status.ready == status) ?
                String.format(MIRROR_DEACTIVATED_MSG, mirror.getLabel(), volume.getLabel()) :
                String.format(MIRROR_DEACTIVATE_FAILED_MSG, mirror.getLabel(), volume.getLabel());
    }
}
