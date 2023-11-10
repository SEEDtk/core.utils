/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;

import org.kohsuke.args4j.Argument;
import org.theseed.basic.BaseProcessor;
import org.theseed.genome.FeatureSearch;

/**
 * This command searches for features containing a specific search expression.  The positional parameters are the name of the
 * coreSEED data directory and the search expression itself.  All searches are case-insensitive.  The features found and their
 * functions will be written to the standard output in tab-delimited format.
 *
 * The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	show more detailed progress messages.
 *
 * @author Bruce Parrello
 *
 */
public class FidSearchProcessor extends BaseProcessor {

    // COMMAND-LINE OPTIONS

    /** coreSEED data directory */
    @Argument(index = 0, metaVar = "CoreSEED", usage = "coreSEED data directory")
    private File coreDir;

    /** search expression */
    @Argument(index = 1, metaVar = "regex", usage = "search expression")
    private String regex;

    @Override
    protected void setDefaults() {
    }

    @Override
    protected boolean validateParms() throws IOException {
        // Verify the coreSEED directory.
        if (! this.coreDir.isDirectory())
            throw new FileNotFoundException("CoreSEED data directory " + this.coreDir + " is not found or invalid.");
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        FeatureSearch searcher = new FeatureSearch(this.coreDir);
        Map<String, String> found = searcher.findFeatures(this.regex);
        System.out.println("feature_id\tfunction");
        for (Map.Entry<String, String> funEntry : found.entrySet()) {
            System.out.format("%s\t%s%n", funEntry.getKey(), funEntry.getValue());
        }
    }

}
