/**
 * 
 */
package org.theseed.core.templates;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.io.IOException;
import java.util.Map;

import org.junit.Test;

/**
 * @author Bruce Parrello
 *
 */
public class TemplateHandlerTest {

	@Test
	public void testParsing() throws IOException {
		String sample = "    name=\"MammalDir\\\\bears.txt\"   caption=\"Useful file about \\\"ursids\\\".\"    ";
		Map<String, String> parms = TemplateHandler.parse(sample);
		assertThat(parms.size(), equalTo(2));
		assertThat(parms.get("frog"), nullValue());
		assertThat(parms.get("name"), equalTo("MammalDir\\bears.txt"));
		assertThat(parms.get("caption"), equalTo("Useful file about \"ursids\"."));
	}

}
