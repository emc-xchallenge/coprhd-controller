/**
 *  Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */

package com.emc.storageos.systemservices.impl.propertyhandler;

import com.emc.storageos.svcs.errorhandling.resources.APIException;

public class ArgumentValidator {

    /**
     * Validates that a number is within the inclusive range specified.
     * @param value the suppled number to check
     * @param minimum the minimum acceptable value
     * @param maximum the maximum acceptable value
     * @param name the name of the field where the value originated
     */
    public static void checkRange(final int value, final int minimum, final int maximum, final String name) {
        if (value < minimum || value > maximum) {
            throw APIException.badRequests.parameterNotWithinRange(name, value, minimum, maximum, "");
        }
    }
}
