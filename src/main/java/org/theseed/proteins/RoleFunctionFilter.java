/**
 *
 */
package org.theseed.proteins;

import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * This filter eliminates multi-functional roles and hypothetical proteins.
 *
 * @author Bruce Parrello
 *
 */
public class RoleFunctionFilter extends FunctionFilter {

    // FIELDS
    /** multi-functional role pattern */
    private static final Pattern MULTI_ROLE = Pattern.compile("\\s@\\s|\\s;|\\s/\\s");

    public RoleFunctionFilter(FunctionMap funcMap, FunctionFilter.Parms processor) {
        super(funcMap);
    }

    @Override
    protected String testFunction(String function, FunctionMap funcMap) {
        String retVal = null;
        if (! StringUtils.equalsIgnoreCase(function, "hypothetical protein")) {
            if (! FunctionFilter.contains(function, MULTI_ROLE))
                retVal = this.getId(function);
        }
        return retVal;
    }

}
