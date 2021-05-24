/**
 *
 */
package org.theseed.reports;

import java.util.Comparator;

import org.theseed.genome.Coupling;
import org.theseed.genome.Feature;

/**
 * This object represents a line of data on a special coupling report.  It is sorted by strength followed by size and then
 * feature IDs.
 *
 * @author Bruce Parrello
 *
 */
public class CouplingDataLine implements Comparable<CouplingDataLine> {

    // FIELDS
    /** comparator for feature IDs */
    private static final Comparator<String> FID_SORTER = new NaturalSort();
    /** first feature ID */
    private String fid1;
    /** first function */
    private String function1;
    /** second feature ID */
    private String fid2;
    /** second function */
    private String function2;
    /** coupling size */
    private int size;
    /** coupling strength */
    private double strength;

    /**
     * Create a coupling data line from the features and the coupling descriptor.
     *
     * @param feat1		source feature
     * @param feat2		coupled feature
     * @param coupling	coupling descriptor
     */
    public CouplingDataLine(Feature feat1, Feature feat2, Coupling coupling) {
        this.fid1 = feat1.getId();
        this.function1 = feat1.getPegFunction();
        this.fid2 = feat2.getId();
        this.function2 = feat2.getPegFunction();
        this.size = coupling.getSize();
        this.strength = coupling.getStrength();
    }

    /**
     * @return the report header.
     */
    public static String getHeader() {
        return "source\tsource_function\tcoupled\tcoupled_function\tsize\tstrength";
    }

    @Override
    public int compareTo(CouplingDataLine o) {
        int retVal = Double.compare(o.strength, this.strength);
        if (retVal == 0) {
            retVal = o.size - this.size;
            if (retVal == 0) {
                retVal = FID_SORTER.compare(this.fid1, o.fid1);
                if (retVal == 0)
                    retVal = FID_SORTER.compare(this.fid2, o.fid2);
            }
        }
        return retVal;
    }

    /**
     * @return the report line for this coupling
     */
    public String output() {
        return String.format("%s\t%s\t%s\t%s\t%d\t%8.2f", this.fid1, this.function1, this.fid2, this.function2,
                this.size, this.strength);
    }

}
