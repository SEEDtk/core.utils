/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.BaseProcessor;
import org.theseed.basic.ParseFailureException;
import org.theseed.counters.CountMap;
import org.theseed.genome.Feature;
import org.theseed.genome.core.CoreUtilities;
import org.theseed.subsystems.StrictRoleMap;
import org.theseed.subsystems.core.CoreSubsystem;

/**
 * This command will produce a report on the accuracy of the subsystem rules.  First, all the coreSEED genomes are
 * read into memory.  Then, we go through the subsystems one at a time, computing the number of genomes for which
 * the spreadsheet variant matches the variant predicted by the rules.
 *
 * The positional parameters are the name of the CoreSEED data directory and the name of an output directory.  The main report will be
 * produced to "subReport.tbl" in the output directory.  There will also be the following reports.
 *
 * badVariants.tbl		list of spreadsheet rows where the wrong rule matched and the error is serious
 * bvSummary.tbl		summary of the mismatches in badVariants.tbl
 * missing.tbl			list of subsystems with missing rules
 * errors.tbl			list of subsystems with invalid rules
 * ivSummary.tbl		summary of the expected variant codes for which no rule exists
 * badIds.tbl			list of bad rule identifiers for each subsystem
 * mismatch.tbl			list of features that cause bad variants because the role definition does not match the subsystem's
 * oldCodes.tbl			list of subsystems with old-style variant codes
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 *
 * --roles		role definition file to use (default is "subsystem.roles" in the CoreSEED data directory)
 * --clear		erase the output directory before starting
 * --filter		if specified, a file of subsystem names; only the named subsystems will be checked
 *
 * In the bad-variant reports, the expected variant is the one in the spreadsheet.  The actual variant is the one predicted
 * by the rules.  A mismatch is considered serious if neither of the variant codes is negative, 0, or "dirty", there are
 * no bad identifiers, and there are no role name mismatches.  For a serious mismatch, an analysis of the roles that
 * participated in the decision will be displayed in comma-delimited form with a slash.  The roles before the slash were
 * found, and the roles after were not found.
 *
 * The main output report has the following columns.
 *
 * Subsystem	name of the subsystem
 * roles		number of roles
 * genomes		number of genome roles
 * bad_ids		number of identifiers in the rules that do not correspond to an abbreviation or definition
 * bad_roles	number of subsystem roles that are not found in CoreSEED
 * bad_variants	number of variants picked by the rules that do not match the spreadsheet; a blank here means no rules exist
 * serious		number of bad-variant cases considered serious
 * invalid		number of invalid-variant cases (genomes with variant codes for which there is no rule)
 * mismatch		number of mismatches due to changed role names
 * bad_genomes	number of genome rows not found in CoreSEED
 *
 * @author Bruce Parrello
 *
 */
public class SubsystemRuleCheckProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(SubsystemRuleCheckProcessor.class);
    /** map of genome IDs to function maps */
    private Map<String, Map<String, String>> coreGenomes;
    /** master role map */
    private StrictRoleMap roleMap;
    /** list of subsystem directories */
    private List<File> subDirs;
    /** mape of genome IDs to names */
    private Map<String, String> nameMap;
    /** core utilities object */
    private CoreUtilities coreSeed;
    /** output file for main report */
    private PrintWriter mainWriter;
    /** output file for missing-rules list */
    private PrintWriter missingRulesWriter;
    /** output file for variant-mismatch list */
    private PrintWriter badVariantDetailWriter;
    /** output file for variant-mismatch summary */
    private PrintWriter badVariantSummaryWriter;
    /** output file for invalid-variant-code summary */
    private PrintWriter invalidVariantSummaryWriter;
    /** mismatch role definition list */
    private PrintWriter mismatchRoleWriter;
    /** output file for compile-error summary */
    private PrintWriter errorWriter;
    /** output file for bad-id list */
    private PrintWriter badIdWriter;
    /** output file for old-codes list */
    private PrintWriter oldCodeWriter;
    /** list of active print writers */
    private List<PrintWriter> writerList;
    /** set of mismatched-role features already found */
    private Set<String> mismatchSet;
    /** number of subsystems processed */
    private int subCount;
    /** total number of subsystems to process */
    private int subTotal;
    /** start time of first subsystem */
    private long subStart;
    /** hash map size to use for genome maps */
    private static final int MAP_SIZE = 2000;
    /** pattern for variant codes that generally indicate an inactive subsystem */
    private static final Pattern INACTIVE_CODE = Pattern.compile("-.*|0.*|dirty.*");
    /** pattern for variant codes that are old-style */
    private static final Pattern NEW_CODE = Pattern.compile("0|-1|dirty.*|likely.*|lookat.*|active.*|inactive.*");

    // COMMAND-LINE OPTIONS

    /** role definition file */
    @Option(name = "--roles", metaVar = "subsystem.roles", usage = "role definition file")
    private File roleFile;

    /** if specified, the output directory will be erased before processing */
    @Option(name = "--clear", usage = "erase the output directory before processing")
    private boolean clearFlag;

    /** if specified, the name of a file containing subsystem names in the first column; only the named subsystems will be checked */
    @Option(name = "--filter", metaVar = "ssNames.tbl", usage = "file of subsystem names to check (tab-separated with headers)")
    private File filterFile;

    /** input coreSEED data directory */
    @Argument(index = 0, metaVar = "FIGdisk/FIG/Data", usage = "input CoreSEED data directory", required = true)
    private File coreDir;

    /** output directory */
    @Argument(index = 1, metaVar = "outDir", usage = "output directory for reports", required = true)
    private File outDir;

    @Override
    protected void setDefaults() {
        this.roleFile = null;
        this.clearFlag = false;
        this.filterFile = null;
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        // Read in the role map.
        if (this.roleFile == null)
            this.roleFile = new File(this.coreDir, "subsystem.roles");
        if (! this.roleFile.canRead())
            throw new FileNotFoundException("Role definition file " + this.roleFile + " is not found or unreadable.");
        this.roleMap = StrictRoleMap.load(roleFile);
        log.info("{} roles found in definition file {}.", this.roleMap.size(), this.roleFile);
        // Verify that we have a genome directory.
        File orgDir = new File(this.coreDir, "Organisms");
        if (! orgDir.isDirectory())
            throw new FileNotFoundException("No organism directory found in " + this.coreDir + ".");
        log.info("Connecting to organism directory {}.", orgDir);
        this.coreSeed = new CoreUtilities(orgDir);
        this.nameMap = new HashMap<String, String>(MAP_SIZE);
        this.coreGenomes = new TreeMap<String, Map<String, String>>();
        this.mismatchSet = new HashSet<String>(MAP_SIZE);
        // Get the subsystem directory list.  This will also validate the coreSEED input directory.
        this.subDirs = CoreSubsystem.getFilteredSubsystemDirectories(this.coreDir, this.filterFile);
        this.subTotal = this.subDirs.size();
        // Set up the output directory.
        if (! this.outDir.isDirectory()) {
            log.info("Creating output directory {}.", this.outDir);
            FileUtils.forceMkdir(this.outDir);
        } else if (this.clearFlag) {
            log.info("Erasing output directory {}.", this.outDir);
            FileUtils.cleanDirectory(this.outDir);
        } else
            log.info("Output reports will be produced in {}.", this.outDir);
        // Insure the writers are all marked unopened.
        this.writerList = new ArrayList<PrintWriter>();
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        try {
            // Set up the output files.
            this.mainWriter = this.openWriter("subReport.tbl");
            this.badVariantDetailWriter = this.openWriter("badVariants.tbl");
            this.badVariantSummaryWriter = this.openWriter("bvSummary.tbl");
            this.invalidVariantSummaryWriter = this.openWriter("ivSummary.tbl");
            this.missingRulesWriter = this.openWriter("missing.tbl");
            this.errorWriter = this.openWriter("errors.tbl");
            this.badIdWriter = this.openWriter("badIds.tbl");
            this.mismatchRoleWriter = this.openWriter("mismatch.tbl");
            this.oldCodeWriter = this.openWriter("oldCodes.tbl");
            // Write the header lines.
            this.mainWriter.println("Subsystem\troles\tgenomes\tbad_ids\tbad_roles\tbad_variants\tserious\tinvalid\tmismatch\tbad_genomes");
            this.badVariantDetailWriter.println("Subsystem\tgood\tgenome_id\texpected\tactual\texpected_roles\tactual_roles");
            this.badVariantSummaryWriter.println("Subsystem\tgood\tbad_ids\texpected\tactual\tcount");
            this.invalidVariantSummaryWriter.println("Subsystem\tgood\tbad_ids\tinvalid\tactual\tcount");
            this.missingRulesWriter.println("Subsystem\tversion\tsuperclass\tclass\tsubclass\tgood");
            this.errorWriter.println("Subsystem\terror_message");
            this.badIdWriter.println("Subsystem\troles\tgood\tbad_ids");
            this.mismatchRoleWriter.println("feature_id\tactual_role\tsubsystem_role");
            this.oldCodeWriter.println("Subsystem\troles\tactive_genomes\tgood\ttotal_codes\told_codes");
            // We start by creating a functional-assignment map for each genome.
            for (String genomeId : this.coreSeed.getGenomes()) {
                String genomeName = this.coreSeed.getGenomeName(genomeId);
                log.info("Processing role set for {}: {}.", genomeId, genomeName);
                this.nameMap.put(genomeId, genomeName);
                var functionMap = this.coreSeed.getGenomeFunctions(genomeId, CoreSubsystem.FID_TYPES);
                this.coreGenomes.put(genomeId, functionMap);
                log.info("{} functions found in {}.", functionMap.size(), genomeId);
            }
            // Now we process the subsystems.
            log.info("Processing subsystems.");
            this.subCount = 0;
            this.subStart = System.currentTimeMillis();
            this.subDirs.parallelStream().forEach(x -> this.analyzeSubsystem(x));
        } finally {
            // Close all the print writers.
            this.writerList.stream().forEach(x -> x.close());
        }
    }

    /**
     * Perform an analysis of the subsystem in the specified directory.
     *
     * @param subDir	directory containing the subsystem
     */
    private void analyzeSubsystem(File subDir) {
        CoreSubsystem subsystem = null;
        try {
            subsystem = new CoreSubsystem(subDir, roleMap);
            this.processSubsystem(subsystem);
            // Flush all the output streams.
            for (PrintWriter writer : this.writerList) {
                synchronized (writer) {
                    writer.flush();
                }
            }
        } catch (ParseFailureException e) {
            // A parsing error is an expected problem with the rules.
            String message = e.getMessage();
            log.error("Error in {}: {}", subDir, e.getMessage());
            String name = CoreSubsystem.dirToName(subDir);
            this.errorWriter.println(name + "\t" + message);
        } catch (IOException e) {
            // Uncheck IO exceptions so we can stream this method.
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @return a writer for the specified output file
     *
     * @param fileName	output file base name
     *
     * @throws FileNotFoundException
     */
    private PrintWriter openWriter(String fileName) throws FileNotFoundException {
        PrintWriter retVal = new PrintWriter(new File(this.outDir, fileName));
        this.writerList.add(retVal);
        return retVal;
    }

    /**
     * Validate the rules for the specified subsystem.
     *
     * @param subsystem		subsystem to check
     *
     * @throws ParseFailureException
     */
    private void processSubsystem(CoreSubsystem subsystem) throws ParseFailureException {
        // These hashes count the variant-mismatch pairs.  Each pair is keyed on expected + \t + actual.
        // The iCountMap counts only those where the expected variant does not have a rule.
        CountMap<String> countMap = new CountMap<String>();
        CountMap<String> iCountMap = new CountMap<String>();
        // Get some important subsystem fields.
        final String subName = subsystem.getName();
        final String goodFlag = subsystem.isGood() ? "Y" : "";
        final int badIdCount = subsystem.getBadIdCount();
        // Loop through the rows, verifying the genome IDs and variant codes.
        synchronized (this) {
            this.subCount++;
        }
        log.info("Validating subsystem {} of {}: {}.", this.subCount, this.subTotal, subName);
        int badGenomes = 0;
        int activeGenomes = 0;
        Set<String> newCodes = new TreeSet<String>();
        Set<String> oldCodes = new TreeSet<String>();
        for (String rowGenomeId : subsystem.getRowGenomes()) {
            // Check for a bad genome ID.
            if (! this.coreGenomes.containsKey(rowGenomeId))
                badGenomes++;
            // Check for an old variant code.
            String code = subsystem.variantOf(rowGenomeId);
            if (! INACTIVE_CODE.matcher(code).matches())
                activeGenomes++;
            if (NEW_CODE.matcher(code).matches())
                newCodes.add(code);
            else
                oldCodes.add(code);
        }
        // Loop through the genomes, checking them against the subsystem.  If there are no rules, we
        // just note the lack of rules.
        String variantIndicator = "";
        String seriousIndicator = "";
        String invalidIndicator = "";
        String mismatchIndicator = "";
        if (! subsystem.hasRules()) synchronized (this.missingRulesWriter) {
            // Here there are no rules, so none of the variants will match.  List the subsystem.
            this.missingRulesWriter.println(subName + "\t" + subsystem.getVersion() + "\t"
                    + subsystem.getSuperClass() + "\t" + subsystem.getMiddleClass() + "\t" + subsystem.getSubClass()
                    + "\t" + goodFlag);
        } else {
            int badVariants = 0;
            int serious = 0;
            int invalid = 0;
            int mismatches = 0;
            int gCount = 0;
            long lastMessage = System.currentTimeMillis();
            for (var genomeEntry : this.coreGenomes.entrySet()) {
                String genomeId = genomeEntry.getKey();
                Map<String, String> functionMap = genomeEntry.getValue();
                // Do we have this genome?
                String expected = subsystem.variantOf(genomeId);
                if (expected != null) {
                    Set<String> roleSet = new HashSet<String>(functionMap.size());
                    Set<String> strictRoleSet = new HashSet<String>(functionMap.size());
                    this.computeRoleSets(subsystem, roleSet, strictRoleSet, functionMap);
                    String actual = subsystem.applyRules(roleSet);
                    if (actual == null) actual = "-1";
                    if (! StringUtils.equals(actual, expected)) {
                        String key = expected + "\t" + actual;
                        // Here we have a bad-variant mismatch.  We need to count it first.
                        // Before we do that, we need to insure there is a rule for the expected variant.
                        if (! subsystem.isRuleVariant(expected)) {
                            // Here no rule exists for the expected variant.
                            iCountMap.count(key);
                            invalid++;
                        } else {
                            // Here we have the wrong rule matching.
                            badVariants++;
                            countMap.count(key);
                            // Check for role-mismatch situation.
                            String strict = subsystem.applyRules(strictRoleSet);
                            if (strict == null) strict = "-1";
                            if (StringUtils.equals(strict, expected)) {
                                mismatches++;
                            } else if (badIdCount == 0 && ! (INACTIVE_CODE.matcher(expected).matches() &&
                                    INACTIVE_CODE.matcher(actual).matches())) {
                                // Here it's serious, so we need to write it to the detail file. Analyze the expected
                                // and actual variant rules.
                                String expectedAnalysis = subsystem.analyzeRule(expected, roleSet);
                                String actualAnalysis = subsystem.analyzeRule(actual, roleSet);
                                // Write the detail line.
                                synchronized (this.badVariantDetailWriter) {
                                    this.badVariantDetailWriter.println(subName + "\t" + goodFlag + "\t" + genomeId + "\t" + key
                                            + "\t" + expectedAnalysis + "\t" + actualAnalysis);
                                }
                                serious++;
                            }
                        }
                    }
                }
                gCount++;
                if (log.isInfoEnabled()) {
                    long now = System.currentTimeMillis();
                    if (now - lastMessage >= 5000) {
                        lastMessage = now;
                        log.info("{} genomes processed.  {} bad variants, {} serious, {} mismatches.",
                                gCount, badVariants, serious, mismatches);
                    }
                }
            }
            variantIndicator = Integer.toString(badVariants);
            seriousIndicator = Integer.toString(serious);
            invalidIndicator = Integer.toString(invalid);
            mismatchIndicator = Integer.toString(mismatches);
            log.info("{} genomes processed.  {} bad variants, {} serious, {} mismatches.",
                    gCount, badVariants, serious, mismatches);
        }
        // Write the main-report summary line.
        synchronized (this.mainWriter) {
            this.mainWriter.println(subName + "\t" + subsystem.getRoleCount() + "\t" +
                    subsystem.getRowGenomes().size() + "\t" + badIdCount + "\t" +
                    subsystem.getBadRoleCount() + "\t" + variantIndicator + "\t" +
                    seriousIndicator + "\t" + invalidIndicator + "\t" + mismatchIndicator + "\t" + badGenomes);
        }
        // If there are old codes, write a summary to the old-code report.
        if (! oldCodes.isEmpty()) synchronized (this.oldCodeWriter) {
            int totCodeCount = oldCodes.size() + newCodes.size();
            String oldCodeString = StringUtils.join(oldCodes, ", ");
            this.oldCodeWriter.println(subName + "\t" + subsystem.getRoleCount() + "\t" + activeGenomes + "\t" + goodFlag
                    + "\t" + totCodeCount + "\t" + oldCodeString);
        }
        // Run through the mismatch counts.
        this.writeCountSummary(countMap, subsystem, this.badVariantSummaryWriter);
        this.writeCountSummary(iCountMap, subsystem, this.invalidVariantSummaryWriter);
        // Check for bad IDs.
        if (badIdCount > 0) {
            String badIdString = StringUtils.join(subsystem.getBadIds(), ", ");
            this.badIdWriter.println(subName + "\t" + subsystem.getRoleCount() + "\t" + goodFlag + "\t" + badIdString);
        }
        if (log.isInfoEnabled()) synchronized (this) {
            int subsLeft = this.subTotal - this.subCount;
            Duration effort = Duration.ofMillis(System.currentTimeMillis() - this.subStart).dividedBy(subCount);
            Duration rem = effort.multipliedBy(subsLeft);
            log.info("{} subsystems completed.  {} per subsystem, {} time remaining.", this.subCount, effort, rem);
        }
    }

    /**
     * Compute the standard and strict role sets for this genome with respect to the specified subsystem.
     * The standard role set uses the role map.  The strict role set does not allow synonuyms.
     *
     * @param subsystem			subsystem in question
     * @param roleSet			output standard role set
     * @param strictRoleSet		output strict role set
     * @param functionMap		map of genome IDs to functional assignments
     */
    private void computeRoleSets(CoreSubsystem subsystem, Set<String> roleSet, Set<String> strictRoleSet,
            Map<String, String> functionMap) {
        // Loop through the function map, extracting individual roles.  For each role, we decide its
        // ID and add to the role set.  If the subsystem's role name does not match, we leave it out of
        // the strict set.
        for (var functionEntry : functionMap.entrySet()) {
            String peg = functionEntry.getKey();
            String functionString = functionEntry.getValue();
            String[] roleStrings = Feature.rolesOfFunction(functionString);
            for (String roleString : roleStrings) {
                // Get the role ID.
                String roleId = subsystem.getRoleId(roleString);
                if (roleId != null) {
                    // Here we have a role in the subsystem.
                    roleSet.add(roleId);
                    // Check for strict matching.
                    if (subsystem.isExactRole(roleId, roleString))
                        strictRoleSet.add(roleId);
                    else synchronized (this.mismatchRoleWriter) {
                        if (! this.mismatchSet.contains(peg)) {
                            // Here we have a new mismatch for the mismatch report.
                            this.mismatchSet.add(peg);
                            this.mismatchRoleWriter.println(peg + "\t" + roleString + "\t" +
                            subsystem.getExpectedRole(roleId));
                        }
                    }
                }
            }
        }


    }

    /**
     * Write a summary of mismatch counts.
     *
     * @param countMap		mismatch counts to write
     * @param subsystem		relevant subsystem
     * @param writer		output writer for report
     */
    private void writeCountSummary(CountMap<String> countMap, CoreSubsystem subsystem, PrintWriter writer) {
        String subName = subsystem.getName();
        String goodFlag = (subsystem.isGood() ? "Y" : "");
        int badIdCount = subsystem.getBadIdCount();
        synchronized (writer) {
            for (var countEntry : countMap.sortedCounts()) {
                String key = countEntry.getKey();
                int count = countEntry.getCount();
                writer.println(subName + "\t" + goodFlag + "\t" + badIdCount + "\t" + key + "\t" + count);
            }
        }
    }

}
