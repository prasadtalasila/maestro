package org.intocps.maestro;

import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

public class Main {

    final static Logger logger = LoggerFactory.getLogger(Main.class);

    private static void showHelp(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("coe", options);
    }

    private static String getVersion() {
        try {
            Properties prop = new Properties();
            InputStream coeProp = Main.class.getResourceAsStream("/coe.properties");
            prop.load(coeProp);
            return prop.getProperty("version");
        } catch (Exception e) {
            return "";
        }
    }

    public static void main(String[] args) throws IOException {
        Option helpOpt = Option.builder("h").longOpt("help").desc("Show this description").build();
        Option verboseOpt = Option.builder("v").longOpt("verbose").desc("Verbose").build();
        Option versionOpt = Option.builder("version").longOpt("version").desc("Version").build();
        Option mablOpt = Option.builder("m").longOpt("mabl").desc("Path to Mabl files").hasArg().numberOfArgs(1000).valueSeparator(' ')
                .argName("path").build();

        Options options = new Options();
        options.addOption(helpOpt);
        options.addOption(mablOpt);
        options.addOption(verboseOpt);
        options.addOption(versionOpt);

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e1) {
            System.err.println("Parsing failed. Reason: " + e1.getMessage());
            showHelp(options);
            return;
        }

        if (cmd.hasOption(helpOpt.getOpt())) {
            showHelp(options);
            return;
        }

        if (cmd.hasOption(versionOpt.getOpt())) {
            System.out.println(getVersion());
            return;
        }

        boolean verbose = cmd.hasOption(verboseOpt.getOpt());

        List<File> sourceFiles = Arrays.stream(cmd.getOptionValues(mablOpt.getOpt())).map(File::new).collect(Collectors.toList());
        new MableSpecificationGenerator(verbose).generate(sourceFiles);
    }
}