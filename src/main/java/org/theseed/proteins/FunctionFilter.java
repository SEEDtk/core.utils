/**
 *
 */
package org.theseed.proteins;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.theseed.roles.RoleUtilities;

/**
 * This is the base class for function filtering.  It is constructed with a function map.  Each call from the client
 * presents a function assignment, and then a function ID is returned, or NULL if the function is being rejected.
 * Subclasses can decide to prohibit functions not in the map, or make any other restrictions.
 *
 * @author Bruce Parrello
 *
 */
public abstract class FunctionFilter {

    // FIELDS

    /** function map to use */
    private FunctionMap funMap;

    /**
     * Construct a new function filter.
     *
     * @param funMap	function map to use
     */
    public FunctionFilter(FunctionMap funcMap) {
        this.funMap = funcMap;
    }

    /**
     * Convert a functional assignment to a function ID.
     *
     * @param function	functional assignment to convert
     *
     * @return the ID of the function, or NULL if it is rejected
     */
    public String checkFunction(String function) {
        String retVal = null;
        if (function != null && ! function.isEmpty())
            retVal = this.testFunction(RoleUtilities.commentFree(function), this.funMap);
        return retVal;
    }

    /**
     * This is a utility method that performs the common task of converting a function description
     * to an ID.
     *
     * @param function	function description
     *
     * @return the ID of the function, or NULL if the function is invalid
     */
    protected String getId(String function) {
        String retVal = null;
        Function fun = this.funMap.findOrInsert(function);
        if (fun != null) retVal = fun.getId();
        return retVal;
    }

    /**
     * This is a utility method that checks for the occurrence of a pattern in a function.
     *
     * @param function	function description
     * @param pattern	pattern to check
     *
     * @return TRUE if the function description contains the pattern, else FALSE
     */
    protected static boolean contains(String function, Pattern pattern) {
        Matcher m = pattern.matcher(function);
        return m.find();
    }

    /**
     * Determine if this function is acceptable, and if it is, return its ID.
     *
     * @param function	functional assignment to process
     * @param funcMap	controlling function map
     *
     * @return the ID of the function, or NULL if it is rejected
     */
    protected abstract String testFunction(String function, FunctionMap funcMap);

    /**
     * This interface is used by clients to pass parameters to the subclasses.
     */
    public static interface Parms {

    }

    /**
     * enum for filter types
     */
    public static enum Type {
        ROLE, CONCRETE;

        /**
         * @return a function filter
         *
         * @param funMap		controlling function map
         * @param processor		client processor (for additional parameters)
         */
        public FunctionFilter create(FunctionMap funcMap, Parms processor) {
            FunctionFilter retVal = null;
            switch (this) {
            case ROLE :
                retVal = new RoleFunctionFilter(funcMap, processor);
                break;
            case CONCRETE :
                retVal = new ConcreteFunctionFilter(funcMap, processor);
                break;
            }
            return retVal;
        }
    }

}
