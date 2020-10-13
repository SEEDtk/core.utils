/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.genome.coupling.RoleCouplingMap;
import org.theseed.utils.BaseProcessor;

/**
 * This command compresses a role-coupling data file into a more compact form that can be used by the coupling web site.
 *
 * The old coupling file comes in on the standard input.  The positional parameter is the name to give to the compressed
 * file.  The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	show more detailed progress messages
 * -i	input file (if not the standard input)
 *
 * @author Bruce Parrello
 *
 */
public class CouplingCompressProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(CouplingCompressProcessor.class);
    /** role-coupling map being compressed */
    private RoleCouplingMap couplingMap;
    /** input stream */
    private InputStream inStream;

    // COMMAND-LINE OPTIONS

    /** input file (if not STDIN) */
    @Option(name = "-i", aliases = { "--input" }, metaVar = "couplings.tbl", usage = "role-coupling input file (if not standard input)")
    private File inFile;

    /** output file */
    @Argument(index = 0, metaVar = "couplings.out", usage = "binary couplings file")
    private File outFile;

    @Override
    protected void setDefaults() {
        this.inFile = null;
    }

    @Override
    protected boolean validateParms() throws IOException {
        // Handle the input.
        if (this.inFile == null) {
            log.info("Input will be read from the standard input.");
            this.inStream = System.in;
        } else if (! this.inFile.canRead())
            throw new FileNotFoundException("Input file " + this.inFile + " not found or unreadable.");
        else {
            log.info("Input will be from the file {}.", this.inFile);
            this.inStream = new FileInputStream(this.inFile);
        }
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Read in the map.
        log.info("Reading coupling map.");
        this.couplingMap = new RoleCouplingMap(this.inStream);
        // Close the input if it's a file.
        if (this.inFile != null)
            this.inStream.close();
        // Write the map out.
        log.info("Writing coupling map to {}.", this.outFile);
        this.couplingMap.save(this.outFile);
    }

}
