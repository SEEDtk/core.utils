/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.counters.CountMap;
import org.theseed.genome.core.OrganismDirectories;
import org.theseed.io.MarkerFile;
import org.theseed.proteins.FunctionFilter;
import org.theseed.proteins.FunctionMap;
import org.theseed.sequence.FastaInputStream;
import org.theseed.sequence.MD5Hex;
import org.theseed.sequence.Sequence;
import org.theseed.subsystems.RowData;
import org.theseed.utils.BaseProcessor;

/**
 * This object creates a table of all the protein sequences in CoreSEED and lists the functions assigned to each.
 *
 * The positional parameter is the name of the CoreSEED data directory.  The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent progress messages
 *
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

    // COMMAND-LINE OPTIONS

    /** type of function filtering */
    @Option(name = "--filter", usage = "function-filtering scheme")
    private FunctionFilter.Type filterType;

    /** CoreSEED directory */
    @Argument(index = 0, metaVar = "CoreSEED", usage = "CoreSEED data directory")
    private File coreDir;

    @Override
    protected void setDefaults() {
        this.filterType = FunctionFilter.Type.ROLE;
    }

    @Override
    protected boolean validateParms() throws IOException {
        if (! this.coreDir.isDirectory())
            throw new FileNotFoundException("CoreSEED directory " + this.coreDir + " not found or invalid.");
        this.orgBase = new File(this.coreDir, "Organisms");
        if (! this.orgBase.isDirectory())
            throw new FileNotFoundException("No organism directory found for " + this.coreDir + ".");
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
        this.protFunctionMap = new HashMap<String, CountMap<String>>(10000);
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
        // Now we have processed all the genomes.  Write the report.
        log.info("Producing report.");
        System.out.println("md5\tfunction_id\tfunction\tcount");
        int countCount = 0;
        int multiFunction = 0;
        int multiOccurring = 0;
        for (Map.Entry<String, CountMap<String>> protEntry : this.protFunctionMap.entrySet()) {
            String md5 = protEntry.getKey();
            // Get the function counts.
            CountMap<String> funCounts = protEntry.getValue();
            if (funCounts.size() > 1) multiFunction++;
            for (CountMap<String>.Count count : funCounts.sortedCounts()) {
                String funId = count.getKey();
                System.out.format("%s\t%s\t%s\t%d%n", md5, funId, this.funMap.getName(funId), count.getCount());
                countCount++;
                if (count.getCount() > 1) multiOccurring++;
            }
        }
        log.info("{} protein/function pairs found for {} protein sequences.", countCount,
                this.protFunctionMap.size());
        log.info("{} sequences are multi-function.  {} pairs are multi-occurring.", multiFunction, multiOccurring);
    }

}
