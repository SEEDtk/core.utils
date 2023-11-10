/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.theseed.p3api.Criterion;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.ParseFailureException;
import org.theseed.genome.Contig;
import org.theseed.genome.Genome;
import org.theseed.genome.iterator.GenomeSource;
import org.theseed.p3api.P3Connection;
import org.theseed.p3api.P3Genome;
import org.theseed.sequence.MD5Hex;
import org.theseed.utils.BaseReportProcessor;

import com.github.cliftonlabs.json_simple.JsonObject;

/**
 * This command accepts as input a genome source and searches PATRIC for genomes with identical sequences.  The most
 * common usage is to specify CoreSEED as the genome source.
 *
 * For a given input genome, the basic algorithm is to extract the genome IDs of all PATRIC genomes with the same
 * taxonomic ID, the same genome length, and the same number of contigs.  The MD5s of the PATRIC genome contigs
 * are then extracted and compared with the computed MD5s of the source genome's contigs.  Alas, while the sequence
 * MD5 is an actual field in PATRIC, the values are missing in most of the records, so we have to download the
 * whole contig and compute it.  For this reason, if there is a PATRIC genome with the same ID, we pass it as
 * identical automatically.
 *
 * The positional parameter is the name of a genome source.  The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -o	output file for report (if not STDOUT)
 *
 * --source		type of genome source (DIR, MASTER, PATRIC, CORE); default is DIR
 *
 * @author Bruce Parrello
 *
 */
public class P3MapProcessor extends BaseReportProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(P3MapProcessor.class);
    /** input genome source */
    private GenomeSource genomes;
    /** MD5 engine */
    private MD5Hex md5Engine;
    /** PATRIC connection */
    private P3Connection p3;

    // COMMAND-LINE OPTIONS

    /** type of input */
    @Option(name = "--source", usage = "type of genome input")
    private GenomeSource.Type inType;

    /** input directory name */
    @Argument(index = 0, metaVar = "inDir", usage = "genome input directory name")
    private File inDir;

    @Override
    protected void setReporterDefaults() {
        this.inType = GenomeSource.Type.DIR;
    }

    @Override
    protected void validateReporterParms() throws IOException, ParseFailureException {
        // Verify the input source.
        if (! this.inDir.exists())
            throw new FileNotFoundException("Input genome source " + this.inDir + " is not found.");
        log.info("Connecting to genome source in {}.", this.inDir);
        this.genomes = this.inType.create(inDir);
        log.info("{} input genomes to process.", this.genomes.size());
        // Insure we can access PATRIC.
        log.info("Connecting to PATRIC.");
        this.p3 = new P3Connection();
    }

    @Override
    protected void runReporter(PrintWriter writer) throws Exception {
        // Write the report header.
        writer.println("genome\tgenome_name\ttaxon\tlength\tbv_brc_id\tbv_brc_name");
        // We need some counters.
        int sourceCount = 0;
        int candidateCount = 0;
        int noMatchCount = 0;
        int exactCount = 0;
        // Get an MD5 computer.
        log.info("Initializing MD5 engine.");
        this.md5Engine = new MD5Hex();
        // Now loop through the source genomes, processing each one.
        for (Genome genome : this.genomes) {
            sourceCount++;
            log.info("Processing {} of {}: {}.", sourceCount, this.genomes.size(), genome);
            // We need to know the genome ID, the taxonomic ID, the contig count, and the total genome length.
            String genomeId = genome.getId();
            int taxId = genome.getTaxonomyId();
            int length = genome.getLength();
            int contigCount = genome.getContigCount();
            // Find all genomes in PATRIC with the same tax ID, length, and contig count.  The genome must also be
            // public.  These criteria are generally stringent enough to return only one genome, but disasters do
            // happen.
            var p3GenomeData = this.p3.query(P3Connection.Table.GENOME, "genome_id,genome_name",
                    Criterion.EQ("taxon_id", Integer.toString(taxId)),
                    Criterion.EQ("genome_length", Integer.toString(length)),
                    Criterion.EQ("contigs", Integer.toString(contigCount)),
                    Criterion.EQ("public", "1"));
            final int candTotal = p3GenomeData.size();
            log.info("{} candidate genomes found for {}.", candTotal, genomeId);
            // These will be used to store the found genome's ID and name.
            String foundId = "";
            String foundName = "";
            if (p3GenomeData.size() == 0) {
                // Here there was no match.
                noMatchCount++;
            } else {
                // We will stash the genome found in here.
                JsonObject found = null;
                // Look for a trivial case.
                Optional<JsonObject> exactMatch = p3GenomeData.stream().filter(x -> P3Connection.getString(x, "genome_id").contentEquals(genomeId))
                        .findFirst();
                if (exactMatch.isPresent()) {
                    found = exactMatch.get();
                    exactCount++;
                } else {
                    // We need MD5s for the contigs in the source genome.
                    Set<String> genomeMD5s = this.hashContigs(genome);
                    // Loop through the genomes returned until we find a match.
                    int candCount = 0;
                    var p3iter = p3GenomeData.iterator();
                    while (p3iter.hasNext() && found == null) {
                        candidateCount++;
                        var p3GenomeDatum = p3iter.next();
                        String candidateId = P3Connection.getString(p3GenomeDatum, "genome_id");
                        candCount++;
                        log.info("Checking candidate #{} of {} ({}) against {}.", candCount, candTotal, candidateId, genome);
                        // Note we only need the contigs here, not any structure data.
                        P3Genome candidateGenome = P3Genome.load(this.p3, candidateId, P3Genome.Details.CONTIGS);
                        Set<String> candidateMD5s = this.hashContigs(candidateGenome);
                        if (genomeMD5s.equals(candidateMD5s))
                            found = p3GenomeDatum;
                    }
                }
                // If we found a match, save it!
                if (found != null) {
                    foundId = P3Connection.getString(found, "genome_id");
                    foundName = P3Connection.getString(found, "genome_name");
                } else
                    noMatchCount++;
            }
            writer.format("%s\t%s\t%d\t%d\t%s\t%s%n", genomeId, genome.getName(), taxId, length, foundId, foundName);
        }
        log.info("{} genomes processed.  {} candidates.  {} genomes unmatched.  {} exact matches.",
                sourceCount, candidateCount, noMatchCount, exactCount);
    }

    /**
     * Compute the set of MD5 hashes for the contigs in this genome.
     *
     * @param genome	genome to process
     *
     * @return a set of the MD5 hex checksums for the contigs
     *
     * @throws UnsupportedEncodingException
     */
    private Set<String> hashContigs(Genome genome) throws UnsupportedEncodingException {
        Set<String> retVal = new HashSet<String>(genome.getContigCount() * 4 / 3 + 1);
        // Loop through the contigs, computing sequence MD5s.
        for (Contig contig : genome.getContigs()) {
            // We have to do a dance here because of reverse complements.  We hash the lexically lower of the
            // sequence and its reverse.
            String seq = contig.getSequence();
            String rev = contig.getRSequence();
            if (rev.compareTo(seq) < 0)
                seq = rev;
            String md5 = this.md5Engine.sequenceMD5(seq);
            retVal.add(md5);
        }
        return retVal;
    }

}
