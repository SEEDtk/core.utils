package org.theseed.core.utils;

import java.util.Arrays;

import org.theseed.utils.BaseProcessor;

/**
 * This module contains commands for maintaining CoreSEED.  The following commands are supported.
 *
 * updates		process weekly updates
 * roles		create the roles.in.subsystems file
 * tables		create the tables required to build the evaluator
 * subList		produce a short subsystem summary report
 *
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
        case "updates" :
            processor = new UpdateProcessor();
            break;
        case "roles" :
            processor = new RolesProcessor();
            break;
        case "subList" :
            processor = new SubsystemListProcessor();
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
