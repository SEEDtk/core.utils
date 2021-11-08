/**
 *
 */
package org.theseed.utils;

import java.util.Arrays;
import java.util.Comparator;

import org.apache.commons.lang3.StringUtils;
import org.theseed.reports.NaturalSort;

/**
 * A version number has multiple strings separated by periods.  The strings are compared in sequence, using natural ordering.
 *
 * @author Bruce Parrello
 *
 */
public class VersionNumber implements Comparable<VersionNumber> {

    // FIELDS
    /** segments of the version number */
    private String[] parts;
    /** comparator for segments */
    private static final NaturalSort SORTER = new NaturalSort();

    /**
     * Reverse sort for version numbers.
     */
    public static class Reverse implements Comparator<VersionNumber> {

        @Override
        public int compare(VersionNumber o1, VersionNumber o2) {
            return -o1.compareTo(o2);
        }

    }

    /**
     * Create a version number
     *
     * @param string	version number string
     */
    public VersionNumber(String string) {
        this.parts = StringUtils.split(string, '.');
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + Arrays.hashCode(this.parts);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof VersionNumber)) {
            return false;
        }
        VersionNumber other = (VersionNumber) obj;
        if (!Arrays.equals(this.parts, other.parts)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return StringUtils.join(this.parts, ".");
    }

    @Override
    public int compareTo(VersionNumber o) {
        // This is the number of parts in common.
        int n;
        // This will be the return value if all the common parts match.
        int residual;
        if (o.parts.length < this.parts.length) {
            n = o.parts.length;
            residual = 1;
        } else if (o.parts.length > this.parts.length) {
            n = this.parts.length;
            residual = -1;
        } else {
            n = this.parts.length;
            residual = 0;
        }
        int retVal = 0;
        for (int i = 0; retVal == 0 && i < n; i++)
            retVal = SORTER.compare(this.parts[i], o.parts[i]);
        if (retVal == 0)
            retVal = residual;
        return retVal;
    }



}
