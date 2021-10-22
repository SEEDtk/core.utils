/**
 * 
 */
package org.theseed.core.templates;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.theseed.io.TabbedLineReader;

import j2html.tags.ContainerTag;
import j2html.tags.DomContent;

import static j2html.TagCreator.*;

/**
 * This handler processes a table-file template command.  The only parameter is the name of the
 * input file.  The file is presumed to be tab-delimited, and is converted into an HTML table.
 * @author Bruce Parrello
 *
 */
public class TableFileCommandHandler extends TemplateHandler {
	
	@Override
	public String process(File parentDir, Map<String, String> parms) throws IOException {
		File tableFile = this.getIncludeFile(parentDir, parms, "table-file");
		// We will build the rows in here.
		List<ContainerTag> rows = new ArrayList<ContainerTag>(100);
		try (TabbedLineReader tableStream = new TabbedLineReader(tableFile)) {
			// Build the table header.
			List<DomContent> cols = Arrays.stream(tableStream.getLabels()).map(x -> th(x)).collect(Collectors.toList());
			rows.add(tr().with(cols));
			// Build the table rows.
			for (TabbedLineReader.Line line : tableStream) {
				cols = Arrays.stream(line.getFields()).map(x -> td(x)).collect(Collectors.toList());
				rows.add(tr().with(cols));
			}
		}
		// Assemble and return the table.
		ContainerTag retVal = table().with(rows);
		return retVal.render();
	}

}
