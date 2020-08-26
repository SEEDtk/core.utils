/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.subsystems.ColumnData;
import org.theseed.subsystems.SubsystemData;
import org.theseed.subsystems.SubsystemFilter;
import org.theseed.utils.BaseProcessor;

/**
 * Produce a master list of all the subsystem roles.  For each role, we output the role name, the subsystem name, and the subsystem curator.
 *
 * The positional parameter is the name of the coreSEED data directory.
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

    // COMMAND-LINE OPTIONS

    @Argument(index = 0, metaVar = "CoreSEED/FIG/Data", usage = "coreSEED data directory", required = true)
    private File coreDir;

    @Override
    protected void setDefaults() {
    }

    @Override
    protected boolean validateParms() throws IOException {
        // Insure the coreSEED directory exists.
        this.subsysDir = new File(this.coreDir, "Subsystems");
        if (! this.subsysDir.isDirectory())
            throw new FileNotFoundException("Specified coreSEED directory " + this.coreDir + " is missing or has no subsystems.");
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Get the list of subsystems.
        File[] subsysFiles = this.subsysDir.listFiles(new SubsystemFilter());
        log.info("{} subsystems found in {}.", subsysFiles.length, this.coreDir);
        // Write the output header.
        System.out.println("role_name\tsubsystem\tcurator\tprivate");
        // Loop through the subsystems.
        for (File subsysFile : subsysFiles) {
            SubsystemData subsystem = SubsystemData.load(this.coreDir, subsysFile.getName());
            if (subsystem == null)
                log.warn("Subsystem {} not found.", subsysFile.getName());
            else {
                String curator = subsystem.getCurator();
                String flag = (subsystem.isPrivate() ? "Y" : "");
                log.info("Processing subsystem: {}.", subsystem.getName());
                for (ColumnData col : subsystem.getColumns())
                    System.out.format("%s\t%s\t%s\t%s%n", col.getFunction(), subsystem.getName(), curator, flag);
            }
        }
    }
}
