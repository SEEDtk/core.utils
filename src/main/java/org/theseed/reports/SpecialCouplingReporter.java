/**
 *
 */
package org.theseed.reports;

import java.io.PrintWriter;

import org.theseed.genome.Coupling;
import org.theseed.genome.Feature;

/**
 * This is the base class for reports about coupled features.
 *
 * @author Bruce Parrello
 *
 */
public abstract class SpecialCouplingReporter extends BaseWritingReporter {

    public static enum Type {
        LIST {
            @Override
            public SpecialCouplingReporter create(PrintWriter writer) {
                return new ListSpecialCouplingReporter(writer);
            }
        };

        public abstract SpecialCouplingReporter create(PrintWriter writer);
    }

    /**
     * Create a special coupling report.
     *
     * @param writer	output writer to receive the report
     */
    public SpecialCouplingReporter(PrintWriter writer) {
        super(writer);
    }

    /**
     * Initialize the report.
     */
    public abstract void openReport();

    /**
     * Record a coupling.
     *
     * @param feat1		target feature
     * @param feat		coupled feature
     * @param coupling	coupling describing the size and strength
     */
    public abstract void processCoupling(Feature feat1, Feature feat2, Coupling coupling);

    /**
     * Finish the report.
     */
    public abstract void finish();

}
