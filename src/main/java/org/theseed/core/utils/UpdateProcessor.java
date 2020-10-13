/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.genome.coupling.CouplesProcessor;
import org.theseed.genome.coupling.PrepareProcessor;
import org.theseed.genome.download.CoreProcessor;
import org.theseed.genome.download.SeedProcessor;
import org.theseed.genome.download.SubsystemProcessor;
import org.theseed.utils.BaseProcessor;
import org.theseed.web.subsystems.SurveyProcessor;

/**
 * This command performs the weekly processing to keep the CoreSEED data structures up-to-date.  It will
 * build the GTOs, project the subsystems, generate the couplings, and create an abridged CoreSEED
 * directory for copying to Windows.
 *
 * The positional parameters are the name of the the name of the CoreSEED data directory,
 * the name of the RepGen.200 GTO directory, and the name of the output
 * coupling directory.  This last directory will also receive the genomes.tbl file used for
 * the coupling web site.
 *
 * This program requires a working directory for global output files.  The default is the
 * current directory.
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	show more detailed progress messages
 *
 * --global		name of the global output directory
 *
 */
public class UpdateProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(UpdateProcessor.class);

    // COMMAND-LINE OPTIONS

    /** working directory */
    @Option(name = "--global", metaVar = "globalDir", usage = "output directory for global files")
    private File globalDir;

    /** input CoreSEED data directory */
    @Argument(index = 0, metaVar = "coreDir", usage = "name of CoreSEED data directory", required = true)
    private File coreDir;

    /** RepGen.200 GTO directory */
    @Argument(index = 1, metaVar = "Rep200/GTOs", usage = "name of RepGen.200 GTO directory", required = true)
    private File repGenDir;

    /** output coupling GTO directory */
    @Argument(index = 2, metaVar = "outDir", usage = "coupling output directory (will be erased and rebuilt", required = true)
    private File outDir;

    @Override
    protected void setDefaults() {
        this.globalDir = new File(System.getProperty("user.dir"));
    }

    @Override
    protected boolean validateParms() throws IOException {
        // Set up the work directory.
        if (! this.globalDir.isDirectory())
            throw new FileNotFoundException("Global output directory " + this.globalDir + " not found or invalid.");
        // Insure the repgen directory exists.
        if (! this.repGenDir.isDirectory())
            throw new FileNotFoundException("RepGen directory " + this.repGenDir + " not found or invalid.");
        // Verify the log configuration file and the core directory.
        if (! this.coreDir.isDirectory())
            throw new FileNotFoundException("CoreSEED directory " + this.coreDir + " not found or invalid.");
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // STEP 1: Create the CoreSEED GTOs.
        File orgDir = new File(this.coreDir, "Organisms");
        this.execute(new CoreProcessor(), null, "--clear", orgDir, this.outDir);
        // STEP 2: Process the subsystems.
        File subDir = new File(this.coreDir, "Subsystems");
        File variantDefinitions = new File(this.globalDir, "variants.tbl");
        this.execute(new SubsystemProcessor(), null, "--projector", variantDefinitions, this.outDir, subDir);
        // STEP 3: Generate the coupling table.
        File couplingTable = new File(this.globalDir, "couples.tbl");
        this.execute(new CouplesProcessor(), couplingTable, this.repGenDir);
        // STEP 4: Prepare the Core GTOs for the coupling website.
        this.execute(new PrepareProcessor(), null, couplingTable, this.outDir);
        // STEP 5: Validate the subsystems.
        File surveyReport = new File(this.globalDir, "survey.tbl");
        this.execute(new SurveyProcessor(), surveyReport, this.coreDir);
        // STEP 6: Create the portable CoreSEED
        File coreZip = new File(this.globalDir, "corerepo.zip");
        this.execute(new SeedProcessor(), coreZip, "--win", this.coreDir);
        // STEP 7: Update the evaluation data
        File evalIn = new File(this.coreDir, "Eval");
        File evalOut = new File(this.coreDir, "Eval.New");
        this.execute(new RolesProcessor(), null, "--clear", this.coreDir, this.outDir, evalIn, evalOut);
        // STEP 8: Generate the role couplings.
        File rCouplingTable = new File(this.coreDir, "roles.couplings.tbl");
        this.execute(new CouplesProcessor(), rCouplingTable, this.outDir);
        // STEP 9: Compress the role couplings.
        File rCouplingBin = new File(this.coreDir, "roles.coupling.ser");
        this.execute(new CouplingCompressProcessor(), null, "-i", rCouplingTable.getAbsolutePath(), rCouplingBin.getAbsolutePath());
        // All done.
        log.info("All done.");
    }

    /**
     * Execute an application.
     *
     * @param processor		processor for the application to execute
     * @param stdOut		file to receive standard output, or NULL to discard the standard output
     * @param parms			array of parameters to pass (File or String)
     *
     * @throws IOException
     */
    private void execute(BaseProcessor processor, File stdOut, Object... parms) throws IOException {
        String commandName = processor.getClass().getName();
        // Convert the files to absolute paths.
        String[] actual = new String[parms.length];
        for (int i = 0; i < actual.length; i++) {
            if (parms[i] instanceof String)
                actual[i] = (String) parms[i];
            else if (parms[i] instanceof File)
                actual[i] = ((File) parms[i]).getAbsolutePath();
            else
                throw new IllegalArgumentException(String.format("Invalid object type in parameter %d for %s.", i, commandName));
        }
        log.info("Executing {} with parameters {}.", commandName, StringUtils.join(actual, ' '));
        // Set up the output stream.
        OutputStream outStream;
        if (stdOut == null)
            outStream = new NullOutputStream();
        else {
            log.info("Output will be to {}.", stdOut);
            outStream = new FileOutputStream(stdOut);
        }
        try (PrintStream stdOutStream = new PrintStream(outStream)){
            System.setOut(stdOutStream);
            // Initialize the processor.
            boolean ok = processor.parseCommand(actual);
            if (! ok)
                throw new IllegalArgumentException("Parameter error in " + commandName + ".");
            // Execute it.
            processor.run();
            // Insure we finish the output.
            stdOutStream.flush();
        } finally {
            outStream.close();
        }
    }


}
