/**
 * 
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.core.templates.TemplateHandler;
import org.theseed.io.LineReader;
import org.theseed.utils.BaseProcessor;
import org.theseed.utils.ParseFailureException;

import java.util.Map;
import java.util.regex.Matcher;

/**
 * This command reads in HTML templates and outputs static web pages.  Several template commands
 * are supported
 * 
 * 		<!-- $table-file name="XXXXX" -->
 * 
 * where "XXXXX" is the name of a tab-delimited file with headers.  The tab-delimited file will be
 * converted into a table.
 * 
 *  	<!-- $fid-table name="XXXXX" -->
 *  
 * is the same as "table-file", but the first column is considered to contain PATRIC feature IDs.
 * 
 * 		<!-- $include name="XXXXX" -->
 * 
 * where "XXXXX" is the name of a text file containing HTML.  The file will be included with no
 * changes.
 * 
 * 		<!-- $header title="XXXXX XXXXX XXXX" -->
 * 
 * where "XXXXX XXXXX XXXX" is a page title.  The file "header.txt" will be included with the title
 * tag containing the specified title.
 * 
 * For best results, put the named files in the template directory and use
 * relative names.  Only files with the extension ".html" will be processed as templates.
 * 
 * All template commands must be on a single line by themselves.
 * 
 * The positional parameters are the names of the input directory containing the templates and the
 * name of the output directory.  The output web pages will be produced in the output directory with
 * the same names as the input templates.
 * 
 * The command-line options are as follows:
 * 
 * -h	display the command-line usage
 * -v	display more frequent log messages
 * 
 * @author Bruce Parrello
 *
 */
public class TablePageProcessor extends BaseProcessor {
	
	// FIELDS
	/** logging facility */
	protected static Logger log = LoggerFactory.getLogger(TablePageProcessor.class);
	/** list of template files */
	private File[] templateFiles;
	/** template command pattern */
	private static final Pattern COMMAND_PATTERN = Pattern.compile("\\s*<\\!--\\s+\\$(\\S+)\\s+(.+)\\s+-->\\s*");
	/** template file filter */
	private static final FileFilter TEMPLATE_FILTER = new TemplateFilter();
	
	// COMMAND-LINE OPTIONS
	
	/** input directory */
	@Argument(index = 0, metaVar = "inDir", usage = "input directory containing template files")
	private File inDir;
	
	/** output directory */
	@Argument(index = 1, metaVar = "outDir", usage = "output directory to contain static web pages")
	private File outDir;

	/**
	 * File filter class for template files.
	 */
	public static class TemplateFilter implements FileFilter {

		@Override
		public boolean accept(File pathname) {
			return (pathname.isFile() && pathname.getName().endsWith(".html"));
		}
		
	}
	
	@Override
	protected void setDefaults() {
	}

	@Override
	protected boolean validateParms() throws IOException, ParseFailureException {
		if (! this.inDir.isDirectory())
			throw new FileNotFoundException("Input directory " + this.inDir + " is not found or invalid.");
		if (! this.outDir.exists()) {
			log.info("Creating output directory {}.", this.outDir);
			FileUtils.forceMkdir(this.outDir);
		} else if (! this.outDir.isDirectory())
			throw new FileNotFoundException("Output directory " + this.outDir + " is not a directory.");
		else
			log.info("Output will be to {}.", this.outDir);
		// Get the list of template files from the input directory.
		this.templateFiles = this.inDir.listFiles(TEMPLATE_FILTER);
		log.info("{} template files found in {}.", this.templateFiles.length, this.inDir);
		return true;
	}

	@Override
	protected void runCommand() throws Exception {
		// Loop through the templates.
		for (File inFile : this.templateFiles) {
			File outFile = new File(this.outDir, inFile.getName());
			log.info("Converting input file {} to output file {}.", inFile, outFile);
			// These will be input and output line counts.
			int inCount = 0;
			int commandCount = 0;
			try (LineReader inStream = new LineReader(inFile);
					PrintWriter outStream = new PrintWriter(outFile)) {
				for (String inLine : inStream) {
					inCount++;
					Matcher m = COMMAND_PATTERN.matcher(inLine);
					if (! m.matches()) {
						// Here we have a static line.
						outStream.println(inLine);
					} else {
						commandCount++;
						// Get the command and the parameters.
						Map<String, String> parms = TemplateHandler.parse(m.group(2));
						TemplateHandler handler = TemplateHandler.getHandler(m.group(1));
						String content = handler.process(this.inDir, parms);
						outStream.println(content);
					}
				}
			}
			log.info("{} lines read, {} commands processed.", inCount, commandCount);
		}
	}
}
