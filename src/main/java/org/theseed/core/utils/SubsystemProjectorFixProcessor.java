/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.ParseFailureException;
import org.theseed.io.TabbedLineReader;
import org.theseed.subsystems.SubsystemProjector;
import org.theseed.subsystems.VariantSpec;
import org.theseed.subsystems.SubsystemSpec;
import org.theseed.utils.BaseMultiReportProcessor;

/**
 *
 * This subcommand takes as input the "variant.tbl" file containing a subsystem projector and produces as output the
 * "roleMap.tbl", "subList.tbl", and "variantMap.tbl" used by the P3 subsystem projector. The positional parameters are the
 * name of the input file and the role-has file, respectively. (The role-hash file is produced by the script "build_role_hash.pl".)
 * The output files are put in the specified output directory.
 *
 * The optional filter file is a single-column text file with a header line. If it is omitted, all subsystems are output.
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -D	output directory name (defaults to the current directory)
 *
 * --clear	erase the output directory before processing
 * --filter optional filter file containing the list of good subsystem names

 * @author Bruce Parrello
 *
 */
public class SubsystemProjectorFixProcessor extends BaseMultiReportProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(SubsystemProjectorFixProcessor.class);
    /** map of role IDs to role checksums */
    private Map<String, String> checksumMap;
    /** map of role names to checksums */
    private Map<String, String> nameMap;
    /** subsystem projector */
    private SubsystemProjector projector;
    /** set of good subsystem names */
    private Set<String> subFilter;

    // COMMAND-LINE OPTIONS

    /** optional name of the subsystem filter file */
    @Option(name = "--filter", metaVar = "ss_good.txt", usage = "optional filter file containing names of subsystems to use")
    private File filterFile;

    /** name of the input variants.tbl file */
    @Argument(index = 0, metaVar = "variants.tbl", usage = "input subsystem projector file (new-format)", required = true)
    private File inFile;

    /** name of the role-hash file */
    @Argument(index = 1, metaVar = "roleHash.tbl", usage = "input role-hash file containing checksums", required = true)
    private File hashFile;

    @Override
    protected File setDefaultOutputDir(File curDir) {
        return curDir;
    }

    @Override
    protected void setMultiReportDefaults() {
        this.filterFile = null;
    }

    @Override
    protected void validateMultiReportParms() throws IOException, ParseFailureException {
        // Validate and load the role hash file.
        if (! this.hashFile.canRead())
            throw new FileNotFoundException("Role-hash file " + this.hashFile + " is not found or unreadable.");
        this.checksumMap = new HashMap<String, String>(15000);
        this.nameMap = new HashMap<String, String>(15000);
        try (TabbedLineReader hashStream = new TabbedLineReader(this.hashFile)) {
            log.info("Reading role hash codes from {}.", this.hashFile);
            int idCol = hashStream.findField("role_id");
            int nameCol = hashStream.findField("role_name");
            int hashCol = hashStream.findField("checksum");
            for (var line : hashStream) {
                this.checksumMap.put(line.get(idCol), line.get(hashCol));
                this.nameMap.put(line.get(nameCol), line.get(hashCol));
            }
            log.info("{} role checksums and {} role names found in {}.", this.checksumMap.size(),
                    this.nameMap.size(), this.hashFile);
        }
        // Validate the input file and load the projector.
        if (! this.inFile.canRead())
            throw new FileNotFoundException("Projector file " + this.inFile + " is not found or unreadable.");
        log.info("Loading subsystem projector from {}.", this.inFile);
        this.projector = SubsystemProjector.load(this.inFile);
        // Validate and load the subsystem filter.
        if (this.filterFile == null) {
            // Here there is no subsystem filter.
            this.subFilter = null;
            log.info("All subsystems will be output.");
        } else if (! this.filterFile.canRead())
            throw new FileNotFoundException("Filter file " + this.inFile + " is not found or unreadable.");
        else {
            // Here we need to load the filter file.
            this.subFilter = TabbedLineReader.readSet(this.filterFile, "1");
            log.info("{} subsystems found in filter file {}.", this.subFilter.size(), this.filterFile);
        }
    }

    @Override
    protected void runMultiReports() throws Exception {
        // Allocate a buffer for the output lines.
        StringBuilder buffer = new StringBuilder(200);
        // Create the role map output file.
        log.info("Creating role map and subsystem list files.");
        try (PrintWriter roleMapWriter = this.openReport("roleMap.tbl");
                PrintWriter subListWriter = this.openReport("subList.tbl")) {
            // Loop through the subsystems in this projector.
            Collection<SubsystemSpec> subs = this.projector.getSubsystems();
            int subCount = 0;
            int errorCount = 0;
            int roleCount = 0;
            int skipCount = 0;
            for (SubsystemSpec sub : subs) {
                // Get the subsystem name.
                String subName = sub.getName();
                // Verify it is a good subsystem.
                if (this.subFilter != null && ! this.subFilter.contains(subName))
                    skipCount++;
                else {
                    // This is a special hack. If the subsystem name ends in an underscore, we convert it
                    // to a space for PATRIC.
                    if (subName.endsWith("_"))
                        subName = subName.replace('_', ' ');
                    // The subsystem list file starts with the name.
                    subListWriter.println(subName);
                    // Next, we build the class string.
                    List<String> classes = sub.getClassifications();
                    buffer.setLength(0);
                    for (int i = 0; i < 3; i++) {
                        if (i > 0) buffer.append('\t');
                        if (i < classes.size()) buffer.append(classes.get(i));
                    }
                    subListWriter.println(buffer.toString());
                    // Put each of the subsystem's roles in the role map and write them to the subsystem list file.
                    for (String role : sub.getRoles()) {
                        // Compute the checksum.
                        String checksum = this.nameMap.get(role);
                        if (checksum == null)
                            errorCount++;
                        else {
                            // Output the checksum of the role and the associated subsystem to the role map.
                            roleMapWriter.println(checksum + "\t" + subName);
                            // Output the role name and the checksum to the subsystem list.
                            subListWriter.println(role + "\t" + checksum);
                            // Count the role.
                            roleCount++;
                        }
                    }
                    // Finish the subsystem section in the subsystem list file.
                    subListWriter.println("//");
                    subCount++;
                }
            }
            log.info("{} roles output for {} subsystems. {} skipped due to filter. {} errors in name map.", roleCount, subCount,
                    skipCount, errorCount);
        }
        // Create the variant map file.
        log.info("Creating variant map file.");
        try (PrintWriter varMapWriter = this.openReport("variantMap.tbl")) {
            // Loop through the variants in this projector.
            int varCount = 0;
            int skipCount = 0;
            int errorCount = 0;
            int emptyCount = 0;
            Collection<VariantSpec> variants = this.projector.getVariants();
            for (VariantSpec variant : variants) {
                // Get the parent subsystem name.
                String subName = variant.getName();
                // Verify it is a good subsystem.
                if (this.subFilter != null && ! this.subFilter.contains(subName))
                    skipCount++;
                else {
                    // This is a special hack. If the subsystem name ends in an underscore, we convert it
                    // to a space for PATRIC.
                    if (subName.endsWith("_"))
                        subName = subName.replace('_', ' ');
                    // Get the list of role IDs for this variant's signature.
                    Collection<String> roleIds = variant.getRoles();
                    // If there are no roles, skip this variant.
                    if (roleIds.isEmpty())
                        emptyCount++;
                    else {
                        // Finally, get the variant code.
                        String varCode = variant.getCode();
                        // Now we output the name and variant code followed by the checksums for the roles.
                        buffer.setLength(0);
                        buffer.append(subName).append('\t');
                        buffer.append(varCode).append('\t');
                        Iterator<String> iter = roleIds.iterator();
                        // Store the first role ID.
                        String role = iter.next();
                        errorCount += this.storeRole(buffer, role);
                        // Store the rest.
                        while (iter.hasNext()) {
                            role = iter.next();
                            buffer.append(' ');
                            errorCount += this.storeRole(buffer, role);
                        }
                        // Write the output line.
                        varMapWriter.println(buffer.toString());
                        varCount++;
                    }
                }
            }
            log.info("{} variants output with {} errors. {} empty variants skipped, {} rejected by filter.", varCount, errorCount,
                    skipCount, emptyCount);
        }
    }

    /**
     * Store a role in the line output buffer. The role must be converted to a role ID.
     *
     * @param buffer	line output buffer
     * @param role		ID of role to store
     *
     * @return 1 if an error occurred, else 0
     */
    private int storeRole(StringBuilder buffer, String role) {
        int retVal;
        String checksum = this.checksumMap.get(role);
        if (checksum == null) {
            retVal = 1;
            buffer.append("<error>");
        } else {
            retVal = 0;
            buffer.append(checksum);
        }
        return retVal;
    }

}
