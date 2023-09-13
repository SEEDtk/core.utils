/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.genome.Feature;
import org.theseed.genome.core.CoreUtilities;
import org.theseed.proteins.RoleMap;
import org.theseed.subsystems.core.CoreSubsystem;
import org.theseed.utils.BaseReportProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This command will produce a report on the accuracy of the subsystem rules.  First, all the coreSEED genomes are
 * read into memory.  Then, we go through the subsystems one at a time, computing the number of genomes for which
 * the spreadsheet variant matches the variant predicted by the rules.
 *
 * The positional parameter is the name of the CoreSEED data directory.  The report will be to the standard output.
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -o	output file for report (if not STDOUT)
 *
 * --roles	role definition file to use (default is "roles.in.subsystems" in the CoreSEED data directory)
 *
 * The output report has the following columns.
 *
 * Subsystem	name of the subsystem
 * roles		number of roles
 * genomes		number of genome roles
 * bad_rules	number of identifiers in the rules that do not correspond to an abbreviation or definition
 * bad_roles	number of subsystem roles that are not found in CoreSEED
 * bad_variants	number of variants picked by the rules that do not match the spreadsheet; a blank here means no rules exist
 * bad_genomes	number of genome rows not found in CoreSEED
 *
 * @author Bruce Parrello
 *
 */
public class SubsystemRuleCheckProcessor extends BaseReportProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(SubsystemRuleCheckProcessor.class);
    /** map of genome IDs to role sets */
    private Map<String, Set<String>> coreGenomes;
    /** master role map */
    private RoleMap roleMap;
    /** list of subsystem directories */
    private List<File> subDirs;
    /** mape of genome IDs to names */
    private Map<String, String> nameMap;
    /** core utilities object */
    private CoreUtilities coreSeed;
    /** list of feature types used in subsystems */
    private static final String[] FID_TYPES = new String[] { "opr", "aSDomain", "pbs", "rna", "rsw", "sRNA", "peg" };
    /** hash map size to use for genome maps */
    private static final int MAP_SIZE = 2000;

    // COMMAND-LINE OPTIONS

    /** role definition file */
    @Option(name = "--roles", metaVar = "roles.in.subsystems", usage = "role definition file")
    private File roleFile;

    /** input coreSEED data directory */
    @Argument(index = 0, metaVar = "FIGdisk/FIG/Data", usage = "input CoreSEED data directory")
    private File coreDir;

    @Override
    protected void setReporterDefaults() {
        this.roleFile = null;
    }

    @Override
    protected void validateReporterParms() throws IOException, ParseFailureException {
        // Read in the role map.
        if (this.roleFile == null)
            this.roleFile = new File(this.coreDir, "roles.in.subsystems");
        if (! this.roleFile.canRead())
            throw new FileNotFoundException("Role definition file " + this.roleFile + " is not found or unreadable.");
        this.roleMap = RoleMap.load(roleFile);
        log.info("{} roles found in definition file.", this.roleFile);
        // Get the subsystem directory list.  This will also validate the coreSEED input directory.
        log.info("Scanning for subsystems in {}.", coreDir);
        this.subDirs = CoreSubsystem.getSubsystemDirectories(this.coreDir);
        // Verify that we have a genome directory.
        File orgDir = new File(this.coreDir, "Organisms");
        if (! orgDir.isDirectory())
            throw new FileNotFoundException("No organism directory found in " + this.coreDir + ".");
        log.info("Connecting to organism directory {}.", orgDir);
        this.coreSeed = new CoreUtilities(orgDir);
        this.nameMap = new HashMap<String, String>(MAP_SIZE);
        this.coreGenomes = new TreeMap<String, Set<String>>();
    }

    @Override
    protected void runReporter(PrintWriter writer) throws Exception {
        // Write the header line.
        writer.println("Subsystem\troles\tgenomes\tbad_rules\tbad_roles\tbad_variants\tbad_genomes");
        // We start by creating a role set for each genome.
        for (String genomeId : this.coreSeed.getGenomes()) {
            String genomeName = this.coreSeed.getGenomeName(genomeId);
            log.info("Processing role set for {}: {}.", genomeId, genomeName);
            this.nameMap.put(genomeId, genomeName);
            var functionMap = this.coreSeed.getGenomeFunctions(genomeId, FID_TYPES);
            // Now loop through the function map building the role set.
            Set<String> roleSet = new HashSet<String>(MAP_SIZE);
            for (String function : functionMap.values())
                Feature.usefulRoles(roleMap, function).stream().forEach(x -> roleSet.add(x.getId()));
            this.coreGenomes.put(genomeId, roleSet);
            log.info("{} useful roles found in {}.", roleSet.size(), genomeId);
        }
        // Now we process the subsystems.
        log.info("Processing subsystems.");
        int badSystems = 0;
        int goodSystems = 0;
        for (File subDir : this.subDirs) {
            CoreSubsystem subsystem = null;
            try {
                subsystem = new CoreSubsystem(subDir, roleMap);
                if (! subsystem.isGood()) {
                    log.info("Skipping subsystem {}.", subsystem.getName());
                    badSystems++;
                } else {
                    goodSystems++;
                    this.processSubsystem(subsystem, writer);
                }
            } catch (ParseFailureException e) {
                log.error("Error in {}: {}", subDir, e.getMessage());
                badSystems++;
            }
        }
        log.info("{} good subsystems, {} bad subsystems.", goodSystems, badSystems);
    }

    /**
     * Validate the rules for the specified subsystem.
     *
     * @param subsystem		subsystem to check
     * @param writer		output writer for report
     */
    private void processSubsystem(CoreSubsystem subsystem, PrintWriter writer) {
        // Loop through the rows, verifying the genome IDs.
        log.info("Validating subsystem {}.", subsystem.getName());
        int badGenomes = 0;
        for (String rowGenomeId : subsystem.getRowGenomes()) {
            if (! this.coreGenomes.containsKey(rowGenomeId))
                badGenomes++;
        }
        // Loop through the genomes, checking them against the subsystem.  If there are no rules, we
        // just note the lack of rules.
        String variantIndicator = "";
        if (subsystem.hasRules()) {
            int badVariants = 0;
            for (var genomeEntry : this.coreGenomes.entrySet()) {
                String genomeId = genomeEntry.getKey();
                Set<String> roleSet = genomeEntry.getValue();
                // Do we have this genome?
                String expected = subsystem.variantOf(genomeId);
                if (expected != null) {
                    String actual = subsystem.applyRules(roleSet);
                    if (! expected.equals(actual))
                        badVariants++;
                }
            }
            variantIndicator = Integer.toString(badVariants);
        }
        writer.println(subsystem.getName() + "\t" + subsystem.getRoleCount() + "\t" +
                subsystem.getRowGenomes().size() + "\t" + subsystem.getBadRuleCount() + "\t" +
                subsystem.getBadRoleCount() + "\t" + variantIndicator + "\t" + badGenomes);
    }

}
