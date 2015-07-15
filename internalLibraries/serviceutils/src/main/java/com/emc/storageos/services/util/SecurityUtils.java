/**
 *  Copyright (c) 2008-2015 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */
package com.emc.storageos.services.util;


import com.google.json.JsonSanitizer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.safety.Whitelist;
import org.owasp.esapi.ESAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Class contains web application security helper methods. 
 *
 */
public class SecurityUtils {
	private static final Logger log = LoggerFactory.getLogger(SecurityUtils.class);

    /**
     * The sanitizer fixes missing punctuation, end quotes, and mismatched or missing close brackets.
     * If an input contains only white-space then the valid JSON string null is substituted.
     * @param value
     * @return
     */
    public static String sanitizeJsonString(String value) {
        if (value == null)
                return null;
        value = JsonSanitizer.sanitize(value);

        return value;
    }

	/**
	 * Removes any potential XSS threats from the value.
	 * Depends on the WASP ESAPI (owasp.org) and jsoup libraries (jsoup.org).
	 * 
	 * @param value  data to be cleaned
	 * @return cleaned data
	 */
    public static String stripXSS(String value) {
        if (value == null)
              return null;

        value = ESAPI.encoder().canonicalize( value );
        value = value.replaceAll("\0", "");
        value = Jsoup.clean( value, Whitelist.none() );
        
        return value;
    }

	/**
	 * Uses stripXSS method to sanitize a map of Strings
	 * 
	 * @param Map data to be cleaned
	 * @return cleaned map data
	 */
    public static Map<String, String> stripMapXSS(Map <String, String> valueMap) {
        if (valueMap == null)
              return null;
        
        Map<String, String> xssMap = new HashMap<String, String>();
        Set<Map.Entry<String, String>> set = valueMap.entrySet();

        for (Map.Entry<String, String> entry : set){
                xssMap.put(stripXSS(entry.getKey()), stripXSS(entry.getValue()));
        }
        return xssMap;
     }

}
