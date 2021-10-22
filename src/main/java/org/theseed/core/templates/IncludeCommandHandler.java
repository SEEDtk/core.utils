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
 * This file includes another file in the output.  The other file is specified by the "name"
 * parameter.
 * 
 * @author Bruce Parrello
 *
 */
public class IncludeCommandHandler extends TemplateHandler {

	@Override
	public String process(File parentDir, Map<String, String> parms) throws IOException {
		File includeFile = this.getIncludeFile(parentDir, parms, "include");
		List<String> lines = LineReader.readList(includeFile);
		String retVal = StringUtils.join(lines, "\n") + "\n";
		return retVal;
	}

}
