/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.counters.CountMap;
import org.theseed.proteins.Function;
import org.theseed.proteins.FunctionFilter;
import org.theseed.proteins.FunctionMap;
import org.theseed.utils.BaseProcessor;
import org.theseed.proteins.ProteinFinder;

/**
 * This object creates a table of all the protein sequences in CoreSEED and uses it to build a correspondence
 * between PATRIC functions and CoreSEED functions.
 *
 * The positional parameters are the name of the CoreSEED data directory and the name of a directory containing
 * PATRIC GTOs to process.  The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent progress messages
 *
 * --min		minimum percent for a good mapping
 * --filter		type of function filtering (default is ROLE)
 *
 * @author Bruce Parrello
 *
 */
public class ProteinsProcessor extends BaseProcessor implements FunctionFilter.Parms, ProteinFinder.Parms {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ProteinsProcessor.class);
    /** function map */
    private FunctionMap funMap;
    /** map of protein IDs to function counts */
    private Map<String, CountMap<String>> protFunctionMap;
    /** map of PATRIC functions to CoreSEED functions */
    private Map<String, CountMap<String>> funFunctionMap;
    /** set of proteins found in PATRIC scan (logging only-- info level) */
    private Set<String> protsFound;
    /** active function filter */
    private FunctionFilter funFilter;
    /** organism base directory */
    private File orgBase;

    // COMMAND-LINE OPTIONS

    /** type of function filtering */
    @Option(name = "--filter", usage = "function-filtering scheme")
    private FunctionFilter.Type filterType;

    /** minimum percent for a good mapping */
    @Option(name = "--min", metaVar = "95.0", usage = "minimum percent cases to qualify for a good mapping")
    private double minPercent;

    /** CoreSEED directory */
    @Argument(index = 0, metaVar = "CoreSEED", usage = "CoreSEED data directory")
    private File coreDir;

    /** PATRIC GTO directory */
    @Argument(index = 1, metaVar = "gtoDir", usage = "genome input directory")
    private File gtoDir;

    @Override
    protected void setDefaults() {
        this.filterType = FunctionFilter.Type.ROLE;
        this.minPercent = 80.0;
    }

    @Override
    protected boolean validateParms() throws IOException {
        if (! this.coreDir.isDirectory())
            throw new FileNotFoundException("CoreSEED directory " + this.coreDir + " not found or invalid.");
        this.orgBase = new File(this.coreDir, "Organisms");
        if (! this.orgBase.isDirectory())
            throw new FileNotFoundException("No organism directory found for " + this.coreDir + ".");
        if (this.minPercent > 100.0 || this.minPercent < 0.0)
            throw new IllegalArgumentException("Minimum percent must be between 0 and 100 inclusive.");
        // Create the function map and filter.
        this.funMap = new FunctionMap();
        this.funFilter = this.filterType.create(this.funMap, this);
        // Initialize the protein-counting maps.
        this.protFunctionMap = new HashMap<String, CountMap<String>>(2500000);
        this.funFunctionMap = new HashMap<String, CountMap<String>>(100000);
        if (log.isInfoEnabled())
            this.protsFound = new HashSet<String>(2500000);
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Set up the counters.
        int proteinsIn = 0;
        int badFunctions = 0;
        // Loop through the coreSEED proteins.
        ProteinFinder.Container proteins = ProteinFinder.Type.CORE.create(this);
        for (ProteinFinder.Instance protein : proteins) {
            proteinsIn++;
            String function = protein.getFunction();
            String funId = this.funFilter.checkFunction(function);
            if (funId == null)
                badFunctions++;
            else {
                // Now we can count this function for this protein.
                String md5 = protein.getMd5();
                CountMap<String> funCounts = this.protFunctionMap.computeIfAbsent(md5, p -> new CountMap<String>());
                funCounts.count(funId);
            }
        }
        log.info("{} pegs read, {} rejected, {} non-redundant sequences.",
                proteinsIn, badFunctions, this.protFunctionMap.size());
        // Now we have processed all the CoreSEED genomes.  Extract features from the PATRIC genomes to compute the
        // function-to-function correspondence.
        proteins = ProteinFinder.Type.GTO.create(this);
        int processed = 0;
        int counted = 0;
        log.info("Scanning proteins.");
        for (ProteinFinder.Instance protein : proteins) {
            if (this.processFeature(protein)) counted++;
            processed++;
        }
        // Now we produce the report.
        log.info("Preparing report.  {} PATRIC functions found. {} pegs used out of {} scanned.", this.funFunctionMap.size(),
                counted, processed);
        if (log.isInfoEnabled()) {
            int missing = this.protFunctionMap.size() - this.protsFound.size();
            log.info("{} proteins in CoreSEED were not found in PATRIC genomes.", missing);
        }
        System.out.println("patric_function\tcore_function\tcount\tgood");
        int simpleMappings = 0;
        int goodMappings = 0;
        // Get a sorted list of all the functions.
        List<Function> allFuns = this.funFunctionMap.keySet().stream().map(f -> this.funMap.getItem(f)).sorted(new Function.ByName()).collect(Collectors.toList());
        // Loop through the functions, producing output.
        log.info("Writing report.");
        for (Function fun : allFuns) {
            CountMap<String> funCounts = this.funFunctionMap.get(fun.getId());
            List<CountMap<String>.Count> countList = funCounts.sortedCounts();
            String good = "";
            if (funCounts.size() == 1) {
                goodMappings++;
                simpleMappings++;
                good = "Y";
            } else {
                double dominance = (countList.get(0).getCount() * 100.0) / funCounts.getTotal();
                if (dominance >= this.minPercent) {
                    goodMappings++;
                    good = "Y";
                }
            }
            // Note that only the FIRST pairing gets the "good" flag, since this is always the dominant function.
            for (CountMap<String>.Count count : countList) {
                System.out.format("%s\t%s\t%d\t%s%n", fun.getName(), this.funMap.getName(count.getKey()), count.getCount(), good);
                good = "";
            }
        }
        log.info("{} PATRIC functions found.  {} simple mappings, {} good mappings.", allFuns.size(), simpleMappings, goodMappings);
    }

    /**
     * Process a feature from PATRIC to update the function map counts.
     *
     * @param record	protein instance to process
     *
     * @return TRUE if we cared about this protein
     */
    protected boolean processFeature(ProteinFinder.Instance record) {
        boolean retVal = false;
        String md5 = record.getMd5();
        CountMap<String> protCounts = this.protFunctionMap.get(md5);
        if (protCounts != null) {
            if (log.isInfoEnabled()) this.protsFound.add(md5);
            // Here the sequence corresponds to one we're interested in.  Get the count map for this function.
            // If the product string is null or empty, we will get back a null or empty result, in which case
            // we skip this record.
            Function fun = this.funMap.findOrInsert(record.getFunction());
            if (fun != null) {
                // Get the counts for this PATRIC function.
                String funId = fun.getId();
                CountMap<String> funCounts = this.funFunctionMap.computeIfAbsent(funId, x -> new CountMap<String>());
                // Add in the coreSEED function counts.
                for (CountMap<String>.Count protCount : protCounts.counts())
                    funCounts.count(protCount.getKey(), protCount.getCount());
                retVal = true;
            }
        }
        return retVal;
    }

    @Override
    public File getGtoDir() {
        return this.gtoDir;
    }

    @Override
    public File getOrgDir() {
        return this.orgBase;
    }

}
