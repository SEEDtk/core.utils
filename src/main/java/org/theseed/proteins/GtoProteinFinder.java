/**
 *
 */
package org.theseed.proteins;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import org.theseed.genome.Feature;
import org.theseed.genome.Genome;
import org.theseed.genome.GenomeDirectory;

/**
 * This class processes proteins found in a directory of GTOs.  The GTOs are loaded individually, and
 * the features with proteins and functions are extracted.  Each protein is converted to an MD5 and
 * the information about the feature passed back.
 *
 * @author Bruce Parrello
 */
public class GtoProteinFinder extends ProteinFinder {

    // FIELDS
    /** genome directory iterator */
    private Iterator<Genome> genomeIter;
    /** feature iterator */
    private Iterator<Feature> pegIter;
    /** GTO directory */
    private File gtoDir;

    public GtoProteinFinder(Parms processor) {
        super(processor);
        // Get the GTO directory.
        this.gtoDir = processor.getGtoDir();
        try {
            // Set up the iterators.
            GenomeDirectory genomes = new GenomeDirectory(this.gtoDir);
            this.genomeIter = genomes.iterator();
            this.pegIter = null;
            this.getNextFeature();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Position on the next feature.
     */
    @Override
    protected void getNextFeature() {
        this.nextInstance = null;
        // If we are in a genome, get the next feature in the genome.
        if (this.pegIter != null)
            this.searchGenome();
        // We are at the end of a genome, get the next genome.
        while (this.nextInstance == null && this.genomeIter.hasNext()) {
            // Get the peg iterator for this genome.
            Genome genome = genomeIter.next();
            Collection<Feature> pegs = genome.getPegs();
            log.info("{} pegs found in {}.", pegs.size(), genome);
            // Loop until we find a good feature.
            this.pegIter = pegs.iterator();
            this.searchGenome();
        }
    }

    /**
     * Try to find a good feature in this genome.
     */
    private void searchGenome() {
        this.nextInstance = null;
        // Loop through the remaining pegs, looking for a good one.
        while (this.nextInstance == null && this.pegIter.hasNext()) {
            Feature peg = this.pegIter.next();
            String function = peg.getFunction();
            String protein = peg.getProteinTranslation();
            if (function != null && ! function.isEmpty() && protein != null
                    && ! protein.isEmpty()) {
                // Here we have a good feature.  Store the instance and end the loop.
                String md5 = this.computeMd5(protein);
                this.nextInstance = new ProteinFinder.Instance(peg.getId(), md5, function);
            }
        }
    }

    @Override
    protected String getName() {
        return "genome directory " + this.gtoDir;
    }

}
