/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.ParseFailureException;
import org.theseed.genome.Feature;
import org.theseed.genome.core.CoreUtilities;
import org.theseed.io.TabbedLineReader;
import org.theseed.subsystems.StrictRole;
import org.theseed.subsystems.StrictRoleMap;
import org.theseed.subsystems.core.CoreSubsystem;
import org.theseed.utils.BasePipeProcessor;

/**
 * This sub-command processes the bad-variants report from "SubsystemRuleCheckProcessor" and looks for
 * roles with non-standard names.
 *
 * The role name in the subsystem definition is the standard name for the role; however, there are cases where
 * a role name differs in an insignificant way (comments, EC numbers, capitalization), and this can cause the
 * SEED subsystem software to miss the role.  This command detects that situation.
 *
 * The bad-variants report is sorted by subsystem and we will be operating on subsystem/genome pairs.  The genomes
 * will be kept in a permanent cache and the subsystems will be cached one at a time.  For each pair, we extract the
 * role IDs for the subsystem roles and check each instance in the genome to see if there is a discrepancy.
 *
 * The output report will be to the standard output.  The bad-variants report should be on the standard input.
 *
 * The positional parameter is the name of the CoreSEED data directory.  The role definition file should be in
 * this directory with the name "roles.in.subsystems", but this can be overridden.
 *
 * The command-line options are
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -i	input bad-variants report (if not STDIN)
 * -o	output role-name report (if not STDOUT)
 *
 * --roles	role definition file (default "subsystem.roles" in the main CoreSEED directory)
 *
 * @author Bruce Parrello
 *
 */
public class SubsystemRoleCheckProcessor extends BasePipeProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(SubsystemRoleCheckProcessor.class);
    /** role definitions */
    private StrictRoleMap roleMap;
    /** current subsystem */
    private CoreSubsystem subsystem;
    /** CoreSEED utilities object */
    private CoreUtilities coreSeed;
    /** master subsystem directory */
    private File subDir;
    /** map of genome IDs to cached genome maps */
    private Map<String, Map<String, String>> genomeCache;
    /** index of subsystem name in the input file */
    private int subNameIdx;
    /** index of genome ID in the input file */
    private int genomeIdIdx;
    /** map of subsystem names to directories */
    private Map<String, File> subMap;

    // COMMAND-LINE OPTIONS

    /** role definition file name */
    @Option(name = "--roles", metaVar = "roles.in.subsystems", usage = "role definition file")
    private File roleFile;

    /** input coreSEED data directory */
    @Argument(index = 0, metaVar = "FIGdisk/FIG/Data", usage = "input CoreSEED data directory", required = true)
    private File coreDir;

    @Override
    protected void setPipeDefaults() {
        this.roleFile = null;
    }

    @Override
    protected void validatePipeParms() throws IOException, ParseFailureException {
        // Verify the CoreSEED data directory.
        if (! this.coreDir.isDirectory())
            throw new FileNotFoundException(this.coreDir + " is not a directory.");
        this.subDir = new File(coreDir, "Subsystems");
        if (! this.subDir.isDirectory())
            throw new FileNotFoundException(this.coreDir + " has no subsystems directory.");
        File orgDir = new File(coreDir, "Organisms");
        if (! orgDir.isDirectory())
            throw new FileNotFoundException(this.coreDir + " has not organisms directory.");
        // Connect to the genomes.
        log.info("Connecting to {} organism directory.", orgDir);
        this.coreSeed = new CoreUtilities(orgDir);
        // Connect to the subsystems.  We need a map of names to directories.
        log.info("Scanning subsystem directory {}.", this.subDir);
        var subList = CoreSubsystem.getSubsystemDirectories(this.coreDir);
        this.subMap = new HashMap<String, File>(subList.size() * 4 / 3 + 1);
        for (File sDir : subList) {
            String name = CoreSubsystem.dirToName(sDir);
            this.subMap.put(name, sDir);
        }
        log.info("{} subsystems found in {}.", this.subMap.size(), this.subDir);
        // Read in the role definitions.
        if (this.roleFile == null)
            this.roleFile = new File(this.coreDir, "subsystem.roles");
        if (! this.roleFile.canRead())
            throw new FileNotFoundException("Role file " + this.roleFile + " is not found or unreadable.");
        this.roleMap = StrictRoleMap.load(this.roleFile);
        log.info("{} role definitions read from {}.", this.roleMap.size(), this.roleFile);
        // Clear the caches.
        this.genomeCache = new HashMap<String, Map<String, String>>(1000);
        this.subsystem = new CoreSubsystem();
    }

    @Override
    protected void validatePipeInput(TabbedLineReader inputStream) throws IOException {
        // Find the two columns of interest from the bad-variant report.
        this.subNameIdx = inputStream.findField("subsystem");
        this.genomeIdIdx = inputStream.findField("genome_id");
    }

    @Override
    protected void runPipeline(TabbedLineReader inputStream, PrintWriter writer) throws Exception {
        // Write the output header.
        writer.println("subsystem\trole_id\tfid\tactual\texpected");
        // Get some counts.
        int pairCount = 0;
        int fidCount = 0;
        int errorCount = 0;
        // Loop through the input file.
        for (var line : inputStream) {
            String subName = line.get(this.subNameIdx);
            String genomeId = line.get(this.genomeIdIdx);
            pairCount++;
            log.info("Comparing {} with {}.", genomeId, subName);
            if (! subName.contentEquals(this.subsystem.getName())) {
                log.info("Loading subsystem {}.", subName);
                File sDir = this.subMap.get(subName);
                if (sDir == null)
                    throw new IOException("Invalid subsystem name \"" + subName + "\".");
                this.subsystem = new CoreSubsystem(sDir, this.roleMap);
            }
            // Get the genome from the cache.  The genomes are stored as maps from feature IDs to functional
            // assignments.
            Map<String, String> genome = this.genomeCache.computeIfAbsent(genomeId,
                    x -> this.getGenomeFunctions(x));
            // Now we get a map from role IDs to role names for the subsystem.
            Map<String, String> subIdMap = this.subsystem.getOriginalNameMap();
            // Finally, we run through all the feature IDs looking for interesting roles.
            for (var fidEntry : genome.entrySet()) {
                String fid = fidEntry.getKey();
                String function = fidEntry.getValue();
                // Separate the function into roles.
                String[] gRoleNames = Feature.rolesOfFunction(function);
                for (String gRoleName : gRoleNames) {
                    StrictRole gRole = this.roleMap.getByName(gRoleName);
                    // Is this an interesting role?
                    if (gRole != null) {
                        final String roleId = gRole.getId();
                        String sRoleName = subIdMap.get(roleId);
                        if (sRoleName != null) {
                            // Here we have an interesting role.
                            fidCount++;
                            if (! sRoleName.contentEquals(gRoleName)) {
                                // Here we have a mismatch to report.
                                writer.println(subName + "\t" + roleId + "\t" + fid + "\t" + gRoleName + "\t" + sRoleName);
                                errorCount++;
                            }
                        }
                    }
                }
            }
        }
        log.info("All done.  {} bad variants processed, {} in-genome roles checked, {} mismatches found.",
                pairCount, fidCount, errorCount);
    }

    /**
     * Retrieve the functional assignments for a genome.
     *
     * @param genomeId	ID of the genome of interest
     *
     * @return a map from feature ID to functional assignment for each feature in the genome
     */
    private Map<String, String> getGenomeFunctions(String genomeId) {
        Map<String, String> retVal;
        try {
            retVal = this.coreSeed.getGenomeFunctions(genomeId, CoreSubsystem.FID_TYPES);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return retVal;
    }

}
