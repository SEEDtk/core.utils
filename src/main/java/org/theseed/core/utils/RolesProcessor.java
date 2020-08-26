/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.counters.CountMap;
import org.theseed.genome.Feature;
import org.theseed.genome.Genome;
import org.theseed.genome.GenomeDirectory;
import org.theseed.io.LineReader;
import org.theseed.proteins.Role;
import org.theseed.proteins.RoleMap;
import org.theseed.subsystems.SubsystemData;
import org.theseed.subsystems.SubsystemFilter;
import org.theseed.utils.BaseProcessor;

/**
 * This command produces the files required for evaluation and role mapping.  First, it isolates the subsystem
 * roles from the CoreSEED and assigns them abbreviations.  The existing file (if any) can be used to insure
 * the abbreviations remain consistent.  Then the genomes in the GTOcouple directory are processed to produce
 * a table of feature-to-role mappings (training.tbl) and a table of useful roles (roles.to.use).
 *
 * The command reads files from an existing evaluation directory and then writes them to a new evaluation
 * directory.  The following files are processed.
 *
 * 	parms.prm				copied unchanged
 * 	roles.in.subsystems		read and rebuilt
 * 	training.tbl			rebuilt
 * 	labels.txt				rebuilt
 * 	roles.to.use			rebuilt
 * 	questionables.tbl		copied unchanged
 *
 * The questionables.tbl file contains genome IDs in the first column.  Genomes will only be processed into raw.table
 * if (1) their ID is not in questionables.tbl, (2) they have at least 300,000 base pairs in the contigs, and (3) they
 * are prokaryotic.  Roles will be put into roles.to.use only if (1) they occur in at least one active subsystem and
 * (2) they are found in 100 or more eligible genomes.  Some of these cutoffs are configurable.
 *
 * If a role in the subsystems has a new name that hashes to the same checksum as an existing role, the new name
 * will override it.  This insures we have the latest role text for each role ID.
 *
 * The positional parameters are the name of the CoreSEED directory, the name of the old evaluation directory, and the
 * name of the new evaluation directory.  The command-line options are as follows.
 *
 * -h		display command-line usage
 * -v		display more detailed progress messages
 * -t		recommended testing set size (default 120)
 *
 * --minSize	the minimum size of an acceptable genome, in base pairs; default 300,000
 * --minOccur	the minimum number of genomes that must contain a role for it to be useful; default 100
 * --maxMulti	the maximum number of times a role can occur in a genome for it to be useful; default 3
 * --domains	set of acceptable genome domains
 * --clear		erase the output directory before proceeding
 *
 * @author Bruce Parrello
 *
 */
public class RolesProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(RolesProcessor.class);
    /** main role map */
    private RoleMap roleMap;
    /** map of role IDs to genome counts */
    private CountMap<String> roleCounts;
    /** current set of IDs for potentially useful roles */
    private Set<String> usefulRoles;
    /** set of IDs of bad genomes */
    private Set<String> badGenomes;
    /** coreSEED subsystem directory */
    private File subsysDir;
    /** set of acceptable genome domains */
    private Set<String> domains;
    /** core genome directory */
    private GenomeDirectory genomes;
    /** map of role counts per genome */
    private Map<String, CountMap<String>> genomeRoleCounts;
    /** number of times each role occurs the specified number of times in a genome */
    private Map<String, int[]> roleFoundCounts;
    /** default list of acceptable genome domains */
    public static String DOMAINS = "Bacteria,B,Archaea,A";

    // COMMAND-LINE OPTIONS


    /** minimum size of an acceptable genome */
    @Option(name = "--minSize", metaVar = "400000", usage = "minimum size of an acceptable genome in base pairs")
    private int minSize;

    /** minimum number of genomes that must contain a role for it to be useful */
    @Option(name = "--minOccur", metaVar = "50", usage = "minimum number of genomes that must contain a role for it to be useful")
    private int minOccur;

    /** maximum number of role occurrences per genome for a role to be useful */
    @Option(name = "--maxMulti", metaVar = "5", usage = "maximum number of times a useful role can occur in a genome")
    private int maxMulti;

    /** comma-delimited list of acceptable genome domains */
    @Option(name = "--domains", metaVar = "Bacteria,Archaea", usage = "comma-delimited list of acceptable genome domains")
    private String domainString;

    /** if specified, the output directory will be erased before processing */
    @Option(name = "--clear", usage = "if specified, the output directory will be erased before processing")
    private boolean clearFlag;

    /** coreSEED data directory */
    @Argument(index = 0, metaVar = "CoreSEED", usage = "coreSEED data directory", required = true)
    private File coreDir;

    /** input directory */
    @Argument(index = 1, metaVar = "inDir", usage = "input evaluation directory", required = true)
    private File inDir;

    /** output directory */
    @Argument(index = 2, metaVar = "outDir", usage = "output evaluation directory", required = true)
    private File outDir;

    @Override
    protected void setDefaults() {
        this.minSize = 300000;
        this.minOccur = 100;
        this.maxMulti = 3;
        this.domainString = DOMAINS;
        this.clearFlag = false;
    }

    @Override
    protected boolean validateParms() throws IOException {
        // Validate the tuning numbers.
        if (this.maxMulti < 1)
            throw new IllegalArgumentException("Invalid value for --maxMulti.  Must be greater than 1.");
        if (this.minOccur < 0)
            throw new IllegalArgumentException("Invalid value for --minOccur. Must be 0 or more.");
        if (this.minSize < 0)
            throw new IllegalArgumentException("Invalid value for --minSize.  Must be 0 or more.");
        // Verify the input directory.
        if (! this.inDir.isDirectory())
            throw new FileNotFoundException("Input directory " + this.inDir + " not found or invalid.");
        log.info("Input will be taken from {}.", this.inDir);
        // Verify the output directory.
        if (! this.outDir.isDirectory()) {
            log.info("Creating output directory {}.", this.outDir);
            FileUtils.forceMkdir(this.outDir);
        } else if (this.clearFlag) {
            log.info("Erasing output directory {}.", this.outDir);
            FileUtils.cleanDirectory(this.outDir);
        } else {
            log.info("Writing to existing output directory {}.", this.outDir);
        }
        // Insure the coreSEED directory exists.
        if (! this.coreDir.isDirectory())
            throw new FileNotFoundException("CoreSEED directory " + this.coreDir + " not found or invalid.");
        // Verify it has a subsystem directory.
        this.subsysDir = new File(this.coreDir, "Subsystems");
        if (! this.subsysDir.isDirectory())
            throw new FileNotFoundException(this.coreDir + " subsystem directory not found or invalid.");
        // Get its genome directory.
        File genomeDir = new File(this.coreDir, "GTOCouple");
        if (! genomeDir.isDirectory())
            throw new FileNotFoundException(this.coreDir + " GTO directory not found or invalid.");
        this.genomes = new GenomeDirectory(genomeDir);
        log.info("{} GTOs found in directory.", this.genomes.size());
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Copy the blacklist and the parm file.
        File qFile = new File(this.inDir, "questionables.tbl");
        File pFile = new File(this.inDir, "parms.prm");
        FileUtils.copyFileToDirectory(qFile, this.outDir);
        FileUtils.copyFileToDirectory(pFile, this.outDir);
        // Read in the list of questionable genomes.
        this.badGenomes = LineReader.readSet(qFile);
        log.info("{} questionable genomes in list.", this.badGenomes.size());
        // Initialize the useful-role list.
        this.usefulRoles = new HashSet<String>(15000);
        // Initialize the good-domain set.
        this.domains = new HashSet<String>(Arrays.asList(StringUtils.split(this.domainString, ',')));
        // Create the label file.
        log.info("Creating label file in {}.", this.outDir);
        try (PrintWriter labelWriter = new PrintWriter(new File(this.outDir, "labels.txt"))) {
            for (int i = 0; i <= this.maxMulti; i++)
                labelWriter.format("%d%n", i);
            labelWriter.flush();
        }
        // Load the old role map.
        File oldRoles = new File(this.inDir, "roles.in.subsystems");
        if (oldRoles.exists()) {
            this.roleMap = RoleMap.load(oldRoles);
            log.info("{} roles and aliases loaded from old file {}.", this.roleMap.fullSize(), oldRoles);
        } else {
            this.roleMap = new RoleMap();
            log.info("New role map created.");
        }
        // Finally, initialize the role-genome counts.
        this.roleCounts = new CountMap<String>();
        this.genomeRoleCounts = new HashMap<String, CountMap<String>>(1000);
        this.roleFoundCounts = new HashMap<String, int[]>(10000);
        // Get the list of subsystems.
        File[] subDirs = this.subsysDir.listFiles(new SubsystemFilter());
        log.info("{} subsystem directories found.", subDirs.length);
        // Process each subsystem individually.
        for (File subDir : subDirs) {
            // Load this subsystem.
            String ssID = subDir.getName();
            SubsystemData subsystem = SubsystemData.load(this.coreDir, ssID);
            // Put the roles in the role map.
            String[] roleIDs = new String[subsystem.getWidth()];
            for (int i = 0; i < roleIDs.length; i++) {
                String function = subsystem.getRole(i);
                String[] roleNames = Feature.rolesOfFunction(function);
                for (String roleName : roleNames) {
                    // Only process non-hypotheticals.
                    if (! Feature.isHypothetical(roleName)) {
                        // Get this role's ID.
                        Role role = this.roleMap.findOrInsert(roleName);
                        // Insure the role name is up to date.
                        role.updateName(roleName);
                        // Mark it as provisionally useful.
                        this.usefulRoles.add(role.getId());
                    }
                }
            }
        }
        // Now write the role map.
        File outFile = new File(this.outDir, "roles.in.subsystems");
        log.info("Saving roles to {}. {} IDs computed for {} roles, of which {} are in the current subsystems.",
                outFile, this.roleMap.size(), this.roleMap.fullSize(), this.usefulRoles);
        this.roleMap.save(outFile);
        // The next step is to count the number of times each role occurs in a useful genome.
        log.info("Searching for roles in genomes.");
        for (Genome genome : genomes) {
            // Verify that this genome is valid.
            if (this.isWellBehaved(genome)) {
                log.info("Processing {}.", genome);
                // Count the roles and save the counts.  If a role count gets too high,
                // it will be deleted from the useful-roles set.
                CountMap<String> gRoleCounts = this.countRoles(genome);
                this.genomeRoleCounts.put(genome.getId(), gRoleCounts);
                // Update the useful roles in the occurrence-count matrix.
                for (String roleId : this.usefulRoles) {
                    int count = gRoleCounts.getCount(roleId);
                    // Note that if the role occurred too often, it will no longer be
                    // in the useful role set, so we don't need to check array index limits here.
                    int[] counters = this.roleFoundCounts.computeIfAbsent(roleId, r -> new int[this.maxMulti + 1]);
                    counters[count]++;
                }
                // Increment the genome-occurrence count for each role in this genome.
                int foundCount = 0;
                for (CountMap<String>.Count count : gRoleCounts.counts()) {
                    if (count.getCount() > 0) {
                        this.roleCounts.count(count.getKey());
                        foundCount++;
                    }
                }
                log.info("{} distinct roles found in {}.", foundCount, genome);
            }
        }
        log.info("{} potentially useful roles after genome scan.", this.usefulRoles.size());
        // Now we compute the final roles.to.use.
        SortedSet<String> finalRoles = this.usefulRoles.stream().filter(r -> this.roleCounts.getCount(r) >= this.minOccur).collect(Collectors.toCollection(TreeSet::new));
        log.info("{} useful roles in final set.", finalRoles.size());
        File rolesToUseFile = new File(this.outDir, "roles.to.use");
        log.info("Writing useful roles to {}.", rolesToUseFile);
        try (PrintWriter roleWriter = new PrintWriter(rolesToUseFile)) {
            for (String role : finalRoles) {
                int[] foundCounts = this.roleFoundCounts.get(role);
                String foundList = Arrays.stream(foundCounts).mapToObj(x -> Integer.toString(x)).collect(Collectors.joining(","));
                roleWriter.format("%s\t%d\t%s%n", role, this.roleCounts.getCount(role), foundList);
            }
            roleWriter.flush();
        }
        // Get a list of all the genomes, ordered so as to provide a good testing/training balance.
        List<String> genomeList = this.getGenomes(finalRoles);
        // Now we write the training set.
        File trainingFile = new File(this.outDir, "training.tbl");
        log.info("Writing role matrix to {}.", trainingFile);
        try (PrintWriter trainWriter = new PrintWriter(trainingFile)) {
            // Start with the header line.
            trainWriter.println("genome_id\t" + StringUtils.join(finalRoles, '\t'));
            // Loop through the genomes.
            for (String genome : genomeList) {
                log.info("Writing genome {}.", genome);
                // Get the role counts in order, tab-separated.
                CountMap<String> gRoleCounts = this.genomeRoleCounts.get(genome);
                String counts = finalRoles.stream().map(x -> Integer.toString(gRoleCounts.getCount(x))).collect(Collectors.joining("\t"));
                // Write them out.
                trainWriter.println(genome + "\t" + counts);
            }
            trainWriter.flush();
        }
        log.info("All done. {} genomes output in training set", genomeList.size());
    }

    /**
     * This method produces a sorted list of genome IDs.  The genomes are ordered so as to provide a good testing
     * set at the front of the list.
     *
     * Currently, we shuffle the genomes in random order and then insure all the roles are represented near the top.
     *
     * @return a list of the IDs for the well-behaved genomes
     */
    private List<String> getGenomes(Set<String> finalRoles) {
        // First, shuffle all the genome IDs.
        List<String> retVal = new ArrayList<String>(this.genomeRoleCounts.keySet());
        Collections.shuffle(retVal);
        // Track the roles with representation in here.
        final Set<String> missingRoles = new HashSet<String>(finalRoles);
        // Loop until all the roles are represented or we hit the end of the genome ID list.
        for (int i = 0; i < retVal.size(); i++) {
            // Find a genome that represents a new role.
            boolean found = false;
            for (int j = i; ! found && j < retVal.size(); j++) {
                // Get this genome ID and its role counts.
                String genomeId = retVal.get(j);
                CountMap<String> gRoleCounts = this.genomeRoleCounts.get(genomeId);
                // Set to TRUE if there is a new role with a nonzero count.
                Set<String> represented = gRoleCounts.counts().stream()
                        .filter(x -> finalRoles.contains(x.getKey()))
                        .map(x -> x.getKey()).filter(r -> missingRoles.contains(r))
                        .collect(Collectors.toSet());
                found = ! represented.isEmpty();
                if (found) {
                    // This is the genome we want.  Swap it into position if necessary.
                    if (i != j) Collections.swap(retVal, i, j);
                    // Denote all of its roles are represented.
                    missingRoles.removeAll(represented);
                }
            }
            // If we have represented all the roles, start over.
            if (! found)
                missingRoles.addAll(finalRoles);
        }
        return retVal;
    }

    /**
     * Count the roles in the specified genome.  If a role occurs too many times, it will be removed
     * from the useful-role list.
     *
     * @param genome	genome whose roles are to be counted.
     *
     * @return a count map keyed on role ID
     */
    private CountMap<String> countRoles(Genome genome) {
        CountMap<String> retVal = new CountMap<String>();
        for (Feature feat : genome.getFeatures()) {
            for (Role role : feat.getUsefulRoles(this.roleMap)) {
                int count = retVal.count(role.getId());
                if (count > this.maxMulti) {
                    boolean removed = this.usefulRoles.remove(role.getId());
                    if (removed)
                        log.info("{} removed from roles.to.use due to too many occurrences in {}.", role, genome);
                }
            }
        }
        return retVal;
    }

    /**
     * @return TRUE if this genome is acceptable for use in the testing/training set
     *
     * @param genome	genome of interest
     */
    private boolean isWellBehaved(Genome genome) {
        boolean retVal = false;
        String domain = genome.getDomain();
        if (! this.domains.contains(domain))
            log.info("{} skipped:  incorrect domain {}.", genome, domain);
        else if (genome.getLength() < this.minSize)
            log.info("{} skipped:  genome is too short.", genome);
        else if (this.badGenomes.contains(genome.getId()))
            log.info("{} skipped:  known to be incomplete.", genome);
        else
            retVal = true;
        return retVal;
    }

}
