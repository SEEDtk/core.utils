/**
 *
 */
package org.theseed.core.utils;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.apache.commons.lang3.StringUtils;
import org.theseed.genome.Feature;
import org.theseed.genome.Genome;
import org.theseed.proteins.ProteinFinder;
import org.theseed.sequence.MD5Hex;

import junit.framework.TestCase;

public class TestProteinFinder extends TestCase {

    private class Tester implements ProteinFinder.Parms {

        @Override
        public File getGtoDir() {
            return new File("data", "gtos");
        }

        @Override
        public File getOrgDir() {
            return new File("data", "Organisms");
        }

    }

    public void testCoreFinder() {
        int counter883 = 0;
        boolean test2found = false;
        boolean test199found = false;
        ProteinFinder.Container coreProteins = ProteinFinder.Type.CORE.create(new Tester());
        for (ProteinFinder.Instance protein : coreProteins) {
            String fid = protein.getFid();
            String genome = Feature.genomeOf(fid);
            switch (genome) {
            case "217.1" :
                if (fid.contentEquals("fig|217.1.peg.2")) {
                    assertThat(protein.getMd5(), equalTo("d4c4752ddc76981378e9246d1c0b6949"));
                    assertThat(protein.getFunction(), equalTo("hypothetical protein"));
                    test2found = true;
                } else if (fid.contentEquals("fig|217.1.peg.199")) {
                    assertThat(protein.getFunction(), equalTo("Thioredoxin reductase (EC 1.8.1.9)"));
                    test199found = true;
                } else {
                    String digits = StringUtils.substringAfterLast(fid, ".");
                    int pegNum = Integer.valueOf(digits);
                    assertThat(pegNum, lessThanOrEqualTo(1663));
                }
                break;
            case "883.3" :
                assertThat(protein.getFunction().isEmpty(), equalTo(false));
                counter883++;
                break;
            default :
                fail("Invalid feature id:" + fid);
            }
        }
        assertThat(counter883, equalTo(3467));
        assertThat(test2found, equalTo(true));
        assertThat(test199found, equalTo(true));
    }

    public void testGtoFinder() throws IOException, NoSuchAlgorithmException {
        MD5Hex md5Computer = new MD5Hex();
        Genome gto217 = new Genome(new File("data", "gtos/217.1.gto"));
        Genome gto883 = new Genome(new File("data", "gtos/883.3.gto"));
        // Do an independent count of the good pegs in 883.
        int counter883 = 0;
        for (Feature feat : gto883.getPegs()) {
            if (feat.getFunction() != null && ! feat.getFunction().isEmpty() &&
                    feat.getProteinTranslation() != null && ! feat.getProteinTranslation().isEmpty())
                counter883++;
        }
        ProteinFinder.Container proteins = ProteinFinder.Type.GTO.create(new Tester());
        for (ProteinFinder.Instance protein : proteins) {
            String fid = protein.getFid();
            Genome genome = gto217;
            if (fid.contains("883.3.peg")) {
                genome = gto883;
                counter883--;
            }
            Feature feat = genome.getFeature(fid);
            assertThat(feat, notNullValue());
            assertThat(protein.getFunction(), equalTo(feat.getFunction()));
            String md5 = md5Computer.sequenceMD5(feat.getProteinTranslation());
            assertThat(protein.getMd5(), equalTo(md5));
        }
        assertThat(counter883, equalTo(0));
    }

}
