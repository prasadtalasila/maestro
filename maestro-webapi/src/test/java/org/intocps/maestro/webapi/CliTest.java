package org.intocps.maestro.webapi;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public class CliTest {

    @Test
    public void oneShotTest() throws IOException, InterruptedException {

        Path outFile = Paths.get("target", CliTest.class.getName(), "output.csv");
        outFile.toFile().getParentFile().mkdirs();
        double startTimeIn = 0;
        double endTimeIn = 10;

        String arguments = String.format(Locale.US, "--oneshot --configuration %s --starttime %f --endtime %f --result %s",
                Paths.get("src", "test", "resources", "cli-test", "config.json").toFile().getAbsolutePath(), startTimeIn, endTimeIn,
                outFile.toString());
        String[] s = arguments.split(" ");

        Application.main(s);
    }
}
