/**
 *
 */
package org.theseed.proj.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.BaseReportProcessor;
import org.theseed.basic.ParseFailureException;
import org.theseed.utils.VersionNumber;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * This command scans the POM.XML files in a project directory and reports on the version numbers for each module.
 * Mismatches are flagged.
 *
 * The basic strategy is to create a map of components, based on the group ID concatentated in front of the
 * artifact ID.  For each component, we have a map from version string to the list of modules (identified by
 * the low-level directory name).  At the end, we sort the versions and list the modules with older versions.
 *
 * The positional parameter is the name of the project directory.
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -o	output file for report (if not STDOUT)
 *
 * @author Bruce Parrello
 *
 */
public class PomCheckProcessor extends BaseReportProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(PomCheckProcessor.class);
    /** map of project names to POM files */
    private SortedMap<String, File> projectMap;
    /** map of components to version/module lists */
    private Map<String, SortedMap<VersionNumber, List<String>>> componentMap;
    /** sorter for version number lists */
    private static final Comparator<? super VersionNumber> VERSION_SORTER = new VersionNumber.Reverse();

    // COMMAND-LINE OPTIONS

    /** project directory name */
    @Argument(index = 0, metaVar = "projDir", usage = "top-level directory containing projects in subdirectories")
    private File projDir;

    @Override
    protected void setReporterDefaults() {
    }

    @Override
    protected void validateReporterParms() throws IOException, ParseFailureException {
        // Verify the project directory.
        if (! this.projDir.isDirectory())
            throw new FileNotFoundException("Project directory " + this.projDir + " not found or invalid.");
        // Assemble a map of all the projects in the project directory.
        log.info("Scanning for projects in {}.", this.projDir);
        this.projectMap = new TreeMap<String, File>();
        for (File moduleDir : projDir.listFiles()) {
            if (moduleDir.isDirectory()) {
                // Here this could be a module directory.  CHeck for a pom.xml file.
                File pomFile = new File(moduleDir, "pom.xml");
                if (pomFile.exists()) {
                    String module = moduleDir.getName();
                    this.projectMap.put(module, pomFile);
                }
            }
        }
        log.info("{} projects found.", this.projectMap.size());
    }

    @Override
    protected void runReporter(PrintWriter writer) throws Exception {
        // Create the output map.
        this.componentMap = new TreeMap<String, SortedMap<VersionNumber, List<String>>>();
        // Loop through the modules.
        for (Map.Entry<String, File> modEntry : this.projectMap.entrySet()) {
            String module = modEntry.getKey();
            File pomFile = modEntry.getValue();
            log.info("Processing {} for {}.", pomFile, module);
            this.processPom(module, pomFile);
        }
        log.info("{} components found.", this.componentMap.size());
        writer.println("component\tuses\tbest_version\tmodules_to_check");
        for (Map.Entry<String, SortedMap<VersionNumber, List<String>>> componentEntry : this.componentMap.entrySet()) {
            String component = componentEntry.getKey();
            SortedMap<VersionNumber, List<String>> versionMap = componentEntry.getValue();
            // Determine the number of uses.
            int uses = versionMap.values().stream().mapToInt(x -> x.size()).reduce(0, (total, i) -> total + i);
            // Loop through the versions.  The key of the first version is the best version.  The values
            // of the other versions determine the modules we need to fix.
            Iterator<Map.Entry<VersionNumber, List<String>>> iter = versionMap.entrySet().iterator();
            // The first entry describes the best version.
            Map.Entry<VersionNumber, List<String>> curr = iter.next();
            String bestVersion = curr.getKey().toString();
            // The remaining entries are the modules we need to check.
            Set<String> badModules = new TreeSet<String>();
            while (iter.hasNext()) {
                curr = iter.next();
                badModules.addAll(curr.getValue());
            }
            // Write this component's data.
            writer.format("%s\t%d\t%s\t%s%n", component, uses, bestVersion, StringUtils.join(badModules, ", "));
        }
    }

    private void processPom(String module, File pomFile) throws SAXException, IOException, ParserConfigurationException {
        InputSource xmlSource = new InputSource(new FileReader(pomFile));
        Document pomDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(xmlSource);
        // Create a map for the properties.
        Map<String, String> propMap = buildPropMap(pomDoc);
        // Loop through all the dependencies.
        NodeList dependencies = pomDoc.getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
            Node dependency = dependencies.item(i);
            Node groupNode = this.getFirstChildOfType(dependency, "groupId");
            Node artifactNode = this.getFirstChildOfType(dependency, "artifactId");
            Node versionNode = this.getFirstChildOfType(dependency, "version");
            if (groupNode != null && artifactNode != null && versionNode != null) {
                // Here we have a valid dependency.
                String component = groupNode.getTextContent() + "." + artifactNode.getTextContent();
                String versionNumber = versionNode.getTextContent();
                if (StringUtils.startsWith(versionNumber, "${") && StringUtils.endsWith(versionNumber, "}")) {
                    // Here the version is a property value.
                    String propKey = StringUtils.substring(versionNumber, 2, versionNumber.length() - 1);
                       versionNumber = propMap.getOrDefault(propKey, versionNumber);
                }
                // Store this dependendency in the component map.
                Map<VersionNumber, List<String>> componentData =
                        this.componentMap.computeIfAbsent(component,
                                x -> new TreeMap<VersionNumber, List<String>>(VERSION_SORTER));
                VersionNumber version = new VersionNumber(versionNumber);
                List<String> modList = componentData.computeIfAbsent(version, x -> new ArrayList<String>());
                modList.add(module);
            }
        }
    }

    /**
     * Create a map of property names to values.
     *
     * @param pomDoc	document to parse
     *
     * @return a map giving the value of each named property
     */
    private Map<String, String> buildPropMap(Document pomDoc) {
        Map<String, String> retVal = new TreeMap<String, String>();
        NodeList properties = pomDoc.getElementsByTagName("properties");
        if (properties.getLength() > 0) {
            NodeList allProperties = properties.item(0).getChildNodes();
            for (int i = 0; i < allProperties.getLength(); i++) {
                Node property = allProperties.item(i);
                if (property.getNodeType() == Node.ELEMENT_NODE) {
                    String key = property.getNodeName();
                    String value = property.getTextContent();
                    retVal.put(key, value);
                }
            }
        }
        return retVal;
    }

    /**
     * This is a utility method to get the first element with the specified tag name.
     *
     * @param parent	source node
     * @param tagName	tag name to find
     *
     * @return the desired node, or NULL if there is none
     */
    private Node getFirstChildOfType(Node parent, String tagName) {
        NodeList children = ((Element) parent).getElementsByTagName(tagName);
        Node retVal = null;
        if (children.getLength() > 0)
            retVal = children.item(0);
        return retVal;
    }

}
