package org.intocps.maestro.interpreter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spencerwi.either.Either;
import org.intocps.maestro.ast.analysis.AnalysisException;
import org.intocps.maestro.interpreter.values.*;
import org.intocps.maestro.interpreter.values.csv.CSVValue;
import org.intocps.maestro.interpreter.values.csv.CsvDataWriter;
import org.intocps.maestro.interpreter.values.datawriter.DataWriterValue;
import org.intocps.maestro.interpreter.values.fmi.FmuValue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Default interpreter factory with framework support and other basic features.
 * This class provides run-time support only. It creates and destroys certain types based on load and unload
 */
public class DefaultExternalValueFactory implements IExternalValueFactory {
    static final String DEFAULT_CSV_FILENAME = "outputs.csv";
    protected final String DATA_WRITER_TYPE_NAME = "DataWriter";
    protected final String MATH_TYPE_NAME = "Math";
    protected HashMap<String, Function<List<Value>, Either<Exception, Value>>> instantiators;

    public DefaultExternalValueFactory() throws IOException {
        this(null, null);
    }

    public DefaultExternalValueFactory(File workingDirectory, InputStream config) throws IOException {


        String dataWriterFileName = DEFAULT_CSV_FILENAME;
        List<String> dataWriterFilter = null;

        if (config != null) {
            JsonNode configTree = new ObjectMapper().readTree(config);

            if (configTree.has(DATA_WRITER_TYPE_NAME)) {
                JsonNode dwConfig = configTree.get(DATA_WRITER_TYPE_NAME);

                for (JsonNode val : dwConfig) {
                    if (val.has("type") && val.get("type").equals("CSV")) {
                        dataWriterFileName = val.get("filename").asText();

                        dataWriterFilter =
                                StreamSupport.stream(Spliterators.spliteratorUnknownSize(val.get("filter").iterator(), Spliterator.ORDERED), false)
                                        .map(v -> v.asText()).collect(Collectors.toList());
                    }
                }
            }
        }

        final String dataWriterFileNameFinal = dataWriterFileName;
        final List<String> dataWriterFilterFinal = dataWriterFilter;


        instantiators = new HashMap<>() {{
            put("FMI2", args -> {
                String guid = ((StringValue) args.get(0)).getValue();
                String path = ((StringValue) args.get(1)).getValue();
                try {
                    path = (new URI(path)).getRawPath();
                } catch (URISyntaxException e) {
                    return Either.left(new AnalysisException("The path passed to load is not a URI", e));
                }
                return Either.right(new FmiInterpreter(workingDirectory).createFmiValue(path, guid));
            });
            put("CSV", args -> Either.right(new CSVValue()));
            put("Logger", args -> Either.right(new LoggerValue()));
            put(DATA_WRITER_TYPE_NAME, args -> Either.right(new DataWriterValue(Collections.singletonList(new CsvDataWriter(
                    workingDirectory == null ? new File(dataWriterFileNameFinal) : new File(workingDirectory, dataWriterFileNameFinal),
                    dataWriterFilterFinal)))));


            put(MATH_TYPE_NAME, args -> Either.right(new MathValue()));
        }};
    }


    @Override
    public boolean supports(String type) {
        return this.instantiators.containsKey(type);
    }

    @Override
    public Either<Exception, Value> create(String type, List<Value> args) {
        return this.instantiators.get(type).apply(args);
    }

    @Override
    public Value destroy(Value value) {
        if (value instanceof FmuValue) {
            FmuValue fmuVal = (FmuValue) value;
            FunctionValue unloadFunction = (FunctionValue) fmuVal.lookup("unload");
            return unloadFunction.evaluate(Collections.emptyList());
        } else if (value instanceof CSVValue) {
            return new VoidValue();
        } else if (value instanceof LoggerValue) {
            return new VoidValue();
        } else if (value instanceof DataWriterValue) {
            return new VoidValue();
        } else if (value instanceof MathValue) {
            return new VoidValue();
        }

        throw new InterpreterException("UnLoad of unknown type: " + value);
    }
}
