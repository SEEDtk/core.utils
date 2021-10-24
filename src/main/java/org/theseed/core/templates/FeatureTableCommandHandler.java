/**
 * 
 */
package org.theseed.core.templates;

import static j2html.TagCreator.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.theseed.io.TabbedLineReader;
import org.theseed.reports.LinkObject;

import j2html.tags.ContainerTag;
import j2html.tags.DomContent;

/**
 * This handler processes a table-file with PATRIC feature IDs in the first column.
 * The feature IDs are converted into hyperlinks.
 * 
 * @author Bruce Parrello
 *
 */
public class FeatureTableCommandHandler extends TemplateHandler {

	@Override
	public String process(File parentDir, Map<String, String> parms) throws IOException {
		File tableFile = this.getIncludeFile(parentDir, parms, "table-file");
		LinkObject linker = new LinkObject.Patric();
		// We will build the rows in here.
		List<ContainerTag> rows = new ArrayList<ContainerTag>(100);
		try (TabbedLineReader tableStream = new TabbedLineReader(tableFile)) {
			// Build the table header.
			List<DomContent> cols = Arrays.stream(tableStream.getLabels()).map(x -> th(x)).collect(Collectors.toList());
			rows.add(tr().with(cols));
			int n = tableStream.size();
			// Build the table rows.
			for (TabbedLineReader.Line line : tableStream) {
				cols.clear();
				// Store the feature link.
				DomContent plink = linker.featureLink(line.get(0));
				cols.add(td(plink));
				// Store the other columns.
				for (int i = 1; i < n; i++)
					cols.add(td(line.get(i)));
				// Form the output row.
				rows.add(tr().with(cols));
			}
		}
		// Assemble and return the table.
		ContainerTag retVal = table().with(rows);
		return retVal.render();
	}

}
