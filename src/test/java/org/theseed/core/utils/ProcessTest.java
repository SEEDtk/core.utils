package org.theseed.core.utils;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Unit test for simple App.
 */
public class ProcessTest
    extends TestCase
{
    /**
     * Create the test case
     *
     * @param testName name of the test case
     */
    public ProcessTest( String testName )
    {
        super( testName );
    }

    /**
     * @return the suite of tests being tested
     */
    public static Test suite()
    {
        return new TestSuite( ProcessTest.class );
    }

    /**
     * Check out path stuff.
     */
    public void testPath()
    {
        String path = System.getenv("PATH");
        String sep = System.getProperty("path.separator");
        assertThat(path, containsString(sep));
    }

}
