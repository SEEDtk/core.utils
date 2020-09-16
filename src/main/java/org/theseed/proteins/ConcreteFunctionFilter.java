/**
 *
 */
package org.theseed.proteins;

import java.util.regex.Pattern;

/**
 * This filter eliminates hypothetical or putative functional assignments.
 *
 * @author Bruce Parrello
 *
 */
public class ConcreteFunctionFilter extends FunctionFilter {

    // FIELDS
    private static final Pattern VAGUE_FUNCTION = Pattern.compile("hypothetical|putative|mobile\\selement", Pattern.CASE_INSENSITIVE);

    public ConcreteFunctionFilter(FunctionMap funcMap, FunctionFilter.Parms processor) {
        super(funcMap);
    }

    @Override
    protected String testFunction(String function, FunctionMap funcMap) {
        String retVal = null;
        if (! FunctionFilter.contains(function, VAGUE_FUNCTION))
            retVal = this.getId(function);
        return retVal;
    }

}
