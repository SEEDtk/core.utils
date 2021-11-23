/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.genome.Coupling;
import org.theseed.genome.Feature;
import org.theseed.genome.FeatureCategory;
import org.theseed.genome.Genome;
import org.theseed.genome.iterator.GenomeSource;
import org.theseed.proteins.Role;
import org.theseed.proteins.RoleMap;
import org.theseed.reports.SpecialCouplingReporter;
import org.theseed.utils.BaseReportProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This command looks for couplings in a genome source that satisfy certain characteristics.  The genomes should include
 * coupling and subsystem data.
 *
 * The positional parameter is the name of the input genome source.  The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -o	output file for report (if not STDOUT)
 * -t	type of input genome source (PATRIC ID file, directory, master directory)
 *
 * --cat1		category of first feature (default HYPOTHETICAL)
 * --cat2		category of coupled feature (default IN_SUBSYSTEM)
 * --format		format of output report
 * --exclude	role definition file for roles to exclude from the coupled features
 *
 * @author Bruce Parrello
 *
 */
public class CouplingSearchProcessor extends BaseReportProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(CouplingSearchProcessor.class);
    /** input genome source */
    private GenomeSource genomes;
    /** output report formatter */
    private SpecialCouplingReporter reporter;
    /** role exclusion map */
    private RoleMap exclusions;

    // COMMAND-LINE OPTIONS

    /** type of input source */
    @Option(name = "--type", aliases = { "-t", "--source" }, usage = "genome input source type")
    private GenomeSource.Type sourceType;

    /** category of target feature */
    @Option(name = "--cat1", usage = "category of feature to select for focus")
    private FeatureCategory featCategory1;

    /** category of coupled feature */
    @Option(name = "--cat2", usage = "category of feature to select for coupling")
    private FeatureCategory featCategory2;

    /** format of output report */
    @Option(name = "--format", usage = "output report format")
    private SpecialCouplingReporter.Type outFormat;

    /** definition file for roles to exclude */
    @Option(name = "--exclude", metaVar = "roles.to.exclude", usage = "definition file for roles to exclude (if any)")
    private File exclusionFile;

    /** input genome source */
    @Argument(index = 0, metaVar = "genomeDir", usage = "input genome source")
    private File genomeDir;

    @Override
    protected void setReporterDefaults() {
        this.sourceType = GenomeSource.Type.DIR;
        this.featCategory1 = FeatureCategory.HYPOTHETICAL;
        this.featCategory2 = FeatureCategory.IN_SUBSYSTEM;
        this.outFormat = SpecialCouplingReporter.Type.LIST;
        this.exclusionFile = null;
    }

    @Override
    protected void validateReporterParms() throws IOException, ParseFailureException {
        // Verify the exclusions.
        if (this.exclusionFile == null) {
            this.exclusions = new RoleMap();
            log.info("No roles will be excluded.");
        } else if (! this.exclusionFile.canRead())
            throw new FileNotFoundException("Exclusion file " + this.exclusionFile + " is not found or unreadable.");
        else {
            this.exclusions = RoleMap.load(this.exclusionFile);
            log.info("{} roles marked for exclusion.", this.exclusions.size());
        }
        // Verify the genome source.
        if (! this.genomeDir.exists())
            throw new FileNotFoundException("Input genome source " + this.genomeDir + " not found.");
        this.genomes = this.sourceType.create(this.genomeDir);
        log.info("{} genomes found in {}.", this.genomes.size(), this.genomeDir);
    }

    @Override
    protected void runReporter(PrintWriter writer) throws Exception {
        // Initialize the report.
        this.reporter = this.outFormat.create(writer);
        this.reporter.openReport();
        // We want to count the number of genomes processed, the number of qualified features examined, the number of
        // couplings found, and the number excluded.
        int gCount = 0;
        int fCount = 0;
        int cCount = 0;
        int xCount = 0;
        // Loop through the genomes.
        for (Genome genome : this.genomes) {
            gCount++;
            log.info("Processing genome {} of {}: {}.  {} couplings of interest found.", gCount, this.genomes.size(), genome, cCount);
            // Loop through the pegs.
            for (Feature feat : genome.getPegs()) {
                if (this.featCategory1.qualifies(feat)) {
                    // Here the feature is interesting to us.
                    fCount++;
                    // Check the feature's couplings.
                    for (Coupling coupling : feat.getCouplings()) {
                        Feature feat2 = genome.getFeature(coupling.getTarget());
                        if (this.featCategory2.qualifies(feat2)) {
                            // Here we have a coupling of interest.  Check for exclusion.
                            List<Role> excluded = feat2.getUsefulRoles(this.exclusions);
                            if (excluded.size() > 0)
                                xCount++;
                            else {
                                cCount++;
                                this.reporter.processCoupling(feat, feat2, coupling);
                            }
                        }
                    }
                }
            }
        }
        // Finish the report.
        log.info("{} couplings found for {} features of interest in {} genomes.  {} excluded.", cCount, fCount, gCount, xCount);
        this.reporter.finish();
    }

 }
