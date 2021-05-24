/**
 *
 */
package org.theseed.reports;

import java.io.PrintWriter;
import java.util.SortedSet;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.genome.Coupling;
import org.theseed.genome.Feature;

/**
 * This is the basic coupling report that shows all the individual pairings found in order by strength.
 * We form the data into report lines and sort it using a tree set, then unspool it at the end.
 *
 * @author Bruce Parrello
 *
 */
public class ListSpecialCouplingReporter extends SpecialCouplingReporter {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ListSpecialCouplingReporter.class);
    /** output lines */
    private SortedSet<CouplingDataLine> reportLines;

    public ListSpecialCouplingReporter(PrintWriter writer) {
        super(writer);
    }

    @Override
    public void openReport() {
        this.reportLines = new TreeSet<CouplingDataLine>();
        this.println(CouplingDataLine.getHeader());
    }

    @Override
    public void processCoupling(Feature feat1, Feature feat2, Coupling coupling) {
        CouplingDataLine reportLine = new CouplingDataLine(feat1, feat2, coupling);
        this.reportLines.add(reportLine);
    }

    @Override
    public void finish() {
        // Unspool the data lines in order.
        for (CouplingDataLine reportLine : this.reportLines)
            this.println(reportLine.output());
    }

}
