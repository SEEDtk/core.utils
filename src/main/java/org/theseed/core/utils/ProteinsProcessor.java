/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.counters.CountMap;
import org.theseed.genome.core.OrganismDirectories;
import org.theseed.io.MarkerFile;
import org.theseed.p3api.Connection;
import org.theseed.p3api.Connection.Table;
import org.theseed.proteins.Function;
import org.theseed.proteins.FunctionFilter;
import org.theseed.proteins.FunctionMap;
import org.theseed.sequence.FastaInputStream;
import org.theseed.sequence.MD5Hex;
import org.theseed.sequence.Sequence;
import org.theseed.subsystems.RowData;
import org.theseed.utils.BaseProcessor;

import com.github.cliftonlabs.json_simple.JsonObject;

// TODO convert to protein finders

/**
 * This object creates a table of all the protein sequences in CoreSEED and uses it to build a correspondence
 * between PATRIC functions and CoreSEED functions.
 *
 * The positional parameter is the name of the CoreSEED data directory.  The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent progress messages
 * -b	batch size for PATRIC protein requests
 *
 * --min		minimum percent for a good mapping
 * --filter		type of function filtering (default is ROLE)
 *
 * @author Bruce Parrello
 *
 */
public class ProteinsProcessor extends BaseProcessor implements FunctionFilter.Parms {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ProteinsProcessor.class);
    /** function map */
    private FunctionMap funMap;
    /** map of protein IDs to function counts */
    private Map<String, CountMap<String>> protFunctionMap;
    /** map of PATRIC functions to CoreSEED functions */
    private Map<String, CountMap<String>> funFunctionMap;
    /** active function filter */
    private FunctionFilter funFilter;
    /** organism directory structure */
    private OrganismDirectories orgDirs;
    /** MD5 computer for proteins */
    private MD5Hex md5Computer;
    /** organism base directory */
    private File orgBase;
    /** set used to filter only for pegs */
    private Set<String> PEG_SET = Collections.singleton("peg");
    /** pattern for prokaryotic taxonomies */
    private Pattern PROKARYOTIC = Pattern.compile("\\s*(?:Bacteria|Archaea);.+");
    /** connection to PATRIC */
    private Connection p3;

    // COMMAND-LINE OPTIONS

    /** type of function filtering */
    @Option(name = "--filter", usage = "function-filtering scheme")
    private FunctionFilter.Type filterType;

    /** minimum percent for a good mapping */
    @Option(name = "--min", metaVar = "95.0", usage = "minimum percent cases to qualify for a good mapping")
    private double minPercent;

    /** batch size for requests to PATRIC */
    @Option(name = "-b", aliases = { "--batchSize" }, usage = "batch size for PATRIC requests")
    private int batchSize;

    /** CoreSEED directory */
    @Argument(index = 0, metaVar = "CoreSEED", usage = "CoreSEED data directory")
    private File coreDir;

    @Override
    protected void setDefaults() {
        this.filterType = FunctionFilter.Type.ROLE;
        this.batchSize = 200;
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
        // Get the organism directories.
        this.orgDirs = new OrganismDirectories(orgBase);
        // Create the function map and filter.
        this.funMap = new FunctionMap();
        this.funFilter = this.filterType.create(this.funMap, this);
        // Initialize the MD5 computer and the protein-counting map.
        try {
            this.md5Computer = new MD5Hex();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        this.protFunctionMap = new HashMap<String, CountMap<String>>(2500000);
        this.funFunctionMap = new HashMap<String, CountMap<String>>(100000);
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Set up the counters.
        int proteinsIn = 0;
        int genomesIn = 0;
        int badFunctions = 0;
        // Loop through the genomes.
        for (String genome : this.orgDirs) {
            File orgDir = new File(this.orgBase, genome);
            log.info("Processing genome {}.", genome);
            // Locate the peg fasta file.
            File pegFile = new File(orgDir, "Features/peg/fasta");
            if (! pegFile.exists())
                log.warn("Genome {} has no protein FASTA.", genome);
            else {
                // Verify that this is a prokaryote.
                String taxString = MarkerFile.read(new File(orgDir, "TAXONOMY"));
                Matcher m = PROKARYOTIC.matcher(taxString);
                if (m.matches()) {
                    genomesIn++;
                    // Get all the genome's functions.
                    Map<String, String> functions = RowData.readFunctions(orgDir, genome, PEG_SET);
                    log.info("{} peg assignments found.", functions.size());
                    // Now loop through the sequences.
                    try (FastaInputStream pegStream = new FastaInputStream(pegFile)) {
                        for (Sequence protein : pegStream) {
                            proteinsIn++;
                            String function = functions.get(protein.getLabel());
                            String funId = this.funFilter.checkFunction(function);
                            if (funId == null)
                                badFunctions++;
                            else {
                                // Now we can count this function for this protein.
                                String md5 = this.md5Computer.sequenceMD5(protein.getSequence());
                                CountMap<String> funCounts = this.protFunctionMap.computeIfAbsent(md5, p -> new CountMap<String>());
                                funCounts.count(funId);
                            }
                        }
                    }
                    log.info("{} genomes processed. {} pegs read, {} rejected, {} non-redundant sequences.",
                            genomesIn, proteinsIn, badFunctions, this.protFunctionMap.size());
                }
            }
        }
        // Now we have processed all the CoreSEED genomes.  Extract features from PATRIC to compute the
        // function-to-function correspondence.
        log.info("Connecting to PATRIC.");
        this.p3 = new Connection();
        // The following array is used to buffer sequences for each batch request to PATRIC.
        List<String> md5Buffer = new ArrayList<String>(this.batchSize);
        // Loop through the sequence MD5s, making PATRIC requests.
        int totalMD5s = this.protFunctionMap.size();
        int processed = 0;
        log.info("Scanning proteins.");
        long start = System.currentTimeMillis();
        for (String md5 : this.protFunctionMap.keySet()) {
            if (md5Buffer.size() >= this.batchSize) {
                this.processBatch(md5Buffer);
                processed += md5Buffer.size();
                md5Buffer.clear();
                if (log.isInfoEnabled()) {
                    double speed = (System.currentTimeMillis() - start) / (1000.0 * processed);
                    log.info("{} of {} proteins processed, {} seconds/protein.", processed, totalMD5s, speed);
                }
            }
            md5Buffer.add(md5);
        }
        this.processBatch(md5Buffer);
        // Now we produce the report.
        log.info("Preparing report.  {} PATRIC functions found in {} proteins.", this.funFunctionMap.size(), processed);
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
            for (CountMap<String>.Count count : countList)
                System.out.format("%s\t%s\t%d\t%s%n", fun.getName(), this.funMap.getName(count.getKey()), count.getCount(), good);
        }
        log.info("{} PATRIC functions found.  {} simple mappings, {} good mappings.", allFuns.size(), simpleMappings, goodMappings);
    }

    /**
     * Process a batch of protein sequences.
     *
     * @param md5list	list of protein MD5s for the proteins to process
     */
    private void processBatch(List<String> md5list) {
        List<JsonObject> records = p3.getRecords(Table.FEATURE, "aa_sequence_md5", md5list, "aa_sequence_md5,product");
        log.info("{} features found for {} proteins.", records.size(), md5list.size());
        processFeatures(records);
    }

    /**
     * Process features from PATRIC to update the function map counts.
     *
     * @param records	list of feature records, containing the protein MD5 and functional assignment
     */
    protected void processFeatures(List<JsonObject> records) {
        for (JsonObject record : records) {
            String md5 = Connection.getString(record, "aa_sequence_md5");
            CountMap<String> protCounts = this.protFunctionMap.get(md5);
            if (protCounts != null) {
                // Here the sequence corresponds to one we're interested in.  Get the count map for this function.
                // If the product string is null or empty, we will get back a null or empty result, in which case
                // we skip this record.
                Function fun = this.funMap.findOrInsert(Connection.getString(record, "product"));
                if (fun != null) {
                    // Get the counts for this PATRIC function.
                    String funId = fun.getId();
                    CountMap<String> funCounts = this.funFunctionMap.computeIfAbsent(funId, x -> new CountMap<String>());
                    // Add in the coreSEED function counts.
                    for (CountMap<String>.Count protCount : protCounts.counts())
                        funCounts.count(protCount.getKey(), protCount.getCount());
                }
            }
        }
    }

}
