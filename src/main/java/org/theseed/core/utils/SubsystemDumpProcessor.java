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
import java.util.List;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.proteins.RoleMap;
import org.theseed.subsystems.VariantId;
import org.theseed.subsystems.core.CoreSubsystem;
import org.theseed.subsystems.core.SubsystemRule;
import org.theseed.utils.BaseMultiReportProcessor;
import org.theseed.utils.ParseFailureException;

import com.github.cliftonlabs.json_simple.JsonArray;
import com.github.cliftonlabs.json_simple.JsonException;
import com.github.cliftonlabs.json_simple.JsonObject;
import com.github.cliftonlabs.json_simple.Jsoner;

/**
 * This command dumps all the CoreSEED subsystems into JSON list files in output directories created
 * one per subsystem.  Each output directory will have as its name the subsystem ID.  In the directory
 * there will be a file "subsystem.json" describing the subsystem, a file "subsystem_cell.json" describing
 * each role/feature relationship, and a file  "variants.json" describing the variants.
 *
 * The positional parameters should be the name of the input CoreSEED subsystem directory and the name of
 * the appropriate role definition file.  The subsystems will be in the subdirectories of the input
 * directory.  The command-line options are the following:
 *
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -D	output directory name (default "subJson" under the current working directory)
 *
 * --clear		erase the output directory before processing
 * --all		output experimental subsystems in addition to normal ones
 * --missing	if specified, output directories that already exist will be skipped
 *
 */
public class SubsystemDumpProcessor extends BaseMultiReportProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(SubsystemDumpProcessor.class);
    /** list of subsystem directories to process */
    private File[] subDirs;
    /** role definition map */
    private RoleMap roleMap;
    /** file filter for listing subsystems */
    private static final FileFilter SUB_FILTER = new FileFilter() {

        @Override
        public boolean accept(File pathname) {
            boolean retVal = false;
            if (pathname.isDirectory()) {
                File pubMarker = new File(pathname, "EXCHANGABLE");
                retVal = pubMarker.isFile();
            }
            return retVal;
        }

    };

    // COMMAND-LINE OPTIONS

    /** if specified, output directories that already exist will not be rewritten */
    @Option(name = "--missing", usage = "if specified, existing output directories will not be overwritten")
    private boolean missingFlag;

    /** if specified, all subsystems, including experimental ones, will be output */
    @Option(name = "--all", usage = "if specified, experimental subsystems will be output as well as normal ones")
    private boolean allFlag;

    /** input CoreSEED subsystem directory */
    @Argument(index = 0, metaVar = "inSubsDir", usage = "input CoreSEED subsystem directory", required = true)
    private File inSubsDir;

    /** role definition file */
    @Argument(index = 1, metaVar = "roles.in.subsystems", usage = "role definition file", required = true)
    private File roleFile;

    @Override
    protected File setDefaultOutputDir(File curDir) {
        return new File(curDir, "subJson");
    }

    @Override
    protected void setMultiReportDefaults() {
        this.allFlag = false;
        this.missingFlag = false;
    }

    @Override
    protected void validateMultiReportParms() throws IOException, ParseFailureException {
        // Verify the input directory.
        if (! this.inSubsDir.isDirectory())
            throw new FileNotFoundException("Input directory " + this.inSubsDir + " is not found or invalid.");
        // List the subsystems.
        this.subDirs = this.inSubsDir.listFiles(SUB_FILTER);
        log.info("{} subsystem directories found.", this.subDirs.length);
        if (this.subDirs.length == 0)
            throw new FileNotFoundException("No public subsystems found in " + this.inSubsDir + ".");
        // Read in the role map.
        this.roleMap = RoleMap.load(this.roleFile);
        log.info("{} role definitions loaded from {}.", this.roleMap.size(), this.roleFile);
    }

    @Override
    protected void runMultiReports() throws Exception {
        // Loop through the subsystems, reading them in and then writing them out.
        int subCount = 0;
        int skipCount = 0;
        int subWritten = 0;
        for (File subDir : this.subDirs) {
            subCount++;
            String subId = subDir.getName();
            String subName = CoreSubsystem.dirToName(subDir);
            log.info("Processng subsystem {} of {}: {}.", subCount, this.subDirs.length, subName);
            File outDir = this.getOutFile(subId);
            if (outDir.isDirectory() && this.missingFlag) {
                skipCount++;
                log.info("Skipping {}: output directory already exists.");
            } else {
                try {
                    // Read in the subsystem.
                    CoreSubsystem subsystem = new CoreSubsystem(subDir, this.roleMap);
                    // Verify that we want to write it.
                    if (! this.allFlag && ! subsystem.isGood()) {
                        skipCount++;
                        log.info("Skipping experimental subsystem {}.", subName);
                    } else {
                        if (! outDir.isDirectory()) {
                            // Insure the output directory exists.
                            log.info("Creating output directory {}.", outDir);
                            FileUtils.forceMkdir(outDir);
                        }
                        this.writeSubsystemFile(outDir, subsystem);
                        this.writeVariantFile(outDir, subsystem);
                        this.writeSpreadsheetFile(outDir, subsystem);
                        subWritten++;
                    }
                } catch (ParseFailureException e) {
                    log.error("Parsing error in {}. Subsystem skipped.", subDir.getName());
                    log.info(e.toString());
                    skipCount++;
                }
            }
        }
        log.info("{} directories written, {} skipped.", subWritten, skipCount);
    }

    /**
     * This method writes a file containing the high-level subsystem information.
     *
     * @param outDir		output directory name
     * @param subsystem		subsystem data structure
     *
     * @throws JsonException
     * @throws IOException
     */
    private void writeSubsystemFile(File outDir, CoreSubsystem subsystem) throws IOException, JsonException {
        JsonObject subsystemJson = subsystem.getSubsystemJson();
        JsonArray outJson = new JsonArray();
        outJson.add(subsystemJson);
        this.writeJson(outJson, outDir, "subsystem.json");
    }
    /**
     * This method writes a file containing the variant-specific data.
     *
     * @param outDir		output directory name
     * @param subsystem		subsystem data structure
     *
     * @throws JsonException
     * @throws IOException
     */
    private void writeVariantFile(File outDir, CoreSubsystem subsystem) throws IOException, JsonException {
        JsonArray outJson = new JsonArray();
        // Get the variant notes.
        var vNoteMap = subsystem.getVariantNotes();
        // Loop through the variants.
        Set<String> variantCodes = subsystem.getVariantCodes();
        for (String variantCode : variantCodes) {
            JsonObject variantJson = new JsonObject();
            variantJson.put("variant_code", variantCode);
            // Compute the code type here.
            String type = "inactive";
            if (VariantId.isLikely(variantCode))
                type = "likely";
            else if (VariantId.isActive(variantCode))
                type = "active";
            variantJson.put("variant_type", type);
            // Add a rule if we have one.
            SubsystemRule vRule = subsystem.getRule(variantCode);
            if (vRule != null)
                variantJson.put("variant_rule", vRule.toString());
            String description = vNoteMap.getOrDefault(variantCode, "");
            variantJson.put("description", description);
            // Get the variant's genome list.
            JsonArray genomes = new JsonArray();
            genomes.addAll(subsystem.getVariantGenomes(variantCode));
            variantJson.put("genomes", genomes);
            // Save this variant in the result list.
            outJson.add(variantJson);
        }
        // Write the variant file.
        this.writeJson(outJson, outDir, "variants.json");
    }

    /**
     * This method writes a file containing the spreadsheet cell data.
     *
     * @param outDir		output directory name
     * @param subsystem		subsystem data structure
     *
     * @throws JsonException
     * @throws IOException
     */
    private void writeSpreadsheetFile(File outDir, CoreSubsystem subsystem) throws IOException, JsonException {
        // Each record in this file represents a feature / subsystem connection.
        // We find this data in the rows.
        JsonArray outJson = new JsonArray();
        var iter = subsystem.rowIterator();
        while (iter.hasNext()) {
            CoreSubsystem.Row row = iter.next();
            // Save the genome ID and variant code.
            String genomeId = row.getGenomeId();
            String variantCode = row.getVariantCode();
            // Loop through the columns.
            List<Set<String>> columns = row.getColumns();
            for (int i = 0; i < columns.size(); i++) {
                Set<String> fids = columns.get(i);
                if (! fids.isEmpty()) {
                    // Get the role data.
                    String role = subsystem.getRole(i);
                    String abbr = subsystem.getRoleAbbr(i);
                    for (String fid : fids) {
                        JsonObject cellJson = new JsonObject();
                        cellJson.put("genome_id", genomeId);
                        cellJson.put("variant_code", variantCode);
                        cellJson.put("role_name", role);
                        cellJson.put("role_abbr", abbr);
                        cellJson.put("patric_id", fid);
                        cellJson.put("cell_idx", i);
                        outJson.add(cellJson);
                    }
                }
            }
        }
        this.writeJson(outJson, outDir, "subsystem_cell.json");
    }

    /**
     * Write a JSON array to the named output file.
     *
     * @param object	object to write
     * @param outDir	subsystem output directory
     * @param name		base name of output file
     *
     * @throws IOException
     * @throws JsonException
     */
    private void writeJson(JsonArray object, File outDir, String name) throws IOException, JsonException {
        String jsonString = object.toJson();
        try (PrintWriter writer = new PrintWriter(new File(outDir, name))) {
            Jsoner.prettyPrint(new StringReader(jsonString), writer, "    ", "\n");
        }
    }

}
