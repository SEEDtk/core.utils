/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.BaseProcessor;
import org.theseed.counters.CountMap;
import org.theseed.genome.Feature;
import org.theseed.genome.core.OrganismDirectories;
import org.theseed.roles.RoleUtilities;
import org.theseed.subsystems.ColumnData;
import org.theseed.subsystems.RowData;
import org.theseed.subsystems.SubsystemData;
import org.theseed.subsystems.SubsystemFilter;

/**
 * Produce a master list of all the subsystem roles.  For each role, we output the role name, the subsystem name, and the subsystem curator, and
 * the number of features containing the role.  For each role not found in a subsystem, we output the role name and a feature ID that contains
 * the role.
 *
 * We also need to track roles without subsystems and the number of times each subsystem role occurs.  We do a first pass over the genomes to
 * get various maps of the roles and functions in the CoreSEED.  Compound functions in subsystems present a special problem, so these are
 * kept separate.  If a subsystem role is singular, then we simply pull the count.  If it is compound, we have to compare it to each feature
 * in the compound-function map to count it.
 *
 * The positional parameter is the name of the coreSEED data directory.
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more progress messages on the log
 *
 * --public		only process public subsystems
 * --orphans	if specified, the name of a file to contain the orphan-role report
 * --counts		if specified, the name of a file to contain a count of the number of subsystems containing each role
 * --aux		if specified, auxiliary roles are included
 * --deadRoles	if specified, the name of a file to contain a list of roles not in genomes
 *
 * @author Bruce Parrello
 *
 */
public class SubsystemListProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(SubsystemListProcessor.class);
    /** subsystem directory */
    private File subsysDir;
    /** count map of roles in the coreSEED */
    private CountMap<String> roleCounts;
    /** count map of subsystems containing each function */
    private CountMap<String> funCounts;
    /** map of roles that have not yet matched a subsystem role to sample feature IDs */
    private Map<String, String> roleFids;
    /** map of feature IDs to compound functions */
    private Map<String, String> fidFunctions;
    /** output stream for orphan-role report */
    private FileOutputStream orphanStream;
    /** output stream for subsystem-count report */
    private FileOutputStream countStream;
    /** dead-role map */
    private Map<String, List<String>> deadRoleMap;
    /** relevant feature types */
    private static Set<String> TYPES = Stream.of("peg", "rna").collect(Collectors.toSet());

    // COMMAND-LINE OPTIONS

    /** TRUE if we should only process public subsystems */
    @Option(name = "--public", usage = "Include only public subsystems")
    private boolean publicOnly;

    /** output file for orphan-role report */
    @Option(name = "--orphans", usage = "output file for orphan-role report")
    private File orphanFile;

    /** output file for dead-role report */
    @Option(name = "--deadRoles", usage = "output file for dead-role report")
    private File deadRoleFile;

    /** output file for role-count report */
    @Option(name = "--counts", usage = "output file for role-count report")
    private File countFile;

    /** if specified, auxiliary roles will be included */
    @Option(name = "--aux", usage = " include auxiliary roles")
    private boolean includeAux;

    /** CoreSEED data directory */
    @Argument(index = 0, metaVar = "CoreSEED/FIG/Data", usage = "coreSEED data directory", required = true)
    private File coreDir;

    @Override
    protected void setDefaults() {
        this.orphanFile = null;
        this.orphanStream = null;
        this.countStream = null;
        this.includeAux = false;
        this.publicOnly = false;
        this.deadRoleFile = null;
    }

    @Override
    protected boolean validateParms() throws IOException {
        // Insure the coreSEED directory exists.
        this.subsysDir = new File(this.coreDir, "Subsystems");
        if (! this.subsysDir.isDirectory())
            throw new FileNotFoundException("Specified coreSEED directory " + this.coreDir + " is missing or has no subsystems.");
        // Check for an orphan-role report.
        if (this.orphanFile != null) {
            // This insures we can open the orphan file for output.
            this.orphanStream = new FileOutputStream(this.orphanFile);
        }
        // Check for a subsystem-count report.
        if (this.countFile != null) {
            // This insures we can open the count file for output.
            this.countStream = new FileOutputStream(this.countFile);
        }
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        try {
            // Create the maps.
            this.fidFunctions = new HashMap<String, String>(5000);
            this.roleFids = new TreeMap<String, String>();
            this.roleCounts = new CountMap<String>();
            this.funCounts = new CountMap<String>();
            this.deadRoleMap = new TreeMap<String, List<String>>();
            // Initialize the maps from the genomes.
            this.processGenomes();
            // Get the list of subsystems.
            File[] subsysFiles = this.subsysDir.listFiles(new SubsystemFilter());
            log.info("{} subsystems found in {}.", subsysFiles.length, this.coreDir);
            // Write the output header.
            System.out.println("role_name\tsubsystem\tcurator\tcount\tprivate");
            // Loop through the subsystems.
            for (File subsysFile : subsysFiles) {
                SubsystemData subsystem = SubsystemData.load(this.coreDir, subsysFile.getName());
                if (subsystem == null)
                    log.warn("Subsystem {} not found.", subsysFile.getName());
                else {
                    String curator = subsystem.getCurator();
                    String flag = (subsystem.isPrivate() ? "Y" : "");
                    // Make sure the private-subsystem filter is handled.
                    String subName = subsystem.getName();
                    if (! flag.isEmpty() && this.publicOnly)
                        log.info("Skipping private subsystem {}.", subName);
                    else {
                        log.info("Processing subsystem: {}.", subName);
                        for (ColumnData col : subsystem.getColumns()) {
                            String function = col.getFunction();
                            String[] roles = Feature.rolesOfFunction(function);
                            // Remove these roles from the roles-not-found map.
                            Arrays.stream(roles).forEach(r -> this.roleFids.remove(r));
                            // Compute the number of times the role occurs.
                            int count = 0;
                            if (roles.length == 1) {
                                // Here we have a simple role.
                                count = this.roleCounts.getCount(roles[0]);
                            } else {
                                // Here we have a compound role, so we need to run through the compound-role list
                                // and count by hand.
                                for (Map.Entry<String, String> fidEntry : this.fidFunctions.entrySet()) {
                                    if (col.matches(fidEntry.getValue()))
                                        count++;
                                }
                            }
                            if (count == 0) {
                                // Here we have a dead role, so add it to the dead-role map.
                                List<String> roleSubs = this.deadRoleMap.computeIfAbsent(function, x -> new ArrayList<String>());
                                roleSubs.add(subName);
                            }
                            // Check for an auxiliary role before we print.
                            if (this.includeAux || ! col.isAux()) {
                                System.out.format("%s\t%s\t%s\t%d\t%s%n", function, subName, curator, count, flag);
                                // Count this role's occurrence in a subsystem.
                                this.funCounts.count(function);
                            }
                        }
                    }
                }
            }
            // Now we need to write out the orphan roles.
            if (this.orphanStream != null)
                try (PrintWriter orphanWriter = new PrintWriter(this.orphanStream)) {
                    log.info("Writing orphan report. {} orphans found.", this.roleFids.size());
                    orphanWriter.println("role_name\tfid\tcount");
                    for (Map.Entry<String, String> roleEntry : this.roleFids.entrySet()) {
                        String role = roleEntry.getKey();
                        orphanWriter.format("%s\t%s\t%d%n", role, roleEntry.getValue(), this.roleCounts.getCount(role));
                    }
                }
            // Here we write out the function counts.
            if (this.countStream != null)
                try (PrintWriter countWriter = new PrintWriter(this.countStream)) {
                    log.info("Writing count report. {} functions found.", this.funCounts.size());
                    countWriter.println("role_name\tsub_count");
                    for (CountMap<String>.Count count : this.funCounts.sortedCounts())
                        countWriter.format("%s\t%d%n", count.getKey(), count.getCount());
                }
            // Finally, the dead roles.
            if (this.deadRoleFile != null) {
                try (PrintWriter deadWriter = new PrintWriter(this.deadRoleFile)) {
                    log.info("Writing dead-role report. {} functions found.", this.deadRoleMap.size());
                    deadWriter.println("role name\tsubsystem");
                    for (Map.Entry<String, List<String>> deadRoleData : this.deadRoleMap.entrySet()) {
                        String role = deadRoleData.getKey();
                        for (String subsys : deadRoleData.getValue())
                            deadWriter.println(role + "\t" + subsys);
                    }
                }
            }
        } finally {
            if (this.countStream != null)
                this.countStream.close();
            if (this.orphanStream != null)
                this.orphanStream.close();
        }
    }

    /**
     * Read in all the genomes to build the feature/role/function maps.
     *
     * @throws IOException
     */
    private void processGenomes() throws IOException {
        // Get the full set of genome directories.  This automatically removes deleted genomes.
        File orgRoot = new File(this.coreDir, "Organisms");
        OrganismDirectories genomes = new OrganismDirectories(orgRoot);
        // Loop through the genomes, keeping roles.
        for (String genomeId : genomes) {
            log.info("Scanning genome {}.", genomeId);
            // Get this genome's directory and read its functional assignments.
            File genomeDir = new File(orgRoot, genomeId);
            Map<String, String> gFunctions = RowData.readFunctions(genomeDir, genomeId, TYPES);
            for (Map.Entry<String, String> fidFunction : gFunctions.entrySet()) {
                // Ignore hypothetical protein.
                String function = fidFunction.getValue();
                if (! StringUtils.equalsIgnoreCase(function, "hypothetical protein")) {
                    // Split this function into roles.
                    String[] fidRoles = Feature.rolesOfFunction(function);
                    // Process all the roles.
                    for (String role : fidRoles) {
                        this.roleCounts.count(role);
                        this.roleFids.put(role, fidFunction.getKey());
                    }
                    // If this is a compound role, remember it as a compound.
                    if (fidRoles.length > 1)
                        this.fidFunctions.put(fidFunction.getKey(), RoleUtilities.commentFree(function));
                }
            }
        }
        log.info("{} different roles in system.  {} compound-role features.",
                this.roleFids.size(), this.fidFunctions.size());
    }

}
