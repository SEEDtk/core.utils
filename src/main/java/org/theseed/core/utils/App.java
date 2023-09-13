package org.theseed.core.utils;

import java.util.Arrays;

import org.theseed.proj.utils.PomCheckProcessor;
import org.theseed.utils.BaseProcessor;

/**
 * This module contains commands for maintaining CoreSEED.  The following commands are supported.
 *
 * updates		process weekly updates
 * roles		create the roles.in.subsystems file
 * tables		create the tables required to build the evaluator
 * subList		produce a short subsystem summary report
 * compress		compress a role-coupling file
 * search		search for features by function pattern
 * csearch		search for coupled features in specific categories
 * proteins		use protein sequences to map CoreSEED functions to PATRIC functions
 * tablePage	build a static table web page
 * pom			pom version check
 * p3map		map genomes to identical-sequenced PATRIC genomes
 * functions	create a master list of all functions in CoreSEED
 * subCheck		validate the subsystem variant rules
 */
public class App
{
    public static void main( String[] args )
    {
        // Get the control parameter.
        String command = args[0];
        String[] newArgs = Arrays.copyOfRange(args, 1, args.length);
        BaseProcessor processor;
        // Determine the command to process.
        switch (command) {
        case "compress" :
            processor = new CouplingCompressProcessor();
            break;
        case "updates" :
            processor = new UpdateProcessor();
            break;
        case "roles" :
            processor = new RolesProcessor();
            break;
        case "subList" :
            processor = new SubsystemListProcessor();
            break;
        case "search" :
            processor = new FidSearchProcessor();
            break;
        case "proteins" :
            processor = new ProteinsProcessor();
            break;
        case "csearch" :
            processor = new CouplingSearchProcessor();
            break;
        case "tablePage" :
            processor = new TablePageProcessor();
            break;
        case "pom" :
            processor = new PomCheckProcessor();
            break;
        case "p3map" :
            processor = new P3MapProcessor();
            break;
        case "functions" :
            processor = new FunctionProcessor();
            break;
        case "subCheck" :
            processor = new SubsystemRuleCheckProcessor();
            break;
        default:
            throw new RuntimeException("Invalid command " + command);
        }
        // Process it.
        boolean ok = processor.parseCommand(newArgs);
        if (ok) {
            processor.run();
        }
    }
}
