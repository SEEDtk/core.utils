/**
 *
 */
package org.theseed.proteins;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.theseed.genome.core.OrganismDirectories;
import org.theseed.io.MarkerFile;
import org.theseed.sequence.FastaInputStream;
import org.theseed.sequence.Sequence;
import org.theseed.subsystems.RowData;

/**
 * This protein finder returns all of the protein sequences in a CoreSEED installation.
 *
 * @author Bruce Parrello
 *
 */
public class CoreProteinFinder extends ProteinFinder {

    // FIELDS
    /** coreseed organism directory */
    private File orgDir;
    /** organism directories iterator */
    private Iterator<String> genomeIter;
    /** current genome function assignments */
    private Map<String, String> genomePegs;
    /** iterator through the current genome's pegs */
    private Iterator<Sequence> pegIter;
    /** set used to filter only for pegs */
    private Set<String> PEG_SET = Collections.singleton("peg");
    /** pattern for prokaryotic taxonomies */
    private Pattern PROKARYOTIC = Pattern.compile("\\s*(?:Bacteria|Archaea);.+");

    public CoreProteinFinder(Parms processor) {
        super(processor);
        // Get the coreSEED organism directory.
        this.orgDir = processor.getOrgDir();
        // Create the genome ID iterator.
        OrganismDirectories genomes = new OrganismDirectories(orgDir);
        this.genomeIter = genomes.iterator();
        // Position on the first protein instance.
        this.pegIter = null;
        this.getNextFeature();
    }

    /**
     * Position on the next feature.
     */
    @Override
    protected void getNextFeature() {
        try {
            this.nextInstance = null;
            if (this.pegIter != null && this.pegIter.hasNext())
                this.searchGenome();
            if (this.nextInstance == null) {
                // Here we need a new genome.
                while (this.nextInstance == null && this.genomeIter.hasNext()) {
                    String genomeId = this.genomeIter.next();
                    log.info("Processing genome {}.", genomeId);
                    // Verify that the genome is prokaryotic.
                    File genomeDir = new File(orgDir, genomeId);
                    String taxonomy = MarkerFile.read(new File(genomeDir, "TAXONOMY"));
                    Matcher m = PROKARYOTIC.matcher(taxonomy);
                    if (m.matches()) {
                        // Here the genome is good, so we read its assignments.
                        this.genomePegs = RowData.readFunctions(genomeDir, genomeId, PEG_SET);
                        log.info("{} pegs with functions.", this.genomePegs.size());
                        // Read in the sequences and get a peg iterator.
                        File pegFile = new File(genomeDir, "Features/peg/fasta");
                        if (pegFile.exists()) {
                            List<Sequence> proteins = FastaInputStream.readAll(pegFile);
                            this.pegIter = proteins.iterator();
                            // Get the first protein instance in this genome.
                            this.searchGenome();
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Find the next feature in the current genome.
     */
    private void searchGenome() {
        this.nextInstance = null;
        while (this.nextInstance == null && this.pegIter.hasNext()) {
            Sequence protein = this.pegIter.next();
            String fid = protein.getLabel();
            String function = this.genomePegs.get(fid);
            if (function != null) {
                String md5 = this.computeMd5(protein.getSequence());
                this.nextInstance = new ProteinFinder.Instance(fid, md5, function);
            }
        }
    }

    @Override
    protected String getName() {
        return "CoreSEED organism directory " + this.orgDir;
    }

}
