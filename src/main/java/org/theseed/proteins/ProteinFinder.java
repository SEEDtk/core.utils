/**
 *
 */
package org.theseed.proteins;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.sequence.MD5Hex;

/**
 * This is the base class for an object that iterates through protein features.  For each feature found, it
 * returns the protein MD5, the functional assignment, and the feature ID.
 *
 * @author Bruce Parrello
 *
 */
public abstract class ProteinFinder implements Iterator<ProteinFinder.Instance> {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ProteinFinder.class);
    /** MD5 object for computing protein IDs */
    private MD5Hex md5Computer;
    /** next feature */
    protected ProteinFinder.Instance nextInstance;

    /**
     * This is the return object from a protein finder.
     */
    public static class Instance {

        private String fid;
        private String md5;
        private String function;

        /**
         * Create a new protein feature instance.
         *
         * @param fid		feature ID
         * @param md5		protein MD5 identifier
         * @param function	functional assignment
         */
        protected Instance(String fid, String md5, String function) {
            this.fid = fid;
            this.md5 = md5;
            this.function = function;
        }

        /**
         * @return the feature ID
         */
        public String getFid() {
            return this.fid;
        }

        /**
         * @return the md5 protein identifier
         */
        public String getMd5() {
            return this.md5;
        }

        /**
         * @return the functional assignment
         */
        public String getFunction() {
            return this.function;
        }

    }


    /**
     * This is an interface that must be implemented by every processor object that uses protein finders.
     * It is used by the subclasses to get parameter information.
     */
    public static interface Parms {

        /**
         * @return the directory containing the input GTOs
         */
        public File getGtoDir();

        /**
         * @return the CoreSEED organism directory
         */
        public File getOrgDir();

    }


    /**
     * This is an iterable that allows a protein finder to be used in a for-each clause.
     */
    public class Container implements Iterable<Instance> {

        @Override
        public Iterator<ProteinFinder.Instance> iterator() {
            return ProteinFinder.this;
        }

    }


    /**
     * Construct a new protein finder.
     *
     * @param processor		client processor object
     */
    public ProteinFinder(Parms processor) {
        try {
            this.md5Computer = new MD5Hex();
            this.nextInstance = null;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @return the MD5 identifier of a protein sequence
     *
     * @param protein	protein whose identifier is to be computed
     */
    public String computeMd5(String protein) {
        try {
            return this.md5Computer.sequenceMD5(protein);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean hasNext() {
        return (this.nextInstance != null);
    }

    @Override
    public ProteinFinder.Instance next() {
        ProteinFinder.Instance retVal = this.nextInstance;
        if (retVal == null)
            throw new NoSuchElementException("No more features available in " + this.getName());
        this.getNextFeature();
        return retVal;
    }


    /**
     * @return the name of this finder
     */
    protected abstract String getName();

    /**
     * Position on the next available feature, updating the next-instance pointer.
     */
    protected abstract void getNextFeature();

    /**
     * Enumeration of protein finder types.
     */
    public static enum Type {
        GTO, CORE;

        public Container create(Parms processor) {
            ProteinFinder iter = null;
            switch (this) {
            case GTO :
                iter = new GtoProteinFinder(processor);
                break;
            case CORE :
                iter = new CoreProteinFinder(processor);
                break;
            }
            return iter.new Container();
        }
    }

}
