package org.theseed.core.utils;

import java.util.Arrays;

import org.theseed.utils.BaseProcessor;

/**
 * This module contains commands for maintaining CoreSEED.  The following commands are supported.
 *
 * updates		processing weekly updates
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
