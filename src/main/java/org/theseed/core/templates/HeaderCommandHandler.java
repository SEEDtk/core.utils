/**
 * 
 */
package org.theseed.core.templates;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.theseed.io.LineReader;

/**
 * This command reads the file "header.txt" in the input directory to get the standard header for
 * each output page.  It will replace the string "$title" with the value of the "title" parameter.
 * 
 * @author Bruce Parrello
 *
 */
public class HeaderCommandHandler extends TemplateHandler {

	@Override
	public String process(File parentDir, Map<String, String> parms) throws IOException {
		// Read in the header file.
		File inFile = new File(parentDir, "header.txt");
		List<String> lines = LineReader.readList(inFile);
		// Get the title parameter.
		String newTitle = parms.get("title");
		if (newTitle == null)
			throw new IOException("No title specified for header command.");
		// Search for $title and replace it.  We only do this once.
		boolean done = false;
		for (int i = 0; ! done && i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.contains("$title")) {
				lines.set(i, line.replace("$title", newTitle));
				done = true;
			}
		}
		return StringUtils.join(lines, "\n") + "\n";
	}

}
