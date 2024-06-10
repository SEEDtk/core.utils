/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.ParseFailureException;
import org.theseed.io.TabbedLineReader;
import org.theseed.subsystems.core.CoreSubsystem;
import org.theseed.utils.BasePipeProcessor;

/**
 * This command will scan a list of subsystem names and output the ones that do not exist in the
 * CoreSEED.  The positional parameter is the CoreSEED directory name.  The subsystem list should
 * be on the standard input in a tab-delimited file with headers.  The missing names will be output
 * to the standard output.
 *
 * The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -i	input file name (if not STDIN)
 * -o	output file name (if not STDOUT)
 *
 * @author Bruce Parrello
 *
 */
public class SubsystemListCheckProcessor extends BasePipeProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(SubsystemListCheckProcessor.class);
    /** list of subsystem directories */
    private List<File> subDirs;

    // COMMAND-LINE OPTIONS

    /** CoreSEED data directory */
    @Argument(index = 0, metaVar = "FIGdisk/FIG/Data", usage = "CoreSEED data directory")
    private File coreDir;

    @Override
    protected void setPipeDefaults() {
    }

    @Override
    protected void validatePipeInput(TabbedLineReader inputStream) throws IOException {
    }

    @Override
    protected void validatePipeParms() throws IOException, ParseFailureException {
        // Insure the CoreSEED directory exists.
        if (! this.coreDir.isDirectory())
            throw new FileNotFoundException("CoreSEED directory " + this.coreDir + " is not found or invalid.");
        // Get the subsystem directories.
        this.subDirs = CoreSubsystem.getSubsystemDirectories(this.coreDir);
        log.info("{} subsystem directories found in {}.", this.subDirs.size(), this.coreDir);
    }

    @Override
    protected void runPipeline(TabbedLineReader inputStream, PrintWriter writer) throws Exception {
        // Create a set of valid subsystem names.
        log.info("Scanning subsystem names.");
        Set<String> subNames = this.subDirs.stream().map(x -> CoreSubsystem.dirToName(x))
                .collect(Collectors.toSet());
        log.info("{} subsystem names collected.", subNames.size());
        // This will count the missing names.
        int notFound = 0;
        // Write the headers to the output file.
        writer.println(inputStream.header());
        // Loop through the input.
        for (var line : inputStream) {
            String inName = line.get(0);
            // If the name ends with a space, change it to an underscore to match the conversion process.
            if (inName.endsWith(" "))
                inName = StringUtils.removeEnd(inName, " ") + "_";
            if (! subNames.contains(inName)) {
                notFound++;
                writer.println(line.toString());
            }
        }
        log.info("{} input names were missing.", notFound);
    }

}
