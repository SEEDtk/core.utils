/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.counters.CountMap;
import org.theseed.genome.Feature;
import org.theseed.genome.core.CoreUtilities;
import org.theseed.proteins.Role;
import org.theseed.proteins.RoleMap;
import org.theseed.utils.BaseMultiReportProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This sub-command scans the CoreSEED directories and accumulates data on how often roles are used.  The
 * roles are folded into a giant role map.  One report shows which roles are synonymous and another simply
 * lists them all with the number of times they occur.
 *
 * The positional parameter is the name of the CoreSEED data directory.  The command-line options are as
 * follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -D	output directory name (default "subReports" in the current directory)
 *
 * --clear	erase the output directory before processing
 *
 * @author Bruce Parrello
 *
 */
public class SynonymReportProcessor extends BaseMultiReportProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(SynonymReportProcessor.class);
    /** master role map */
    private RoleMap roleMap;
    /** count maps for role occurrences within role IDs */
    private Map<String, CountMap<String>> synonymCounts;
    /** count map for role IDs */
    private CountMap<String> roleIdCounts;
    /** utility object for accessing CoreSEED */
    private CoreUtilities core;

    /** name of the CoreSEED data directory */
    @Argument(index = 0, metaVar = "/coreseed/FIGdisk/FIG/Data", usage = "name of the input CoreSEED data directory", required = true)
    private File coreDir;


    @Override
    protected File setDefaultOutputDir(File curDir) {
        return new File(curDir, "subReports");
    }

    @Override
    protected void setMultiReportDefaults() {
    }

    @Override
    protected void validateMultiReportParms() throws IOException, ParseFailureException {
        // Verify the coreSEED directory and set up the organism access object.
        if (! this.coreDir.isDirectory())
            throw new FileNotFoundException("CoreSEED directory " + this.coreDir + " is not found or invalid.");
        File orgDir = new File(this.coreDir, "Organisms");
        if (! orgDir.isDirectory())
            throw new FileNotFoundException(this.coreDir + " does not appear to be a CoreSEED directory:  Organisms sub-directory is missing.");
        log.info("Connecting to organism directory {}.", orgDir);
        this.core = new CoreUtilities(orgDir);
        // Create the empty role map and the counters.
        this.roleMap = new RoleMap();
        this.roleIdCounts = new CountMap<String>();
        this.synonymCounts = new HashMap<String, CountMap<String>>(20000);
    }

    @Override
    protected void runMultiReports() throws Exception {
        // We start by opening the output report files and writing the headers.  This insures we stop immediately if
        // we can't write the reports.
        try (PrintWriter sortWriter = this.openReport("roleCounts.tbl");
                PrintWriter synWriter = this.openReport("synonyms.tbl")) {
            sortWriter.println("role\tcount");
            synWriter.println("role_id\trole\tcount");
            // Now we loop through the genomes, counting roles.
            int gCount = 0;
            for (String genomeId : this.core.getGenomes()) {
                gCount++;
                String gName = this.core.getGenomeName(genomeId);
                log.info("Processing genome #{} {}: {}", gCount, genomeId, gName);
                // Load the peg assignments.  We loop through the functions, splitting them
                // into roles and processing each role.
                var functionMap = this.core.getGenomeFunctions(genomeId);
                for (String function : functionMap.values()) {
                    String[] roles = Feature.rolesOfFunction(function);
                    for (String role : roles) {
                        // Use the role map to normalize the role description.
                        Role mappedRole = this.roleMap.findOrInsert(role);
                        // Get the count map for this role ID and count the occurrence.
                        String roleId = mappedRole.getId();
                        CountMap<String> countMap = this.synonymCounts.computeIfAbsent(roleId, x -> new CountMap<String>());
                        countMap.count(role);
                        // Count the occurrence for the role ID.  We use this to sort the synonym report.
                        this.roleIdCounts.count(roleId);
                    }
                }
            }
            log.info("Writing synonym report.");
            // Get the role IDs from most to least popular.
            var sortedRoleIds = this.roleIdCounts.sortedCounts().stream().map(x -> x.getKey()).collect(Collectors.toList());
            for (String roleId : sortedRoleIds) {
                // Get the role's count map.
                CountMap<String> countMap = this.synonymCounts.get(roleId);
                for (var roleNameEntry : countMap.sortedCounts()) {
                    String roleName = roleNameEntry.getKey();
                    int count = roleNameEntry.getCount();
                    synWriter.println(roleId + "\t" + roleName + "\t" + count);
                }
            }
            // Now we need to get all of the role names sorted by count.
            log.info("Writing raw counts.");
            var countList = this.synonymCounts.values().stream().flatMap(x -> x.sortedCounts().stream()).sorted().collect(Collectors.toList());
            for (var counter : countList)
                sortWriter.println(counter.getKey() + "\t" + counter.getCount());
        }
    }

}
