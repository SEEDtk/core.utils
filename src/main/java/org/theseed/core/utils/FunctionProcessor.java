/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.genome.core.CoreUtilities;
import org.theseed.utils.BaseReportProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This command scans the organism directories for the CoreSEED and outputs one feature for each unique functional
 * assignment in CoreSEED.  The standard output will be a three-column tab-delimited file, for each feature containing the
 * feature ID, the feature type, and the assignment.
 *
 * The positional parameter should be the name of the CoreSEED directory.  The command-line parameters are
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -o	name of output file (if not the standard output)
 *
 * @author Bruce Parrello
 *
 */
public class FunctionProcessor extends BaseReportProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(FunctionProcessor.class);
    /** set of MD5s for functions already encountered */
    private Set<String> alreadyFound;
    /** MD5 digest computer */
    private MessageDigest md;
    /** core utilities object */
    private CoreUtilities coreSEED;

    // COMMAND-LINE OPTIONS

    /** coreSEED data directory */
    @Argument(index = 0, metaVar = "/vol/core-seed/FIGdisk/FIG/Data", usage = "name of the coreSEED data directory", required = true)
    private File coreDir;

    @Override
    protected void setReporterDefaults() {
    }

    @Override
    protected void validateReporterParms() throws IOException, ParseFailureException {
        // Verify the coreSEED data directory.
        if (! this.coreDir.isDirectory())
            throw new FileNotFoundException("CoreSEED data directory " + this.coreDir + " is not found or invalid.");
        File orgDir = new File(this.coreDir, "Organisms");
        if (! orgDir.isDirectory())
            throw new FileNotFoundException(this.coreDir + " has no Organisms subdirectory.");
        // Get an iterator through the organism sub-directories.
        this.coreSEED = new CoreUtilities(orgDir);
        log.info("Connected to organism directory {}.", orgDir);
    }

    @Override
    protected void runReporter(PrintWriter writer) throws Exception {
        // Create the MD5 computer.  We save the functions as MD5s to save space.
        this.md = MessageDigest.getInstance("MD5");
        // Create the already-found set.
        this.alreadyFound = new HashSet<String>(125000);
       // Now write the output header.  Note that the column names are mimicking A PATRIC feature-table dump.
        writer.println("patric_id\tfeature_type\tproduct");
        // Start with the dummy nulls.  We give them a bogus genome ID.
        writer.println("fig|66666666.1.rna.1\trRNA\t");
        writer.println("fig|66666666.1.rna.2\ttRNA\t");
        writer.println("fig|66666666.1.rna.3\tmisc_RNA\t");
        writer.println("fig|66666666.1.lrr.1\trepeat_region\t");
        writer.println("fig|66666666.1.pg.1\tpseudogene\t");
        writer.println("fig|66666666.1.peg.1\tCDS\t");
        // This counts the total null functions.
        int nullCount = 0;
        // We also need global totals.
        int allFidCount = 0;
        int allDupCount = 0;
        // Count the genomes processed.
        int gCount = 0;
        // Loop through the organism directories.
        Iterable<String> orgDirs = this.coreSEED.getGenomes();
        for (String genomeId : orgDirs) {
            gCount++;
            // Get the assigned_functions map.
            Map<String, String> functionMap = this.coreSEED.getGenomeFunctions(genomeId, "peg", "lrr", "rna", "pg");
            // Now loop through the function map.
            int fidCount = 0;
            int funCount = 0;
            int dupCount = 0;
            for (var funEntry : functionMap.entrySet()) {
                // Get the ID, type, and function.  These are our output values.
                String fid = funEntry.getKey();
                String function = funEntry.getValue();
                String type = CoreUtilities.typeOf(fid);
                fidCount++;
                // Here we check for a null function, and if we have one, we save it if it is the first
                // of its type.
                if (StringUtils.isBlank(function))
                    nullCount++;
                else {
                    // Here we have a real function. Compute the checksum to insure it is new.
                    String check = Hex.encodeHexString(this.md.digest(function.getBytes()));
                    if (this.alreadyFound.contains(check)) {
                        // Here we have a duplicate.
                        dupCount++;
                    } else {
                        // Now we have a new function.  Save the checksum to prevent duplicates.
                        this.alreadyFound.add(check);
                        // Compute the output type.  For rna, this requires looking at the function.
                        String realType;
                        switch (type) {
                        case "peg" :
                            realType = "CDS";
                            break;
                        case "lrr" :
                            realType = "repeat_region";
                            break;
                        case "pg" :
                            realType = "pseudogene";
                            break;
                        case "rna" :
                            String lcFunction = function.toLowerCase();
                            if (lcFunction.contains("rrna") || lcFunction.contains("ribosomal"))
                                realType = "rRNA";
                            else if (lcFunction.startsWith("trna"))
                                realType = "tRNA";
                            else
                                realType = "misc_RNA";
                            break;
                        default :
                            realType = type;
                        }
                        // Write the output line.
                        writer.println(fid + "\t" + realType + "\t" + function);
                        funCount++;
                    }
                }
            }
            log.info("{} genomes complete, {} contained {} features, {} duplicate functions, and {} functions to output.",
                    gCount, genomeId, fidCount, dupCount, funCount);
            allFidCount += fidCount;
            allDupCount += dupCount;
        }
        log.info("{} null functions encountered.", nullCount);
        log.info("{} genomes processed, {} unique non-null functions found, {} features, {} duplicates.", gCount,
                this.alreadyFound.size(), allFidCount, allDupCount);
    }


}
