/**
 *
 */
package org.theseed.utils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.junit.Test;

/**
 * @author Bruce Parrello
 *
 */
public class TestVersioning {

    @Test
    public void test() {
        VersionNumber vnum1 = new VersionNumber("1.2.1");
        assertThat(vnum1.toString(), equalTo("1.2.1"));
        VersionNumber vnum2 = new VersionNumber("1.2");
        assertThat(vnum2.toString(), equalTo("1.2"));
        assertThat(vnum1.compareTo(vnum2), greaterThan(0));
        assertThat(vnum2.compareTo(vnum1), lessThan(0));
        VersionNumber vnum3 = new VersionNumber("1.2.1-beta7");
        VersionNumber vnum4 = new VersionNumber("1.2.1-M1.1");
        VersionNumber vnum5 = new VersionNumber("1.2.1-M1");
        assertThat(vnum3.compareTo(vnum1), greaterThan(0));
        assertThat(vnum4.compareTo(vnum3), greaterThan(0));
        assertThat(vnum5.compareTo(vnum4), lessThan(0));
    }

}
