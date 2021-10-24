/**
 * 
 */
package org.theseed.core.templates;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * This is the base class for a template command handler.  The bulk of the code in here is occupied with
 * parsing the parameter strings for the commands.  The syntax for these is very simple.  Each parameter
 * is of the form
 * 
 * 		keyword="value"
 * 
 * Inside the value, any character may be escaped by a backslash.  Key/value pairs are separated by
 * whitespace.  Thus,
 * 
 * 		name="MammalDir\\bears.txt" caption="Useful file about \"ursids\""
 * 
 * would parse to a "name" value of
 * 
 * 		MamalDir\bears.txt
 * 
 * and a "caption" value of
 * 
 * 		Useful file about "ursids"
 * 
 * @author Bruce Parrello
 *
 */
public abstract class TemplateHandler {
	
	/**
	 * Process a template command.
	 * 
	 * @param parentDir		input directory for templates
	 * @param outStream		output stream for template results
	 * @param parms			key/value map for command parameters
	 * 
	 * @return the HTML produced by the template
	 * 
	 * @throws IOException
	 */
	abstract public String process(File parentDir, Map<String, String> parms) throws IOException;
	
	/**
	 * @return the handler for a specific command
	 * 
	 * @param command	command specified in the template
	 * 
	 * @throws IOException
	 */
	public static final TemplateHandler getHandler(String command) throws IOException {
		TemplateHandler retVal;
		switch (command.toLowerCase()) {
		case "fid-table" :
			retVal = new FeatureTableCommandHandler();
			break;
		case "table-file" :
			retVal = new TableFileCommandHandler();
			break;
		case "header" :
			retVal = new HeaderCommandHandler();
			break;
		case "include" :
			retVal = new IncludeCommandHandler();
			break;
		default:
			throw new IOException("Invalid template command \"" + command + "\" encountered.");
		}
		return retVal;
		
	}
	
	/**
	 * Current parser status.
	 */
	private static class Parser {
		
		/** current token being built */
		protected StringBuilder token;
		/** current string being parsed */
		protected String source;
		/** position in the string */
		protected int pos;
		
		/**
		 * Construct a new parser.
		 * 
		 * @param newString		string to parse
		 */
		protected Parser(String newString) {
			this.source = newString;
			this.token = new StringBuilder(newString.length());
			this.pos = 0;
		}
		
		/**
		 * @return TRUE if there is more data to parse
		 */
		protected boolean isMore() {
			return (this.pos < this.source.length());
		}
		
		/**
		 * Position after whitespace.
		 */
		public void eatWhite() {
			while (this.pos < this.source.length() && Character.isWhitespace(this.source.charAt(this.pos)))
				this.pos++;
		}

		/**
		 * @return the next keyword in the stream and position after the equal sign
		 */
		public String getKeyword() throws IOException {
			this.token.setLength(0);
			boolean done = false;
			while (this.pos < this.source.length() && ! done) {
				char c = this.source.charAt(this.pos);
				switch (c) {
				case '=' :
					// We have found the end of the keyword.
					done = true;
					break;
				case ' ' :
					throw new IOException("Space found in keyword starting with \"" + 
							this.token.toString() + "\".");
				default :
					// Here we have a keyword character.
					this.token.append(c);
				}
				this.pos++;
			}
			// Verify we did not fall off the end.
			if (this.pos >= this.source.length())
				throw new IOException("Keyword \"" + this.token.toString() + "\" has no value.");
			return this.token.toString();
		}

		/**
		 * @return the next value in the stream and position after it
		 * 
		 * @param keyword	current keyword for error message purposes
		 */
		public String getValue(String keyword) throws IOException {
			if (this.source.charAt(this.pos) != '"')
				throw new IOException("Value of keyword \"" + keyword + "\" is unquoted.");
			this.pos++;
			this.token.setLength(0);
			boolean done = false;
			while (this.pos < this.source.length() && ! done) {
				char c = this.source.charAt(this.pos);
				switch (c) {
				case '\\' :
					this.pos++;
					if (this.pos < this.source.length())
						this.token.append(this.source.charAt(this.pos));
					break;
				case '"' :
					done = true;
					break;
				default :
					this.token.append(c);
				}
				this.pos++;
			}
			// Verify we did not fall off the end.
			if (! done)
				throw new IOException("Keyword \"" + keyword + "\" has an invalid value.");
			// Return the actual value.
			return this.token.toString();
		}
		
	}

	/**
	 * Parse the parameters from the specified parameter string.  At this point we are very simple,
	 * only allowing escapes for quotes.
	 * 
	 * @param parmString	parameter string to parse
	 * 
	 * @return a key/value map for the string
	 * 
	 * @throws IOException 
	 */
	public static Map<String, String> parse(String parmString) throws IOException {
		// We expect few parameters, so a tree map is better.
		Map<String, String> retVal = new TreeMap<String, String>();
		// Create the parser.
		Parser parser = new Parser(parmString);
		// Skip past the initial whitespace.
		parser.eatWhite();
		while (parser.isMore()) {
			// Get the keyword.
			String keyword = parser.getKeyword();
			// Get the quoted value.
			String value = parser.getValue(keyword);
			// Store the parameter.
			retVal.put(keyword, value);
			// Skip past whitespace.
			parser.eatWhite();
		}
		// Return the parameter map.
		return retVal;
	}

	/**
	 * Get an included file from the name parameter of a template command.  An IOException will
	 * be thrown if the file is not specified or cannot be read.  In addition, relative paths
	 * are computed relative to the template input directory.
	 * 
	 * @param parentDir		template input directory
	 * @param parms			parameter object
	 * @param command		command being processed
	 * 
	 * @return the name of the file to include
	 * 
	 * @throws IOException
	 */
	protected File getIncludeFile(File parentDir, Map<String, String> parms, String command)
			throws IOException {
		String fileName = parms.get("name");
		if (fileName == null)
			throw new IOException("No file name specified for " + command + " command.");
		File tableFile = new File(fileName);
		// Relative paths are with respect to the parent directory.
		if (! tableFile.isAbsolute())
			tableFile = new File(parentDir, fileName);
		if (! tableFile.canRead())
			throw new FileNotFoundException("Included file " + fileName + " is not found or unreadable.");
		return tableFile;
	}


}
