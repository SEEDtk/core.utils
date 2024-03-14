/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.BaseProcessor;
import org.theseed.basic.ParseFailureException;
import org.theseed.genome.Feature;
import org.theseed.genome.Genome;
import org.theseed.genome.GenomeDirectory;
import org.theseed.genome.SubsystemRow;
import org.theseed.io.FieldInputStream;
import org.theseed.subsystems.SubsystemIdMap;
import org.theseed.subsystems.VariantId;

import com.github.cliftonlabs.json_simple.JsonArray;
import com.github.cliftonlabs.json_simple.JsonObject;
import com.github.cliftonlabs.json_simple.Jsoner;

/**
 * This command replaces (or creates) the rows.json files in a subsystem JSON dump to specify the genomes in
 * a GTO directory instead of the genomes used to curate the subsystems.  The GTOs must already have the
 * subsystems projected, and they should be the same subsystems as found in the dump directory (though they
 * can be a superset).  In general, we assume a uniform projection:  that is, every subsystem instance in
 * the GTO has the same roles in the same order.  An extra or missing role in the GTO is a non-fatal error,
 * as is a subsystem not found in the subsystem dump.
 *
 * The strategy will be to read in the genomes one at a time.  When a subsystem is found, we open its subsystem.json
 * file and save its list of roles.  Then we use the genome data to build a hash that maps genome IDs to a bitset of
 * the roles used.  Once all the genomes are processed, we can write the rows.json files.
 *
 * To save memory, we use subsystem IDs instead of names in the big hash.  These are generated using a magic map.
 *
 * The positional parameters are the name of the GTO input directory and the name of the subsystem directory.
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 *
 * @author Bruce Parrello
 *
 */
public class SubsystemFixProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(SubsystemFixProcessor.class);
    /** map of subsystem IDs to names */
    private SubsystemIdMap subIdMap;
    /** map of subsystem IDs to directories */
    private Map<String, File> subDirMap;
    /** map of genome IDs to names */
    private Map<String, String> genomeMap;
    /** two-level map of subsystem ID -> genome ID -> used-role bitmap */
    private Map<String, Map<String, RowDescriptor>> subRowMap;
    /** map of subsystem IDs to role lists */
    private Map<String, List<String>> subRoleMap;
    /** list of subsystem directory names */
    private File[] subsystemDirs;
    /** input GTO directory source */
    private GenomeDirectory genomes;
    /** number of errors found */
    private int errorCount;
    /** file filter for subsystem dump directories */
    private FileFilter SUBSYSTEM_DUMP_FILTER = new FileFilter() {

        @Override
        public boolean accept(File pathname) {
            boolean retVal = pathname.isDirectory();
            if (retVal) {
                File subFile = new File(pathname, "subsystem.json");
                retVal = subFile.canRead();
            }
            return retVal;
        }

    };

    // COMMAND-LINE OPTIONS

    /** genome input directory */
    @Argument(index = 0, metaVar = "gtoDir", usage = "directory of GTOs to process", required = true)
    private File gtoDir;

    /** subsystem JSON dump directory */
    @Argument(index = 1, metaVar = "subDir", usage = "master subsystem JSON dump directory", required = true)
    private File subDir;

    /**
     * This class contains all the data we need about a subsystem row for the output files.
     */
    protected class RowDescriptor {

        /** variant code */
        private String variantCode;
        /** map of used roles */
        private BitSet roleSet;

        /**
         * Construct a row descriptor for a missing subsystem.
         */
        /**
         * Construct a row descriptor for a specific subsystem row.
         *
         * @param subId			subsystem ID code
         * @param subRow		subsystem row object from the genome
         */
        protected RowDescriptor(String subId, SubsystemRow subRow) {
            // Save the variant code.
            this.variantCode = subRow.getVariantCode();
            // Get the subsystem's role list.
            List<String> roles = SubsystemFixProcessor.this.subRoleMap.get(subId);
            // Create a bit set for this subsystem row.
            this.roleSet = new BitSet(roles.size());
            // Loop through the role bindings.
            for (SubsystemRow.Role binding : subRow.getRoles()) {
                if (! binding.getFeatures().isEmpty()) {
                    // Here the role is used in the subsystem, so we set its bit.
                    int roleIdx = roles.indexOf(binding.getName());
                    if (roleIdx < 0)
                        SubsystemFixProcessor.this.errorCount++;
                    else
                        this.roleSet.set(roleIdx);
                }
            }

        }

        /**
         * Construct a row descriptor for a missing subsystem.
         *
         * @param gRoles	genome role set
         * @param subId		ID of the missing subsystem
         */
        protected RowDescriptor(Set<String> gRoles, String subId) {
            this.variantCode = "-1";
            // Get the subsystem's role list.
            List<String> roles = SubsystemFixProcessor.this.subRoleMap.get(subId);
            final int n = roles.size();
            // Record the roles found.
            this.roleSet = new BitSet(n);
            for (int i = 0; i < n; i++) {
                String role = roles.get(i);
                if (gRoles.contains(role))
                    this.roleSet.set(i);
            }
        }

        /**
         * @return the variant code
         */
        protected String getVariantCode() {
            return this.variantCode;
        }

        /**
         * @return the set of roles used
         *
         * @param subId		ID of the subsystem in question
         */
        protected List<String> getRoleSet(String subId) {
            List<String> roles = SubsystemFixProcessor.this.subRoleMap.get(subId);
            List<String> retVal = this.roleSet.stream().mapToObj(i -> roles.get(i)).collect(Collectors.toList());
            return retVal;
        }

    }


    @Override
    protected void setDefaults() {
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        if (! this.gtoDir.isDirectory())
            throw new FileNotFoundException("Input GTO directory " + this.gtoDir + " is not found or invalid.");
        // Check the subsystem directory.
        if (! this.subDir.isDirectory())
            throw new FileNotFoundException("Master subsystem directory " + this.subDir + " is not found or invalid.");
        // Connect to the GTO source.
        this.genomes = new GenomeDirectory(this.gtoDir);
        final int nGenomes = this.genomes.size();
        if (nGenomes <= 0)
            throw new IOException("No genomes found in " + this.gtoDir + ".");
        log.info("{} genomes found in {}.", nGenomes, this.gtoDir);
        // Find the subsystems in the subsystem directory.
        this.subsystemDirs = this.subDir.listFiles(SUBSYSTEM_DUMP_FILTER);
        if (this.subsystemDirs.length <= 0)
            throw new IOException("No subsystem dump directories found in " + this.subDir + ".");
        log.info("{} subsystems found in {}.", this.subsystemDirs.length);
        // Create the basic maps.
        log.info("Initializing data structures.");
        this.genomeMap = new HashMap<String, String>(nGenomes * 4 / 3 + 1);
        this.subIdMap = new SubsystemIdMap();
        final int subHashSize = this.subsystemDirs.length + 4 / 3 + 1;
        this.subRoleMap = new HashMap<String, List<String>>(subHashSize);
        this.subRowMap = new HashMap<String, Map<String, RowDescriptor>>(subHashSize);
        this.subDirMap = new HashMap<String, File>(subHashSize);
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // First, we read the subsystems.  This enables us to create the subsystem ID map and the role lists.
        // We use a simple array list for the roles because the number of roles per suybsystem is always small.
        int subCount = 0;
        int roleCount = 0;
        long lastMsg = System.currentTimeMillis();
        log.info("Reading in subsystem names and roles.");
        for (File subsystemDir : this.subsystemDirs) {
            File subFile = new File(subsystemDir, "subsystem.json");
            try (FieldInputStream subStream = FieldInputStream.create(subFile)) {
                int nameIdx = subStream.findField("subsystem_name");
                int roleIdx = subStream.findField("role_names");
                // There will always be exactly one record, but we loop just in case.
                for (var record : subStream) {
                    // Get the name and ID.
                    String name = record.get(nameIdx);
                    String subId = this.subIdMap.findOrInsert(name);
                    // Connect this directory to the ID.
                    this.subDirMap.put(subId, subsystemDir);
                    // Get the roles.
                    List<String> roles = record.getList(roleIdx);
                    this.subRoleMap.put(subId, roles);
                    subCount++;
                    roleCount += roles.size();
                }
                if (System.currentTimeMillis() - lastMsg >= 5000) {
                    log.info("{} subsystems read with {} roles.", subCount, roleCount);
                    lastMsg = System.currentTimeMillis();
                }
            }
        }
        log.info("{} total subsystems read with {} roles.", subCount, roleCount);
        // Now we read the genomes.  Each genome is added to the master subsystem map.
        int gCount = 0;
        int rowCount = 0;
        int missCount = 0;
        this.errorCount = 0;
        final int nGenomes = this.genomes.size();
        log.info("Processing {} genomes.", nGenomes);
        for (Genome genome : this.genomes) {
            // Get the genome ID and fill in the name map.
            String genomeId = genome.getId();
            String genomeName = genome.getName();
            this.genomeMap.put(genomeId, genomeName);
            // Get the subsystem rows in this genome.
            var subRows = genome.getSubsystems();
            // This will track the subsystems we've found.
            Set<String> subsFound = new HashSet<String>(subRows.size() * 4 / 3);
            // Process all the rows.
            for (SubsystemRow subRow : subRows) {
                String subName = subRow.getName();
                var subIdObject = this.subIdMap.getByName(subName);
                if (subIdObject == null) {
                    log.error("Could not find subsystem \"{}\" from genome {}.", subName, genome);
                    this.errorCount++;
                } else {
                    String subId = subIdObject.getId();
                    // Create a descriptor for the row.
                    RowDescriptor rowDesc = this.new RowDescriptor(subId, subRow);
                    // Find the row map for this subsystem and add the descriptor.
                    this.storeRowDescriptor(genomeId, subId, rowDesc);
                    // Record this subsystem being found.
                    subsFound.add(subId);
                    rowCount++;
                }
            }
            // Now we need to mark all the subsystems we didn't find as incomplete.  To do this, we need the set of roles
            // in this genome.
            Set<String> genomeRoles = new HashSet<String>(genome.getFeatureCount() * 4 / 3 + 1);
            for (Feature peg : genome.getPegs()) {
                String function = peg.getPegFunction();
                String[] roles = Feature.rolesOfFunction(function);
                for (String role : roles)
                    genomeRoles.add(role);
            }
            log.info("Processing missing subsystems.  {} distinct roles found in {}.", genomeRoles.size(), genome);
            // Next we looop through the subsystems, skipping the ones present.
            for (var subEntry : this.subRoleMap.entrySet()) {
                String subId = subEntry.getKey();
                if (! subsFound.contains(subId)) {
                    // Here we have a missing subsystem.
                    RowDescriptor rowDesc = this.new RowDescriptor(genomeRoles, subId);
                    // Find the row map for this subsystem and add the descriptor.
                    this.storeRowDescriptor(genomeId, subId, rowDesc);
                    missCount++;
                }
            }
            gCount++;
            log.info("{} of {} genomes processed.  {} rows found, {} missing rows.", gCount, nGenomes, rowCount, missCount);
        }
        log.info("{} total genomes processed.  {} rows found.  {} errors.", gCount, rowCount, this.errorCount);
        // Now all the subsystems have row specifications.  Write out the row files.
        subCount = 0;
        int nSubs = this.subRowMap.size();
        rowCount = 0;
        for (var subRowEntry : this.subRowMap.entrySet()) {
            // Get the subsystem name and directory.
            final String subId = subRowEntry.getKey();
            String subName = this.subIdMap.getName(subId);
            File subsystemDir = this.subDirMap.get(subId);
            // Set up a json object for the output rows.
            JsonArray fileJson = new JsonArray();
            Map<String, RowDescriptor> rowMap = subRowEntry.getValue();
            // Process all the genomes.
            for (var rowEntry : rowMap.entrySet()) {
                // Get the genome ID and the row descriptor.
                String genomeId = rowEntry.getKey();
                RowDescriptor rowDesc= rowEntry.getValue();
                // Build the json entry for this row.
                JsonObject rowJson = new JsonObject();
                rowJson.put("genome_id", genomeId);
                rowJson.put("genome_name", this.genomeMap.get(genomeId));
                String variantCode = rowDesc.variantCode;
                rowJson.put("variant_code", variantCode);
                String vType = VariantId.computeActiveLevel(variantCode);
                rowJson.put("variant_type", vType);
                rowJson.put("is_active", VariantId.isActive(variantCode));
                rowJson.put("is_clean", ! VariantId.isDirty(variantCode));
                rowJson.put("subsystem_name", subName);
                // Add the row to the output JSON.
                fileJson.add(rowJson);
                rowCount++;
            }
            // Write the json to the file.
            String jsonString = fileJson.toJson();
            try (PrintWriter writer = new PrintWriter(new File(subsystemDir, "rows.json"))) {
                Jsoner.prettyPrint(new StringReader(jsonString), writer, "    ", "\n");
            }
            subCount++;
            log.info("{} of {} subsystems processed.  {} rows output.", subCount, nSubs, rowCount);
        }
    }

    /**
     * Store a row descriptor in a subsystem's row map.
     *
     * @param genomeId	ID of the relevant genome
     * @param subId		ID of the relevant subsystem
     * @param rowDesc	row descriptor to store
     */
    private void storeRowDescriptor(String genomeId, String subId, RowDescriptor rowDesc) {
        Map<String, RowDescriptor> rowMap = this.subRowMap.computeIfAbsent(subId, x -> new TreeMap<String, RowDescriptor>());
        rowMap.put(genomeId, rowDesc);
    }

}
